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

## GROUP 6: OPEN ADDRESSES FROM OTHER APPS — IN PROGRESS
| # | Status | Note |
|---|---|---|
| 25 | FIXED | Added `VIEW` intent filters on `MainActivity` for `geo:`, `https` hosts `maps.google.com`, `maps.app.goo.gl`, `maps.apple.com`, path-prefixed `www.google.com/maps` and `google.com/maps`, plus `ACTION_SEND` `text/plain` for the Android Share menu. |

## GROUP 7: PERSIAN VOICE ENGINE — PENDING
## GROUP 8: ONLINE FEATURES WITHOUT KEYS — PENDING
## GROUP 9: SIMULATION — PENDING
## GROUP 10: DIAGNOSTICS AND REPORTS — PENDING
## GROUP 11: GRAPHICS — PENDING
## GROUP 12: SPLIT LARGE FILES — PENDING
