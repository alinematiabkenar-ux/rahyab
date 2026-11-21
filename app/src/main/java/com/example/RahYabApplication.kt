package com.example

import android.app.Application
import com.example.data.local.RahYabDatabase
import com.example.data.packs.StatePackManager
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.OnlineOregonDevSearchClient
import com.example.navigation.CompassTracker
import com.example.navigation.LocationTracker
import com.example.navigation.ValhallaRoutingEngine
import com.example.navigation.VoiceGuidanceManager
import com.example.util.CrashLogManager
import org.maplibre.android.MapLibre

class RahYabApplication : Application() {

    lateinit var database: RahYabDatabase
        private set
    lateinit var preferencesRepository: UserPreferencesRepository
        private set
    lateinit var packManager: StatePackManager
        private set
    lateinit var offlineSearchEngine: OfflinePlaceSearchEngine
        private set
    lateinit var onlineOregonDevSearchClient: OnlineOregonDevSearchClient
        private set
    lateinit var routingEngine: ValhallaRoutingEngine
        private set
    lateinit var locationTracker: LocationTracker
        private set
    lateinit var compassTracker: CompassTracker
        private set
    lateinit var voiceGuidanceManager: VoiceGuidanceManager
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogManager.install(this)
        // Initialize MapLibre Native once at application startup
        runCatching { MapLibre.getInstance(this) }

        database = RahYabDatabase.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)
        packManager = StatePackManager(this, database.installedPackDao())
        offlineSearchEngine = OfflinePlaceSearchEngine(packManager, database.savedPlaceDao())
        onlineOregonDevSearchClient = OnlineOregonDevSearchClient()
        routingEngine = ValhallaRoutingEngine(this, packManager)
        locationTracker = LocationTracker(this)
        compassTracker = CompassTracker(this)
        voiceGuidanceManager = VoiceGuidanceManager(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        locationTracker.stopTracking()
        compassTracker.stop()
        voiceGuidanceManager.shutdown()
        routingEngine.close()
    }
}
