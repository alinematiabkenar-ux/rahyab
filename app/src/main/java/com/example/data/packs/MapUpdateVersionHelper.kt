package com.example.data.packs

import com.example.i18n.AppLanguage

/**
 * Pure JVM helper for comparing monthly/dated/semver map pack version strings
 * and formatting localized update notification messages.
 */
object MapUpdateVersionHelper {

    private val separatorRegex = Regex("[._\\-\\s/]+")

    /**
     * Returns true when [availableVersion] is strictly newer than [installedVersion].
     * Handles dated versions ("2026.05.01", "2026-05", "20260501"), semver ("2.1.0"),
     * optional leading "v", and safe fallback for non-numeric strings.
     * Returns false for blank inputs, equal versions, or locally imported ("local") packs.
     */
    fun isNewerVersion(installedVersion: String?, availableVersion: String?): Boolean {
        val inst = cleanVersion(installedVersion)
        val avail = cleanVersion(availableVersion)
        if (inst.isEmpty() || avail.isEmpty()) return false
        if (inst.equals("local", ignoreCase = true)) return false
        if (inst.equals(avail, ignoreCase = true)) return false

        val instParts = parseNumericParts(inst)
        val availParts = parseNumericParts(avail)
        if (instParts != null && availParts != null) {
            val maxLen = maxOf(instParts.size, availParts.size)
            for (i in 0 until maxLen) {
                val a = availParts.getOrElse(i) { 0L }
                val b = instParts.getOrElse(i) { 0L }
                if (a != b) return a > b
            }
            return false
        }

        // Mixed or opaque version strings: compare lexicographically
        return avail.compareTo(inst, ignoreCase = true) > 0
    }

    /**
     * Formats the low-importance map update notification body in English or Persian.
     */
    fun formatUpdateNotificationBody(stateNames: List<String>, language: AppLanguage): String {
        val cleaned = stateNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) {
            return if (language == AppLanguage.PERSIAN) {
                "نقشهٔ تازه آماده است"
            } else {
                "New map update is available"
            }
        }
        return if (cleaned.size == 1) {
            val state = cleaned.first()
            if (language == AppLanguage.PERSIAN) {
                "نقشهٔ تازهٔ $state آماده است"
            } else {
                "New map for $state is available"
            }
        } else {
            val shown = cleaned.take(3)
            val suffix = if (cleaned.size > 3) {
                val more = cleaned.size - 3
                if (language == AppLanguage.PERSIAN) " و $more مورد دیگر" else " +$more more"
            } else ""
            if (language == AppLanguage.PERSIAN) {
                "نقشه‌های تازهٔ ${shown.joinToString("، ")}$suffix آماده هستند"
            } else {
                "New maps for ${shown.joinToString(", ")}$suffix are available"
            }
        }
    }

    private fun cleanVersion(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.length > 1 && (trimmed[0] == 'v' || trimmed[0] == 'V') && trimmed[1].isDigit()) {
            trimmed.substring(1)
        } else {
            trimmed
        }
    }

    private fun parseNumericParts(version: String): List<Long>? {
        val tokens = separatorRegex.split(version).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val nums = ArrayList<Long>(tokens.size)
        for (token in tokens) {
            val value = token.toLongOrNull() ?: return null
            nums.add(value)
        }
        return nums
    }
}
