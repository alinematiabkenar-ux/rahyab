package com.example

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.local.SavedPlaceDao
import com.example.data.local.SavedPlaceEntity
import com.example.data.model.UsStatesCatalog
import com.example.data.online.GoogleMapsPlacesClient
import com.example.data.online.NetworkState
import com.example.data.packs.StatePackManager
import com.example.data.packs.StatePackUiItem
import com.example.data.search.OfflineAddressSearchEngine
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PersianQueryNormalizer
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.MapStyleBuilder
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.MapViewportMetrics
import com.example.navigation.NavigationSessionController
import com.example.navigation.NavigationViewportFraming
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.SynchronizedFollowAnimator
import com.example.navigation.TravelMode
import com.example.navigation.TtsEngineDriver
import com.example.navigation.TtsUtteranceCallbacks
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusSessionTracker
import com.example.navigation.VoiceGuidanceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Cold-start airplane-mode verification suite for RahYab's offline-first architecture and
 * optional Google Maps Platform integration.
 *
 * All SQLite queries, tar extractions, PMTiles style generation, lane guidance, marker-camera
 * framing, offline TTS queueing, and off-route rerouting run deterministically on Robolectric JVM
 * without any network access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineColdStartAirplaneModeTest {

    private val eugeneLat = 44.0521
    private val eugeneLon = -123.0868

    @Test
    fun coldStartAirplaneMode_oregonPackReadyAndUpgradePreservesTiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        app.networkStatusMonitor.setOverrideStateForTesting(NetworkState(isOnline = false))
        assertFalse(app.networkStatusMonitor.networkState.value.isOnline)

        val fakePackDao = InMemoryInstalledPackDao()
        val manager = StatePackManager(app, fakePackDao)
        installRealisticOregonPack(manager)

        // Simulate an app upgrade or cache cleanup that cleared extracted valhalla_tiles while
        // Android preserved app filesDir/packs/or/.
        manager.unifiedValhallaTileDir.deleteRecursively()
        assertFalse(manager.hasAnyValidValhallaTiles())

        // Cold-start pack audit in airplane mode must auto-extract Oregon's Valhalla tar and report Offline Ready.
        manager.auditInstalledPacks()

        val orRecord = fakePackDao.getByState("or")
        assertNotNull("Oregon pack should be recognized after cold-start audit", orRecord)
        assertEquals(PackInstallStatus.INSTALLED.name, orRecord!!.status)
        assertTrue("PMTiles header valid", orRecord.hasPmtiles)
        assertTrue("Valhalla tar valid", orRecord.hasValhalla)
        assertTrue("Places DB valid", orRecord.hasPlacesDb)
        assertTrue("Cold-start audit must re-extract .gph routing tiles offline", manager.hasAnyValidValhallaTiles())
        assertTrue("Oregon routing must be usable offline", manager.hasUsableRoutingForState("or"))
        assertTrue("Oregon address index must be usable offline", manager.hasUsableAddressIndex("or"))

        val uiItem = StatePackUiItem(
            stateCode = "or",
            abbr = "OR",
            nameEn = "Oregon",
            nameFa = "اورگن",
            status = PackInstallStatus.INSTALLED,
            installedVersion = orRecord.installedVersion,
            availableVersion = "",
            totalSizeBytes = orRecord.totalBytes,
            downloadedBytes = orRecord.downloadedBytes,
            installedBytesOnDisk = manager.getStateDiskBytes("or"),
            hasPmtiles = orRecord.hasPmtiles,
            hasValhalla = orRecord.hasValhalla,
            hasPlacesDb = orRecord.hasPlacesDb,
            hasUsableRoutingTiles = manager.hasUsableRoutingForState("or"),
            hasAddressIndex = manager.hasUsableAddressIndex("or"),
            hasManifestEntry = false
        )
        assertTrue("Oregon region card must report Offline Ready", uiItem.isOfflineReady)

        // Offline MapLibre style must use strictly local PMTiles + bundled asset fonts, zero HTTP URLs.
        val styleJson = MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = listOf("or" to manager.pmtilesFile("or")),
            isNightMode = false,
            language = AppLanguage.ENGLISH
        )
        val styleObj = org.json.JSONObject(styleJson)
        val orSourceUrl = styleObj.getJSONObject("sources").getJSONObject("pmtiles_or").getString("url")
        val glyphsUrl = styleObj.getString("glyphs")
        val unescapedStyle = styleJson.replace("\\/", "/")
        assertTrue("Style must reference local pmtiles://file:// (was $orSourceUrl)", orSourceUrl.startsWith("pmtiles://file://"))
        assertTrue("Style must reference bundled asset://fonts/ (was $glyphsUrl)", glyphsUrl.startsWith("asset://fonts/"))
        assertFalse("Offline style must not contain http:// URLs", unescapedStyle.contains("http://"))
        assertFalse("Offline style must not contain https:// URLs", unescapedStyle.contains("https://"))
    }

    @Test
    fun offlineFirstSearch_unqueriedAddressAndEnFaNearMeGasStationsRankNearestWithoutNetwork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        app.networkStatusMonitor.setOverrideStateForTesting(NetworkState(isOnline = false))

        val fakePackDao = InMemoryInstalledPackDao()
        val fakeSavedDao = InMemorySavedPlaceDao()
        val manager = StatePackManager(app, fakePackDao)
        installRealisticOregonPack(manager)
        manager.auditInstalledPacks()

        val driver = RobolectricOfflineSearchDriver()
        val searchEngine = OfflinePlaceSearchEngine(
            packManager = manager,
            savedPlaceDao = fakeSavedDao,
            sqliteDriver = driver
        )

        // 1. Previously unqueried exact Oregon street address
        val addressResults = searchEngine.search("1425 Oak Street, Eugene, OR", eugeneLat, eugeneLon, limit = 10)
        assertTrue("Unqueried address should resolve locally", addressResults.isNotEmpty())
        assertEquals(OfflineAddressSearchEngine.SOURCE_EXACT_ADDRESS, addressResults.first().source)
        assertTrue("Should return 1425 Oak St", addressResults.first().name.contains("1425"))

        // 2. English proximity query: "gas stations near me"
        val enExpanded = PersianQueryNormalizer.expandQuery("gas stations near me")
        assertTrue(enExpanded.isProximityQuery)
        assertTrue(enExpanded.isPureCategoryQuery)
        assertTrue(enExpanded.matchedCategoryTags.contains("gas_station"))

        val enGasResults = searchEngine.search("gas stations near me", eugeneLat, eugeneLon, limit = 10)
        assertTrue("English 'gas stations near me' must return local gas stations", enGasResults.size >= 3)
        assertEquals("Nearest station must rank #1", "Chevron Franklin Blvd", enGasResults[0].name)
        assertEquals("Second nearest station must rank #2", "Shell Coburg Rd", enGasResults[1].name)
        assertTrue(
            "Distances must be ascending for nearest-first ranking",
            enGasResults[0].distanceMeters < enGasResults[1].distanceMeters &&
                enGasResults[1].distanceMeters < enGasResults[2].distanceMeters
        )

        // 3. Persian joined proximity query: "نزدیکترین پمپبنزین به من"
        val faExpanded = PersianQueryNormalizer.expandQuery("نزدیکترین پمپبنزین به من")
        assertTrue(faExpanded.isPersianInput)
        assertTrue(faExpanded.isProximityQuery)
        assertTrue(faExpanded.isPureCategoryQuery)
        assertTrue(faExpanded.matchedCategoryTags.contains("gas_station"))

        val faGasResults = searchEngine.search("نزدیکترین پمپبنزین به من", eugeneLat, eugeneLon, limit = 10)
        assertTrue("Persian 'نزدیکترین پمپبنزین به من' must return local gas stations", faGasResults.size >= 3)
        assertEquals("Nearest station must rank #1 in Persian query too", "Chevron Franklin Blvd", faGasResults[0].name)
        assertEquals("Second nearest station must rank #2 in Persian query too", "Shell Coburg Rd", faGasResults[1].name)

        // 4. Out-of-coverage destination clearly identifies the missing state (Washington)
        val installedCodes = setOf("or")
        assertFalse(UsStatesCatalog.isPointCoveredByInstalledPacks(47.6062, -122.3321, installedCodes))
        val missingState = UsStatesCatalog.findBestStateForLocation(47.6062, -122.3321)
        assertNotNull(missingState)
        assertEquals("wa", missingState!!.code)
        assertEquals("Washington", missingState.nameEn)
    }

    @Test
    fun airplaneModeGuidanceReplay_blueLaneVehicleSyncOfflineVoiceAndMissedTurnLocalReroute() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        app.networkStatusMonitor.setOverrideStateForTesting(NetworkState(isOnline = false))

        val initialRoute = buildOregonRouteWithLanes(routeId = "or_route_initial")
        val controller = NavigationSessionController()
        var navState = ActiveNavigationState(
            isNavigating = true,
            route = initialRoute,
            destinationName = "Chevron Franklin Blvd",
            destinationAddress = "890 Franklin Blvd, Eugene, OR",
            travelMode = TravelMode.DRIVE,
            isSimulatingDrive = true
        )

        var fakeClockMs = 1_700_000_000_000L
        val spokenSegments = mutableListOf<String>()
        val fakeTts = ImmediateOfflineTtsDriver(spokenSegments)
        val voiceManager = VoiceGuidanceManager(app, clockMs = { fakeClockMs })
        voiceManager.setTestEngineDriver(fakeTts)
        voiceManager.setLanguage(AppLanguage.ENGLISH)

        val turnFocusTracker = TurnFocusSessionTracker()
        val followAnimator = SynchronizedFollowAnimator()

        // Drive into the 500 m approach window of maneuver 1 (right turn onto Franklin Blvd)
        val approachLat = 44.0521 + (700.0 / 111_320.0) // ~300 m before turn at 44.0611
        val approachFix = GpsFix(
            lat = approachLat,
            lon = -123.0868,
            bearing = 0f,
            speedMps = 13.4f,
            accuracyMeters = 4f,
            timestampMs = fakeClockMs
        )
        val resultApproach = controller.processGpsFix(
            currentState = navState,
            fix = approachFix,
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.METRIC,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            nowMs = fakeClockMs
        )
        navState = resultApproach.state

        // 1. Blue lane recommendation verified
        val activeManeuver = navState.currentManeuver
        assertNotNull(activeManeuver)
        val laneDecision = LaneGuidanceDecision.evaluate(activeManeuver!!, edgeLaneCountBeforeManeuver = 3)
        assertTrue("Blue lane panel must have actionable lane guidance", laneDecision.hasActionableLaneGuidance)
        assertTrue("Right lane must be recommended in blue", activeManeuver.lanes.last().isRecommended)

        // 2. Turn Focus activates inside approach window independently of audio
        val focusEvent = turnFocusTracker.evaluate(
            route = navState.route,
            currentManeuverIndex = navState.currentManeuverIndex,
            snappedShapeIndex = navState.snappedShapeIndex,
            distanceToNextManeuverMeters = navState.distanceToNextManeuverMeters,
            speedMps = navState.currentSpeedMps,
            travelMode = navState.travelMode,
            isNavigating = navState.isNavigating,
            isOffRoute = navState.isOffRoute,
            isRerouting = navState.isRerouting,
            hasArrived = navState.hasArrived,
            config = TurnFocusConfig(
                enabled = true,
                beepsEnabled = false,
                cityDistanceMeters = 500.0,
                highwayDistanceMeters = 500.0,
                wideHighwayEarlierEnabled = true
            ),
            snappedLocation = navState.snappedLocation,
            gpsAgeMs = 0L,
            snappedBearing = navState.snappedBearing
        )
        assertTrue("Turn focus must activate on approach", focusEvent.isActive)

        // 3. Synchronized vehicle marker + unobstructed viewport framing
        val viewport = MapViewportMetrics(
            widthPx = 1080,
            heightPx = 2200,
            insetTopPx = 420,
            insetBottomPx = 280
        )
        val vehicleLoc = navState.snappedLocation!!
        val pivotPt = initialRoute.shape[activeManeuver.beginShapeIndex]
        val framed = NavigationViewportFraming.computeBoundedFocusFraming(
            vehicleLoc = vehicleLoc,
            desiredCameraTarget = vehicleLoc,
            baseZoom = 17.5,
            tiltDeg = 40.0,
            bearingDeg = navState.snappedBearing.toDouble(),
            viewport = viewport,
            junctionPivot = pivotPt,
            anchorLowerQuarter = true
        )
        followAnimator.onNavigationTargetUpdate(
            nowMs = fakeClockMs,
            fixTimestampMs = fakeClockMs,
            markerLat = vehicleLoc.lat,
            markerLon = vehicleLoc.lon,
            markerBearing = navState.snappedBearing,
            camLat = framed.cameraTarget.lat,
            camLon = framed.cameraTarget.lon,
            camZoom = framed.zoom,
            camTilt = 40.0,
            camBearing = navState.snappedBearing.toDouble(),
            padding = framed.padding,
            durationMs = 450,
            isFollowingCamera = true
        )
        val pose = followAnimator.sample(fakeClockMs)!!
        val projected = NavigationViewportFraming.projectLatLonToScreen(
            point = LatLon(pose.markerLat, pose.markerLon),
            cameraLat = pose.camLat,
            cameraLon = pose.camLon,
            zoom = pose.camZoom,
            tiltDeg = pose.camTilt,
            bearingDeg = pose.camBearing,
            padding = pose.paddingArray(),
            viewport = viewport
        )
        assertTrue(
            "Vehicle marker must stay strictly inside unobstructed viewport (y=${projected.y})",
            viewport.isMarkerFullyInside(projected)
        )

        // 4. Offline voice guidance queues & completes cleanly without cutoff
        voiceManager.speakManeuver(
            maneuver = activeManeuver,
            distanceMeters = navState.distanceToNextManeuverMeters,
            unit = DistanceUnit.METRIC,
            language = AppLanguage.ENGLISH,
            announcementStage = NavigationSessionController.STAGE_CITY_1KM,
            includeLaneGuidance = true,
            laneDecision = laneDecision,
            routeId = initialRoute.id
        )
        assertTrue("Offline voice must speak full maneuver instruction", spokenSegments.isNotEmpty())
        assertTrue(
            "Spoken instruction must include street name",
            spokenSegments.joinToString(" ").contains("Franklin", ignoreCase = true)
        )

        // 5. Deliberately miss the turn (drive ~145 m off-route west for 4 consecutive GPS fixes)
        var triggeredReroute = false
        for (step in 1..4) {
            fakeClockMs += 1_000L
            val offRouteFix = GpsFix(
                lat = 44.0611 + step * 0.0004,
                lon = -123.0868 - 0.0018,
                bearing = 315f,
                speedMps = 13.4f,
                accuracyMeters = 5f,
                timestampMs = fakeClockMs
            )
            val update = controller.processGpsFix(
                currentState = navState,
                fix = offRouteFix,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = DistanceUnit.METRIC,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = false,
                nowMs = fakeClockMs
            )
            navState = update.state
            if (update.shouldReroute) {
                triggeredReroute = true
                break
            }
        }
        assertTrue("Missed turn must trigger local rerouting", triggeredReroute)

        // Simulate local offline reroute computed from the off-route location back to destination
        val reroutedLocalRoute = buildOregonRouteWithLanes(
            routeId = "or_route_local_reroute",
            startLon = -123.0886
        )
        navState = navState.copy(
            route = reroutedLocalRoute,
            currentManeuverIndex = 0,
            snappedShapeIndex = 0,
            isOffRoute = false,
            isRerouting = false
        )
        fakeClockMs += 1_000L
        val afterReroute = controller.processGpsFix(
            currentState = navState,
            fix = GpsFix(
                lat = 44.0550,
                lon = -123.0886,
                bearing = 0f,
                speedMps = 12.0f,
                accuracyMeters = 4f,
                timestampMs = fakeClockMs
            ),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.METRIC,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            nowMs = fakeClockMs
        )
        assertFalse("After local reroute, driver is back on-route", afterReroute.state.isOffRoute)
        assertEquals("or_route_local_reroute", afterReroute.state.route?.id)
    }

    @Test
    fun googleMapsDisabledOrKeyAbsent_zeroHttpCallsAndCleanParsingWhenExplicitlyInvoked() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        var httpRequestCount = 0
        val countingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor {
                httpRequestCount++
                throw IllegalStateException("Network must never be called when key is missing or offline")
            })
            .build()

        // 0. Without GOOGLE_MAPS_API_KEY in .env, Google Maps initialization is skipped completely
        assertFalse(
            "GOOGLE_MAPS_API_KEY must be optional and unconfigured by default",
            GoogleMapsPlacesClient.isOptionalKeyConfigured()
        )
        val viewModel = com.example.ui.RahYabViewModel(app)
        assertFalse("ViewModel must report Google Maps unconfigured without key", viewModel.isGoogleMapsKeyConfigured())
        assertTrue("ViewModel must skip initializing GoogleMapsPlacesClient when key is missing", viewModel.googleMapsPlacesClient == null)

        // 1. Key absent / placeholder: must report unconfigured and make ZERO network calls
        val placeholderClient = GoogleMapsPlacesClient(
            context = app,
            apiKeyProvider = { "YOUR_GOOGLE_MAPS_API_KEY" },
            httpClient = countingClient
        )
        assertFalse(placeholderClient.isConfigured)
        val noKeyResult = placeholderClient.searchText("gas stations near me", eugeneLat, eugeneLon)
        assertTrue(noKeyResult.isFailure)
        assertEquals(GoogleMapsPlacesClient.ERROR_API_KEY_NOT_CONFIGURED, noKeyResult.exceptionOrNull()?.message)
        assertEquals("Must perform 0 HTTP requests when API key is absent/placeholder", 0, httpRequestCount)

        val emptyKeyClient = GoogleMapsPlacesClient(
            context = app,
            apiKeyProvider = { "" },
            httpClient = countingClient
        )
        assertFalse(emptyKeyClient.isConfigured)
        assertTrue(emptyKeyClient.searchText("Eugene Library", eugeneLat, eugeneLon).isFailure)
        assertEquals(0, httpRequestCount)

        // 2. Verify official Places API (New) JSON parsing & ToS-compliant attribution/URL handoff
        val sampleResponse = """
            {
              "places": [
                {
                  "id": "ChIJExampleEugenePlace123",
                  "displayName": { "text": "Eugene Public Library" },
                  "formattedAddress": "100 W 10th Ave, Eugene, OR 97401, USA",
                  "location": { "latitude": 44.0489, "longitude": -123.0937 },
                  "primaryTypeDisplayName": { "text": "Public Library" },
                  "addressComponents": [
                    { "shortText": "OR", "types": ["administrative_area_level_1"] }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsed = GoogleMapsPlacesClient.parseSearchTextResponse(sampleResponse, eugeneLat, eugeneLon, limit = 5)
        assertEquals(1, parsed.size)
        assertEquals("Eugene Public Library", parsed[0].name)
        assertEquals(GoogleMapsPlacesClient.SOURCE_ONLINE_GOOGLE_MAPS, parsed[0].source)
        assertEquals("or", parsed[0].stateCode)
        val externalUrl = GoogleMapsPlacesClient.buildGoogleMapsExternalUrl(parsed[0])
        assertTrue(externalUrl.contains("query_place_id=ChIJExampleEugenePlace123"))
    }

    @Test
    fun speechCancelStaleAdvancesValidQueuedInstruction_andMarkerGlidesDuringCameraHold() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        var clockMs = 1_700_200_000_000L

        // 1. Speech: when stale maneuver 1 is canceled while maneuver 2 is queued, maneuver 2 speaks immediately
        val startedTexts = mutableListOf<String>()
        val pendingCallbacks = mutableListOf<Pair<String, TtsUtteranceCallbacks>>()
        val deferredDriver = object : TtsEngineDriver {
            override var isReady: Boolean = true
            override var isSpeaking: Boolean = false
            override fun speakSegment(
                text: String,
                isEnglish: Boolean,
                useFallbackEngine: Boolean,
                utteranceId: String,
                callbacks: TtsUtteranceCallbacks
            ): Boolean {
                isSpeaking = true
                startedTexts.add(text)
                callbacks.onStart(utteranceId)
                pendingCallbacks.add(utteranceId to callbacks)
                return true
            }
            override fun stopAll() {
                isSpeaking = false
                pendingCallbacks.clear()
            }
        }
        val voice = VoiceGuidanceManager(app, clockMs = { clockMs })
        voice.setTestEngineDriver(deferredDriver)
        voice.setLanguage(AppLanguage.ENGLISH)

        val route = buildOregonRouteWithLanes("speech_advance_route")
        val m1 = route.maneuvers[1]
        val m2 = route.maneuvers[2]
        // Start maneuver 1 preparation (leaves TTS speaking)
        voice.speakManeuver(m1, 500.0, DistanceUnit.METRIC, AppLanguage.ENGLISH, NavigationSessionController.STAGE_CITY_1KM, routeId = route.id)
        assertEquals(1, startedTexts.size)
        // Queue a non-stale critical road alert while maneuver 1 is speaking (must queue, not interrupt m1)
        val cameraAlert = com.example.navigation.AheadAlert(
            id = "cam_1",
            kind = com.example.navigation.RoadAlertKind.SPEED_CAMERA,
            lat = 44.0600,
            lon = -123.0868,
            distanceMeters = 350.0,
            titleEn = "Speed camera ahead",
            titleFa = "دوربین کنترل سرعت در پیش رو"
        )
        voice.speakAlert(cameraAlert, AppLanguage.ENGLISH, DistanceUnit.METRIC)
        assertEquals("Critical alert must queue behind active maneuver instead of cutting it off", 1, startedTexts.size)
        // Advance active maneuver past m1 -> cancelStaleForManeuverOrReroute stops stale m1 and MUST start queued alert immediately
        voice.cancelStaleForManeuverOrReroute(activeRouteId = route.id, activeManeuverIndex = m2.index)
        assertEquals("Queued non-stale alert must immediately start speaking after stale m1 cancel", 2, startedTexts.size)
        assertTrue("Second spoken text must be the queued speed camera alert", startedTexts.last().contains("camera", ignoreCase = true))

        // 2. Vehicle marker: smoothly interpolates during camera-hold / throttled camera updates without moving camera
        val animator = SynchronizedFollowAnimator()
        val pad = doubleArrayOf(0.0, 320.0, 0.0, 200.0)
        animator.onNavigationTargetUpdate(
            nowMs = 1_000L, fixTimestampMs = 1_000L,
            markerLat = 44.0521, markerLon = -123.0868, markerBearing = 0f,
            camLat = 44.0525, camLon = -123.0868, camZoom = 17.0, camTilt = 40.0, camBearing = 0.0,
            padding = pad, durationMs = 300, isFollowingCamera = true
        )
        // Camera holds steady while GPS moves slightly forward at low speed -> marker-only update advances marker
        animator.onMarkerOnlyUpdate(
            nowMs = 1_400L, fixTimestampMs = 1_400L,
            markerLat = 44.0522, markerLon = -123.0868, markerBearing = 5f, durationMs = 180
        )
        val endPose = animator.sample(1_600L)!!
        assertEquals(44.0522, endPose.markerLat, 1e-6)
        assertEquals(5f, endPose.markerBearing, 0.5f)
        assertEquals(44.0525, endPose.camLat, 1e-6)
    }

    private fun installRealisticOregonPack(manager: StatePackManager) {
        val dir = manager.stateDir("or")
        dir.mkdirs()

        // 1. Valid PMTiles v3 header
        val pmFile = manager.pmtilesFile("or")
        FileOutputStream(pmFile).use { fos ->
            fos.write("PMTiles".toByteArray(StandardCharsets.US_ASCII))
            fos.write(ByteArray(256))
        }

        // 2. Valid POSIX tar archive containing a real Valhalla .gph tile entry
        val tarFile = manager.valhallaTarFile("or")
        writeSingleEntryTar(tarFile, "2/000/123.gph", ByteArray(256) { 0x42 })

        // 3. Valid SQLite places + addresses + road_hazards DB for Eugene & Salem, Oregon
        val dbFile = manager.placesDbFile("or")
        if (dbFile.exists()) dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    brand TEXT,
                    category TEXT,
                    address TEXT,
                    city TEXT,
                    state TEXT,
                    postcode TEXT,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    popularity REAL DEFAULT 1.0,
                    source TEXT DEFAULT 'overture'
                )
                """.trimIndent()
            )
            db.execSQL("CREATE VIRTUAL TABLE places_fts USING fts4(name, brand, category, address, city)")
            db.execSQL(
                """
                CREATE TABLE addresses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    number TEXT NOT NULL,
                    street TEXT NOT NULL,
                    unit TEXT,
                    city TEXT,
                    state TEXT,
                    postcode TEXT,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    source TEXT DEFAULT 'overture'
                )
                """.trimIndent()
            )
            db.execSQL("CREATE VIRTUAL TABLE addresses_fts USING fts4(number, street, city, postcode)")
            db.execSQL(
                """
                CREATE TABLE road_hazards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    label TEXT,
                    speed_limit_mph INT,
                    direction_deg REAL,
                    osm_id TEXT
                )
                """.trimIndent()
            )

            // Seed Oregon gas stations at varying distances from Eugene (44.0521, -123.0868)
            db.execSQL(
                "INSERT INTO places (id, name, brand, category, address, city, state, postcode, lat, lon, popularity, source) VALUES " +
                    "(1, 'Chevron Franklin Blvd', 'Chevron', 'gas_station', '890 Franklin Blvd', 'Eugene', 'OR', '97401', 44.0498, -123.0825, 2.0, 'overture')," +
                    "(2, 'Shell Coburg Rd', 'Shell', 'gas_station', '1690 Coburg Rd', 'Eugene', 'OR', '97401', 44.0820, -123.0680, 9.5, 'overture')," +
                    "(3, 'ARCO Gateway Springfield', 'ARCO', 'fuel', '3100 Gateway St', 'Springfield', 'OR', '97477', 44.0860, -123.0120, 10.0, 'osm')," +
                    "(4, 'Fred Meyer West 11th', 'Fred Meyer', 'supermarket', '60 Division Ave', 'Eugene', 'OR', '97404', 44.0540, -123.1120, 8.0, 'overture')"
            )

            // Seed previously unqueried Eugene address
            db.execSQL(
                "INSERT INTO addresses (id, number, street, unit, city, state, postcode, lat, lon, source) VALUES " +
                    "(101, '1425', 'Oak St', '', 'Eugene', 'OR', '97401', 44.0442, -123.0908, 'overture')"
            )
            db.execSQL(
                "INSERT INTO addresses_fts (rowid, number, street, city, postcode) VALUES (101, '1425', 'oak st', 'Eugene', 'OR', '97401')"
                    .replace(", 'OR'", "")
            )

            // Seed traffic signal along Eugene route
            db.execSQL(
                "INSERT INTO road_hazards (type, lat, lon, label) VALUES ('traffic_light', 44.0595, -123.0868, 'Franklin Blvd & Onyx St')"
            )
        } finally {
            db.close()
        }
    }

    private fun buildOregonRouteWithLanes(routeId: String, startLon: Double = -123.0868): OfflineRoute {
        val points = mutableListOf<LatLon>()
        // 1 km north along Onyx St (100 points, ~10 m apart), then 400 m east along Franklin Blvd
        for (i in 0..100) {
            points.add(LatLon(44.0521 + (i * 10.0) / 111_320.0, startLon))
        }
        val turnLat = points.last().lat
        val turnIdx = points.lastIndex
        for (i in 1..40) {
            points.add(LatLon(turnLat, startLon + (i * 10.0) / 80_000.0))
        }
        val lanes = listOf(
            LaneInfo(directions = listOf(LaneDirection.THROUGH), activeDirections = emptyList(), isRecommended = false),
            LaneInfo(directions = listOf(LaneDirection.THROUGH), activeDirections = emptyList(), isRecommended = false),
            LaneInfo(
                directions = listOf(LaneDirection.RIGHT),
                activeDirections = listOf(LaneDirection.RIGHT),
                isRecommended = true,
                isActive = true,
                isValid = true
            )
        )
        return OfflineRoute(
            id = routeId,
            summaryLabel = "Fastest",
            distanceMeters = 1_400.0,
            durationSeconds = 110.0,
            shape = points,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north on Onyx St",
                    streetNames = listOf("Onyx St"),
                    lengthMeters = 1_000.0,
                    timeSeconds = 75.0,
                    beginShapeIndex = 0,
                    endShapeIndex = turnIdx,
                    lat = points.first().lat,
                    lon = points.first().lon,
                    laneCount = 3,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Franklin Blvd",
                    streetNames = listOf("Franklin Blvd"),
                    lengthMeters = 400.0,
                    timeSeconds = 35.0,
                    beginShapeIndex = turnIdx,
                    endShapeIndex = points.lastIndex,
                    lat = points[turnIdx].lat,
                    lon = points[turnIdx].lon,
                    lanes = lanes,
                    laneSource = LaneSource.DIRECT,
                    laneCount = 3,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 2,
                    type = ManeuverType.ARRIVE,
                    instructionEn = "You have arrived at Chevron Franklin Blvd",
                    streetNames = listOf("Franklin Blvd"),
                    lengthMeters = 0.0,
                    timeSeconds = 0.0,
                    beginShapeIndex = points.lastIndex,
                    endShapeIndex = points.lastIndex,
                    lat = points.last().lat,
                    lon = points.last().lon
                )
            ),
            travelMode = TravelMode.DRIVE
        )
    }

    private fun writeSingleEntryTar(tarFile: File, entryName: String, content: ByteArray) {
        val header = ByteArray(512)
        val nameBytes = entryName.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
        "0000644\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 100)
        "0000000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 108)
        "0000000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 116)
        String.format("%011o\u0000", content.size).toByteArray(StandardCharsets.US_ASCII).copyInto(header, 124)
        "00000000000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 136)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 257)
        "00".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 263)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        String.format("%06o\u0000 ", checksum).toByteArray(StandardCharsets.US_ASCII).copyInto(header, 148)

        val baos = ByteArrayOutputStream()
        baos.write(header)
        baos.write(content)
        val pad = (512 - (content.size % 512)) % 512
        if (pad > 0) baos.write(ByteArray(pad))
        baos.write(ByteArray(1024))
        tarFile.writeBytes(baos.toByteArray())
    }

    private class ImmediateOfflineTtsDriver(
        private val spokenOut: MutableList<String>
    ) : TtsEngineDriver {
        override var isReady: Boolean = true
        override val isSpeaking: Boolean = false

        override fun speakSegment(
            text: String,
            isEnglish: Boolean,
            useFallbackEngine: Boolean,
            utteranceId: String,
            callbacks: TtsUtteranceCallbacks
        ): Boolean {
            spokenOut.add(text)
            callbacks.onStart(utteranceId)
            callbacks.onDone(utteranceId)
            return true
        }

        override fun stopAll() = Unit
    }

    private class InMemoryInstalledPackDao : InstalledPackDao {
        private val flow = MutableStateFlow<List<InstalledPackEntity>>(emptyList())
        override fun observeAll(): Flow<List<InstalledPackEntity>> = flow
        override suspend fun getAllOnce(): List<InstalledPackEntity> = flow.value
        override suspend fun getByState(stateCode: String): InstalledPackEntity? =
            flow.value.find { it.stateCode.equals(stateCode, true) }
        override suspend fun upsert(pack: InstalledPackEntity) {
            flow.value = flow.value.filterNot { it.stateCode.equals(pack.stateCode, true) } + pack
        }
        override suspend fun delete(stateCode: String) {
            flow.value = flow.value.filterNot { it.stateCode.equals(stateCode, true) }
        }
    }

    private class InMemorySavedPlaceDao : SavedPlaceDao {
        private val flow = MutableStateFlow<List<SavedPlaceEntity>>(emptyList())
        override fun observeAll(): Flow<List<SavedPlaceEntity>> = flow
        override fun observeByType(type: String): Flow<List<SavedPlaceEntity>> =
            flow.map { list -> list.filter { it.type == type } }
        override suspend fun getAllOnce(): List<SavedPlaceEntity> = flow.value
        override suspend fun insert(place: SavedPlaceEntity): Long {
            val id = (flow.value.maxOfOrNull { it.id } ?: 0L) + 1L
            flow.value = flow.value + place.copy(id = id)
            return id
        }
        override suspend fun deleteById(id: Long) { flow.value = flow.value.filterNot { it.id == id } }
        override suspend fun deleteByType(type: String) { flow.value = flow.value.filterNot { it.type == type } }
        override suspend fun trimRecent() = Unit
    }

    /**
     * Robolectric SQLiteDriver supporting both standard `addresses_fts` (FTS4) and emulated
     * `places_fts` category/text queries with exact distance ordering on Linux JVM.
     */
    private class RobolectricOfflineSearchDriver : SQLiteDriver {
        override fun open(fileName: String): SQLiteConnection {
            val db = SQLiteDatabase.openDatabase(fileName, null, SQLiteDatabase.OPEN_READONLY)
            return object : SQLiteConnection {
                override fun prepare(sql: String): SQLiteStatement = DriverStatement(db, sql)
                override fun close() = db.close()
            }
        }

        private class DriverStatement(
            private val db: SQLiteDatabase,
            private val originalSql: String
        ) : SQLiteStatement {
            private val args = mutableMapOf<Int, String?>()
            private var cursor: Cursor? = null
            private var executed = false

            override fun bindBlob(index: Int, value: ByteArray) { args[index] = value.decodeToString() }
            override fun bindDouble(index: Int, value: Double) { args[index] = value.toString() }
            override fun bindLong(index: Int, value: Long) { args[index] = value.toString() }
            override fun bindText(index: Int, value: String) { args[index] = value }
            override fun bindNull(index: Int) { args[index] = null }

            override fun step(): Boolean {
                if (!executed) {
                    executed = true
                    val maxIdx = args.keys.maxOrNull() ?: 0
                    val rawArgs = Array(maxIdx) { idx -> args[idx + 1] ?: "" }
                    if (originalSql.contains("places_fts MATCH ?")) {
                        val isNearby = originalSql.contains("p.lat BETWEEN ? AND ?")
                        val ftsExpr = rawArgs.getOrNull(1).orEmpty()
                        val isCategoryOr = ftsExpr.contains("category:")
                        val tokens = ftsExpr
                            .replace("category:", "")
                            .replace("*", "")
                            .replace("\"", "")
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() && !it.equals("AND", true) && !it.equals("OR", true) }
                        val joiner = if (isCategoryOr) " OR " else " AND "
                        val whereClause = if (tokens.isEmpty()) "1=1" else "(" + tokens.joinToString(joiner) {
                            if (isCategoryOr) {
                                "LOWER(COALESCE(p.category,'')) LIKE ?"
                            } else {
                                "LOWER(p.name || ' ' || COALESCE(p.brand,'') || ' ' || COALESCE(p.category,'') || ' ' || COALESCE(p.address,'') || ' ' || COALESCE(p.city,'')) LIKE ?"
                            }
                        } + ")"
                        val tokenArgs = tokens.map { "%${it.lowercase()}%" }
                        val hasBm25 = originalSql.contains("bm25(")
                        val extraCol = if (hasBm25) ", -8.0 AS rank_score" else ""
                        val hasDistanceOrder = originalSql.contains("ORDER BY ((p.lat - ?)")
                        val orderByClause = if (hasDistanceOrder) {
                            "ORDER BY ((p.lat - ?) * (p.lat - ?) + (p.lon - ?) * (p.lon - ?) * ?) ASC"
                        } else {
                            "ORDER BY COALESCE(p.popularity, 1.0) DESC"
                        }
                        val translatedSql = if (isNearby) {
                            """
                            SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                                   COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                                   COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                                   COALESCE(p.popularity, 1.0)$extraCol
                            FROM places p
                            WHERE $whereClause
                              AND p.lat BETWEEN ? AND ?
                              AND p.lon BETWEEN ? AND ?
                            $orderByClause
                            LIMIT ?
                            """.trimIndent()
                        } else {
                            """
                            SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                                   COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                                   COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                                   COALESCE(p.popularity, 1.0)$extraCol
                            FROM places p
                            WHERE $whereClause
                            $orderByClause
                            LIMIT ?
                            """.trimIndent()
                        }
                        val translatedArgs = buildList {
                            add(rawArgs[0])
                            addAll(tokenArgs)
                            for (i in 2 until rawArgs.size) add(rawArgs[i])
                        }
                        cursor = db.rawQuery(translatedSql, translatedArgs.toTypedArray())
                    } else {
                        cursor = db.rawQuery(originalSql, rawArgs)
                    }
                }
                return cursor?.moveToNext() ?: false
            }

            override fun getBlob(index: Int): ByteArray = cursor!!.getBlob(index)
            override fun getDouble(index: Int): Double = cursor!!.getDouble(index)
            override fun getLong(index: Int): Long = cursor!!.getLong(index)
            override fun getText(index: Int): String = cursor!!.getString(index) ?: ""
            override fun isNull(index: Int): Boolean = cursor!!.isNull(index)
            override fun getColumnCount(): Int = cursor?.columnCount ?: 0
            override fun getColumnName(index: Int): String = cursor?.getColumnName(index) ?: ""
            override fun getColumnType(index: Int): Int = cursor?.getType(index) ?: 0
            override fun reset() { cursor?.close(); cursor = null; executed = false }
            override fun clearBindings() { args.clear() }
            override fun close() { cursor?.close(); cursor = null }
        }
    }
}
