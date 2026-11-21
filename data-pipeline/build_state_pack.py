#!/usr/bin/env python3
"""
RahYab Offline State Pack Builder
---------------------------------
Builds the 3 offline files + manifest.json for one or all 50 US states + Washington, D.C.:
  1. <state>.pmtiles       — Protomaps vector basemap extract for MapLibre Native
  2. <state>_valhalla.tar  — Valhalla routing graph tiles built from Geofabrik OSM PBF
  3. <state>_places.db     — SQLite FTS5 search database merging Overture Maps Places
                             (businesses/stores via DuckDB) + OpenStreetMap addresses,
                             POIs, and road hazards (railroad crossings, school zones,
                             stop signs, toll booths).
"""

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
from pathlib import Path

import duckdb
import osmium
import requests

from states import US_STATES

GEOFABRIK_US_URL = "https://download.geofabrik.de/north-america/us/{slug}-latest.osm.pbf"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def run_cmd(cmd: list[str], cwd: Path | None = None) -> None:
    print(f"[cmd] {' '.join(cmd)}")
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def download_geofabrik_pbf(slug: str, cache_dir: Path) -> Path:
    dest = cache_dir / f"{slug}-latest.osm.pbf"
    if dest.exists() and dest.stat().st_size > 100_000:
        print(f"[cache] Reusing {dest}")
        return dest

    url = GEOFABRIK_US_URL.format(slug=slug)
    print(f"[download] Fetching Geofabrik extract: {url}")
    tmp = dest.with_suffix(".pbf.part")
    with requests.get(url, stream=True, timeout=60) as r:
        r.raise_for_status()
        with tmp.open("wb") as f:
            for chunk in r.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    f.write(chunk)
    tmp.rename(dest)
    return dest


def extract_pmtiles(state_code: str, bbox: tuple[float, float, float, float], protomaps_url: str, out_file: Path) -> None:
    min_lon, min_lat, max_lon, max_lat = bbox
    bbox_str = f"{min_lon},{min_lat},{max_lon},{max_lat}"
    print(f"[pmtiles] Extracting {state_code.upper()} ({bbox_str}) from {protomaps_url}")
    run_cmd([
        "pmtiles", "extract",
        protomaps_url,
        str(out_file),
        f"--bbox={bbox_str}",
        "--maxzoom=15",
    ])


def build_valhalla_tiles(state_code: str, pbf_path: Path, work_dir: Path, out_tar: Path) -> None:
    tile_dir = work_dir / f"valhalla_tiles_{state_code}"
    if tile_dir.exists():
        shutil.rmtree(tile_dir)
    tile_dir.mkdir(parents=True, exist_ok=True)

    config_path = work_dir / f"valhalla_{state_code}.json"
    print(f"[valhalla] Generating Valhalla config at {config_path}")
    with config_path.open("w") as cfg_file:
        subprocess.run([
            "valhalla_build_config",
            "--mjolnir-tile-dir", str(tile_dir),
            "--mjolnir-tile-extract", "",
            "--mjolnir-traffic-extract", "",
            "--mjolnir-admin", "",
            "--mjolnir-timezone", "",
        ], stdout=cfg_file, check=True)

    print(f"[valhalla] Building routing tiles for {state_code.upper()} from {pbf_path.name}")
    run_cmd(["valhalla_build_tiles", "-c", str(config_path), str(pbf_path)])

    print(f"[valhalla] Packing routing tiles into {out_tar}")
    with tarfile.open(out_tar, "w") as tar:
        for root, _, files in os.walk(tile_dir):
            for fname in files:
                full = Path(root) / fname
                rel = full.relative_to(tile_dir)
                tar.add(full, arcname=str(rel))

    shutil.rmtree(tile_dir, ignore_errors=True)


