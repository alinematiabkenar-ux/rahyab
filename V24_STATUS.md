# RahYab v24 Batch Status

## Phase W — Leftovers
- [x] W1 Verify AndroidAutoUnitTest.kt, run unit tests, mark AA9/FINAL.2/FINAL.3 DONE in AUTO_STATUS.md & FEATURES_V19_STATUS.md — PASSED

## Phase R — Refactor (all resulting files < 400 lines, no behavior change)
- [ ] R1 Split `navigation/VoiceGuidanceManager.kt`
- [ ] R2 Split `map/MapStyleBuilder.kt`
- [ ] R3 Split `data/packs/StatePackManager.kt`
- [ ] R4 Split `navigation/ValhallaResponseParser.kt`
- [ ] R5 Split `navigation/NavigationSessionController.kt`
- [ ] R6 Split `ui/screens/SettingsAndAboutScreen.kt`
- [ ] R7 Split `ui/components/MapLibreMapContainer.kt`
- [ ] R8 Split `ui/components/NavigationHudComponents.kt`
- [ ] R9 Split `ui/screens/DownloadMapsScreen.kt`
- [ ] R10 Split `data/search/OfflinePlaceSearchEngine.kt`
- [ ] R11 Split `ui/screens/DiagnosticsScreen.kt`
- [ ] R12 Split `MainActivity.kt`
- [ ] R13 Split `ui/components/LaneGuidanceBannerRow.kt`
- [ ] R14 Split `ui/RahYabViewModel.kt`
- [ ] R15 Add 450-line file-size unit test to `StartupAndArchitectureSafetyTest` (exempting AppStrings/EnglishStrings/PersianStrings)

## Phase J — Highway Junction View (Sygic-style)
- [ ] J1 `JunctionScene.kt` (pure Kotlin scene builder)
- [ ] J2 `JunctionViewRenderer.kt` (Android Canvas Bitmap renderer, day/night, US shields & green sign)
- [ ] J3 Phone DriveScreen top junction view card (800 m before to 50 m after, replaces lane banner, tap to dismiss for maneuver)
- [ ] J4 Android Auto `RoutingInfo.Builder.setJunctionImage(CarIcon)` when supported, fallback to lanes image
- [ ] J5 Setting "Junction view" (ON by default, EN/FA, respected on phone and car)
- [ ] J6 I-5 simulation triggers junction view at Eugene/Springfield highway exits
- [ ] J7 Unit tests for `JunctionScene` (exit right 3+1, exit left, fork, missing signpost, missing lanes, non-highway hidden)

## Phase X — Extra Offline Data (`<state>_extras.db`)
- [ ] X1 Pipeline: `valhalla_build_elevation` + one-shot `valhalla_service` test route check with fallback
- [ ] X2 Pipeline: FRA rail crossings (`m2f8-22s6`) into `fra_crossings` table
- [ ] X3 Pipeline: HPMS speed limits (`42um-tgh5`) into `hpms_speed` table with bbox index
- [ ] X4 Pipeline & App: `extras.db` `meta` table, `"extras"` manifest key + merge + download/smart-update/Diagnostics + About attribution + Python tests
- [ ] X5 App: Steep grade warnings (`edge.max_upward_grade`/`max_downward_grade`/`weighted_grade`, `RoadAlertKind.STEEP_GRADE`, toggle ON, phone + car)
- [ ] X6 App: Railroad crossing FRA detail (within 40 m match + FRA-only crossings, EN/FA phone banner + voice + Android Auto)
- [ ] X7 App: HPMS speed limit fallback (bbox + 20 m + 30 deg heading, Diagnostics source `osm`/`hpms`, never override OSM)
- [ ] X8 App: Unit tests for X5, X6, X7 including missing `extras.db` and old packs without grades

## Final
- [ ] FINAL All unit tests pass, APK builds cleanly, bump versionCode to 24 / versionName "24.0", mark everything DONE

## Noticed bugs
- (None recorded yet)
