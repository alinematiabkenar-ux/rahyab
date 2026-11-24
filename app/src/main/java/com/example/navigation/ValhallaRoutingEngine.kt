package com.example.navigation

import android.content.Context
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.online.OnlineHttpConfig
import com.example.data.packs.StatePackManager
import com.example.util.CrashLogManager
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class LastRouteDiagnostics(
    val maneuverCount: Int,
    val maneuversWithLaneDataCount: Int,
    val shapePointsWithSpeedLimitCount: Int,
    val totalShapePoints: Int,
    val routingTimeMs: Long,
    val traceAttributesEnriched: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Offline turn-by-turn routing engine backed by native `valhalla-mobile` (`Valhalla`).
 *
 * Uses the unified extracted Valhalla routing tiles directory (`filesDir/valhalla_tiles`)
 * built with ~30-mile border overlap across neighboring states so routes seamlessly cross
 * US state lines whenever both states are installed.
 *
 * Enriches each calculated route with real OSM speed limits and edge lane counts via
 * Valhalla's offline `trace_attributes` (`shape_match: "edge_walk"`) action — never inventing
 * speed limits when OSM maxspeed is absent.
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

    private val _lastRouteDiagnostics = MutableStateFlow<LastRouteDiagnostics?>(null)
    val lastRouteDiagnostics: StateFlow<LastRouteDiagnostics?> = _lastRouteDiagnostics.asStateFlow()

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
        val startNanos = System.nanoTime()
        val fastestRequestJson = ValhallaResponseParser.buildRouteRequestJson(
            origin = origin,
            destination = destination,
            options = options.copy(preferShortest = false),
            headingDegrees = headingDegrees,
            alternatesCount = 2
        )
        val shortestRequestJson = ValhallaResponseParser.buildRouteRequestJson(
            origin = origin,
            destination = destination,
            options = options.copy(preferShortest = true),
            headingDegrees = headingDegrees,
            alternatesCount = 0
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
                    val fastestRaw = engine.routeRaw(fastestRequestJson)
                    val fastestParsed = ValhallaResponseParser.parseRoutes(fastestRaw)
                        .map { it.copy(travelMode = options.travelMode) }
                    val shortestParsed = if (options.travelMode != TravelMode.DRIVE) null else runCatching {
                        ValhallaResponseParser.parseRoutes(engine.routeRaw(shortestRequestJson))
                            .firstOrNull()?.copy(travelMode = options.travelMode)
                    }.getOrNull()
                    val merged = ValhallaResponseParser.mergeFastestAndShortestRoutes(
                        fastestRoutes = fastestParsed,
                        shortestRoute = shortestParsed,
                        preferShortest = options.preferShortest
                    ).map { it.copy(travelMode = options.travelMode) }
                    if (merged.isEmpty()) {
                        throw IllegalStateException("No offline route found between these locations.")
                    }
                    var anyTraceSucceeded = false
                    val enriched = merged.map { route ->
                        val traced = enrichRouteOffline(engine, route, options.travelMode)
                        if (traced.speedLimitsByShapeIndex.size > route.speedLimitsByShapeIndex.size) {
                            anyTraceSucceeded = true
                        }
                        traced
                    }
                    val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                    recordRouteDiagnostics(enriched.first(), elapsedMs, anyTraceSucceeded)
                    Result.success(enriched)
                }
            } catch (ule: UnsatisfiedLinkError) {
                CrashLogManager.logError(context, "Valhalla", "Native Valhalla library load failed", ule)
                Result.failure(
                    IllegalStateException(
                        "Native Valhalla routing library failed to load on this device architecture (${ule.message.orEmpty()})."
                    )
                )
            } catch (t: Throwable) {
                CrashLogManager.logError(context, "Valhalla", "Offline route calculation failed", t)
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
                val fastestParsed = executeOnlineValhallaRequest(fastestRequestJson)
                    .map { it.copy(travelMode = options.travelMode) }
                val shortestParsed = if (options.travelMode != TravelMode.DRIVE) null else runCatching {
                    executeOnlineValhallaRequest(shortestRequestJson)
                        .firstOrNull()?.copy(travelMode = options.travelMode)
                }.getOrNull()
                val merged = ValhallaResponseParser.mergeFastestAndShortestRoutes(
                    fastestRoutes = fastestParsed,
                    shortestRoute = shortestParsed,
                    preferShortest = options.preferShortest
                ).map { it.copy(travelMode = options.travelMode) }
                if (merged.isEmpty()) {
                    throw IllegalStateException("No route returned from public Valhalla test server.")
                }
                var anyTraceSucceeded = false
                val enriched = merged.map { route ->
                    val traced = enrichRouteOnline(route, options.travelMode)
                    if (traced.speedLimitsByShapeIndex.size > route.speedLimitsByShapeIndex.size) {
                        anyTraceSucceeded = true
                    }
                    traced
                }
                val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                recordRouteDiagnostics(enriched.first(), elapsedMs, anyTraceSucceeded)
                enriched
            }.onFailure { err ->
                CrashLogManager.logError(context, "ValhallaOnline", "Online dev route failed", err)
            }
        }

        Result.failure(
            IllegalStateException(
                "Offline routing tiles are not installed for this area. Download or import a state pack in Download Maps."
            )
        )
    }

    private fun enrichRouteOffline(
        engine: Valhalla,
        route: OfflineRoute,
        travelMode: TravelMode = TravelMode.DRIVE
    ): OfflineRoute {
        if (route.shape.size < 2) return route
        return runCatching {
            val encoded = ValhallaResponseParser.encodePolyline6(route.shape)
            val traceReq = ValhallaResponseParser.buildTraceAttributesRequestJson(encoded, travelMode)
            val traceRaw = engine.traceAttributesRaw(traceReq)
            ValhallaResponseParser.enrichRouteWithTraceAttributes(route, traceRaw)
        }.onFailure { err ->
            CrashLogManager.logError(context, "ValhallaTrace", "Offline trace_attributes skipped", err)
        }.getOrDefault(route)
    }

    private fun enrichRouteOnline(
        route: OfflineRoute,
        travelMode: TravelMode = TravelMode.DRIVE
    ): OfflineRoute {
        if (route.shape.size < 2) return route
        return runCatching {
            val encoded = ValhallaResponseParser.encodePolyline6(route.shape)
            val traceReq = ValhallaResponseParser.buildTraceAttributesRequestJson(encoded, travelMode)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(DEV_PUBLIC_VALHALLA_TRACE_URL)
                .header("User-Agent", OnlineHttpConfig.userAgent())
                .post(traceReq.toRequestBody(mediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return@use route
                ValhallaResponseParser.enrichRouteWithTraceAttributes(route, body)
            }
        }.onFailure { err ->
            CrashLogManager.logError(context, "ValhallaTraceOnline", "Online trace_attributes skipped", err)
        }.getOrDefault(route)
    }

    private fun recordRouteDiagnostics(
        primaryRoute: OfflineRoute,
        routingTimeMs: Long,
        traceEnriched: Boolean
    ) {
        _lastRouteDiagnostics.value = LastRouteDiagnostics(
            maneuverCount = primaryRoute.maneuvers.size,
            maneuversWithLaneDataCount = primaryRoute.maneuvers.count { it.lanes.isNotEmpty() },
            shapePointsWithSpeedLimitCount = primaryRoute.speedLimitsByShapeIndex.size,
            totalShapePoints = primaryRoute.shape.size,
            routingTimeMs = routingTimeMs,
            traceAttributesEnriched = traceEnriched
        )
    }

    private fun executeOnlineValhallaRequest(requestJson: String): List<OfflineRoute> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(DEV_PUBLIC_VALHALLA_URL)
            .header("User-Agent", OnlineHttpConfig.userAgent())
            .post(requestJson.toRequestBody(mediaType))
            .build()

        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Dev online Valhalla returned HTTP ${resp.code}: $body")
            }
            ValhallaResponseParser.parseRoutes(body)
        }
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
        private const val DEV_PUBLIC_VALHALLA_TRACE_URL = "https://valhalla1.openstreetmap.de/trace_attributes"
    }
}
