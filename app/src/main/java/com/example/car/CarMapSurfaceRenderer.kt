package com.example.car

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.example.RahYabApplication
import com.example.data.preferences.UserSettings
import com.example.i18n.stringsFor
import com.example.map.MapStyleBuilder
import com.example.navigation.CameraFollowController
import com.example.navigation.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.ln

/**
 * AA3: Renders RahYab's offline MapLibre PMTiles vector map directly onto the Android Auto
 * navigation surface via a VirtualDisplay + Presentation hosting a MapLibre [MapView].
 *
 * Shares the exact same [MapStyleBuilder], route colors, layers, and [CameraFollowController]
 * as the phone app, respects `carContext.isDarkMode`, `visibleArea`/`stableArea`, handles
 * pan/fling/scale gestures with a 10-second auto-recenter timer, and cleanly tears down on destroy.
 */
class CarMapSurfaceRenderer(
    private val carContext: CarContext,
    private val app: RahYabApplication
) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val followController = CameraFollowController()
    private var scope: CoroutineScope? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var loadedStyle: Style? = null
    private var speedOverlay: CarSpeedOverlayView? = null

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var visibleArea: Rect = Rect()
    private var stableArea: Rect = Rect()
    private var isFollowing: Boolean = true
    private var isDarkMode: Boolean = carContext.isDarkMode
    private var currentSettings: UserSettings = UserSettings()
    private var lastLoadedStyleJson: String? = null

    private val autoRecenterRunnable = Runnable {
        isFollowing = true
        followCurrentLocation(forceImmediate = true)
    }

    fun attachToHost() {
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        }
    }

    fun onDarkModeChanged(darkMode: Boolean) {
        if (isDarkMode != darkMode) {
            isDarkMode = darkMode
            mainHandler.post {
                reloadMapStyleIfNeeded(force = true)
                refreshSpeedOverlay()
            }
        }
    }

    fun zoomIn() {
        mainHandler.post {
            pauseFollowWithTimer()
            val map = mapLibreMap ?: return@post
            val nextZoom = (map.cameraPosition.zoom + 1.0).coerceAtMost(19.0)
            map.animateCamera(CameraUpdateFactory.zoomTo(nextZoom), 250)
        }
    }

    fun zoomOut() {
        mainHandler.post {
            pauseFollowWithTimer()
            val map = mapLibreMap ?: return@post
            val nextZoom = (map.cameraPosition.zoom - 1.0).coerceAtLeast(4.0)
            map.animateCamera(CameraUpdateFactory.zoomTo(nextZoom), 250)
        }
    }

    fun recenter() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoRecenterRunnable)
            isFollowing = true
            followCurrentLocation(forceImmediate = true)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        val dpi = surfaceContainer.dpi.coerceAtLeast(120)
        if (w <= 0 || h <= 0) return

        mainHandler.post {
            tearDownSurface()
            app.ensureMapLibreInitialized()
            surfaceWidth = w
            surfaceHeight = h
            visibleArea = Rect(0, 0, w, h)
            stableArea = Rect(0, 0, w, h)

            val dm = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(
                "RahYabCarMap",
                w,
                h,
                dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd

            val pres = Presentation(carContext, vd.display)
            val root = FrameLayout(carContext)
            val mv = MapView(pres.context, MapLibreMapOptions.createFromAttributes(pres.context).textureMode(true)).apply {
                onCreate(null)
                onStart()
                onResume()
            }
            val overlay = CarSpeedOverlayView(carContext)
            root.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            pres.setContentView(root)
            pres.show()

            presentation = pres
            mapView = mv
            speedOverlay = overlay

            mv.getMapAsync { map ->
                mapLibreMap = map
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = false
                val ref = app.navigationHub.getCurrentReferenceLocation()
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(ref.lat, ref.lon))
                    .zoom(14.5)
                    .build()
                reloadMapStyleIfNeeded(force = true)
                startObservers()
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        mainHandler.post {
            this.visibleArea = Rect(visibleArea)
            speedOverlay?.updateVisibleArea(this.visibleArea)
            if (isFollowing) followCurrentLocation(forceImmediate = true)
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        mainHandler.post {
            this.stableArea = Rect(stableArea)
            if (isFollowing) followCurrentLocation(forceImmediate = true)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            pauseFollowWithTimer()
            mapLibreMap?.scrollBy(-distanceX, -distanceY, 0L)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            pauseFollowWithTimer()
            val dx = (velocityX * 0.12f).coerceIn(-260f, 260f)
            val dy = (velocityY * 0.12f).coerceIn(-260f, 260f)
            mapLibreMap?.scrollBy(dx, dy, 260L)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f || !scaleFactor.isFinite()) return
        mainHandler.post {
            pauseFollowWithTimer()
            val map = mapLibreMap ?: return@post
            val zoomDelta = ln(scaleFactor.toDouble()) / ln(2.0)
            val nextZoom = (map.cameraPosition.zoom + zoomDelta).coerceIn(4.0, 19.0)
            map.moveCamera(CameraUpdateFactory.zoomTo(nextZoom))
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mainHandler.post { tearDownSurface() }
    }

    fun release() {
        mainHandler.post { tearDownSurface() }
    }

    private fun pauseFollowWithTimer() {
        isFollowing = false
        followController.recordUserMapGesture(SystemClock.elapsedRealtime())
        mainHandler.removeCallbacks(autoRecenterRunnable)
        mainHandler.postDelayed(autoRecenterRunnable, CameraFollowController.AUTO_RECENTER_DELAY_MS)
    }

    private fun startObservers() {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val hub = app.navigationHub

        s.launch {
            app.preferencesRepository.settingsFlow.collectLatest { settings ->
                currentSettings = settings; reloadMapStyleIfNeeded(force = false); updateExtraLayers(); refreshSpeedOverlay()
            }
        }
        s.launch {
            app.packManager.packsChangedTick.collectLatest {
                reloadMapStyleIfNeeded(force = true)
                refreshSpeedOverlay()
            }
        }
        s.launch {
            combine(
                hub.navigationState,
                app.locationTracker.currentFix,
                hub.userHeadingDegrees
            ) { nav, fix, heading -> Triple(nav, fix, heading) }.collectLatest { (nav, fix, heading) ->
                updatePositionAndRoutes()
                refreshSpeedOverlay()
                if (isFollowing) {
                    val loc = nav.snappedLocation ?: fix?.let { LatLon(it.lat, it.lon) }
                    if (loc != null) {
                        val speed = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
                        val bearing = if (nav.isNavigating) nav.snappedBearing else heading
                        applyCameraFollow(loc, speed, bearing, nav.isNavigating, nav.isWalkingMode, force = false)
                    }
                }
            }
        }
        s.launch {
            combine(hub.candidateRoutes, hub.selectedRouteIndex, hub.selectedPlace) { r, i, p -> Triple(r, i, p) }
                .collectLatest { updatePositionAndRoutes() }
        }
        s.launch {
            combine(hub.visibleCameras, hub.visibleTrafficLights, hub.visibleSpeedBumps, hub.visibleStopSigns) { _, _, _, _ -> Unit }
                .collectLatest { updateExtraLayers() }
        }
    }

    private fun reloadMapStyleIfNeeded(force: Boolean) {
        val map = mapLibreMap ?: return
        val pmtiles = app.packManager.getInstalledPmtilesFiles()
        val styleJson = MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = pmtiles,
            isNightMode = isDarkMode,
            useDevOnlineBasemap = currentSettings.devOnlineOregonMode,
            language = currentSettings.language
        )
        if (!force && styleJson == lastLoadedStyleJson) return
        lastLoadedStyleJson = styleJson
        loadedStyle = null
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            CarMapStyleHelper.registerAllCarStyleImages(carContext, style, isDarkMode)
            loadedStyle = style
            updatePositionAndRoutes()
            updateExtraLayers()
            followCurrentLocation(forceImmediate = true)
        }
    }

    private fun updatePositionAndRoutes() = CarMapStyleHelper.updatePositionAndRoutes(app, loadedStyle)

    private fun updateExtraLayers() = CarMapStyleHelper.updateExtraLayers(
        app, loadedStyle, currentSettings.showTrafficLights, currentSettings.showStopSignsOnMap
    )

    private fun followCurrentLocation(forceImmediate: Boolean) {
        val hub = app.navigationHub
        val nav = hub.navigationState.value
        val fix = app.locationTracker.currentFix.value
        val loc = nav.snappedLocation ?: fix?.let { LatLon(it.lat, it.lon) } ?: hub.getCurrentReferenceLocation()
        val speed = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
        val bearing = if (nav.isNavigating) nav.snappedBearing else hub.userHeadingDegrees.value
        applyCameraFollow(loc, speed, bearing, nav.isNavigating, nav.isWalkingMode, forceImmediate)
    }

    private fun applyCameraFollow(
        loc: LatLon,
        speedMps: Float,
        bearing: Float,
        isNavigating: Boolean,
        isWalking: Boolean,
        force: Boolean
    ) {
        val map = mapLibreMap ?: return
        val update = followController.computeFollowCameraUpdate(
            lat = loc.lat,
            lon = loc.lon,
            speedMps = speedMps,
            headingDegrees = bearing,
            isHeadingUpMode = true,
            is3DView = currentSettings.is3DView,
            isNavigating = isNavigating,
            forceImmediate = force,
            isWalkingMode = isWalking
        ) ?: return

        val area = if (stableArea.width() > 0 && stableArea.height() > 0) stableArea else visibleArea
        val leftPad = area.left.toDouble().coerceAtLeast(0.0)
        val rightPad = (surfaceWidth - area.right).toDouble().coerceAtLeast(0.0)
        val bottomPad = (surfaceHeight - area.bottom).toDouble().coerceAtLeast(0.0)
        val lowerThirdOffset = if (update.anchorLowerThird && area.height() > 0) area.height() * 0.28 else 0.0
        val topPad = (area.top.toDouble() + lowerThirdOffset).coerceAtLeast(0.0)

        val pos = CameraPosition.Builder()
            .target(LatLng(update.lat, update.lon))
            .zoom(update.zoom)
            .tilt(update.tilt)
            .bearing(update.bearing)
            .padding(doubleArrayOf(leftPad, topPad, rightPad, bottomPad))
            .build()
        runCatching {
            if (update.animationDurationMs <= 0) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
            } else {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), update.animationDurationMs)
            }
        }
    }

    private fun refreshSpeedOverlay() {
        val overlay = speedOverlay ?: return
        val hasPacks = app.packManager.getInstalledPmtilesFiles().isNotEmpty() || currentSettings.devOnlineOregonMode
        val str = stringsFor(currentSettings.language)
        val nav = app.navigationHub.navigationState.value
        val fix = app.locationTracker.currentFix.value
        val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
        overlay.updateState(
            noPackMsg = if (!hasPacks) str.carNoOfflineMapMessage else null,
            showSpeed = hasPacks && (nav.isNavigating || fix != null),
            currentSpeedMps = speedMps,
            currentLimitMph = nav.currentSpeedLimitMph,
            overspeed = nav.isOverspeed,
            unit = currentSettings.distanceUnit,
            lang = currentSettings.language, darkMode = isDarkMode,
            toleranceMph = currentSettings.speedWarningThresholdMph, limitSource = nav.speedLimitSource
        )
    }

    private fun tearDownSurface() {
        mainHandler.removeCallbacks(autoRecenterRunnable)
        scope?.cancel()
        scope = null
        loadedStyle = null
        mapLibreMap = null
        lastLoadedStyleJson = null
        speedOverlay = null
        runCatching {
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
        mapView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }
}
