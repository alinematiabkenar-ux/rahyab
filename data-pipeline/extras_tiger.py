#!/usr/bin/env python3
"""
RahYab Extras SQLite Builder: US Census TIGER/Line ADDRFEAT Address Ranges (D1)
------------------------------------------------------------------------------
Uses only the Python standard library and DuckDB (spatial extension `/vsizip/`
shapefile reader). Downloads newest available TIGER/Line ADDRFEAT county zips
(trying 2026, 2025, 2024 in order) for the target state and intersecting
neighboring-state counties, normalizes street names with `address_utils.py`,
and populates the `tiger_ranges` table in `<state>_extras.db`.
"""

from __future__ import annotations

import http.client
import os
import re
import socket
import sqlite3
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from address_utils import normalize_street
from extras_fra_hpms import (
    US_STATE_FIPS,
    encode_polyline6,
    extract_polylines_from_geometry,
    simplify_polyline,
)
from states import ALL_PACK_CATALOG

TIGER_YEARS: tuple[int, ...] = (2026, 2025, 2024)
TIGER_BASE_URL = "https://www2.census.gov/geo/tiger"
HTTP_USER_AGENT = "RahYab-Pipeline/25.0"
TIGER_TIMEOUT_SEC = 120
TIGER_RETRY_DELAYS_SEC: tuple[int, ...] = (3, 10, 20)
TIGER_RETRYABLE_HTTP_CODES: frozenset[int] = frozenset({429, 500, 502, 503, 504})

_HOUSE_NUMBER_RE = re.compile(r"^\s*(\d+)")
_ZIP5_RE = re.compile(r"(\d{5})")


def init_tiger_schema(conn: sqlite3.Connection) -> None:
    """Create the additive `tiger_ranges` table and indexes in `<state>_extras.db`."""
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS tiger_ranges (
            id INTEGER PRIMARY KEY,
            street TEXT,
            street_norm TEXT,
            city TEXT,
            zip TEXT,
            from_left INTEGER,
            to_left INTEGER,
            from_right INTEGER,
            to_right INTEGER,
            parity_left TEXT,
            parity_right TEXT,
            min_lat REAL,
            min_lon REAL,
            max_lat REAL,
            max_lon REAL,
            polyline TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_tiger_ranges_street_norm
            ON tiger_ranges(street_norm);
        CREATE INDEX IF NOT EXISTS idx_tiger_ranges_bbox
            ON tiger_ranges(min_lat, max_lat, min_lon, max_lon);
        """
    )


def resolve_base_state_abbr(state_abbr: str, pack_code: str = "") -> str:
    """Resolve regional pack codes (e.g. `CA-N` / `ca-north`) to 2-letter US state abbr."""
    code = (pack_code or "").strip().lower()
    if code in ALL_PACK_CATALOG:
        parent = ALL_PACK_CATALOG[code].get("parent")
        if parent and parent in ALL_PACK_CATALOG:
            return str(ALL_PACK_CATALOG[parent]["abbr"]).upper()
    raw = (state_abbr or "").strip().upper()
    if "-" in raw:
        raw = raw.split("-", 1)[0]
    return raw


def resolve_neighbor_state_fips(pack_code: str, state_abbr: str) -> list[str]:
    """Return 2-digit FIPS codes for neighboring states listed in `states.py`."""
    code = (pack_code or "").strip().lower()
    meta = ALL_PACK_CATALOG.get(code)
    if meta is None:
        base = resolve_base_state_abbr(state_abbr, pack_code).lower()
        meta = ALL_PACK_CATALOG.get(base)
    if not meta:
        return []
    out: list[str] = []
    for nbr in meta.get("neighbors") or []:
        nbr_fips = US_STATE_FIPS.get(str(nbr).strip().upper())
        if nbr_fips and nbr_fips not in out:
            out.append(nbr_fips)
    return out


def parse_tiger_house_number(raw: Any) -> int | None:
    """Parse a TIGER house number string (e.g. '100', '104A') to its integer value."""
    if raw is None:
        return None
    if isinstance(raw, bool):
        return None
    if isinstance(raw, int):
        return raw if raw > 0 else None
    if isinstance(raw, float):
        iv = int(round(raw))
        return iv if iv > 0 else None
    m = _HOUSE_NUMBER_RE.match(str(raw))
    if not m:
        return None
    val = int(m.group(1))
    return val if val > 0 else None


def normalize_tiger_parity(raw: Any) -> str:
    """Normalize TIGER parity code to 'E', 'O', 'B', or ''."""
    s = str(raw or "").strip().upper()
    if s in ("E", "EVEN"):
        return "E"
    if s in ("O", "ODD"):
        return "O"
    if s in ("B", "BOTH"):
        return "B"
    return ""


def _clean_zip5(raw: Any) -> str:
    if raw is None:
        return ""
    m = _ZIP5_RE.search(str(raw).strip())
    return m.group(1) if m else ""


def _is_retryable_error(exc: BaseException) -> bool:
    if isinstance(exc, urllib.error.HTTPError):
        return exc.code in TIGER_RETRYABLE_HTTP_CODES
    return isinstance(
        exc,
        (
            TimeoutError,
            socket.timeout,
            ConnectionError,
            urllib.error.URLError,
            http.client.HTTPException,
        ),
    )


def http_get_text(url: str, timeout_sec: int = 30) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": HTTP_USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
        return resp.read().decode("utf-8", errors="replace")


def download_file(
    url: str,
    dest: Path,
    timeout_sec: int = TIGER_TIMEOUT_SEC,
    retry_delays: tuple[int, ...] = TIGER_RETRY_DELAYS_SEC,
) -> Path:
    """Stream-download `url` to `dest` with bounded retries on transient failures."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(len(retry_delays) + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": HTTP_USER_AGENT})
            with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
                with open(dest, "wb") as out_f:
                    while True:
                        chunk = resp.read(256 * 1024)
                        if not chunk:
                            break
                        out_f.write(chunk)
            return dest
        except Exception as exc:
            dest.unlink(missing_ok=True)
            if attempt < len(retry_delays) and _is_retryable_error(exc):
                time.sleep(retry_delays[attempt])
                continue
            raise
    return dest


