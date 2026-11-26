package com.example.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.RahYabApplication
import com.example.navigation.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android Auto entry point for RahYab offline turn-by-turn navigation.
 *
 * Uses [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] because this personal build is sideloaded
 * directly with Android Auto Developer Settings -> "Unknown sources" enabled, and is also
 * tested with the Desktop Head Unit (DHU).
 */
class RahYabCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return RahYabCarSession()
    }
}

/**
 * B1: Single source of truth for switching Android Auto between [CarHomeScreen] and
 * [CarNavigationScreen] whenever navigation starts or ends (from phone OR car).
 */
class RahYabCarSession : Session() {

    private var surfaceRenderer: CarMapSurfaceRenderer? = null
    private var sessionScope: CoroutineScope? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as RahYabApplication
        val renderer = CarMapSurfaceRenderer(carContext, app)
        surfaceRenderer = renderer
        renderer.attachToHost()
        app.navigationHub.setCarSessionConnected(true)
        CarScreenDiagnosticsStore.onSessionConnected(carContext.carAppApiLevel)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        sessionScope = scope

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                sessionScope?.cancel()
                sessionScope = null
                app.navigationHub.setCarSessionConnected(false)
                CarScreenDiagnosticsStore.onSessionDisconnected()
                surfaceRenderer?.release()
                surfaceRenderer = null
            }
        })

        val screenManager = carContext.getCarService(ScreenManager::class.java)
        val home = CarHomeScreen(carContext, app, renderer)
        val initialNav = app.navigationHub.navigationState.value
        val startInNav = initialNav.isNavigating && initialNav.effectiveTravelMode == TravelMode.DRIVE

        scope.launch {
            app.navigationHub.navigationState.collectLatest { nav ->
                runCatching {
                    val top = screenManager.top
                    val shouldShowNav = nav.isNavigating && nav.effectiveTravelMode == TravelMode.DRIVE
                    if (shouldShowNav && top !is CarNavigationScreen) {
                        screenManager.popToRoot()
                        screenManager.push(CarNavigationScreen(carContext, app, renderer))
                        CarScreenDiagnosticsStore.onTopScreenChanged("CarNavigationScreen")
                    } else if (!nav.isNavigating && top is CarNavigationScreen) {
                        screenManager.popToRoot()
                        CarScreenDiagnosticsStore.onTopScreenChanged("CarHomeScreen")
                    } else {
                        CarScreenDiagnosticsStore.onTopScreenChanged(top.javaClass.simpleName)
                    }
                }
            }
        }

        return if (startInNav) {
            screenManager.push(home)
            CarScreenDiagnosticsStore.onTopScreenChanged("CarNavigationScreen")
            CarNavigationScreen(carContext, app, renderer)
        } else {
            CarScreenDiagnosticsStore.onTopScreenChanged("CarHomeScreen")
            home
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        surfaceRenderer?.onDarkModeChanged(carContext.isDarkMode)
    }
}
