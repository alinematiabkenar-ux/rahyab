package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.car.CarMapView
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import com.example.map.LaneHighlightColor
import com.example.navigation.CyclingSpeedPreset
import com.example.navigation.LaneChangeTiming
import com.example.navigation.RoutePreference
import com.example.navigation.TravelMode
import com.example.navigation.WalkingSpeedPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rahYabDataStore: DataStore<Preferences> by preferencesDataStore(name = "rahyab_prefs")

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
        val ALERT_SPEED_CAMERA = booleanPreferencesKey("alert_speed_camera")
        val ALERT_RED_LIGHT_CAMERA = booleanPreferencesKey("alert_red_light_camera")
        val ALERT_SPEED_BUMP = booleanPreferencesKey("alert_speed_bump")
        val ALERT_STEEP_GRADE = booleanPreferencesKey("alert_steep_grade")
        val SHOW_TRAFFIC_LIGHTS = booleanPreferencesKey("show_traffic_lights")
        val SHOW_3D_BUILDINGS = booleanPreferencesKey("show_3d_buildings")
        val POSITION_MARKER_STYLE = stringPreferencesKey("position_marker_style")
        val IS_3D_VIEW = booleanPreferencesKey("is_3d_view")
        val ROUTE_PREFERENCE = stringPreferencesKey("route_preference")
        val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val AVOID_HIGHWAYS = booleanPreferencesKey("avoid_highways")
        val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")
        val DEFAULT_TRAVEL_MODE = stringPreferencesKey("default_travel_mode")
        val LAST_TRAVEL_MODE = stringPreferencesKey("last_travel_mode")
        val WALKING_SPEED_PRESET = stringPreferencesKey("walking_speed_preset")
        val CYCLING_SPEED_PRESET = stringPreferencesKey("cycling_speed_preset")
        val AVOID_HILLS_WHEN_CYCLING = booleanPreferencesKey("avoid_hills_when_cycling")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val PACK_BASE_URL = stringPreferencesKey("pack_base_url")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEV_ONLINE_OREGON = booleanPreferencesKey("dev_online_oregon_mode")
        val SIMULATE_DRIVE = booleanPreferencesKey("simulate_drive_enabled")
        val PERSIAN_TTS_NOTICE_SHOWN = booleanPreferencesKey("persian_tts_notice_shown")
        val USE_ENGLISH_VOICE_IN_PERSIAN = booleanPreferencesKey("use_english_voice_in_persian_mode")
        val ONLINE_FEATURES_ENABLED = booleanPreferencesKey("online_features_enabled")
        val ONLINE_WEATHER_ALERTS_ENABLED = booleanPreferencesKey("online_weather_alerts_enabled")
        val ONLINE_SEARCH_FALLBACK_ENABLED = booleanPreferencesKey("online_search_fallback_enabled")
        val GOOGLE_MAPS_ONLINE_SEARCH_ENABLED = booleanPreferencesKey("google_maps_online_search_enabled")
        val AUTO_CHECK_MAP_UPDATES_ENABLED = booleanPreferencesKey("auto_check_map_updates_enabled")
        val WIFI_ONLY_LARGE_DOWNLOADS = booleanPreferencesKey("wifi_only_large_downloads")
        val AUTO_UPDATE_MAPS_ON_WIFI = booleanPreferencesKey("auto_update_maps_on_wifi")
        val LAST_MANIFEST_CHECK_EPOCH_MS = longPreferencesKey("last_manifest_check_epoch_ms")
        val LAST_MAP_UPDATE_CHECK_RESULT = stringPreferencesKey("last_map_update_check_result")
        val SKIP_PARKING_AT_HOME = booleanPreferencesKey("skip_parking_at_home")
        val CAR_BT_DEVICE_ADDRESS = stringPreferencesKey("car_bt_device_address")
        val CAR_BT_DEVICE_NAME = stringPreferencesKey("car_bt_device_name")
    }

    val settingsFlow: Flow<UserSettings> = context.rahYabDataStore.data.map { prefs ->
        mapPreferencesToSettings(prefs)
    }

    companion object {
        val KEY_ALERT_STOP_SIGN = booleanPreferencesKey("alert_stop_sign")
        val KEY_ALERT_TRAFFIC_LIGHT = booleanPreferencesKey("alert_traffic_light")
        val KEY_SHOW_TRAFFIC_LIGHTS = booleanPreferencesKey("show_traffic_lights")
        val KEY_SHOW_STOP_SIGNS_ON_MAP = booleanPreferencesKey("show_stop_signs_on_map")
        val KEY_LANE_CHANGE_BEEPS = booleanPreferencesKey("lane_change_beeps_enabled")
        val KEY_LANE_CHANGE_TIMING = stringPreferencesKey("lane_change_timing")
        val KEY_LAST_CHANCE_BEEP = booleanPreferencesKey("last_chance_beep_enabled")
        val KEY_LANE_HIGHLIGHT_ON_MAP = booleanPreferencesKey("lane_highlight_on_map")
        val KEY_LANE_HIGHLIGHT_COLOR = stringPreferencesKey("lane_highlight_color")
        val KEY_LANE_COLOR_MIGRATED_TO_BLUE = booleanPreferencesKey("lane_color_migrated_to_blue_v32")
        val KEY_LANE_COLOR_USER_MODIFIED = booleanPreferencesKey("lane_color_user_modified")
        val KEY_CAR_MAP_VIEW = stringPreferencesKey("car_map_view")
        val KEY_TURN_FOCUS_MODE = booleanPreferencesKey("turn_focus_mode_enabled")
        val KEY_TURN_FOCUS_BEEPS = booleanPreferencesKey("turn_focus_beeps_enabled")
        val KEY_TURN_FOCUS_CITY_DIST = intPreferencesKey("turn_focus_city_distance_m")
        val KEY_TURN_FOCUS_CITY_DIST_MIGRATED_V40 = booleanPreferencesKey("turn_focus_city_dist_migrated_v40")
        val KEY_TURN_FOCUS_CITY_DIST_USER_MODIFIED = booleanPreferencesKey("turn_focus_city_dist_user_modified")
        val KEY_TURN_FOCUS_HIGHWAY_DIST = intPreferencesKey("turn_focus_highway_distance_m")
        val KEY_TURN_FOCUS_WIDE_HIGHWAY = booleanPreferencesKey("turn_focus_wide_highway_earlier")
        val KEY_TURN_FOCUS_SHORT_PHRASE = booleanPreferencesKey("turn_focus_short_phrase_enabled")

        /**
         * Preserves any existing supported city Turn Focus distance (including legacy 500 m saved
         * without a user-modified flag). Defaults to 1,000 m only when the key is absent.
         */
        fun resolveMigratedTurnFocusCityDistanceMeters(
            storedMeters: Int?,
            migratedToV40: Boolean,
            userModified: Boolean
        ): Int {
            return (storedMeters ?: 1000).coerceIn(300, 1500)
        }

        /**
         * F6: One-time migration from the legacy default RED lane highlight color to BLUE.
         * Preserves any explicit user choice (e.g. GREEN, or RED explicitly chosen after migration).
         */
        fun resolveMigratedLaneColor(
            storedCode: String?,
            migratedToBlue: Boolean,
            userModified: Boolean
        ): LaneHighlightColor {
            if (!migratedToBlue && !userModified &&
                (storedCode == null || storedCode.equals(LaneHighlightColor.RED.code, ignoreCase = true))
            ) {
                return LaneHighlightColor.BLUE
            }
            return LaneHighlightColor.fromCode(storedCode)
        }

        fun mapPreferencesToSettings(prefs: Preferences): UserSettings {
            val defaultMode = TravelMode.fromCode(prefs[Keys.DEFAULT_TRAVEL_MODE])
            return UserSettings(
                language = AppLanguage.entries.find { it.code == prefs[Keys.LANGUAGE] } ?: AppLanguage.ENGLISH,
                distanceUnit = DistanceUnit.entries.find { it.code == prefs[Keys.DISTANCE_UNIT] } ?: DistanceUnit.IMPERIAL,
                themeMode = MapThemeMode.entries.find { it.code == prefs[Keys.THEME_MODE] } ?: MapThemeMode.AUTO,
                carMapView = CarMapView.fromCode(prefs[KEY_CAR_MAP_VIEW]),
                voiceGuidanceEnabled = prefs[Keys.VOICE_ENABLED] ?: true,
                speedWarningAudioEnabled = prefs[Keys.SPEED_AUDIO_ENABLED] ?: true,
                speedWarningThresholdMph = prefs[Keys.SPEED_THRESHOLD_MPH] ?: 5,
                junctionPreviewEnabled = prefs[Keys.JUNCTION_PREVIEW_ENABLED] ?: true,
                laneHighlightOnMapEnabled = prefs[KEY_LANE_HIGHLIGHT_ON_MAP] ?: true,
                laneHighlightColor = resolveMigratedLaneColor(
                    storedCode = prefs[KEY_LANE_HIGHLIGHT_COLOR],
                    migratedToBlue = prefs[KEY_LANE_COLOR_MIGRATED_TO_BLUE] ?: false,
                    userModified = prefs[KEY_LANE_COLOR_USER_MODIFIED] ?: false
                ),
                turnFocusModeEnabled = prefs[KEY_TURN_FOCUS_MODE] ?: true,
                turnFocusBeepsEnabled = prefs[KEY_TURN_FOCUS_BEEPS] ?: true,
                turnFocusCityDistanceMeters = resolveMigratedTurnFocusCityDistanceMeters(
                    storedMeters = prefs[KEY_TURN_FOCUS_CITY_DIST],
                    migratedToV40 = prefs[KEY_TURN_FOCUS_CITY_DIST_MIGRATED_V40] ?: false,
                    userModified = prefs[KEY_TURN_FOCUS_CITY_DIST_USER_MODIFIED] ?: false
                ),
                turnFocusHighwayDistanceMeters = (prefs[KEY_TURN_FOCUS_HIGHWAY_DIST] ?: 1000).coerceIn(500, 1500),
                turnFocusWideHighwayEarlierEnabled = prefs[KEY_TURN_FOCUS_WIDE_HIGHWAY] ?: true,
                turnFocusShortPhraseEnabled = prefs[KEY_TURN_FOCUS_SHORT_PHRASE] ?: false,
                laneChangeBeepsEnabled = prefs[KEY_LANE_CHANGE_BEEPS] ?: true,
                laneChangeTiming = LaneChangeTiming.fromCode(prefs[KEY_LANE_CHANGE_TIMING]),
                lastChanceBeepEnabled = prefs[KEY_LAST_CHANCE_BEEP] ?: true,
                alertSpeedDropEnabled = prefs[Keys.ALERT_SPEED_DROP] ?: true,
                alertSharpCurveEnabled = prefs[Keys.ALERT_SHARP_CURVE] ?: true,
                alertTollBoothEnabled = prefs[Keys.ALERT_TOLL_BOOTH] ?: true,
                alertRailroadEnabled = prefs[Keys.ALERT_RAILROAD] ?: true,
                alertSchoolZoneEnabled = prefs[Keys.ALERT_SCHOOL_ZONE] ?: true,
                alertStopSignEnabled = prefs[KEY_ALERT_STOP_SIGN] ?: false,
                alertTrafficLightEnabled = prefs[KEY_ALERT_TRAFFIC_LIGHT] ?: false,
                showTrafficLights = prefs[KEY_SHOW_TRAFFIC_LIGHTS] ?: true,
                showStopSignsOnMap = prefs[KEY_SHOW_STOP_SIGNS_ON_MAP] ?: true,
                alertSpeedCameraEnabled = prefs[Keys.ALERT_SPEED_CAMERA] ?: true,
                alertRedLightCameraEnabled = prefs[Keys.ALERT_RED_LIGHT_CAMERA] ?: true,
                alertSpeedBumpEnabled = prefs[Keys.ALERT_SPEED_BUMP] ?: true,
                alertSteepGradeEnabled = prefs[Keys.ALERT_STEEP_GRADE] ?: true,
                show3DBuildings = prefs[Keys.SHOW_3D_BUILDINGS] ?: true,
                positionMarkerStyle = prefs[Keys.POSITION_MARKER_STYLE]?.takeIf { it == "car" || it == "arrow" } ?: "arrow",
                is3DView = prefs[Keys.IS_3D_VIEW] ?: true,
                routePreference = RoutePreference.entries.find { it.code == prefs[Keys.ROUTE_PREFERENCE] }
                    ?: RoutePreference.FASTEST,
                avoidTolls = prefs[Keys.AVOID_TOLLS] ?: false,
                avoidHighways = prefs[Keys.AVOID_HIGHWAYS] ?: false,
                avoidFerries = prefs[Keys.AVOID_FERRIES] ?: false,
                defaultTravelMode = defaultMode,
                lastTravelMode = prefs[Keys.LAST_TRAVEL_MODE]?.let { TravelMode.fromCode(it) } ?: defaultMode,
                walkingSpeedPreset = WalkingSpeedPreset.fromCode(prefs[Keys.WALKING_SPEED_PRESET]),
                cyclingSpeedPreset = CyclingSpeedPreset.fromCode(prefs[Keys.CYCLING_SPEED_PRESET]),
                avoidHillsWhenCycling = prefs[Keys.AVOID_HILLS_WHEN_CYCLING] ?: false,
                githubOwner = prefs[Keys.GITHUB_OWNER]?.takeIf { it.isNotBlank() } ?: UserSettings.DEFAULT_GITHUB_OWNER,
                githubRepo = prefs[Keys.GITHUB_REPO]?.takeIf { it.isNotBlank() } ?: UserSettings.DEFAULT_GITHUB_REPO,
                packBaseUrl = prefs[Keys.PACK_BASE_URL] ?: "",
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                devOnlineOregonMode = prefs[Keys.DEV_ONLINE_OREGON] ?: false,
                simulateDriveEnabled = prefs[Keys.SIMULATE_DRIVE] ?: false,
                persianTtsNoticeShown = prefs[Keys.PERSIAN_TTS_NOTICE_SHOWN] ?: false,
                useEnglishVoiceInPersianMode = prefs[Keys.USE_ENGLISH_VOICE_IN_PERSIAN] ?: false,
                onlineFeaturesEnabled = prefs[Keys.ONLINE_FEATURES_ENABLED] ?: true,
                onlineWeatherAlertsEnabled = prefs[Keys.ONLINE_WEATHER_ALERTS_ENABLED] ?: true,
                onlineSearchFallbackEnabled = prefs[Keys.ONLINE_SEARCH_FALLBACK_ENABLED] ?: true,
                googleMapsOnlineSearchEnabled = prefs[Keys.GOOGLE_MAPS_ONLINE_SEARCH_ENABLED] ?: true,
                autoCheckMapUpdatesEnabled = prefs[Keys.AUTO_CHECK_MAP_UPDATES_ENABLED] ?: true,
                wifiOnlyLargeDownloads = prefs[Keys.WIFI_ONLY_LARGE_DOWNLOADS] ?: true,
                autoUpdateMapsOnWifi = prefs[Keys.AUTO_UPDATE_MAPS_ON_WIFI] ?: false,
                lastManifestCheckEpochMs = prefs[Keys.LAST_MANIFEST_CHECK_EPOCH_MS] ?: 0L,
                lastMapUpdateCheckResult = prefs[Keys.LAST_MAP_UPDATE_CHECK_RESULT] ?: "",
                skipParkingAtHome = prefs[Keys.SKIP_PARKING_AT_HOME] ?: true,
                carBluetoothDeviceAddress = prefs[Keys.CAR_BT_DEVICE_ADDRESS] ?: "",
                carBluetoothDeviceName = prefs[Keys.CAR_BT_DEVICE_NAME] ?: ""
            )
        }
    }

    suspend fun setLanguage(language: AppLanguage) { context.rahYabDataStore.edit { it[Keys.LANGUAGE] = language.code } }
    suspend fun setDistanceUnit(unit: DistanceUnit) { context.rahYabDataStore.edit { it[Keys.DISTANCE_UNIT] = unit.code } }
    suspend fun setThemeMode(mode: MapThemeMode) { context.rahYabDataStore.edit { it[Keys.THEME_MODE] = mode.code } }
    suspend fun setCarMapView(view: CarMapView) { context.rahYabDataStore.edit { it[KEY_CAR_MAP_VIEW] = view.code } }
    suspend fun setVoiceGuidanceEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.VOICE_ENABLED] = enabled } }
    suspend fun setSpeedWarningAudioEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.SPEED_AUDIO_ENABLED] = enabled } }
    suspend fun setSpeedWarningThresholdMph(thresholdMph: Int) { context.rahYabDataStore.edit { it[Keys.SPEED_THRESHOLD_MPH] = thresholdMph.coerceIn(0, 15) } }
    suspend fun setJunctionPreviewEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.JUNCTION_PREVIEW_ENABLED] = enabled } }
    suspend fun setLaneHighlightOnMapEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_LANE_HIGHLIGHT_ON_MAP] = enabled } }
    suspend fun setLaneHighlightColor(color: LaneHighlightColor) {
        context.rahYabDataStore.edit {
            it[KEY_LANE_HIGHLIGHT_COLOR] = color.code
            it[KEY_LANE_COLOR_USER_MODIFIED] = true
            it[KEY_LANE_COLOR_MIGRATED_TO_BLUE] = true
        }
    }

    suspend fun runOneTimeLaneColorMigrationIfNeeded() {
        context.rahYabDataStore.edit { prefs ->
            if (prefs[KEY_LANE_COLOR_MIGRATED_TO_BLUE] == true) return@edit
            val userModified = prefs[KEY_LANE_COLOR_USER_MODIFIED] == true
            val stored = prefs[KEY_LANE_HIGHLIGHT_COLOR]
            if (!userModified && (stored == null || stored.equals(LaneHighlightColor.RED.code, ignoreCase = true))) {
                prefs[KEY_LANE_HIGHLIGHT_COLOR] = LaneHighlightColor.BLUE.code
            }
            prefs[KEY_LANE_COLOR_MIGRATED_TO_BLUE] = true
        }
    }
    suspend fun runOneTimeTurnFocusDistanceMigrationIfNeeded() {
        context.rahYabDataStore.edit { prefs ->
            if (prefs[KEY_TURN_FOCUS_CITY_DIST_MIGRATED_V40] == true) return@edit
            val stored = prefs[KEY_TURN_FOCUS_CITY_DIST]
            if (stored == null) {
                prefs[KEY_TURN_FOCUS_CITY_DIST] = 1000
            }
            prefs[KEY_TURN_FOCUS_CITY_DIST_MIGRATED_V40] = true
        }
    }

    suspend fun setTurnFocusModeEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_TURN_FOCUS_MODE] = enabled } }
    suspend fun setTurnFocusBeepsEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_TURN_FOCUS_BEEPS] = enabled } }
    suspend fun setTurnFocusCityDistanceMeters(meters: Int) {
        context.rahYabDataStore.edit {
            it[KEY_TURN_FOCUS_CITY_DIST] = meters.coerceIn(300, 1500)
            it[KEY_TURN_FOCUS_CITY_DIST_USER_MODIFIED] = true
            it[KEY_TURN_FOCUS_CITY_DIST_MIGRATED_V40] = true
        }
    }
    suspend fun setTurnFocusHighwayDistanceMeters(meters: Int) {
        context.rahYabDataStore.edit {
            it[KEY_TURN_FOCUS_HIGHWAY_DIST] = meters.coerceIn(500, 1500)
        }
    }
    suspend fun setTurnFocusWideHighwayEarlierEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_TURN_FOCUS_WIDE_HIGHWAY] = enabled } }
    suspend fun setTurnFocusShortPhraseEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_TURN_FOCUS_SHORT_PHRASE] = enabled } }
    suspend fun setLaneChangeBeepsEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_LANE_CHANGE_BEEPS] = enabled } }
    suspend fun setLaneChangeTiming(timing: LaneChangeTiming) { context.rahYabDataStore.edit { it[KEY_LANE_CHANGE_TIMING] = timing.code } }
    suspend fun setLastChanceBeepEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_LAST_CHANCE_BEEP] = enabled } }
    suspend fun setAlertSpeedDropEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_SPEED_DROP] = enabled } }
    suspend fun setAlertSharpCurveEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_SHARP_CURVE] = enabled } }
    suspend fun setAlertTollBoothEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_TOLL_BOOTH] = enabled } }
    suspend fun setAlertRailroadEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_RAILROAD] = enabled } }
    suspend fun setAlertSchoolZoneEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_SCHOOL_ZONE] = enabled } }
    suspend fun setAlertStopSignEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_ALERT_STOP_SIGN] = enabled } }
    suspend fun setAlertTrafficLightEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_ALERT_TRAFFIC_LIGHT] = enabled } }
    suspend fun setAlertSpeedCameraEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_SPEED_CAMERA] = enabled } }
    suspend fun setAlertRedLightCameraEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_RED_LIGHT_CAMERA] = enabled } }
    suspend fun setAlertSpeedBumpEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_SPEED_BUMP] = enabled } }
    suspend fun setAlertSteepGradeEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ALERT_STEEP_GRADE] = enabled } }
    suspend fun setShowTrafficLights(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_SHOW_TRAFFIC_LIGHTS] = enabled } }
    suspend fun setShowStopSignsOnMap(enabled: Boolean) { context.rahYabDataStore.edit { it[KEY_SHOW_STOP_SIGNS_ON_MAP] = enabled } }
    suspend fun set3DView(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.IS_3D_VIEW] = enabled } }
    suspend fun setRoutePreference(preference: RoutePreference) { context.rahYabDataStore.edit { it[Keys.ROUTE_PREFERENCE] = preference.code } }
    suspend fun setAvoidTolls(avoid: Boolean) { context.rahYabDataStore.edit { it[Keys.AVOID_TOLLS] = avoid } }
    suspend fun setAvoidHighways(avoid: Boolean) { context.rahYabDataStore.edit { it[Keys.AVOID_HIGHWAYS] = avoid } }
    suspend fun setAvoidFerries(avoid: Boolean) { context.rahYabDataStore.edit { it[Keys.AVOID_FERRIES] = avoid } }
    suspend fun setDefaultTravelMode(mode: TravelMode) { context.rahYabDataStore.edit { it[Keys.DEFAULT_TRAVEL_MODE] = mode.code } }
    suspend fun setLastTravelMode(mode: TravelMode) { context.rahYabDataStore.edit { it[Keys.LAST_TRAVEL_MODE] = mode.code } }
    suspend fun setWalkingSpeedPreset(preset: WalkingSpeedPreset) { context.rahYabDataStore.edit { it[Keys.WALKING_SPEED_PRESET] = preset.code } }
    suspend fun setCyclingSpeedPreset(preset: CyclingSpeedPreset) { context.rahYabDataStore.edit { it[Keys.CYCLING_SPEED_PRESET] = preset.code } }
    suspend fun setAvoidHillsWhenCycling(avoid: Boolean) { context.rahYabDataStore.edit { it[Keys.AVOID_HILLS_WHEN_CYCLING] = avoid } }

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

    suspend fun setOnboardingCompleted(completed: Boolean) { context.rahYabDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed } }
    suspend fun setDevOnlineOregonMode(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.DEV_ONLINE_OREGON] = enabled } }
    suspend fun setSimulateDriveEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.SIMULATE_DRIVE] = enabled } }
    suspend fun markPersianTtsNoticeShown() { context.rahYabDataStore.edit { it[Keys.PERSIAN_TTS_NOTICE_SHOWN] = true } }
    suspend fun setUseEnglishVoiceInPersianMode(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.USE_ENGLISH_VOICE_IN_PERSIAN] = enabled } }
    suspend fun setOnlineFeaturesEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ONLINE_FEATURES_ENABLED] = enabled } }
    suspend fun setOnlineWeatherAlertsEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ONLINE_WEATHER_ALERTS_ENABLED] = enabled } }
    suspend fun setOnlineSearchFallbackEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.ONLINE_SEARCH_FALLBACK_ENABLED] = enabled } }
    suspend fun setGoogleMapsOnlineSearchEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.GOOGLE_MAPS_ONLINE_SEARCH_ENABLED] = enabled } }
    suspend fun setAutoCheckMapUpdatesEnabled(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.AUTO_CHECK_MAP_UPDATES_ENABLED] = enabled } }
    suspend fun setWifiOnlyLargeDownloads(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.WIFI_ONLY_LARGE_DOWNLOADS] = enabled } }
    suspend fun setAutoUpdateMapsOnWifi(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.AUTO_UPDATE_MAPS_ON_WIFI] = enabled } }
    suspend fun setLastManifestCheckEpochMs(epochMs: Long) { context.rahYabDataStore.edit { it[Keys.LAST_MANIFEST_CHECK_EPOCH_MS] = epochMs } }

    suspend fun setLastMapUpdateCheck(epochMs: Long, result: String) {
        context.rahYabDataStore.edit {
            it[Keys.LAST_MANIFEST_CHECK_EPOCH_MS] = epochMs
            it[Keys.LAST_MAP_UPDATE_CHECK_RESULT] = result.take(120)
        }
    }

    suspend fun setShow3DBuildings(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.SHOW_3D_BUILDINGS] = enabled } }

    suspend fun setPositionMarkerStyle(style: String) {
        val safe = if (style == "car") "car" else "arrow"
        context.rahYabDataStore.edit { it[Keys.POSITION_MARKER_STYLE] = safe }
    }

    suspend fun setSkipParkingAtHome(enabled: Boolean) { context.rahYabDataStore.edit { it[Keys.SKIP_PARKING_AT_HOME] = enabled } }

    suspend fun setCarBluetoothDevice(address: String, name: String) {
        context.rahYabDataStore.edit {
            it[Keys.CAR_BT_DEVICE_ADDRESS] = address.trim()
            it[Keys.CAR_BT_DEVICE_NAME] = name.trim()
        }
    }
}
