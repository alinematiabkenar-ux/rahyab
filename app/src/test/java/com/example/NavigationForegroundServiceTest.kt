package com.example

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.search.PlaceSearchResult
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.service.NavigationForegroundService
import com.example.ui.RahYabViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationForegroundServiceTest {

    private lateinit var app: RahYabApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        NavigationForegroundService.resetForTesting()
    }

    @After
    fun tearDown() {
        NavigationForegroundService.resetForTesting()
    }

    private fun grantLocationAndNotificationPermissions() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private fun revokeLocationPermissions() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private fun buildRoute(id: String, street: String, durationSec: Double): OfflineRoute {
        val shape = listOf(
            LatLon(44.0521, -123.0868),
            LatLon(44.0560, -123.0800),
            LatLon(44.0600, -123.0700)
        )
        return OfflineRoute(
            id = id,
            summaryLabel = street,
            distanceMeters = 4_500.0,
            durationSeconds = durationSec,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head east on $street",
                    streetNames = listOf(street),
                    lengthMeters = 1_500.0,
                    timeSeconds = durationSec * 0.4,
                    beginShapeIndex = 0,
                    endShapeIndex = 1,
                    lat = shape[0].lat,
                    lon = shape[0].lon
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Gateway St",
                    streetNames = listOf("Gateway St"),
                    lengthMeters = 3_000.0,
                    timeSeconds = durationSec * 0.6,
                    beginShapeIndex = 1,
                    endShapeIndex = 2,
                    lat = shape[1].lat,
                    lon = shape[1].lon
                )
            ),
            speedLimitsByShapeIndex = emptyMap(),
            roadAlerts = emptyList(),
            crossesStateLines = false,
            statesCrossed = listOf("OR"),
            hasTolls = false
        )
    }

    @Test
    fun startOrUpdateSkipsStartingServiceWithoutLocationPermission() {
        revokeLocationPermissions()
        NavigationForegroundService.startOrUpdate(app, "Best Buy • 10 min", "Head east")

        val started = shadowOf(app).nextStartedService
        assertNull("Service must never be started without location permission", started)
    }

    @Test
    fun startOrUpdateStartsOnceAndUpdateNotificationThrottlesAndSkipsUnchangedText() {
        grantLocationAndNotificationPermissions()

        NavigationForegroundService.startOrUpdate(app, "Best Buy • 10 min", "East 22nd Ave")
        val firstStart = shadowOf(app).nextStartedService
        assertNotNull("Expected initial startForegroundService intent", firstStart)
        assertEquals(NavigationForegroundService.ACTION_START_OR_UPDATE, firstStart.action)

        // Calling startOrUpdate again while running must NOT start the service again
        NavigationForegroundService.startOrUpdate(app, "Best Buy • 9 min", "Hilyard St")
        assertNull("Must not call startForegroundService again while running", shadowOf(app).nextStartedService)

        // Unchanged text is skipped
        val sameTextPosted = NavigationForegroundService.updateNotification(
            context = app,
            title = "Best Buy • 9 min",
            body = "Hilyard St",
            nowElapsedMs = 10_000L
        )
        assertFalse("Unchanged text must be skipped", sameTextPosted)

        // Changed text within 2 seconds is throttled
        val initialPosted = NavigationForegroundService.updateNotification(
            context = app,
            title = "1.2 mi • 8 min",
            body = "Hilyard St",
            force = true,
            nowElapsedMs = 20_000L
        )
        assertTrue(initialPosted)

        val tooSoonPosted = NavigationForegroundService.updateNotification(
            context = app,
            title = "1.1 mi • 8 min",
            body = "Hilyard St",
            force = false,
            nowElapsedMs = 21_500L
        )
        assertFalse("Updates faster than 2 seconds must be throttled", tooSoonPosted)

        // Changed text after >= 2 seconds posts via NotificationManagerCompat.notify() with same ID
        val after2sPosted = NavigationForegroundService.updateNotification(
            context = app,
            title = "0.9 mi • 7 min",
            body = "Franklin Blvd",
            force = false,
            nowElapsedMs = 22_100L
        )
        assertTrue("Changed text after 2 seconds must post notification", after2sPosted)
        assertNull("Notification updates must never start the service again", shadowOf(app).nextStartedService)

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = shadowOf(nm).getNotification(NavigationForegroundService.NOTIFICATION_ID)
        assertNotNull("Notification should be posted under NOTIFICATION_ID", active)
    }

    @Test
    fun onStartCommandAlwaysCallsStartForegroundFirstAndReturnsStartNotSticky() {
        grantLocationAndNotificationPermissions()

        // 1. Normal start intent
        val controller = Robolectric.buildService(NavigationForegroundService::class.java).create()
        val service = controller.get()
        NavigationForegroundService.startOrUpdate(app, "Best Buy • 10 min", "Head east")
        val startIntent = Intent(app, NavigationForegroundService::class.java).apply {
            action = NavigationForegroundService.ACTION_START_OR_UPDATE
            putExtra(NavigationForegroundService.EXTRA_TITLE, "Best Buy • 10 min")
            putExtra(NavigationForegroundService.EXTRA_BODY, "Head east")
        }
        val stickyMode = service.onStartCommand(startIntent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, stickyMode)
        assertEquals(1, NavigationForegroundService.startForegroundInvocations)
        assertNotNull("startForeground must be called on normal start", shadowOf(service).lastForegroundNotification)
        assertEquals(NavigationForegroundService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)

        // 2. Null intent restart must STILL call startForeground() first, then stop cleanly & return START_NOT_STICKY
        val nullController = Robolectric.buildService(NavigationForegroundService::class.java).create()
        val nullService = nullController.get()
        val nullResult = nullService.onStartCommand(null, 0, 2)
        assertEquals(Service.START_NOT_STICKY, nullResult)
        assertEquals("startForeground must be called before stopping on null intent", 2, NavigationForegroundService.startForegroundInvocations)
        assertTrue("stopForeground must detach/remove foreground state on null intent", shadowOf(nullService).isForegroundStopped)
        assertTrue("Null intent must stop service cleanly via stopSelf(startId)", shadowOf(nullService).isStoppedBySelf)

        // 3. Permission revoked mid-flight must STILL call startForeground() first, then stop cleanly
        revokeLocationPermissions()
        val noPermController = Robolectric.buildService(NavigationForegroundService::class.java).create()
        val noPermService = noPermController.get()
        val noPermResult = noPermService.onStartCommand(startIntent, 0, 3)
        assertEquals(Service.START_NOT_STICKY, noPermResult)
        assertEquals("startForeground must be called even if permission check fails", 3, NavigationForegroundService.startForegroundInvocations)
        assertTrue("stopForeground must run on missing permission", shadowOf(noPermService).isForegroundStopped)
        assertTrue("Missing permission must stop service cleanly", shadowOf(noPermService).isStoppedBySelf)

        // 4. ACTION_STOP must call startForeground() first and stop cleanly via stopSelf(startId)
        grantLocationAndNotificationPermissions()
        val stopController = Robolectric.buildService(NavigationForegroundService::class.java).create()
        val stopService = stopController.get()
        val stopActionIntent = Intent(app, NavigationForegroundService::class.java).apply {
            action = NavigationForegroundService.ACTION_STOP
        }
        val stopResult = stopService.onStartCommand(stopActionIntent, 0, 4)
        assertEquals(Service.START_NOT_STICKY, stopResult)
        assertEquals("startForeground must be called before stopping on ACTION_STOP", 4, NavigationForegroundService.startForegroundInvocations)
        assertTrue("stopForeground must run on ACTION_STOP", shadowOf(stopService).isForegroundStopped)
        assertTrue(shadowOf(stopService).isStoppedBySelf)
    }

    @Test
    fun stopUsesContextStopServiceNotStartServiceActionStop() {
        grantLocationAndNotificationPermissions()
        NavigationForegroundService.startOrUpdate(app, "Best Buy • 10 min", "East 22nd Ave")
        // Drain start intent
        assertNotNull(shadowOf(app).nextStartedService)

        NavigationForegroundService.stop(app)

        // Must NOT send ACTION_STOP via startService()
        assertNull("stop() must NOT call startService(ACTION_STOP)", shadowOf(app).nextStartedService)

        // Must call context.stopService()
        val stopped = shadowOf(app).nextStoppedService
        assertNotNull("stop() must call context.stopService()", stopped)
        assertEquals(NavigationForegroundService::class.java.name, stopped.component?.className)
    }

    @Test
    fun viewModelRouteSwitchingSimulatedRerouteRapidRestartAndScreenLockUnlockNeverCrashes() {
        grantLocationAndNotificationPermissions()
        val vm = RahYabViewModel(app)

        vm.selectPlace(
            PlaceSearchResult(
                id = "dest_1",
                name = "Best Buy",
                brand = "Best Buy",
                category = "electronics",
                address = "3300 Gateway St",
                city = "Springfield",
                stateCode = "OR",
                postcode = "97477",
                lat = 44.0600,
                lon = -123.0700,
                distanceMeters = 4500.0,
                relevanceScore = 100.0,
                source = "overture"
            )
        )

        val routes = listOf(
            buildRoute("route_fastest", "East 22nd Ave", 600.0).copy(isFastestRoute = true),
            buildRoute("route_shortest", "Hilyard St", 680.0).copy(isShortestRoute = true),
            buildRoute("route_alt_1", "Franklin Blvd", 720.0),
            buildRoute("route_alt_2", "Coburg Rd", 760.0)
        )
        val candField = RahYabViewModel::class.java.getDeclaredField("_candidateRoutes")
        candField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (candField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<OfflineRoute>>).value = routes

        // 1. Start navigation once -> strictly 1 startForegroundService() call
        vm.startNavigation()
        assertNotNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedService)

        // 2. Switch routes 20 times during active navigation -> ZERO extra service starts or stops!
        for (i in 0 until 20) {
            vm.selectCandidateRoute(i % routes.size)
        }
        assertNull("Switching routes must NOT restart the service", shadowOf(app).nextStartedService)
        assertNull("Switching routes must NOT stop the service", shadowOf(app).nextStoppedService)

        // 3. Simulate wrong turn + screen lock/unlock while navigating
        vm.triggerSimulatedWrongTurn()
        vm.setAppInForeground(false) // Screen locked / backgrounded
        vm.selectCandidateRoute(2)
        vm.setAppInForeground(true)  // Screen unlocked / foregrounded
        assertTrue(vm.navigationState.value.isNavigating)
        assertNull("Lock/unlock must NOT restart the service", shadowOf(app).nextStartedService)

        // 4. Rapidly stop and restart navigation 6 times in a row
        for (cycle in 0 until 6) {
            vm.stopNavigation()
            assertFalse(vm.navigationState.value.isNavigating)
            assertNotNull("stopNavigation must call stopService()", shadowOf(app).nextStoppedService)
            assertNull("stopNavigation must NOT call startService()", shadowOf(app).nextStartedService)

            vm.selectCandidateRoute(cycle % routes.size)
            vm.startNavigation()
            assertTrue(vm.navigationState.value.isNavigating)
            assertNotNull("Restarting navigation must call startForegroundService once", shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedService)
        }

        vm.stopNavigation()
    }
}
