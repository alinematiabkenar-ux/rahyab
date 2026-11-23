# RahYab v19–v21 Feature Batch Status

## P. Pipeline Fixes (`data-pipeline/`)
- [x] P1 Fix `addresses_fts` cursor-reset bug (separate read/write cursors + `fetchmany(25000)` + row-count assertion + Python unit test >1 batch)
- [x] P2 Fix `download_geofabrik_pbf` (no retry on HTTP 4xx/5xx, Geofabrik dated fallbacks last 10 days + 1st of current/prev month via HEAD, osm.fr fallback + California clip for norcal/socal, `osmium fileinfo -e` check, print URL & timestamp, dated URL generator unit test)
- [x] P3 Fix `extract_pmtiles` (retry 3x, fallback via `https://build-metadata.protomaps.dev/builds.json` trying previous 3 build keys newest first, print working URL)
- [x] P4 Stage error reporting in `build_pack` (`[FATAL] stage <name> failed: <type>: <message>` + exit 1) & `run_cmd` (print command, exit code, last 30 lines of output on failure)
- [x] P5 Identical `normalize_street` in Python & Kotlin (suffixes at any pos > 0, `way`/`wy`, all non-word chars -> spaces, shared >=20 street test table in Python & Kotlin)
- [x] P6 `python3 -m py_compile` on all pipeline files + run all Python tests

## A. App Address Search & Voice Fixes
- [x] A1 Distance-first SQL in `OfflineAddressSearchEngine.runFtsQuery` (`ORDER BY` approx distance with bound `cos(lat)` before `LIMIT`: 60 exact, 400 street-only)
- [x] A2 City fallback (retry exact & street-only without city terms if 0 rows, penalize non-matching city)
- [x] A3 Guard address engine so it only runs when query is address-like OR contains a street suffix word (skip plain business names like "best buy"/"starbucks")
- [x] A4 Split `OfflineAddressSearchEngine.kt` (<400 lines) by extracting `AddressInterpolation.kt`
- [x] A5 Fix `VoiceGuidanceManager.speakQueuedAlert` to hold one pending low-priority alert, speak when current utterance finishes, and drop if >8s old or bump is behind user
- [x] A6 Unit tests for A (address detection EN/FA/ZIP, P5 shared normalization table, city fallback, distance ordering) + clean build

## F1. Offline House-Number Address Search (Pipeline + App)
- [x] F1.1 Pipeline: Overture addresses query (DESCRIBE probe, bbox + country='US', city fallback, non-fatal warning on failure)
- [x] F1.2 Pipeline: New `addresses` table + `addresses_fts` FTS5 index, move unnamed OSM addr nodes/ways to `addresses`, deduplicate within 30 m (prefer Overture), street suffix/directional normalization helper, post-build verification (`24th Ave` Eugene check + warning <100k), `addressCount` in manifest
- [x] F1.3 App: `AddressNormalizer.kt` & `OfflineAddressSearchEngine.kt` (detect address queries EN/FA digits/ZIP, exact match first, nearest + linear interpolation marked approximate, merge with place results, silent skip on old packs without `addresses` table)
- [x] F1.4 App: Overture addresses attribution (OpenAddresses + US National Address Database + Overture attribution link) in About/Licenses screen + bilingual strings

## F2. Speed Bump and Traffic Calming Alerts (Pipeline + App)
- [x] F2.1 Pipeline: Extract OSM `traffic_calming` nodes & ways (first node) for `bump`, `hump`, `table`, `cushion`, `dip`, `yes` as `speed_bump` in `road_hazards` (skip rumble_strip, chicane, choker, island, painted_table), print count
- [x] F2.2 App: `RoadAlertKind.SPEED_BUMP`, Settings toggle "Speed bump alerts" (ON by default), `SpeedBumpAlertEvaluator.kt` (ahead check, 120 m <30 mph / 200 m >=30 mph, 80 m cluster -> singular vs plural EN/FA voice, no speech <5 mph, never interrupt lane/turn voice — queue after)
- [x] F2.3 App: Vector icon `ic_speed_bump_map.xml`, map layer at zoom 15+, top alert banner support, and synthetic speed bumps in I-5 / city simulation

## F3. Monthly Map Updates (Pipeline + App)
- [x] F3.1 Pipeline: `--resolve-matrix published` (stdlib urllib fetch `manifest.json`, resolve unique base states via `parentStateCode`, fallback to `or`, print resolved) + verify manifest merge preserves unbuilt states
- [x] F3.2 App: Add `androidx.work:work-runtime-ktx`, `MapUpdateCheckWorker.kt` (periodic 7-day check on connected network, version compare, low-importance `"Map updates"` channel notification opening Maps screen, 24h startup check + badge, optional Wi-Fi+charging auto-download toggle OFF by default)
- [x] F3.3 App: Smart per-component SHA-256 diff update (`MapUpdateSmartPlanner.kt`) downloading only changed components (pmtiles/valhalla/places) with real download size preview, postpone file swap while navigating, and record last check time/result in Diagnostics

## F4. Parked Car Location
- [x] F4.1 App: `ParkedCarEntity` / `ParkedCarDao` (Room migration, single latest row + optional note + Clear), `ParkedCarManager.kt` (auto-save on nav arrival <3 mph & acc <50 m, free-drive >15 mph 2 min then <3 mph 2 min, Home 150 m skip toggle ON by default, manual "I parked here" action)
- [x] F4.2 App: Optional Car Bluetooth disconnect detector (`CarBluetoothReceiver.kt` + Settings "Car Bluetooth device" picker requesting `BLUETOOTH_CONNECT` only when opened, hidden if denied, saves last good fix within 2 min on ACL disconnect)
- [x] F4.3 App: Connect existing map blue "P" marker, DriveScreen parked car card when >50 m away (EN/FA relative time & distance, note edit, Clear, Share `geo:` link), and Diagnostics entry (time & accuracy only)
- [x] F4.4 App: Offline "Walk to car" pedestrian Valhalla routing (`costing = pedestrian`), walking mode HUD (zoom 17, no speed limit/lanes/cameras/hazards, short turn voice prompts, arrival <=15 m with vibration & "You have reached your car" / "به ماشین رسیدید")

## Final Checks & Release v21
- [x] FINAL.1 Dry-check `python3 data-pipeline/build_state_pack.py --resolve-matrix published` and `--resolve-matrix or,wa` — confirmed valid JSON (`["or"]`, `["or", "wa"]`)
- [ ] FINAL.2 All unit tests pass (P5 normalization, address detection/city/distance, bump clustering, version comparison, parking Home radius)
- [ ] FINAL.3 Bump `versionCode = 21` / `versionName = "21.0"`, verify all new/edited Kotlin files <400 lines, compile APK cleanly
