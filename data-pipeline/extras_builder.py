#!/usr/bin/env python3
"""
RahYab Extra Offline Data Builder (Phase X: X1-X4)
--------------------------------------------------
Stdlib-only Python module (zero new pip dependencies) that provides:
  - X1: Optional Valhalla elevation tile baking (`valhalla_build_elevation`)
        plus one-shot `valhalla_service` test route verification with clean fallback.
  - X2: FRA highway-rail grade crossings (`m2f8-22s6`) into `<pack>_extras.db`.
  - X3: HPMS 2024 speed limits (`42um-tgh5`) into `<pack>_extras.db` with bbox index.
  - X4: `meta` table (`schema_version=1`, `built_at`, `sources`, `licenses`).

Safety rule: Every extra step is non-fatal. Any network/schema/tool error prints
`[warn] extras step <name> skipped: <reason>` and continues cleanly without breaking
the core pack build or touching `<pack>_places.db`.
"""

from __future__ import annotations

import datetime
import json
import math
import os
import shutil
import sqlite3
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

# ~30-mile buffer in degrees (matches build_state_pack.py)
ELEVATION_BUFFER_LAT = 0.45
ELEVATION_BUFFER_LON = 0.55

# X1: Default Oregon verification route (Eugene -> Portland)
OREGON_TEST_ORIGIN = (44.0521, -123.0868)
OREGON_TEST_DEST = (45.5152, -122.6784)
OREGON_TEST_MIN_DURATION_SEC = 90 * 60
OREGON_TEST_MAX_DURATION_SEC = 180 * 60


def compute_elevation_bbox(
    bbox: tuple[float, float, float, float],
    pad_lon: float = ELEVATION_BUFFER_LON,
    pad_lat: float = ELEVATION_BUFFER_LAT,
) -> tuple[int, int, int, int]:
    """Expand pack bbox by ~30 mi and snap outward to integer degree tile bounds."""
    min_lon, min_lat, max_lon, max_lat = bbox
    return (
        max(-180, math.floor(min_lon - pad_lon)),
        max(-90, math.floor(min_lat - pad_lat)),
        min(180, math.ceil(max_lon + pad_lon)),
        min(90, math.ceil(max_lat + pad_lat)),
    )


def build_elevation_tiles(
    pack_code: str,
    bbox: tuple[float, float, float, float],
    elevation_dir: Path,
    timeout_sec: int = 900,
) -> Path | None:
    """
    X1: Download AWS Open Data terrain tiles into `elevation_dir` via `valhalla_build_elevation`.
    Elevation tiles are kept in cache/work dir ONLY during `valhalla_build_tiles` so road
    grades get baked into Valhalla routing tiles; they are NEVER shipped to the phone.
    """
    tool = shutil.which("valhalla_build_elevation")
    if not tool:
        print("[warn] extras step elevation skipped: valhalla_build_elevation not found in PATH", flush=True)
        return None

    min_x, min_y, max_x, max_y = compute_elevation_bbox(bbox)
    elevation_dir.mkdir(parents=True, exist_ok=True)
    cmd = [tool, f"--from-bbox={min_x},{min_y},{max_x},{max_y}", "--outdir", str(elevation_dir), "-p", str(os.cpu_count() or 4), "-v"]
    print(f"[extras] Building elevation tiles for {pack_code.upper()} ({min_x},{min_y}..{max_x},{max_y})", flush=True)
    try:
        res = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            errors="replace",
            timeout=timeout_sec,
        )
        if res.returncode != 0:
            tail = " | ".join((res.stderr or res.stdout or "").splitlines()[-5:])
            print(f"[warn] extras step elevation skipped: exit {res.returncode}: {tail}", flush=True)
            return None
        has_tiles = any(elevation_dir.rglob("*.hgt")) or any(elevation_dir.rglob("*.hgt.gz"))
        if not has_tiles:
            # Some valhalla versions store tiles in nested numeric dirs without .hgt extension
            has_tiles = any(p.is_file() for p in elevation_dir.rglob("*"))
        if not has_tiles:
            print("[warn] extras step elevation skipped: no elevation tiles downloaded", flush=True)
            return None
        return elevation_dir
    except Exception as exc:
        print(f"[warn] extras step elevation skipped: {exc}", flush=True)
        return None


