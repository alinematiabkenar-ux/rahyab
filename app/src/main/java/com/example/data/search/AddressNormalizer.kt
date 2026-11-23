package com.example.data.search

/**
 * Normalizes US street addresses and parses house-number / street / city / ZIP queries.
 * Digit, suffix, and directional normalization intentionally mirrors `data-pipeline/address_utils.py`
 * so query tokens match `addresses_fts` identically across English and Persian inputs.
 */
object AddressNormalizer {

    data class ParsedAddressQuery(
        val rawQuery: String,
        val normalizedQuery: String,
        val isAddressLike: Boolean,
        val houseNumber: String,
        val numericHouseNumber: Long?,
        val streetRaw: String,
        val normalizedStreet: String,
        val cityHint: String,
        val stateHint: String,
        val zipCode: String
    )

    private val streetSuffixMap = mapOf(
        "street" to "st", "st" to "st", "str" to "st",
        "avenue" to "ave", "ave" to "ave", "av" to "ave",
        "road" to "rd", "rd" to "rd",
        "drive" to "dr", "dr" to "dr", "drv" to "dr",
        "boulevard" to "blvd", "blvd" to "blvd", "boul" to "blvd",
        "lane" to "ln", "ln" to "ln",
        "court" to "ct", "crt" to "ct", "ct" to "ct",
        "place" to "pl", "pl" to "pl",
        "highway" to "hwy", "hwy" to "hwy",
        "parkway" to "pkwy", "pkwy" to "pkwy", "pky" to "pkwy",
        "circle" to "cir", "cir" to "cir",
        "terrace" to "ter", "ter" to "ter",
        "trail" to "trl", "trl" to "trl",
        "way" to "way", "wy" to "way"
    )

    private val directionalMap = mapOf(
        "north" to "n", "n" to "n",
        "south" to "s", "s" to "s",
        "east" to "e", "e" to "e",
        "west" to "w", "w" to "w",
        "northeast" to "ne", "ne" to "ne",
        "northwest" to "nw", "nw" to "nw",
        "southeast" to "se", "se" to "se",
        "southwest" to "sw", "sw" to "sw"
    )

    private val usStateCodes = setOf(
        "al", "ak", "az", "ar", "ca", "co", "ct", "de", "fl", "ga",
        "hi", "id", "il", "in", "ia", "ks", "ky", "la", "me", "md",
        "ma", "mi", "mn", "ms", "mo", "mt", "ne", "nv", "nh", "nj",
        "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri", "sc",
        "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy", "dc"
    )

    private val zipRegex = Regex("""\b(\d{5})(?:-\d{4})?\b""")
    private val leadingNumberRegex = Regex("""^(\d+[a-zA-Z]?(?:-\d+[a-zA-Z]?)?)\s+(.+)$""")
    private val embeddedNumberStreetRegex = Regex("""\b(\d+[a-zA-Z]?)\s+([a-zA-Z0-9][a-zA-Z0-9\s.'-]{1,})$""")
    private val numericPrefixRegex = Regex("""^(\d+)""")

