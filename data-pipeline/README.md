# RahYab Companion Data Pipeline

This folder builds the 100% free, open-data offline map packs used by the **RahYab** Android navigation app.

Each US state pack contains **three files**:
1. **`<state>.pmtiles`** — Vector map tiles extracted from the [Protomaps](https://protomaps.com/) basemap (OpenStreetMap).
2. **`<state>_valhalla.tar`** — Offline turn-by-turn routing graph tiles built with `valhalla_build_tiles` from [Geofabrik](https://download.geofabrik.de/north-america/us.html) OpenStreetMap extracts.
3. **`<state>_places.db`** — SQLite **FTS5** search database merging [Overture Maps Places](https://overturemaps.org/) (businesses, stores, brands via DuckDB) with OpenStreetMap street addresses, POIs, and driving road hazards (railroad crossings, school zones, stop signs, toll booths).

It also generates **`manifest.json`** with file sizes, versions, and SHA-256 checksums, ready to upload to any static file host (Cloudflare R2, Amazon S3, Backblaze B2, Netlify, or your own web server).

---

## Step 1: Install Docker (One-Time Setup)

You do **not** need to install Python, Valhalla, or DuckDB manually — Docker runs everything inside a clean container.

- **Windows / macOS**: Download and install [Docker Desktop](https://www.docker.com/products/docker-desktop/).
- **Linux**: Install Docker Engine and the Docker Compose plugin.

Open a terminal (PowerShell on Windows, Terminal on macOS/Linux) and navigate into this `data-pipeline` folder:

```bash
cd data-pipeline
```

---

## Step 2: Build the Pipeline Container

Run this once to build the Docker image:

```bash
docker compose build
```

---

## Step 3: Build Oregon First (Phase 1 Test Pack)

Start with **Oregon (`or`)** to verify everything works end-to-end:

```bash
docker compose run --rm pipeline --state or --base-url https://your-host.example.com/rahyab
```

When it finishes, look inside `data-pipeline/output/`. You will see:
- `or.pmtiles`
- `or_valhalla.tar`
- `or_places.db`
- `manifest.json`

### Verify the "Best Buy" Eugene, OR Search Database Locally

You can verify right on your computer that `or_places.db` contains Best Buy near Eugene/Springfield, OR:

```bash
sqlite3 output/or_places.db \
  "SELECT p.name, p.address, p.city, p.state, p.postcode
   FROM places_fts JOIN places p ON p.id = places_fts.rowid
   WHERE places_fts MATCH 'best* buy*'
   LIMIT 10;"
```

---

## Step 4: Load the Oregon Pack into RahYab

You have **two options**:

### Option A — Import Directly via USB / Local File (Fastest for Testing)
1. Zip the 3 files (`or.pmtiles`, `or_valhalla.tar`, `or_places.db`) into `or_pack.zip` (or copy the 3 files individually onto your Android phone/emulator Downloads folder).
2. In RahYab, open **Maps** → find **Oregon (OR)** → tap **Import** → choose `or_pack.zip` (or each file).
3. Turn off Wi-Fi & mobile data (Airplane Mode with GPS on) and search **"Best Buy"** or **"بست بای"**!

### Option B — Host Online on Any Static File Host
1. Upload everything in `data-pipeline/output/` (including `manifest.json`) to Cloudflare R2, Amazon S3, Backblaze B2, or any HTTPS static server with HTTP Range request support.
2. In RahYab, open **Settings** → **Map Pack Server** → paste your Base URL (e.g. `https://maps.example.com/rahyab`) → tap **Save URL**.
3. Open **Maps** → tap **Download** next to Oregon (or any state you built).

---

## Step 5: Build Neighboring States or All 51 States (Cross-State Routing)

To test cross-state routing (for example Oregon ↔ Washington or Oregon ↔ California):

```bash
docker compose run --rm pipeline --state or wa ca --base-url https://your-host.example.com/rahyab
```

To build **all 50 US states + Washington, D.C.** (requires ~100 GB free disk space and several hours):

```bash
docker compose run --rm pipeline --all --base-url https://your-host.example.com/rahyab
```

### Updating Data Sources
You can override the Overture Maps release date or Protomaps basemap snapshot URL with environment variables:

```bash
OVERTURE_RELEASE=2025-02-19.0 \
PROTOMAPS_BASEMAP_URL=https://build.protomaps.com/20250219.pmtiles \
docker compose run --rm pipeline --state or
```

---

## Data Licenses & Attribution
- **OpenStreetMap**: `© OpenStreetMap contributors` — Open Database License (ODbL).
- **Overture Maps Foundation**: Places dataset under CDLA Permissive 2.0 / ODbL.
- **Protomaps**: Open-source PMTiles vector basemap tooling (BSD-3-Clause).
- **Valhalla**: Open-source routing engine (MIT).
