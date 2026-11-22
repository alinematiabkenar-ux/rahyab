package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.map.MapStyleBuilder
import com.example.map.RouteGeoJsonCache
import com.example.navigation.CompassTracker
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import com.example.ui.CameraTargetCommand
import com.example.ui.RahYabViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val COMPASS_MAP_MIN_INTERVAL_MS = 100L // At most 10 updates per second
private const val COMPASS_MAP_MIN_DELTA_DEG = 2.0f   // Only update when heading changes > 2 degrees

/**
 * Lifecycle-safe Jetpack Compose host for MapLibre Native Android `MapView`.
 *
 * Features:
 * - Local per-state & regional PMTiles vector maps (`pmtiles://file://...`) with offline PBF glyphs
 * - Split independent LaunchedEffects for routes, destination pin, user reports, and GPS/compass
 * - Off-main-thread GeoJSON route conversion & caching (up to 4 distinct candidate routes)
 * - Throttled compass updates (<= 10 Hz, > 2° delta) so map rendering stays silky smooth
 * - Long-press anywhere on the map to drop a pin and request Directions
 * - Snapped road location rendering during active navigation
 */
@Composable
fun MapLibreMapContainer(
    installedPmtiles: List<Pair<String, File>>,
    isNightMode: Boolean,
    language: AppLanguage,
    useDevOnlineBasemap: Boolean,
    packsChangedTick: Long,
    gpsFix: GpsFix?,
    snappedNavLocation: LatLon?,
    userHeadingDegrees: Float,
    isHeadingUpMode: Boolean,
    isCameraFollowing: Boolean,
    candidateRoutes: List<OfflineRoute>,
    selectedRouteIndex: Int,
    activeNavRoute: OfflineRoute?,
    selectedPlace: PlaceSearchResult?,
    userReports: List<UserReportEntity>,
    cameras: List<RoadHazardPoint> = emptyList(),
    trafficLights: List<RoadHazardPoint> = emptyList(),
    cameraCommand: CameraTargetCommand?,
    onMapLongClick: (Double, Double) -> Unit,
    onRouteTapped: (Int) -> Unit,
    onUserMovedMap: () -> Unit,
    onMapBearingChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentCandidates by rememberUpdatedState(candidateRoutes)
    val currentActiveRoute by rememberUpdatedState(activeNavRoute)
    val currentOnMapLongClick by rememberUpdatedState(onMapLongClick)
    val currentOnRouteTapped by rememberUpdatedState(onRouteTapped)
    val currentOnUserMovedMap by rememberUpdatedState(onUserMovedMap)
    val currentOnMapBearingChanged by rememberUpdatedState(onMapBearingChanged)

    val routeGeoJsonCache = remember { RouteGeoJsonCache(maxEntries = 24) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var lastAppliedHeadingBearing by remember { mutableFloatStateOf(Float.NaN) }

    // Throttled heading state for the map (at most 10 Hz and only when heading changes > 2 deg)
    var throttledHeadingDegrees by remember { mutableFloatStateOf(CompassTracker.normalizeAngle(userHeadingDegrees)) }
    var lastHeadingUpdateMs by remember { mutableLongStateOf(0L) }
    var hasAppliedInitialHeading by remember { mutableStateOf(false) }

    LaunchedEffect(userHeadingDegrees) {
        val normalized = CompassTracker.normalizeAngle(userHeadingDegrees)
        val nowMs = SystemClock.elapsedRealtime()
        val delta = abs(CompassTracker.shortestAngleDelta(throttledHeadingDegrees, normalized))
        if (!hasAppliedInitialHeading ||
            (nowMs - lastHeadingUpdateMs >= COMPASS_MAP_MIN_INTERVAL_MS && delta > COMPASS_MAP_MIN_DELTA_DEG)
        ) {
            hasAppliedInitialHeading = true
            lastHeadingUpdateMs = nowMs
            throttledHeadingDegrees = normalized
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val styleJson = remember(installedPmtiles, isNightMode, useDevOnlineBasemap, packsChangedTick, language) {
        MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = installedPmtiles,
            isNightMode = isNightMode,
            useDevOnlineBasemap = useDevOnlineBasemap,
            language = language
        )
    }

    LaunchedEffect(mapLibreMap, styleJson) {
        val map = mapLibreMap ?: return@LaunchedEffect
        loadedStyle = null
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            runCatching {
                style.addImage(MapStyleBuilder.IMAGE_USER_ARROW, createUserArrowBitmap())
                style.addImage(MapStyleBuilder.IMAGE_HIGHWAY_SHIELD, createHighwayShieldBitmap(isNightMode))
                style.addImage(MapStyleBuilder.IMAGE_SPEED_CAMERA, createSpeedCameraBitmap())
                style.addImage(MapStyleBuilder.IMAGE_RED_LIGHT_CAMERA, createRedLightCameraBitmap())
                createVectorDrawableBitmap(context, R.drawable.ic_traffic_light_map, 36, 54)?.let {
                    style.addImage(MapStyleBuilder.IMAGE_TRAFFIC_LIGHT, it)
                }
            }
            loadedStyle = style
        }
    }

    // 1. ROUTE LINES EFFECT — runs ONLY when route candidates, selected index, or active nav route changes.
    // Converts route shapes off the main thread and caches GeoJSON per route so switching alternatives is instant.
    LaunchedEffect(loadedStyle, candidateRoutes, selectedRouteIndex, activeNavRoute) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val emptyFc = RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION
        val routeSources = MapStyleBuilder.ALL_ROUTE_SOURCES
        val routeColors = MapStyleBuilder.ALL_ROUTE_COLORS

        if (activeNavRoute != null && activeNavRoute.shape.size >= 2) {
            val primaryFc = routeGeoJsonCache.getOrBuildFeatureCollection(
                route = activeNavRoute,
                colorHex = MapStyleBuilder.ROUTE_COLOR_0
            )
            if (!style.isFullyLoaded) return@LaunchedEffect
            runCatching {
                routeSources.forEach { src -> style.getSourceAs<GeoJsonSource>(src)?.setGeoJson(emptyFc) }
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(primaryFc)
            }
        } else if (candidateRoutes.isNotEmpty()) {
            val safeSelectedIdx = selectedRouteIndex.coerceIn(0, candidateRoutes.lastIndex)

            // Pre-compute / fetch cached GeoJSON off the main thread for all up to 4 routes
            val unselectedFcs = ArrayList<FeatureCollection>(routeSources.size)
            for (idx in routeSources.indices) {
                val route = candidateRoutes.getOrNull(idx)
                val fc = if (route != null && idx != safeSelectedIdx && route.shape.size >= 2) {
                    routeGeoJsonCache.getOrBuildFeatureCollection(route = route, colorHex = null)
                } else {
                    emptyFc
                }
                unselectedFcs.add(fc)
            }

            val selectedRoute = candidateRoutes.getOrNull(safeSelectedIdx)
            val primaryFc = if (selectedRoute != null && selectedRoute.shape.size >= 2) {
                val selectedColor = routeColors.getOrElse(safeSelectedIdx) { MapStyleBuilder.ROUTE_COLOR_0 }
                routeGeoJsonCache.getOrBuildFeatureCollection(route = selectedRoute, colorHex = selectedColor)
            } else {
                emptyFc
            }

            if (!style.isFullyLoaded) return@LaunchedEffect
            runCatching {
                routeSources.forEachIndexed { idx, srcId ->
                    style.getSourceAs<GeoJsonSource>(srcId)?.setGeoJson(unselectedFcs[idx])
                }
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(primaryFc)
            }
        } else {
            runCatching {
                routeSources.forEach { src -> style.getSourceAs<GeoJsonSource>(src)?.setGeoJson(emptyFc) }
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(emptyFc)
            }
        }
    }

    // 2. DESTINATION PIN EFFECT — runs ONLY when selectedPlace changes
    LaunchedEffect(loadedStyle, selectedPlace) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val destFc = if (selectedPlace != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(selectedPlace.lon, selectedPlace.lat))
            )
        } else {
            RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_DESTINATION)?.setGeoJson(destFc)
        }
    }

    // 3. USER REPORTS EFFECT — runs ONLY when userReports changes
    LaunchedEffect(loadedStyle, userReports) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val reportsFc = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                userReports.map { r ->
                    val rType = runCatching { ReportType.valueOf(r.type) }.getOrNull()
                    val colorHex = when (rType) {
                        ReportType.POLICE, ReportType.SPEED_TRAP -> "#2563EB"
                        ReportType.CRASH, ReportType.ROAD_CLOSURE -> "#E53935"
                        ReportType.CONSTRUCTION -> "#F97316"
                        else -> "#F59E0B"
                    }
                    Feature.fromGeometry(Point.fromLngLat(r.lon, r.lat)).apply {
                        addStringProperty("color", colorHex)
                        addStringProperty("type", r.type)
                    }
                }
            )
        }
        if (!style.isFullyLoaded) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_USER_REPORTS)?.setGeoJson(reportsFc)
        }
    }

    // 3B. SPEED CAMERAS & RED LIGHT CAMERAS EFFECT (rendered from zoom 13)
    LaunchedEffect(loadedStyle, cameras) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val camerasFc = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                cameras.map { cam ->
                    val iconName = if (cam.type.equals("red_light_camera", ignoreCase = true)) {
                        MapStyleBuilder.IMAGE_RED_LIGHT_CAMERA
                    } else {
                        MapStyleBuilder.IMAGE_SPEED_CAMERA
                    }
                    Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply {
                        addStringProperty("icon", iconName)
                        addStringProperty("type", cam.type)
                    }
                }
            )
        }
        if (!style.isFullyLoaded) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_CAMERAS)?.setGeoJson(camerasFc)
        }
    }

    // 3C. TRAFFIC LIGHTS EFFECT (offline vector icon from zoom 15, merged within 15m)
    LaunchedEffect(loadedStyle, trafficLights) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val lightsFc = withContext(Dispatchers.Default) {
            if (trafficLights.isEmpty()) {
                RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION
            } else {
                FeatureCollection.fromFeatures(
                    trafficLights.map { sig ->
                        Feature.fromGeometry(Point.fromLngLat(sig.lon, sig.lat))
                    }
                )
            }
        }
        if (!style.isFullyLoaded) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_TRAFFIC_LIGHTS)?.setGeoJson(lightsFc)
        }
    }

    // 4. POSITION ARROW & ACCURACY CIRCLE EFFECT — runs ONLY when position or throttled heading changes
    val displayLoc = snappedNavLocation ?: gpsFix?.let { LatLon(it.lat, it.lon) }
    val accuracyMeters = gpsFix?.accuracyMeters?.toDouble() ?: 0.0

    LaunchedEffect(loadedStyle, displayLoc, accuracyMeters) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val accuracyFc = if (displayLoc != null && accuracyMeters > 0.0) {
            val circle = buildAccuracyCirclePolygon(
                centerLat = displayLoc.lat,
                centerLon = displayLoc.lon,
                radiusMeters = accuracyMeters.coerceIn(5.0, 1000.0)
            )
            FeatureCollection.fromFeature(Feature.fromGeometry(circle))
        } else {
            RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_GPS_ACCURACY)?.setGeoJson(accuracyFc)
        }
    }

    LaunchedEffect(loadedStyle, displayLoc, throttledHeadingDegrees) {
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val puckFc = if (displayLoc != null) {
            val feat = Feature.fromGeometry(Point.fromLngLat(displayLoc.lon, displayLoc.lat)).apply {
                addNumberProperty("bearing", throttledHeadingDegrees)
            }
            FeatureCollection.fromFeature(feat)
        } else {
            RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_GPS_PUCK)?.setGeoJson(puckFc)
        }
    }

    LaunchedEffect(mapLibreMap, cameraCommand) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val cmd = cameraCommand ?: return@LaunchedEffect
        lastAppliedHeadingBearing = cmd.bearing.toFloat()
        val topPadding = if (cmd.anchorLowerThird && mapView.height > 0) {
            (mapView.height * 0.34)
        } else {
            0.0
        }
        val pos = CameraPosition.Builder()
            .target(LatLng(cmd.lat, cmd.lon))
            .zoom(cmd.zoom)
            .tilt(cmd.tilt)
            .bearing(cmd.bearing)
            .padding(doubleArrayOf(0.0, topPadding, 0.0, 0.0))
            .build()
        runCatching {
            if (cmd.animationDurationMs <= 0) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
            } else {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), cmd.animationDurationMs)
            }
        }
    }

    // Smoothly rotate map camera bearing in heading-up mode ONLY when stationary (< 3 mph),
    // so driving GPS camera animations (~1s) are never interrupted by compass ticks.
    LaunchedEffect(mapLibreMap, throttledHeadingDegrees, isHeadingUpMode, isCameraFollowing, activeNavRoute, selectedPlace, gpsFix) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val speedMps = gpsFix?.speedMps ?: 0f
        if (!isHeadingUpMode || !isCameraFollowing || activeNavRoute != null || selectedPlace != null || speedMps > 1.34f) {
            return@LaunchedEffect
        }
        val currentBearing = map.cameraPosition.bearing.toFloat()
        val diff = abs(CompassTracker.shortestAngleDelta(currentBearing, throttledHeadingDegrees))
        if (lastAppliedHeadingBearing.isNaN() || diff > 3.0f) {
            lastAppliedHeadingBearing = throttledHeadingDegrees
            val topPadding = if (mapView.height > 0) (mapView.height * 0.34) else 0.0
            val updatedPos = CameraPosition.Builder(map.cameraPosition)
                .bearing(throttledHeadingDegrees.toDouble())
                .padding(doubleArrayOf(0.0, topPadding, 0.0, 0.0))
                .build()
            runCatching {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(updatedPos), 280)
            }
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    // Disable built-in compass so our custom Compose Compass Button handles North-Up / Heading-Up
                    map.uiSettings.isCompassEnabled = false
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(RahYabViewModel.EUGENE_OR_LAT, RahYabViewModel.EUGENE_OR_LON))
                        .zoom(11.5)
                        .build()
                    map.addOnCameraMoveListener {
                        currentOnMapBearingChanged(map.cameraPosition.bearing.toFloat())
                    }

                    // Long press anywhere on the map to drop a pin -> Directions
                    map.addOnMapLongClickListener { latLng ->
                        if (currentActiveRoute == null) {
                            currentOnMapLongClick(latLng.latitude, latLng.longitude)
                            true
                        } else {
                            false
                        }
                    }

                    // Tap a candidate route line directly on the map to select it
                    map.addOnMapClickListener { latLng ->
                        val routes = currentCandidates
                        if (currentActiveRoute == null && routes.size > 1) {
                            var bestRouteIdx = -1
                            var bestDistMeters = 900.0
                            routes.take(MapStyleBuilder.ALL_ROUTE_SOURCES.size).forEachIndexed { idx, route ->
                                if (route.shape.isEmpty()) return@forEachIndexed
                                val step = (route.shape.size / 120).coerceAtLeast(1)
                                for (i in route.shape.indices step step) {
                                    val pt = route.shape[i]
                                    val d = OfflinePlaceSearchEngine.haversineMeters(
                                        latLng.latitude, latLng.longitude, pt.lat, pt.lon
                                    )
                                    if (d < bestDistMeters) {
                                        bestDistMeters = d
                                        bestRouteIdx = idx
                                    }
                                }
                            }
                            if (bestRouteIdx in routes.indices) {
                                currentOnRouteTapped(bestRouteIdx)
                                return@addOnMapClickListener true
                            }
                        }
                        false
                    }

                    // Detect ANY user finger pan, pinch-zoom, rotate, or tilt in any mode -> pause camera follow
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            currentOnUserMovedMap()
                        }
                    }
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) {
                            currentOnUserMovedMap()
                        }
                        override fun onMove(detector: MoveGestureDetector) {}
                        override fun onMoveEnd(detector: MoveGestureDetector) {}
                    })

                    mapLibreMap = map
                }
            }
        },
        modifier = modifier
    )
}

