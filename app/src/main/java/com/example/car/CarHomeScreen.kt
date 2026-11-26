package com.example.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.R
import com.example.RahYabApplication
import com.example.data.preferences.UserSettings
import com.example.i18n.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AA4: Android Auto Home screen showing RahYab's live offline vector map with an action strip
 * (Search, Favorites, Recent) and map controls (zoom in, zoom out, recenter, pan).
 * Automatically transitions to [CarNavigationScreen] whenever active navigation begins on either
 * the phone or the car.
 */
class CarHomeScreen(
    carContext: CarContext,
    private val app: RahYabApplication,
    private val renderer: CarMapSurfaceRenderer
) : Screen(carContext) {

    private val alertHelper = CarAlertHelper(carContext)
    private var scope: CoroutineScope? = null
    private var settings: UserSettings = UserSettings()
    private var isInPanMode: Boolean = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
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
                    app.navigationHub.freeDriveAheadAlert.collectLatest { alert ->
                        alertHelper.showAheadAlertIfNew(alert, settings.distanceUnit, settings.language)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        CarScreenDiagnosticsStore.onTopScreenChanged("CarHomeScreen")
        val str = stringsFor(settings.language)
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_search))
                    .setTitle(str.carActionSearch)
                    .setOnClickListener {
                        screenManager.push(CarSearchScreen(carContext, app, renderer))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_favorite))
                    .setTitle(str.carActionFavorites)
                    .setOnClickListener {
                        screenManager.push(
                            CarPlacesListScreen(carContext, app, renderer, showRecents = false)
                        )
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_recent))
                    .setTitle(str.carActionRecent)
                    .setOnClickListener {
                        screenManager.push(
                            CarPlacesListScreen(carContext, app, renderer, showRecents = true)
                        )
                    }
                    .build()
            )
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip)

        if (carContext.carAppApiLevel >= 2) {
            builder
                .setMapActionStrip(CarMapActionsHelper.buildMapActionStrip(carContext, renderer))
                .setPanModeListener { enabled ->
                    isInPanMode = enabled
                    if (!enabled) renderer.recenter()
                }
        }
        CarScreenDiagnosticsStore.onTemplateBuilt("NavigationTemplate", routingInfoHadStep = false)
        return builder.build()
    }
}
