#!/usr/bin/env python3
"""
RahYab Extras SQLite Builders: FRA Rail Crossings (X2) & HPMS Speed Limits (X3)
--------------------------------------------------------------------------------
Stdlib-only Python (urllib.request, json, sqlite3, math). No new pip packages.
"""

from __future__ import annotations

import datetime
import json
import math
import sqlite3
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

FRA_DATASET_ID = "m2f8-22s6"
FRA_COLUMNS_URL = f"https://data.transportation.gov/api/views/{FRA_DATASET_ID}/columns.json"
FRA_RESOURCE_URL = f"https://data.transportation.gov/resource/{FRA_DATASET_ID}.json"

HTTP_USER_AGENT = "RahYab-Pipeline/25.0"

US_STATE_FIPS: dict[str, str] = {
    "AL": "01", "AK": "02", "AZ": "04", "AR": "05", "CA": "06",
    "CO": "08", "CT": "09", "DE": "10", "DC": "11", "FL": "12",
    "GA": "13", "HI": "15", "ID": "16", "IL": "17", "IN": "18",
    "IA": "19", "KS": "20", "KY": "21", "LA": "22", "ME": "23",
    "MD": "24", "MA": "25", "MI": "26", "MN": "27", "MS": "28",
    "MO": "29", "MT": "30", "NE": "31", "NV": "32", "NH": "33",
    "NJ": "34", "NM": "35", "NY": "36", "NC": "37", "ND": "38",
    "OH": "39", "OK": "40", "OR": "41", "PA": "42", "RI": "44",
    "SC": "45", "SD": "46", "TN": "47", "TX": "48", "UT": "49",
    "VT": "50", "VA": "51", "WA": "53", "WV": "54", "WI": "55",
    "WY": "56",
}


def http_get_json(url: str, timeout_sec: int = 45) -> Any:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": HTTP_USER_AGENT, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
        return json.loads(resp.read().decode("utf-8"))


def find_column_field(columns: list[dict[str, Any]], candidates: tuple[str, ...]) -> str | None:
    """Dynamically locate a Socrata column `fieldName` by exact or substring match."""
    fields: list[tuple[str, str]] = []
    for col in columns:
        if not isinstance(col, dict):
            continue
        fn = str(col.get("fieldName") or "").strip()
        name = str(col.get("name") or "").strip()
        if fn:
            fields.append((fn, f"{fn} {name}".lower()))

    # 1. Exact lowercase fieldName match
    for cand in candidates:
        c = cand.lower()
        for fn, _ in fields:
            if fn.lower() == c:
                return fn
    # 2. Substring match on fieldName or human name
    for cand in candidates:
        c = cand.lower()
        for fn, combined in fields:
            if c in fn.lower() or c in combined:
                return fn
    return None


def _to_float(val: Any) -> float | None:
    if val is None or val == "":
        return None
    try:
        out = float(val)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def _to_int(val: Any, default: int = 0) -> int:
    f = _to_float(val)
    return int(round(f)) if f is not None else default


def _to_bool_int(val: Any) -> int:
    if val is None:
        return 0
    if isinstance(val, bool):
        return 1 if val else 0
    s = str(val).strip().lower()
    if not s or s in ("0", "no", "n", "false", "none", "unknown", "na", "n/a"):
        return 0
    f = _to_float(s)
    if f is not None:
        return 1 if f > 0 else 0
    return 1 if s in ("yes", "y", "true", "gates", "flashing lights", "both") else 0


def discover_fra_columns(columns: list[dict[str, Any]]) -> dict[str, str | None]:
    """Resolve FRA crossing field names dynamically from Socrata columns.json."""
    return {
        "crossing_id": find_column_field(columns, ("crossingid", "crossing_id", "crossing id", "dot_crossing")),
        "lat": find_column_field(columns, ("latitude", "lat")),
        "lon": find_column_field(columns, ("longitude", "lon", "long")),
        "street": find_column_field(columns, ("street", "highway", "roadway", "street_name")),
        "railroad": find_column_field(columns, ("railroad", "railroad_name", "rr_name", "reporting_agency")),
        "gates": find_column_field(columns, ("gates", "gate_arms", "quad_gates", "has_gates", "highway_gates")),
        "lights": find_column_field(columns, ("flashing_lights", "flashers", "cantilever", "lights", "bells", "wdcode")),
        "wdcode": find_column_field(columns, ("wdcode", "warning_device_code", "warning_device")),
        "main_tracks": find_column_field(columns, ("maintrk", "main_tracks", "total_tracks", "tracks")),
        "other_tracks": find_column_field(columns, ("othrtrk", "other_tracks", "siding_tracks")),
        "day_trains": find_column_field(columns, ("daythru", "day_thru_trains", "total_trains", "daily_trains")),
        "night_trains": find_column_field(columns, ("nghtthru", "night_thru_trains", "switching_trains")),
        "closed": find_column_field(columns, ("crossingclosed", "crossing_closed", "closed", "status")),
        "position": find_column_field(columns, ("posxing", "crossing_position", "position")),
        "type_xing": find_column_field(columns, ("typexing", "crossing_type", "type_of_crossing", "public_private")),
        "state": find_column_field(columns, ("stateab", "state_abbr", "statecd", "state_code", "state")),
    }


