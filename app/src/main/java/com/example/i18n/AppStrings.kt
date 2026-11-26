package com.example.i18n

import androidx.compose.runtime.compositionLocalOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class AppLanguage(val code: String) {
    ENGLISH("en"),
    PERSIAN("fa");

    val isRtl: Boolean get() = this == PERSIAN
}

enum class DistanceUnit(val code: String) {
    IMPERIAL("imperial"),
    METRIC("metric")
}

enum class MapThemeMode(val code: String) {
    AUTO("auto"),
    DAY("day"),
    NIGHT("night")
}

/**
 * Bilingual string contract implemented as zero-arg singleton objects ([EnglishStrings] and
 * [PersianStrings]) so it never hits the 255-parameter JVM/DEX constructor limit.
 */
interface AppStrings {
    val appName: String
    val tagline: String
    // Bottom nav / navigation destinations
    val tabDrive: String
    val tabMaps: String
    val tabSettings: String
    // Search
    val searchHint: String
    val searchNoPacksTitle: String
    val searchNoPacksBody: String
    val searchNoResults: String
    val searchCategoryGas: String
    val searchCategoryPharmacy: String
    val searchCategoryGrocery: String
    val searchCategoryCoffee: String
    val searchCategoryFood: String
    val searchCategoryHospital: String
    val savedHome: String
    val savedWork: String
    val savedFavorites: String
    val savedRecent: String
    val setHome: String
    val setWork: String
    val addFavorite: String
    val removeFavorite: String
    val navigateNow: String
    val previewRoute: String
    // Routing & Navigation
    val routeOptions: String
    val avoidTolls: String
    val avoidHighways: String
    val avoidFerries: String
    val calculatingRoute: String
    val routePrimary: String
    val routeShortest: String
    val routeAlternate: String
    val routePreferenceLabel: String
    val startNavigation: String
    val endNavigation: String
    val rerouting: String
    val arrivedTitle: String
    val arrivedSubtitle: String
    val etaLabel: String
    val remainingLabel: String
    val arrivalLabel: String
    val nextManeuverExit: String
    val view3D: String
    val view2D: String
    val recenter: String
    val compassNorthUp: String
    val compassHeadingUp: String
    val compassLowAccuracyMessage: String
    val muteVoice: String
    val unmuteVoice: String
    val gpsWaiting: String
    val gpsPermissionRequired: String
    val grantLocationPermission: String
    val approximateLocationTitle: String
    val approximateLocationBanner: String
    val openAppSettings: String
    val simulationModeBadge: String
    val offlineReadyBadge: String
    val noMapPackLoaded: String
    val downloadOrImportAction: String
    val missingRoutingTiles: String
    val missingStateRouteTitle: String
    val downloadMissingStateAction: String
    // Lane guidance & signposts
    val junctionPreviewLabel: String
    val tapToClosePreview: String
    val exitLabel: String
    // Speed & Alerts
    val speedLimitTitle: String
    val speedUnitMph: String
    val speedUnitKmh: String
    val overspeedWarning: String
    val alertSpeedDrop: String
    val alertSharpCurve: String
    val alertTollBooth: String
    val alertRailroad: String
    val alertSchoolZone: String
    val alertStopSign: String
    val sectionRoadAlerts: String
    val junctionPreviewSetting: String
    val speedWarningThresholdLabel: String
    val alertToggleSpeedDrop: String
    val alertToggleSharpCurve: String
    val alertToggleTollBooth: String
    val alertToggleRailroad: String
    val alertToggleSchoolZone: String
    val alertToggleStopSign: String
    val alertToggleSpeedCamera: String
    val alertToggleRedLightCamera: String
    val alertSpeedCamera: String
    val alertRedLightCamera: String
    // Waze-style user reports (8 types + management + export/import)
    val reportButton: String
    val reportSheetTitle: String
    val reportPolice: String
    val reportHazard: String
    val reportCrash: String
    val reportClosure: String
    val reportSpeedTrap: String
    val reportConstruction: String
    val reportObjectOnRoad: String
    val reportStalledVehicle: String
    val reportSavedToast: String
    val reportAheadPrefix: String
    val reportStillThere: String
    val reportGone: String
    val activeReportsTitle: String
    val sectionMyReports: String
    val noActiveReports: String
    val addNoteHint: String
    val saveNote: String
    val exportReports: String
    val importReports: String
    // Backup & Transfer (Settings + Saved Places + Reports)
    val sectionBackupTransfer: String
    val exportBackupButton: String
    val importBackupButton: String
    val backupExportSuccess: String
    val backupImportSuccess: String
    val backupTransferFailed: String
    // State Map Packs screen
    val mapsScreenTitle: String
    val storageUsedLabel: String
    val storageAvailableLabel: String
    val refreshManifest: String
    val importLocalPack: String
    val filterStatesHint: String
    val filterAll: String
    val filterInstalled: String
    val currentStateBadge: String
    val regionalPacksHint: String
    val updateAllButton: String
    val buildMismatchTitle: String
    val buildMismatchBody: String
    val statusNotDownloaded: String
    val statusQueued: String
    val statusDownloading: String
    val statusPaused: String
    val statusVerifying: String
    val statusInstalled: String
    val statusUpdateAvailable: String
    val statusCorrupted: String
    val actionDownload: String
    val actionPause: String
    val actionResume: String
    val actionUpdate: String
    val actionDelete: String
    val actionRetry: String
    val actionImportFiles: String
    val lowStorageTitle: String
    val lowStorageBody: String
    val cancel: String
    val proceedAnyway: String
    val confirmDeleteTitle: String
    val confirmDeleteBody: String
    val manifestUnconfiguredHint: String
    val packFilesSummary: String
    val importPickStateTitle: String
    val importPickFileHint: String
    val importSuccess: String
    val importFailed: String
    val checksumMismatch: String
    val osmOnlyPlacesNote: String
    // Settings & GitHub Releases Pack Source
    val settingsTitle: String
    val sectionLanguageUnits: String
    val languageLabel: String
    val languageEnglish: String
    val languagePersian: String
    val unitsLabel: String
    val unitsImperial: String
    val unitsMetric: String
    val themeModeLabel: String
    val themeAuto: String
    val themeDay: String
    val themeNight: String
    val sectionVoiceGuidance: String
    val voiceGuidanceEnabled: String
    val speedWarningAudio: String
    val testVoiceButton: String
    val persianTtsMissingTitle: String
    val persianTtsMissingBody: String
    val openTtsSettings: String
    val sectionPackServer: String
    val githubOwnerLabel: String
    val githubRepoLabel: String
    val saveGithubRepo: String
    val customBaseUrlLabel: String
    val baseUrlPlaceholder: String
    val activePackUrlLabel: String
    val saveBaseUrl: String
    val sectionRoutingDefaults: String
    val sectionAboutLicenses: String
    val aboutTitle: String
    val appVersionLabel: String
    val dataBuildVersionLabel: String
    val mapAttributionShort: String
    val osmAttributionFull: String
    val overtureAttributionFull: String
    val openSourceLicensesBody: String
    val close: String
    // Developer Testing
    val sectionDevTesting: String
    val devOnlineOregonTitle: String
    val devOnlineOregonSubtitle: String
    val devOnlineBadge: String
    val enableDevOregonQuickAction: String
    // Phase 2 Additions
    val thenPrefix: String
    val repeatVoice: String
    val gpsSignalLost: String
    val turnListHeader: String
    val showTurns: String
    val hideTurns: String
    val droppedPin: String
    val simulateDriveTitle: String
    val simulateDriveSubtitle: String
    val simulateWrongTurn: String
    val permissionRationaleTitle: String
    val permissionRationaleBody: String
    val continueAction: String
    val nativeRoutingError: String
    // Phase 4 Welcome Onboarding
    val welcomeTitle: String
    val welcomeSubtitle: String
    val welcomePermissionsCardTitle: String
    val welcomePermissionsGranted: String
    val welcomeGrantPermissionsButton: String
    val welcomeOpenMapsButton: String
    // Crash Recovery Dialog
    val crashDialogTitle: String
    val crashDialogBody: String
    val copyErrorDetails: String
    val errorDetailsCopied: String
    // Diagnostics & Persian Voice Fallback
    val diagnosticsTitle: String
    val diagnosticsSubtitle: String
    val openDiagnostics: String
    val copyAll: String
    val diagnosticsCopied: String
    val backAction: String
    val diagSectionAppDevice: String
    val diagAndroidVersion: String
    val diagPhoneModel: String
    val diagSectionPacks: String
    val diagBuildVersion: String
    val diagPlacesSource: String
    val diagFileSizes: String
    val diagNoPacksInstalled: String
    val diagSectionGpsCompass: String
    val diagGpsAccuracy: String
    val diagGpsProvider: String
    val diagGpsFixAge: String
    val diagPreciseLocation: String
    val diagCompassHeading: String
    val diagCompassAccuracy: String
    val diagNoGpsFix: String
    val diagSectionLastRoute: String
    val diagManeuversCount: String
    val diagManeuversWithLanes: String
    val diagShapeSpeedLimits: String
    val diagRoutingTimeMs: String
    val diagNoRouteYet: String
    val diagSectionTts: String
    val diagTtsEngine: String
    val diagPersianTtsEngine: String
    val diagEnglishTtsEngine: String
    val diagPersianVoiceStatus: String
    val diagEnglishVoiceStatus: String
    val diagAvailableLabel: String
    val diagInstalledLabel: String
    val diagTestPersianVoice: String
    val diagTestEnglishVoice: String
    val diagPersianVoiceUnavailableToast: String
    val diagUseEnglishVoiceInPersian: String
    val diagUseEnglishVoiceInPersianSubtitle: String
    val diagPersianInstallSteps: String
    val diagSectionErrorLog: String
    val diagNoErrorsLogged: String
    val diagYes: String
    val diagNo: String
    val diagGranted: String
    val diagNotGranted: String
    val diagSensorHigh: String
    val diagSensorMedium: String
    val diagSensorLow: String
    val diagSensorUnreliable: String
    val diagSensorUnavailable: String
    // Simulation button & speed controls
    val simulateButtonLabel: String
    val simulationSpeedLabel: String
    val simulationNoRouteMessage: String
    val simulationSpeedChangedPrefix: String
    // Online-when-available connectivity, weather alerts, Photon fallback & auto map updates
    val sectionOnlineFeatures: String
    val onlineFeaturesMainSwitch: String
    val onlineWeatherAlertsSwitch: String
    val onlineSearchFallbackSwitch: String
    val autoCheckMapUpdatesSwitch: String
    val wifiOnlyLargeDownloadsSwitch: String
    val autoUpdateMapsOnWifiSwitch: String
    val onlineLocationDisclosureTitle: String
    val onlineLocationDisclosureBody: String
    val cloudStatusOnlineWifi: String
    val cloudStatusOnlineMobile: String
    val cloudStatusOffline: String
    val cloudStatusDisabled: String
    val cloudDialogTitle: String
    val onlineSourceWeatherName: String
    val onlineSourceSearchName: String
    val onlineSourceMapUpdatesName: String
    val onlineSourceStateWorking: String
    val onlineSourceStateReady: String
    val onlineSourceStateOff: String
    val onlineSourceStateOffline: String
    val onlineSourceStateUnavailable: String
    val weatherAlertBannerUntilPrefix: String
    val weatherAlertRouteSuffix: String
    val onlineSearchSectionHeader: String
    val onlineSearchResultBadge: String
    val mapUpdateNotificationTitle: String
    val mapUpdateNotificationBody: String
    val mapUpdateWifiOnlyNotice: String
    // External map links & shared addresses
    val externalLinkOfflineShortUrl: String
    val externalLinkExpanding: String
    val externalLinkMatchesTitle: String
    val externalLinkNoMatch: String
    val externalLinkOutsideInstalledStates: String
    val externalLinkLabelDefault: String
    // Traffic lights on map
    val showTrafficLightsToggle: String
    val trafficLightAheadLabel: String
    // Map graphics (3D buildings, position marker style)
    val show3DBuildingsToggle: String
    val positionMarkerStyleLabel: String
    val positionMarkerArrow: String
    val positionMarkerCar: String
    // F1: Offline house-number address search & Overture attribution
    val addressApproximateBadge: String
    val overtureAddressesAttributionFull: String
    val overtureAttributionLinkLabel: String
    // F2: Speed bump alerts
    val alertToggleSpeedBump: String
    // F3: Monthly & smart map updates
    val mapUpdateChannelName: String
    val mapUpdateSmartSizeNote: String
    val mapUpdatePostponedNotice: String
    val mapUpdateReadySwapApplied: String
    val diagSectionMapUpdates: String
    val diagLastUpdateCheckTime: String
    val diagLastUpdateCheckResult: String
    val diagNeverChecked: String
    // F4: Parked car location & walk to car
    val parkedCarMenuAction: String
    val parkedCarSavedToast: String
    val parkedCarClearedToast: String
    val parkedCarWalkToCar: String
    val parkedCarEditNote: String
    val parkedCarNoteHint: String
    val parkedCarShare: String
    val parkedCarClear: String
    val parkedCarLabel: String
    val parkedCarArrivedVoice: String
    val parkedCarWalkingModeBadge: String
    val settingsSectionParking: String
    val settingsSkipParkingAtHome: String
    val settingsCarBluetoothLabel: String
    val settingsCarBluetoothNone: String
    val settingsCarBluetoothPickerTitle: String
    val settingsCarBluetoothNoDevices: String
    val diagSectionParkedCar: String
    val diagParkedTime: String
    val diagParkedAccuracy: String
    val diagNoParkedCar: String
    // Android Auto (AA3-AA8)
    val carNoOfflineMapMessage: String
    val carActionSearch: String
    val carActionFavorites: String
    val carActionRecent: String
    val carActionStart: String
    val carActionStop: String
    val carActionMute: String
    val carActionUnmute: String
    val carActionReportClosure: String
    val carClosureReportedToast: String
    val carSearchHint: String
    val carSearchDrivingHint: String
    val carNoResults: String
    val carNoSavedPlaces: String
    val carNoRecentPlaces: String
    val carCalculatingRoute: String
    val carRoutePreviewTitle: String
    val carRouteFastestTag: String
    val carRouteShortestTag: String
    val carArrivedTitle: String
    val carReroutingTitle: String
    val carAlertDismiss: String
    // Phase M: Walking & Bicycle routing
    val travelModeDrive: String
    val travelModeWalk: String
    val travelModeBike: String
    val travelModeTooFar: String
    val settingsSectionTravelModes: String
    val settingsDefaultTravelMode: String
    val settingsWalkingSpeed: String
    val settingsWalkSlow: String
    val settingsWalkNormal: String
    val settingsWalkFast: String
    val settingsCyclingSpeed: String
    val settingsBikeRelaxed: String
    val settingsBikeNormal: String
    val settingsBikeFast: String
    val settingsAvoidHillsCycling: String
    val carWalkBikePhoneOnlyNotice: String
    // Phase J & X: Junction view, steep grades, FRA & HPMS
    val settingsJunctionViewTitle: String
    val alertToggleSteepGrade: String
    val fraWithGates: String
    val fraLightsOnly: String
    val fraNoGatesOrLights: String
    val diagSpeedLimitSourceLabel: String
    val diagExtrasDbStatusLabel: String
    val extrasAttributionFull: String
    // S1: Separate Traffic Lights & Stop Signs
    val sectionTrafficLightsStopSigns: String
    val alertToggleTrafficLight: String
    val showStopSignsToggle: String
    // L4 & L5: Lane Change Beeps & Timing
    val laneChangeBeepsToggle: String
    val laneChangeTimingLabel: String
    val laneChangeTimingEarly: String
    val laneChangeTimingNormal: String
    val laneChangeTimingLate: String
    val lastChanceBeepToggle: String
    val carChangeLanesNowTitle: String
    // V2 & V3: Route Progress Bar & Speedometer Ring
    val routeProgressBarDesc: String
    val routeEventHighwayExit: String
    val routeEventDestination: String
    val speedLimitEstimatedLabel: String
    // LN3: Lane Highlight on Map
    val laneHighlightOnMapToggle: String
    val laneHighlightColorLabel: String
    val laneColorRed: String
    val laneColorBlue: String
    val laneColorGreen: String
    // SL2: Speed Limit Drop Ahead
    val speedLimitDropWarningToggle: String
    val speedLimitDropVoicePrefix: String
    val speedLimitDropVoiceSuffix: String
    // PK1-PK3: Parking Near Destination
    val parkingDefaultLotName: String
    val parkingTypeGarage: String
    val parkingTypeLot: String
    val parkingTypeStreet: String
    val parkingNearbyButton: String
    val parkingNearDestTitle: String
    val parkingWalkMinSuffix: String
    val parkingWalkToDestButton: String
    val parkingArrivedTitle: String
    // VC1-VC3: Offline English Voice Commands
    val voiceCommandMicDesc: String
    val voiceListeningTitle: String
    val voiceNotUnderstood: String
    val voiceOfflineModelMissing: String
    val voiceDownloadModelButton: String
    val voiceOpenSpeechSettingsButton: String
    val voiceMicPermissionNeeded: String
    val voiceAutoStartCountdownPrefix: String
    val voiceCancelAutoStart: String
    val voiceNoSavedHome: String
    val voiceNoSavedWork: String
    val voiceNoParkedCar: String
    val voiceNotNavigating: String
    val voiceEtaPrefix: String
    val voiceMutedConfirm: String
    val voiceUnmutedConfirm: String
    val voiceStoppedConfirm: String
    val voiceRoadClosedConfirm: String
    val voiceNoSearchMatches: String
    // A1, A4: Android Auto Car Map View & Diagnostics
    val carMapViewLabel: String
    val carMapViewClose: String
    val carMapViewNormal: String
    val carMapViewWide: String
    val diagSectionCarScreen: String
    val diagCarNotConnected: String
    // F2, F7: Turn Focus Mode
    val turnFocusSectionTitle: String
    val turnFocusModeToggle: String
    val turnFocusBeepsToggle: String
    val turnFocusCityDistanceLabel: String
    val turnFocusHighwayDistanceLabel: String
    val turnFocusWideHighwayToggle: String
    val turnFocusShortPhraseToggle: String
    val turnFocusShortPhraseSpoken: String
    // S1: Simulator speed buttons
    val simSpeedDecreaseDesc: String
    val simSpeedIncreaseDesc: String
    // L1, L4, L5, L6: Lane accuracy, confidence hints & wrong-lane reports
    val diagLaneSourceLabel: String
    val laneHintUseRightLanes: String
    val laneHintUseLeftLanes: String
    val laneHintUseMiddleLanes: String
    val reportWrongLaneDesc: String
    val wrongLaneSavedMessage: String
    val diagSectionLaneReports: String
    val diagNoLaneReports: String
    val diagClearLaneReports: String
    val diagLaneReportsCleared: String
    // D5: GNIS landmarks
    val gnisLandmarkBadge: String
}

