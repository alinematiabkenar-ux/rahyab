package com.example

import androidx.car.app.navigation.model.LaneDirection as CarLaneDirection
import androidx.car.app.navigation.model.Maneuver
import com.example.car.CarManeuverMapper
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.EnglishStrings
import com.example.i18n.PersianStrings
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.SharedNavigationStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AA9: Pure JVM unit tests for Android Auto maneuver mapping, lane bitmask mapping,
 * TravelEstimate snapshot & bilingual cue formatting, shared navigation hub state sync,
 * and constructor-parameter guardrail checks.
 */
class AndroidAutoUnitTest {

    @Test
    fun allValhallaManeuverTypesMapToExpectedCarManeuverTypes() {
        val expected = mapOf(
            ManeuverType.DEPART to Maneuver.TYPE_DEPART,
            ManeuverType.STRAIGHT to Maneuver.TYPE_STRAIGHT,
            ManeuverType.SLIGHT_RIGHT to Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            ManeuverType.RIGHT to Maneuver.TYPE_TURN_NORMAL_RIGHT,
            ManeuverType.SHARP_RIGHT to Maneuver.TYPE_TURN_SHARP_RIGHT,
            ManeuverType.U_TURN_LEFT to Maneuver.TYPE_U_TURN_LEFT,
            ManeuverType.U_TURN_RIGHT to Maneuver.TYPE_U_TURN_RIGHT,
            ManeuverType.SLIGHT_LEFT to Maneuver.TYPE_TURN_SLIGHT_LEFT,
            ManeuverType.LEFT to Maneuver.TYPE_TURN_NORMAL_LEFT,
            ManeuverType.SHARP_LEFT to Maneuver.TYPE_TURN_SHARP_LEFT,
            ManeuverType.RAMP_RIGHT to Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
            ManeuverType.RAMP_LEFT to Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
            ManeuverType.EXIT_RIGHT to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
            ManeuverType.EXIT_LEFT to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
            ManeuverType.STAY_RIGHT to Maneuver.TYPE_KEEP_RIGHT,
            ManeuverType.STAY_LEFT to Maneuver.TYPE_KEEP_LEFT,
            ManeuverType.MERGE to Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED,
            ManeuverType.ROUNDABOUT to Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW,
            ManeuverType.FERRY to Maneuver.TYPE_FERRY_BOAT,
            ManeuverType.ARRIVE to Maneuver.TYPE_DESTINATION,
            ManeuverType.ARRIVE_RIGHT to Maneuver.TYPE_DESTINATION_RIGHT,
            ManeuverType.ARRIVE_LEFT to Maneuver.TYPE_DESTINATION_LEFT
        )

        assertEquals(ManeuverType.entries.size, expected.size)
        for ((valhallaType, carType) in expected) {
            assertEquals("Mismatch for $valhallaType", carType, CarManeuverMapper.toCarManeuverType(valhallaType))
        }
    }

