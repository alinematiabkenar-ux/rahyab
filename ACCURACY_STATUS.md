# RahYab Accuracy, Simulator Speed & Open US Data Status

## S. Simulator Speed Buttons
- [x] S1 Round `−` and `+` buttons with current speed (`×4`) on the drive screen left side above bottom bar during simulation (steps 1, 2, 4, 8, 16, 32, disabled at ends, hidden when not simulating, synced with side menu)

## L. Lane Accuracy
- [x] L1 Add `laneSource` (`DIRECT`, `EDGE_APPROACH`, `CARRIED_ALIGNED`, `NONE`) to `RouteManeuver`, set in `ValhallaResponseParser`, display in Diagnostics for current maneuver
- [x] L2 Create `LaneGuidanceValidator.kt` (pure Kotlin, unit-testable): arrow rule, side rule, target choice, source rule, count rule (edge lane count & HPMS through-lane count), personal wrong-lane report rule -> `HIGH` / `MEDIUM` / `LOW` + reasons
- [x] L3 Add `edge.lane_count` (and `edge.way_id` for L5) to offline `trace_attributes`, store per route edge, draw lane overlay only on final approach matching maneuver lane count (within 1 for turn bay); fallback max 300 m city / 600 m highway when missing
- [x] L4 Apply lane confidence everywhere (lane banner, map overlay, junction view, Turn Focus, Android Auto lanes image): `HIGH` bright blue, `MEDIUM` lighter blue 50% / subtle banner, `LOW` no colored lanes + side hint ("Use the right/left/middle lanes" EN & FA), Turn Focus still beeps & zooms
- [x] L5 "Wrong lane" flag button when lane advice is visible -> save to new Room table (additive migration) with time, location, map version, maneuver details, raw lane JSON, computed targets, edge lane count, approach OSM way id; suppress intersection within 30 m & 30° bearing until map version changes; confirmation toast EN & FA
- [x] L6 Diagnostics "Lane reports" section: list with date, road names, reason, "Copy all" including raw lane JSON, OSM way id link-style text, and "Clear" button

## D. More Open US Data (Pipeline & App)
- [ ] D1 Pipeline: TIGER/Line `ADDRFEAT` address ranges (`tiger_ranges` table + indexes, try 2026/2025/2024, state + neighboring bbox counties, DuckDB spatial `/vsizip/`, `address_utils.py` normalizer, schema_version 2)
- [ ] D2 Pipeline: USGS GNIS Domestic Names (`gnis_names` + FTS5 `gnis_fts`, filtered feature classes)
- [ ] D3 Pipeline: HPMS through-lanes (`hpms_lanes` table, keep `hpms_speed` unchanged)
- [ ] D4 App: TIGER address range fallback with linear interpolation and "approximate" / "تقریبی" badge (exact `addresses` always win)
- [ ] D5 App: GNIS place search results (natural features & landmarks) after places & addresses, prefix search, sorted by distance, icon by feature class

## T. Tests
- [ ] T1 Unit tests (Kotlin + Python pipeline tests with mocked downloads + `python3 -m py_compile data-pipeline/*.py`)

## Final
- [ ] FINAL All unit tests pass, APK builds with zero errors, mark everything DONE