class OsmPlaceAndHazardHandler(osmium.SimpleHandler):
    """Extracts OSM addresses, POIs, cities, and driving road hazards from state PBF."""

    def __init__(self, state_abbr: str, place_batch: list, hazard_batch: list):
        super().__init__()
        self.state_abbr = state_abbr
        self.places = place_batch
        self.hazards = hazard_batch

    def _process(self, tags, lat: float, lon: float):
        # 1. Road hazards (railroad crossings, school zones, stop signs, toll booths)
        if tags.get("railway") in ("level_crossing", "crossing"):
            self.hazards.append(("railroad", lat, lon, tags.get("name", "Railroad Crossing"), None))
        elif tags.get("highway") == "stop":
            self.hazards.append(("stop_sign", lat, lon, tags.get("name", "Stop Sign"), None))
        elif tags.get("barrier") == "toll_booth":
            self.hazards.append(("toll_booth", lat, lon, tags.get("name", "Toll Booth"), None))
        elif tags.get("amenity") == "school" or tags.get("zone:traffic") == "US:school":
            self.hazards.append(("school_zone", lat, lon, tags.get("name", "School Zone"), 20))

        # 2. OSM POIs, Cities, and Street Addresses
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

        # Keep cities/towns, named POIs, and numbered addresses
        is_poi = bool(category)
        is_address = bool(housenumber and street)
        if is_poi or is_address:
            popularity = 3.0 if brand else (2.5 if tags.get("place") in ("city", "town") else 1.5 if is_poi else 1.0)
            self.places.append((
                name,
                brand,
                category,
                address,
                city,
                self.state_abbr,
                postcode,
                lat,
                lon,
                "osm",
                popularity,
            ))

    def node(self, n):
        if n.location.valid():
            self._process(n.tags, n.location.lat, n.location.lon)

    def area(self, a):
        # Centroid extraction for building/school/mall polygons
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


