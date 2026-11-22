#!/usr/bin/env python3
"""
RahYab Offline State & Regional Pack Builder (Phase 5 GitHub Actions Edition)
-----------------------------------------------------------------------------
Builds the 3 offline files + per-pack manifest JSON for any US state or regional sub-pack
directly on free GitHub-hosted `ubuntu-latest` runners (or locally via Docker):

  1. <pack>.pmtiles       — Protomaps vector basemap extract for MapLibre Native
  2. <pack>_valhalla.tar  — Valhalla routing graph tiles built with a ~30-mile (~0.45°)
                            border buffer into neighboring states so routes cross US state
                            lines seamlessly when adjacent packs are installed!
  3. <pack>_places.db     — SQLite FTS5 search database merging Overture Maps Places
                            (businesses/stores via DuckDB with bbox filter) + OpenStreetMap
                            addresses, POIs, and road hazards (railroad crossings, school
                            zones, stop signs, traffic signals, toll booths).

Automatic safeguards for free GitHub Releases:
  - Splits large states (CA, TX, FL, NY) into regional sub-packs (ca-north, ca-south, etc.)
  - Splits any single artifact > 1.9 GiB into `.part001`, `.part002`, ... with per-part and
    whole-file SHA-256 checksums so GitHub's 2 GiB release asset limit is never exceeded
  - Supports `--merge-manifests` to merge parallel matrix job outputs into one `manifest.json`
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import time
from pathlib import Path

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from states import ALL_PACK_CATALOG, US_STATES, resolve_pack_targets

GEOFABRIK_INDEX_URL = "https://download.geofabrik.de/index-v1-nogeom.json"
GEOFABRIK_FALLBACK_PATTERNS = (
    "https://download.geofabrik.de/north-america/us/{slug}/{slug}-latest.osm.pbf",
    "https://download.geofabrik.de/north-america/us/{slug}-latest.osm.pbf",
)
GEOFABRIK_CA_SUB_FALLBACK_PATTERNS = (
    "https://download.geofabrik.de/north-america/us/california/{slug}/{slug}-latest.osm.pbf",
    "https://download.geofabrik.de/north-america/us/california/{slug}-latest.osm.pbf",
)
_GEOFABRIK_INDEX_CACHE: dict[str, str] | None = None
_GEOFABRIK_INDEX_LOADED = False

# ~30-mile border buffer in degrees (~0.45 deg lat / ~0.55 deg lon at US latitudes)
BORDER_BUFFER_DEG_LAT = 0.45
BORDER_BUFFER_DEG_LON = 0.55

# Split any file > 1.9 GiB into chunks to stay safely below GitHub Releases' 2 GiB asset limit
GITHUB_ASSET_SPLIT_BYTES = 1_900 * 1024 * 1024


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def run_cmd(cmd: list[str], cwd: Path | None = None) -> None:
    print(f"[cmd] {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def expand_bbox(bbox: tuple[float, float, float, float], pad_lon: float, pad_lat: float) -> tuple[float, float, float, float]:
    min_lon, min_lat, max_lon, max_lat = bbox
    return (
        max(-180.0, min_lon - pad_lon),
        max(-90.0, min_lat - pad_lat),
        min(180.0, max_lon + pad_lon),
        min(90.0, max_lat + pad_lat),
    )


def load_geofabrik_index() -> dict[str, str]:
    """Download Geofabrik's machine-readable index once per run and map region id -> pbf URL."""
    global _GEOFABRIK_INDEX_CACHE, _GEOFABRIK_INDEX_LOADED
    if _GEOFABRIK_INDEX_LOADED:
        return _GEOFABRIK_INDEX_CACHE or {}

    import requests

    _GEOFABRIK_INDEX_LOADED = True
    last_err: Exception | None = None
    for attempt in range(1, 4):
        try:
            print(f"[geofabrik] Downloading index ({attempt}/3): {GEOFABRIK_INDEX_URL}", flush=True)
            resp = requests.get(GEOFABRIK_INDEX_URL, timeout=60)
            resp.raise_for_status()
            payload = resp.json()
            mapping: dict[str, str] = {}
            for feature in payload.get("features", []):
                props = feature.get("properties") or {}
                region_id = props.get("id")
                pbf_url = (props.get("urls") or {}).get("pbf")
                if isinstance(region_id, str) and isinstance(pbf_url, str) and pbf_url:
                    mapping[region_id.strip().lower()] = pbf_url.strip()
            _GEOFABRIK_INDEX_CACHE = mapping
            print(f"[geofabrik] Loaded {len(mapping)} region URLs from official index.", flush=True)
            return mapping
        except Exception as exc:
            last_err = exc
            print(f"[warn] Geofabrik index attempt {attempt}/3 failed: {exc}", flush=True)
            if attempt < 3:
                time.sleep(2 * attempt)

    print(f"[warn] Could not load Geofabrik index ({last_err}); falling back to URL patterns.", flush=True)
    _GEOFABRIK_INDEX_CACHE = {}
    return {}


def resolve_geofabrik_urls(slug: str) -> list[str]:
    """Return ordered list of candidate Geofabrik PBF URLs for a state or CA sub-region slug."""
    index = load_geofabrik_index()
    if slug in ("norcal", "socal"):
        candidate_ids = (f"us/california/{slug}", f"north-america/us/california/{slug}")
        fallback_templates = GEOFABRIK_CA_SUB_FALLBACK_PATTERNS + GEOFABRIK_FALLBACK_PATTERNS
    else:
        candidate_ids = (f"us/{slug}", f"north-america/us/{slug}")
        fallback_templates = GEOFABRIK_FALLBACK_PATTERNS

    urls: list[str] = []
    matched_id: str | None = None
    for region_id in candidate_ids:
        found = index.get(region_id)
        if found:
            matched_id = region_id
            urls.append(found)
            print(f"[geofabrik] Matched region id '{region_id}' -> {found}", flush=True)
            break

    if not matched_id:
        print(f"[warn] Region id '{candidate_ids[0]}' not found in Geofabrik index; using fallback patterns.", flush=True)

    for tmpl in fallback_templates:
        candidate = tmpl.format(slug=slug)
        if candidate not in urls:
            urls.append(candidate)
    return urls


def download_geofabrik_pbf(slug: str, cache_dir: Path) -> Path:
    import requests

    dest = cache_dir / f"{slug}-latest.osm.pbf"
    if dest.exists() and dest.stat().st_size > 100_000:
        print(f"[cache] Reusing {dest}", flush=True)
        return dest

    candidate_urls = resolve_geofabrik_urls(slug)
    tmp = dest.with_suffix(".pbf.part")

    for url in candidate_urls:
        for attempt in range(1, 4):
            try:
                print(f"[download] Fetching Geofabrik extract ({attempt}/3): {url}", flush=True)
                with requests.get(url, stream=True, timeout=120) as r:
                    r.raise_for_status()
                    with tmp.open("wb") as f:
                        for chunk in r.iter_content(chunk_size=1024 * 1024):
                            if chunk:
                                f.write(chunk)
                tmp.rename(dest)
                return dest
            except Exception as exc:
                tmp.unlink(missing_ok=True)
                print(f"[warn] Download failed ({attempt}/3) for {url}: {exc}", flush=True)
                if attempt < 3:
                    time.sleep(2 * attempt)

    tried_str = ", ".join(candidate_urls)
    raise SystemExit(
        f"RAHYAB ERROR: Failed to download Geofabrik OSM extract for '{slug}' after 3 attempts per URL. URLs tried: {tried_str}"
    )


