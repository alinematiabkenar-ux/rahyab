package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SavedPlaceEntity
import com.example.data.online.WeatherAlertItem
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.GpsFix
import com.example.ui.components.AheadAlertBanner
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon

@Composable
internal fun DriveTopOverlay(
    navState: ActiveNavigationState,
    settings: UserSettings,
    isNightMode: Boolean,
    gpsFix: GpsFix?,
    hasLocationPermission: Boolean,
    isApproximateLocationOnly: Boolean,
    isCompassLowAccuracy: Boolean,
    compassCalibDismissed: Boolean,
    aheadTrafficLightMeters: Double?,
    freeDriveAheadAlert: AheadAlert?,
    weatherAlerts: List<WeatherAlertItem>,
    installedPmtilesCount: Int,
    installedPlacesCount: Int,
    searchQuery: String,
    searchResults: List<PlaceSearchResult>,
    savedPlaces: List<SavedPlaceEntity>,
    isSearching: Boolean,
    isSearchExpanded: Boolean,
    keyboard: SoftwareKeyboardController?,
    onSetSearchExpanded: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onExecuteSearchNow: (String) -> Unit,
    onSelectPlace: (PlaceSearchResult) -> Unit,
    onDismissJunctionPreview: () -> Unit,
    onDismissCompassCalibration: () -> Unit,
    onConfirmReport: (Long) -> Unit,
    onDismissReport: (Long) -> Unit,
    onDismissWeatherAlert: (String) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNavigateToMaps: () -> Unit,
    onEnableDevOregon: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (navState.isNavigating && navState.currentManeuver != null) {
            val activeManeuver = navState.currentManeuver!!
            TurnByTurnTopHud(
                maneuver = activeManeuver,
                thenManeuver = navState.thenManeuver,
                distanceMeters = navState.distanceToNextManeuverMeters,
                unit = settings.distanceUnit,
                isRerouting = navState.isRerouting,
                showLaneStrip = navState.showLaneStrip,
                showJunctionPreview = navState.showJunctionPreview,
                routeShape = navState.route?.shape.orEmpty(),
                onDismissJunctionPreview = onDismissJunctionPreview,
                isNightMode = isNightMode,
                lanePulseTriggerKey = if (navState.lanePulseManeuverIndex == activeManeuver.index) {
                    navState.lanePulseTriggerCount
                } else 0
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (settings.simulateDriveEnabled || navState.isSimulatingDrive) {
                    SimulationModeBadge(label = strings.simulationModeBadge)
                }
                if (settings.showTrafficLights && aheadTrafficLightMeters != null) {
                    TrafficLightAheadPill(
                        distanceMeters = aheadTrafficLightMeters,
                        unit = settings.distanceUnit,
                        language = language,
                        contentDescription = strings.trafficLightAheadLabel
                    )
                }
                if (navState.isGpsSignalLost && !navState.isSimulatingDrive) {
                    GpsSignalLostPill(label = strings.gpsSignalLost)
                }
            }

            if (isApproximateLocationOnly && !navState.isSimulatingDrive) {
                ApproximateLocationBanner(
                    title = strings.approximateLocationTitle,
                    body = strings.approximateLocationBanner,
                    buttonLabel = strings.openAppSettings,
                    onOpenSettings = onOpenAppSettings
                )
            }
            if (isCompassLowAccuracy && !compassCalibDismissed && !navState.isSimulatingDrive) {
                CompassCalibrationBanner(
                    message = strings.compassLowAccuracyMessage,
                    closeContentDescription = strings.close,
                    onDismiss = onDismissCompassCalibration
                )
            }
            navState.activeAheadAlert?.let { alert ->
                AheadAlertBanner(
                    alert = alert,
                    unit = settings.distanceUnit,
                    onConfirmReport = onConfirmReport,
                    onDismissReport = onDismissReport
                )
            }
            weatherAlerts.firstOrNull()?.let { weatherAlert ->
                SevereWeatherAlertBanner(
                    alert = weatherAlert,
                    untilPrefix = strings.weatherAlertBannerUntilPrefix,
                    routeSuffix = strings.weatherAlertRouteSuffix,
                    closeContentDescription = strings.close,
                    onDismiss = { onDismissWeatherAlert(weatherAlert.id) }
                )
            }
        } else {
            // Floating Search Bar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        onSearchQueryChanged(it)
                        onSetSearchExpanded(true)
                    },
                    placeholder = {
                        Text(
                            text = strings.searchHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = strings.searchHint, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (searchQuery.isNotEmpty() || isSearchExpanded) {
                                IconButton(
                                    onClick = {
                                        onSearchQueryChanged("")
                                        onSetSearchExpanded(false)
                                        keyboard?.hide()
                                    },
                                    modifier = Modifier.testTag("clear_search_button")
                                ) {
                                    Icon(Icons.Filled.Clear, contentDescription = strings.close)
                                }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onExecuteSearchNow(searchQuery)
                            keyboard?.hide()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_input_field")
                )
            }

            // Quick Bilingual Category Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                val quickCats = listOf(
                    Triple(strings.searchCategoryGas, if (language == AppLanguage.PERSIAN) "پمپ بنزین" else "gas station", Icons.Filled.LocalGasStation),
                    Triple(strings.searchCategoryPharmacy, if (language == AppLanguage.PERSIAN) "داروخانه" else "pharmacy", Icons.Filled.LocalPharmacy),
                    Triple(strings.searchCategoryGrocery, if (language == AppLanguage.PERSIAN) "سوپرمارکت" else "grocery", Icons.Filled.ShoppingCart),
                    Triple(strings.searchCategoryCoffee, if (language == AppLanguage.PERSIAN) "کافه" else "coffee", Icons.Filled.LocalCafe),
                    Triple(strings.searchCategoryFood, if (language == AppLanguage.PERSIAN) "رستوران" else "restaurant", Icons.Filled.Restaurant),
                    Triple(strings.searchCategoryHospital, if (language == AppLanguage.PERSIAN) "بیمارستان" else "hospital", Icons.Filled.LocalHospital)
                )
                items(quickCats) { (label, queryText, icon) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .height(40.dp)
                            .clickable {
                                onSetSearchExpanded(true)
                                onExecuteSearchNow(queryText)
                            }
                            .testTag("quick_cat_$label")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Free-drive status badges row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (settings.simulateDriveEnabled) {
                    SimulationModeBadge(label = strings.simulationModeBadge)
                }
                if (settings.showTrafficLights && aheadTrafficLightMeters != null && !isSearchExpanded) {
                    TrafficLightAheadPill(
                        distanceMeters = aheadTrafficLightMeters,
                        unit = settings.distanceUnit,
                        language = language,
                        contentDescription = strings.trafficLightAheadLabel
                    )
                }
                if (settings.devOnlineOregonMode && installedPmtilesCount == 0) {
                    Surface(shape = RoundedCornerShape(12.dp), color = AlertAmber, shadowElevation = 4.dp, modifier = Modifier.testTag("dev_online_badge")) {
                        Text(strings.devOnlineBadge, color = Color(0xFF0F172A), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                } else if (installedPmtilesCount > 0) {
                    Surface(shape = RoundedCornerShape(12.dp), color = CockpitMidnight.copy(alpha = 0.86f), border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.5f))) {
                        Text(
                            text = "${strings.offlineReadyBadge} • ${BilingualFormatter.formatNumber(installedPmtilesCount, language)}",
                            color = CyanBeacon, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                if (!hasLocationPermission) {
                    Surface(shape = RoundedCornerShape(12.dp), color = AlertRed, modifier = Modifier.clickable(onClick = onRequestLocationPermission).testTag("grant_gps_chip")) {
                        Text(strings.grantLocationPermission, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                } else if (gpsFix == null && !settings.simulateDriveEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CockpitMidnight.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.55f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier.testTag("finding_location_pill")
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = CyanBeacon)
                            Text(strings.gpsWaiting, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        }
                    }
                }
            }

            if (isApproximateLocationOnly && !isSearchExpanded) {
                ApproximateLocationBanner(strings.approximateLocationTitle, strings.approximateLocationBanner, strings.openAppSettings, onOpenAppSettings)
            }
            if (isCompassLowAccuracy && !compassCalibDismissed && !settings.simulateDriveEnabled && !isSearchExpanded) {
                CompassCalibrationBanner(strings.compassLowAccuracyMessage, strings.close, onDismissCompassCalibration)
            }
            if (!isSearchExpanded) {
                freeDriveAheadAlert?.let { alert ->
                    AheadAlertBanner(alert = alert, unit = settings.distanceUnit, onConfirmReport = onConfirmReport, onDismissReport = onDismissReport)
                }
                weatherAlerts.firstOrNull()?.let { weatherAlert ->
                    SevereWeatherAlertBanner(weatherAlert, strings.weatherAlertBannerUntilPrefix, strings.weatherAlertRouteSuffix, strings.close) {
                        onDismissWeatherAlert(weatherAlert.id)
                    }
                }
            }
            if (installedPmtilesCount == 0 && !settings.devOnlineOregonMode && !isSearchExpanded) {
                NoMapPackPromptCard(onNavigateToMaps = onNavigateToMaps, onEnableDevOregon = onEnableDevOregon)
            }

            AnimatedVisibility(visible = isSearchExpanded) {
                SearchResultsOverlayCard(
                    query = searchQuery,
                    results = searchResults,
                    savedPlaces = savedPlaces,
                    hasAnyPlacesSource = installedPlacesCount > 0 || settings.devOnlineOregonMode,
                    distanceUnit = settings.distanceUnit,
                    onSelectPlace = { place ->
                        onSetSearchExpanded(false)
                        keyboard?.hide()
                        onSelectPlace(place)
                    },
                    onSelectSaved = { saved ->
                        onSetSearchExpanded(false)
                        keyboard?.hide()
                        onSelectPlace(
                            PlaceSearchResult(
                                id = "saved_${saved.id}", name = saved.name, brand = "", category = saved.category,
                                address = saved.address, city = "", stateCode = saved.stateCode, postcode = "",
                                lat = saved.lat, lon = saved.lon, distanceMeters = 0.0, relevanceScore = 100.0,
                                source = "saved_${saved.type.lowercase()}", savedId = saved.id
                            )
                        )
                    },
                    onGoToMaps = {
                        onSetSearchExpanded(false)
                        onNavigateToMaps()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).imePadding()
                )
            }
        }
    }
}

