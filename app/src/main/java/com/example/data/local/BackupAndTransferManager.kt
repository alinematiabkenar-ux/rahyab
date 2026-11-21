package com.example.data.local

import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import com.example.navigation.RoutePreference
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure JSON serializer/deserializer for:
 *  1. Sharing Waze-style driving reports with family (Phase 4)
 *  2. Exporting & importing all user Settings, Saved Places (Home/Work/Favorites/Recent),
 *     and active Reports when moving RahYab to a new phone (Final Step)
 */
object BackupAndTransferManager {

    data class ImportedFullBackup(
        val settings: UserSettings?,
        val savedPlaces: List<SavedPlaceEntity>,
        val reports: List<UserReportEntity>
    )

    fun exportReportsJson(reports: List<UserReportEntity>): String {
        val arr = JSONArray()
        for (r in reports) {
            arr.put(JSONObject().apply {
                put("uuid", r.uuid)
                put("type", r.type)
                put("lat", r.lat)
                put("lon", r.lon)
                put("bearing", r.bearing.toDouble())
                put("roadName", r.roadName)
                put("note", r.note)
                put("createdAt", r.createdAt)
                put("expiresAt", r.expiresAt)
                put("confirmations", r.confirmations)
            })
        }
        return JSONObject().apply {
            put("format", "rahyab_reports_v1")
            put("exportedAt", System.currentTimeMillis())
            put("reports", arr)
        }.toString(2)
    }

