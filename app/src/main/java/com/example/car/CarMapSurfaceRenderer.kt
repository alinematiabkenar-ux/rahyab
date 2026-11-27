package com.example.car

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
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
import com.example.navigation.SynchronizedFollowAnimator
import com.example.navigation.SynchronizedMapPose
import com.example.navigation.TurnFocusStage
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
 * AA3: Renders RahYab's offline MapLibre PMTiles vector map onto the Android Auto navigation
 * surface via a VirtualDisplay + Presentation hosting a MapLibre [MapView].
 *
 * Drives BOTH the vehicle puck and the map camera from one shared [SynchronizedFollowAnimator]
 * Choreographer clock bounded to `stableArea`/`visibleArea` so the marker never lags behind the
 * moving map or leaves the unobstructed area, while keeping pan/fling/scale responsive.
 */
class CarMapSurfaceRenderer(
    private val carContext: CarContext,
    private val app: RahYabApplication
) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val followController = CameraFollowController()
    private val syncAnimator = SynchronizedFollowAnimator()
    private var choreographer: Choreographer? = null
    private var isFrameLoopPosted: Boolean = false
    private var scope: CoroutineScope? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var loadedStyle: Style? = null
    private var speedOverlay: CarSpeedOverlayView? = null

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var surfacePixelRatio: Float = 1f
    private var visibleArea: Rect = Rect()
    private var stableArea: Rect = Rect()
    private var isFollowing: Boolean = true
    private var isDarkMode: Boolean = carContext.isDarkMode
    private var currentSettings: UserSettings = UserSettings()
    private var lastLoadedStyleJson: String? = null
    private var wasFocusCameraActive: Boolean = false
    private var userPannedDuringFocus: Boolean = false
    private var lastRouteId: String? = null
    private var lastFocusManeuverIndex: Int = -1
    private var lastFocusStage = TurnFocusStage.NONE
    private var lastFocusSnapshot: CarCameraSnapshot? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            isFrameLoopPosted = false
            val nowMs = SystemClock.uptimeMillis()
            val pose = syncAnimator.sample(nowMs) ?: return
            applySynchronizedPose(pose)
            if (!syncAnimator.isAnimationFinishedAt(nowMs)) {
                isFrameLoopPosted = true
                choreographer?.postFrameCallback(this)
            }
        }
    }

    private val autoRecenterRunnable = Runnable {
        if (app.navigationHub.navigationState.value.turnFocusActive && userPannedDuringFocus) return@Runnable
        isFollowing = true
        followCurrentLocation(forceImmediate = true)
    }

    fun attachToHost() {
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    }

    fun onDarkModeChanged(darkMode: Boolean) {
        if (isDarkMode != darkMode) {
            isDarkMode = darkMode
            mainHandler.post { reloadMapStyleIfNeeded(force = true); refreshSpeedOverlay() }
        }
    }

    fun zoomIn() = mainHandler.post {
        pauseFollowWithTimer()
        val map = mapLibreMap ?: return@post
        map.animateCamera(CameraUpdateFactory.zoomTo((map.cameraPosition.zoom + 1.0).coerceAtMost(19.0)), 250)
    }

    fun zoomOut() = mainHandler.post {
        pauseFollowWithTimer()
        val map = mapLibreMap ?: return@post
        map.animateCamera(CameraUpdateFactory.zoomTo((map.cameraPosition.zoom - 1.0).coerceAtLeast(4.0)), 250)
    }

    fun recenter() = mainHandler.post {
        mainHandler.removeCallbacks(autoRecenterRunnable)
        userPannedDuringFocus = false
        isFollowing = true
        followCurrentLocation(forceImmediate = true)
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
            choreographer = Choreographer.getInstance()

            val dm = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay("RahYabCarMap", w, h, dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            virtualDisplay = vd

            val pres = Presentation(carContext, vd.display)
            val root = FrameLayout(carContext)
            surfacePixelRatio = maxOf(1.0f, surfaceContainer.dpi.toFloat() / 160f)
            CarScreenDiagnosticsStore.onSurfaceAvailable(w, h, dpi, surfacePixelRatio)
            val mapOptions = MapLibreMapOptions.createFromAttributes(pres.context).textureMode(true).pixelRatio(surfacePixelRatio)
            val mv = MapView(pres.context, mapOptions).apply { onCreate(null); onStart(); onResume() }
            val overlay = CarSpeedOverlayView(carContext)
            root.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            pres.setContentView(root)
            pres.show()

            presentation = pres; mapView = mv; speedOverlay = overlay
            mv.getMapAsync { map ->
                mapLibreMap = map
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = false
                val ref = app.navigationHub.getCurrentReferenceLocation()
                map.cameraPosition = CameraPosition.Builder().target(LatLng(ref.lat, ref.lon)).zoom(14.5).build()
                reloadMapStyleIfNeeded(force = true)
                startObservers()
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        mainHandler.post {
            this.visibleArea = Rect(visibleArea)
            CarScreenDiagnosticsStore.onVisibleAreaChanged(this.visibleArea)
            speedOverlay?.updateVisibleArea(this.visibleArea)
            if (isFollowing) followCurrentLocation(forceImmediate = true)
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        mainHandler.post {
            this.stableArea = Rect(stableArea)
            CarScreenDiagnosticsStore.onStableAreaChanged(this.stableArea)
            if (isFollowing) followCurrentLocation(forceImmediate = true)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post { pauseFollowWithTimer(); mapLibreMap?.scrollBy(-distanceX, -distanceY, 0L) }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            pauseFollowWithTimer()
            mapLibreMap?.scrollBy((velocityX * 0.12f).coerceIn(-260f, 260f), (velocityY * 0.12f).coerceIn(-260f, 260f), 260L)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f || !scaleFactor.isFinite()) return
        mainHandler.post {
            pauseFollowWithTimer()
            val map = mapLibreMap ?: return@post
            val nextZoom = (map.cameraPosition.zoom + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(4.0, 19.0)
            map.moveCamera(CameraUpdateFactory.zoomTo(nextZoom))
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { mainHandler.post { tearDownSurface() } }
    fun release() { mainHandler.post { tearDownSurface() } }

    private fun pauseFollowWithTimer() {
        isFollowing = false
        syncAnimator.pauseCameraFollow()
        runCatching { mapLibreMap?.cancelTransitions() }
        if (app.navigationHub.navigationState.value.turnFocusActive) userPannedDuringFocus = true
        followController.recordUserMapGesture(SystemClock.elapsedRealtime())
        mainHandler.removeCallbacks(autoRecenterRunnable)
        mainHandler.postDelayed(autoRecenterRunnable, CameraFollowController.AUTO_RECENTER_DELAY_MS)
    }

    private fun applySynchronizedPose(pose: SynchronizedMapPose) {
        CarMapStyleHelper.updatePuckPose(loadedStyle, pose.markerLat, pose.markerLon, pose.markerBearing)
        if (pose.updateCamera && isFollowing) {
            val map = mapLibreMap ?: return
            val camPos = CameraPosition.Builder()
                .target(LatLng(pose.camLat, pose.camLon))
                .zoom(pose.camZoom).tilt(pose.camTilt).bearing(pose.camBearing)
                .padding(pose.paddingArray()).build()
            runCatching { map.moveCamera(CameraUpdateFactory.newCameraPosition(camPos)) }
        }
    }

    private fun ensureFrameLoopRunning() {
        val nowMs = SystemClock.uptimeMillis()
        syncAnimator.sample(nowMs)?.let { applySynchronizedPose(it) }
        if (!syncAnimator.isAnimationFinishedAt(nowMs) && !isFrameLoopPosted) {
            isFrameLoopPosted = true
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun startObservers() {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val hub = app.navigationHub

        s.launch {
            app.preferencesRepository.settingsFlow.collectLatest { settings ->
                val cameraChanged = settings.carMapView != currentSettings.carMapView || settings.is3DView != currentSettings.is3DView
                currentSettings = settings; reloadMapStyleIfNeeded(force = false); updatePositionAndRoutes(updatePuck = false); updateExtraLayers(); refreshSpeedOverlay()
                if (cameraChanged && isFollowing) followCurrentLocation(forceImmediate = true)
            }
        }
        s.launch { app.packManager.packsChangedTick.collectLatest { reloadMapStyleIfNeeded(force = true); refreshSpeedOverlay() } }
        s.launch {
            combine(hub.navigationState, app.locationTracker.currentFix, hub.userHeadingDegrees) { nav, fix, heading -> Triple(nav, fix, heading) }
                .collectLatest { (nav, fix, heading) ->
                    if (!nav.turnFocusActive && userPannedDuringFocus) userPannedDuringFocus = false
                    if (nav.isRerouting || nav.route?.id != lastRouteId) {
                        syncAnimator.pauseCameraFollow(); runCatching { mapLibreMap?.cancelTransitions() }
                        lastRouteId = nav.route?.id; wasFocusCameraActive = false; lastFocusManeuverIndex = -1
                        lastFocusStage = TurnFocusStage.NONE; lastFocusSnapshot = null
                    }
                    updatePositionAndRoutes(updatePuck = false); refreshSpeedOverlay()
                    val loc = nav.snappedLocation ?: fix?.let { LatLon(it.lat, it.lon) }
                    if (loc != null) {
                        val speed = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
                        val bearing = if (nav.isNavigating) nav.snappedBearing else heading
                        val fixTs = fix?.timestampMs ?: System.currentTimeMillis()
                        if (isFollowing && !nav.isRerouting) {
                            applyCameraFollow(loc, speed, bearing, nav.isNavigating, nav.isWalkingMode, force = false, fixTimestampMs = fixTs)
                        } else {
                            syncAnimator.onMarkerOnlyUpdate(SystemClock.uptimeMillis(), fixTs, loc.lat, loc.lon, bearing, durationMs = 200)
                            ensureFrameLoopRunning()
                        }
                    }
                }
        }
        s.launch { combine(hub.candidateRoutes, hub.selectedRouteIndex, hub.selectedPlace) { _, _, _ -> Unit }.collectLatest { updatePositionAndRoutes(updatePuck = !syncAnimator.hasPose()) } }
        s.launch { combine(hub.visibleCameras, hub.visibleTrafficLights, hub.visibleSpeedBumps, hub.visibleStopSigns) { _, _, _, _ -> Unit }.collectLatest { updateExtraLayers() } }
    }

    private fun reloadMapStyleIfNeeded(force: Boolean) {
        val map = mapLibreMap ?: return
        val styleJson = MapStyleBuilder.buildOfflineStyleJson(
            installedPmtiles = app.packManager.getInstalledPmtilesFiles(), isNightMode = isDarkMode,
            useDevOnlineBasemap = currentSettings.devOnlineOregonMode, language = currentSettings.language,
            styleProfile = MapStyleBuilder.StyleProfile.CAR
        )
        if (!force && styleJson == lastLoadedStyleJson) return
        lastLoadedStyleJson = styleJson; loadedStyle = null
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            CarMapStyleHelper.registerAllCarStyleImages(carContext, style, isDarkMode)
            loadedStyle = style
            updatePositionAndRoutes(updatePuck = true); updateExtraLayers(); followCurrentLocation(forceImmediate = true)
        }
    }

    private fun updatePositionAndRoutes(updatePuck: Boolean = true) = CarMapStyleHelper.updatePositionAndRoutes(
        app, loadedStyle, currentSettings.laneHighlightOnMapEnabled, currentSettings.laneHighlightColor.hex, updatePuck = updatePuck
    )

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
        applyCameraFollow(loc, speed, bearing, nav.isNavigating, nav.isWalkingMode, forceImmediate, fix?.timestampMs ?: System.currentTimeMillis())
    }

    private fun applyCameraFollow(
        loc: LatLon,
        speedMps: Float,
        bearing: Float,
        isNavigating: Boolean,
        isWalking: Boolean,
        force: Boolean,
        fixTimestampMs: Long = 0L
    ) {
        val map = mapLibreMap ?: return
        val nav = app.navigationHub.navigationState.value
        val focusCamera = isNavigating && !nav.isRerouting && nav.turnFocusCameraActive && !userPannedDuringFocus && !isWalking
        val enteringFocus = focusCamera && (!wasFocusCameraActive || nav.turnFocusManeuverIndex != lastFocusManeuverIndex)
        val tightening = focusCamera && !enteringFocus && lastFocusStage != TurnFocusStage.LANE_LEVEL && nav.turnFocusStage == TurnFocusStage.LANE_LEVEL
        val exitingFocus = !focusCamera && wasFocusCameraActive
        wasFocusCameraActive = focusCamera
        lastFocusManeuverIndex = if (focusCamera) nav.turnFocusManeuverIndex else -1
        lastFocusStage = if (focusCamera) nav.turnFocusStage else TurnFocusStage.NONE

        val area = if (stableArea.width() > 0 && stableArea.height() > 0) stableArea else visibleArea
        val viewport = CarCameraProfile.toViewportMetrics(surfaceWidth, surfaceHeight, area, surfacePixelRatio)
        val targetLat: Double; val targetLon: Double; val zoom: Double; val tilt: Double; val camBearing: Double
        val anchorLowerThird: Boolean; val anchorLowerQuarter: Boolean; val animMs: Int; val isTransition: Boolean

        if (focusCamera) {
            val f = CarCameraProfile.computeFocusCameraUpdate(nav, loc, bearing, currentSettings.is3DView, enteringFocus, tightening, viewport)
            if (!force && !enteringFocus && !tightening && CarCameraProfile.shouldSkipStationaryFocusUpdate(
                    lastFocusSnapshot, f.target, f.zoom, f.tilt, f.bearing, speedMps, nav.route?.id, nav.turnFocusManeuverIndex
                )
            ) {
                syncAnimator.onMarkerOnlyUpdate(SystemClock.uptimeMillis(), fixTimestampMs, loc.lat, loc.lon, bearing, durationMs = 180)
                ensureFrameLoopRunning()
                return
            }
            targetLat = f.target.lat; targetLon = f.target.lon; zoom = f.zoom; tilt = f.tilt; camBearing = f.bearing
            anchorLowerThird = false; anchorLowerQuarter = true; animMs = f.animMs; isTransition = enteringFocus || tightening || force
        } else {
            val update = followController.computeFollowCameraUpdate(
                lat = loc.lat, lon = loc.lon, speedMps = speedMps, headingDegrees = bearing,
                isHeadingUpMode = true, is3DView = currentSettings.is3DView, isNavigating = isNavigating,
                forceImmediate = force || exitingFocus, isWalkingMode = isWalking
            )
            if (update == null) {
                syncAnimator.onMarkerOnlyUpdate(SystemClock.uptimeMillis(), fixTimestampMs, loc.lat, loc.lon, bearing, durationMs = 200)
                ensureFrameLoopRunning()
                return
            }
            val carTarget = CarCameraProfile.computeCarCameraTarget(
                basePhoneZoom = update.zoom, carMapView = currentSettings.carMapView, isNavigating = isNavigating,
                is3DView = currentSettings.is3DView, focusCameraActive = false
            )
            targetLat = update.lat; targetLon = update.lon; zoom = carTarget.zoom; tilt = carTarget.tilt; camBearing = update.bearing
            anchorLowerThird = update.anchorLowerThird; anchorLowerQuarter = false
            animMs = CarCameraProfile.resolveCarCameraAnimMs(false, false, exitingFocus, nav.turnFocusEnterAnimMs, update.animationDurationMs)
            isTransition = exitingFocus || force
        }

        val padding = CarCameraProfile.computeCameraPadding(surfaceWidth, surfaceHeight, area, anchorLowerThird, anchorLowerQuarter, surfacePixelRatio)
        lastFocusSnapshot = if (focusCamera) CarCameraSnapshot(targetLat, targetLon, zoom, tilt, camBearing, nav.route?.id, nav.turnFocusManeuverIndex) else null
        CarScreenDiagnosticsStore.onCameraUpdated(zoom, tilt)
        runCatching { map.cancelTransitions() }
        val snapFar = force && map.cameraPosition.target != null &&
            SynchronizedFollowAnimator.needsCameraSnapOnRecenter(map.cameraPosition.target!!.latitude, map.cameraPosition.target!!.longitude, targetLat, targetLon)
        syncAnimator.resumeCameraFollow()
        syncAnimator.onNavigationTargetUpdate(
            nowMs = SystemClock.uptimeMillis(), fixTimestampMs = fixTimestampMs,
            markerLat = loc.lat, markerLon = loc.lon, markerBearing = bearing,
            camLat = targetLat, camLon = targetLon, camZoom = zoom, camTilt = tilt, camBearing = camBearing,
            padding = padding, durationMs = if (snapFar) 0 else animMs,
            isFollowingCamera = true, forceSnap = animMs <= 0 || snapFar, useEaseOut = isTransition
        )
        ensureFrameLoopRunning()
    }

    private fun refreshSpeedOverlay() {
        val overlay = speedOverlay ?: return
        val hasPacks = app.packManager.getInstalledPmtilesFiles().isNotEmpty() || currentSettings.devOnlineOregonMode
        val str = stringsFor(currentSettings.language)
        val nav = app.navigationHub.navigationState.value
        val fix = app.locationTracker.currentFix.value
        val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
        overlay.updateState(
            noPackMsg = if (!hasPacks) str.carNoOfflineMapMessage else null, showSpeed = hasPacks && (nav.isNavigating || fix != null),
            currentSpeedMps = speedMps, currentLimitMph = nav.currentSpeedLimitMph, overspeed = nav.isOverspeed,
            unit = currentSettings.distanceUnit, lang = currentSettings.language, darkMode = isDarkMode,
            toleranceMph = currentSettings.speedWarningThresholdMph, limitSource = nav.speedLimitSource,
            upcomingLimitMph = nav.upcomingSpeedLimitMph, upcomingLimitDistMeters = nav.upcomingSpeedLimitDistanceMeters
        )
    }

    private fun tearDownSurface() {
        mainHandler.removeCallbacks(autoRecenterRunnable)
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null; isFrameLoopPosted = false; syncAnimator.reset()
        scope?.cancel(); scope = null; loadedStyle = null; mapLibreMap = null; lastLoadedStyleJson = null
        wasFocusCameraActive = false; userPannedDuringFocus = false; lastFocusStage = TurnFocusStage.NONE; lastFocusSnapshot = null; speedOverlay = null
        runCatching { mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy() }; mapView = null
        runCatching { presentation?.dismiss() }; presentation = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
    }
}
