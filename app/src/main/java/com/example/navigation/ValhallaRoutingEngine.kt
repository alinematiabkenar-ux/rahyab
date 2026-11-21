package com.example.navigation

import android.content.Context
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.packs.StatePackManager
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Offline turn-by-turn routing engine backed by native `valhalla-mobile` (`Valhalla`).
 *
 * Uses the unified extracted Valhalla routing tiles directory (`filesDir/valhalla_tiles`)
 * built with ~30-mile border overlap across neighboring states so routes seamlessly cross
 * US state lines whenever both states are installed.
 *
 * Checks installed states before routing so missing states produce a clear, actionable
 * `MissingStatesException` (never silently drawing a partial or straight-line route!).
 */
class ValhallaRoutingEngine(
    private val context: Context,
    private val packManager: StatePackManager
) {
    private val mutex = Mutex()
    private var valhallaInstance: Valhalla? = null
    private var lastLoadedPacksTick: Long = -1L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Checks whether all US states/regions along the corridor between [origin] and [destination]
     * are installed locally. Returns missing states if any are not installed.
     */
    fun detectMissingStatesForTrip(
        origin: LatLon,
        destination: LatLon,
        installedPackCodes: Set<String> = packManager.getInstalledRoutingPackCodes()
    ): List<UsStateInfo> {
        return UsStatesCatalog.findMissingStatesForCorridor(
            origin = origin,
            destination = destination,
            installedCodes = installedPackCodes
        )
    }

    suspend fun calculateRoutes(
        origin: LatLon,
        destination: LatLon,
        options: RouteOptions,
        headingDegrees: Float? = null,
        allowDevOnlineFallback: Boolean = false
    ): Result<List<OfflineRoute>> = withContext(Dispatchers.IO) {
        val requestJson = ValhallaResponseParser.buildRouteRequestJson(
            origin = origin,
            destination = destination,
            options = options,
            headingDegrees = headingDegrees,
            alternatesCount = 2
        )

        val installedCodes = packManager.getInstalledRoutingPackCodes()
        val hasLocalTiles = packManager.hasAnyValidValhallaTiles()
        val tarFiles = packManager.getInstalledValhallaTarFiles()

        // 0. Check for missing states along the corridor when operating offline
        if (!allowDevOnlineFallback || installedCodes.isNotEmpty()) {
            val missingStates = detectMissingStatesForTrip(origin, destination, installedCodes)
            if (missingStates.isNotEmpty() && !allowDevOnlineFallback) {
                val names = missingStates.joinToString(", ") { it.nameEn }
                return@withContext Result.failure(
                    MissingStatesException(
                        missingStates = missingStates,
                        message = "Missing offline map pack(s) required for this route: $names. Download them to route across state lines."
                    )
                )
            }
        }

        // 1. Primary path: 100% offline on-device valhalla-mobile engine
        if (hasLocalTiles || tarFiles.isNotEmpty()) {
            val localResult = try {
                mutex.withLock {
                    val engine = getOrCreateLocalValhalla(hasLocalTiles, tarFiles)
                        ?: throw IllegalStateException("Valhalla routing engine could not initialize for installed tiles.")
                    val rawJson = engine.routeRaw(requestJson)
                    val parsed = ValhallaResponseParser.parseRoutes(rawJson)
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("No offline route found between these locations.")
                    }
                    Result.success(parsed)
                }
            } catch (ule: UnsatisfiedLinkError) {
                Result.failure(
                    IllegalStateException(
                        "Native Valhalla routing library failed to load on this device architecture (${ule.message.orEmpty()})."
                    )
                )
            } catch (t: Throwable) {
                Result.failure(
                    IllegalStateException(t.message ?: "Offline Valhalla routing failed.")
                )
            }
            if (localResult.isSuccess || !allowDevOnlineFallback) {
                return@withContext localResult
            }
        }

        // 2. Temporary Developer Option: Public Valhalla server (FOSSGIS / openstreetmap.de)
        if (allowDevOnlineFallback) {
            return@withContext runCatching {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = Request.Builder()
                    .url(DEV_PUBLIC_VALHALLA_URL)
                    .header("User-Agent", "RahYab-Android-Nav/1.1")
                    .post(requestJson.toRequestBody(mediaType))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Dev online Valhalla returned HTTP ${resp.code}: $body")
                    }
                    val parsed = ValhallaResponseParser.parseRoutes(body)
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("No route returned from public Valhalla test server.")
                    }
                    parsed
                }
            }
        }

        Result.failure(
            IllegalStateException(
                "Offline routing tiles are not installed for this area. Download or import a state pack in Download Maps."
            )
        )
    }

    private fun getOrCreateLocalValhalla(
        hasExtractedTiles: Boolean,
        tarFiles: List<java.io.File>
    ): Valhalla? {
        val currentTick = packManager.packsChangedTick.value
        if (valhallaInstance != null && lastLoadedPacksTick == currentTick) {
            return valhallaInstance
        }

        runCatching { valhallaInstance?.close() }
        valhallaInstance = null

        val config = if (hasExtractedTiles) {
            ValhallaConfigFactory.usingTilesDir(packManager.unifiedValhallaTileDir.absolutePath)
        } else if (tarFiles.size == 1) {
            ValhallaConfigFactory.usingTileExtract(tarFiles.first().absolutePath)
        } else {
            return null
        }

        return Valhalla(context.applicationContext, config).also {
            valhallaInstance = it
            lastLoadedPacksTick = currentTick
        }
    }

    fun close() {
        runCatching { valhallaInstance?.close() }
        valhallaInstance = null
    }

    companion object {
        private const val DEV_PUBLIC_VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
    }
}
