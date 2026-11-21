package com.example.data.search

/**
 * Normalizes English and Persian search queries for offline FTS5 search:
 *  1. Normalizes Arabic/Persian character variants (ي -> ی, ك -> ک, ZWNJ -> space, Persian digits -> ASCII digits).
 *  2. Expands Persian category terms (e.g., "پمپ بنزین" -> gas station / fuel, "داروخانه" -> pharmacy).
 *  3. Expands Persian transliterated US brand names (e.g., "بست بای" -> Best Buy, "والمارت" -> Walmart).
 *  4. Provides algorithmic Persian-to-Latin phonetic transliteration for any unknown Persian brand/street query.
 */
object PersianQueryNormalizer {

    data class ExpandedQuery(
        val original: String,
        val normalizedLatinQueries: List<String>,
        val matchedCategoryTags: List<String>,
        val isPersianInput: Boolean
    )

    private val persianCategoryMap: Map<String, List<String>> = mapOf(
        "پمپ بنزین" to listOf("gas_station", "fuel", "gas station", "charging_station"),
        "بنزین" to listOf("gas_station", "fuel", "gas station"),
        "جایگاه سوخت" to listOf("gas_station", "fuel"),
        "داروخانه" to listOf("pharmacy", "drugstore"),
        "داروخونه" to listOf("pharmacy", "drugstore"),
        "سوپرمارکت" to listOf("supermarket", "grocery", "grocery_store"),
        "سوپر مارکت" to listOf("supermarket", "grocery", "grocery_store"),
        "خواربارفروشی" to listOf("grocery", "supermarket"),
        "فروشگاه مواد غذایی" to listOf("grocery", "supermarket"),
        "رستوران" to listOf("restaurant", "dining", "fast_food"),
        "غذاخوری" to listOf("restaurant", "fast_food"),
        "فست فود" to listOf("fast_food", "burger", "pizza"),
        "کافه" to listOf("cafe", "coffee", "coffee_shop"),
        "قهوه" to listOf("coffee", "cafe", "coffee_shop"),
        "کافی شاپ" to listOf("coffee", "cafe", "coffee_shop"),
        "بیمارستان" to listOf("hospital", "emergency", "medical_center"),
        "درمانگاه" to listOf("clinic", "urgent_care", "hospital"),
        "اورژانس" to listOf("hospital", "emergency", "urgent_care"),
        "بانک" to listOf("bank", "atm", "credit_union"),
        "خودپرداز" to listOf("atm", "bank"),
        "هتل" to listOf("hotel", "motel", "lodging"),
        "متل" to listOf("motel", "hotel"),
        "پارکینگ" to listOf("parking"),
        "تعمیرگاه" to listOf("auto_repair", "car_repair", "mechanic"),
        "مکانیکی" to listOf("auto_repair", "car_repair"),
        "کارواش" to listOf("car_wash"),
        "فرودگاه" to listOf("airport"),
        "پلیس" to listOf("police"),
        "اداره پست" to listOf("post_office"),
        "پست" to listOf("post_office", "shipping"),
        "پارک" to listOf("park"),
        "مدرسه" to listOf("school"),
        "دانشگاه" to listOf("university", "college"),
        "کتابخانه" to listOf("library"),
        "نانوایی" to listOf("bakery"),
        "شیرینی فروشی" to listOf("bakery", "dessert"),
        "باشگاه" to listOf("gym", "fitness"),
        "سینما" to listOf("cinema", "movie_theater"),
        "لوازم الکترونیکی" to listOf("electronics", "electronics_store"),
        "فروشگاه" to listOf("store", "retail", "shopping"),
        "مرکز خرید" to listOf("shopping_mall", "mall", "department_store"),
        "مسجد" to listOf("mosque"),
        "کلیسا" to listOf("church"),
        "خشکشویی" to listOf("laundry", "dry_cleaning"),
        "آرایشگاه" to listOf("hair_salon", "barber")
    )