def extract_pmtiles(pack_code: str, bbox: tuple[float, float, float, float], protomaps_url: str, out_file: Path) -> None:
    buffered = expand_bbox(bbox, 0.15, 0.12)
    bbox_str = f"{buffered[0]:.4f},{buffered[1]:.4f},{buffered[2]:.4f},{buffered[3]:.4f}"
    print(f"[pmtiles] Extracting {pack_code.upper()} ({bbox_str}) from {protomaps_url}", flush=True)
    run_cmd([
        "pmtiles", "extract",
        protomaps_url,
        str(out_file),
        f"--bbox={bbox_str}",
        "--maxzoom=15",
    ])


def prepare_buffered_routing_pbf(
    pack_code: str,
    meta: dict,
    primary_pbf: Path,
    cache_dir: Path,
) -> Path:
    """
    Phase 5 Cross-State Routing (30-mile border buffer):
    Clips the primary state/region PBF AND neighboring states' PBFs to the pack's
    bounding box expanded by ~30 miles, merges them with `osmium merge`, and builds
    Valhalla tiles from the unified buffered PBF so routes cross state lines seamlessly!
    """
    buffered = expand_bbox(meta["bbox"], BORDER_BUFFER_DEG_LON, BORDER_BUFFER_DEG_LAT)
    bbox_str = f"{buffered[0]:.4f},{buffered[1]:.4f},{buffered[2]:.4f},{buffered[3]:.4f}"

    neighbors = meta.get("neighbors", [])
    is_region = "parent" in meta
    if not neighbors and not is_region:
        return primary_pbf

    osmium_bin = shutil.which("osmium")
    if not osmium_bin:
        print("[warn] osmium-tool CLI not found; falling back to primary PBF without neighbor buffer", flush=True)
        return primary_pbf

    parts_to_merge: list[Path] = []
    if is_region:
        clipped_primary = cache_dir / f"{pack_code}_primary_clip.osm.pbf"
        run_cmd([osmium_bin, "extract", "-b", bbox_str, "--strategy=smart", "-O", "-o", str(clipped_primary), str(primary_pbf)])
        parts_to_merge.append(clipped_primary)
    else:
        parts_to_merge.append(primary_pbf)

    for nbr_code in neighbors:
        nbr_meta = US_STATES.get(nbr_code)
        if not nbr_meta:
            continue
        try:
            nbr_pbf = download_geofabrik_pbf(nbr_meta["slug"], cache_dir)
            nbr_clip = cache_dir / f"{pack_code}_nbr_{nbr_code}_clip.osm.pbf"
            run_cmd([
                osmium_bin, "extract",
                "-b", bbox_str,
                "--strategy=smart",
                "-O", "-o", str(nbr_clip),
                str(nbr_pbf),
            ])
            if nbr_clip.exists() and nbr_clip.stat().st_size > 1024:
                parts_to_merge.append(nbr_clip)
            # Immediately free downloaded neighbor PBF on disk-constrained CI runners
            if os.environ.get("CI") == "true" and nbr_pbf != primary_pbf:
                nbr_pbf.unlink(missing_ok=True)
        except Exception as exc:
            print(f"[warn] Skipping neighbor {nbr_code} buffer clip: {exc}", flush=True)

    if len(parts_to_merge) == 1:
        return parts_to_merge[0]

    merged_pbf = cache_dir / f"{pack_code}_buffered_merged.osm.pbf"
    run_cmd([osmium_bin, "merge", "-O", "-o", str(merged_pbf)] + [str(p) for p in parts_to_merge])

    # Remove intermediate clips to keep runner disk usage low
    for p in parts_to_merge:
        if p != primary_pbf:
            p.unlink(missing_ok=True)
    return merged_pbf


def build_valhalla_tiles(pack_code: str, pbf_path: Path, work_dir: Path, out_tar: Path) -> None:
    tile_dir = work_dir / f"valhalla_tiles_{pack_code}"
    if tile_dir.exists():
        shutil.rmtree(tile_dir)
    tile_dir.mkdir(parents=True, exist_ok=True)

    config_path = work_dir / f"valhalla_{pack_code}.json"
    print(f"[valhalla] Generating Valhalla config at {config_path}", flush=True)
    with config_path.open("w") as cfg_file:
        subprocess.run([
            "valhalla_build_config",
            "--mjolnir-tile-dir", str(tile_dir),
            "--mjolnir-tile-extract", "",
            "--mjolnir-traffic-extract", "",
            "--mjolnir-admin", "",
            "--mjolnir-timezone", "",
        ], stdout=cfg_file, check=True)

    print(f"[valhalla] Building routing tiles for {pack_code.upper()} from {pbf_path.name}", flush=True)
    run_cmd(["valhalla_build_tiles", "-c", str(config_path), str(pbf_path)])

    print(f"[valhalla] Packing routing tiles into {out_tar}", flush=True)
    with tarfile.open(out_tar, "w") as tar:
        for root, _, files in os.walk(tile_dir):
            for fname in files:
                full = Path(root) / fname
                rel = full.relative_to(tile_dir)
                tar.add(full, arcname=str(rel))

    shutil.rmtree(tile_dir, ignore_errors=True)


def _duckdb_struct_has_field(col_type: str, field_name: str) -> bool:
    """Best-effort check for whether a DuckDB STRUCT type string declares `field_name`."""
    t = col_type.lower()
    f = field_name.lower()
    return (
        f'"{f}" ' in t
        or f" {f} " in t
        or f"({f} " in t
        or f",{f} " in t
        or f", {f} " in t
    )


def _build_category_keywords(*parts: str | None) -> str:
    """Combine primary, basic, hierarchy, and alternate category labels into searchable tokens."""
    seen: list[str] = []
    seen_set: set[str] = set()
    for part in parts:
        if not part:
            continue
        for raw in str(part).replace(",", " ").replace(";", " ").split():
            tok = raw.strip().lower()
            if not tok:
                continue
            if tok not in seen_set:
                seen_set.add(tok)
                seen.append(tok)
            if "_" in tok:
                for sub in tok.split("_"):
                    sub = sub.strip()
                    if sub and sub not in seen_set:
                        seen_set.add(sub)
                        seen.append(sub)
    return " ".join(seen)


