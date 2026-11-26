# RahYab Focus & Android Auto Fix Status

## A. Android Auto Map View & Style Fixes
- [x] A1 Car camera profile: `carZoomOffset` (`Close` -0.0, `Normal` -0.8 default, `Wide` -1.5) in `CarMapSurfaceRenderer`, car tilt 40° nav / 30° free drive / 0° 2D, lower-third marker, Turn Focus override
- [x] A2 Car map style tuning (`StyleProfile.CAR`): minor road widths x1.3, minor street labels 1 zoom earlier, label text size x1.15, brighter night minor-road contrast (phone style untouched)
- [x] A3 Car surface density: `MapLibreMapOptions.pixelRatio(max(1.0, surfaceContainer.dpi / 160.0))` + `textureMode(true)`
- [x] A4 Diagnostics "Car screen" section: surface width/height/dpi/pixelRatio, car zoom & tilt, visibleArea & stableArea, car API level, top car screen class name, last template type & whether RoutingInfo had a current step, last car template error

## B. Android Auto Turn Card Fixes
- [x] B1 Session-level navigation switch in `RahYabCarAppService` session (`navigationHub.navigationState` collector -> pop to root & push `CarNavigationScreen` in DRIVE, pop back on end; remove screen-level push from `CarHomeScreen`)
- [x] B2 `CarNavigationScreen` fallback Step when road name / current step is missing (using `CarManeuverMapper.toCarManeuver` + maneuver instruction cue + road name when available + distance)
- [x] B3 Refresh `CarNavigationScreen` on every nav progress update (at most 1/sec, immediately on maneuver change) and ensure `navigationStarted()` & `updateTrip()` run even when started from phone
- [x] B4 Wrap template building in `runCatching`: log error to Diagnostics "Car screen" section and show `MessageTemplate` with current instruction instead of blank card

## F. Turn Focus Mode (3 Beeps, Zoom, Blue Lanes)
- [x] F1 `TurnFocusPlanner.kt` (pure Kotlin: focus maneuvers, highway context, city/highway trigger distance, wide-highway 3+ lanes safety rule capped at 1600 m, previous-maneuver 30 m rule, too-late no-beep rule, end distances 50 m city / 150 m highway exit + off-route/reroute end, trigger once per maneuver)
- [x] F2 Beeps: exactly 3 short beeps (150 ms tone, 150 ms gap, ~750 ms total) with `TONE_PROP_BEEP`, nav audio attributes & ducking, wait up to 4 s after voice instruction, optional short phrase ("Get in the blue lane" / "به خط آبی بروید", OFF by default)
- [x] F3 One beep source only: disable old lane-change cue beeps & "Change lanes now" & last-chance beep while Turn Focus mode is ON (keep `LaneChangeCuePlanner` for wide-highway distance & keep regular voice instructions)
- [x] F4 Focus zoom: 300 ms after last beep animate over 1200 ms to zoom 17.3 highway / 18.0 city, tilt 55° (0° if 2D), heading up, car in lower quarter; suppress speed zoom-out; respect user pinch/pan until focus ends; animate back over 1500 ms
- [x] F5 Focus lanes: `LaneOverlayBuilder` custom start distance (default 500 m, focus uses trigger distance max 1600 m), blue `#1E88E5` target at 85% opacity, gray other lanes at 45%, white dashed separators, lane arrows every 150 m + larger arrow 40 m before maneuver; no-lane fallback enlarges maneuver arrow
- [x] F6 Lane color default RED -> BLUE with one-time migration flag preserving explicit user changes
- [x] F7 Settings in new `TurnFocusSettingsSection.kt` (EN & FA, mile equivalents when unit is miles, hide old last-chance/timing options while Turn Focus is ON)
- [x] F8 Android Auto uses focus zoom, tilt, and blue lane overlay while focus is active (overriding A1 car profile) and returns to car profile after
- [x] F9 Simulation: I-5 highway simulation & Eugene city simulation both trigger Turn Focus mode at exits and turns

## T. Tests
- [x] T1 Unit tests (`FocusAndAutoUnitTest.kt`): `TurnFocusPlanner`, 3 beeps & suppression of old beeps, `LaneOverlayBuilder` custom start distance, lane color migration, car zoom offsets, `CarNavigationScreen` fallback step

## Final
- [x] FINAL All unit tests pass, APK builds with zero errors, mark everything DONE
