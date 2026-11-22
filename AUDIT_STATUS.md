# RahYab Audit & Fix Status

## GROUP 1: STABILITY — COMPLETE
| # | Status | Note |
|---|---|---|
| 1 | DONE | Cold start opens normally with installed state packs; heavy subsystems initialized lazily in `RahYabApplication`. |
| 2 | DONE | `CrashLogManager.install(this)` runs first in `attachBaseContext()` and `Application.onCreate()`; `SafeCrashRecoveryScreen` renders before creating ViewModel, MapLibre, TTS, Compass, or PackManager. |
| 3 | DONE | `AppStrings` is an interface implemented by zero-arg `EnglishStrings`/`PersianStrings`; `StartupAndArchitectureSafetyTest` enforces <=200 constructor parameters across app classes. |
| 4 | DONE | `ensurePlacesLatLonIndex` runs only in background after pack install/import/update, never during startup `auditInstalledPacks()`. |
| 5 | DONE | `NavigationForegroundService` calls `startForegroundService` once at nav start, calls `ServiceCompat.startForeground` first in `onStartCommand`, throttles `NotificationManagerCompat.notify` to >=2s, stops via `stopService()`, uses `stopSelf(startId)`, returns `START_NOT_STICKY`, and never restarts on route change. |

## GROUP 2: MAP FOLLOWS MY CAR — COMPLETE
| # | Status | Note |
|---|---|---|
| 6 | FIXED | Added `CameraFollowController` and `handleFreeDriveGpsFix()` in `RahYabViewModel`; follows GPS in free-drive (heading-up with GPS/compass bearing and lower-third padding via MapLibre `CameraPosition.Builder().padding`, north-up with bearing 0 and centered). |
| 7 | FIXED | Implemented smooth speed-based zoom (`targetZoomForSpeedMps` with EMA smoothing and clamped step) from ~17.2 in city to ~14.8 on highways. |
| 8 | FIXED | `MapLibreMapContainer` listens to `REASON_API_GESTURE` (pan, pinch-zoom, rotate, tilt) + `onMoveBegin`; `pauseCameraFollow()` now works in any mode; Recenter button highlights whenever `!isCameraFollowing` and resumes follow on tap. |
| 9 | FIXED | Auto-recenter triggers after 10 seconds without touching the map when driving faster than 10 mph (~4.47 m/s). |
| 10 | FIXED | `selectPlace()`, `dropPinOnMap()`, and `calculateRoutesToSelectedPlace()` pause following; `clearSelectedPlace()` turns following back on and recenters. |
| 11 | FIXED | Smooth 1,000 ms animation per real GPS fix, skipping animations when moved < 2 meters without bearing/mode change; works during active navigation and simulation at 1x–32x. |

## GROUP 3: GUIDANCE — COMPLETE
| # | Status | Note |
|---|---|---|
| 12 | DONE | `ValhallaResponseParser` parses integer bitmasks `directions`, `valid`, `active` (bits 2..1024) and approach-segment lanes (`parseApproachSegmentLanes`). |
| 13 | DONE | `LaneGuidanceBannerRow` renders inside top banner in forced `LayoutDirection.Ltr` with 3 highlight tiers (`ACTIVE`, `VALID`, `INACTIVE`), visible from 1 mile (`LANE_STRIP_WINDOW_METERS = 1780.0`). |
| 14 | DONE | `NavigationSessionController` deduplicates announcement stages per maneuver, enforces `MIN_VOICE_SPEED_MPS` (2 mph) except departure instruction (`speakStartInstruction`), and `VoiceGuidanceManager` uses `QUEUE_FLUSH` so outdated instructions are dropped, never queued. |
| 15 | DONE | Early lane announcements fire at 1 mile and 0.5 mile on highways and multi-lane city roads with lane advice when lane data exists and "prepare to turn" wording when absent. |
| 16 | DONE | `ValhallaRoutingEngine` fills per-shape speed limits from offline `trace_attributes`; HUD displays speed limit sign, live speed, and overspeed warning with 0/5/10 mph threshold. |
| 17 | DONE | `LocationTracker.resolveRoutingHeadingDegrees` sends heading only with real GPS bearing, speed > 3 mph (`MIN_BEARING_SPEED_MPS`), < 10s old; rerouting gated at `MIN_REROUTE_SPEED_MPS` (3 mph). |
| 18 | DONE | Up to 4 routes including `isShortestRoute` and Fastest/Shortest preference in Settings & route preview. |