    private val persianBrandTransliterations: Map<String, String> = mapOf(
        "بست بای" to "Best Buy",
        "بستبای" to "Best Buy",
        "وال مارت" to "Walmart",
        "والمارت" to "Walmart",
        "تارگت" to "Target",
        "کاستکو" to "Costco",
        "کاسکو" to "Costco",
        "استارباکس" to "Starbucks",
        "استار باکس" to "Starbucks",
        "مک دونالد" to "McDonald's",
        "مکدونالد" to "McDonald's",
        "مک دونالدز" to "McDonald's",
        "برگر کینگ" to "Burger King",
        "وندیز" to "Wendy's",
        "تاکو بل" to "Taco Bell",
        "ساب وی" to "Subway",
        "سابوی" to "Subway",
        "این اند اوت" to "In-N-Out",
        "چیپوتله" to "Chipotle",
        "چیک فیل ای" to "Chick-fil-A",
        "هوم دیپو" to "Home Depot",
        "هوم دپو" to "Home Depot",
        "لوز" to "Lowe's",
        "سی وی اس" to "CVS",
        "والگرینز" to "Walgreens",
        "وال گرینز" to "Walgreens",
        "رایت اید" to "Rite Aid",
        "سیف وی" to "Safeway",
        "سیفوی" to "Safeway",
        "فرد مایر" to "Fred Meyer",
        "تریدر جوز" to "Trader Joe's",
        "تردر جوز" to "Trader Joe's",
        "هول فودز" to "Whole Foods",
        "آلبرتسونز" to "Albertsons",
        "وینکو" to "WinCo",
        "کروگر" to "Kroger",
        "اپل استور" to "Apple Store",
        "اپل" to "Apple",
        "آمازون" to "Amazon",
        "ایکیا" to "IKEA",
        "آیکیا" to "IKEA",
        "شل" to "Shell",
        "شورون" to "Chevron",
        "آرکو" to "ARCO",
        "اکسان" to "Exxon",
        "موبیل" to "Mobil",
        "سیکس سون" to "76",
        "سون الون" to "7-Eleven",
        "بنک آو آمریکا" to "Bank of America",
        "چیس" to "Chase",
        "ولز فارگو" to "Wells Fargo",
        "یو اس بانک" to "US Bank",
        "فدکس" to "FedEx",
        "یو پی اس" to "UPS",
        "داچ بروز" to "Dutch Bros",
        "دانکین" to "Dunkin",
        "پانرا" to "Panera",
        "پیتزا هات" to "Pizza Hut",
        "دومینوز" to "Domino's",
        "آتو زون" to "AutoZone",
        "اورایلی" to "O'Reilly",
        "دالر تری" to "Dollar Tree",
        "تی جی مکس" to "TJ Maxx",
        "راس" to "Ross",
        "نورداستروم" to "Nordstrom",
        "میسیز" to "Macy's",
        "کولز" to "Kohl's"
    )

    private val englishCategorySynonyms: Map<String, List<String>> = mapOf(
        "gas" to listOf("gas_station", "fuel"),
        "gas station" to listOf("gas_station", "fuel"),
        "fuel" to listOf("gas_station", "fuel"),
        "pharmacy" to listOf("pharmacy", "drugstore"),
        "drugstore" to listOf("pharmacy", "drugstore"),
        "grocery" to listOf("supermarket", "grocery", "grocery_store"),
        "supermarket" to listOf("supermarket", "grocery", "grocery_store"),
        "coffee" to listOf("cafe", "coffee", "coffee_shop"),
        "cafe" to listOf("cafe", "coffee", "coffee_shop"),
        "restaurant" to listOf("restaurant", "fast_food"),
        "food" to listOf("restaurant", "fast_food", "cafe"),
        "hospital" to listOf("hospital", "emergency", "urgent_care"),
        "hotel" to listOf("hotel", "motel"),
        "electronics" to listOf("electronics", "electronics_store")
    )

    fun normalizePersianText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\u064A', '\u0626', '\u0649' -> sb.append('\u06CC') // Arabic yeh -> Persian ی
                '\u0643' -> sb.append('\u06A9')                     // Arabic kaf -> Persian ک
                '\u0622', '\u0623', '\u0625' -> sb.append('\u0627') // Alef variants -> ا
                '\u200C', '\u200B' -> sb.append(' ')                // ZWNJ -> space
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u064B'..'\u065F' -> Unit                       // Strip diacritics
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun containsPersianScript(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }

