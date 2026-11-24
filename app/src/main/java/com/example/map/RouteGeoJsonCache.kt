package com.example.map

import com.example.navigation.OfflineRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Thread-safe bounded cache that converts an [OfflineRoute]'s coordinate list into MapLibre
 * GeoJSON [LineString] / [FeatureCollection] objects off the main thread and reuses them across
 * rapid route-card switches and map updates.
 */
class RouteGeoJsonCache(
    private val maxEntries: Int = 24
) {
    private val lock = Any()

    private val lineCache = object : LinkedHashMap<String, LineString>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LineString>?): Boolean =
            size > maxEntries
    }

    private val featureCollectionCache =
        object : LinkedHashMap<String, FeatureCollection>(maxEntries * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FeatureCollection>?): Boolean =
                size > maxEntries * 2
        }

    fun routeKey(route: OfflineRoute): String {
        val first = route.shape.firstOrNull()
        val last = route.shape.lastOrNull()
        return buildString {
            append(route.id)
            append('|')
            append(route.travelMode.code)
            append('|')
            append(route.shape.size)
            append('|')
            append(route.distanceMeters.toLong())
            append('|')
            append(route.durationSeconds.toLong())
            if (first != null && last != null) {
                append('|')
                append((first.lat * 1e5).toLong())
                append(',')
                append((first.lon * 1e5).toLong())
                append(':')
                append((last.lat * 1e5).toLong())
                append(',')
                append((last.lon * 1e5).toLong())
            }
        }
    }

    suspend fun getOrBuildFeatureCollection(
        route: OfflineRoute,
        colorHex: String? = null
    ): FeatureCollection {
        if (route.shape.size < 2) return EMPTY_FEATURE_COLLECTION
        val baseKey = routeKey(route)
        val fcKey = if (colorHex != null) "$baseKey#$colorHex" else baseKey

        synchronized(lock) {
            featureCollectionCache[fcKey]?.let { return it }
        }

        return withContext(Dispatchers.Default) {
            val line = synchronized(lock) { lineCache[baseKey] } ?: run {
                val pts = ArrayList<Point>(route.shape.size)
                for (pt in route.shape) {
                    pts.add(Point.fromLngLat(pt.lon, pt.lat))
                }
                val builtLine = LineString.fromLngLats(pts)
                synchronized(lock) {
                    lineCache[baseKey] = builtLine
                }
                builtLine
            }

            val feature = Feature.fromGeometry(line).apply {
                addStringProperty("mode", route.travelMode.code)
                if (colorHex != null) {
                    addStringProperty("color", colorHex)
                }
            }
            val fc = FeatureCollection.fromFeature(feature)
            synchronized(lock) {
                featureCollectionCache[fcKey] = fc
            }
            fc
        }
    }

    fun clear() {
        synchronized(lock) {
            lineCache.clear()
            featureCollectionCache.clear()
        }
    }

    internal fun cachedLineCount(): Int = synchronized(lock) { lineCache.size }

    companion object {
        val EMPTY_FEATURE_COLLECTION: FeatureCollection =
            FeatureCollection.fromFeatures(emptyArray())
    }
}