def _overture_filter_expressions(
    cols: dict[str, str],
    bbox: tuple[float, float, float, float],
) -> tuple[str, str, str, str]:
    """
    Return the four sequential Overture filter SQL expressions:
      (bbox_cond, country_cond, name_cond, status_cond)
    Intentionally does NOT filter on addresses[1].region because Overture region
    values are inconsistent across releases and state formats.
    """
    min_lon, min_lat, max_lon, max_lat = bbox
    if "bbox" in cols:
        bbox_cond = (
            f"(bbox.xmin >= {min_lon} AND bbox.xmax <= {max_lon} "
            f"AND bbox.ymin >= {min_lat} AND bbox.ymax <= {max_lat})"
        )
    else:
        bbox_cond = (
            f"(ST_X(geometry) BETWEEN {min_lon} AND {max_lon} "
            f"AND ST_Y(geometry) BETWEEN {min_lat} AND {max_lat})"
        )

    addr_type = cols.get("addresses", "")
    has_addresses = "addresses" in cols
    if has_addresses and _duckdb_struct_has_field(addr_type, "country"):
        country_cond = (
            "(addresses IS NULL OR len(addresses) = 0 "
            "OR addresses[1].country IS NULL OR addresses[1].country = 'US')"
        )
    else:
        country_cond = "TRUE"

    name_cond = "(names.primary IS NOT NULL AND TRIM(names.primary) != '')"

    if "operating_status" in cols:
        status_cond = (
            "(operating_status IS NULL OR "
            "(LOWER(operating_status) NOT LIKE '%permanently_closed%' "
            "AND LOWER(operating_status) NOT IN ('closed_permanently', 'closed')))"
        )
    else:
        status_cond = "TRUE"

    return bbox_cond, country_cond, name_cond, status_cond


def build_overture_filter_counts_sql(
    columns: dict[str, str],
    overture_s3: str,
    bbox: tuple[float, float, float, float],
) -> str:
    """Build a single-pass DuckDB diagnostic query returning row counts after each filter step."""
    cols = {k.lower(): (v or "") for k, v in columns.items()}
    bbox_cond, country_cond, name_cond, status_cond = _overture_filter_expressions(cols, bbox)
    return f"""
        SELECT
            COUNT(*) AS rows_in_bbox,
            COUNT(*) FILTER (WHERE {country_cond}) AS rows_after_country,
            COUNT(*) FILTER (WHERE {country_cond} AND {name_cond}) AS rows_after_name,
            COUNT(*) FILTER (WHERE {country_cond} AND {name_cond} AND {status_cond}) AS rows_after_operating_status
        FROM read_parquet('{overture_s3}', filename=false, hive_partitioning=1)
        WHERE {bbox_cond}
    """


def build_overture_places_sql(
    columns: dict[str, str],
    overture_s3: str,
    bbox: tuple[float, float, float, float],
    base_state_abbr: str,
) -> str:
    """
    Build a schema-resilient DuckDB SQL query from DESCRIBE output so Overture 2026+
    (`taxonomy`, `basic_category`, `operating_status`), older releases (`categories`),
    and future schema changes work without BinderException failures.
    """
    cols = {k.lower(): (v or "") for k, v in columns.items()}

    if "names" not in cols or not _duckdb_struct_has_field(cols["names"], "primary"):
        raise ValueError("Overture schema is missing required 'names.primary' column")
    if "geometry" not in cols:
        raise ValueError("Overture schema is missing required 'geometry' column")

    # Brand name (e.g., "Best Buy")
    if "brand" in cols and _duckdb_struct_has_field(cols["brand"], "names"):
        brand_expr = "COALESCE(brand.names.primary, '')"
    else:
        brand_expr = "''"

    # Main category: taxonomy.primary -> basic_category -> legacy categories.primary -> ''
    has_taxonomy = "taxonomy" in cols
    has_tax_primary = has_taxonomy and _duckdb_struct_has_field(cols["taxonomy"], "primary")
    has_tax_hierarchy = has_taxonomy and _duckdb_struct_has_field(cols["taxonomy"], "hierarchy")
    has_tax_alternates = has_taxonomy and _duckdb_struct_has_field(cols["taxonomy"], "alternates")
    has_basic_cat = "basic_category" in cols
    has_legacy_cats = "categories" in cols
    has_legacy_primary = has_legacy_cats and _duckdb_struct_has_field(cols["categories"], "primary")
    has_legacy_alt = has_legacy_cats and _duckdb_struct_has_field(cols["categories"], "alternate")

    cat_candidates: list[str] = []
    if has_tax_primary:
        cat_candidates.append("NULLIF(taxonomy.primary, '')")
    if has_basic_cat:
        cat_candidates.append("NULLIF(basic_category, '')")
    if has_legacy_primary:
        cat_candidates.append("NULLIF(categories.primary, '')")
    category_expr = f"COALESCE({', '.join(cat_candidates)}, '')" if cat_candidates else "''"

    # Extra searchable category words from taxonomy.primary, basic_category, hierarchy, alternates
    extra_cat_parts: list[str] = []
    if has_tax_primary:
        extra_cat_parts.append("NULLIF(taxonomy.primary, '')")
    if has_basic_cat:
        extra_cat_parts.append("NULLIF(basic_category, '')")
    if has_tax_hierarchy:
        extra_cat_parts.append("NULLIF(array_to_string(taxonomy.hierarchy, ' '), '')")
    if has_tax_alternates:
        extra_cat_parts.append("NULLIF(array_to_string(taxonomy.alternates, ' '), '')")
    if has_legacy_primary:
        extra_cat_parts.append("NULLIF(categories.primary, '')")
    if has_legacy_alt:
        extra_cat_parts.append("NULLIF(array_to_string(categories.alternate, ' '), '')")
    extra_cat_expr = f"concat_ws(' ', {', '.join(extra_cat_parts)})" if extra_cat_parts else "''"

    # Addresses (freeform, locality, region, postcode, country)
    addr_type = cols.get("addresses", "")
    has_addresses = "addresses" in cols
    addr_expr = "COALESCE(addresses[1].freeform, '')" if (has_addresses and _duckdb_struct_has_field(addr_type, "freeform")) else "''"
    city_expr = "COALESCE(addresses[1].locality, '')" if (has_addresses and _duckdb_struct_has_field(addr_type, "locality")) else "''"
    state_expr = (
        f"COALESCE(addresses[1].region, '{base_state_abbr}')"
        if (has_addresses and _duckdb_struct_has_field(addr_type, "region"))
        else f"'{base_state_abbr}'"
    )
    postcode_expr = "COALESCE(addresses[1].postcode, '')" if (has_addresses and _duckdb_struct_has_field(addr_type, "postcode")) else "''"

    # Confidence -> popularity score
    popularity_expr = "COALESCE(confidence, 0.8) * 5.0" if "confidence" in cols else "4.0"

    bbox_cond, country_cond, name_cond, status_cond = _overture_filter_expressions(cols, bbox)
    where_clauses = [bbox_cond]
    if country_cond != "TRUE":
        where_clauses.append(country_cond)
    where_clauses.append(name_cond)
    if status_cond != "TRUE":
        where_clauses.append(status_cond)

    where_sql = "\n          AND ".join(where_clauses)
    return f"""
        SELECT
            names.primary AS name,
            {brand_expr} AS brand,
            {category_expr} AS category,
            {extra_cat_expr} AS category_keywords,
            {addr_expr} AS address,
            {city_expr} AS city,
            {state_expr} AS state,
            {postcode_expr} AS postcode,
            ST_Y(geometry) AS lat,
            ST_X(geometry) AS lon,
            {popularity_expr} AS popularity
        FROM read_parquet('{overture_s3}', filename=false, hive_partitioning=1)
        WHERE {where_sql}
    """