def parse_fra_row(
    row: dict[str, Any],
    col_map: dict[str, str | None],
    bbox: tuple[float, float, float, float],
) -> tuple[str, float, float, str, str, int, int, int, int] | None:
    """
    Validate and transform one FRA Socrata row into a `fra_crossings` tuple:
    (crossing_id, lat, lon, street, railroad, has_gates, has_lights, tracks, daily_trains).
    Filters strictly to open, at-grade, public crossings inside `bbox`.
    """
    cid_f = col_map.get("crossing_id")
    lat_f = col_map.get("lat")
    lon_f = col_map.get("lon")
    if not cid_f or not lat_f or not lon_f:
        return None

    crossing_id = str(row.get(cid_f) or "").strip()
    lat = _to_float(row.get(lat_f))
    lon = _to_float(row.get(lon_f))
    if not crossing_id or lat is None or lon is None:
        return None

    min_lon, min_lat, max_lon, max_lat = bbox
    if not (min_lat <= lat <= max_lat and min_lon <= lon <= max_lon):
        return None

    # Filter: must be open (not closed)
    closed_f = col_map.get("closed")
    if closed_f and closed_f in row:
        cval = str(row.get(closed_f) or "").strip().lower()
        if cval in ("yes", "y", "true", "1", "closed"):
            return None

    # Filter: must be at-grade (FRA posxing == "1" or "at grade")
    pos_f = col_map.get("position")
    if pos_f and pos_f in row:
        pval = str(row.get(pos_f) or "").strip().lower()
        if pval and not (pval == "1" or "at grade" in pval or "at-grade" in pval):
            return None

    # Filter: must be public (FRA typexing == "3" or contains "public")
    type_f = col_map.get("type_xing")
    if type_f and type_f in row:
        tval = str(row.get(type_f) or "").strip().lower()
        if tval and not (tval == "3" or "public" in tval):
            return None

    street = str(row.get(col_map["street"]) or "").strip() if col_map.get("street") else ""
    railroad = str(row.get(col_map["railroad"]) or "").strip() if col_map.get("railroad") else ""

    has_gates = _to_bool_int(row.get(col_map["gates"])) if col_map.get("gates") else 0
    has_lights = _to_bool_int(row.get(col_map["lights"])) if col_map.get("lights") else 0

    # FRA warning device code (wdcode): 8/9 = gates (implies lights too), 7 = flashing lights
    wd_f = col_map.get("wdcode")
    if wd_f and wd_f in row:
        wd = str(row.get(wd_f) or "").strip().lower()
        if wd in ("8", "9") or "gate" in wd:
            has_gates = 1
            has_lights = 1
        elif wd == "7" or "flash" in wd or "light" in wd:
            has_lights = 1
    if has_gates == 1:
        has_lights = 1

    main_t = _to_int(row.get(col_map["main_tracks"]), 0) if col_map.get("main_tracks") else 0
    other_t = _to_int(row.get(col_map["other_tracks"]), 0) if col_map.get("other_tracks") else 0
    tracks = max(0, main_t + other_t)

    day_tr = _to_int(row.get(col_map["day_trains"]), 0) if col_map.get("day_trains") else 0
    night_tr = _to_int(row.get(col_map["night_trains"]), 0) if col_map.get("night_trains") else 0
    daily_trains = max(0, day_tr + night_tr)

    return (crossing_id, lat, lon, street, railroad, has_gates, has_lights, tracks, daily_trains)