## GROUP 4: LOCATION AND COMPASS — COMPLETE
| # | Status | Note |
|---|---|---|
| 19 | DONE | `LocationTracker.isUsableLastKnownLocation` rejects fixes older than 2 min or worse than 100 m; `DriveScreen` shows `gpsWaiting` ("Finding your location..."). |
| 20 | DONE | `LocationTracker` uses `PRIORITY_HIGH_ACCURACY` on map screen, rejects worse fixes within `RECENT_FIX_WINDOW_MS` (5 s), and uses fused provider during navigation with raw GPS as a 3-second (`FUSED_FALLBACK_TIMEOUT_MS`) fallback. |
| 21 | DONE | `NavigationSessionController` snaps to route only within 30 m (`bestDist <= 30.0`); `MapLibreMapContainer` renders light blue accuracy polygon; `DriveScreen` shows `ApproximateLocationBanner` with Settings button. |
| 22 | DONE | `CompassTracker` uses `TYPE_ROTATION_VECTOR` with `GeomagneticField` true north and screen rotation; used below 3 mph for display only (never sent to routing); `CompassCalibrationBanner` shown on low accuracy. |

## GROUP 5: SPEED CAMERAS AND RED LIGHT CAMERAS — COMPLETE
| # | Status | Note |
|---|---|---|
| 23 | FIXED | Updated `data-pipeline/build_state_pack.py` to add `direction` & `osm_id` columns to `road_hazards`, extract `highway=speed_camera` nodes and `type=enforcement` (`maxspeed` -> `speed_camera`, `traffic_signals` -> `red_light_camera`) relations with `device` position, `from` travel bearing, `direction` tag, `maxspeed` (mph), deduplication, and printed counts (`.github/workflows/build-map-packs.yml` untouched). |
| 24 | FIXED | Added zoom >= 13 speed/red-light camera map icons (`SOURCE_CAMERAS`/`LAYER_CAMERAS_SYMBOL`), direction- & lateral-offset-filtered ahead warnings (~315 m city / ~420 m highway, once per camera) in both active navigation/simulation (1x–32x) and free driving, alert chime + EN/FA voice with optional speed limit, two Settings toggles (ON by default) + backup sync, and backward-compatible `PRAGMA table_info` schema probing for older map packs. |

## GROUP 6: OPEN ADDRESSES FROM OTHER APPS — COMPLETE
| # | Status | Note |
|---|---|---|
| 25 | FIXED | Added `VIEW` intent filters on `MainActivity` for `geo:`, `https` hosts `maps.google.com`, `maps.app.goo.gl`, `maps.apple.com`, path-prefixed `www.google.com/maps` and `google.com/maps`, plus `ACTION_SEND` `text/plain` for the Android Share menu. |
| 26 | FIXED | Added `ExternalMapIntentParser` and `RahYabViewModel.handleExternalMapIntent` supporting `geo:lat,lon`, `q=`, `ll=`, `@lat,lon`, `!3d/!4d`, offline search with best-result Directions or multi-match picker sheet, online `maps.app.goo.gl` HTTP redirect expansion (with offline prompt to share full address), out-of-coverage missing-state Download prompt, and both cold-start (`onCreate`) and warm-start (`onNewIntent`) handling. |
| 42 | FIXED | Created offline vector drawable `ic_traffic_light_map.xml` (static red/yellow/green lamps in dark housing), added `queryTrafficSignalsInBounds` with ~15 m intersection clustering, rendered from zoom 15 (`LAYER_TRAFFIC_LIGHTS_SYMBOL` with `icon-allow-overlap: false`), added visual-only ahead pill (~300 ft / 95 m on road, no voice) in navigation & free driving, and added Settings switch "Show traffic lights" (ON by default). |



## GROUP 7: PERSIAN VOICE ENGINE — COMPLETE
| # | Status | Note |
|---|---|---|
| 27 | FIXED | Dual-engine `VoiceGuidanceManager` discovers installed TTS engines via `TextToSpeech.getEngines()` (prefers any `"sherpa"` package with `fa`/`fa-IR` for Persian, `com.google.android.tts` for English, no bundled `sherpa-onnx`), splits mixed Persian/English instructions so street names & highway numbers speak on the English engine in exact order via `UtteranceProgressListener.onDone`, and reports both engines in `DiagnosticsScreen`. |

## GROUP 8: ONLINE FEATURES WITHOUT KEYS — COMPLETE
| # | Status | Note |
|---|---|---|
| 28 | FIXED | Reused `NetworkStatusMonitor` & `OnlineHttpConfig` (`RahYab/<version> (personal navigation app)` User-Agent, ~5s timeout on `Dispatchers.IO`); added Settings "Online features" card (master switch, per-source switches, Wi-Fi-only downloads ON by default, auto-update on Wi-Fi OFF by default, location disclosure) and map cloud indicator button opening live per-source status dialog. |
| 29 | FIXED | Added `WeatherAlertsClient` polling `api.weather.gov/alerts/active?point={lat},{lon}` every 15 min & on route calculation (for user position + route samples every ~30 miles, 5s timeout, no API key), filtering severe driving alerts, caching in memory, speaking once per alert ID, and showing a dismissible `SevereWeatherAlertBanner` with title, area, and end time. |
| 30 | FIXED | Added `PhotonSearchClient` (`photon.komoot.io/api/`, 5s timeout, strict >=1s rate limit, no API key) wired as optional online fallback after 1s typing pause or on Search submit when offline search has no strong match; online results render below offline results with a cloud icon badge and support offline routing & Favorites. |
| 31 | FIXED | Added daily background `manifest.json` check (5s timeout, once per 24h when online & enabled), Maps tab `BadgedBox` update count badge, Android update notification, Wi-Fi-only guard for large downloads (ON by default), and optional Wi-Fi auto-update (OFF by default). |