EUGENE_OR_LAT = 44.0521
EUGENE_OR_LON = -123.0868
EUGENE_CHECK_RADIUS_M = 15_000.0
FULL_STATE_MIN_OVERTURE_PLACES = 50_000
DEDUP_MAX_DISTANCE_M = 50.0


def _haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    return 2.0 * r * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def _normalize_place_name(name: str) -> str:
    return re.sub(r"[^\w]+", " ", (name or "").lower().replace("_", " ")).strip()


def _address_completeness_score(row: tuple) -> tuple[int, int, int, int]:
    """
    Score address completeness for a 12-tuple place row:
      (name, brand, category, category_keywords, address, city, state, postcode, lat, lon, source, popularity)
    Prefers rows with street address, city, postcode, and state filled in.
    """
    address = (row[4] or "").strip()
    city = (row[5] or "").strip()
    state = (row[6] or "").strip()
    postcode = (row[7] or "").strip()
    source = (row[10] or "").strip().lower()

    has_address = 1 if address else 0
    has_city = 1 if city else 0
    has_postcode = 1 if postcode else 0
    has_state = 1 if state else 0
    weighted = has_address * 4 + has_city * 2 + has_postcode * 2 + has_state
    field_count = has_address + has_city + has_postcode + has_state
    char_len = len(address) + len(city) + len(postcode)
    source_tiebreak = 1 if source == "overture" else 0
    return (weighted, field_count, char_len, source_tiebreak)


def deduplicate_overture_and_osm_places(
    overture_rows: list[tuple],
    osm_rows: list[tuple],
    max_distance_m: float = DEDUP_MAX_DISTANCE_M,
) -> tuple[list[tuple], list[tuple], int]:
    """
    When an Overture place and an OSM place share the same normalized name within
    `max_distance_m` meters (50 m), keep only one, preferring the one with the more
    complete address. All other places from both sources are preserved.
    """
    if not overture_rows or not osm_rows:
        return list(overture_rows), list(osm_rows), 0

    cell_deg = 0.001  # ~111 m lat, ~78 m lon at mid-US latitudes; 3x3 search safely covers 50 m
    overture_grid: dict[tuple[str, int, int], list[int]] = {}
    for idx, row in enumerate(overture_rows):
        norm = _normalize_place_name(row[0])
        if not norm:
            continue
        clat = int(math.floor(row[8] / cell_deg))
        clon = int(math.floor(row[9] / cell_deg))
        overture_grid.setdefault((norm, clat, clon), []).append(idx)

    dropped_overture: set[int] = set()
    kept_osm: list[tuple] = []
    dedup_removed = 0

    for osm_row in osm_rows:
        norm = _normalize_place_name(osm_row[0])
        if not norm:
            kept_osm.append(osm_row)
            continue

        lat = osm_row[8]
        lon = osm_row[9]
        clat = int(math.floor(lat / cell_deg))
        clon = int(math.floor(lon / cell_deg))

        best_match_idx = -1
        best_match_dist = max_distance_m + 1.0
        for dlat in (-1, 0, 1):
            for dlon in (-1, 0, 1):
                candidates = overture_grid.get((norm, clat + dlat, clon + dlon))
                if not candidates:
                    continue
                for ov_idx in candidates:
                    if ov_idx in dropped_overture:
                        continue
                    ov_row = overture_rows[ov_idx]
                    dist = _haversine_meters(lat, lon, ov_row[8], ov_row[9])
                    if dist <= max_distance_m and dist < best_match_dist:
                        best_match_dist = dist
                        best_match_idx = ov_idx

        if best_match_idx >= 0:
            dedup_removed += 1
            ov_row = overture_rows[best_match_idx]
            if _address_completeness_score(osm_row) > _address_completeness_score(ov_row):
                dropped_overture.add(best_match_idx)
                kept_osm.append(osm_row)
            # Else keep the Overture row and drop this OSM duplicate
        else:
            kept_osm.append(osm_row)

    kept_overture = [row for idx, row in enumerate(overture_rows) if idx not in dropped_overture]
    return kept_overture, kept_osm, dedup_removed


