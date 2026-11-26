#!/usr/bin/env python3
"""
RahYab Extras SQLite Builder: USGS GNIS Domestic Names (D2)
-----------------------------------------------------------
Stdlib-only Python (urllib.request, zipfile, csv, sqlite3, xml.etree.ElementTree).
Downloads the state's USGS GNIS Domestic Names pipe-delimited text zip
(`DomesticNames_<ST>_Text.zip`, with S3 folder listing fallback), keeps valid
natural features & landmarks inside the pack bbox, and writes `gnis_names` plus
prefix-indexed FTS5 `gnis_fts` into `<state>_extras.db`.
"""

from __future__ import annotations

import csv
import io
import math
import re
import sqlite3
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Any

from extras_tiger import download_file, http_get_text, resolve_base_state_abbr

GNIS_S3_BASE = "https://prd-tnm.s3.amazonaws.com"
GNIS_S3_PREFIX = "StagedProducts/GeographicNames/DomesticNames/"
GNIS_TIMEOUT_SEC = 120

GNIS_ALLOWED_CLASSES: dict[str, str] = {
    "summit": "Summit",
    "lake": "Lake",
    "reservoir": "Reservoir",
    "stream": "Stream",
    "falls": "Falls",
    "park": "Park",
    "trail": "Trail",
    "bridge": "Bridge",
    "dam": "Dam",
    "locale": "Locale",
    "populated place": "Populated Place",
    "gap": "Gap",
    "valley": "Valley",
    "spring": "Spring",
    "island": "Island",
    "beach": "Beach",
}


