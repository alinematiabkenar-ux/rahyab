# RahYab Visual & Safety Batch Status

## S. Separate Traffic Light and Stop Sign Settings
- [x] S1 Settings model & UI: `showTrafficLights` (default ON), `alertTrafficLightEnabled` (default OFF), `showStopSignsOnMap` (default ON), `alertStopSignEnabled` (preserve existing value); grouped under "Traffic lights and stop signs" / "چراغ راهنما و تابلوی ایست" (EN & FA)
- [x] S2 Stop signs on map: load `stop_sign`/`stop` from `road_hazards`, draw `ic_stop_sign_map.xml` red octagon at zoom >= 16 on phone & Android Auto respecting `showStopSignsOnMap`
- [x] S3 Traffic light voice alert: DRIVE mode only, ~150 m ahead, once per light, skip if speaking or < 120 m cluster, never in WALK

## L. Lane Change Beeps (deterministic, offline, no AI)
- [x] L1 `LaneChangeCuePlanner.kt` (pure Kotlin: `lanesToCross`, highway/city `perLane` & buffer, timing multipliers, clamp ranges, `lastChanceDistance`, target side)
- [x] L2 Beeps & short voice phrase: 3 short beeps (120 ms / 120 ms) + "Change lanes now, keep right/left/middle" (EN/FA), 1 beep at `lastChanceDistance`, queue after active speech, merge within 150 m of 0.5 mi lane prompt ("now" / "همین حالا")
- [x] L3 Visual pulse: 3-second pulse on lane banner (and junction view if visible) with bright target lanes and dimmed other lanes
- [x] L4 Settings: "Lane change beeps" (ON), "Lane change timing" (early/normal/late), "Last chance beep" (ON) (EN & FA)
- [x] L5 Android Auto: short car Alert "Change lanes now" / "حالا خط را عوض کنید" when lane change cue fires
- [x] L6 I-5 simulation around Eugene/Springfield triggers lane change cues at highway exits

## V. Visual Improvements
- [x] V1 Maneuver arrow on map: 40 m before to 25 m after next maneuver, thick white line with dark outline + rotated arrowhead above route & below labels, DRIVE & BIKE only, <= 500 m city / <= 1 km highway, zoom >= 15, phone & Android Auto
- [x] V2 Route progress bar: thin right-edge vertical bar in DRIVE & BIKE, bottom = current / top = destination, icons for cameras/railroad/steep grades/school zones/highway exits/destination, cluster < 4% with count, max 20 icons, tap shows distance 3s, never overlaps speed/junction/buttons
- [x] V3 Speedometer ring: circular ring next to speed limit sign (normal / yellow within 3 mph or 5 km/h / red over limit + tolerance), "est." / "تخمینی" when `speedLimitSource == "hpms"`, phone & Android Auto

## T. Tests
- [x] T1 Unit tests: `LaneChangeCuePlanner`, settings migration, progress bar icon placement & clustering, speedometer ring color thresholds

## Final
- [x] FINAL All unit tests pass, APK builds with zero errors, mark everything DONE
