package com.example.navigation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes Valhalla official bitmask turn lanes as well as legacy/Mapbox-style lane JSON arrays.
 */
internal object ValhallaTurnLaneDecoder {

    fun parseTurnLanes(maneuverObj: JSONObject, maneuverType: ManeuverType? = null): List<LaneInfo> {
        val rawArray = maneuverObj.optJSONArray("lanes")
            ?: maneuverObj.optJSONArray("turn_lanes")
            ?: return emptyList()
        return parseLaneJsonArray(rawArray, maneuverType)
    }

    fun parseLaneJsonArray(rawArray: JSONArray, maneuverType: ManeuverType? = null): List<LaneInfo> {
        if (rawArray.length() == 0) return emptyList()

        // Detect whether legacy boolean-style payloads explicitly distinguish `active` vs `valid`
        var anyLegacyExplicitActive = false
        for (i in 0 until rawArray.length()) {
            val laneObj = rawArray.optJSONObject(i) ?: continue
            val rawActive = laneObj.opt("active")
            if ((rawActive is Boolean && rawActive) ||
                (rawActive is Number && rawActive.toInt() != 0) ||
                laneObj.optInt("active_direction", 0) > 0 ||
                laneObj.optString("active_direction", "").isNotBlank()
            ) {
                anyLegacyExplicitActive = true
                break
            }
        }

        val preferredDirs = maneuverType
            ?.let { ValhallaApproachLaneResolver.preferredDirectionsForManeuver(it) }
            .orEmpty()
        val result = mutableListOf<LaneInfo>()
        for (i in 0 until rawArray.length()) {
            val laneObj = rawArray.optJSONObject(i) ?: continue
            val dirs = mutableListOf<LaneDirection>()
            val activeDirs = mutableListOf<LaneDirection>()

            // 1. Official Valhalla format: "directions" is an integer bitmask (always present).
            //    Fall back to JSON array, semicolon/pipe-delimited string, or "directions_mask".
            val rawDirections = laneObj.opt("directions")
            val directionsInt = if (rawDirections is Number) laneObj.optInt("directions", 0) else 0
            if (directionsInt > 0) {
                dirs.addAll(decodeValhallaTurnLaneMask(directionsInt))
            } else {
                val indicationsArr = laneObj.optJSONArray("directions")
                    ?: laneObj.optJSONArray("indications")
                if (indicationsArr != null) {
                    for (j in 0 until indicationsArr.length()) {
                        parseLaneDirectionToken(indicationsArr.optString(j))?.let { dirs.add(it) }
                    }
                } else if (rawDirections is String && rawDirections.isNotBlank()) {
                    rawDirections.split(';', '|', ',').forEach { token ->
                        parseLaneDirectionToken(token)?.let { dirs.add(it) }
                    }
                } else if (laneObj.has("directions_mask")) {
                    dirs.addAll(decodeValhallaTurnLaneMask(laneObj.optInt("directions_mask", 0)))
                }
            }

            // 2. Official Valhalla format: "active" and "valid" are optional integer bitmasks.
            //    - Best lane (`ACTIVE`): `active` bitmask is non-zero
            //    - Allowed lane (`VALID`): `valid` bitmask is non-zero (and `active` is zero)
            //    - activeDirections = decode(active) if non-zero, else decode(valid)
            val rawActive = laneObj.opt("active")
            val rawValid = laneObj.opt("valid")
            val activeBitmask = if (rawActive is Number) laneObj.optInt("active", 0) else 0
            val validBitmask = if (rawValid is Number) laneObj.optInt("valid", 0) else 0
            val isOfficialBitmaskLane = (rawDirections is Number) || (rawActive is Number) || (rawValid is Number)

            val isActive: Boolean
            val isValid: Boolean
            if (isOfficialBitmaskLane && rawActive !is Boolean && rawValid !is Boolean) {
                if (activeBitmask != 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(activeBitmask))
                } else if (validBitmask != 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(validBitmask))
                }
                isActive = activeBitmask != 0
                isValid = validBitmask != 0 || activeBitmask != 0
            } else {
                // Fallback for legacy / Mapbox-style JSON payloads (boolean valid/active + string active_direction)
                val activeDirMask = laneObj.optInt("active_direction", 0)
                if (activeDirMask > 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(activeDirMask))
                } else {
                    val activeStr = laneObj.optString("active_direction", "")
                    parseLaneDirectionToken(activeStr)?.let { activeDirs.add(it) }
                }
                val validIndication = laneObj.optString("valid_indication", "")
                if (validIndication.isNotBlank()) {
                    parseLaneDirectionToken(validIndication)?.let { activeDirs.add(it) }
                }
                val activeDirsArr = laneObj.optJSONArray("active_directions")
                if (activeDirsArr != null) {
                    for (j in 0 until activeDirsArr.length()) {
                        parseLaneDirectionToken(activeDirsArr.optString(j))?.let { activeDirs.add(it) }
                    }
                }