    fun importReportsJson(json: String, nowMillis: Long = System.currentTimeMillis()): List<UserReportEntity> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("reports") ?: return emptyList()
        val out = mutableListOf<UserReportEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val typeName = obj.optString("type", "")
            if (runCatching { ReportType.valueOf(typeName) }.isFailure) continue
            val expiresAt = obj.optLong("expiresAt", 0L)
            if (expiresAt <= nowMillis) continue
            out.add(
                UserReportEntity(
                    uuid = obj.optString("uuid", "").ifBlank { UUID.randomUUID().toString() },
                    type = typeName,
                    lat = obj.optDouble("lat", 0.0),
                    lon = obj.optDouble("lon", 0.0),
                    bearing = obj.optDouble("bearing", -1.0).toFloat(),
                    roadName = obj.optString("roadName", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", nowMillis),
                    expiresAt = expiresAt,
                    confirmations = obj.optInt("confirmations", 1).coerceAtLeast(1),
                    syncStatus = "LOCAL_ONLY"
                )
            )
        }
        return out
    }

    fun exportFullBackupJson(
        settings: UserSettings,
        savedPlaces: List<SavedPlaceEntity>,
        reports: List<UserReportEntity>
    ): String {
        val settingsObj = JSONObject().apply {
            put("language", settings.language.code)
            put("distanceUnit", settings.distanceUnit.code)
            put("themeMode", settings.themeMode.code)
            put("voiceGuidanceEnabled", settings.voiceGuidanceEnabled)
            put("speedWarningAudioEnabled", settings.speedWarningAudioEnabled)
            put("speedWarningThresholdMph", settings.speedWarningThresholdMph)
            put("junctionPreviewEnabled", settings.junctionPreviewEnabled)
            put("alertSpeedDropEnabled", settings.alertSpeedDropEnabled)
            put("alertSharpCurveEnabled", settings.alertSharpCurveEnabled)
            put("alertTollBoothEnabled", settings.alertTollBoothEnabled)
            put("alertRailroadEnabled", settings.alertRailroadEnabled)
            put("alertSchoolZoneEnabled", settings.alertSchoolZoneEnabled)
            put("alertStopSignEnabled", settings.alertStopSignEnabled)
            put("is3DView", settings.is3DView)
            put("routePreference", settings.routePreference.code)
            put("avoidTolls", settings.avoidTolls)
            put("avoidHighways", settings.avoidHighways)
            put("avoidFerries", settings.avoidFerries)
            put("githubOwner", settings.githubOwner)
            put("githubRepo", settings.githubRepo)
            put("packBaseUrl", settings.packBaseUrl)
        }

        val placesArr = JSONArray()
        for (p in savedPlaces) {
            placesArr.put(JSONObject().apply {
                put("type", p.type)
                put("name", p.name)
                put("address", p.address)
                put("category", p.category)
                put("lat", p.lat)
                put("lon", p.lon)
                put("stateCode", p.stateCode)
                put("updatedAt", p.updatedAt)
            })
        }

        val reportsRoot = JSONObject(exportReportsJson(reports))
        return JSONObject().apply {
            put("format", "rahyab_backup_v1")
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsObj)
            put("savedPlaces", placesArr)
            put("reports", reportsRoot.getJSONArray("reports"))
        }.toString(2)
    }

    fun importFullBackupJson(json: String, nowMillis: Long = System.currentTimeMillis()): ImportedFullBackup {
        val root = JSONObject(json)
        val settingsObj = root.optJSONObject("settings")
        val parsedSettings = settingsObj?.let { s ->
            UserSettings(
                language = AppLanguage.entries.find { it.code == s.optString("language") } ?: AppLanguage.ENGLISH,
                distanceUnit = DistanceUnit.entries.find { it.code == s.optString("distanceUnit") } ?: DistanceUnit.IMPERIAL,
                themeMode = MapThemeMode.entries.find { it.code == s.optString("themeMode") } ?: MapThemeMode.AUTO,
                voiceGuidanceEnabled = s.optBoolean("voiceGuidanceEnabled", true),
                speedWarningAudioEnabled = s.optBoolean("speedWarningAudioEnabled", true),
                speedWarningThresholdMph = s.optInt("speedWarningThresholdMph", 5),
                junctionPreviewEnabled = s.optBoolean("junctionPreviewEnabled", true),
                alertSpeedDropEnabled = s.optBoolean("alertSpeedDropEnabled", true),
                alertSharpCurveEnabled = s.optBoolean("alertSharpCurveEnabled", true),
                alertTollBoothEnabled = s.optBoolean("alertTollBoothEnabled", true),
                alertRailroadEnabled = s.optBoolean("alertRailroadEnabled", true),
                alertSchoolZoneEnabled = s.optBoolean("alertSchoolZoneEnabled", true),
                alertStopSignEnabled = s.optBoolean("alertStopSignEnabled", false),
                is3DView = s.optBoolean("is3DView", true),
                routePreference = RoutePreference.entries.find { it.code == s.optString("routePreference") }
                    ?: RoutePreference.FASTEST,
                avoidTolls = s.optBoolean("avoidTolls", false),
                avoidHighways = s.optBoolean("avoidHighways", false),
                avoidFerries = s.optBoolean("avoidFerries", false),
                githubOwner = s.optString("githubOwner", UserSettings.DEFAULT_GITHUB_OWNER),
                githubRepo = s.optString("githubRepo", UserSettings.DEFAULT_GITHUB_REPO),
                packBaseUrl = s.optString("packBaseUrl", ""),
                onboardingCompleted = true
            )
        }

        val placesArr = root.optJSONArray("savedPlaces") ?: JSONArray()
        val places = mutableListOf<SavedPlaceEntity>()
        for (i in 0 until placesArr.length()) {
            val p = placesArr.optJSONObject(i) ?: continue
            val typeStr = p.optString("type", SavedPlaceType.FAVORITE.name)
            if (runCatching { SavedPlaceType.valueOf(typeStr) }.isFailure) continue
            places.add(
                SavedPlaceEntity(
                    type = typeStr,
                    name = p.optString("name", ""),
                    address = p.optString("address", ""),
                    category = p.optString("category", ""),
                    lat = p.optDouble("lat", 0.0),
                    lon = p.optDouble("lon", 0.0),
                    stateCode = p.optString("stateCode", ""),
                    updatedAt = p.optLong("updatedAt", nowMillis)
                )
            )
        }

        val reports = importReportsJson(json, nowMillis)
        return ImportedFullBackup(
            settings = parsedSettings,
            savedPlaces = places,
            reports = reports
        )
    }
}
