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
import os
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


def build_places_fts5_db(
    pack_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    pbf_path: Path,
    overture_release: str,
    out_db: Path,
) -> None:
    import duckdb
    import osmium

    class OsmPlaceAndHazardHandler(osmium.SimpleHandler):
        def __init__(self, abbr: str, place_batch: list, hazard_batch: list):
            super().__init__()
            self.state_abbr = abbr
            self.places = place_batch
            self.hazards = hazard_batch

        def _process(self, tags, lat: float, lon: float):
            if not (bbox[1] <= lat <= bbox[3] and bbox[0] <= lon <= bbox[2]):
                return

            # Road hazards for Phase 3 alerts
            if tags.get("railway") in ("level_crossing", "crossing"):
                self.hazards.append(("railroad", lat, lon, tags.get("name", "Railroad Crossing"), None))
            elif tags.get("highway") == "stop":
                self.hazards.append(("stop_sign", lat, lon, tags.get("name", "Stop Sign"), None))
            elif tags.get("highway") == "traffic_signals":
                self.hazards.append(("traffic_signal", lat, lon, tags.get("name", "Traffic Signal"), None))
            elif tags.get("barrier") == "toll_booth":
                self.hazards.append(("toll_booth", lat, lon, tags.get("name", "Toll Booth"), None))
            elif tags.get("amenity") == "school" or tags.get("zone:traffic") == "US:school":
                self.hazards.append(("school_zone", lat, lon, tags.get("name", "School Zone"), 20))

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
                self.places.append((
                    name, brand, category, address, city, self.state_abbr,
                    postcode, lat, lon, "osm", popularity,
                ))

        def node(self, n):
            if n.location.valid():
                self._process(n.tags, n.location.lat, n.location.lon)

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
                    self._process(a.tags, sum(lats) / len(lats), sum(lons) / len(lons))
            except Exception:
                pass

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
            speed_limit_mph INTEGER
        );
        CREATE INDEX idx_hazards_lat_lon ON road_hazards(lat, lon);
    """)

    # 1. Query Overture Maps Places GeoParquet from AWS S3 via DuckDB with bbox pushdown
    min_lon, min_lat, max_lon, max_lat = bbox
    base_state_abbr = state_abbr.split("-")[0]
    overture_s3 = f"s3://overturemaps-us-west-2/release/{overture_release}/theme=places/type=place/*"
    print(f"[overture] Querying Overture Places ({overture_release}) for {pack_code.upper()} via DuckDB...", flush=True)

    duck = duckdb.connect()
    duck.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial;")
    duck.execute("SET s3_region='us-west-2'; SET s3_access_key_id=''; SET s3_secret_access_key='';")

    overture_query = f"""
        SELECT
            names.primary AS name,
            COALESCE(brand.names.primary, '') AS brand,
            COALESCE(categories.primary, '') AS category,
            COALESCE(addresses[1].freeform, '') AS address,
            COALESCE(addresses[1].locality, '') AS city,
            COALESCE(addresses[1].region, '{base_state_abbr}') AS state,
            COALESCE(addresses[1].postcode, '') AS postcode,
            ST_Y(geometry) AS lat,
            ST_X(geometry) AS lon,
            COALESCE(confidence, 0.8) * 5.0 AS popularity
        FROM read_parquet('{overture_s3}', filename=false, hive_partitioning=1)
        WHERE bbox.xmin >= {min_lon} AND bbox.xmax <= {max_lon}
          AND bbox.ymin >= {min_lat} AND bbox.ymax <= {max_lat}
          AND names.primary IS NOT NULL
          AND (addresses IS NULL OR addresses[1].country = 'US')
          AND (addresses IS NULL OR addresses[1].region IS NULL OR addresses[1].region = '{base_state_abbr}')
    """
    overture_rows = duck.execute(overture_query).fetchall()
    print(f"[overture] Inserted {len(overture_rows):,} Overture businesses for {pack_code.upper()}", flush=True)
    cur.executemany(
        "INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'overture', ?)",
        overture_rows,
    )
    conn.commit()
    duck.close()

    # 2. Merge OSM addresses, POIs, and road hazards
    print(f"[osm] Extracting addresses, POIs, and hazards from {pbf_path.name}...", flush=True)
    osm_places: list = []
    osm_hazards: list = []
    handler = OsmPlaceAndHazardHandler(base_state_abbr, osm_places, osm_hazards)
    handler.apply_file(str(pbf_path), locations=True)
    print(f"[osm] Extracted {len(osm_places):,} OSM places/addresses and {len(osm_hazards):,} road hazards", flush=True)

    cur.executemany(
        "INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        osm_places,
    )
    cur.executemany(
        "INSERT INTO road_hazards (type, lat, lon, label, speed_limit_mph) VALUES (?, ?, ?, ?, ?)",
        osm_hazards,
    )
    conn.commit()

    # 3. Build SQLite FTS5 index
    print("[sqlite] Building FTS5 full-text search index...", flush=True)
    cur.executescript("""
        CREATE VIRTUAL TABLE places_fts USING fts5(
            name, brand, category, address, city, postcode,
            content='places', content_rowid='id',
            tokenize='unicode61 remove_diacritics 2',
            prefix='2 3 4'
        );
        INSERT INTO places_fts(places_fts) VALUES('rebuild');
        INSERT INTO places_fts(places_fts) VALUES('optimize');
        ANALYZE;
        VACUUM;
    """)
    conn.commit()
    conn.close()
    print(f"[sqlite] Built {out_db} ({out_db.stat().st_size / (1024 * 1024):.1f} MB)", flush=True)


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

    if not skip_places:
        build_places_fts5_db(code, meta["abbr"], meta["bbox"], primary_pbf, overture_release, output_dir / f"{code}_places.db")

    return write_pack_manifest_entry(code, output_dir, base_url, version, build_version)


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