val LocalAppStrings = compositionLocalOf<AppStrings> { EnglishStrings }
val LocalAppLanguage = compositionLocalOf { AppLanguage.ENGLISH }

fun stringsFor(language: AppLanguage): AppStrings =
    if (language == AppLanguage.PERSIAN) PersianStrings else EnglishStrings

object BilingualFormatter {
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun localizeDigits(text: String, language: AppLanguage): String {
        if (language != AppLanguage.PERSIAN) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '0'..'9') {
                sb.append(persianDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun formatNumber(value: Int, language: AppLanguage): String =
        localizeDigits(value.toString(), language)

    fun formatDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String {
        val raw = when (unit) {
            DistanceUnit.IMPERIAL -> {
                val feet = meters * 3.28084
                val miles = meters / 1609.344
                when {
                    miles >= 10.0 -> "${miles.roundToInt()} ${if (language == AppLanguage.PERSIAN) "مایل" else "mi"}"
                    miles >= 0.2 -> String.format(Locale.US, "%.1f %s", miles, if (language == AppLanguage.PERSIAN) "مایل" else "mi")
                    else -> {
                        val roundedFeet = ((feet / 50.0).roundToInt() * 50).coerceAtLeast(50)
                        "$roundedFeet ${if (language == AppLanguage.PERSIAN) "فوت" else "ft"}"
                    }
                }
            }
            DistanceUnit.METRIC -> {
                val km = meters / 1000.0
                when {
                    km >= 10.0 -> "${km.roundToInt()} ${if (language == AppLanguage.PERSIAN) "کیلومتر" else "km"}"
                    km >= 0.5 -> String.format(Locale.US, "%.1f %s", km, if (language == AppLanguage.PERSIAN) "کیلومتر" else "km")
                    else -> {
                        val roundedMeters = ((meters / 10.0).roundToInt() * 10).coerceAtLeast(10)
                        "$roundedMeters ${if (language == AppLanguage.PERSIAN) "متر" else "m"}"
                    }
                }
            }
        }
        return localizeDigits(raw, language)
    }

    fun formatDuration(seconds: Double, language: AppLanguage): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val raw = if (language == AppLanguage.PERSIAN) {
            when {
                hours > 0 && mins > 0 -> "$hours ساعت و $mins دقیقه"
                hours > 0 -> "$hours ساعت"
                else -> "$mins دقیقه"
            }
        } else {
            when {
                hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                hours > 0 -> "${hours}h"
                else -> "$mins min"
            }
        }
        return localizeDigits(raw, language)
    }

    fun formatArrivalTime(remainingSeconds: Double, language: AppLanguage): String {
        val arrivalMillis = System.currentTimeMillis() + (remainingSeconds * 1000).toLong()
        val pattern = if (language == AppLanguage.PERSIAN) "HH:mm" else "h:mm a"
        val formatter = SimpleDateFormat(pattern, Locale.US)
        return localizeDigits(formatter.format(Date(arrivalMillis)), language)
    }

    fun formatBytes(bytes: Long, language: AppLanguage): String {
        val raw = when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
        return localizeDigits(raw, language)
    }

    fun formatSpeedValue(metersPerSecond: Float, unit: DistanceUnit): Int {
        return when (unit) {
            DistanceUnit.IMPERIAL -> (metersPerSecond * 2.23694f).roundToInt().coerceAtLeast(0)
            DistanceUnit.METRIC -> (metersPerSecond * 3.6f).roundToInt().coerceAtLeast(0)
        }
    }

    fun convertMphToUnit(mph: Int, unit: DistanceUnit): Int {
        return when (unit) {
            DistanceUnit.IMPERIAL -> mph
            DistanceUnit.METRIC -> (mph * 1.60934).roundToInt()
        }
    }
}