def init_gnis_schema(conn: sqlite3.Connection) -> None:
    """Create the additive `gnis_names` table and prefix-enabled FTS5 `gnis_fts`."""
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS gnis_names (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            feature_class TEXT NOT NULL,
            county TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_gnis_names_lat_lon
            ON gnis_names(lat, lon);
        CREATE VIRTUAL TABLE IF NOT EXISTS gnis_fts USING fts5(
            name,
            tokenize='unicode61',
            prefix='2 3'
        );
        """
    )


def build_gnis_primary_url(state_abbr: str) -> str:
    abbr = resolve_base_state_abbr(state_abbr)
    return f"{GNIS_S3_BASE}/{GNIS_S3_PREFIX}DomesticNames_{abbr}_Text.zip"


def find_gnis_url_from_s3_listing(xml_text: str, state_abbr: str) -> str | None:
    """Locate the current DomesticNames zip/txt key for `state_abbr` in an S3 ListBucket XML."""
    abbr = resolve_base_state_abbr(state_abbr).upper()
    if not abbr:
        return None
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None

    keys: list[str] = []
    for elem in root.iter():
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag == "Key" and elem.text:
            keys.append(elem.text.strip())

    # Prefer exact DomesticNames_<ST>_Text*.zip, then any *_<ST>_*.zip, then *_<ST>*.txt
    st_pat = re.compile(rf"(?:^|[/_-]){re.escape(abbr)}(?:[._-]|$)", re.IGNORECASE)
    ranked: list[tuple[int, str]] = []
    for key in keys:
        filename = key.rsplit("/", 1)[-1]
        if not st_pat.search(filename):
            continue
        lower = filename.lower()
        if lower.endswith(".zip") and "text" in lower:
            ranked.append((0, key))
        elif lower.endswith(".zip"):
            ranked.append((1, key))
        elif lower.endswith(".txt"):
            ranked.append((2, key))
    if not ranked:
        return None
    ranked.sort(key=lambda item: (item[0], item[1]))
    chosen_key = ranked[0][1].lstrip("/")
    return f"{GNIS_S3_BASE}/{urllib.parse.quote(chosen_key, safe='/-_.')}"


def _to_valid_coord(raw: Any) -> float | None:
    if raw is None or raw == "":
        return None
    try:
        val = float(str(raw).strip())
        return val if math.isfinite(val) else None
    except (TypeError, ValueError):
        return None


def parse_gnis_row(
    row: dict[str, Any],
    bbox: tuple[float, float, float, float],
) -> tuple[str, str, str, float, float] | None:
    """
    Validate and normalize one USGS GNIS pipe-delimited row into:
    (name, feature_class, county, lat, lon).
    """
    norm = {str(k).lstrip("\ufeff").strip().lower(): v for k, v in row.items() if k is not None}
    name = str(norm.get("feature_name") or norm.get("name") or "").strip()
    if not name:
        return None

    raw_class = str(norm.get("feature_class") or norm.get("class") or "").strip().lower()
    canonical_class = GNIS_ALLOWED_CLASSES.get(raw_class)
    if not canonical_class:
        return None

    lat = _to_valid_coord(norm.get("prim_lat_dec") or norm.get("lat") or norm.get("latitude"))
    lon = _to_valid_coord(norm.get("prim_long_dec") or norm.get("lon") or norm.get("longitude"))
    if lat is None or lon is None:
        return None
    if (lat == 0.0 and lon == 0.0) or not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
        return None

    min_lon, min_lat, max_lon, max_lat = bbox
    if not (min_lat <= lat <= max_lat and min_lon <= lon <= max_lon):
        return None

    county = str(norm.get("county_name") or norm.get("county") or "").strip()
    return (name, canonical_class, county, lat, lon)


def parse_gnis_pipe_text(
    text: str,
    bbox: tuple[float, float, float, float],
) -> list[tuple[str, str, str, float, float]]:
    """Parse USGS GNIS pipe-delimited text into deduplicated filtered rows."""
    cleaned = text.lstrip("\ufeff")
    reader = csv.DictReader(io.StringIO(cleaned), delimiter="|")
    rows: list[tuple[str, str, str, float, float]] = []
    seen: set[tuple[str, str, int, int]] = set()
    for raw in reader:
        parsed = parse_gnis_row(raw, bbox)
        if parsed is None:
            continue
        name, fclass, county, lat, lon = parsed
        dedup_key = (name.lower(), fclass, int(round(lat * 10000)), int(round(lon * 10000)))
        if dedup_key in seen:
            continue
        seen.add(dedup_key)
        rows.append((name, fclass, county, lat, lon))
    return rows


def extract_gnis_rows_from_archive(
    archive_path: Path,
    bbox: tuple[float, float, float, float],
) -> list[tuple[str, str, str, float, float]]:
    """Read a GNIS `.zip` (or plain `.txt`) file and return filtered rows."""
    if zipfile.is_zipfile(archive_path):
        all_rows: list[tuple[str, str, str, float, float]] = []
        with zipfile.ZipFile(archive_path, "r") as zf:
            txt_members = [
                m for m in zf.namelist()
                if not m.endswith("/") and m.lower().endswith(".txt")
            ]
            for member in txt_members:
                raw_bytes = zf.read(member)
                text = raw_bytes.decode("utf-8", errors="replace")
                all_rows.extend(parse_gnis_pipe_text(text, bbox))
        return all_rows
    text = archive_path.read_text(encoding="utf-8", errors="replace")
    return parse_gnis_pipe_text(text, bbox)


def download_gnis_state_file(
    state_abbr: str,
    dest_path: Path,
    timeout_sec: int = GNIS_TIMEOUT_SEC,
) -> Path:
    """Download DomesticNames_<ST>_Text.zip, falling back to the S3 folder listing if needed."""
    primary_url = build_gnis_primary_url(state_abbr)
    try:
        return download_file(primary_url, dest_path, timeout_sec=timeout_sec)
    except Exception as primary_exc:
        listing_url = f"{GNIS_S3_BASE}/?list-type=2&prefix={urllib.parse.quote(GNIS_S3_PREFIX)}"
        xml_text = http_get_text(listing_url, timeout_sec=min(45, timeout_sec))
        fallback_url = find_gnis_url_from_s3_listing(xml_text, state_abbr)
        if not fallback_url:
            raise RuntimeError(
                f"GNIS file not found for {state_abbr} (primary failed: {primary_exc})"
            ) from primary_exc
        return download_file(fallback_url, dest_path, timeout_sec=timeout_sec)


def populate_gnis_names(
    conn: sqlite3.Connection,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    work_dir: Path | None = None,
    timeout_sec: int = GNIS_TIMEOUT_SEC,
) -> int:
    """
    D2: Download USGS GNIS Domestic Names for `state_abbr`, filter by allowed feature classes
    and `bbox`, and populate `gnis_names` + FTS5 `gnis_fts`.
    """
    init_gnis_schema(conn)
    cleanup_dir: tempfile.TemporaryDirectory[str] | None = None
    if work_dir is None:
        cleanup_dir = tempfile.TemporaryDirectory(prefix="rahyab_gnis_")
        active_dir = Path(cleanup_dir.name)
    else:
        active_dir = work_dir
        active_dir.mkdir(parents=True, exist_ok=True)

    abbr = resolve_base_state_abbr(state_abbr)
    archive_path = active_dir / f"DomesticNames_{abbr}_Text.zip"
    try:
        download_gnis_state_file(abbr, archive_path, timeout_sec=timeout_sec)
        rows = extract_gnis_rows_from_archive(archive_path, bbox)
    finally:
        archive_path.unlink(missing_ok=True)
        if cleanup_dir is not None:
            cleanup_dir.cleanup()

    inserted = 0
    if rows:
        conn.executemany(
            """
            INSERT INTO gnis_names (name, feature_class, county, lat, lon)
            VALUES (?, ?, ?, ?, ?)
            """,
            rows,
        )
        conn.execute("INSERT INTO gnis_fts (rowid, name) SELECT id, name FROM gnis_names")
        inserted = len(rows)
        conn.commit()

    print(f"[check] gnis_names count={inserted}", flush=True)
    return inserted
