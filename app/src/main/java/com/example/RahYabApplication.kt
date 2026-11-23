package com.example

import android.app.Application
import android.content.Context
import com.example.data.local.RahYabDatabase
import com.example.data.online.NetworkStatusMonitor
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

    @Volatile
    var isMapLibreInitialized: Boolean = false
        private set

    private val databaseDelegate = lazy { RahYabDatabase.getInstance(this) }
    val database: RahYabDatabase by databaseDelegate

    private val preferencesDelegate = lazy { UserPreferencesRepository(this) }
    val preferencesRepository: UserPreferencesRepository by preferencesDelegate

    private val packManagerDelegate = lazy { StatePackManager(this, database.installedPackDao()) }
    val packManager: StatePackManager by packManagerDelegate

    private val offlineSearchDelegate = lazy { OfflinePlaceSearchEngine(packManager, database.savedPlaceDao()) }
    val offlineSearchEngine: OfflinePlaceSearchEngine by offlineSearchDelegate

    private val onlineSearchDelegate = lazy { OnlineOregonDevSearchClient() }
    val onlineOregonDevSearchClient: OnlineOregonDevSearchClient by onlineSearchDelegate

    private val routingEngineDelegate = lazy { ValhallaRoutingEngine(this, packManager) }
    val routingEngine: ValhallaRoutingEngine by routingEngineDelegate

    private val locationTrackerDelegate = lazy { LocationTracker(this) }
    val locationTracker: LocationTracker by locationTrackerDelegate

    private val compassTrackerDelegate = lazy { CompassTracker(this) }
    val compassTracker: CompassTracker by compassTrackerDelegate

    private val voiceGuidanceDelegate = lazy { VoiceGuidanceManager(this) }
    val voiceGuidanceManager: VoiceGuidanceManager by voiceGuidanceDelegate

    private val networkStatusDelegate = lazy { NetworkStatusMonitor(this) }
    val networkStatusMonitor: NetworkStatusMonitor by networkStatusDelegate

    private val navigationHubDelegate = lazy { com.example.navigation.RahYabNavigationHub(this) }
    val navigationHub: com.example.navigation.RahYabNavigationHub by navigationHubDelegate

    val isPackManagerInitialized: Boolean get() = packManagerDelegate.isInitialized()
    val isCompassTrackerInitialized: Boolean get() = compassTrackerDelegate.isInitialized()
    val isVoiceGuidanceInitialized: Boolean get() = voiceGuidanceDelegate.isInitialized()
    val isRoutingEngineInitialized: Boolean get() = routingEngineDelegate.isInitialized()
    val isNetworkMonitorInitialized: Boolean get() = networkStatusDelegate.isInitialized()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Install crash handler as early as possible so even Application.onCreate crashes are captured
        runCatching { CrashLogManager.install(base) }
    }

    override fun onCreate() {
        // Install uncaught exception handler as the very first line before anything else runs
        CrashLogManager.install(this)
        super.onCreate()
        // All heavy subsystems (MapLibre, Room, PackManager, TTS, Compass, Valhalla) are
        // initialized lazily so that if a crash log is pending on next launch, MainActivity
        // can show the safe crash recovery screen without touching any of them.
        if (CrashLogManager.readPendingCrashLog(this) == null) {
            com.example.data.packs.MapUpdateCheckWorker.schedulePeriodic(this)
        }
    }

    fun ensureMapLibreInitialized() {
        if (isMapLibreInitialized) return
        synchronized(this) {
            if (isMapLibreInitialized) return
            runCatching {
                MapLibre.getInstance(this)
                isMapLibreInitialized = true
            }.onFailure { err ->
                CrashLogManager.logError(this, "MapLibre", "MapLibre initialization failed", err)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        if (locationTrackerDelegate.isInitialized()) locationTracker.stopTracking()
        if (compassTrackerDelegate.isInitialized()) compassTracker.stop()
        if (voiceGuidanceDelegate.isInitialized()) voiceGuidanceManager.shutdown()
        if (routingEngineDelegate.isInitialized()) routingEngine.close()
        if (networkStatusDelegate.isInitialized()) networkStatusMonitor.stop()
    }
}