                val hasActiveBool = rawActive is Boolean
                val explicitActive = (rawActive as? Boolean) == true
                val explicitValid = (rawValid as? Boolean) == true
                val hasExplicitActiveDir = activeDirMask > 0 || laneObj.optString("active_direction", "").isNotBlank()

                when {
                    hasActiveBool -> {
                        isActive = explicitActive
                        isValid = explicitValid || explicitActive
                    }
                    hasExplicitActiveDir -> {
                        isActive = true
                        isValid = true
                    }
                    explicitValid -> {
                        isActive = !anyLegacyExplicitActive
                        isValid = true
                    }
                    else -> {
                        isActive = false
                        isValid = false
                    }
                }
            }

            val isRecommended = isActive || isValid
            val level = when {
                isActive -> LaneHighlightTier.ACTIVE
                isValid -> LaneHighlightTier.VALID
                else -> LaneHighlightTier.INACTIVE
            }

            if (dirs.isNotEmpty()) {
                val distinctDirs = dirs.distinct()
                val resolvedActiveDirs = when {
                    activeDirs.isNotEmpty() -> activeDirs.distinct().filter { it in distinctDirs }.ifEmpty { activeDirs.distinct() }
                    isRecommended && distinctDirs.size > 1 && preferredDirs.isNotEmpty() ->
                        distinctDirs.filter { it in preferredDirs }.ifEmpty { distinctDirs }
                    isRecommended -> distinctDirs
                    else -> emptyList()
                }
                result.add(
                    LaneInfo(
                        directions = distinctDirs,
                        activeDirections = resolvedActiveDirs,
                        isRecommended = isRecommended,
                        isActive = isActive,
                        isValid = isValid,
                        level = level
                    )
                )
            }
        }
        return result
    }

    private fun parseLaneDirectionToken(token: String): LaneDirection? {
        return when (token.trim().lowercase()) {
            "sharp_left", "sharp left" -> LaneDirection.SHARP_LEFT
            "left" -> LaneDirection.LEFT
            "slight_left", "slight left" -> LaneDirection.SLIGHT_LEFT
            "through", "straight", "none" -> LaneDirection.THROUGH
            "slight_right", "slight right" -> LaneDirection.SLIGHT_RIGHT
            "right" -> LaneDirection.RIGHT
            "sharp_right", "sharp right" -> LaneDirection.SHARP_RIGHT
            "reverse", "uturn", "u_turn", "uturn_left", "reverse_left", "u-turn" -> LaneDirection.U_TURN_LEFT
            "uturn_right", "reverse_right" -> LaneDirection.U_TURN_RIGHT
            "merge_to_left", "merge_left", "merge left" -> LaneDirection.MERGE_LEFT
            "merge_to_right", "merge_right", "merge right" -> LaneDirection.MERGE_RIGHT
            else -> null
        }
    }

    /**
     * Decodes an official Valhalla turn lane bitmask into ordered [LaneDirection] values:
     * 1 None, 2 Through, 4 SharpLeft, 8 Left, 16 SlightLeft, 32 SlightRight,
     * 64 Right, 128 SharpRight, 256 Reverse (U-turn), 512 MergeToLeft, 1024 MergeToRight.
     */
    fun decodeValhallaTurnLaneMask(mask: Int): List<LaneDirection> {
        val list = mutableListOf<LaneDirection>()
        if ((mask and 4) != 0) list.add(LaneDirection.SHARP_LEFT)
        if ((mask and 8) != 0) list.add(LaneDirection.LEFT)
        if ((mask and 16) != 0) list.add(LaneDirection.SLIGHT_LEFT)
        if ((mask and 2) != 0) list.add(LaneDirection.THROUGH)
        if ((mask and 32) != 0) list.add(LaneDirection.SLIGHT_RIGHT)
        if ((mask and 64) != 0) list.add(LaneDirection.RIGHT)
        if ((mask and 128) != 0) list.add(LaneDirection.SHARP_RIGHT)
        if ((mask and 256) != 0) list.add(LaneDirection.U_TURN_LEFT)
        if ((mask and 512) != 0) list.add(LaneDirection.MERGE_LEFT)
        if ((mask and 1024) != 0) list.add(LaneDirection.MERGE_RIGHT)
        // Valhalla bit 1 = kNone (unmarked OSM lane, e.g. `turn:lanes=left|none|right`):
        // if no directional bits are set, represent it as a through lane so the physical lane box is not dropped.
        if (list.isEmpty() && (mask and 1) != 0) {
            list.add(LaneDirection.THROUGH)
        }
        return list
    }
}