private fun buildAccuracyCirclePolygon(
    centerLat: Double,
    centerLon: Double,
    radiusMeters: Double,
    steps: Int = 48
): Polygon {
    val earthRadiusMeters = 6_371_000.0
    val latRad = Math.toRadians(centerLat)
    val dLatDeg = Math.toDegrees(radiusMeters / earthRadiusMeters)
    val cosLat = cos(latRad).coerceAtLeast(0.01)
    val dLonDeg = dLatDeg / cosLat

    val ring = ArrayList<Point>(steps + 1)
    for (i in 0..steps) {
        val angle = 2.0 * PI * i / steps
        val ptLat = (centerLat + dLatDeg * sin(angle)).coerceIn(-85.0, 85.0)
        val ptLon = centerLon + dLonDeg * cos(angle)
        ring.add(Point.fromLngLat(ptLon, ptLat))
    }
    return Polygon.fromLngLats(listOf(ring))
}

private fun createUserArrowBitmap(): Bitmap {
    val size = 84
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    // Crisp upward-pointing navigation chevron (north = 0 deg) with white outline & cyan-blue fill
    val path = Path().apply {
        moveTo(cx, 6f)
        lineTo(cx + 22f, size - 16f)
        lineTo(cx, cy + 10f)
        lineTo(cx - 22f, size - 16f)
        close()
    }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#0284C7")
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        color = AndroidColor.WHITE
    }

    canvas.drawPath(path, fillPaint)
    canvas.drawPath(path, strokePaint)
    return bmp
}

