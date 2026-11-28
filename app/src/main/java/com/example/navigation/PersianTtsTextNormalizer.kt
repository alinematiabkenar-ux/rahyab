package com.example.navigation

/**
 * Normalizes dynamic Persian navigation instructions for the built-in bilingual
 * Khadijah (Persian/English) offline TTS model so Persian digits, Latin digits,
 * distances, highway/exit numbers, lane counts, and English street names are spoken
 * clearly in a single continuous utterance.
 */
object PersianTtsTextNormalizer {

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    private val ONES = arrayOf(
        "", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه",
        "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده"
    )
    private val TENS = arrayOf(
        "", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود"
    )
    private val HUNDREDS = arrayOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد"
    )

    private val LATIN_LETTER_PERSIAN = mapOf(
        'A' to "ای", 'B' to "بی", 'C' to "سی", 'D' to "دی", 'E' to "ئی",
        'F' to "اف", 'G' to "جی", 'H' to "اچ", 'I' to "آی", 'J' to "جی",
        'K' to "کی", 'L' to "ال", 'M' to "ام", 'N' to "ان", 'O' to "او",
        'P' to "پی", 'Q' to "کیو", 'R' to "آر", 'S' to "اس", 'T' to "تی",
        'U' to "یو", 'V' to "وی", 'W' to "دابلیو", 'X' to "ایکس", 'Y' to "وای", 'Z' to "زی"
    )

    private val HIGHWAY_PREFIX_REGEX = Regex("""\b(I|US|OR|WA|CA|NV|ID|SR|CR|HWY)[\s-]+(\d{1,4})([A-Za-z]?)\b""")
    private val EXIT_NUMBER_REGEX = Regex("""(خروجی\s+)(\d{1,4})([A-Za-z]?)\b""")
    private val ORDINAL_REGEX = Regex("""(\d{1,2})م(?=\s|[،.,؛:!?]|$)""")
    private val DECIMAL_REGEX = Regex("""\b(\d{1,3})[.,٫](\d{1,2})\b""")
    private val NUMBER_WITH_LATIN_UNIT_REGEX = Regex("""\b(\d{1,6})\s*(km|m|mi|ft|mph|km/h)\b""", RegexOption.IGNORE_CASE)
    private val STANDALONE_NUMBER_REGEX = Regex("""\b(\d{1,6})\b""")
    private val SLASH_SEPARATOR_REGEX = Regex("""\s*/\s*""")
    private val LATIN_AMPERSAND_REGEX = Regex("""([A-Za-z0-9])\s*&\s*([A-Za-z0-9])""")
    private val PERSIAN_TO_LATIN_BOUNDARY_REGEX = Regex("""([\u0600-\u06FF])([A-Za-z])""")
    private val LATIN_TO_PERSIAN_BOUNDARY_REGEX = Regex("""([A-Za-z])([\u0600-\u06FF])""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    private val STREET_ABBREVS: List<Pair<Regex, String>> = listOf(
        Regex("""\bNW\.?(?=\s|$|[،.,؛:!?])""") to "Northwest",
        Regex("""\bNE\.?(?=\s|$|[،.,؛:!?])""") to "Northeast",
        Regex("""\bSW\.?(?=\s|$|[،.,؛:!?])""") to "Southwest",
        Regex("""\bSE\.?(?=\s|$|[،.,؛:!?])""") to "Southeast",
        Regex("""\bN\.(?=\s+[A-Za-z0-9])""") to "North",
        Regex("""\bS\.(?=\s+[A-Za-z0-9])""") to "South",
        Regex("""\bE\.(?=\s+[A-Za-z0-9])""") to "East",
        Regex("""\bW\.(?=\s+[A-Za-z0-9])""") to "West",
        Regex("""\bJr\.?(?=\s|$|[،.,؛:!?])""") to "Junior",
        Regex("""\bSr\.?(?=\s|$|[،.,؛:!?])""") to "Senior",
        Regex("""\bMt\.?(?=\s+[A-Za-z])""") to "Mount",
        Regex("""\bFt\.?(?=\s+[A-Za-z])""") to "Fort",
        Regex("""\bSt\.?(?=\s|$|[،.,؛:!?])""") to "Street",
        Regex("""\bAve\.?(?=\s|$|[،.,؛:!?])""") to "Avenue",
        Regex("""\bBlvd\.?(?=\s|$|[،.,؛:!?])""") to "Boulevard",
        Regex("""\bRd\.?(?=\s|$|[،.,؛:!?])""") to "Road",
        Regex("""\bDr\.?(?=\s|$|[،.,؛:!?])""") to "Drive",
        Regex("""\bLn\.?(?=\s|$|[،.,؛:!?])""") to "Lane",
        Regex("""\bCt\.?(?=\s|$|[،.,؛:!?])""") to "Court",
        Regex("""\bPl\.?(?=\s|$|[،.,؛:!?])""") to "Place",
        Regex("""\bCir\.?(?=\s|$|[،.,؛:!?])""") to "Circle",
        Regex("""\bPkwy\.?(?=\s|$|[،.,؛:!?])""") to "Parkway",
        Regex("""\bHwy\.?(?=\s|$|[،.,؛:!?])""") to "Highway",
        Regex("""\bFwy\.?(?=\s|$|[،.,؛:!?])""") to "Freeway",
        Regex("""\bExpy\.?(?=\s|$|[،.,؛:!?])""") to "Expressway",
        Regex("""\bTer\.?(?=\s|$|[،.,؛:!?])""") to "Terrace",
        Regex("""\bTrl\.?(?=\s|$|[،.,؛:!?])""") to "Trail",
        Regex("""\bSq\.?(?=\s|$|[،.,؛:!?])""") to "Square"
    )

    /**
     * Normalizes a Persian navigation instruction (which may include Persian/Latin digits,
     * highway codes, exit letters, and English street names) into clean spoken text for
     * the bilingual Khadijah `fa_en` Matcha-TTS engine.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var text = toAsciiDigits(raw)
            .replace("یک‌چهارم", "یک چهارم")
            .replace("٫", ".")
            .replace("؛", "، ")

        // 1. Expand highway codes like I-5, US-101, OR-99W into natural spoken Persian
        text = HIGHWAY_PREFIX_REGEX.replace(text) { m ->
            val prefix = m.groupValues[1].uppercase()
            val num = m.groupValues[2].toIntOrNull()
            val suffix = m.groupValues[3].uppercase()
            val spokenPrefix = when (prefix) {
                "HWY" -> "بزرگراه"
                else -> prefix.map { LATIN_LETTER_PERSIAN[it] ?: it.toString() }.joinToString(" ")
            }
            val spokenNum = if (num != null) numberToPersianWords(num) else m.groupValues[2]
            val spokenSuffix = suffix.firstOrNull()?.let { " " + (LATIN_LETTER_PERSIAN[it] ?: it.toString()) }.orEmpty()
            "$spokenPrefix $spokenNum$spokenSuffix"
        }

        // 2. Expand exit codes like "خروجی 194B" or "خروجی 195"
        text = EXIT_NUMBER_REGEX.replace(text) { m ->
            val lead = m.groupValues[1]
            val num = m.groupValues[2].toIntOrNull()
            val suffix = m.groupValues[3].uppercase()
            val spokenNum = if (num != null) numberToPersianWords(num) else m.groupValues[2]
            val spokenSuffix = suffix.firstOrNull()?.let { " " + (LATIN_LETTER_PERSIAN[it] ?: it.toString()) }.orEmpty()
            "$lead$spokenNum$spokenSuffix"
        }

        // 3. Expand Persian ordinals like "1م" / "6م"
        text = ORDINAL_REGEX.replace(text) { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            ordinalToPersian(num)
        }

        // 4. Expand decimals like "1.5" -> "یک و نیم", "0.5" -> "نیم", "1.2" -> "یک ممیز دو"
        text = DECIMAL_REGEX.replace(text) { m ->
            val whole = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val fracStr = m.groupValues[2]
            val frac = fracStr.toIntOrNull() ?: return@replace m.value
            when {
                frac == 0 -> numberToPersianWords(whole)
                fracStr == "5" || fracStr == "50" -> {
                    if (whole == 0) "نیم" else "${numberToPersianWords(whole)} و نیم"
                }
                else -> "${numberToPersianWords(whole)} ممیز ${numberToPersianWords(frac)}"
            }
        }

        // 5. Expand Latin distance/speed units attached to numbers (e.g. "500 m", "2 km")
        text = NUMBER_WITH_LATIN_UNIT_REGEX.replace(text) { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val unitWord = when (m.groupValues[2].lowercase()) {
                "m" -> "متر"
                "km" -> "کیلومتر"
                "mi" -> "مایل"
                "ft" -> "فوت"
                "mph" -> "مایل بر ساعت"
                "km/h" -> "کیلومتر بر ساعت"
                else -> m.groupValues[2]
            }
            "${numberToPersianWords(num)} $unitWord"
        }

        // 6. Convert remaining standalone integers (distances, lane counts, house numbers, speeds)
        text = STANDALONE_NUMBER_REGEX.replace(text) { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            numberToPersianWords(num)
        }

        // 7. Expand common US street abbreviations while preserving clean English street names
        for ((pattern, replacement) in STREET_ABBREVS) {
            text = pattern.replace(text, replacement)
        }

        // 8. Normalize signpost slashes, ampersands, script boundaries, and whitespace
        text = SLASH_SEPARATOR_REGEX.replace(text, " و ")
        text = LATIN_AMPERSAND_REGEX.replace(text, "$1 and $2").replace("&", " و ")
        text = PERSIAN_TO_LATIN_BOUNDARY_REGEX.replace(text, "$1 $2")
        text = LATIN_TO_PERSIAN_BOUNDARY_REGEX.replace(text, "$1 $2")
        return text
            .replace(" ,", "،")
            .replace(" ،", "،")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun toAsciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val pIdx = PERSIAN_DIGITS.indexOf(ch)
            if (pIdx >= 0) {
                sb.append(('0'.code + pIdx).toChar())
                continue
            }
            val aIdx = ARABIC_DIGITS.indexOf(ch)
            if (aIdx >= 0) {
                sb.append(('0'.code + aIdx).toChar())
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    fun numberToPersianWords(value: Int): String {
        if (value == 0) return "صفر"
        if (value < 0) return "منفی ${numberToPersianWords(-value)}"
        if (value >= 1_000_000) return value.toString()

        val parts = mutableListOf<String>()
        val thousands = value / 1000
        val remainder = value % 1000

        if (thousands > 0) {
            parts.add(if (thousands == 1) "هزار" else "${underThousandToPersian(thousands)} هزار")
        }
        if (remainder > 0) {
            parts.add(underThousandToPersian(remainder))
        }
        return parts.joinToString(" و ")
    }

    private fun underThousandToPersian(n: Int): String {
        val parts = mutableListOf<String>()
        val h = n / 100
        val rem = n % 100
        if (h > 0) parts.add(HUNDREDS[h])
        if (rem in 1..19) {
            parts.add(ONES[rem])
        } else if (rem >= 20) {
            val t = rem / 10
            val o = rem % 10
            parts.add(TENS[t])
            if (o > 0) parts.add(ONES[o])
        }
        return parts.joinToString(" و ")
    }

    private fun ordinalToPersian(n: Int): String = when (n) {
        1 -> "اول"
        2 -> "دوم"
        3 -> "سوم"
        4 -> "چهارم"
        5 -> "پنجم"
        6 -> "ششم"
        7 -> "هفتم"
        8 -> "هشتم"
        9 -> "نهم"
        10 -> "دهم"
        else -> "${numberToPersianWords(n)}م"
    }
}