def discover_newest_tiger_year(
    years: tuple[int, ...] = TIGER_YEARS,
    timeout_sec: int = 30,
) -> tuple[int, str]:
    """Try TIGER years in descending order (2026, 2025, 2024) and return (year, dir_html)."""
    last_err: Exception | None = None
    for year in years:
        url = f"{TIGER_BASE_URL}/TIGER{year}/ADDRFEAT/"
        try:
            html = http_get_text(url, timeout_sec=timeout_sec)
            if f"tl_{year}_" in html:
                return year, html
        except Exception as exc:
            last_err = exc
            continue
    if last_err is not None:
        raise RuntimeError(f"No TIGER ADDRFEAT directory available for {years}: {last_err}") from last_err
    raise RuntimeError(f"No TIGER ADDRFEAT files found for years {years}")


def extract_county_fips_from_addrfeat_listing(
    html: str,
    year: int,
    state_fips_set: set[str],
) -> list[str]:
    """Parse county 5-digit FIPS codes from a TIGER ADDRFEAT HTML directory listing."""
    pattern = re.compile(rf"tl_{year}_(\d{{5}})_addrfeat\.zip", re.IGNORECASE)
    found: set[str] = set()
    for match in pattern.finditer(html or ""):
        fips = match.group(1)
        if not state_fips_set or fips[:2] in state_fips_set:
            found.add(fips)
    return sorted(found)


def _open_duckdb_spatial():
    import duckdb  # Imported lazily so pure-Python unit tests run even without duckdb

    con = duckdb.connect(":memory:")
    try:
        con.execute("LOAD spatial;")
    except Exception:
        con.execute("INSTALL spatial;")
        con.execute("LOAD spatial;")
    return con