def validate_test_route_response(
    route_payload: dict[str, Any],
    trace_payload: dict[str, Any] | None = None,
    min_duration_sec: float = OREGON_TEST_MIN_DURATION_SEC,
    max_duration_sec: float = OREGON_TEST_MAX_DURATION_SEC,
) -> tuple[bool, str]:
    """
    Pure validation for the X1 one-shot `valhalla_service` test route response.
    Checks:
      1. Route found with non-empty legs and summary.
      2. Duration within expected bounds (90-180 min for Eugene -> Portland).
      3. Maneuvers present (>= 2).
      4. At least 1 maneuver or traced edge carries lane data.
    """
    trip = route_payload.get("trip")
    if not isinstance(trip, dict):
        return False, "missing trip object"
    legs = trip.get("legs")
    if not isinstance(legs, list) or not legs:
        return False, "no route legs returned"

    summary = trip.get("summary") or {}
    duration_sec = float(summary.get("time", 0.0) or 0.0)
    length_km = float(summary.get("length", 0.0) or 0.0)
    if duration_sec < min_duration_sec or duration_sec > max_duration_sec:
        return (
            False,
            f"duration {duration_sec / 60.0:.1f} min outside [{min_duration_sec / 60.0:.0f}, {max_duration_sec / 60.0:.0f}] min",
        )

    maneuvers: list[dict[str, Any]] = []
    for leg in legs:
        if isinstance(leg, dict) and isinstance(leg.get("maneuvers"), list):
            maneuvers.extend(m for m in leg["maneuvers"] if isinstance(m, dict))
    if len(maneuvers) < 2:
        return False, f"too few maneuvers ({len(maneuvers)})"

    lanes_count = 0
    for m in maneuvers:
        if m.get("lanes") or m.get("turn_lanes"):
            lanes_count += 1
    if isinstance(trace_payload, dict):
        for edge in trace_payload.get("edges") or []:
            if isinstance(edge, dict) and (edge.get("lanes") or edge.get("turn_lanes") or edge.get("lane_count", 0)):
                lanes_count += 1

    if lanes_count < 1:
        return False, "no maneuver or edge had lane data"

    return (
        True,
        f"duration={duration_sec / 60.0:.1f}min length={length_km:.1f}km maneuvers={len(maneuvers)} lanes_hits={lanes_count}",
    )


def verify_valhalla_test_route(pack_code: str, config_path: Path) -> bool:
    """
    X1: Run one-shot `valhalla_service <config> route <json>` to verify freshly built tiles.
    For Oregon (`or`), verifies Eugene (44.0521,-123.0868) -> Portland (45.5152,-122.6784)
    in 90-180 min with maneuvers and lane data. Returns True if valid or skipped (non-OR /
    missing binary), False ONLY when `valhalla_service` runs and fails verification.
    """
    if pack_code.lower() != "or":
        print(f"[check] test route skipped for non-OR pack {pack_code.upper()}", flush=True)
        return True

    service_bin = shutil.which("valhalla_service")
    if not service_bin:
        print("[warn] extras step test_route skipped: valhalla_service not found in PATH", flush=True)
        return True

    req = {
        "locations": [
            {"lat": OREGON_TEST_ORIGIN[0], "lon": OREGON_TEST_ORIGIN[1]},
            {"lat": OREGON_TEST_DEST[0], "lon": OREGON_TEST_DEST[1]},
        ],
        "costing": "auto",
        "directions_options": {"units": "kilometers"},
        "alternates": 0,
    }
    try:
        res = subprocess.run(
            [service_bin, str(config_path), "route", json.dumps(req)],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=120,
        )
        if res.returncode != 0:
            tail = " | ".join((res.stderr or res.stdout or "").splitlines()[-5:])
            print(f"[check] test route failed (exit {res.returncode}): {tail}", flush=True)
            return False

        raw_out = (res.stdout or "").strip()
        json_start = raw_out.find("{")
        if json_start < 0:
            print("[check] test route failed: no JSON output from valhalla_service", flush=True)
            return False
        route_payload = json.loads(raw_out[json_start:])

        # Optional one-shot trace_attributes for lane count verification if maneuvers omit lanes
        trace_payload = None
        legs = (route_payload.get("trip") or {}).get("legs") or []
        shape = legs[0].get("shape") if legs and isinstance(legs[0], dict) else None
        if shape:
            trace_req = {
                "encoded_polyline": shape,
                "shape_match": "walk_or_snap",
                "costing": "auto",
                "filters": {
                    "attributes": [
                        "edge.lane_count",
                        "edge.max_upward_grade",
                        "edge.max_downward_grade",
                        "edge.weighted_grade",
                    ],
                    "action": "include",
                },
            }
            t_res = subprocess.run(
                [service_bin, str(config_path), "trace_attributes", json.dumps(trace_req)],
                capture_output=True,
                text=True,
                errors="replace",
                timeout=120,
            )
            if t_res.returncode == 0 and t_res.stdout:
                t_start = t_res.stdout.find("{")
                if t_start >= 0:
                    trace_payload = json.loads(t_res.stdout[t_start:])

        ok, summary = validate_test_route_response(route_payload, trace_payload)
        status = "PASS" if ok else "FAIL"
        print(f"[check] test route Eugene->Portland {status}: {summary}", flush=True)
        return ok
    except Exception as exc:
        print(f"[check] test route error: {exc}", flush=True)
        return False


