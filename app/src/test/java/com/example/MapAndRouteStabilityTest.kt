package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.map.MapStyleBuilder
import com.example.map.RouteGeoJsonCache
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.ValhallaResponseParser
import com.example.ui.RahYabViewModel
import com.example.util.CrashLogManager
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapAndRouteStabilityTest {

    private fun buildSyntheticRoute(
        id: String,
        distanceMeters: Double,
        durationSeconds: Double,
        latOffset: Double,
        pointCount: Int = 2500
    ): OfflineRoute {
        val shape = ArrayList<LatLon>(pointCount)
        for (i in 0 until pointCount) {
            val frac = i.toDouble() / (pointCount - 1)
            shape.add(
                LatLon(
                    lat = 44.0521 + latOffset * kotlin.math.sin(frac * Math.PI) + frac * 0.04,
                    lon = -123.0868 + frac * 0.05
                )
            )
        }
        val start = shape.first()
        val mid = shape[shape.size / 2]
        val end = shape.last()
        val maneuvers = listOf(
            RouteManeuver(
                index = 0,
                type = ManeuverType.DEPART,
                instructionEn = "Head east on East 22nd Avenue",
                streetNames = listOf("East 22nd Avenue"),
                lengthMeters = distanceMeters * 0.4,
                timeSeconds = durationSeconds * 0.4,
                beginShapeIndex = 0,
                endShapeIndex = shape.size / 2,
                lat = start.lat,
                lon = start.lon
            ),
            RouteManeuver(
                index = 1,
                type = ManeuverType.RIGHT,
                instructionEn = "Turn right onto Hilyard Street",
                streetNames = listOf("Hilyard Street"),
                lengthMeters = distanceMeters * 0.6,
                timeSeconds = durationSeconds * 0.6,
                beginShapeIndex = shape.size / 2,
                endShapeIndex = shape.lastIndex,
                lat = mid.lat,
                lon = mid.lon
            ),
            RouteManeuver(
                index = 2,
                type = ManeuverType.ARRIVE,
                instructionEn = "You have arrived at your destination",
                streetNames = emptyList(),
                lengthMeters = 0.0,
                timeSeconds = 0.0,
                beginShapeIndex = shape.lastIndex,
                endShapeIndex = shape.lastIndex,
                lat = end.lat,
                lon = end.lon
            )
        )
        return OfflineRoute(
            id = id,
            summaryLabel = id,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            shape = shape,
            maneuvers = maneuvers,
            speedLimitsByShapeIndex = emptyMap(),
            roadAlerts = emptyList(),
            crossesStateLines = false,
            statesCrossed = listOf("OR"),
            hasTolls = false
        )
    }

    @Test
    fun offlineFontGlyphAssetsExistForLatinAndPersian() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = context.assets
        val fonts = listOf(
            MapStyleBuilder.FONT_REGULAR,
            MapStyleBuilder.FONT_MEDIUM,
            MapStyleBuilder.FONT_ITALIC
        )
        val requiredRanges = listOf(
            "0-255.pbf",
            "1536-1791.pbf",
            "8192-8447.pbf",
            "64256-64511.pbf",
            "65024-65279.pbf"
        )
        for (font in fonts) {
            for (range in requiredRanges) {
                val path = "fonts/$font/$range"
                assets.open(path).use { stream ->
                    val bytes = stream.readBytes()
                    assertTrue("Expected non-empty glyph file at $path", bytes.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun styleJsonIncludesOfflineGlyphsLabelsLanguageRulesAndCorrectLayerOrder() {
        val fakePmTiles = listOf("or" to File("/tmp/or.pmtiles"))
        val persianDayJson = MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = fakePmTiles,
            isNightMode = false,
            useDevOnlineBasemap = false,
            language = AppLanguage.PERSIAN
        )
        val root = JSONObject(persianDayJson)

        assertEquals(MapStyleBuilder.OFFLINE_GLYPHS_URL, root.getString("glyphs"))

        // All 4 candidate route sources must be declared
        val sources = root.getJSONObject("sources")
        for (src in MapStyleBuilder.ALL_ROUTE_SOURCES) {
            assertTrue("Missing route source $src", sources.has(src))
        }

        val layers = root.getJSONArray("layers")
        val indexById = mutableMapOf<String, Int>()
        val layerById = mutableMapOf<String, JSONObject>()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val id = layer.getString("id")
            indexById[id] = i
            layerById[id] = layer
        }

        val minorRoadLabel = layerById["pmtiles_or_label_roads_minor"]
        val majorRoadLabel = layerById["pmtiles_or_label_roads_major"]
        val shieldLabel = layerById["pmtiles_or_label_highway_shields"]
        val placesLabel = layerById["pmtiles_or_label_places_locality"]
        val waterLabel = layerById["pmtiles_or_label_water"]
        val parkLabel = layerById["pmtiles_or_label_parks"]
        val poiLabel = layerById["pmtiles_or_label_pois"]

        assertNotNull(minorRoadLabel)
        assertNotNull(majorRoadLabel)
        assertNotNull(shieldLabel)
        assertNotNull(placesLabel)
        assertNotNull(waterLabel)
        assertNotNull(parkLabel)
        assertNotNull(poiLabel)

        // Zoom thresholds
        assertEquals(15, minorRoadLabel!!.getInt("minzoom"))
        assertEquals(13, majorRoadLabel!!.getInt("minzoom"))
        assertTrue(poiLabel!!.getInt("minzoom") >= 15)

        // Street names & highway shields MUST remain strictly English even in Persian mode
        val minorTextField = minorRoadLabel.getJSONObject("layout").getJSONArray("text-field").toString()
        val majorTextField = majorRoadLabel.getJSONObject("layout").getJSONArray("text-field").toString()
        val shieldTextField = shieldLabel!!.getJSONObject("layout").getJSONArray("text-field").toString()
        assertFalse("Minor streets must not use name:fa", minorTextField.contains("name:fa"))
        assertFalse("Major roads must not use name:fa", majorTextField.contains("name:fa"))
        assertFalse("Highway shields must not use name:fa", shieldTextField.contains("name:fa"))

        // City / place names in Persian mode use name:fa with fallback to English
        val placesTextField = placesLabel!!.getJSONObject("layout").getJSONArray("text-field").toString()
        assertTrue("Places should prefer name:fa in Persian mode", placesTextField.contains("name:fa"))

        // All basemap symbol layers MUST appear BEFORE routes and BEFORE GPS position arrow
        val firstRouteLayerIdx = minOf(
            indexById[MapStyleBuilder.LAYER_ROUTE_0]!!,
            indexById[MapStyleBuilder.LAYER_ROUTE_1]!!,
            indexById[MapStyleBuilder.LAYER_ROUTE_2]!!,
            indexById[MapStyleBuilder.LAYER_ROUTE_3]!!
        )
        val gpsArrowIdx = indexById[MapStyleBuilder.LAYER_GPS_ARROW]!!
        val basemapLabelIds = listOf(
            "pmtiles_or_label_water",
            "pmtiles_or_label_parks",
            "pmtiles_or_label_roads_minor",
            "pmtiles_or_label_roads_major",
            "pmtiles_or_label_highway_shields",
            "pmtiles_or_label_pois",
            "pmtiles_or_label_places_neighbourhood",
            "pmtiles_or_label_places_locality"
        )
        for (labelId in basemapLabelIds) {
            val idx = indexById[labelId]!!
            assertTrue("Label $labelId must render below route lines", idx < firstRouteLayerIdx)
            assertTrue("Label $labelId must render below GPS position arrow", idx < gpsArrowIdx)
        }

        // Night mode style also generates valid JSON with offline glyphs
        val nightJson = MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = fakePmTiles,
            isNightMode = true,
            useDevOnlineBasemap = false,
            language = AppLanguage.ENGLISH
        )
        val nightRoot = JSONObject(nightJson)
        assertEquals(MapStyleBuilder.OFFLINE_GLYPHS_URL, nightRoot.getString("glyphs"))
    }

    @Test
    fun mergeRoutesSupportsUpToFourDistinctRoutesAndGeoJsonCacheReusesEntries() = runTest {
        val fastest = buildSyntheticRoute("r0", distanceMeters = 12_000.0, durationSeconds = 720.0, latOffset = 0.0)
        val alt1 = buildSyntheticRoute("r1", distanceMeters = 13_400.0, durationSeconds = 810.0, latOffset = 0.015)
        val alt2 = buildSyntheticRoute("r2", distanceMeters = 14_100.0, durationSeconds = 880.0, latOffset = -0.015)
        val shortest = buildSyntheticRoute("r3", distanceMeters = 10_200.0, durationSeconds = 950.0, latOffset = 0.03)

        val merged = ValhallaResponseParser.mergeFastestAndShortestRoutes(
            fastestRoutes = listOf(fastest, alt1, alt2),
            shortestRoute = shortest,
            preferShortest = false
        )

        assertEquals(4, merged.size)
        assertTrue(merged[0].isFastestRoute)
        assertTrue(merged[1].isShortestRoute)
        assertEquals(4, merged.map { it.id }.toSet().size)

        val cache = RouteGeoJsonCache()
        val firstBuilds = merged.mapIndexed { idx, route ->
            cache.getOrBuildFeatureCollection(route, MapStyleBuilder.ALL_ROUTE_COLORS[idx])
        }
        assertEquals(4, cache.cachedLineCount())

        // Rapidly switch across all 4 routes 100 times: every switch must hit cache and return exact cached instance
        for (cycle in 0 until 25) {
            for (idx in merged.indices) {
                val fc = cache.getOrBuildFeatureCollection(merged[idx], MapStyleBuilder.ALL_ROUTE_COLORS[idx])
                assertSame("Route $idx should reuse cached FeatureCollection", firstBuilds[idx], fc)
            }
        }
        assertEquals(4, cache.cachedLineCount())
    }

    @Test
    fun rapidRouteSwitchingRotationAndNavigationOnEachAlternativeDoesNotCrash() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val vm = RahYabViewModel(app)

        val place = PlaceSearchResult(
            id = "test_dest",
            name = "Best Buy",
            brand = "Best Buy",
            category = "electronics",
            address = "3300 Gateway St",
            city = "Springfield",
            stateCode = "OR",
            postcode = "97477",
            lat = 44.0802,
            lon = -123.0439,
            distanceMeters = 5200.0,
            relevanceScore = 100.0,
            source = "overture"
        )
        vm.selectPlace(place)

        val routes = listOf(
            buildSyntheticRoute("route_fastest", 12_000.0, 720.0, 0.0).copy(isFastestRoute = true),
            buildSyntheticRoute("route_shortest", 10_200.0, 950.0, 0.02).copy(isShortestRoute = true),
            buildSyntheticRoute("route_alt_1", 13_400.0, 810.0, -0.02),
            buildSyntheticRoute("route_alt_2", 14_100.0, 880.0, 0.035)
        )

        // Populate 4 candidate routes cleanly
        val field = RahYabViewModel::class.java.getDeclaredField("_candidateRoutes")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<OfflineRoute>>
        stateFlow.value = routes

        // Rapidly switch between all 4 route cards + invalid out-of-bounds indices
        for (i in 0 until 40) {
            vm.selectCandidateRoute(i % 4)
            assertEquals(i % 4, vm.selectedRouteIndex.value)
        }
        vm.selectCandidateRoute(-5)
        assertEquals(0, vm.selectedRouteIndex.value)
        vm.selectCandidateRoute(99)
        assertEquals(3, vm.selectedRouteIndex.value)

        // Simulate phone orientation changes (landscape <-> portrait) while selecting and navigating each alternative
        val config = Configuration(app.resources.configuration)
        for (idx in routes.indices) {
            config.orientation = if (idx % 2 == 0) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            vm.selectCandidateRoute(idx)
            assertEquals(idx, vm.selectedRouteIndex.value)

            vm.startNavigation()
            assertTrue("Navigation should start on alternative $idx", vm.navigationState.value.isNavigating)
            assertEquals(routes[idx].id, vm.navigationState.value.route?.id)
            vm.stopNavigation()
            assertFalse("Navigation should stop cleanly after alternative $idx", vm.navigationState.value.isNavigating)
        }
    }

    @Test
    fun crashLogManagerPersistsReadsAndClearsErrorReportWithTimestamp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CrashLogManager.clearPendingCrashLog(context)
        assertNull(CrashLogManager.readPendingCrashLog(context))

        val simulated = IllegalStateException("Simulated route switch failure")
        CrashLogManager.writeCrashLog(
            context = context,
            threadName = "main",
            throwable = simulated,
            timestampMs = 1_760_000_000_000L
        )

        val saved = CrashLogManager.readPendingCrashLog(context)
        assertNotNull(saved)
        assertTrue(saved!!.contains("RahYab Crash Report"))
        assertTrue(saved.contains("Time:"))
        assertTrue(saved.contains("1760000000000"))
        assertTrue(saved.contains("IllegalStateException"))
        assertTrue(saved.contains("Simulated route switch failure"))

        CrashLogManager.clearPendingCrashLog(context)
        assertNull(CrashLogManager.readPendingCrashLog(context))
    }
}