    fun expandQuery(rawQuery: String): ExpandedQuery {
        val cleaned = normalizePersianText(rawQuery)
        if (cleaned.isBlank()) {
            return ExpandedQuery(rawQuery, emptyList(), emptyList(), false)
        }

        val isPersian = containsPersianScript(cleaned)
        val latinCandidates = linkedSetOf<String>()
        val categoryTags = linkedSetOf<String>()

        if (isPersian) {
            // 1. Exact or substring brand transliteration match
            persianBrandTransliterations[cleaned]?.let { latinCandidates.add(it) }
            for ((faBrand, enBrand) in persianBrandTransliterations) {
                if (cleaned.contains(faBrand)) {
                    latinCandidates.add(enBrand)
                    val remainder = cleaned.replace(faBrand, "").trim()
                    if (remainder.isNotEmpty()) {
                        latinCandidates.add("$enBrand ${transliteratePersianToLatin(remainder)}")
                    }
                }
            }

            // 2. Persian category matching
            persianCategoryMap[cleaned]?.let { categoryTags.addAll(it) }
            for ((faCat, tags) in persianCategoryMap) {
                if (cleaned.contains(faCat)) {
                    categoryTags.addAll(tags)
                }
            }

            // 3. Algorithmic phonetic transliteration fallback for arbitrary Persian input
            val transliterated = transliteratePersianToLatin(cleaned)
            if (transliterated.isNotBlank()) {
                latinCandidates.add(transliterated)
            }
        } else {
            val lower = cleaned.lowercase()
            latinCandidates.add(cleaned)
            englishCategorySynonyms[lower]?.let { categoryTags.addAll(it) }
        }

        return ExpandedQuery(
            original = rawQuery,
            normalizedLatinQueries = latinCandidates.toList(),
            matchedCategoryTags = categoryTags.toList(),
            isPersianInput = isPersian
        )
    }

    /**
     * Algorithmic Persian-to-Latin phonetic transliterator for arbitrary store names,
     * street names, or city names entered in Persian script.
     */
    fun transliteratePersianToLatin(persian: String): String {
        val normalized = normalizePersianText(persian)
        val words = normalized.split(" ").filter { it.isNotBlank() }
        return words.joinToString(" ") { word ->
            val sb = StringBuilder()
            var i = 0
            while (i < word.length) {
                val ch = word[i]
                val next = word.getOrNull(i + 1)
                when (ch) {
                    'ا' -> sb.append(if (i == 0) "a" else "a")
                    'ب' -> sb.append("b")
                    'پ' -> sb.append("p")
                    'ت', 'ط' -> sb.append("t")
                    'ث', 'س', 'ص' -> sb.append("s")
                    'ج' -> sb.append("j")
                    'چ' -> sb.append("ch")
                    'ح', 'ه' -> if (i == word.lastIndex && word.length > 2) sb.append("e") else sb.append("h")
                    'خ' -> sb.append("kh")
                    'د' -> sb.append("d")
                    'ذ', 'ز', 'ض', 'ظ' -> sb.append("z")
                    'ر' -> sb.append("r")
                    'ژ' -> sb.append("zh")
                    'ش' -> sb.append("sh")
                    'ع' -> if (i == 0) sb.append("a") else Unit
                    'غ', 'ق' -> sb.append("g")
                    'ف' -> sb.append("f")
                    'ک' -> sb.append("k")
                    'گ' -> sb.append("g")
                    'ل' -> sb.append("l")
                    'م' -> sb.append("m")
                    'ن' -> sb.append("n")
                    'و' -> sb.append(if (i == 0) "w" else "o")
                    'ی' -> sb.append(if (i == 0 || next == 'ا') "y" else "i")
                    else -> if (ch.isLetterOrDigit()) sb.append(ch)
                }
                i++
            }
            sb.toString()
        }
    }
}