## GROUP 9: SIMULATION — COMPLETE
| # | Status | Note |
|---|---|---|
| 32 | DONE | Verified `SimulateToggleButton` (red when active), `toggleSimulationFromMap()`, `stopSimulationAndReturnToRealGps()`, Settings sync, no-route prompt, real-GPS ignore guard, and immediate reroute from real GPS on stop. |
| 33 | DONE | Verified `SimulationSpeedButton` (visible only during simulation; tap cycles 1x/2x/4x/8x/16x/32x, long-press & stop reset to 1x) and `advanceSimulationStep` 1.0s sub-stepping preserving realistic 1x speed, voice/lane/speed/camera alerts, and smooth map frame rate. |

## GROUP 10: DIAGNOSTICS AND REPORTS — COMPLETE
| # | Status | Note |
|---|---|---|
| 34 | DONE | Verified `DiagnosticsScreen` with all 6 sections (app/device info, installed packs with build version & places source & sizes, GPS accuracy/provider/fix age & compass accuracy, last route maneuvers/lanes/speed limits, Persian & English TTS engines + voice test buttons, last 20 error lines) and working `Copy all` button. |
| 35 | DONE | Verified all 8 `ReportType` categories, TTL expiry auto-filtering/purging, ahead voice & banner warnings, JSON export/import in `BackupAndTransferManager`, and active `ROAD_CLOSURE` avoidance via Valhalla `exclude_locations`. |

## GROUP 11: GRAPHICS — COMPLETE
| # | Status | Note |
|---|---|---|
| 36 | FIXED | Added 3D extruded buildings (`fill-extrusion`) from Protomaps `buildings` (and dev basemap) starting at zoom 15 with height/min_height, smooth fade-in opacity, vertical gradient, z-ordered strictly below labels/routes/arrow, dynamically enabled only when tilted (`tilt > 5°`) & Settings switch "Show 3D buildings" is ON (default ON), with Choreographer frame-pacing fallback that auto-disables 3D buildings if FPS drops persistently. |
| 37 | FIXED | Replaced flat position arrow with dual-facet 3D-shaded arrow & modern top-down car bitmaps with soft drop shadow, automatic slate-gray poor-GPS state (`accuracy > 35 m` or GPS lost), and Settings "Position marker" (`3D Arrow` / `Car`) selector. |
| 38 | FIXED | Added darker active route casing with rounded caps/joins, `SOURCE_DRIVEN_ROUTE` / `LAYER_DRIVEN_ROUTE` splitting the already-driven route segment in muted gray behind the car, and `LAYER_ROUTE_CHEVRONS` rendering crisp white direction chevrons along the remaining route from zoom 15. |
| 39 | FIXED | Created `MapBitmapIcons` with distinct US Interstate (red crest/blue), US Route (white shield), and State Route (white oval) highway shields wired via `network`/`ref` expressions, plus 10 offline vector POI icons (gas, parking, restaurant, cafe, grocery, pharmacy, hospital, school, bank, EV) rendered from zoom 15 with non-overlapping placement. |

## GROUP 12: SPLIT LARGE FILES — COMPLETE
| # | Status | Note |
|---|---|---|
| 40 | FIXED | Split `RahYabViewModel.kt` (2,514 lines) into 9 feature files all <= 400 lines (`RahYabViewModel.kt` 333, `RahYabViewModelCameraAndHazards.kt` 389, `RahYabViewModelSearchAndExternal.kt` 396, `RahYabViewModelRouting.kt` 308, `RahYabViewModelNavigationStep.kt` 242, `RahYabViewModelSimulation.kt` 241, `RahYabViewModelPacksAndOnline.kt` 329, `RahYabViewModelReportsAndBackup.kt` 172, `RahYabViewModelSettings.kt` 126) with zero public API or behavior changes. |
| 41 | FIXED | Split `DriveScreen.kt` (2,277 lines) into 7 feature files all <= 400 lines (`DriveScreen.kt` 316, `DriveTopOverlay.kt` 387, `DriveStatusBanners.kt` 338, `DriveMapControls.kt` 397, `DriveSearchOverlay.kt` 327, `DrivePlaceAndRouteCard.kt` 345, `DriveBottomBarAndDialogs.kt` 322) with zero visual or behavior changes; bumped `versionCode` once from `17` to `18` (`versionName` `"18.0"`). |