def build_places_fts5_db(
    pack_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    pbf_path: Path,
    overture_release: str,
    out_db: Path,
) -> str:
    import osmium

    import math
    import re

    CARDINAL_DIRECTIONS = {
        "N": 0.0, "NNE": 22.5, "NE": 45.0, "ENE": 67.5,
        "E": 90.0, "ESE": 112.5, "SE": 135.0, "SSE": 157.5,
        "S": 180.0, "SSW": 202.5, "SW": 225.0, "WSW": 247.5,
        "W": 270.0, "WNW": 292.5, "NW": 315.0, "NNW": 337.5,
    }

    def _parse_maxspeed_mph(raw_val: str | None) -> int | None:
        if not raw_val:
            return None
        s = raw_val.strip().lower()
        m = re.search(r"(\d+(?:\.\d+)?)", s)
        if not m:
            return None
        val = float(m.group(1))
        if val <= 0:
            return None
        if "km" in s or "kph" in s:
            return max(5, int(round(val * 0.621371)))
        return max(5, int(round(val)))

    def _parse_direction_degrees(raw_val: str | None) -> float | None:
        if not raw_val:
            return None
        first_token = raw_val.split(";")[0].strip().upper()
        if first_token in CARDINAL_DIRECTIONS:
            return CARDINAL_DIRECTIONS[first_token]
        try:
            deg = float(first_token)
            if math.isfinite(deg):
                return deg % 360.0
        except ValueError:
            pass
        return None

    def _bearing_degrees(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dlon = math.radians(lon2 - lon1)
        y = math.sin(dlon) * math.cos(phi2)
        x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dlon)
        return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0

    def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        r = 6371000.0
        p1, p2 = math.radians(lat1), math.radians(lat2)
        dp = math.radians(lat2 - lat1)
        dl = math.radians(lon2 - lon1)
        a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
        return 2.0 * r * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))

    class OsmPlaceAndHazardHandler(osmium.SimpleHandler):
        def __init__(self, abbr: str, place_batch: list, hazard_batch: list):
            super().__init__()
            self.state_abbr = abbr
            self.places = place_batch
            self.hazards = hazard_batch
            self.speed_camera_nodes: dict[int, dict] = {}
            self.enforcement_relations: list[dict] = []

        def _process(self, tags, lat: float, lon: float, osm_id: str | None = None, node_id: int | None = None):
            if not (bbox[1] <= lat <= bbox[3] and bbox[0] <= lon <= bbox[2]):
                return

            # Road hazards for Phase 3 alerts + speed cameras
            if tags.get("highway") == "speed_camera" and node_id is not None:
                self.speed_camera_nodes[node_id] = {
                    "type": "speed_camera",
                    "lat": lat,
                    "lon": lon,
                    "label": tags.get("name", "Speed Camera"),
                    "speed_limit_mph": _parse_maxspeed_mph(tags.get("maxspeed")),
                    "direction": _parse_direction_degrees(tags.get("direction")),
                    "osm_id": f"node/{node_id}",
                }
            elif tags.get("railway") in ("level_crossing", "crossing"):
                self.hazards.append(("railroad", lat, lon, tags.get("name", "Railroad Crossing"), None, None, osm_id))
            elif tags.get("highway") == "stop":
                self.hazards.append(("stop_sign", lat, lon, tags.get("name", "Stop Sign"), None, None, osm_id))
            elif tags.get("highway") == "traffic_signals":
                self.hazards.append(("traffic_signal", lat, lon, tags.get("name", "Traffic Signal"), None, None, osm_id))
            elif tags.get("barrier") == "toll_booth":
                self.hazards.append(("toll_booth", lat, lon, tags.get("name", "Toll Booth"), None, None, osm_id))
            elif tags.get("amenity") == "school" or tags.get("zone:traffic") == "US:school":
                self.hazards.append(("school_zone", lat, lon, tags.get("name", "School Zone"), 20, None, osm_id))

            # OSM POIs, cities, and street addresses
            name = tags.get("name", "").strip()
            housenumber = tags.get("addr:housenumber", "").strip()
            street = tags.get("addr:street", "").strip()
            city = tags.get("addr:city", "").strip()
            postcode = tags.get("addr:postcode", "").strip()
            brand = tags.get("brand", "").strip()

            category = (
                tags.get("shop")
                or tags.get("amenity")
                or tags.get("tourism")
                or tags.get("healthcare")
                or tags.get("place")
                or ""
            ).strip()

            address = f"{housenumber} {street}".strip()
            if not name and address:
                name = address
                category = category or "address"
            if not name:
                return

            is_poi = bool(category)
            is_address = bool(housenumber and street)
            if is_poi or is_address:
                popularity = 3.0 if brand else (2.5 if tags.get("place") in ("city", "town") else 1.5 if is_poi else 1.0)
                category_keywords = _build_category_keywords(
                    category,
                    tags.get("shop"),
                    tags.get("amenity"),
                    tags.get("cuisine"),
                )
                self.places.append((
                    name, brand, category, category_keywords, address, city, self.state_abbr,
                    postcode, lat, lon, "osm", popularity,
                ))

        def node(self, n):
            if n.location.valid():
                self._process(n.tags, n.location.lat, n.location.lon, osm_id=f"node/{n.id}", node_id=n.id)

        def relation(self, r):
            if r.tags.get("type") != "enforcement":
                return
            enforcement = (r.tags.get("enforcement") or "").strip().lower()
            if enforcement == "maxspeed":
                cam_type = "speed_camera"
            elif enforcement == "traffic_signals":
                cam_type = "red_light_camera"
            else:
                return
            device_nodes: list[int] = []
            from_nodes: list[int] = []
            from_ways: list[int] = []
            for m in r.members:
                role = (m.role or "").strip().lower()
                if role == "device" and m.type == "n":
                    device_nodes.append(m.ref)
                elif role == "from" and m.type == "n":
                    from_nodes.append(m.ref)
                elif role == "from" and m.type == "w":
                    from_ways.append(m.ref)
            if not device_nodes:
                return
            self.enforcement_relations.append({
                "rel_id": r.id,
                "type": cam_type,
                "device_node_id": device_nodes[0],
                "from_node_id": from_nodes[0] if from_nodes else None,
                "from_way_id": from_ways[0] if from_ways else None,
                "maxspeed_mph": _parse_maxspeed_mph(r.tags.get("maxspeed")),
                "direction": _parse_direction_degrees(r.tags.get("direction")),
                "label": r.tags.get("name") or ("Speed Camera" if cam_type == "speed_camera" else "Red Light Camera"),
            })

        def area(self, a):
            try:
                lats, lons = [], []
                for ring in a.outer_rings():
                    for node in ring:
                        if node.location.valid():
                            lats.append(node.location.lat)
                            lons.append(node.location.lon)
                    break
                if lats:
                    self._process(a.tags, sum(lats) / len(lats), sum(lons) / len(lons), osm_id=f"area/{a.id}")
            except Exception:
                pass

    class EnforcementGeometryResolver(osmium.SimpleHandler):
        def __init__(self, wanted_nodes: set[int], wanted_ways: set[int]):
            super().__init__()
            self.wanted_nodes = wanted_nodes
            self.wanted_ways = wanted_ways
            self.node_coords: dict[int, tuple[float, float]] = {}
            self.node_tags: dict[int, dict] = {}
            self.way_coords: dict[int, tuple[float, float]] = {}

        def node(self, n):
            if n.id in self.wanted_nodes and n.location.valid():
                self.node_coords[n.id] = (n.location.lat, n.location.lon)
                self.node_tags[n.id] = {
                    "maxspeed": n.tags.get("maxspeed"),
                    "direction": n.tags.get("direction"),
                    "name": n.tags.get("name"),
                }

        def way(self, w):
            if w.id not in self.wanted_ways:
                return
            coords = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
            if coords:
                self.way_coords[w.id] = coords[0]

    if out_db.exists():
        out_db.unlink()

    conn = sqlite3.connect(str(out_db))
    cur = conn.cursor()
    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;

        CREATE TABLE places (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            brand TEXT DEFAULT '',
            category TEXT DEFAULT '',
            category_keywords TEXT DEFAULT '',
            address TEXT DEFAULT '',
            city TEXT DEFAULT '',
            state TEXT DEFAULT '',
            postcode TEXT DEFAULT '',
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            source TEXT DEFAULT 'overture',
            popularity REAL DEFAULT 1.0
        );
        CREATE INDEX idx_places_lat_lon ON places(lat, lon);
        CREATE INDEX idx_places_state ON places(state);

        CREATE TABLE road_hazards (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            label TEXT DEFAULT '',
            speed_limit_mph INTEGER,
            direction REAL,
            osm_id TEXT
        );
        CREATE INDEX idx_hazards_lat_lon ON road_hazards(lat, lon);
        CREATE INDEX idx_hazards_type ON road_hazards(type);
    """)

    # 1. Query Overture Maps Places GeoParquet from AWS S3 via DuckDB with schema inspection
    base_state_abbr = state_abbr.split("-")[0]
    overture_s3 = f"s3://overturemaps-us-west-2/release/{overture_release}/theme=places/type=place/*"
    places_source = "overture+osm"
    normalized_overture_rows: list[tuple] = []
    duck = None
    try:
        import duckdb

        print(f"[overture] Inspecting & querying Overture Places ({overture_release}) for {pack_code.upper()} via DuckDB...", flush=True)
        duck = duckdb.connect()
        duck.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial;")
        duck.execute("SET s3_region='us-west-2'; SET s3_access_key_id=''; SET s3_secret_access_key='';")

        describe_rows = duck.execute(
            f"DESCRIBE SELECT * FROM read_parquet('{overture_s3}', filename=false, hive_partitioning=1)"
        ).fetchall()
        schema_cols = {str(r[0]): str(r[1]) for r in describe_rows}
        print(f"[overture] Discovered {len(schema_cols)} parquet columns: {', '.join(sorted(schema_cols.keys()))}", flush=True)

        counts_sql = build_overture_filter_counts_sql(
            columns=schema_cols,
            overture_s3=overture_s3,
            bbox=bbox,
        )
        cnt_bbox, cnt_country, cnt_name, cnt_status = duck.execute(counts_sql).fetchone()
        print(
            f"[overture] Row counts for {pack_code.upper()}: "
            f"in_bbox={cnt_bbox:,} -> after_country={cnt_country:,} -> "
            f"after_name={cnt_name:,} -> after_operating_status={cnt_status:,}",
            flush=True,
        )

        overture_query = build_overture_places_sql(
            columns=schema_cols,
            overture_s3=overture_s3,
            bbox=bbox,
            base_state_abbr=base_state_abbr,
        )
        raw_overture_rows = duck.execute(overture_query).fetchall()
        for row in raw_overture_rows:
            name, brand, category, raw_cat_kw, address, city, state_val, postcode, lat, lon, popularity = row
            cat_keywords = _build_category_keywords(category, raw_cat_kw)
            normalized_overture_rows.append((
                name, brand or "", category or "", cat_keywords, address or "",
                city or "", state_val or base_state_abbr, postcode or "",
                lat, lon, "overture", popularity,
            ))

        print(f"[overture] Loaded {len(normalized_overture_rows):,} Overture businesses for {pack_code.upper()}", flush=True)
    except Exception as exc:
        places_source = "osm-only"
        normalized_overture_rows = []
        print(
            f"RAHYAB WARNING: Overture Places query failed for {pack_code.upper()} ({exc}); "
            "building places database from OpenStreetMap only.",
            flush=True,
        )
    finally:
        if duck is not None:
            try:
                duck.close()
            except Exception:
                pass

    # 2. Extract OSM addresses, POIs, and road hazards
    print(f"[osm] Extracting addresses, POIs, and hazards from {pbf_path.name}...", flush=True)
    osm_places: list = []
    osm_hazards: list = []
    handler = OsmPlaceAndHazardHandler(base_state_abbr, osm_places, osm_hazards)
    handler.apply_file(str(pbf_path), locations=True)

    # 2a. Resolve enforcement relations (speed cameras & red light cameras) and deduplicate cameras
    camera_records: list[dict] = []
    consumed_device_nodes: set[int] = set()
    if handler.enforcement_relations:
        wanted_nodes: set[int] = set()
        wanted_ways: set[int] = set()
        for rel in handler.enforcement_relations:
            wanted_nodes.add(rel["device_node_id"])
            if rel["from_node_id"] is not None:
                wanted_nodes.add(rel["from_node_id"])
            if rel["from_way_id"] is not None:
                wanted_ways.add(rel["from_way_id"])
        resolver = EnforcementGeometryResolver(wanted_nodes, wanted_ways)
        resolver.apply_file(str(pbf_path), locations=True)
        for rel in handler.enforcement_relations:
            dev_id = rel["device_node_id"]
            dev_coord = resolver.node_coords.get(dev_id)
            if not dev_coord:
                continue
            lat, lon = dev_coord
            if not (bbox[1] <= lat <= bbox[3] and bbox[0] <= lon <= bbox[2]):
                continue
            node_meta = resolver.node_tags.get(dev_id, {})
            direction = rel["direction"]
            if direction is None:
                direction = _parse_direction_degrees(node_meta.get("direction"))
            if direction is None:
                from_coord = None
                if rel["from_node_id"] is not None:
                    from_coord = resolver.node_coords.get(rel["from_node_id"])
                elif rel["from_way_id"] is not None:
                    from_coord = resolver.way_coords.get(rel["from_way_id"])
                if from_coord and (abs(from_coord[0] - lat) > 1e-6 or abs(from_coord[1] - lon) > 1e-6):
                    direction = _bearing_degrees(from_coord[0], from_coord[1], lat, lon)
            maxspeed_mph = rel["maxspeed_mph"]
            if maxspeed_mph is None:
                maxspeed_mph = _parse_maxspeed_mph(node_meta.get("maxspeed"))
            consumed_device_nodes.add(dev_id)
            camera_records.append({
                "type": rel["type"],
                "lat": lat,
                "lon": lon,
                "label": rel["label"] or node_meta.get("name") or "",
                "speed_limit_mph": maxspeed_mph,
                "direction": direction,
                "osm_id": f"relation/{rel['rel_id']}",
            })

    for node_id, cam in handler.speed_camera_nodes.items():
        if node_id not in consumed_device_nodes:
            camera_records.append(cam)

    # Deduplicate cameras by osm_id and by proximity (<= 25 m for same camera type)
    deduped_cameras: list[dict] = []
    seen_camera_osm_ids: set[str] = set()
    for cam in camera_records:
        oid = cam.get("osm_id") or ""
        if oid and oid in seen_camera_osm_ids:
            continue
        is_near_dup = any(
            existing["type"] == cam["type"]
            and _haversine_m(existing["lat"], existing["lon"], cam["lat"], cam["lon"]) <= 25.0
            for existing in deduped_cameras
        )
        if is_near_dup:
            continue
        if oid:
            seen_camera_osm_ids.add(oid)
        deduped_cameras.append(cam)
        osm_hazards.append((
            cam["type"],
            cam["lat"],
            cam["lon"],
            cam["label"],
            cam["speed_limit_mph"],
            cam["direction"],
            cam["osm_id"],
        ))

    speed_cam_count = sum(1 for c in deduped_cameras if c["type"] == "speed_camera")
    red_light_cam_count = sum(1 for c in deduped_cameras if c["type"] == "red_light_camera")
    print(
        f"[osm] Extracted {len(osm_places):,} OSM places/addresses, {len(osm_hazards):,} road hazards "
        f"(speed_camera={speed_cam_count:,}, red_light_camera={red_light_cam_count:,}) for {pack_code.upper()}",
        flush=True,
    )

    # 2b. Cross-source deduplication: same normalized name within 50 m -> keep richer address
    dedup_overture, dedup_osm, dedup_removed = deduplicate_overture_and_osm_places(
        normalized_overture_rows,
        osm_places,
        max_distance_m=DEDUP_MAX_DISTANCE_M,
    )
    print(
        f"[dedup] Removed {dedup_removed:,} Overture/OSM duplicates within {int(DEDUP_MAX_DISTANCE_M)}m "
        f"(keeping {len(dedup_overture):,} Overture + {len(dedup_osm):,} OSM).",
        flush=True,
    )

    cur.executemany(
        "INSERT INTO places (name, brand, category, category_keywords, address, city, state, postcode, lat, lon, source, popularity) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        dedup_overture,
    )
    cur.executemany(
        "INSERT INTO places (name, brand, category, category_keywords, address, city, state, postcode, lat, lon, source, popularity) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        dedup_osm,
    )
    cur.executemany(
        "INSERT INTO road_hazards (type, lat, lon, label, speed_limit_mph, direction, osm_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        osm_hazards,
    )
    conn.commit()

    # 3. Build SQLite FTS5 index (6 columns matching Android OfflinePlaceSearchEngine bm25 weights;
    #    category column indexes taxonomy.primary + basic_category + hierarchy + alternates via view)
    print("[sqlite] Building FTS5 full-text search index...", flush=True)
    cur.executescript("""
        CREATE VIEW places_fts_src AS
        SELECT
            id,
            name,
            brand,
            CASE
                WHEN category_keywords IS NOT NULL AND category_keywords != '' THEN category_keywords
                ELSE category
            END AS category,
            address,
            city,
            postcode
        FROM places;

        CREATE VIRTUAL TABLE places_fts USING fts5(
            name, brand, category, address, city, postcode,
            content='places_fts_src', content_rowid='id',
            tokenize='unicode61 remove_diacritics 2',
            prefix='2 3 4'
        );
        INSERT INTO places_fts(places_fts) VALUES('rebuild');
        INSERT INTO places_fts(places_fts) VALUES('optimize');
        ANALYZE;
        VACUUM;
    """)
    conn.commit()

    # 4. Post-build verification check: total counts, full-state Overture warning, and Eugene 15 km Best Buy check
    total_places = cur.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    overture_count = cur.execute("SELECT COUNT(*) FROM places WHERE source = 'overture'").fetchone()[0]
    osm_count = cur.execute("SELECT COUNT(*) FROM places WHERE source = 'osm'").fetchone()[0]
    print(
        f"[check] {pack_code.upper()} places DB ready ({places_source}): "
        f"{total_places:,} total places ({overture_count:,} Overture, {osm_count:,} OSM).",
        flush=True,
    )

    is_full_state = "parent" not in ALL_PACK_CATALOG.get(pack_code.lower(), {})
    if is_full_state and places_source != "osm-only" and overture_count < FULL_STATE_MIN_OVERTURE_PLACES:
        print(
            f"RAHYAB WARNING: Full state {pack_code.upper()} has only {overture_count:,} Overture places "
            f"(expected at least {FULL_STATE_MIN_OVERTURE_PLACES:,}).",
            flush=True,
        )

    best_buy_candidates = cur.execute(
        """
        SELECT p.name, p.brand, p.category, p.address, p.city, p.state, p.postcode, p.lat, p.lon, p.source
        FROM places_fts
        JOIN places p ON p.id = places_fts.rowid
        WHERE places_fts MATCH '"best" "buy"'
        ORDER BY bm25(places_fts, 10.0, 12.0, 4.0, 5.0, 3.0, 2.0) ASC
        LIMIT 200
        """
    ).fetchall()
    eugene_best_buy = []
    for row in best_buy_candidates:
        dist_m = _haversine_meters(EUGENE_OR_LAT, EUGENE_OR_LON, row[7], row[8])
        if dist_m <= EUGENE_CHECK_RADIUS_M:
            eugene_best_buy.append((dist_m, row))
    eugene_best_buy.sort(key=lambda item: item[0])

    if eugene_best_buy:
        for idx, (dist_m, (b_name, b_brand, b_cat, b_addr, b_city, b_state, b_zip, _, _, b_src)) in enumerate(eugene_best_buy, start=1):
            formatted_addr = ", ".join(p for p in (b_addr, b_city, b_state, b_zip) if p)
            print(
                f"[check] Eugene Best Buy FTS5 match #{idx} ({dist_m / 1000.0:.2f} km from Eugene): "
                f"{b_name} (brand='{b_brand}', source='{b_src}') — {formatted_addr or '(no street address)'}",
                flush=True,
            )
    else:
        print(
            f"[check] Eugene Best Buy FTS5 search (within 15 km of {EUGENE_OR_LAT}, {EUGENE_OR_LON}) "
            f"returned 0 matches in {pack_code.upper()}.",
            flush=True,
        )

    conn.close()
    print(f"[sqlite] Built {out_db} ({out_db.stat().st_size / (1024 * 1024):.1f} MB)", flush=True)
    return places_source


def split_file_if_needed(path: Path, base_url: str, max_chunk_bytes: int = GITHUB_ASSET_SPLIT_BYTES) -> dict:
    """
    Creates a manifest PackFileEntry. If `path` exceeds GitHub Releases' 2 GiB asset limit
    (threshold 1.9 GiB), automatically splits it into `<name>.part001`, `<name>.part002`, ...
    and records every part + full-file SHA-256 so the Android app downloads and rejoins them.
    """
    total_size = path.stat().st_size
    full_sha = sha256_file(path)
    rel_name = path.name
    url = f"{base_url.rstrip('/')}/{rel_name}" if base_url else rel_name

    if total_size <= max_chunk_bytes:
        return {
            "filename": rel_name,
            "url": url,
            "sizeBytes": total_size,
            "sha256": full_sha,
            "parts": [],
        }

    print(f"[split] {rel_name} is {total_size / (1024**3):.2f} GiB (> 1.9 GiB); splitting for GitHub Releases...", flush=True)
    parts_meta = []
    part_idx = 1
    with path.open("rb") as src:
        while True:
            part_name = f"{rel_name}.part{part_idx:03d}"
            part_path = path.parent / part_name
            written = 0
            part_hash = hashlib.sha256()
            with part_path.open("wb") as dst:
                while written < max_chunk_bytes:
                    chunk = src.read(min(4 * 1024 * 1024, max_chunk_bytes - written))
                    if not chunk:
                        break
                    dst.write(chunk)
                    part_hash.update(chunk)
                    written += len(chunk)
            if written == 0:
                part_path.unlink(missing_ok=True)
                break
            part_url = f"{base_url.rstrip('/')}/{part_name}" if base_url else part_name
            parts_meta.append({
                "filename": part_name,
                "url": part_url,
                "sizeBytes": written,
                "sha256": part_hash.hexdigest(),
            })
            part_idx += 1

    # Remove the oversized unsplit file so CI only uploads valid <= 1.9 GiB release assets
    path.unlink(missing_ok=True)
    return {
        "filename": rel_name,
        "url": url,
        "sizeBytes": total_size,
        "sha256": full_sha,
        "parts": parts_meta,
    }


def write_pack_manifest_entry(
    pack_code: str,
    output_dir: Path,
    base_url: str,
    version: str,
    build_version: str,
    places_source: str = "overture+osm",
) -> dict:
    meta = ALL_PACK_CATALOG[pack_code]
    pm = output_dir / f"{pack_code}.pmtiles"
    vh = output_dir / f"{pack_code}_valhalla.tar"
    db = output_dir / f"{pack_code}_places.db"

    pm_e = split_file_if_needed(pm, base_url)
    vh_e = split_file_if_needed(vh, base_url)
    db_e = split_file_if_needed(db, base_url)

    entry = {
        "stateCode": pack_code,
        "name": meta["name"],
        "version": version,
        "buildVersion": build_version,
        "parentStateCode": meta.get("parent"),
        "placesSource": places_source,
        "totalSizeBytes": pm_e["sizeBytes"] + vh_e["sizeBytes"] + db_e["sizeBytes"],
        "pmtiles": pm_e,
        "valhalla": vh_e,
        "places": db_e,
    }
    single_manifest = output_dir / f"manifest_{pack_code}.json"
    single_manifest.write_text(json.dumps(entry, indent=2), encoding="utf-8")
    return entry


def merge_manifests(
    manifest_files: list[Path],
    output_manifest: Path,
    base_url: str,
    build_version: str,
    existing_manifest: Path | None = None,
) -> None:
    """Merges per-state/region manifest JSONs with any existing release manifest.json."""
    by_code: dict[str, dict] = {}
    if existing_manifest and existing_manifest.exists():
        try:
            old = json.loads(existing_manifest.read_text(encoding="utf-8"))
            for item in old.get("states", []):
                by_code[item["stateCode"].lower()] = item
        except Exception as exc:
            print(f"[warn] Could not read existing manifest {existing_manifest}: {exc}", flush=True)

    for mf in manifest_files:
        if not mf.exists():
            continue
        data = json.loads(mf.read_text(encoding="utf-8"))
        if "states" in data:
            for item in data["states"]:
                by_code[item["stateCode"].lower()] = item
        elif "stateCode" in data:
            by_code[data["stateCode"].lower()] = data

    merged = {
        "schemaVersion": 2,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "buildVersion": build_version,
        "baseUrl": base_url.rstrip("/"),
        "states": [by_code[k] for k in sorted(by_code.keys())],
    }
    output_manifest.write_text(json.dumps(merged, indent=2), encoding="utf-8")
    print(f"[manifest] Wrote {output_manifest} with {len(merged['states'])} pack(s).", flush=True)


def build_pack(
    pack_code: str,
    output_dir: Path,
    cache_dir: Path,
    protomaps_url: str,
    overture_release: str,
    base_url: str,
    version: str,
    build_version: str,
    skip_pmtiles: bool = False,
    skip_valhalla: bool = False,
    skip_places: bool = False,
) -> dict:
    code = pack_code.lower()
    if code not in ALL_PACK_CATALOG:
        raise SystemExit(f"Unknown state or region code: {pack_code}")

    meta = ALL_PACK_CATALOG[code]
    print(f"\n========== Building RahYab Pack: {meta['name']} ({code.upper()}) ==========", flush=True)
    primary_pbf = download_geofabrik_pbf(meta["slug"], cache_dir)

    if not skip_pmtiles:
        extract_pmtiles(code, meta["bbox"], protomaps_url, output_dir / f"{code}.pmtiles")

    if not skip_valhalla:
        buffered_pbf = prepare_buffered_routing_pbf(code, meta, primary_pbf, cache_dir)
        build_valhalla_tiles(code, buffered_pbf, cache_dir, output_dir / f"{code}_valhalla.tar")
        if buffered_pbf != primary_pbf:
            buffered_pbf.unlink(missing_ok=True)

    places_source = "overture+osm"
    if not skip_places:
        places_source = build_places_fts5_db(
            code, meta["abbr"], meta["bbox"], primary_pbf, overture_release, output_dir / f"{code}_places.db"
        )

    return write_pack_manifest_entry(code, output_dir, base_url, version, build_version, places_source=places_source)


def main():
    parser = argparse.ArgumentParser(description="Build offline RahYab US state & regional packs")
    parser.add_argument("--state", nargs="+", help="State or region code(s), e.g. or wa ca-north")
    parser.add_argument("--all", action="store_true", help="Build all 50 states + D.C. (splitting CA/TX/FL/NY)")
    parser.add_argument("--resolve-matrix", help="Print JSON array of resolved pack codes for GitHub Actions matrix")
    parser.add_argument("--no-split-large", action="store_true", help="Do not auto-split CA/TX/FL/NY into regions")
    parser.add_argument("--merge-manifests", nargs="*", help="Merge per-pack manifest_*.json files into output manifest.json")
    parser.add_argument("--existing-manifest", help="Optional existing manifest.json to preserve untouched states")
    parser.add_argument("--output-dir", default="/output", help="Output directory for generated packs")
    parser.add_argument("--cache-dir", default="/cache", help="Cache directory for Geofabrik PBF downloads")
    parser.add_argument("--version", default=datetime.date.today().strftime("%Y.%m.%d"), help="Pack version string")
    parser.add_argument("--build-version", default=datetime.date.today().strftime("%Y.%m.%d"), help="Shared build version tag")
    parser.add_argument("--base-url", default=os.environ.get("PUBLIC_BASE_URL", ""), help="Public release download base URL")
    parser.add_argument(
        "--protomaps-url",
        default=os.environ.get("PROTOMAPS_BASEMAP_URL", "https://build.protomaps.com/20250219.pmtiles"),
        help="Protomaps daily basemap PMTiles URL",
    )
    parser.add_argument(
        "--overture-release",
        default=os.environ.get("OVERTURE_RELEASE", "2025-02-19.0"),
        help="Overture Maps release tag",
    )
    parser.add_argument("--skip-pmtiles", action="store_true")
    parser.add_argument("--skip-valhalla", action="store_true")
    parser.add_argument("--skip-places", action="store_true")

    args = parser.parse_args()

    if args.resolve_matrix is not None:
        resolved = resolve_pack_targets(args.resolve_matrix, split_large_states=not args.no_split_large)
        print(json.dumps(resolved))
        return

    if args.merge_manifests is not None:
        valid_files = [Path(p) for p in args.merge_manifests if Path(p).is_file()]
        if not valid_files:
            print(
                "[manifest] Skipping manifest merge: no new per-pack manifests found (all pack builds failed or were skipped).",
                flush=True,
            )
            return
        output_dir = Path(args.output_dir)
        existing = Path(args.existing_manifest) if args.existing_manifest else None
        merge_manifests(valid_files, output_dir / "manifest.json", args.base_url, args.build_version, existing)
        return

    raw_spec = "all" if args.all else " ".join(args.state or [])
    if not raw_spec.strip():
        parser.error("Specify --state <code...>, --all, --resolve-matrix, or --merge-manifests")

    output_dir = Path(args.output_dir)
    cache_dir = Path(args.cache_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)

    targets = resolve_pack_targets(raw_spec, split_large_states=not args.no_split_large)
    manifest_files: list[Path] = []
    for code in targets:
        build_pack(
            pack_code=code,
            output_dir=output_dir,
            cache_dir=cache_dir,
            protomaps_url=args.protomaps_url,
            overture_release=args.overture_release,
            base_url=args.base_url,
            version=args.version,
            build_version=args.build_version,
            skip_pmtiles=args.skip_pmtiles,
            skip_valhalla=args.skip_valhalla,
            skip_places=args.skip_places,
        )
        manifest_files.append(output_dir / f"manifest_{code}.json")

    merge_manifests(manifest_files, output_dir / "manifest.json", args.base_url, args.build_version)


if __name__ == "__main__":
    main()
