# RahYab Android Auto & Remaining Items Status

## S0. Finish Remaining v21 Items (`FEATURES_V19_STATUS.md`)
- [x] F3.2 Map update check (`MapUpdateVersionHelper.kt`, `MapUpdateCheckWorker.kt`, WorkManager 7d + 24h startup + low-importance channel + unmetered Wi-Fi/charging toggle) — verified & compiled
- [x] F3.3 Smart per-component SHA-256 diff update (`MapUpdateSmartPlanner.kt`, Room MIGRATION_3_4, real size preview, deferred swap after navigation, Diagnostics) — verified & compiled
- [x] F4.1 Parked car storage & auto-save rules (`ParkedCarEntity`/`ParkedCarDao`, Room MIGRATION_4_5, `ParkedCarManager.kt`, Home 150 m skip, manual "I parked here") — verified & compiled
- [x] F4.2 Optional Car Bluetooth disconnect detector (`CarBluetoothReceiver.kt`, `SettingsParkingSection.kt`, runtime `BLUETOOTH_CONNECT` on picker open, hidden if denied) — verified & compiled
- [x] F4.3 Parked car UI (Map "P" marker wired, `ParkedCarCard.kt` when >50 m away with EN/FA relative time & distance, note edit, Share `geo:`, Clear, Diagnostics time & accuracy only) — verified & compiled
- [x] F4.4 Offline "Walk to car" pedestrian Valhalla routing (`costing = pedestrian`), walking HUD (zoom 17, no speed limit/lanes/cameras/hazards, short turn voice, arrival <=15 m with vibration & EN/FA speech) — verified & compiled
- [x] S0-Tests Unit tests for F3/F4 (version compare, SHA-256 diff, Home 150 m radius, free-drive stop rule, walking mode) — PASSED

## Android Auto (`com.example.car`)
- [x] AA1 Dependencies (`androidx.car.app:app:1.7.0`, `androidx.car.app:app-projected:1.7.0`), manifest `RahYabCarAppService`, `res/xml/automotive_app_desc.xml`, `minCarApiLevel=1`, permissions, `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` — verified & compiled
- [x] AA2 Application-scoped shared navigation state (`RahYabNavigationHub`) exposing StateFlows & commands shared between `RahYabViewModel` and Car session — verified & compiled
- [x] AA3 Car map surface rendering (`CarMapSurfaceRenderer.kt` using `VirtualDisplay` + `Presentation` + MapLibre `MapView`, same style/layers/images, `CameraFollowController` with `visibleArea`/`stableArea`, dark mode, pan/fling/scale + 10s return to follow, clean teardown, no-pack fallback text) — verified & compiled
- [x] AA3b `CarMapSurfaceRenderer.onSurfaceAvailable`: instantiate `MapView` with `pres.context` and `MapLibreMapOptions.createFromAttributes(pres.context).textureMode(true)` for reliable VirtualDisplay rendering — verified & compiled
- [x] AA4 Car Home screen (`CarHomeScreen.kt`: NavigationTemplate map with Search/Favorites/Recent action strip + zoom/recenter/pan map action strip + free-drive alert observer; `CarPlacesListScreen.kt` ListTemplate with Home/Work on top) — verified & compiled
- [x] AA5 Car Search & Route Preview (`CarSearchScreen.kt` SearchTemplate offline search + driving-mode Favorites/Recent fallback; `CarRoutePreviewScreen.kt` RoutePreviewNavigationTemplate / MapWithContentTemplate API 7+ with up to 3 routes & Start) — verified & compiled
- [x] AA6 Car Active Navigation (`CarNavigationScreen.kt`, `CarManeuverMapper.kt`, `CarLaneBitmapRenderer.kt`: RoutingInfo, Valhalla->Car Maneuver & Lanes + lanes bitmap, TravelEstimate, Stop/Mute/Report closure, NavigationManager `navigationStarted`/`updateTrip`/`navigationEnded`/`onStopNavigation`/`onAutoDriveEnabled`, speed limit & current speed overlay inside visibleArea) — verified & compiled
- [x] AA7 Car Alerts & Arrival (`CarAlertHelper.kt`: AppManager.showAlert API 5+ / CarToast fallback for cameras/speed bumps/hazards/weather/closures, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE audio focus ducking, arrival message & auto-end) — verified & compiled
- [x] AA8 Language & compact Android Auto copy (EN/FA from app language setting, street names preserved, concise Persian strings fitting car display limits) — verified & compiled
- [x] AA9 Unit tests (maneuver mapping, lane bitmask mapping, TravelEstimate formatting, shared hub sync), clean APK build — PASSED (version is set in V24_STATUS.md FINAL)
