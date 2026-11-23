package com.example

import com.example.data.search.AddressInterpolation
import com.example.data.search.AddressNormalizer
import com.example.data.search.OfflineAddressRow
import com.example.data.search.OfflineAddressSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.navigation.PendingLowPriorityAlertQueue
import com.example.navigation.SpeedBumpAlertEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressAndVoiceV21Test {

    @Test
    fun addressQueryDetection_englishPersianZipAndPlainBusinessGuard() {
        val en = AddressNormalizer.parseAddressQuery("1585 E 13th Ave, Eugene OR 97403")
        assertTrue(en.isAddressLike)
        assertEquals("1585", en.houseNumber)
        assertEquals("e 13th ave", en.normalizedStreet)
        assertEquals("eugene", en.cityHint)
        assertEquals("or", en.stateHint)
        assertEquals("97403", en.zipCode)
        assertTrue(OfflineAddressSearchEngine.shouldRunAddressSearch(en))

        val fa = AddressNormalizer.parseAddressQuery("۱۵۸۵ East 13th Avenue")
        assertTrue(fa.isAddressLike)
        assertEquals("1585", fa.houseNumber)
        assertEquals("e 13th ave", fa.normalizedStreet)
        assertTrue(OfflineAddressSearchEngine.shouldRunAddressSearch(fa))

        val zipOnly = AddressNormalizer.parseAddressQuery("۹۷۴۰۳")
        assertTrue(zipOnly.isAddressLike)
        assertEquals("97403", zipOnly.zipCode)
        assertTrue(OfflineAddressSearchEngine.shouldRunAddressSearch(zipOnly))

        // Street-suffix only query (no house number) should still run address engine
        val streetOnly = AddressNormalizer.parseAddressQuery("Coburg Road")
        assertTrue(OfflineAddressSearchEngine.shouldRunAddressSearch(streetOnly))

        // A3: Plain business names must NOT trigger the address search engine
        val bestBuy = AddressNormalizer.parseAddressQuery("best buy")
        assertFalse(bestBuy.isAddressLike)
        assertFalse(OfflineAddressSearchEngine.shouldRunAddressSearch(bestBuy))

        val starbucks = AddressNormalizer.parseAddressQuery("starbucks")
        assertFalse(starbucks.isAddressLike)
        assertFalse(OfflineAddressSearchEngine.shouldRunAddressSearch(starbucks))
    }

    @Test
    fun p5_sharedStreetNormalizationTableMatchesPythonReference() {
        val sharedCases = listOf(
            "E 13th Ave" to "e 13th ave",
            "East 13th Avenue" to "e 13th ave",
            "13th Ave E" to "13th ave e",
            "13th Avenue East" to "13th ave e",
            "Old Highway 99" to "old hwy 99",
            "Old Highway 99 S" to "old hwy 99 s",
            "Old Highway 99 South" to "old hwy 99 s",
            "Pacific Highway" to "pacific hwy",
            "Martin Luther King Jr Blvd" to "martin luther king jr blvd",
            "Martin Luther King Jr. Boulevard" to "martin luther king jr blvd",
            "Coburg Road" to "coburg rd",
            "O'Brien Court" to "o brien ct",
            "Northwest 23rd Avenue" to "nw 23rd ave",
            "SW Barbur Blvd." to "sw barbur blvd",
            "N.E. Sandy Boulevard" to "n e sandy blvd",
            "Southeast Powell Blvd" to "se powell blvd",
            "West 11th Ave" to "w 11th ave",
            "Highway 99" to "highway 99",
            "Willamette Street" to "willamette st",
            "Oak Parkway" to "oak pkwy",
            "Sunset Terrace" to "sunset ter",
            "River Trail" to "river trl",
            "Meadow Circle" to "meadow cir",
            "Valley Wy" to "valley way",
            "Valley Way" to "valley way",
            "۱3th Avenue" to "13th ave"
        )
        assertTrue(sharedCases.size >= 20)
        for ((raw, expected) in sharedCases) {
            assertEquals("Mismatch for '$raw'", expected, AddressNormalizer.normalizeStreet(raw))
        }
    }

    @Test
    fun a1_distanceFirstSqlAndDistanceOrdering() {
        val sql = OfflineAddressSearchEngine.buildDistanceFirstFtsSql()
        val orderIdx = sql.indexOf("ORDER BY ((a.lat - ?) * (a.lat - ?) + ((a.lon - ?) * ?) * ((a.lon - ?) * ?)) ASC")
        val limitIdx = sql.indexOf("LIMIT ?")
        assertTrue("Expected distance-first ORDER BY in SQL", orderIdx > 0)
        assertTrue("ORDER BY must precede LIMIT", orderIdx < limitIdx)
        assertEquals(60, OfflineAddressSearchEngine.EXACT_QUERY_LIMIT)
        assertEquals(400, OfflineAddressSearchEngine.STREET_CANDIDATE_LIMIT)

        val parsed = AddressNormalizer.parseAddressQuery("100 Main St")
        val nearRow = OfflineAddressRow(1L, "100", "Main St", "", "Eugene", "OR", "97401", 44.052, -123.086, "overture")
        val farRow = OfflineAddressRow(2L, "100", "Main St", "", "Portland", "OR", "97201", 45.515, -122.678, "overture")
        val nearScore = AddressInterpolation.scoreAddressMatch(nearRow, parsed, 44.0521, -123.0868)
        val farScore = AddressInterpolation.scoreAddressMatch(farRow, parsed, 44.0521, -123.0868)
        assertTrue("Closer address must score higher than distant address", nearScore > farScore)
    }

    @Test
    fun a2_cityFallbackMatchExpressionsAndScoringPenalty() {
        val parsed = AddressNormalizer.parseAddressQuery("1585 E 13th Ave, Springfield")
        val withCity = OfflineAddressSearchEngine.buildExactFtsMatch(parsed, includeCity = true)
        val withoutCity = OfflineAddressSearchEngine.buildExactFtsMatch(parsed, includeCity = false)
        assertTrue(withCity.contains("city:springfield*"))
        assertFalse(withoutCity.contains("city:"))

        val streetWithCity = OfflineAddressSearchEngine.buildStreetOnlyFtsMatch(parsed, includeCity = true)
        val streetWithoutCity = OfflineAddressSearchEngine.buildStreetOnlyFtsMatch(parsed, includeCity = false)
        assertTrue(streetWithCity.contains("city:springfield*"))
        assertFalse(streetWithoutCity.contains("city:"))

        val matchingCity = OfflineAddressRow(1L, "1585", "E 13th Ave", "", "Springfield", "OR", "97477", 44.046, -123.020, "overture")
        val fallbackCity = OfflineAddressRow(2L, "1585", "E 13th Ave", "", "Eugene", "OR", "97403", 44.046, -123.020, "overture")
        val scoreMatch = AddressInterpolation.scoreAddressMatch(matchingCity, parsed, 44.046, -123.020)
        val scoreFallback = AddressInterpolation.scoreAddressMatch(fallbackCity, parsed, 44.046, -123.020)
        assertTrue("Matching city must outrank mismatched city fallback", scoreMatch > scoreFallback + 50.0)
    }

    @Test
    fun a5_pendingLowPriorityAlertQueueTtlAndBehindUserDrop() {
        val queue = PendingLowPriorityAlertQueue()
        // Heading north (0 deg) at (44.0500, -123.0800), bump is ahead at (44.0510, -123.0800)
        queue.updateUserMotion(44.0500, -123.0800, 0f)
        queue.offer("Speed bump ahead", AppLanguage.ENGLISH, 44.0510, -123.0800, nowMs = 1_000L)
        val valid = queue.pollIfStillValid(nowMs = 4_000L)
        assertNotNull(valid)
        assertEquals("Speed bump ahead", valid!!.text)

        // Older than 8 seconds -> dropped
        queue.offer("Speed bump ahead", AppLanguage.ENGLISH, 44.0510, -123.0800, nowMs = 1_000L)
        assertNull(queue.pollIfStillValid(nowMs = 9_500L))

        // Already behind the user (user moved north of bump to 44.0520 still heading north 0 deg) -> dropped
        queue.offer("Speed bump ahead", AppLanguage.ENGLISH, 44.0510, -123.0800, nowMs = 10_000L)
        queue.updateUserMotion(44.0520, -123.0800, 0f)
        assertNull(queue.pollIfStillValid(nowMs = 11_000L))
    }

    @Test
    fun f2_speedBumpClusteringSpeedThresholdsAndLowSpeedSilence() {
        val evaluator = SpeedBumpAlertEvaluator()
        // Heading due north (0 deg) from (44.0500, -123.0800)
        // Two bumps ahead at ~65m and ~115m (50m apart <= 80m cluster radius)
        val bump1 = RoadHazardPoint(1L, "speed_bump", 44.05058, -123.0800, "bump", osmId = "n1")
        val bump2 = RoadHazardPoint(2L, "speed_bump", 44.05103, -123.0800, "hump", osmId = "n2")

        assertEquals(120.0, SpeedBumpAlertEvaluator.alertDistanceMeters(10f), 0.01)
        assertEquals(200.0, SpeedBumpAlertEvaluator.alertDistanceMeters(15f), 0.01)

        // Speed < 5 mph (1.5 m/s): banner shown, speech suppressed
        val (bannerSlow, speakSlow) = evaluator.evaluate(
            userLat = 44.0500, userLon = -123.0800, headingDegrees = 0f, speedMps = 1.5f,
            bumps = listOf(bump1, bump2), enabled = true, nowMs = 1_000L
        )
        assertNotNull(bannerSlow)
        assertEquals(SpeedBumpAlertEvaluator.TITLE_PLURAL_EN, bannerSlow!!.titleEn)
        assertNull(speakSlow)

        // Speed 20 mph (~9 m/s): speaks plural once for the whole cluster
        val (bannerMoving, speakMoving) = evaluator.evaluate(
            userLat = 44.0500, userLon = -123.0800, headingDegrees = 0f, speedMps = 9.0f,
            bumps = listOf(bump1, bump2), enabled = true, nowMs = 2_000L
        )
        assertNotNull(bannerMoving)
        assertNotNull(speakMoving)
        assertEquals(SpeedBumpAlertEvaluator.TITLE_PLURAL_FA, speakMoving!!.titleFa)

        // Second tick on same cluster does NOT re-speak
        val (_, speakRepeat) = evaluator.evaluate(
            userLat = 44.0501, userLon = -123.0800, headingDegrees = 0f, speedMps = 9.0f,
            bumps = listOf(bump1, bump2), enabled = true, nowMs = 3_000L
        )
        assertNull(speakRepeat)
    }
}