def _find_spatial_geom_column(con, vsizip_path: str) -> tuple[list[str], str]:
    desc = con.execute("DESCRIBE SELECT * FROM ST_Read(?)", [vsizip_path]).fetchall()
    col_names = [str(row[0]) for row in desc]
    geom_col = ""
    for row in desc:
        col_name = str(row[0])
        col_type = str(row[1]).upper()
        if "GEOMETRY" in col_type or col_name.lower() in ("geom", "geometry", "wkb_geometry"):
            geom_col = col_name
            break
    if not geom_col and col_names:
        geom_col = col_names[-1]
    return col_names, geom_col


def _read_county_fips_from_boundary_zip(
    zip_path: Path,
    state_fips: str,
    neighbor_fips: list[str],
    bbox: tuple[float, float, float, float],
    is_regional_pack: bool,
) -> list[str]:
    """Read US county shapefile via DuckDB spatial and return intersecting county FIPS."""
    vsizip_path = f"/vsizip/{zip_path.as_posix()}"
    b_min_lon, b_min_lat, b_max_lon, b_max_lat = bbox
    allowed_states = {state_fips, *neighbor_fips}
    con = _open_duckdb_spatial()
    try:
        col_names, geom_col = _find_spatial_geom_column(con, vsizip_path)
        upper_map = {c.upper(): c for c in col_names}
        geoid_col = upper_map.get("GEOID") or upper_map.get("GEOIDFQ")
        state_col = upper_map.get("STATEFP")
        county_col = upper_map.get("COUNTYFP")
        if not state_col or (not geoid_col and not county_col) or not geom_col:
            return []

        id_expr = f'"{geoid_col}"' if geoid_col else f'("{state_col}" || "{county_col}")'
        sql = (
            f'SELECT "{state_col}", {id_expr}, '
            f'ST_XMin("{geom_col}"), ST_YMin("{geom_col}"), '
            f'ST_XMax("{geom_col}"), ST_YMax("{geom_col}") '
            f"FROM ST_Read(?)"
        )
        rows = con.execute(sql, [vsizip_path]).fetchall()
        matched: set[str] = set()
        for st_val, raw_id, c_min_lon, c_min_lat, c_max_lon, c_max_lat in rows:
            st = str(st_val or "").strip().zfill(2)
            if st not in allowed_states:
                continue
            fips_digits = re.sub(r"\D+", "", str(raw_id or ""))
            if len(fips_digits) < 5:
                continue
            county_fips = fips_digits[-5:]
            if c_min_lon is None or c_min_lat is None or c_max_lon is None or c_max_lat is None:
                if st == state_fips and not is_regional_pack:
                    matched.add(county_fips)
                continue
            intersects = not (
                float(c_max_lon) < b_min_lon
                or float(c_min_lon) > b_max_lon
                or float(c_max_lat) < b_min_lat
                or float(c_min_lat) > b_max_lat
            )
            if (st == state_fips and not is_regional_pack) or intersects:
                matched.add(county_fips)
        return sorted(matched)
    finally:
        con.close()


def discover_intersecting_county_fips(
    year: int,
    pack_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    addrfeat_html: str,
    work_dir: Path,
    timeout_sec: int = TIGER_TIMEOUT_SEC,
) -> list[str]:
    """
    Find every county FIPS in the target state plus counties of neighboring states
    whose boundary intersects `bbox`. Falls back to all target-state counties from
    the ADDRFEAT directory listing if the county boundary file is unavailable.
    """
    base_abbr = resolve_base_state_abbr(state_abbr, pack_code)
    state_fips = US_STATE_FIPS.get(base_abbr, "")
    if not state_fips:
        return []
    neighbor_fips = resolve_neighbor_state_fips(pack_code, state_abbr)
    is_regional = "-" in (pack_code or "") or "-" in (state_abbr or "")

    available_all = set(
        extract_county_fips_from_addrfeat_listing(
            addrfeat_html, year, {state_fips, *neighbor_fips}
        )
    )

    candidate_urls: list[str] = []
    for y in (year, 2025, 2024):
        url_cb = f"{TIGER_BASE_URL}/GENZ{y}/shp/cb_{y}_us_county_500k.zip"
        if url_cb not in candidate_urls:
            candidate_urls.append(url_cb)
    candidate_urls.append(f"{TIGER_BASE_URL}/TIGER{year}/COUNTY/tl_{year}_us_county.zip")

    county_zip = work_dir / "us_counties.zip"
    for url in candidate_urls:
        try:
            download_file(url, county_zip, timeout_sec=timeout_sec)
            fips_list = _read_county_fips_from_boundary_zip(
                county_zip, state_fips, neighbor_fips, bbox, is_regional
            )
            county_zip.unlink(missing_ok=True)
            if fips_list:
                if available_all:
                    filtered = [f for f in fips_list if f in available_all]
                    if filtered:
                        return filtered
                return fips_list
        except Exception:
            county_zip.unlink(missing_ok=True)
            continue

    # Clean fallback: every county of the target state present in the ADDRFEAT listing
    return sorted(
        extract_county_fips_from_addrfeat_listing(addrfeat_html, year, {state_fips})
    )


