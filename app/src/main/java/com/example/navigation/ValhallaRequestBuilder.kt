package com.example.navigation

import java.util.Locale

/**
 * Pure Kotlin Valhalla JSON request builder (no Android org.json runtime dependency so pure JVM
 * unit tests can verify exact payloads for DRIVE, WALK, BIKE, and trace_attributes).
 */
internal object ValhallaRequestBuilder {

    fun buildRouteRequestJson(
        origin: LatLon,
        destination: LatLon,
        options: RouteOptions,
        headingDegrees: Float? = null,
        alternatesCount: Int = 2
    ): String {
        val mode = options.travelMode
        val includeHeading = mode != TravelMode.WALK && headingDegrees != null && headingDegrees in 0f..360f
        val originJson = buildString {
            append('{')
            append("\"lat\":").append(fmtCoord(origin.lat))
            append(",\"lon\":").append(fmtCoord(origin.lon))
            append(",\"type\":\"break\"")
            if (includeHeading) {
                append(",\"heading\":").append(headingDegrees!!.toInt())
                append(",\"heading_tolerance\":60")
            }
            append('}')
        }
        val destJson = buildString {
            append('{')
            append("\"lat\":").append(fmtCoord(destination.lat))
            append(",\"lon\":").append(fmtCoord(destination.lon))
            append(",\"type\":\"break\"")
            append('}')
        }

        val (costing, costingOptionsJson, effectiveAlternates) = when (mode) {
            TravelMode.DRIVE -> {
                val autoOpts = buildString {
                    append("{\"use_tolls\":").append(if (options.avoidTolls) "0.0" else "0.5")
                    append(",\"use_highways\":").append(if (options.avoidHighways) "0.0" else "1.0")
                    append(",\"use_ferry\":").append(if (options.avoidFerries) "0.0" else "0.5")
                    if (options.preferShortest) {
                        append(",\"shortest\":true")
                    }
                    append('}')
                }
                Triple("auto", "{\"auto\":$autoOpts}", alternatesCount.coerceIn(0, 2))
            }
            TravelMode.WALK -> {
                val speed = options.walkingSpeedKmh.coerceIn(2.0, 10.0)
                val pedOpts = "{\"walking_speed\":${fmtDecimal(speed)}}"
                Triple("pedestrian", "{\"pedestrian\":$pedOpts}", 0)
            }
            TravelMode.BIKE -> {
                val speed = options.cyclingSpeedKmh.coerceIn(5.0, 40.0)
                val useHills = if (options.avoidHillsWhenCycling) "0.1" else "0.4"
                val bikeOpts = buildString {
                    append("{\"bicycle_type\":\"Hybrid\"")
                    append(",\"cycling_speed\":").append(fmtDecimal(speed))
                    append(",\"use_roads\":0.25")
                    append(",\"use_hills\":").append(useHills)
                    append('}')
                }
                val bikeAlternates = if (alternatesCount == 0) 0 else 1
                Triple("bicycle", "{\"bicycle\":$bikeOpts}", bikeAlternates)
            }
        }

        return buildString {
            append('{')
            append("\"locations\":[").append(originJson).append(',').append(destJson).append(']')
            append(",\"costing\":\"").append(costing).append('"')
            append(",\"costing_options\":").append(costingOptionsJson)
            append(",\"units\":\"kilometers\"")
            append(",\"language\":\"en-US\"")
            append(",\"alternates\":").append(effectiveAlternates)
            append(",\"directions_options\":{\"units\":\"kilometers\",\"narrative\":true}")
            append(",\"banner_instructions\":true")
            append(",\"voice_instructions\":false")
            append(",\"turn_lanes\":true")

            if (options.avoidLocations.isNotEmpty()) {
                append(",\"exclude_locations\":[")
                options.avoidLocations.take(20).forEachIndexed { idx, loc ->
                    if (idx > 0) append(',')
                    append("{\"lat\":").append(fmtCoord(loc.lat))
                    append(",\"lon\":").append(fmtCoord(loc.lon)).append('}')
                }
                append(']')
            }
            append('}')
        }
    }

    /**
     * Builds a Valhalla `trace_attributes` JSON request using `shape_match: "edge_walk"`.
     * - For DRIVE: requests speed limits, lane counts, shape spans, and elevation grades.
     * - For WALK and BIKE: requests ONLY shape spans and elevation grades (never speed limits).
     */
    fun buildTraceAttributesRequestJson(
        encodedPolyline6: String,
        travelMode: TravelMode = TravelMode.DRIVE
    ): String {
        val costing = when (travelMode) {
            TravelMode.DRIVE -> "auto"
            TravelMode.WALK -> "pedestrian"
            TravelMode.BIKE -> "bicycle"
        }
        val attrs = when (travelMode) {
            TravelMode.DRIVE -> listOf(
                "edge.speed_limit",
                "edge.begin_shape_index",
                "edge.end_shape_index",
                "edge.lane_count",
                "edge.traversability",
                "edge.max_upward_grade",
                "edge.max_downward_grade",
                "edge.weighted_grade"
            )
            TravelMode.WALK, TravelMode.BIKE -> listOf(
                "edge.begin_shape_index",
                "edge.end_shape_index",
                "edge.max_upward_grade",
                "edge.max_downward_grade",
                "edge.weighted_grade"
            )
        }
        val escapedPolyline = escapeJsonString(encodedPolyline6)
        val attrsJson = attrs.joinToString(",") { "\"$it\"" }
        return buildString {
            append('{')
            append("\"encoded_polyline\":\"").append(escapedPolyline).append('"')
            append(",\"shape_match\":\"edge_walk\"")
            append(",\"costing\":\"").append(costing).append('"')
            append(",\"units\":\"kilometers\"")
            append(",\"filters\":{\"attributes\":[").append(attrsJson).append("],\"action\":\"include\"}")
            append('}')
        }
    }

    private fun fmtCoord(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun fmtDecimal(value: Double): String {
        val s = String.format(Locale.US, "%.2f", value)
        return s.trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it }
    }

    private fun escapeJsonString(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
