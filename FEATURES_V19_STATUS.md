# RahYab v19 Feature Batch Status

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
- [ ] F3.1 Pipeline: `--resolve-matrix published` (stdlib urllib fetch `manifest.json`, resolve unique base states via `parentStateCode`, fallback to `or`, print resolved) + verify manifest merge preserves unbuilt states
- [ ] F3.2 App: Add `androidx.work:work-runtime-ktx`, `MapUpdateCheckWorker.kt` (periodic 7-day check on connected network, version compare, low-importance `"Map updates"` channel notification opening Maps screen, 24h startup check + badge, optional Wi-Fi+charging auto-download toggle OFF by default)
- [ ] F3.3 App: Smart per-component SHA-256 diff update (`MapUpdateSmartPlanner.kt`) downloading only changed components (pmtiles/valhalla/places) with real download size preview, postpone file swap while navigating, and record last check time/result in Diagnostics

## F4. Parked Car Location
- [ ] F4.1 App: `ParkedCarEntity` / `ParkedCarDao` (Room DB migration, single latest row + optional note + Clear), `ParkedCarManager.kt` (auto-save on nav arrival <3 mph & acc <50 m, free-drive >15 mph 2 min then <3 mph 2 min, Home 150 m skip toggle ON by default, manual "I parked here" action)
- [ ] F4.2 App: Optional Car Bluetooth disconnect detector (`CarBluetoothReceiver.kt` + Settings "Car Bluetooth device" picker requesting `BLUETOOTH_CONNECT` only when opened, hidden if denied, saves last good fix within 2 min on ACL disconnect)
- [ ] F4.3 App: Map blue "P" marker, DriveScreen parked car card when >50 m away (EN/FA relative time & distance, note edit, Clear, Share `geo:` link), and Diagnostics entry (time & accuracy only)
- [ ] F4.4 App: Offline "Walk to car" pedestrian Valhalla routing (`costing = pedestrian`), walking mode HUD (zoom 17, no speed limit/lanes/cameras/hazards, short turn voice prompts, arrival <=15 m with vibration & "You have reached your car" / "به ماشین رسیدید")

## Final Checks & Release v19
- [ ] FINAL.1 Dry-check `python3 data-pipeline/build_state_pack.py --resolve-matrix published` and `--resolve-matrix or,wa`
- [ ] FINAL.2 Unit tests for F1..F4 (address query detection EN/FA/ZIP, street suffix normalization, bump clustering, version comparison, parking Home radius)
- [ ] FINAL.3 Bump `versionCode = 19` / `versionName = "19.0"`, compile APK cleanly, verify all unit tests pass
