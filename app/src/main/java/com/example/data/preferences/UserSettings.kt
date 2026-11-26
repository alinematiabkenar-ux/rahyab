package com.example.data.preferences

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

data class UserSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val distanceUnit: DistanceUnit = DistanceUnit.IMPERIAL,
    val themeMode: MapThemeMode = MapThemeMode.AUTO,
    val carMapView: CarMapView = CarMapView.NORMAL,
    val voiceGuidanceEnabled: Boolean = true,
    val speedWarningAudioEnabled: Boolean = true,
    val speedWarningThresholdMph: Int = 5, // 0, 5, or 10 mph (converted automatically for km/h)
    val junctionPreviewEnabled: Boolean = true,
    // LN3 / F6: Lane highlight on the map itself (default BLUE)
    val laneHighlightOnMapEnabled: Boolean = true,
    val laneHighlightColor: LaneHighlightColor = LaneHighlightColor.BLUE,
    // F1-F7: Turn Focus Mode (3 beeps, zoom, blue lanes)
    val turnFocusModeEnabled: Boolean = true,
    val turnFocusBeepsEnabled: Boolean = true,
    val turnFocusCityDistanceMeters: Int = 500,
    val turnFocusHighwayDistanceMeters: Int = 1000,
    val turnFocusWideHighwayEarlierEnabled: Boolean = true,
    val turnFocusShortPhraseEnabled: Boolean = false,
    // L4: Lane change beeps & timing
    val laneChangeBeepsEnabled: Boolean = true,
    val laneChangeTiming: LaneChangeTiming = LaneChangeTiming.NORMAL,
    val lastChanceBeepEnabled: Boolean = true,
    // Individual Phase 3 Road Alerts toggles
    val alertSpeedDropEnabled: Boolean = true,
    val alertSharpCurveEnabled: Boolean = true,
    val alertTollBoothEnabled: Boolean = true,
    val alertRailroadEnabled: Boolean = true,
    val alertSchoolZoneEnabled: Boolean = true,
    val alertStopSignEnabled: Boolean = false, // Optional — OFF by default
    val alertTrafficLightEnabled: Boolean = false, // S1: Traffic light voice alert, default OFF
    val showTrafficLights: Boolean = true,
    val showStopSignsOnMap: Boolean = true, // S1: Show stop signs on map, default ON
    val alertSpeedCameraEnabled: Boolean = true,
    val alertRedLightCameraEnabled: Boolean = true,
    val alertSpeedBumpEnabled: Boolean = true,
    val alertSteepGradeEnabled: Boolean = true,
    val show3DBuildings: Boolean = true,
    val positionMarkerStyle: String = "arrow", // "arrow" or "car"
    val is3DView: Boolean = true,
    val routePreference: RoutePreference = RoutePreference.FASTEST,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    // Phase M: Travel mode & walk/bike speed settings
    val defaultTravelMode: TravelMode = TravelMode.DRIVE,
    val lastTravelMode: TravelMode = TravelMode.DRIVE,
    val walkingSpeedPreset: WalkingSpeedPreset = WalkingSpeedPreset.NORMAL,
    val cyclingSpeedPreset: CyclingSpeedPreset = CyclingSpeedPreset.NORMAL,
    val avoidHillsWhenCycling: Boolean = false,
    // Phase 5: GitHub Releases default pack source + optional custom URL override
    val githubOwner: String = DEFAULT_GITHUB_OWNER,
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val packBaseUrl: String = "",
    // Onboarding & Developer Testing
    val onboardingCompleted: Boolean = false,
    val devOnlineOregonMode: Boolean = false,
    val simulateDriveEnabled: Boolean = false,
    val persianTtsNoticeShown: Boolean = false,
    val useEnglishVoiceInPersianMode: Boolean = false,
    // Offline-first, online-when-available controls
    val onlineFeaturesEnabled: Boolean = true,
    val onlineWeatherAlertsEnabled: Boolean = true,
    val onlineSearchFallbackEnabled: Boolean = true,
    val autoCheckMapUpdatesEnabled: Boolean = true,
    val wifiOnlyLargeDownloads: Boolean = true,
    val autoUpdateMapsOnWifi: Boolean = false,
    val lastManifestCheckEpochMs: Long = 0L,
    val lastMapUpdateCheckResult: String = "",
    // F4: Parked car location
    val skipParkingAtHome: Boolean = true,
    val carBluetoothDeviceAddress: String = "",
    val carBluetoothDeviceName: String = ""
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
