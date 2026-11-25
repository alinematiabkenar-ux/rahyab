# RahYab Next Batch Status

## LN. Correct Lane Highlighted on the Road Itself
- [x] LN1 `LaneOverlayBuilder.kt` (pure Kotlin: 500 m before to 30 m after maneuver, 3.6 m geometric parallel offsets for one-way vs two-way, `edge.traversability` in `trace_attributes`, lane features & dashed white separators)
- [x] LN2 Rendering on phone & Android Auto (GeoJSON source & layers above route/below labels, zoom >= 16, camera min zoom 16.5 while active, remove 30 m after / off route / WALK or BIKE)
- [x] LN3 Settings: "Lane highlight on map" (ON) & "Lane highlight color" (Red `#E53935`, Blue `#1E88E5`, Green `#43A047`) EN & FA
- [x] LN4 Missing / < 2 / all-valid lanes -> no overlay; I-5 simulation around Eugene/Springfield shows lane overlay at exits

## SL. Speed Limit Drop Ahead
- [x] SL1 `SpeedLimitChangeAhead.kt` (pure Kotlin: detect drop >= 5 mph / 8 km/h lasting >= 150 m, ignore unknown values, return distance & new limit)
- [x] SL2 Voice warning in DRIVE mode only, once per change at `max(150 m, speedMps * 8 s)`, never interrupting turns/lanes/lane-change cues, setting "Speed limit drop warning" ON by default (EN & FA)
- [x] SL3 Visual upcoming speed limit sign + distance next to current speed limit sign from 500 m before change (phone & Android Auto)

## PK. Parking Near Destination
- [x] PK1 `ParkingNearDestinationFinder.kt`: offline places DB query for parking within 400 m, up to 5 sorted by walking distance (`straight-line * 1.25`, top 3 offline pedestrian route if < 1 s each), name/type/walking minutes
- [x] PK2 UI: card "Parking near your destination" / "پارکینگهای نزدیک مقصد" at < 800 m in DRIVE + "Parking nearby" / "پارکینگ نزدیک" button in route preview + P map icons; tapping switches driving destination to parking and keeps original final destination
- [x] PK3 On arrival at chosen parking: auto-save parked car location + offer "Walk to destination" / "پیاده تا مقصد" starting offline WALK route to original destination (show nothing if no parking found)

## VC. Offline English Voice Commands (phone only)
- [x] VC1 Offline `SpeechRecognizer` (`createOnDeviceSpeechRecognizer`, `en-US`, `RECORD_AUDIO` on first tap, Android 13+ `checkRecognitionSupport` / `triggerModelDownload` / offline speech download help, pause voice + audio focus, listening animation & text, mic button on drive screen & search box)
- [x] VC2 `VoiceCommandParser.kt` (pure Kotlin: navigate/take me/go to, go home/work, find nearest, where is/walk to my car, stop/cancel, mute/unmute, ETA, repeat, report road closed, zoom in/out, unknown -> "Sorry, I didn't understand" / "متوجه نشدم")
- [x] VC3 Actions: offline search, 1 strong result -> route preview with cancellable 5s auto-start countdown, multiple results -> list, answers/confirmations spoken in app language (EN/FA)
- [x] VC4 Confirm Android Auto untouched by voice commands

## T. Tests
- [x] T1 Unit tests: `LaneOverlayBuilder` offsets & guards, `SpeedLimitChangeAhead` thresholds/duration/unknowns, parking sorting & 400 m limit, `VoiceCommandParser` all commands + unknown text

## Final
- [x] FINAL All unit tests pass, APK builds with zero errors, mark everything DONE
