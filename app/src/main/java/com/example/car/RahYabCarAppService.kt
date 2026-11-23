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

class RahYabCarSession : Session() {

    private var surfaceRenderer: CarMapSurfaceRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as RahYabApplication
        val renderer = CarMapSurfaceRenderer(carContext, app)
        surfaceRenderer = renderer
        renderer.attachToHost()
        app.navigationHub.setCarSessionConnected(true)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                app.navigationHub.setCarSessionConnected(false)
                surfaceRenderer?.release()
                surfaceRenderer = null
            }
        })

        val home = CarHomeScreen(carContext, app, renderer)
        if (app.navigationHub.navigationState.value.isNavigating) {
            carContext.getCarService(ScreenManager::class.java).push(home)
            return CarNavigationScreen(carContext, app, renderer)
        }
        return home
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        surfaceRenderer?.onDarkModeChanged(carContext.isDarkMode)
    }
}