def build_places_fts5_db(
    state_code: str,
    state_abbr: str,
    bbox: tuple[float, float, float, float],
    pbf_path: Path,
    overture_release: str,
    out_db: Path,
) -> None:
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

    # 1. Query Overture Maps Places GeoParquet from AWS S3 via DuckDB
    min_lon, min_lat, max_lon, max_lat = bbox
    overture_s3 = f"s3://overturemaps-us-west-2/release/{overture_release}/theme=places/type=place/*"
    print(f"[overture] Querying Overture Maps Places ({overture_release}) for {state_abbr} via DuckDB...")

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
            COALESCE(addresses[1].region, '{state_abbr}') AS state,
            COALESCE(addresses[1].postcode, '') AS postcode,
            ST_Y(geometry) AS lat,
            ST_X(geometry) AS lon,
            COALESCE(confidence, 0.8) * 5.0 AS popularity
        FROM read_parquet('{overture_s3}', filename=false, hive_partitioning=1)
        WHERE bbox.xmin >= {min_lon} AND bbox.xmax <= {max_lon}
          AND bbox.ymin >= {min_lat} AND bbox.ymax <= {max_lat}
          AND names.primary IS NOT NULL
          AND (addresses IS NULL OR addresses[1].country = 'US')
          AND (addresses IS NULL OR addresses[1].region IS NULL OR addresses[1].region = '{state_abbr}')
    """

    overture_rows = duck.execute(overture_query).fetchall()
    print(f"[overture] Inserted {len(overture_rows):,} Overture businesses/places for {state_abbr}")

    cur.executemany(
        """
        INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'overture', ?)
        """,
        overture_rows,
    )
    conn.commit()
    duck.close()

    # 2. Merge OSM addresses, POIs, cities, and road hazards from Geofabrik PBF
    print(f"[osm] Extracting addresses, POIs, and road hazards from {pbf_path.name}...")
    osm_places: list = []
    osm_hazards: list = []
    handler = OsmPlaceAndHazardHandler(state_abbr, osm_places, osm_hazards)
    handler.apply_file(str(pbf_path), locations=True)

    print(f"[osm] Extracted {len(osm_places):,} OSM places/addresses and {len(osm_hazards):,} road hazards")
    cur.executemany(
        """
        INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        osm_places,
    )
    cur.executemany(
        """
        INSERT INTO road_hazards (type, lat, lon, label, speed_limit_mph)
        VALUES (?, ?, ?, ?, ?)
        """,
        osm_hazards,
    )
    conn.commit()

    # 3. Build SQLite FTS5 virtual table for instant prefix + BM25 offline search
    print("[sqlite] Building FTS5 full-text search index...")
    cur.executescript("""
        CREATE VIRTUAL TABLE places_fts USING fts5(
            name,
            brand,
            category,
            address,
            city,
            postcode,
            content='places',
            content_rowid='id',
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
    print(f"[sqlite] Built {out_db} ({out_db.stat().st_size / (1024 * 1024):.1f} MB)")


def update_manifest(output_dir: Path, base_url: str, version: str) -> None:
    manifest_path = output_dir / "manifest.json"
    states_entries = []

    for code, meta in sorted(US_STATES.items()):
        pm = output_dir / f"{code}.pmtiles"
        vh = output_dir / f"{code}_valhalla.tar"
        db = output_dir / f"{code}_places.db"
        if not (pm.exists() and vh.exists() and db.exists()):
            continue

        def entry(path: Path) -> dict:
            rel = path.name
            url = f"{base_url.rstrip('/')}/{rel}" if base_url else rel
            return {
                "filename": rel,
                "url": url,
                "sizeBytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }

        pm_e = entry(pm)
        vh_e = entry(vh)
        db_e = entry(db)
        states_entries.append({
            "stateCode": code,
            "name": meta["name"],
            "version": version,
            "totalSizeBytes": pm_e["sizeBytes"] + vh_e["sizeBytes"] + db_e["sizeBytes"],
            "pmtiles": pm_e,
            "valhalla": vh_e,
            "places": db_e,
        })

    manifest = {
        "schemaVersion": 1,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "baseUrl": base_url.rstrip("/"),
        "states": states_entries,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"[manifest] Wrote {manifest_path} with {len(states_entries)} state pack(s).")


def build_state(
    state_code: str,
    output_dir: Path,
    cache_dir: Path,
    protomaps_url: str,
    overture_release: str,
    skip_pmtiles: bool = False,
    skip_valhalla: bool = False,
    skip_places: bool = False,
) -> None:
    code = state_code.lower()
    if code not in US_STATES:
        raise SystemExit(f"Unknown state code: {state_code}. Use 2-letter US postal codes (e.g. or, ca, dc).")

    meta = US_STATES[code]
    print(f"\n========== Building RahYab Pack: {meta['name']} ({meta['abbr']}) ==========")
    pbf = download_geofabrik_pbf(meta["slug"], cache_dir)

    if not skip_pmtiles:
        extract_pmtiles(code, meta["bbox"], protomaps_url, output_dir / f"{code}.pmtiles")

    if not skip_valhalla:
        build_valhalla_tiles(code, pbf, cache_dir, output_dir / f"{code}_valhalla.tar")

    if not skip_places:
        build_places_fts5_db(code, meta["abbr"], meta["bbox"], pbf, overture_release, output_dir / f"{code}_places.db")


def main():
    parser = argparse.ArgumentParser(description="Build offline RahYab US state packs and manifest.json")
    parser.add_argument("--state", nargs="+", help="State code(s) to build (e.g. --state or wa ca)")
    parser.add_argument("--all", action="store_true", help="Build all 50 states + Washington, D.C.")
    parser.add_argument("--output-dir", default="/output", help="Output directory for generated packs")
    parser.add_argument("--cache-dir", default="/cache", help="Cache directory for Geofabrik PBF downloads")
    parser.add_argument("--version", default=datetime.date.today().strftime("%Y.%m.%d"), help="Pack version string")
    parser.add_argument("--base-url", default=os.environ.get("PUBLIC_BASE_URL", ""), help="Public static host Base URL")
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
    parser.add_argument("--manifest-only", action="store_true", help="Only regenerate manifest.json from existing files")
    parser.add_argument("--skip-pmtiles", action="store_true")
    parser.add_argument("--skip-valhalla", action="store_true")
    parser.add_argument("--skip-places", action="store_true")

    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    cache_dir = Path(args.cache_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)

    if not args.manifest_only:
        targets = list(US_STATES.keys()) if args.all else (args.state or [])
        if not targets:
            parser.error("Specify --state <code...> (e.g. --state or), --all, or --manifest-only")
        for code in targets:
            build_state(
                state_code=code,
                output_dir=output_dir,
                cache_dir=cache_dir,
                protomaps_url=args.protomaps_url,
                overture_release=args.overture_release,
                skip_pmtiles=args.skip_pmtiles,
                skip_valhalla=args.skip_valhalla,
                skip_places=args.skip_places,
            )

    update_manifest(output_dir, args.base_url, args.version)


if __name__ == "__main__":
    main()
