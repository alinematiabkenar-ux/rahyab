package com.example.data.model

import com.example.navigation.LatLon
import kotlin.math.abs

data class UsStateInfo(
    val code: String,               // e.g. "or" or regional "ca-north"
    val abbr: String,               // e.g. "OR" or "CA-N"
    val nameEn: String,             // e.g. "Oregon" or "Northern California"
    val nameFa: String,             // e.g. "اورگن" or "کالیفرنیای شمالی"
    val centerLat: Double,
    val centerLon: Double,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val geofabrikSlug: String,
    val parentStateCode: String? = null // Non-null for regional packs of large states (CA, TX, FL, NY)
) {
    val isRegionalPack: Boolean get() = parentStateCode != null
}

object UsStatesCatalog {
    val allStates: List<UsStateInfo> = listOf(
        UsStateInfo("al", "AL", "Alabama", "آلاباما", 32.8067, -86.7911, -88.4732, 30.2233, -84.8891, 35.0080, "alabama"),
        UsStateInfo("ak", "AK", "Alaska", "آلاسکا", 64.2008, -149.4937, -179.1489, 51.2142, -129.9795, 71.3651, "alaska"),
        UsStateInfo("az", "AZ", "Arizona", "آریزونا", 34.0489, -111.0937, -114.8165, 31.3322, -109.0452, 37.0043, "arizona"),
        UsStateInfo("ar", "AR", "Arkansas", "آرکانزاس", 35.2010, -91.8318, -94.6179, 33.0041, -89.6444, 36.4997, "arkansas"),
        UsStateInfo("ca", "CA", "California", "کالیفرنیا", 36.7783, -119.4179, -124.4096, 32.5342, -114.1312, 42.0095, "california"),
        UsStateInfo("co", "CO", "Colorado", "کلرادو", 39.5501, -105.7821, -109.0603, 36.9924, -102.0415, 41.0034, "colorado"),
        UsStateInfo("ct", "CT", "Connecticut", "کنتیکت", 41.6032, -73.0877, -73.7278, 40.9801, -71.7870, 42.0506, "connecticut"),
        UsStateInfo("de", "DE", "Delaware", "دلاور", 38.9108, -75.5277, -75.7887, 38.4510, -75.0489, 39.8390, "delaware"),
        UsStateInfo("dc", "DC", "Washington, D.C.", "واشینگتن دی‌سی", 38.9072, -77.0369, -77.1198, 38.7916, -76.9094, 38.9955, "district-of-columbia"),
        UsStateInfo("fl", "FL", "Florida", "فلوریدا", 27.6648, -81.5158, -87.6349, 24.5231, -80.0314, 31.0009, "florida"),
        UsStateInfo("ga", "GA", "Georgia", "جورجیا", 32.1656, -82.9001, -85.6052, 30.3579, -80.8397, 35.0007, "georgia"),
        UsStateInfo("hi", "HI", "Hawaii", "هاوایی", 19.8968, -155.5828, -160.2471, 18.9104, -154.8068, 22.2356, "hawaii"),
        UsStateInfo("id", "ID", "Idaho", "آیداهو", 44.0682, -114.7420, -117.2430, 41.9881, -111.0436, 49.0011, "idaho"),
        UsStateInfo("il", "IL", "Illinois", "ایلینوی", 40.6331, -89.3985, -91.5131, 36.9703, -87.4948, 42.5085, "illinois"),
        UsStateInfo("in", "IN", "Indiana", "ایندیانا", 40.2672, -86.1349, -88.0978, 37.7717, -84.7846, 41.7606, "indiana"),
        UsStateInfo("ia", "IA", "Iowa", "آیووا", 41.8780, -93.0977, -96.6397, 40.3755, -90.1401, 43.5012, "iowa"),
        UsStateInfo("ks", "KS", "Kansas", "کانزاس", 39.0119, -98.4842, -102.0517, 36.9930, -94.5884, 40.0032, "kansas"),
        UsStateInfo("ky", "KY", "Kentucky", "کنتاکی", 37.8393, -84.2700, -89.5715, 36.4971, -81.9650, 39.1475, "kentucky"),
        UsStateInfo("la", "LA", "Louisiana", "لوئیزیانا", 30.9843, -91.9623, -94.0431, 28.9286, -88.8170, 33.0195, "louisiana"),
        UsStateInfo("me", "ME", "Maine", "مین", 45.2538, -69.4455, -71.0839, 43.0584, -66.9499, 47.4597, "maine"),
        UsStateInfo("md", "MD", "Maryland", "مریلند", 39.0458, -76.6413, -79.4877, 37.9117, -75.0489, 39.7230, "maryland"),
        UsStateInfo("ma", "MA", "Massachusetts", "ماساچوست", 42.4072, -71.3824, -73.5081, 41.2379, -69.9284, 42.8866, "massachusetts"),
        UsStateInfo("mi", "MI", "Michigan", "میشیگان", 44.3148, -85.6024, -90.4181, 41.6961, -82.4135, 48.2388, "michigan"),
        UsStateInfo("mn", "MN", "Minnesota", "مینه‌سوتا", 46.7296, -94.6859, -97.2392, 43.4994, -89.4917, 49.3844, "minnesota"),
        UsStateInfo("ms", "MS", "Mississippi", "می‌سی‌سی‌پی", 32.3547, -89.3985, -91.6550, 30.1739, -88.0979, 34.9961, "mississippi"),
        UsStateInfo("mo", "MO", "Missouri", "میزوری", 37.9643, -91.8318, -95.7747, 35.9957, -89.0988, 40.6136, "missouri"),
        UsStateInfo("mt", "MT", "Montana", "مونتانا", 46.8797, -110.3626, -116.0500, 44.3582, -104.0391, 49.0014, "montana"),
        UsStateInfo("ne", "NE", "Nebraska", "نبراسکا", 41.4925, -99.9018, -104.0535, 39.9999, -95.3083, 43.0017, "nebraska"),
        UsStateInfo("nv", "NV", "Nevada", "نوادا", 38.8026, -116.4194, -120.0057, 35.0019, -114.0396, 42.0022, "nevada"),
        UsStateInfo("nh", "NH", "New Hampshire", "نیوهمپشایر", 43.1939, -71.5724, -72.5572, 42.6970, -70.7036, 45.3055, "new-hampshire"),
        UsStateInfo("nj", "NJ", "New Jersey", "نیوجرسی", 40.0583, -74.4057, -75.5596, 38.9285, -73.8940, 41.3574, "new-jersey"),
        UsStateInfo("nm", "NM", "New Mexico", "نیومکزیکو", 34.5199, -105.8701, -109.0502, 31.3323, -103.0020, 37.0002, "new-mexico"),
        UsStateInfo("ny", "NY", "New York", "نیویورک", 43.2994, -74.2179, -79.7626, 40.4961, -71.8562, 45.0159, "new-york"),
        UsStateInfo("nc", "NC", "North Carolina", "کارولینای شمالی", 35.7596, -79.0193, -84.3219, 33.8423, -75.4606, 36.5881, "north-carolina"),
        UsStateInfo("nd", "ND", "North Dakota", "داکوتای شمالی", 47.5515, -101.0020, -104.0489, 45.9351, -96.5545, 49.0006, "north-dakota"),
        UsStateInfo("oh", "OH", "Ohio", "اوهایو", 40.4173, -82.9071, -84.8203, 38.4032, -80.5187, 41.9775, "ohio"),
        UsStateInfo("ok", "OK", "Oklahoma", "اکلاهما", 35.0078, -97.0929, -103.0026, 33.6158, -94.4307, 37.0022, "oklahoma"),
        UsStateInfo("or", "OR", "Oregon", "اورگن", 44.0521, -123.0868, -124.5662, 41.9918, -116.4635, 46.2920, "oregon"),
        UsStateInfo("pa", "PA", "Pennsylvania", "پنسیلوانیا", 41.2033, -77.1945, -80.5199, 39.7198, -74.6895, 42.2699, "pennsylvania"),
        UsStateInfo("ri", "RI", "Rhode Island", "رود آیلند", 41.5801, -71.4774, -71.8628, 41.1463, -71.1206, 42.0188, "rhode-island"),
        UsStateInfo("sc", "SC", "South Carolina", "کارولینای جنوبی", 33.8361, -81.1637, -83.3539, 32.0346, -78.5420, 35.2154, "south-carolina"),
        UsStateInfo("sd", "SD", "South Dakota", "داکوتای جنوبی", 43.9695, -99.9018, -104.0577, 42.4796, -96.4366, 45.9455, "south-dakota"),
        UsStateInfo("tn", "TN", "Tennessee", "تنسی", 35.5175, -86.5804, -90.3103, 34.9829, -81.6469, 36.6781, "tennessee"),
        UsStateInfo("tx", "TX", "Texas", "تگزاس", 31.9686, -99.9018, -106.6456, 25.8374, -93.5083, 36.5007, "texas"),
        UsStateInfo("ut", "UT", "Utah", "یوتا", 39.3210, -111.0937, -114.0530, 36.9979, -109.0411, 42.0016, "utah"),
        UsStateInfo("vt", "VT", "Vermont", "ورمانت", 44.5588, -72.5778, -73.4377, 42.7269, -71.4646, 45.0167, "vermont"),
        UsStateInfo("va", "VA", "Virginia", "ویرجینیا", 37.4316, -78.6569, -83.6754, 36.5407, -75.2423, 39.4660, "virginia"),
        UsStateInfo("wa", "WA", "Washington", "واشینگتن", 47.7511, -120.7401, -124.7631, 45.5435, -116.9156, 49.0025, "washington"),
        UsStateInfo("wv", "WV", "West Virginia", "ویرجینیای غربی", 38.5976, -80.4549, -82.6447, 37.2015, -77.7195, 40.6388, "west-virginia"),
        UsStateInfo("wi", "WI", "Wisconsin", "ویسکانسین", 43.7844, -88.7879, -92.8894, 42.4919, -86.8054, 47.0806, "wisconsin"),
        UsStateInfo("wy", "WY", "Wyoming", "وایومینگ", 43.0760, -107.2903, -111.0569, 40.9947, -104.0522, 45.0059, "wyoming")
    )

