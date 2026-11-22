package com.example.data.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.util.CrashLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(
    val isOnline: Boolean = false,
    val isWifi: Boolean = false,
    val isMobileData: Boolean = false,
    val isValidated: Boolean = false
)

enum class OnlineSourceState {
    ACTIVE,
    IDLE,
    DISABLED,
    OFFLINE,
    ERROR
}

data class OnlineSourceStatus(
    val state: OnlineSourceState = OnlineSourceState.IDLE,
    val lastSuccessEpochMs: Long = 0L,
    val detail: String = ""
)

data class OnlineSourcesSummary(
    val weatherAlerts: OnlineSourceStatus = OnlineSourceStatus(),
    val onlineSearch: OnlineSourceStatus = OnlineSourceStatus(),
    val mapUpdates: OnlineSourceStatus = OnlineSourceStatus()
)

/**
 * Observes device connectivity in real time via [ConnectivityManager.NetworkCallback].
 * Distinguishes offline, Wi-Fi, and mobile data safely without ever crashing or blocking.
 */
class NetworkStatusMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? = runCatching {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

    private val _networkState = MutableStateFlow(readCurrentNetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    @Volatile
    private var testOverrideState: NetworkState? = null

    @Volatile
    private var isRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitLatestState()
        }

        override fun onLost(network: Network) {
            emitLatestState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            emitLatestState()
        }

        override fun onUnavailable() {
            emitLatestState()
        }
    }

    init {
        registerCallbackSafely()
    }

    private fun registerCallbackSafely() {
        val cm = connectivityManager ?: return
        if (isRegistered) return
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            isRegistered = true
            emitLatestState()
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "NetworkStatusMonitor", "Failed to register network callback", err)
        }
    }

    fun refreshNow() {
        emitLatestState()
    }

    /**
     * Allows unit/Robolectric tests to deterministically simulate Airplane Mode ON/OFF,
     * Wi-Fi, and Mobile Data transitions without OS-level airplane mode permissions.
     */
    fun setOverrideStateForTesting(state: NetworkState?) {
        testOverrideState = state
        emitLatestState()
    }

    fun stop() {
        val cm = connectivityManager ?: return
        if (!isRegistered) return
        runCatching {
            cm.unregisterNetworkCallback(callback)
        }
        isRegistered = false
    }

    private fun emitLatestState() {
        val override = testOverrideState
        _networkState.value = override ?: readCurrentNetworkState()
    }

    private fun readCurrentNetworkState(): NetworkState {
        val cm = connectivityManager ?: return NetworkState()
        return runCatching {
            val activeNetwork = cm.activeNetwork ?: return@runCatching NetworkState()
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching NetworkState()
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isOnline = hasInternet && (isWifi || isCellular || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            NetworkState(
                isOnline = isOnline,
                isWifi = isOnline && isWifi,
                isMobileData = isOnline && !isWifi && isCellular,
                isValidated = isOnline && isValidated
            )
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "NetworkStatusMonitor", "Error reading network capabilities", err)
        }.getOrDefault(NetworkState())
    }
}
