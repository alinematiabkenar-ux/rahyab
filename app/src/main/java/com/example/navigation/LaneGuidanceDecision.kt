package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.map.LaneHighlightColor

enum class LaneDisplaySurface {
    PHONE_SCREEN,
    ANDROID_AUTO,
    SCREEN_OFF
}

data class LaneSlotDecision(
    val index: Int,
    val directions: List<LaneDirection>,
    val activeDirections: List<LaneDirection>,
    val tier: LaneHighlightTier,
    val isUnmarked: Boolean
)

/**
 * Single shared lane-guidance decision model consumed by the phone lane diagram,
 * top maneuver banner, map lane overlay, Junction View, Turn Focus voice cues, and Android Auto.
 *
 * Guarantees:
 * - Preserves physical Left-to-Right lane order across English and Persian (including unmarked slots).
 * - Separates preferred (`ACTIVE`) lanes from merely allowed (`VALID`) lanes.
 * - Skips lane-change instructions when all lanes are equally suitable.
 * - Refuses to invent lane advice from lane count alone or uncertain/contradictory data.
 * - Never claims to know the vehicle's current lane from GPS.
 * - Speaks "Get in the blue lane" ONLY when a blue target lane is visibly highlighted on the
 *   active display; otherwise speaks physical lane position (e.g., "Use the second lane from the right.").
 */
