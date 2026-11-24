package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * X5: Elevation grade span parsed from Valhalla `trace_attributes` edge attributes.
 * All values are optional so old state packs without baked elevation data cleanly yield null/empty.
 */
data class EdgeGradeSpan(
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val maxUpwardGrade: Double? = null,
    val maxDownwardGrade: Double? = null,
    val weightedGrade: Double? = null
)

/**
 * X5: Pure Kotlin steep-grade detector along a Valhalla-traced route polyline.
 *
 * Rules:
 * - Downgrade >= 6% sustained for >= 0.5 miles (804.67 m), OR
 * - Upgrade >= 7% sustained for >= 0.5 miles (804.67 m).
 * - Produces one [RoadAlertKind.STEEP_GRADE] [AheadAlert] anchored at the start of each
 *   qualifying grade run (spoken & shown ~1 mile before via [NavigationAlertEvaluator]).
 * - Old packs without elevation data produce zero alerts without crashing or inventing grades.
 */
object SteepGradeEvaluator {

    const val MIN_DOWNGRADE_PERCENT = 6.0
    const val MIN_UPGRADE_PERCENT = 7.0
    const val MIN_GRADE_LENGTH_METERS = 804.672 // 0.5 miles
    private const val MAX_SANE_GRADE_PERCENT = 60.0 // Guards against Valhalla kNoElevationData (32768)
    private const val METERS_PER_MILE = 1609.344

    private enum class GradeDirection { NONE, DOWN, UP }

    fun detectSteepGradeAlerts(
        shape: List<LatLon>,
        spans: List<EdgeGradeSpan>
    ): List<AheadAlert> {
        if (shape.size < 2 || spans.isEmpty()) return emptyList()

        val sorted = spans
            .filter { it.beginShapeIndex >= 0 && it.endShapeIndex > it.beginShapeIndex }
            .sortedBy { it.beginShapeIndex }
        if (sorted.isEmpty()) return emptyList()

        val alerts = mutableListOf<AheadAlert>()
        var activeDir = GradeDirection.NONE
        var runStartIdx = 0
        var runLengthMeters = 0.0
        var runPeakGrade = 0.0
        var alertCounter = 0

        fun flushRun() {
            if (activeDir != GradeDirection.NONE && runLengthMeters >= MIN_GRADE_LENGTH_METERS) {
                val anchor = shape.getOrNull(runStartIdx.coerceIn(0, shape.lastIndex)) ?: return
                val pct = runPeakGrade.roundToInt().coerceIn(
                    if (activeDir == GradeDirection.DOWN) 6 else 7,
                    30
                )
                val isDown = activeDir == GradeDirection.DOWN
                val (enMiles, faMiles) = formatMilesForGrade(runLengthMeters)
                val faPct = BilingualFormatter.formatNumber(pct, AppLanguage.PERSIAN)
                val titleEn = if (isDown) {
                    "Steep downgrade ahead, $pct% for $enMiles"
                } else {
                    "Steep upgrade ahead, $pct% for $enMiles"
                }
                val titleFa = if (isDown) {
                    "جلوتر سراشیبی تند است، $faPct درصد به طول $faMiles"
                } else {
                    "جلوتر سربالایی تند است، $faPct درصد به طول $faMiles"
                }
                alerts.add(
                    AheadAlert(
                        id = "steep_grade_${if (isDown) "down" else "up"}_${alertCounter++}_$runStartIdx",
                        kind = RoadAlertKind.STEEP_GRADE,
                        lat = anchor.lat,
                        lon = anchor.lon,
                        distanceMeters = 0.0,
                        titleEn = titleEn,
                        titleFa = titleFa,
                        detail = "${if (isDown) "-" else "+"}$pct% • $enMiles"
                    )
                )
            }
            activeDir = GradeDirection.NONE
            runLengthMeters = 0.0
            runPeakGrade = 0.0
        }

        for (span in sorted) {
            val (dir, gradePct) = classifySpan(span)
            val segLen = ValhallaPolylineAndGeo.shapeDistanceBetween(
                shape = shape,
                fromIdx = span.beginShapeIndex.coerceIn(0, shape.lastIndex),
                toIdx = span.endShapeIndex.coerceIn(0, shape.lastIndex)
            )
            if (dir == GradeDirection.NONE || segLen <= 0.0) {
                flushRun()
                continue
            }
            if (dir != activeDir) {
                flushRun()
                activeDir = dir
                runStartIdx = span.beginShapeIndex
                runLengthMeters = segLen
                runPeakGrade = gradePct
            } else {
                runLengthMeters += segLen
                if (gradePct > runPeakGrade) runPeakGrade = gradePct
            }
        }
        flushRun()
        return alerts
    }

    private fun classifySpan(span: EdgeGradeSpan): Pair<GradeDirection, Double> {
        val weighted = span.weightedGrade?.takeIf { abs(it) <= MAX_SANE_GRADE_PERCENT }
        val maxDown = span.maxDownwardGrade?.takeIf { abs(it) <= MAX_SANE_GRADE_PERCENT }
        val maxUp = span.maxUpwardGrade?.takeIf { abs(it) <= MAX_SANE_GRADE_PERCENT }

        if (weighted == null && maxDown == null && maxUp == null) {
            return GradeDirection.NONE to 0.0
        }

        val downPct = when {
            weighted != null && weighted <= -MIN_DOWNGRADE_PERCENT -> -weighted
            maxDown != null && maxDown <= -MIN_DOWNGRADE_PERCENT && (weighted == null || weighted <= -3.0) -> -maxDown
            else -> 0.0
        }
        val upPct = when {
            weighted != null && weighted >= MIN_UPGRADE_PERCENT -> weighted
            maxUp != null && maxUp >= MIN_UPGRADE_PERCENT && (weighted == null || weighted >= 3.0) -> maxUp
            else -> 0.0
        }

        return when {
            downPct >= MIN_DOWNGRADE_PERCENT && downPct >= upPct -> GradeDirection.DOWN to downPct
            upPct >= MIN_UPGRADE_PERCENT -> GradeDirection.UP to upPct
            else -> GradeDirection.NONE to 0.0
        }
    }

    private fun formatMilesForGrade(meters: Double): Pair<String, String> {
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.75 -> "0.5 miles" to "۰٫۵ مایل"
            miles < 1.5 -> "1 mile" to "۱ مایل"
            else -> {
                val whole = miles.roundToInt().coerceAtLeast(2)
                val faWhole = BilingualFormatter.formatNumber(whole, AppLanguage.PERSIAN)
                "$whole miles" to "$faWhole مایل"
            }
        }
    }
}
