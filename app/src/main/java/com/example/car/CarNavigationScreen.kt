package com.example.car

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.R
import com.example.RahYabApplication
import com.example.data.preferences.UserSettings
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.JunctionScene
import com.example.navigation.JunctionSceneBuilder
import com.example.navigation.TravelMode
import com.example.ui.components.JunctionViewRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * AA6 & AA7: Android Auto Active Turn-by-Turn Navigation screen.
 *
 * Renders [NavigationTemplate] with live [RoutingInfo] (Valhalla -> Car Maneuver, road/highway
 * cue, lane arrows + bitmap, and upcoming "then" step), [Trip] / [TravelEstimate] updates to
 * [NavigationManager], Stop / Mute / Report closure actions, map zoom/recenter/pan controls,
 * DHU `onAutoDriveEnabled` simulation hook, and Car Alerts for cameras/speed bumps/hazards/arrival.
 */
class CarNavigationScreen(
    carContext: CarContext,
    private val app: RahYabApplication,
    private val renderer: CarMapSurfaceRenderer
) : Screen(carContext) {

    private val navigationManager: NavigationManager =
        carContext.getCarService(NavigationManager::class.java)
    private val alertHelper = CarAlertHelper(carContext)

    private var scope: CoroutineScope? = null
    private var settings: UserSettings = UserSettings()
    private var navState: ActiveNavigationState = app.navigationHub.navigationState.value
    private var isNavStartedReported: Boolean = false
    private var hasShownArrival: Boolean = false
    private var lastTripPushElapsedMs: Long = 0L
    private var lastReportedManeuverIdx: Int = -1
    private var cachedJunctionKey: Pair<JunctionScene, Boolean>? = null
    private var cachedJunctionIcon: CarIcon? = null

    private val navCallback = object : NavigationManagerCallback {
        override fun onStopNavigation() {
            isNavStartedReported = false
            app.navigationHub.stopNavigation()
        }

        override fun onAutoDriveEnabled() {
            app.navigationHub.triggerAutoDriveSimulation()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                alertHelper.reset()
                hasShownArrival = false
                runCatching { navigationManager.setNavigationManagerCallback(navCallback) }

                scope?.cancel()
                val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope = s

                s.launch {
                    app.preferencesRepository.settingsFlow.collectLatest {
                        settings = it
                        invalidate()
                    }
                }
                s.launch {
                    val hub = app.navigationHub
                    combine(
                        hub.navigationState,
                        hub.stateStore._cachedWeatherAlerts,
                        hub.stateStore._dismissedWeatherAlertIds
                    ) { nav, weather, dismissed ->
                        nav to weather.firstOrNull { it.id !in dismissed }
                    }.collectLatest { (nav, weatherAlert) ->
                        navState = nav
                        if (!nav.isNavigating) {
                            endNavManagerSession()
                            if (screenManager.top === this@CarNavigationScreen) {
                                screenManager.pop()
                            }
                            return@collectLatest
                        }

                        if (nav.effectiveTravelMode != TravelMode.DRIVE) {
                            endNavManagerSession()
                            invalidate()
                            return@collectLatest
                        }

                        if (!isNavStartedReported) {
                            runCatching { navigationManager.navigationStarted() }
                            isNavStartedReported = true
                        }

                        if (nav.hasArrived && !hasShownArrival) {
                            hasShownArrival = true
                            alertHelper.showArrivalAlert(nav.destinationName, settings.language)
                        } else if (nav.effectiveTravelMode == TravelMode.DRIVE) {
                            alertHelper.showAheadAlertIfNew(
                                alert = nav.activeAheadAlert,
                                unit = settings.distanceUnit,
                                language = settings.language
                            )
                            alertHelper.showWeatherAlertIfNew(weatherAlert, settings.language)
                            if (nav.laneChangeCuePulseAtMs > 0L) {
                                alertHelper.showLaneChangeCueAlert(
                                    maneuverIndex = nav.laneChangeCueManeuverIndex,
                                    pulseAtMs = nav.laneChangeCuePulseAtMs,
                                    language = settings.language
                                )
                            }
                        }

                        pushTripUpdateIfNeeded(nav)
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
                if (!app.navigationHub.navigationState.value.isNavigating) {
                    endNavManagerSession()
                }
                runCatching { navigationManager.clearNavigationManagerCallback() }
            }
        })
    }

    private fun endNavManagerSession() {
        if (isNavStartedReported) {
            runCatching { navigationManager.navigationEnded() }
            isNavStartedReported = false
        }
    }

    private fun pushTripUpdateIfNeeded(nav: ActiveNavigationState) {
        val nowMs = SystemClock.elapsedRealtime()
        val maneuverChanged = nav.currentManeuverIndex != lastReportedManeuverIdx
        if (!maneuverChanged && nowMs - lastTripPushElapsedMs < 1000L) return
        lastTripPushElapsedMs = nowMs
        lastReportedManeuverIdx = nav.currentManeuverIndex

        val step = buildCurrentStep(nav) ?: return
        val destEstimate = CarManeuverMapper.toCarTravelEstimate(
            remainingDistanceMeters = nav.remainingDistanceMeters,
            remainingTimeSeconds = nav.remainingTimeSeconds,
            unit = settings.distanceUnit
        )
        val stepEstimate = CarManeuverMapper.toCarTravelEstimate(
            remainingDistanceMeters = nav.distanceToNextManeuverMeters,
            remainingTimeSeconds = nav.timeToNextManeuverSeconds,
            unit = settings.distanceUnit
        )
        val destBuilder = Destination.Builder()
            .setName(nav.destinationName.ifBlank { stringsFor(settings.language).appName })
        if (nav.destinationAddress.isNotBlank()) {
            destBuilder.setAddress(nav.destinationAddress)
        }
        val trip = Trip.Builder()
            .addDestination(destBuilder.build(), destEstimate)
            .addStep(step, stepEstimate)
            .setLoading(nav.isRerouting)
            .build()
        runCatching { navigationManager.updateTrip(trip) }
    }

    private fun buildJunctionCarIcon(nav: ActiveNavigationState): CarIcon? {
        if (nav.effectiveTravelMode != TravelMode.DRIVE ||
            !settings.junctionPreviewEnabled ||
            !nav.showJunctionPreview
        ) {
            return null
        }
        val maneuver = nav.activeJunctionManeuver ?: nav.currentManeuver ?: return null
        val scene = JunctionSceneBuilder.build(maneuver) ?: return null
        val isNight = carContext.isDarkMode
        val key = scene to isNight
        if (cachedJunctionKey == key && cachedJunctionIcon != null) {
            return cachedJunctionIcon
        }
        val bmp = runCatching {
            JunctionViewRenderer.render(
                scene = scene,
                widthPx = 500,
                heightPx = 260,
                isNightMode = isNight
            )
        }.getOrNull() ?: return null
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        cachedJunctionKey = key
        cachedJunctionIcon = icon
        return icon
    }

    private fun buildCurrentStep(
        nav: ActiveNavigationState,
        suppressLanes: Boolean = false
    ): Step? {
        val maneuver = nav.currentManeuver ?: return null
        val cue = CarManeuverMapper.formatCarStepCue(maneuver, settings.language)
        val stepBuilder = Step.Builder(cue)
            .setManeuver(CarManeuverMapper.toCarManeuver(maneuver))

        val roadName = maneuver.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: maneuver.signpost?.primaryRoadNumbers?.takeIf { it.isNotBlank() }
        if (!roadName.isNullOrBlank()) {
            stepBuilder.setRoad(roadName)
        }

        if (!suppressLanes && !nav.isWalkingMode && nav.showLaneStrip && maneuver.lanes.isNotEmpty()) {
            CarManeuverMapper.toCarLanes(maneuver.lanes).forEach { lane ->
                stepBuilder.addLane(lane)
            }
            CarLaneBitmapRenderer.renderLanesIcon(maneuver.lanes)?.let { icon ->
                stepBuilder.setLanesImage(icon)
            }
        }
        return stepBuilder.build()
    }

    override fun onGetTemplate(): Template {
        val str = stringsFor(settings.language)
        val nav = navState

        val voiceAction = Action.Builder()
            .setIcon(
                CarMapActionsHelper.icon(
                    carContext,
                    if (settings.voiceGuidanceEnabled) R.drawable.ic_car_volume_on else R.drawable.ic_car_volume_off
                )
            )
            .setTitle(if (settings.voiceGuidanceEnabled) str.carActionMute else str.carActionUnmute)
            .setOnClickListener { app.navigationHub.toggleVoiceMuted() }
            .build()

        val closureAction = Action.Builder()
            .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_closure))
            .setTitle(str.carActionReportClosure)
            .setOnClickListener {
                app.navigationHub.reportRoadClosureAtCurrentLocation()
                alertHelper.showClosureReportedToast(settings.language)
            }
            .build()

        val stopAction = Action.Builder()
            .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_stop))
            .setTitle(str.carActionStop)
            .setOnClickListener {
                app.navigationHub.stopNavigation()
                endNavManagerSession()
                screenManager.pop()
            }
            .build()

        if (nav.isNavigating && nav.effectiveTravelMode != TravelMode.DRIVE) {
            return MessageTemplate.Builder(str.carWalkBikePhoneOnlyNotice)
                .setTitle(str.appName)
                .setHeaderAction(Action.BACK)
                .addAction(stopAction)
                .build()
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(stopAction)
            .addAction(voiceAction)
            .addAction(closureAction)
            .build()

        val templateBuilder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip)

        if (carContext.carAppApiLevel >= 2) {
            templateBuilder
                .setMapActionStrip(CarMapActionsHelper.buildMapActionStrip(carContext, renderer))
                .setPanModeListener { enabled -> if (!enabled) renderer.recenter() }
        }

        if (nav.isRerouting) {
            templateBuilder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        } else {
            val junctionIcon = buildJunctionCarIcon(nav)
            val step = buildCurrentStep(nav, suppressLanes = junctionIcon != null)
            if (step != null) {
                val routingBuilder = RoutingInfo.Builder()
                    .setCurrentStep(
                        step,
                        CarMapActionsHelper.toCarDistance(nav.distanceToNextManeuverMeters, settings.distanceUnit)
                    )
                if (junctionIcon != null) {
                    runCatching { routingBuilder.setJunctionImage(junctionIcon) }
                }
                val thenManeuver = nav.thenManeuver
                if (thenManeuver != null) {
                    val nextStep = Step.Builder(CarManeuverMapper.formatCarStepCue(thenManeuver, settings.language))
                        .setManeuver(CarManeuverMapper.toCarManeuver(thenManeuver))
                        .build()
                    routingBuilder.setNextStep(nextStep)
                }
                templateBuilder.setNavigationInfo(routingBuilder.build())
            }
        }

        templateBuilder.setDestinationTravelEstimate(
            CarManeuverMapper.toCarTravelEstimate(
                remainingDistanceMeters = nav.remainingDistanceMeters,
                remainingTimeSeconds = nav.remainingTimeSeconds,
                unit = settings.distanceUnit
            )
        )

        return templateBuilder.build()
    }
}
