package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.map.MapStyleBuilder
import com.example.navigation.CompassTracker
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import com.example.ui.CameraTargetCommand
import com.example.ui.RahYabViewModel
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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lifecycle-safe Jetpack Compose host for MapLibre Native Android `MapView`.
 *
 * Features:
 * - Local per-state & regional PMTiles vector maps (`pmtiles://file://...`)
 * - Up to 3 candidate routes in distinct colors, tappable directly on the map
 * - Long-press anywhere on the map to drop a pin and request Directions
 * - Snapped road location rendering during active navigation
 * - User map pan detection to pause auto-follow until user taps Recenter
 * - Color-coded Waze-style user report markers
 */
@Composable
fun MapLibreMapContainer(
    installedPmtiles: List<Pair<String, File>>,
    isNightMode: Boolean,
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

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var lastAppliedHeadingBearing by remember { mutableFloatStateOf(Float.NaN) }

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

    val styleJson = remember(installedPmtiles, isNightMode, useDevOnlineBasemap, packsChangedTick) {
        MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = installedPmtiles,
            isNightMode = isNightMode,
            useDevOnlineBasemap = useDevOnlineBasemap
        )
    }

    LaunchedEffect(mapLibreMap, styleJson) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            runCatching {
                style.addImage(MapStyleBuilder.IMAGE_USER_ARROW, createUserArrowBitmap())
            }
            loadedStyle = style
        }
    }

    LaunchedEffect(
        loadedStyle,
        candidateRoutes,
        selectedRouteIndex,
        activeNavRoute,
        selectedPlace,
        gpsFix,
        snappedNavLocation,
        userHeadingDegrees,
        userReports
    ) {
        val style = loadedStyle ?: return@LaunchedEffect

        val routeColors = listOf(
            MapStyleBuilder.ROUTE_COLOR_0,
            MapStyleBuilder.ROUTE_COLOR_1,
            MapStyleBuilder.ROUTE_COLOR_2
        )
        val routeSources = listOf(
            MapStyleBuilder.SOURCE_ROUTE_0,
            MapStyleBuilder.SOURCE_ROUTE_1,
            MapStyleBuilder.SOURCE_ROUTE_2
        )

        val emptyFc = FeatureCollection.fromFeatures(emptyArray())

        if (activeNavRoute != null && activeNavRoute.shape.size >= 2) {
            // Active navigation: show only active route prominently
            routeSources.forEach { src -> style.getSourceAs<GeoJsonSource>(src)?.setGeoJson(emptyFc) }
            val line = LineString.fromLngLats(activeNavRoute.shape.map { Point.fromLngLat(it.lon, it.lat) })
            val feat = Feature.fromGeometry(line).apply {
                addStringProperty("color", MapStyleBuilder.ROUTE_COLOR_0)
            }
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
                ?.setGeoJson(FeatureCollection.fromFeature(feat))
        } else if (candidateRoutes.isNotEmpty()) {
            // Route preview: draw up to 3 routes in 3 distinct colors, selected route thicker on top
            routeSources.forEachIndexed { idx, srcId ->
                val route = candidateRoutes.getOrNull(idx)
                if (route != null && idx != selectedRouteIndex && route.shape.size >= 2) {
                    val line = LineString.fromLngLats(route.shape.map { Point.fromLngLat(it.lon, it.lat) })
                    style.getSourceAs<GeoJsonSource>(srcId)
                        ?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(line)))
                } else {
                    style.getSourceAs<GeoJsonSource>(srcId)?.setGeoJson(emptyFc)
                }
            }
            val selected = candidateRoutes.getOrNull(selectedRouteIndex)
            if (selected != null && selected.shape.size >= 2) {
                val line = LineString.fromLngLats(selected.shape.map { Point.fromLngLat(it.lon, it.lat) })
                val feat = Feature.fromGeometry(line).apply {
                    addStringProperty("color", routeColors.getOrElse(selectedRouteIndex) { MapStyleBuilder.ROUTE_COLOR_0 })
                }
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
                    ?.setGeoJson(FeatureCollection.fromFeature(feat))
            } else {
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(emptyFc)
            }
        } else {
            routeSources.forEach { src -> style.getSourceAs<GeoJsonSource>(src)?.setGeoJson(emptyFc) }
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(emptyFc)
        }

        // Destination pin
        style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_DESTINATION)?.setGeoJson(
            if (selectedPlace != null) {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(selectedPlace.lon, selectedPlace.lat))
                )
            } else {
                emptyFc
            }
        )

        // Color-coded Waze-style user report markers
        style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_USER_REPORTS)?.setGeoJson(
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
        )

        // Snapped navigation marker (when actively navigating within 30 m) or live GPS puck
        val displayLoc = snappedNavLocation ?: gpsFix?.let { LatLon(it.lat, it.lon) }
        val accuracyMeters = gpsFix?.accuracyMeters?.toDouble() ?: 0.0
        style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_GPS_ACCURACY)?.setGeoJson(
            if (displayLoc != null && accuracyMeters > 0.0) {
                val circle = buildAccuracyCirclePolygon(
                    centerLat = displayLoc.lat,
                    centerLon = displayLoc.lon,
                    radiusMeters = accuracyMeters.coerceIn(5.0, 1000.0)
                )
                FeatureCollection.fromFeature(Feature.fromGeometry(circle))
            } else {
                emptyFc
            }
        )
        style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_GPS_PUCK)?.setGeoJson(
            if (displayLoc != null) {
                val feat = Feature.fromGeometry(Point.fromLngLat(displayLoc.lon, displayLoc.lat)).apply {
                    addNumberProperty("bearing", CompassTracker.normalizeAngle(userHeadingDegrees))
                }
                FeatureCollection.fromFeature(feat)
            } else {
                emptyFc
            }
        )
    }

    LaunchedEffect(mapLibreMap, cameraCommand) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val cmd = cameraCommand ?: return@LaunchedEffect
        lastAppliedHeadingBearing = cmd.bearing.toFloat()
        val pos = CameraPosition.Builder()
            .target(LatLng(cmd.lat, cmd.lon))
            .zoom(cmd.zoom)
            .tilt(cmd.tilt)
            .bearing(cmd.bearing)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500)
    }

    // Smoothly rotate map camera bearing in heading-up mode when browsing or stationary
    LaunchedEffect(mapLibreMap, userHeadingDegrees, isHeadingUpMode, isCameraFollowing, activeNavRoute, selectedPlace) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isHeadingUpMode || !isCameraFollowing || activeNavRoute != null || selectedPlace != null) return@LaunchedEffect
        val normalizedHeading = CompassTracker.normalizeAngle(userHeadingDegrees)
        val currentBearing = map.cameraPosition.bearing.toFloat()
        val diff = abs(CompassTracker.shortestAngleDelta(currentBearing, normalizedHeading))
        if (lastAppliedHeadingBearing.isNaN() || diff >= 3.0f) {
            lastAppliedHeadingBearing = normalizedHeading
            val updatedPos = CameraPosition.Builder(map.cameraPosition)
                .bearing(normalizedHeading.toDouble())
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(updatedPos), 250)
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
                            routes.forEachIndexed { idx, route ->
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
                            if (bestRouteIdx >= 0) {
                                currentOnRouteTapped(bestRouteIdx)
                                return@addOnMapClickListener true
                            }
                        }
                        false
                    }

                    // Detect manual user pan to pause camera follow until Recenter is tapped
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