    @Test
    fun valhallaLaneBitmasksMapToCarLaneDirectionsAndRecommendations() {
        val singleCases = listOf(
            CarManeuverMapper.BIT_SHARP_LEFT to CarLaneDirection.SHAPE_SHARP_LEFT,
            CarManeuverMapper.BIT_LEFT to CarLaneDirection.SHAPE_NORMAL_LEFT,
            CarManeuverMapper.BIT_SLIGHT_LEFT to CarLaneDirection.SHAPE_SLIGHT_LEFT,
            CarManeuverMapper.BIT_THROUGH to CarLaneDirection.SHAPE_STRAIGHT,
            CarManeuverMapper.BIT_SLIGHT_RIGHT to CarLaneDirection.SHAPE_SLIGHT_RIGHT,
            CarManeuverMapper.BIT_RIGHT to CarLaneDirection.SHAPE_NORMAL_RIGHT,
            CarManeuverMapper.BIT_SHARP_RIGHT to CarLaneDirection.SHAPE_SHARP_RIGHT,
            CarManeuverMapper.BIT_REVERSE to CarLaneDirection.SHAPE_U_TURN_LEFT
        )

        for ((bit, expectedShape) in singleCases) {
            val mapped = CarManeuverMapper.mapValhallaLaneBitmask(directionsMask = bit, activeMask = bit)
            assertEquals(1, mapped.size)
            assertEquals(expectedShape, mapped[0].shape)
            assertTrue("Bit $bit should be recommended", mapped[0].isRecommended)
        }

        // Combined lane (through + right) where only right is recommended
        val combined = CarManeuverMapper.mapValhallaLaneBitmask(
            directionsMask = CarManeuverMapper.BIT_THROUGH or CarManeuverMapper.BIT_RIGHT,
            activeMask = CarManeuverMapper.BIT_RIGHT
        )
        assertEquals(2, combined.size)
        assertEquals(CarLaneDirection.SHAPE_STRAIGHT, combined[0].shape)
        assertFalse(combined[0].isRecommended)
        assertEquals(CarLaneDirection.SHAPE_NORMAL_RIGHT, combined[1].shape)
        assertTrue(combined[1].isRecommended)

        // Empty mask falls back to SHAPE_UNKNOWN (not recommended)
        val unknown = CarManeuverMapper.mapValhallaLaneBitmask(directionsMask = 0, activeMask = 0)
        assertEquals(1, unknown.size)
        assertEquals(CarLaneDirection.SHAPE_UNKNOWN, unknown[0].shape)
        assertFalse(unknown[0].isRecommended)

        // Enum mapping covers all LaneDirection values
        assertEquals(CarLaneDirection.SHAPE_STRAIGHT, CarManeuverMapper.toCarLaneDirectionShape(LaneDirection.THROUGH))
        assertEquals(CarLaneDirection.SHAPE_U_TURN_RIGHT, CarManeuverMapper.toCarLaneDirectionShape(LaneDirection.U_TURN_RIGHT))
        assertEquals(CarLaneDirection.SHAPE_SLIGHT_LEFT, CarManeuverMapper.toCarLaneDirectionShape(LaneDirection.MERGE_LEFT))
        assertEquals(CarLaneDirection.SHAPE_SLIGHT_RIGHT, CarManeuverMapper.toCarLaneDirectionShape(LaneDirection.MERGE_RIGHT))
    }