    /** Converts Persian (۰-۹) and Arabic-Indic (٠-٩) digits to ASCII 0-9. */
    fun normalizeDigits(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - 0x06F0)).toChar())
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - 0x0660)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeHouseNumber(number: String): String {
        val ascii = normalizeDigits(number).trim().lowercase()
        return ascii.replace(Regex("""\s+"""), "")
    }

    fun extractLeadingLong(houseNumber: String): Long? {
        val norm = normalizeHouseNumber(houseNumber)
        return numericPrefixRegex.find(norm)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /**
     * Normalizes US street suffixes and directional words identically to Python
     * `data-pipeline/address_utils.normalize_street`:
     * - Persian/Arabic digits -> ASCII 0-9, lowercased
     * - All non-word characters (and underscores) become spaces
     * - Suffix words abbreviated at ANY position after the first token (`idx > 0`)
     * - Directionals abbreviated at start, end, or immediately before a trailing suffix
     */
    fun normalizeStreet(street: String): String {
        val raw = normalizeDigits(street).trim().lowercase()
        if (raw.isEmpty()) return ""
        val tokens = raw
            .replace('_', ' ')
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val n = tokens.size
        val out = ArrayList<String>(n)
        for ((idx, tok) in tokens.withIndex()) {
            val mapped = when {
                directionalMap.containsKey(tok) && (idx == 0 || idx == n - 1) ->
                    directionalMap.getValue(tok)
                streetSuffixMap.containsKey(tok) && idx > 0 ->
                    streetSuffixMap.getValue(tok)
                directionalMap.containsKey(tok) && idx > 0 && idx == n - 2 &&
                    streetSuffixMap.containsKey(tokens.last()) ->
                    directionalMap.getValue(tok)
                else -> tok
            }
            out.add(mapped)
        }
        return out.joinToString(" ")
    }

    fun containsStreetWord(text: String): Boolean {
        val words = normalizeDigits(text)
            .lowercase()
            .replace(Regex("""[.,#/]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        return words.any { streetSuffixMap.containsKey(it) }
    }

    fun parseAddressQuery(rawQuery: String): ParsedAddressQuery {
        val normalized = normalizeDigits(rawQuery)
            .replace('،', ',')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isEmpty()) {
            return ParsedAddressQuery(
                rawQuery = rawQuery,
                normalizedQuery = "",
                isAddressLike = false,
                houseNumber = "",
                numericHouseNumber = null,
                streetRaw = "",
                normalizedStreet = "",
                cityHint = "",
                stateHint = "",
                zipCode = ""
            )
        }

        // Extract optional 5-digit US ZIP code
        val zipMatch = zipRegex.find(normalized)
        val zipCode = zipMatch?.groupValues?.getOrNull(1).orEmpty()
        val withoutZip = if (zipMatch != null) {
            normalized.removeRange(zipMatch.range).replace(Regex("""\s+"""), " ").trim().trimEnd(',')
        } else {
            normalized
        }

        // Split comma-separated parts: e.g. "1585 E 13th Ave, Eugene, OR"
        val commaParts = withoutZip.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val streetSegment = commaParts.firstOrNull().orEmpty()
        var cityHint = ""
        var stateHint = ""
        if (commaParts.size >= 2) {
            val tailTokens = commaParts.drop(1).joinToString(" ")
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }
            if (tailTokens.isNotEmpty() && usStateCodes.contains(tailTokens.last().lowercase())) {
                stateHint = tailTokens.last().lowercase()
                cityHint = tailTokens.dropLast(1).joinToString(" ").lowercase()
            } else {
                cityHint = tailTokens.joinToString(" ").lowercase()
            }
        }

        var houseNumber = ""
        var streetRaw = streetSegment
        val leadingMatch = leadingNumberRegex.matchEntire(streetSegment)
        if (leadingMatch != null) {
            houseNumber = normalizeHouseNumber(leadingMatch.groupValues[1])
            streetRaw = leadingMatch.groupValues[2].trim()
        } else {
            val embedded = embeddedNumberStreetRegex.find(streetSegment)
            if (embedded != null && containsStreetWord(embedded.groupValues[2])) {
                houseNumber = normalizeHouseNumber(embedded.groupValues[1])
                streetRaw = embedded.groupValues[2].trim()
            }
        }

        // If no comma was provided, peel trailing US state code if preceded by street suffix
        if (commaParts.size == 1 && stateHint.isEmpty() && streetRaw.isNotEmpty()) {
            val tokens = streetRaw.split(Regex("""\s+"""))
            if (tokens.size >= 3 && usStateCodes.contains(tokens.last().lowercase())) {
                val beforeState = tokens.dropLast(1)
                if (containsStreetWord(beforeState.joinToString(" "))) {
                    stateHint = tokens.last().lowercase()
                    streetRaw = beforeState.joinToString(" ")
                }
            }
        }

        // If street has a suffix followed by city word(s) without comma: e.g. "1585 E 13th Ave Eugene"
        if (commaParts.size == 1 && cityHint.isEmpty() && streetRaw.isNotEmpty()) {
            val tokens = streetRaw.split(Regex("""\s+"""))
            val suffixIdx = tokens.indexOfLast {
                streetSuffixMap.containsKey(it.lowercase().trimEnd('.'))
            }
            if (suffixIdx in 1 until tokens.lastIndex) {
                val afterSuffix = tokens.subList(suffixIdx + 1, tokens.size)
                val firstAfter = afterSuffix.first().lowercase().trimEnd('.')
                if (!directionalMap.containsKey(firstAfter)) {
                    streetRaw = tokens.subList(0, suffixIdx + 1).joinToString(" ")
                    cityHint = afterSuffix.joinToString(" ").lowercase()
                }
            }
        }

        val normalizedStreet = normalizeStreet(streetRaw)
        val numericHouse = extractLeadingLong(houseNumber)

        val startsWithNumber = houseNumber.isNotEmpty() && leadingMatch != null
        val hasNumberAndStreetWord = houseNumber.isNotEmpty() && containsStreetWord(streetRaw)
        val hasZip = zipCode.length == 5
        val isAddressLike = startsWithNumber || hasNumberAndStreetWord || hasZip

        return ParsedAddressQuery(
            rawQuery = rawQuery,
            normalizedQuery = normalized,
            isAddressLike = isAddressLike,
            houseNumber = houseNumber,
            numericHouseNumber = numericHouse,
            streetRaw = streetRaw,
            normalizedStreet = normalizedStreet,
            cityHint = cityHint,
            stateHint = stateHint,
            zipCode = zipCode
        )
    }

    /** Formats a US address cleanly as `"1585 E 13th Ave, Eugene OR 97403"`. */
    fun formatUsAddress(
        number: String,
        street: String,
        unit: String,
        city: String,
        state: String,
        postcode: String
    ): String {
        val line1 = buildString {
            if (number.isNotBlank()) append(number.trim())
            if (street.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(street.trim())
            }
            if (unit.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                val u = unit.trim()
                append(if (u.startsWith("#")) u else "#$u")
            }
        }
        val locality = listOf(city.trim(), state.trim().uppercase(), postcode.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return listOf(line1, locality).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