private fun createHighwayShieldBitmap(isNightMode: Boolean): Bitmap {
    val width = 64
    val height = 36
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val rect = RectF(2f, 2f, width - 2f, height - 2f)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isNightMode) AndroidColor.parseColor("#1D4ED8") else AndroidColor.parseColor("#1E40AF")
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = AndroidColor.WHITE
    }

    canvas.drawRoundRect(rect, 9f, 9f, fillPaint)
    canvas.drawRoundRect(rect, 9f, 9f, borderPaint)
    return bmp
}

private fun createSpeedCameraBitmap(): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    // Red circular badge with crisp white border
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#DC2626")
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = AndroidColor.WHITE
    }
    canvas.drawCircle(cx, cy, 26f, bgPaint)
    canvas.drawCircle(cx, cy, 26f, borderPaint)

    // Camera silhouette in white
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.WHITE
    }
    canvas.drawRoundRect(RectF(17f, 23f, 47f, 44f), 5f, 5f, whitePaint)
    canvas.drawRoundRect(RectF(24f, 18f, 36f, 24f), 3f, 3f, whitePaint)

    // Camera lens ring
    val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#DC2626")
    }
    canvas.drawCircle(cx, 33.5f, 7.5f, lensPaint)
    canvas.drawCircle(cx, 33.5f, 3.8f, whitePaint)
    return bmp
}

private fun createRedLightCameraBitmap(): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    // Dark slate circular badge with amber-red border
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#1E293B")
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = AndroidColor.parseColor("#EF4444")
    }
    canvas.drawCircle(cx, cy, 26f, bgPaint)
    canvas.drawCircle(cx, cy, 26f, borderPaint)

    // Traffic signal housing
    val housingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#0F172A")
    }
    canvas.drawRoundRect(RectF(23f, 12f, 41f, 52f), 5f, 5f, housingPaint)

    val redLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#EF4444") }
    val amberLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#F59E0B") }
    val greenLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#22C55E") }
    canvas.drawCircle(cx, 20f, 5.2f, redLight)
    canvas.drawCircle(cx, 32f, 4.6f, amberLight)
    canvas.drawCircle(cx, 44f, 4.6f, greenLight)
    return bmp
}

private fun createVectorDrawableBitmap(
    context: android.content.Context,
    drawableResId: Int,
    widthPx: Int,
    heightPx: Int
): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, widthPx, heightPx)
    drawable.draw(canvas)
    return bmp
}