def read_addrfeat_zip_rows(zip_path: Path) -> list[dict[str, Any]]:
    """Read a zipped TIGER ADDRFEAT shapefile via DuckDB spatial `/vsizip/`."""
    vsizip_path = f"/vsizip/{zip_path.as_posix()}"
    con = _open_duckdb_spatial()
    try:
        col_names, geom_col = _find_spatial_geom_column(con, vsizip_path)
        non_geom = [c for c in col_names if c != geom_col]
        select_parts = [f'"{c}"' for c in non_geom]
        select_parts.append(f'ST_AsGeoJSON("{geom_col}") AS geom_geojson')
        sql = f"SELECT {', '.join(select_parts)} FROM ST_Read(?)"
        cur = con.execute(sql, [vsizip_path])
        out_cols = [c.upper() for c in non_geom] + ["GEOM_GEOJSON"]
        rows: list[dict[str, Any]] = []
        while True:
            chunk = cur.fetchmany(10_000)
            if not chunk:
                break
            for tup in chunk:
                rows.append(dict(zip(out_cols, tup)))
        return rows
    finally:
        con.close()


def parse_tiger_feature_row(
    row: dict[str, Any],
    bbox: tuple[float, float, float, float],
) -> list[
    tuple[
        str,
        str,
        str,
        str,
        int | None,
        int | None,
        int | None,
        int | None,
        str,
        str,
        float,
        float,
        float,
        float,
        str,
    ]
]:
    """
    Transform one TIGER ADDRFEAT feature dict into zero or more `tiger_ranges` rows:
    (street, street_norm, city, zip, from_left, to_left, from_right, to_right,
     parity_left, parity_right, min_lat, min_lon, max_lat, max_lon, polyline).
    """
    norm_row = {str(k).upper(): v for k, v in row.items()}
    street = str(norm_row.get("FULLNAME") or norm_row.get("STREET") or "").strip()
    street_norm = normalize_street(street)
    if not street_norm:
        return []

    from_left = parse_tiger_house_number(norm_row.get("LFROMHN"))
    to_left = parse_tiger_house_number(norm_row.get("LTOHN"))
    from_right = parse_tiger_house_number(norm_row.get("RFROMHN"))
    to_right = parse_tiger_house_number(norm_row.get("RTOHN"))
    has_left = from_left is not None and to_left is not None
    has_right = from_right is not None and to_right is not None
    if not has_left and not has_right:
        return []
    if not has_left:
        from_left = None
        to_left = None
    if not has_right:
        from_right = None
        to_right = None

    parity_left = normalize_tiger_parity(norm_row.get("PARITYL"))
    parity_right = normalize_tiger_parity(norm_row.get("PARITYR"))
    city = str(
        norm_row.get("CITY")
        or norm_row.get("PLACEL")
        or norm_row.get("PLACER")
        or ""
    ).strip()
    zip_code = _clean_zip5(norm_row.get("ZIPL")) or _clean_zip5(norm_row.get("ZIPR"))

    geom_raw = (
        norm_row.get("GEOM_GEOJSON")
        or norm_row.get("GEOMETRY")
        or norm_row.get("GEOM")
    )
    lines = extract_polylines_from_geometry(geom_raw)
    if not lines:
        return []

    b_min_lon, b_min_lat, b_max_lon, b_max_lat = bbox
    results = []
    for line in lines:
        lats = [p[0] for p in line]
        lons = [p[1] for p in line]
        min_lat, max_lat = min(lats), max(lats)
        min_lon, max_lon = min(lons), max(lons)
        if max_lat < b_min_lat or min_lat > b_max_lat or max_lon < b_min_lon or min_lon > b_max_lon:
            continue
        simplified = simplify_polyline(line, tolerance_deg=0.00004)
        if len(simplified) < 2:
            continue
        encoded = encode_polyline6(simplified)
        if not encoded:
            continue
        results.append(
            (
                street,
                street_norm,
                city,
                zip_code,
                from_left,
                to_left,
                from_right,
                to_right,
                parity_left,
                parity_right,
                min_lat,
                min_lon,
                max_lat,
                max_lon,
                encoded,
            )
        )
    return results