def init_extras_schema(conn: sqlite3.Connection) -> None:
    """Create the `<state>_extras.db` schema (meta, fra_crossings, hpms_speed)."""
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS fra_crossings (
            crossing_id TEXT PRIMARY KEY,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            street TEXT NOT NULL,
            railroad TEXT NOT NULL,
            has_gates INTEGER NOT NULL,
            has_lights INTEGER NOT NULL,
            tracks INTEGER NOT NULL,
            daily_trains INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_fra_crossings_lat_lon
            ON fra_crossings(lat, lon);

        CREATE TABLE IF NOT EXISTS hpms_speed (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            speed_mph INTEGER NOT NULL,
            route_name TEXT NOT NULL,
            min_lat REAL NOT NULL,
            min_lon REAL NOT NULL,
            max_lat REAL NOT NULL,
            max_lon REAL NOT NULL,
            polyline TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_hpms_speed_bbox
            ON hpms_speed(min_lat, max_lat, min_lon, max_lon);
        """
    )


def populate_fra_crossings(
    conn: sqlite3.Connection,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    page_size: int = 20000,
) -> int:
    """
    X2: Fetch FRA rail crossings (`m2f8-22s6`) dynamically via Socrata columns.json
    and insert open, at-grade, public crossings inside `bbox` into `fra_crossings`.
    """
    columns = http_get_json(FRA_COLUMNS_URL)
    if not isinstance(columns, list):
        raise RuntimeError("FRA columns.json did not return a list")

    col_map = discover_fra_columns(columns)
    lat_f = col_map.get("lat")
    lon_f = col_map.get("lon")
    if not col_map.get("crossing_id") or not lat_f or not lon_f:
        raise RuntimeError(f"FRA required columns missing: {col_map}")

    min_lon, min_lat, max_lon, max_lat = bbox
    where_clause = (
        f"{lat_f} >= {min_lat:.6f} AND {lat_f} <= {max_lat:.6f} "
        f"AND {lon_f} >= {min_lon:.6f} AND {lon_f} <= {max_lon:.6f}"
    )

    inserted = 0
    offset = 0
    while True:
        params = urllib.parse.urlencode(
            {
                "$where": where_clause,
                "$limit": str(page_size),
                "$offset": str(offset),
            }
        )
        rows = http_get_json(f"{FRA_RESOURCE_URL}?{params}")
        if not isinstance(rows, list) or not rows:
            break

        batch: list[tuple[str, float, float, str, str, int, int, int, int]] = []
        for row in rows:
            if isinstance(row, dict):
                parsed = parse_fra_row(row, col_map, bbox)
                if parsed is not None:
                    batch.append(parsed)

        if batch:
            conn.executemany(
                """
                INSERT OR REPLACE INTO fra_crossings
                    (crossing_id, lat, lon, street, railroad, has_gates, has_lights, tracks, daily_trains)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batch,
            )
            inserted += len(batch)

        if len(rows) < page_size:
            break
        offset += page_size

    conn.commit()
    print(f"[check] fra_crossings count={inserted} state={state_abbr.upper()}", flush=True)
    return inserted


HPMS_DATASET_ID = "42um-tgh5"
HPMS_COLUMNS_URL = f"https://data.transportation.gov/api/views/{HPMS_DATASET_ID}/columns.json"
HPMS_RESOURCE_URL = f"https://data.transportation.gov/resource/{HPMS_DATASET_ID}.json"


def discover_hpms_columns(columns: list[dict[str, Any]]) -> dict[str, str | None]:
    """Resolve HPMS 2024 speed-limit field names dynamically from Socrata columns.json."""
    geom_field = None
    for col in columns:
        if not isinstance(col, dict):
            continue
        dt = str(col.get("dataTypeName") or "").lower()
        fn = str(col.get("fieldName") or "").strip()
        if fn and dt in ("multiline", "line", "geoshape", "geometry", "polygon", "location"):
            geom_field = fn
            break
    if not geom_field:
        geom_field = find_column_field(
            columns, ("the_geom", "geo_shape", "geometry", "geom", "shape", "wkt", "polyline")
        )
    return {
        "speed": find_column_field(
            columns, ("speed_limit", "speedlimit", "speed_mph", "posted_speed", "value_numeric", "speed")
        ),
        "state": find_column_field(
            columns, ("state_code", "state_id", "statecd", "state_fips", "state_abbr", "state")
        ),
        "route_name": find_column_field(
            columns, ("route_id", "route_name", "route_number", "road_name", "street", "facility")
        ),
        "geometry": geom_field,
    }


def _perp_dist_deg(
    pt: tuple[float, float],
    start: tuple[float, float],
    end: tuple[float, float],
) -> float:
    lat, lon = pt
    lat1, lon1 = start
    lat2, lon2 = end
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    denom = dlat * dlat + dlon * dlon
    if denom <= 1e-18:
        return math.hypot(lat - lat1, lon - lon1)
    t = max(0.0, min(1.0, ((lat - lat1) * dlat + (lon - lon1) * dlon) / denom))
    proj_lat = lat1 + t * dlat
    proj_lon = lon1 + t * dlon
    return math.hypot(lat - proj_lat, lon - proj_lon)


def simplify_polyline(
    points: list[tuple[float, float]],
    tolerance_deg: float = 0.00008,
) -> list[tuple[float, float]]:
    """Douglas-Peucker line simplification on (lat, lon) points (~8 m default tolerance)."""
    if len(points) <= 2:
        return list(points)
    max_dist = -1.0
    max_idx = 0
    for i in range(1, len(points) - 1):
        d = _perp_dist_deg(points[i], points[0], points[-1])
        if d > max_dist:
            max_dist = d
            max_idx = i
    if max_dist > tolerance_deg:
        left = simplify_polyline(points[: max_idx + 1], tolerance_deg)
        right = simplify_polyline(points[max_idx:], tolerance_deg)
        return left[:-1] + right
    return [points[0], points[-1]]


def _encode_signed_varint(value: int) -> str:
    v = ~(value << 1) if value < 0 else (value << 1)
    chars: list[str] = []
    while v >= 0x20:
        chars.append(chr((0x20 | (v & 0x1F)) + 63))
        v >>= 5
    chars.append(chr(v + 63))
    return "".join(chars)


def encode_polyline6(points: list[tuple[float, float]]) -> str:
    """Encode a list of (lat, lon) pairs using Google/Valhalla polyline6 (1e6 precision)."""
    out: list[str] = []
    prev_lat = 0
    prev_lon = 0
    for lat, lon in points:
        ilat = int(round(lat * 1_000_000.0))
        ilon = int(round(lon * 1_000_000.0))
        out.append(_encode_signed_varint(ilat - prev_lat))
        out.append(_encode_signed_varint(ilon - prev_lon))
        prev_lat = ilat
        prev_lon = ilon
    return "".join(out)


def _coords_lonlat_to_latlon(coords: list[Any]) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for c in coords:
        if isinstance(c, (list, tuple)) and len(c) >= 2:
            lon = _to_float(c[0])
            lat = _to_float(c[1])
            if lat is not None and lon is not None and -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0:
                pts.append((lat, lon))
    return pts


def extract_polylines_from_geometry(geom: Any) -> list[list[tuple[float, float]]]:
    """Extract one or more (lat, lon) polylines from GeoJSON dict/string or WKT LINESTRING."""
    if geom is None:
        return []
    if isinstance(geom, str):
        s = geom.strip()
        if not s:
            return []
        if s.startswith("{"):
            try:
                geom = json.loads(s)
            except json.JSONDecodeError:
                return []
        else:
            # Parse WKT LINESTRING / MULTILINESTRING
            upper = s.upper()
            if "LINESTRING" not in upper:
                return []
            lines: list[list[tuple[float, float]]] = []
            # Split inner coordinate groups enclosed in parentheses
            start = s.find("(")
            end = s.rfind(")")
            if start < 0 or end <= start:
                return []
            body = s[start + 1 : end].strip()
            groups = body.split("),") if "(" in body else [body]
            for grp in groups:
                cleaned = grp.replace("(", "").replace(")", "").strip()
                pts: list[tuple[float, float]] = []
                for pair in cleaned.split(","):
                    parts = pair.strip().split()
                    if len(parts) >= 2:
                        lon = _to_float(parts[0])
                        lat = _to_float(parts[1])
                        if lat is not None and lon is not None:
                            pts.append((lat, lon))
                if len(pts) >= 2:
                    lines.append(pts)
            return lines

    if isinstance(geom, dict):
        gtype = str(geom.get("type") or "").lower()
        if gtype == "feature":
            return extract_polylines_from_geometry(geom.get("geometry"))
        coords = geom.get("coordinates")
        if gtype == "linestring" and isinstance(coords, list):
            pts = _coords_lonlat_to_latlon(coords)
            return [pts] if len(pts) >= 2 else []
        if gtype == "multilinestring" and isinstance(coords, list):
            lines = []
            for sub in coords:
                if isinstance(sub, list):
                    pts = _coords_lonlat_to_latlon(sub)
                    if len(pts) >= 2:
                        lines.append(pts)
            return lines
    return []


def parse_hpms_row(
    row: dict[str, Any],
    col_map: dict[str, str | None],
    bbox: tuple[float, float, float, float],
) -> list[tuple[int, str, float, float, float, float, str]]:
    """
    Validate and transform one HPMS Socrata row into zero or more `hpms_speed` rows:
    (speed_mph, route_name, min_lat, min_lon, max_lat, max_lon, polyline6).
    """
    speed_f = col_map.get("speed")
    geom_f = col_map.get("geometry")
    if not speed_f or not geom_f:
        return []

    speed_mph = _to_int(row.get(speed_f), 0)
    if speed_mph < 10 or speed_mph > 85:
        return []

    route_name = str(row.get(col_map["route_name"]) or "").strip() if col_map.get("route_name") else ""
    lines = extract_polylines_from_geometry(row.get(geom_f))
    if not lines:
        return []

    b_min_lon, b_min_lat, b_max_lon, b_max_lat = bbox
    results: list[tuple[int, str, float, float, float, float, str]] = []
    for line in lines:
        lats = [p[0] for p in line]
        lons = [p[1] for p in line]
        min_lat, max_lat = min(lats), max(lats)
        min_lon, max_lon = min(lons), max(lons)
        # Skip segments completely outside pack bbox
        if max_lat < b_min_lat or min_lat > b_max_lat or max_lon < b_min_lon or min_lon > b_max_lon:
            continue
        simplified = simplify_polyline(line)
        if len(simplified) < 2:
            continue
        encoded = encode_polyline6(simplified)
        if not encoded:
            continue
        results.append((speed_mph, route_name, min_lat, min_lon, max_lat, max_lon, encoded))
    return results


def populate_hpms_speed(
    conn: sqlite3.Connection,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    page_size: int = 20000,
) -> int:
    """
    X3: Fetch HPMS 2024 speed limits (`42um-tgh5`) dynamically via Socrata columns.json,
    simplify polylines, and store in `hpms_speed` with bbox index.
    """
    columns = http_get_json(HPMS_COLUMNS_URL)
    if not isinstance(columns, list):
        raise RuntimeError("HPMS columns.json did not return a list")

    col_map = discover_hpms_columns(columns)
    speed_f = col_map.get("speed")
    geom_f = col_map.get("geometry")
    if not speed_f or not geom_f:
        raise RuntimeError(f"HPMS speed or geometry column missing: {col_map}")

    state_f = col_map.get("state")
    abbr_up = state_abbr.strip().upper()
    fips = US_STATE_FIPS.get(abbr_up, "")

    where_parts = [f"{speed_f} IS NOT NULL"]
    if state_f and fips:
        fips_int = str(int(fips))
        where_parts.append(
            f"({state_f} = '{fips}' OR {state_f} = '{fips_int}' OR upper({state_f}) = '{abbr_up}')"
        )
    elif state_f:
        where_parts.append(f"upper({state_f}) = '{abbr_up}'")
    where_clause = " AND ".join(where_parts)

    inserted = 0
    offset = 0
    while True:
        params = urllib.parse.urlencode(
            {
                "$where": where_clause,
                "$limit": str(page_size),
                "$offset": str(offset),
            }
        )
        rows = http_get_json(f"{HPMS_RESOURCE_URL}?{params}")
        if not isinstance(rows, list) or not rows:
            break

        batch: list[tuple[int, str, float, float, float, float, str]] = []
        for row in rows:
            if isinstance(row, dict):
                batch.extend(parse_hpms_row(row, col_map, bbox))

        if batch:
            conn.executemany(
                """
                INSERT INTO hpms_speed
                    (speed_mph, route_name, min_lat, min_lon, max_lat, max_lon, polyline)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                batch,
            )
            inserted += len(batch)

        if len(rows) < page_size:
            break
        offset += page_size

    conn.commit()
    print(f"[check] hpms_speed count={inserted} state={abbr_up}", flush=True)
    return inserted
