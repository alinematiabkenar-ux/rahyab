package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rahYabDataStore: DataStore<Preferences> by preferencesDataStore(name = "rahyab_prefs")

data class UserSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val distanceUnit: DistanceUnit = DistanceUnit.IMPERIAL,
    val themeMode: MapThemeMode = MapThemeMode.AUTO,
    val voiceGuidanceEnabled: Boolean = true,
    val speedWarningAudioEnabled: Boolean = true,
    val speedWarningThresholdMph: Int = 5, // 0, 5, or 10 mph (converted automatically for km/h)
    val junctionPreviewEnabled: Boolean = true,
    // Individual Phase 3 Road Alerts toggles
    val alertSpeedDropEnabled: Boolean = true,
    val alertSharpCurveEnabled: Boolean = true,
    val alertTollBoothEnabled: Boolean = true,
    val alertRailroadEnabled: Boolean = true,
    val alertSchoolZoneEnabled: Boolean = true,
    val alertStopSignEnabled: Boolean = false, // Optional — OFF by default
    val is3DView: Boolean = true,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    // Phase 5: GitHub Releases default pack source + optional custom URL override
    val githubOwner: String = DEFAULT_GITHUB_OWNER,
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val packBaseUrl: String = "",
    // Onboarding & Developer Testing
    val onboardingCompleted: Boolean = false,
    val devOnlineOregonMode: Boolean = false,
    val simulateDriveEnabled: Boolean = false,
    val persianTtsNoticeShown: Boolean = false
) {
    /**
     * Effective pack host base URL: uses explicit custom Base URL if entered,
     * otherwise derives the GitHub Release latest download URL from `githubOwner` / `githubRepo`.
     */
    val effectivePackBaseUrl: String
        get() {
            val custom = packBaseUrl.trim().trimEnd('/')
            if (custom.isNotEmpty()) return custom
            val owner = githubOwner.trim()
            val repo = githubRepo.trim()
            return if (owner.isNotEmpty() && repo.isNotEmpty()) {
                "https://github.com/$owner/$repo/releases/latest/download"
            } else {
                ""
            }
        }

    companion object {
        const val DEFAULT_GITHUB_OWNER = "alinematiabkenar-ux"
        const val DEFAULT_GITHUB_REPO = "rahyab"
    }
}

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val SPEED_AUDIO_ENABLED = booleanPreferencesKey("speed_audio_enabled")
        val SPEED_THRESHOLD_MPH = intPreferencesKey("speed_threshold_mph")
        val JUNCTION_PREVIEW_ENABLED = booleanPreferencesKey("junction_preview_enabled")
        val ALERT_SPEED_DROP = booleanPreferencesKey("alert_speed_drop")
        val ALERT_SHARP_CURVE = booleanPreferencesKey("alert_sharp_curve")
        val ALERT_TOLL_BOOTH = booleanPreferencesKey("alert_toll_booth")
        val ALERT_RAILROAD = booleanPreferencesKey("alert_railroad")
        val ALERT_SCHOOL_ZONE = booleanPreferencesKey("alert_school_zone")
        val ALERT_STOP_SIGN = booleanPreferencesKey("alert_stop_sign")
        val IS_3D_VIEW = booleanPreferencesKey("is_3d_view")
        val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val AVOID_HIGHWAYS = booleanPreferencesKey("avoid_highways")
        val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val PACK_BASE_URL = stringPreferencesKey("pack_base_url")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEV_ONLINE_OREGON = booleanPreferencesKey("dev_online_oregon_mode")
        val SIMULATE_DRIVE = booleanPreferencesKey("simulate_drive_enabled")
        val PERSIAN_TTS_NOTICE_SHOWN = booleanPreferencesKey("persian_tts_notice_shown")
    }

    val settingsFlow: Flow<UserSettings> = context.rahYabDataStore.data.map { prefs ->
        UserSettings(
            language = AppLanguage.entries.find { it.code == prefs[Keys.LANGUAGE] } ?: AppLanguage.ENGLISH,
            distanceUnit = DistanceUnit.entries.find { it.code == prefs[Keys.DISTANCE_UNIT] } ?: DistanceUnit.IMPERIAL,
            themeMode = MapThemeMode.entries.find { it.code == prefs[Keys.THEME_MODE] } ?: MapThemeMode.AUTO,
            voiceGuidanceEnabled = prefs[Keys.VOICE_ENABLED] ?: true,
            speedWarningAudioEnabled = prefs[Keys.SPEED_AUDIO_ENABLED] ?: true,
            speedWarningThresholdMph = prefs[Keys.SPEED_THRESHOLD_MPH] ?: 5,
            junctionPreviewEnabled = prefs[Keys.JUNCTION_PREVIEW_ENABLED] ?: true,
            alertSpeedDropEnabled = prefs[Keys.ALERT_SPEED_DROP] ?: true,
            alertSharpCurveEnabled = prefs[Keys.ALERT_SHARP_CURVE] ?: true,
            alertTollBoothEnabled = prefs[Keys.ALERT_TOLL_BOOTH] ?: true,
            alertRailroadEnabled = prefs[Keys.ALERT_RAILROAD] ?: true,
            alertSchoolZoneEnabled = prefs[Keys.ALERT_SCHOOL_ZONE] ?: true,
            alertStopSignEnabled = prefs[Keys.ALERT_STOP_SIGN] ?: false,
            is3DView = prefs[Keys.IS_3D_VIEW] ?: true,
            avoidTolls = prefs[Keys.AVOID_TOLLS] ?: false,
            avoidHighways = prefs[Keys.AVOID_HIGHWAYS] ?: false,
            avoidFerries = prefs[Keys.AVOID_FERRIES] ?: false,
            githubOwner = prefs[Keys.GITHUB_OWNER]?.takeIf { it.isNotBlank() } ?: UserSettings.DEFAULT_GITHUB_OWNER,
            githubRepo = prefs[Keys.GITHUB_REPO]?.takeIf { it.isNotBlank() } ?: UserSettings.DEFAULT_GITHUB_REPO,
            packBaseUrl = prefs[Keys.PACK_BASE_URL] ?: "",
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            devOnlineOregonMode = prefs[Keys.DEV_ONLINE_OREGON] ?: false,
            simulateDriveEnabled = prefs[Keys.SIMULATE_DRIVE] ?: false,
            persianTtsNoticeShown = prefs[Keys.PERSIAN_TTS_NOTICE_SHOWN] ?: false
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.rahYabDataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        context.rahYabDataStore.edit { it[Keys.DISTANCE_UNIT] = unit.code }
    }

    suspend fun setThemeMode(mode: MapThemeMode) {
        context.rahYabDataStore.edit { it[Keys.THEME_MODE] = mode.code }
    }

    suspend fun setVoiceGuidanceEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.VOICE_ENABLED] = enabled }
    }

    suspend fun setSpeedWarningAudioEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.SPEED_AUDIO_ENABLED] = enabled }
    }

    suspend fun setSpeedWarningThresholdMph(thresholdMph: Int) {
        context.rahYabDataStore.edit { it[Keys.SPEED_THRESHOLD_MPH] = thresholdMph.coerceIn(0, 15) }
    }

    suspend fun setJunctionPreviewEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.JUNCTION_PREVIEW_ENABLED] = enabled }
    }

    suspend fun setAlertSpeedDropEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_SPEED_DROP] = enabled }
    }

    suspend fun setAlertSharpCurveEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_SHARP_CURVE] = enabled }
    }

    suspend fun setAlertTollBoothEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_TOLL_BOOTH] = enabled }
    }

    suspend fun setAlertRailroadEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_RAILROAD] = enabled }
    }

    suspend fun setAlertSchoolZoneEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_SCHOOL_ZONE] = enabled }
    }

    suspend fun setAlertStopSignEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ALERT_STOP_SIGN] = enabled }
    }

    suspend fun set3DView(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.IS_3D_VIEW] = enabled }
    }

    suspend fun setAvoidTolls(avoid: Boolean) {
        context.rahYabDataStore.edit { it[Keys.AVOID_TOLLS] = avoid }
    }

    suspend fun setAvoidHighways(avoid: Boolean) {
        context.rahYabDataStore.edit { it[Keys.AVOID_HIGHWAYS] = avoid }
    }

    suspend fun setAvoidFerries(avoid: Boolean) {
        context.rahYabDataStore.edit { it[Keys.AVOID_FERRIES] = avoid }
    }

    suspend fun setGitHubRepository(owner: String, repo: String) {
        context.rahYabDataStore.edit {
            it[Keys.GITHUB_OWNER] = owner.trim()
            it[Keys.GITHUB_REPO] = repo.trim()
        }
    }

    suspend fun setPackBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        context.rahYabDataStore.edit { it[Keys.PACK_BASE_URL] = cleaned }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.rahYabDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setDevOnlineOregonMode(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.DEV_ONLINE_OREGON] = enabled }
    }

    suspend fun setSimulateDriveEnabled(enabled: Boolean) {
        context.rahYabDataStore.edit { it[Keys.SIMULATE_DRIVE] = enabled }
    }

    suspend fun markPersianTtsNoticeShown() {
        context.rahYabDataStore.edit { it[Keys.PERSIAN_TTS_NOTICE_SHOWN] = true }
    }
}
