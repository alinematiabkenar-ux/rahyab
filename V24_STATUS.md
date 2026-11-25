# RahYab v24/v25 Batch Status

## Phase W — Leftovers
- [x] W1 Verify AndroidAutoUnitTest.kt, run unit tests, mark AA9/FINAL.2/FINAL.3 DONE in AUTO_STATUS.md & FEATURES_V19_STATUS.md — PASSED

## Phase R — Refactor (all resulting files < 400 lines, no behavior change)
- [x] R1 Split `navigation/VoiceGuidanceManager.kt` (`VoiceTtsEngineSelector.kt`, `VoiceSpeechQueue.kt`, `VoicePhraseBuilder.kt`, `VoicePersianPhraseBuilder.kt`)
- [x] R2 Split `map/MapStyleBuilder.kt` (`MapStylePaletteAndExprs.kt`, `MapStyleProtomapsGeometry.kt`, `MapStyleProtomapsRoadLabels.kt`, `MapStyleProtomapsPlaceLabels.kt`, `MapStyleDevOnlineLayers.kt`, `MapStyleOverlayLayers.kt`)
- [x] R3 Split `data/packs/StatePackManager.kt` (`PackArchiveAndValidation.kt`, `PackHttpDownloader.kt`, `PackDownloadExecutor.kt`, `PackAuditAndImportHelper.kt`)
- [x] R4 Split `navigation/ValhallaResponseParser.kt` (`ValhallaRequestBuilder.kt`, `ValhallaTraceAttributesParser.kt`, `ValhallaApproachLaneResolver.kt`, `ValhallaTurnLaneDecoder.kt`, `ValhallaPolylineAndGeo.kt`)
- [x] R5 Split `navigation/NavigationSessionController.kt` (`NavigationGeometryHelper.kt`, `NavigationAlertEvaluator.kt`, `NavigationVoiceTriggerPolicy.kt`, `NavigationSimulationEngine.kt`)
- [DEFERRED] R6 Split `ui/screens/SettingsAndAboutScreen.kt`
- [DEFERRED] R7 Split `ui/components/MapLibreMapContainer.kt`
- [DEFERRED] R8 Split `ui/components/NavigationHudComponents.kt`
- [DEFERRED] R9 Split `ui/screens/DownloadMapsScreen.kt`
- [DEFERRED] R10 Split `data/search/OfflinePlaceSearchEngine.kt`
- [DEFERRED] R11 Split `ui/screens/DiagnosticsScreen.kt`
- [DEFERRED] R12 Split `MainActivity.kt`
- [DEFERRED] R13 Split `ui/components/LaneGuidanceBannerRow.kt`
- [DEFERRED] R14 Split `ui/RahYabViewModel.kt`
- [x] R15 Add 450-line file-size unit test to `StartupAndArchitectureSafetyTest` (exempting AppStrings/EnglishStrings/PersianStrings and DEFERRED R6–R14 files) + pure JVM `ArchitectureLineCountTest`

## Phase M — Walking and Bicycle Routing with Arrival Times
- [x] M1 `TravelMode` enum (`DRIVE`, `WALK`, `BIKE`) in new file, add to `RouteOptions`, keep `isPedestrian` derived (`mode == WALK`), persist last chosen mode
- [x] M2 Valhalla requests per mode (`DRIVE`, `WALK` pedestrian + walking_speed, `BIKE` bicycle Hybrid + cycling_speed + use_roads 0.25 + use_hills + alternates 1; skip speed-limit trace_attributes for WALK/BIKE)
- [x] M3 Route preview 3 mode chips (Drive/Walk/Bike EN & FA), background offline calculation for all 3 modes with duration + ETA, "Too far" (>50 km walk / >150 km bike), select chip to display & start mode
- [x] M4 Navigation behavior per mode (WALK reuse, BIKE zoom 16 + speed + no limit/lanes/junction/cameras/bumps + keep rail & steep grade + 100 m/30 m prompts + 20 m arrival; 30 m off-route + same-mode reroute; dotted blue WALK / dashed green BIKE route line; foreground notification shows mode)
- [x] M5 Arrival time for every mode + 3-min moving-average speed ETA adjustment (0.6x–1.5x) for WALK & BIKE only, never adjusting while stopped < 3 min
- [x] M6 Settings: default travel mode, walking speed (slow/normal/fast), cycling speed (relaxed/normal/fast), "Avoid hills when cycling" (EN & FA)
- [x] M7 Android Auto stays DRIVE only; shows phone-only notice when phone navigates in WALK or BIKE; simulation supports WALK (5 km/h) and BIKE (18 km/h)
- [x] M8 Unit tests for M (request JSON per mode, Too far limits, ETA 0.6x–1.5x adjustment, WALK/BIKE alerts, Walk to car pedestrian request)

## Phase J — Highway Junction View (Sygic-style)
- [x] J1 `JunctionScene.kt` (pure Kotlin scene builder)
- [x] J2 `JunctionViewRenderer.kt` (Android Canvas Bitmap renderer, day/night, US shields & green sign)
- [x] J3 Phone DriveScreen top junction view card (800 m before to 50 m after, replaces lane banner, tap to dismiss for maneuver)
- [x] J4 Android Auto `RoutingInfo.Builder.setJunctionImage(CarIcon)` when supported, fallback to lanes image
- [x] J5 Setting "Junction view" (ON by default, EN/FA, respected on phone and car)
- [x] J6 I-5 simulation triggers junction view at Eugene/Springfield highway exits
- [x] J7 Unit tests for `JunctionScene` (exit right 3+1, exit left, fork, missing signpost, missing lanes, non-highway hidden)

## Phase X — Extra Offline Data (`<state>_extras.db`)
- [x] X1 Pipeline: `valhalla_build_elevation` + one-shot `valhalla_service` test route check with fallback
- [x] X2 Pipeline: FRA rail crossings (`m2f8-22s6`) into `fra_crossings` table
- [x] X3 Pipeline: HPMS speed limits (`42um-tgh5`) into `hpms_speed` table with bbox index
- [x] X4 Pipeline & App: `extras.db` `meta` table, `"extras"` manifest key + merge + download/smart-update/Diagnostics + About attribution + Python tests
- [x] X5 App: Steep grade warnings (`edge.max_upward_grade`/`max_downward_grade`/`weighted_grade`, `RoadAlertKind.STEEP_GRADE`, toggle ON, phone + car)
- [x] X1b Pipeline: Fix `valhalla_build_elevation` CLI flags (`--from-bbox=`, `--outdir`, `-p`, `-v`) + exact command unit test
- [x] W2 Workflow: Automate `.github/workflows/build-map-packs.yml` (`_extras.db` upload, monthly schedule, push trigger, concurrency, `published` fallback, `YYYY.MM.DD.HHMM`)
- [x] X6 App: Railroad crossing FRA detail (within 40 m match + FRA-only crossings, EN/FA phone banner + voice + Android Auto)
- [x] X7 App: HPMS speed limit fallback (bbox + 20 m + 30 deg heading, Diagnostics source `osm`/`hpms`, never override OSM)
- [x] X8 App: Unit tests for X5, X6, X7 including missing `extras.db` and old packs without grades

## Final
- [x] FINAL done, version set by build step

## Noticed bugs
- (None recorded yet)