def build_extras_db(
    pack_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    out_db_path: Path,
) -> Path | None:
    """
    X4: Build separate `<pack>_extras.db` containing `meta`, `fra_crossings`, and `hpms_speed`.
    NEVER touches `<pack>_places.db`. Each external data source is wrapped in try/except so
    any failure logs `[warn] extras step <name> skipped: <reason>` and continues cleanly.
    """
    from extras_fra_hpms import (
        init_extras_schema,
        populate_fra_crossings,
        populate_hpms_speed,
    )
    from extras_gnis import init_gnis_schema, populate_gnis_names
    from extras_tiger import init_tiger_schema, populate_tiger_ranges

    tmp_path = out_db_path.with_suffix(".db.tmp")
    tmp_path.unlink(missing_ok=True)
    out_db_path.parent.mkdir(parents=True, exist_ok=True)

    sources_used: list[str] = []
    fra_count = 0
    hpms_count = 0
    tiger_count = 0
    gnis_count = 0

    try:
        conn = sqlite3.connect(str(tmp_path))
        try:
            init_extras_schema(conn)
            init_tiger_schema(conn)
            init_gnis_schema(conn)

            try:
                fra_count = populate_fra_crossings(conn, state_abbr, bbox)
                if fra_count > 0:
                    sources_used.append("USDOT FRA m2f8-22s6")
            except Exception as exc:
                print(f"[warn] extras step fra_crossings skipped: {type(exc).__name__}: {exc}", flush=True)

            try:
                hpms_count = populate_hpms_speed(conn, state_abbr, bbox, include_lanes=True)
                if hpms_count > 0:
                    sources_used.append("FHWA HPMS 42um-tgh5")
            except Exception as exc:
                print(f"[warn] extras step hpms_speed skipped: {type(exc).__name__}: {exc}", flush=True)
            hpms_lanes_count = conn.execute("SELECT COUNT(*) FROM hpms_lanes").fetchone()[0]

            try:
                tiger_count = populate_tiger_ranges(conn, pack_code, state_abbr, bbox)
                if tiger_count > 0:
                    sources_used.append("US Census TIGER/Line ADDRFEAT")
            except Exception as exc:
                print(f"[warn] extras step tiger_ranges skipped: {type(exc).__name__}: {exc}", flush=True)

            try:
                gnis_count = populate_gnis_names(conn, state_abbr, bbox)
                if gnis_count > 0:
                    sources_used.append("USGS GNIS Domestic Names")
            except Exception as exc:
                print(f"[warn] extras step gnis_names skipped: {type(exc).__name__}: {exc}", flush=True)

            built_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
            meta_rows = [
                ("schema_version", "2"),
                ("pack_code", pack_code.lower()),
                ("state_abbr", state_abbr.upper()),
                ("built_at", built_at),
                ("sources", ", ".join(sources_used) if sources_used else "none"),
                ("licenses", "Public Domain (USDOT FRA, FHWA HPMS, US Census TIGER/Line, USGS GNIS)"),
                ("fra_count", str(fra_count)),
                ("hpms_count", str(hpms_count)),
                ("hpms_lanes_count", str(hpms_lanes_count)),
                ("tiger_count", str(tiger_count)),
                ("gnis_count", str(gnis_count)),
            ]
            conn.executemany(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                meta_rows,
            )
            conn.commit()
        finally:
            conn.close()

        tmp_path.replace(out_db_path)
        print(
            f"[extras] Wrote {out_db_path.name} "
            f"(fra={fra_count}, hpms={hpms_count}, hpms_lanes={hpms_lanes_count}, "
            f"tiger={tiger_count}, gnis={gnis_count}, bytes={out_db_path.stat().st_size})",
            flush=True,
        )
        return out_db_path
    except Exception as exc:
        print(f"[warn] extras step build_extras_db skipped: {type(exc).__name__}: {exc}", flush=True)
        tmp_path.unlink(missing_ok=True)
        return None