    /**
     * Regional sub-packs for very large states that are split automatically on free
     * GitHub-hosted runners (Phase 5). Each region is treated as its own downloadable
     * pack grouped under its parent state name in Download Maps.
     */
    val regionalSubPacks: List<UsStateInfo> = listOf(
        UsStateInfo("ca-north", "CA-N", "Northern California", "کالیفرنیای شمالی", 39.2, -121.6, -124.4096, 36.8, -118.0, 42.0095, "norcal", parentStateCode = "ca"),
        UsStateInfo("ca-south", "CA-S", "Southern California", "کالیفرنیای جنوبی", 34.4, -118.2, -121.6, 32.5342, -114.1312, 37.2, "socal", parentStateCode = "ca"),
        UsStateInfo("tx-north", "TX-N", "North Texas", "تگزاس شمالی", 33.4, -97.5, -103.1, 31.5, -93.5083, 36.5007, "texas", parentStateCode = "tx"),
        UsStateInfo("tx-south", "TX-S", "Central & South Texas", "تگزاس مرکزی و جنوبی", 29.2, -97.6, -101.5, 25.8374, -93.5083, 31.9, "texas", parentStateCode = "tx"),
        UsStateInfo("tx-west", "TX-W", "West Texas", "تگزاس غربی", 31.6, -103.2, -106.6456, 28.9, -100.8, 35.0, "texas", parentStateCode = "tx"),
        UsStateInfo("fl-north", "FL-N", "North Florida", "فلوریدای شمالی", 29.9, -83.5, -87.6349, 28.2, -80.5, 31.0009, "florida", parentStateCode = "fl"),
        UsStateInfo("fl-south", "FL-S", "Central & South Florida", "فلوریدای مرکزی و جنوبی", 26.8, -81.1, -83.2, 24.5231, -80.0314, 28.6, "florida", parentStateCode = "fl"),
        UsStateInfo("ny-upstate", "NY-U", "Upstate New York", "شمال نیویورک", 43.0, -76.0, -79.7626, 41.8, -73.2, 45.0159, "new-york", parentStateCode = "ny"),
        UsStateInfo("ny-downstate", "NY-D", "NYC & Long Island", "نیویورک سیتی و لانگ آیلند", 40.9, -73.8, -75.4, 40.4961, -71.8562, 42.2, "new-york", parentStateCode = "ny")
    )