    @Test
    fun travelEstimateSnapshotAndBilingualCarCuePreserveRoadNames() {
        val nowMs = 1_700_000_000_000L
        val snap = CarManeuverMapper.computeTravelEstimateSnapshot(
            remainingDistanceMeters = 12_345.6,
            remainingTimeSeconds = 842.4,
            nowEpochMs = nowMs
        )
        assertEquals(12_345.6, snap.remainingDistanceMeters, 0.001)
        assertEquals(842L, snap.remainingTimeSeconds)
        assertEquals(nowMs + 842_000L, snap.arrivalEpochMs)

        // Negative values clamp cleanly to 0
        val clamped = CarManeuverMapper.computeTravelEstimateSnapshot(
            remainingDistanceMeters = -50.0,
            remainingTimeSeconds = -12.0,
            nowEpochMs = nowMs
        )
        assertEquals(0.0, clamped.remainingDistanceMeters, 0.001)
        assertEquals(0L, clamped.remainingTimeSeconds)
        assertEquals(nowMs, clamped.arrivalEpochMs)

        // Bilingual cue preserves raw street/highway names
        val exitManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B onto I-105 W",
            streetNames = listOf("I-105 W"),
            lengthMeters = 650.0,
            timeSeconds = 30.0,
            beginShapeIndex = 0,
            endShapeIndex = 5,
            lat = 44.058,
            lon = -123.095,
            signpost = HighwaySignpost(
                exitNumbers = listOf("194B"),
                exitBranches = listOf("I-105 W")
            )
        )
        assertEquals("I-105 W", CarManeuverMapper.formatCarStepCue(exitManeuver, AppLanguage.ENGLISH))
        val faCue = CarManeuverMapper.formatCarStepCue(exitManeuver, AppLanguage.PERSIAN)
        assertTrue("Persian cue must contain street/highway name", faCue.contains("I-105 W"))
        assertTrue("Persian cue must contain Persian maneuver label", faCue.contains("خروجی"))
    }

    @Test
    fun sharedNavigationStateStoreSyncsStartProgressAndStopAcrossObservers() {
        val store = SharedNavigationStateStore()
        assertFalse(store.navigationState.value.isNavigating)

        val route = OfflineRoute(
            id = "route_aa_test",
            summaryLabel = "Fastest",
            distanceMeters = 5_000.0,
            durationSeconds = 420.0,
            shape = listOf(LatLon(44.0521, -123.0868), LatLon(44.0600, -123.0900)),
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Willamette St",
                    streetNames = listOf("Willamette St"),
                    lengthMeters = 800.0,
                    timeSeconds = 60.0,
                    beginShapeIndex = 0,
                    endShapeIndex = 1,
                    lat = 44.0521,
                    lon = -123.0868
                )
            )
        )

        store.startSessionSnapshot(
            route = route,
            destinationName = "Eugene Station",
            destinationAddress = "1080 Willamette St",
            isSimulating = false
        )
        val started = store.navigationState.value
        assertTrue(started.isNavigating)
        assertEquals("Eugene Station", started.destinationName)
        assertEquals(800.0, started.distanceToNextManeuverMeters, 0.01)
        assertEquals(5_000.0, started.remainingDistanceMeters, 0.01)

        // Update mid-trip progress & ahead alert
        store.updateNavigationState(
            started.copy(
                distanceToNextManeuverMeters = 250.0,
                remainingDistanceMeters = 3_200.0,
                remainingTimeSeconds = 260.0,
                currentSpeedLimitMph = 35,
                isOverspeed = true
            )
        )
        store._freeDriveAheadAlert.value = AheadAlert(
            id = "cam_1",
            kind = RoadAlertKind.SPEED_CAMERA,
            lat = 44.055,
            lon = -123.088,
            distanceMeters = 180.0,
            titleEn = "Speed camera ahead",
            titleFa = "دوربین کنترل سرعت"
        )
        assertEquals(250.0, store.navigationState.value.distanceToNextManeuverMeters, 0.01)
        assertEquals(35, store.navigationState.value.currentSpeedLimitMph)
        assertTrue(store.navigationState.value.isOverspeed)
        assertEquals("cam_1", store.freeDriveAheadAlert.value?.id)

        // Clear session resets all active state cleanly
        store.clearSession()
        assertFalse(store.navigationState.value.isNavigating)
        assertNull(store.navigationState.value.route)
        assertNull(store.freeDriveAheadAlert.value)
        assertNull(store.aheadTrafficLightMeters.value)
    }

    @Test
    fun appStringsAndCoreModelsStayWellUnder200ConstructorParameters() {
        val checkedClasses = listOf(
            EnglishStrings::class.java,
            PersianStrings::class.java,
            ActiveNavigationState::class.java,
            UserSettings::class.java,
            SharedNavigationStateStore::class.java,
            CarManeuverMapper::class.java
        )
        for (cls in checkedClasses) {
            val maxParams = cls.declaredConstructors.maxOfOrNull { it.parameterCount } ?: 0
            assertTrue(
                "${cls.simpleName} constructor has $maxParams params (limit 200)",
                maxParams <= 200
            )
        }
        // Spot-check bilingual Android Auto strings are non-blank and compact
        assertTrue(EnglishStrings.carNoOfflineMapMessage.isNotBlank())
        assertTrue(PersianStrings.carNoOfflineMapMessage.isNotBlank())
        assertTrue(PersianStrings.carActionSearch.length <= 20)
        assertTrue(PersianStrings.carActionStop.length <= 20)
    }
}