def populate_tiger_ranges(
    conn: sqlite3.Connection,
    pack_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    work_dir: Path | None = None,
    timeout_sec: int = TIGER_TIMEOUT_SEC,
) -> int:
    """
    D1: Download TIGER/Line ADDRFEAT county zips for the state + intersecting neighbor
    counties, extract street address ranges via DuckDB spatial `/vsizip/`, and insert
    into `tiger_ranges`.
    """
    init_tiger_schema(conn)
    year, addrfeat_html = discover_newest_tiger_year(timeout_sec=min(30, timeout_sec))

    cleanup_dir: tempfile.TemporaryDirectory[str] | None = None
    if work_dir is None:
        cleanup_dir = tempfile.TemporaryDirectory(prefix="rahyab_tiger_")
        active_dir = Path(cleanup_dir.name)
    else:
        active_dir = work_dir
        active_dir.mkdir(parents=True, exist_ok=True)

    total_inserted = 0
    try:
        county_fips_list = discover_intersecting_county_fips(
            year=year,
            pack_code=pack_code,
            state_abbr=state_abbr,
            bbox=bbox,
            addrfeat_html=addrfeat_html,
            work_dir=active_dir,
            timeout_sec=timeout_sec,
        )
        print(
            f"[extras] TIGER {year} ADDRFEAT: {len(county_fips_list)} counties for {pack_code.upper()}",
            flush=True,
        )
        for county_fips in county_fips_list:
            url = f"{TIGER_BASE_URL}/TIGER{year}/ADDRFEAT/tl_{year}_{county_fips}_addrfeat.zip"
            zip_path = active_dir / f"tl_{year}_{county_fips}_addrfeat.zip"
            try:
                download_file(url, zip_path, timeout_sec=timeout_sec)
                raw_rows = read_addrfeat_zip_rows(zip_path)
                batch = []
                for raw in raw_rows:
                    batch.extend(parse_tiger_feature_row(raw, bbox))
                if batch:
                    conn.executemany(
                        """
                        INSERT INTO tiger_ranges (
                            street, street_norm, city, zip,
                            from_left, to_left, from_right, to_right,
                            parity_left, parity_right,
                            min_lat, min_lon, max_lat, max_lon, polyline
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        batch,
                    )
                    total_inserted += len(batch)
                    conn.commit()
            except urllib.error.HTTPError as exc:
                if exc.code == 404:
                    continue
                print(
                    f"[warn] TIGER county {county_fips} skipped: {type(exc).__name__}: {exc}",
                    flush=True,
                )
            except Exception as exc:
                print(
                    f"[warn] TIGER county {county_fips} skipped: {type(exc).__name__}: {exc}",
                    flush=True,
                )
            finally:
                zip_path.unlink(missing_ok=True)
    finally:
        if cleanup_dir is not None:
            cleanup_dir.cleanup()

    print(f"[check] tiger_ranges count={total_inserted}", flush=True)
    return total_inserted