    val allPackEntries: List<UsStateInfo> = allStates + regionalSubPacks

    val byCode: Map<String, UsStateInfo> = allPackEntries.associateBy { it.code }

    val regionsByParent: Map<String, List<UsStateInfo>> =
        regionalSubPacks.groupBy { it.parentStateCode!! }

    fun findContainingStates(lat: Double, lon: Double): List<UsStateInfo> =
        allStates.filter { lat in it.minLat..it.maxLat && lon in it.minLon..it.maxLon }

    /**
     * Best-match US state for a GPS coordinate (resolves overlapping rectangular bounding boxes
     * by selecting the containing state with the nearest geographic center).
     */
    fun findBestStateForLocation(lat: Double, lon: Double): UsStateInfo? {
        val candidates = findContainingStates(lat, lon)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        return candidates.minByOrNull { s ->
            val dLat = lat - s.centerLat
            val dLon = lon - s.centerLon
            dLat * dLat + dLon * dLon
        }
    }

    /**
     * Checks whether a given coordinate is covered by at least one installed pack code
     * (either a full state pack or an installed regional sub-pack).
     */
    fun isPointCoveredByInstalledPacks(lat: Double, lon: Double, installedCodes: Set<String>): Boolean {
        val normalized = installedCodes.map { it.lowercase() }.toSet()
        for (code in normalized) {
            val info = byCode[code] ?: continue
            if (lat in (info.minLat - 0.15)..(info.maxLat + 0.15) &&
                lon in (info.minLon - 0.15)..(info.maxLon + 0.15)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Samples the corridor between origin and destination and returns all US state codes
     * along the trip that are NOT currently covered by any installed pack.
     */
    fun findMissingStatesForCorridor(
        origin: LatLon,
        destination: LatLon,
        installedCodes: Set<String>,
        samples: Int = 16
    ): List<UsStateInfo> {
        val missing = linkedMapOf<String, UsStateInfo>()
        for (i in 0..samples) {
            val t = i.toDouble() / samples.toDouble()
            val lat = origin.lat + (destination.lat - origin.lat) * t
            val lon = origin.lon + (destination.lon - origin.lon) * t
            if (!isPointCoveredByInstalledPacks(lat, lon, installedCodes)) {
                val state = findBestStateForLocation(lat, lon)
                if (state != null) {
                    // Check if any regional sub-pack for this state covers the point
                    val regional = regionsByParent[state.code]
                    if (regional != null) {
                        val matchingRegion = regional.firstOrNull {
                            lat in it.minLat..it.maxLat && lon in it.minLon..it.maxLon
                        }
                        if (matchingRegion != null && matchingRegion.code !in installedCodes) {
                            missing[matchingRegion.code] = matchingRegion
                        } else if (state.code !in installedCodes) {
                            missing[state.code] = state
                        }
                    } else if (state.code !in installedCodes) {
                        missing[state.code] = state
                    }
                }
            }
        }
        return missing.values.toList()
    }
}