data class LaneGuidanceDecision(
    val maneuverIndex: Int,
    val maneuverType: ManeuverType,
    val slots: List<LaneSlotDecision>,
    val preferredIndices: List<Int>,
    val otherValidIndices: List<Int>,
    val confidence: LaneConfidence,
    val reasons: List<String>,
    val suggestedSide: LaneTargetSide?,
    val hasActionableLaneGuidance: Boolean,
    val shouldShowFullDiagram: Boolean,
    val shouldSkipLaneChangeInstruction: Boolean,
    val positionTextEn: String?,
    val positionTextFa: String?,
    val fallbackManeuverCueEn: String,
    val fallbackManeuverCueFa: String
) {
    val primaryTargetIndices: List<Int>
        get() = preferredIndices.ifEmpty { otherValidIndices }

    fun positionText(language: AppLanguage): String? =
        if (language == AppLanguage.PERSIAN) positionTextFa else positionTextEn

    fun fallbackManeuverCue(language: AppLanguage): String =
        if (language == AppLanguage.PERSIAN) fallbackManeuverCueFa else fallbackManeuverCueEn

    /**
     * Resolves the spoken lane cue right before speech starts.
     *
     * Rules:
     * - If all lanes are equally suitable -> returns null (skip lane-change instruction).
     * - If lane data is missing, lane-count-only, or uncertain (LOW confidence) -> returns a
     *   supported maneuver cue (e.g., "Take the right exit.").
     * - If 2+ lanes are equally recommended -> speaks the exact multi-lane physical phrase
     *   (e.g., "Use either of the two right lanes.").
     * - Says "Get in the blue lane." ONLY when:
     *     1. A single preferred target lane is highlighted on the active phone display,
     *     2. The screen is on and the lane diagram is currently visible,
     *     3. The user's highlight color is BLUE, and
     *     4. Android Auto host is NOT controlling lane colors.
     * - Otherwise speaks the physical position (e.g., "Use the second lane from the right.").
     */
    fun resolveVoiceCue(
        surface: LaneDisplaySurface,
        isDiagramVisibleOnActiveDisplay: Boolean,
        highlightColor: LaneHighlightColor,
        isCarHostColorControlled: Boolean,
        language: AppLanguage
    ): String? {
        if (shouldSkipLaneChangeInstruction) return null
        if (!hasActionableLaneGuidance || confidence == LaneConfidence.LOW) {
            return fallbackManeuverCue(language)
        }
        val targets = primaryTargetIndices
        if (targets.isEmpty()) return fallbackManeuverCue(language)

        // Two or more equally recommended lanes: always state the multi-lane choice explicitly.
        if (targets.size >= 2) {
            return positionText(language) ?: fallbackManeuverCue(language)
        }

        val canReferenceBlueTarget = surface == LaneDisplaySurface.PHONE_SCREEN &&
            isDiagramVisibleOnActiveDisplay &&
            highlightColor == LaneHighlightColor.BLUE &&
            !isCarHostColorControlled

        if (canReferenceBlueTarget) {
            return if (language == AppLanguage.PERSIAN) {
                "به خط آبی بروید."
            } else {
                "Get in the blue lane."
            }
        }

        return formatImperativePositionCue(targets, slots.size, language)
            ?: fallbackManeuverCue(language)
    }

    companion object {
        fun evaluate(
            maneuver: RouteManeuver,
            edgeLaneCountBeforeManeuver: Int? = null,
            hpmsThroughLanes: Int? = null,
            isReportedWrongByUser: Boolean = false
        ): LaneGuidanceDecision {
            val lanes = maneuver.lanes
            val fallbackEn = buildFallbackManeuverCue(maneuver, AppLanguage.ENGLISH)
            val fallbackFa = buildFallbackManeuverCue(maneuver, AppLanguage.PERSIAN)

            val slots = lanes.mapIndexed { idx, lane ->
                val unmarked = lane.isUnmarkedSlot || lane.directions.isEmpty()
                LaneSlotDecision(
                    index = idx,
                    directions = lane.directions,
                    activeDirections = lane.activeDirections,
                    tier = lane.highlightTier,
                    isUnmarked = unmarked
                )
            }

            // Lane count alone or completely unmarked lanes cannot produce lane-specific guidance.
            val hasAnyDirectionalMarkings = slots.any { !it.isUnmarked && it.directions.isNotEmpty() }
            if (slots.size < 2 || !hasAnyDirectionalMarkings) {
                return LaneGuidanceDecision(
                    maneuverIndex = maneuver.index,
                    maneuverType = maneuver.type,
                    slots = slots,
                    preferredIndices = emptyList(),
                    otherValidIndices = emptyList(),
                    confidence = LaneConfidence.LOW,
                    reasons = listOf(if (slots.size < 2) "too_few_lanes" else "unmarked_lanes_only"),
                    suggestedSide = LaneGuidanceValidator.resolveSuggestedSide(maneuver.type, emptyList(), slots.size),
                    hasActionableLaneGuidance = false,
                    shouldShowFullDiagram = false,
                    shouldSkipLaneChangeInstruction = false,
                    positionTextEn = null,
                    positionTextFa = null,
                    fallbackManeuverCueEn = fallbackEn,
                    fallbackManeuverCueFa = fallbackFa
                )
            }

            val preferred = slots.filter { it.tier == LaneHighlightTier.ACTIVE }.map { it.index }
            val otherValid = slots.filter { it.tier == LaneHighlightTier.VALID }.map { it.index }
            val allRecommendedCount = slots.count { it.tier != LaneHighlightTier.INACTIVE }

            // If all lanes are equally suitable:
            // - When every lane has an explicit turn/exit arrow matching a directional turn/exit
            //   (e.g., 2-lane one-way street where both lanes turn right), show "Any lane." / "هر خطی."
            // - Otherwise (straight/through all-valid), skip lane-change guidance cleanly.
            val allEquallySuitable = (preferred.size == slots.size) ||
                (preferred.isEmpty() && allRecommendedCount == slots.size)
            if (allEquallySuitable) {
                val allTurnForManeuver = isAllLanesTurnUseful(maneuver.type, slots)
                return LaneGuidanceDecision(
                    maneuverIndex = maneuver.index,
                    maneuverType = maneuver.type,
                    slots = slots,
                    preferredIndices = preferred,
                    otherValidIndices = otherValid,
                    confidence = LaneConfidence.HIGH,
                    reasons = listOf("all_lanes_equally_suitable"),
                    suggestedSide = if (allTurnForManeuver) {
                        LaneGuidanceValidator.resolveSuggestedSide(maneuver.type, slots.indices.toList(), slots.size)
                    } else null,
                    hasActionableLaneGuidance = allTurnForManeuver,
                    shouldShowFullDiagram = allTurnForManeuver,
                    shouldSkipLaneChangeInstruction = !allTurnForManeuver,
                    positionTextEn = if (allTurnForManeuver) "Any lane." else null,
                    positionTextFa = if (allTurnForManeuver) "هر خطی." else null,
                    fallbackManeuverCueEn = fallbackEn,
                    fallbackManeuverCueFa = fallbackFa
                )
            }

            val validation = LaneGuidanceValidator.validate(
                maneuverType = maneuver.type,
                lanes = lanes,
                laneSource = maneuver.laneSource,
                carriedDistanceMeters = maneuver.carriedLaneDistanceMeters,
                carriedHadInterveningIntersection = maneuver.carriedLaneHadInterveningIntersection,
                edgeLaneCountBeforeManeuver = edgeLaneCountBeforeManeuver,
                isHighway = maneuver.isHighwayRoad || maneuver.isHighwayExitOrSplit,
                hpmsThroughLanes = hpmsThroughLanes,
                isReportedWrongByUser = isReportedWrongByUser
            )

            val actionable = validation.hasAdvice && validation.confidence != LaneConfidence.LOW
            val targets = if (preferred.isNotEmpty()) preferred else otherValid
            val posEn = if (actionable) formatDisplayPositionText(targets, slots.size, AppLanguage.ENGLISH) else null
            val posFa = if (actionable) formatDisplayPositionText(targets, slots.size, AppLanguage.PERSIAN) else null

            return LaneGuidanceDecision(
                maneuverIndex = maneuver.index,
                maneuverType = maneuver.type,
                slots = slots,
                preferredIndices = preferred,
                otherValidIndices = otherValid,
                confidence = validation.confidence,
                reasons = validation.reasons,
                suggestedSide = validation.suggestedSide,
                hasActionableLaneGuidance = actionable,
                shouldShowFullDiagram = actionable,
                shouldSkipLaneChangeInstruction = false,
                positionTextEn = posEn,
                positionTextFa = posFa,
                fallbackManeuverCueEn = fallbackEn,
                fallbackManeuverCueFa = fallbackFa
            )
        }

        fun formatDisplayPositionText(
            targets: List<Int>,
            totalLanes: Int,
            language: AppLanguage
        ): String? {
            if (targets.isEmpty() || totalLanes < 2) return null
            val sorted = targets.sorted()
            val count = sorted.size
            val first = sorted.first()
            val last = sorted.last()
            val fromLeftOneBased = first + 1
            val fromRightOneBased = totalLanes - last

            if (count == 1) {
                return formatSingleLaneLabel(first, totalLanes, language)
            }

            val isContiguous = (last - first + 1) == count
            if (isContiguous && last == totalLanes - 1) {
                return if (count == 2) {
                    if (language == AppLanguage.PERSIAN) {
                        "از هر یک از دو خط سمت راست استفاده کنید."
                    } else {
                        "Use either of the two right lanes."
                    }
                } else {
                    if (language == AppLanguage.PERSIAN) {
                        "از یکی از ${persianCount(count)} خط سمت راست استفاده کنید."
                    } else {
                        "Use any of the ${englishCount(count)} right lanes."
                    }
                }
            }
            if (isContiguous && first == 0) {
                return if (count == 2) {
                    if (language == AppLanguage.PERSIAN) {
                        "از هر یک از دو خط سمت چپ استفاده کنید."
                    } else {
                        "Use either of the two left lanes."
                    }
                } else {
                    if (language == AppLanguage.PERSIAN) {
                        "از یکی از ${persianCount(count)} خط سمت چپ استفاده کنید."
                    } else {
                        "Use any of the ${englishCount(count)} left lanes."
                    }
                }
            }
            if (isContiguous) {
                return if (fromRightOneBased <= fromLeftOneBased) {
                    if (language == AppLanguage.PERSIAN) {
                        "از خطوط ${persianNum(fromRightOneBased)} تا ${persianNum(totalLanes - first)} از سمت راست استفاده کنید."
                    } else {
                        "Use lanes $fromRightOneBased to ${totalLanes - first} from the right."
                    }
                } else {
                    if (language == AppLanguage.PERSIAN) {
                        "از خطوط ${persianNum(first + 1)} تا ${persianNum(last + 1)} از سمت چپ استفاده کنید."
                    } else {
                        "Use lanes ${first + 1} to ${last + 1} from the left."
                    }
                }
            }
            return if (language == AppLanguage.PERSIAN) {
                "از خطوط مشخص‌شده استفاده کنید."
            } else {
                "Use one of the highlighted lanes."
            }
        }

        fun formatImperativePositionCue(
            targets: List<Int>,
            totalLanes: Int,
            language: AppLanguage
        ): String? {
            if (targets.isEmpty() || totalLanes < 2) return null
            if (targets.size >= 2) return formatDisplayPositionText(targets, totalLanes, language)
            val label = formatSingleLaneLabel(targets.first(), totalLanes, language)?.trimEnd('.') ?: return null
            return if (language == AppLanguage.PERSIAN) {
                "از $label استفاده کنید."
            } else {
                "Use the ${label.replaceFirstChar { it.lowercase() }}."
            }
        }

        private fun formatSingleLaneLabel(index: Int, total: Int, language: AppLanguage): String? {
            if (index !in 0 until total || total < 2) return null
            val posFromLeft = index + 1
            val posFromRight = total - index
            return when {
                index == 0 ->
                    if (language == AppLanguage.PERSIAN) "خط سمت چپ." else "Left lane."
                index == total - 1 ->
                    if (language == AppLanguage.PERSIAN) "خط سمت راست." else "Right lane."
                total == 3 && index == 1 ->
                    if (language == AppLanguage.PERSIAN) "خط میانی." else "Middle lane."
                posFromRight <= posFromLeft -> {
                    if (language == AppLanguage.PERSIAN) {
                        "خط ${persianOrdinal(posFromRight)} از سمت راست."
                    } else {
                        "${englishOrdinalCap(posFromRight)} lane from the right."
                    }
                }
                else -> {
                    if (language == AppLanguage.PERSIAN) {
                        "خط ${persianOrdinal(posFromLeft)} از سمت چپ."
                    } else {
                        "${englishOrdinalCap(posFromLeft)} lane from the left."
                    }
                }
            }
        }

        private fun buildFallbackManeuverCue(maneuver: RouteManeuver, language: AppLanguage): String {
            val exitNum = maneuver.signpost?.primaryExitNumber?.takeIf { it.isNotBlank() }
            val road = maneuver.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
            return if (language == AppLanguage.PERSIAN) {
                when (maneuver.type) {
                    ManeuverType.EXIT_RIGHT -> if (exitNum != null) "از خروجی $exitNum در سمت راست خارج شوید." else "از خروجی سمت راست خارج شوید."
                    ManeuverType.EXIT_LEFT -> if (exitNum != null) "از خروجی $exitNum در سمت چپ خارج شوید." else "از خروجی سمت چپ خارج شوید."
                    ManeuverType.RAMP_RIGHT -> if (road != null) "از رمپ سمت راست وارد $road شوید." else "وارد رمپ سمت راست شوید."
                    ManeuverType.RAMP_LEFT -> if (road != null) "از رمپ سمت چپ وارد $road شوید." else "وارد رمپ سمت چپ شوید."
                    ManeuverType.STAY_RIGHT -> if (road != null) "در سمت راست به سمت $road بمانید." else "در سمت راست بمانید."
                    ManeuverType.STAY_LEFT -> if (road != null) "در سمت چپ به سمت $road بمانید." else "در سمت چپ بمانید."
                    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT ->
                        if (road != null) "به راست بپیچید و وارد $road شوید." else "به راست بپیچید."
                    ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT ->
                        if (road != null) "به چپ بپیچید و وارد $road شوید." else "به چپ بپیچید."
                    ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "دور بزنید."
                    else -> if (road != null) "در $road ادامه دهید." else "مسیر را ادامه دهید."
                }
            } else {
                when (maneuver.type) {
                    ManeuverType.EXIT_RIGHT -> if (exitNum != null) "Take exit $exitNum on the right." else "Take the right exit."
                    ManeuverType.EXIT_LEFT -> if (exitNum != null) "Take exit $exitNum on the left." else "Take the left exit."
                    ManeuverType.RAMP_RIGHT -> if (road != null) "Take the right ramp onto $road." else "Take the right ramp."
                    ManeuverType.RAMP_LEFT -> if (road != null) "Take the left ramp onto $road." else "Take the left ramp."
                    ManeuverType.STAY_RIGHT -> if (road != null) "Keep right onto $road." else "Keep right."
                    ManeuverType.STAY_LEFT -> if (road != null) "Keep left onto $road." else "Keep left."
                    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT ->
                        if (road != null) "Turn right on $road." else "Turn right."
                    ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT ->
                        if (road != null) "Turn left on $road." else "Turn left."
                    ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "Make a U-turn."
                    else -> if (road != null) "Continue on $road." else "Continue ahead."
                }
            }
        }

        private fun englishOrdinalCap(n: Int): String = when (n) {
            1 -> "First"
            2 -> "Second"
            3 -> "Third"
            4 -> "Fourth"
            5 -> "Fifth"
            6 -> "Sixth"
            7 -> "Seventh"
            8 -> "Eighth"
            else -> "${n}th"
        }

        private fun englishCount(n: Int): String = when (n) {
            2 -> "two"
            3 -> "three"
            4 -> "four"
            else -> n.toString()
        }

        private fun persianOrdinal(n: Int): String = when (n) {
            1 -> "اول"
            2 -> "دوم"
            3 -> "سوم"
            4 -> "چهارم"
            5 -> "پنجم"
            6 -> "ششم"
            7 -> "هفتم"
            8 -> "هشتم"
            else -> persianNum(n)
        }

        private fun persianCount(n: Int): String = when (n) {
            2 -> "دو"
            3 -> "سه"
            4 -> "چهار"
            else -> persianNum(n)
        }

        private fun isAllLanesTurnUseful(type: ManeuverType, slots: List<LaneSlotDecision>): Boolean {
            val turnDirs = when (type) {
                ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT,
                ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT ->
                    setOf(LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT, LaneDirection.SLIGHT_RIGHT)
                ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT,
                ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT ->
                    setOf(LaneDirection.LEFT, LaneDirection.SHARP_LEFT, LaneDirection.SLIGHT_LEFT)
                else -> return false
            }
            return slots.all { slot -> !slot.isUnmarked && slot.directions.any { it in turnDirs } }
        }

        private fun persianNum(n: Int): String {
            val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
            return n.toString().map { ch -> if (ch in '0'..'9') digits[ch - '0'] else ch }.joinToString("")
        }
    }
}
