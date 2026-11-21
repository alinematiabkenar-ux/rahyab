package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.model.UsStateInfo
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.OfflineRoute
import com.example.ui.RahYabViewModel
import com.example.ui.components.AheadAlertBanner
import com.example.ui.components.MapLibreMapContainer
import com.example.ui.components.OneTapReportGrid
import com.example.ui.components.SpeedometerAndLimitCluster
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.components.maneuverIconFor
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertSafeGreen
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.JetBrainsMonoFamily

private val RoutePreviewColors = listOf(
    Color(0xFF00A8E8), // Route 1 (Fastest — Cyan)
    Color(0xFF10B981), // Route 2 (Alternate 1 — Emerald)
    Color(0xFFA855F7)  // Route 3 (Alternate 2 — Purple)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    viewModel: RahYabViewModel,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onNavigateToMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gpsFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
    val packsTick by viewModel.packsChangedTick.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedPlace by viewModel.selectedPlace.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val candidateRoutes by viewModel.candidateRoutes.collectAsStateWithLifecycle()
    val selectedRouteIdx by viewModel.selectedRouteIndex.collectAsStateWithLifecycle()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsStateWithLifecycle()
    val routeError by viewModel.routeError.collectAsStateWithLifecycle()
    val missingStatesForRoute by viewModel.missingStatesForRoute.collectAsStateWithLifecycle()
    val navState by viewModel.navigationState.collectAsStateWithLifecycle()
    val activeReports by viewModel.activeUserReports.collectAsStateWithLifecycle()
    val cameraCmd by viewModel.cameraCommand.collectAsStateWithLifecycle()
    val isCameraFollowing by viewModel.isCameraFollowing.collectAsStateWithLifecycle()

    val isNightMode = viewModel.resolveEffectiveNightMode(settings, gpsFix)
    val installedPmtiles = remember(packsTick) { viewModel.getInstalledPmtilesFiles() }
    val installedPlacesCount = remember(packsTick) { viewModel.getInstalledPlacesDbCount() }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var showAttributionDialog by remember { mutableStateOf(false) }

    // Keep screen awake strictly while actively navigating
    DisposableEffect(navState.isNavigating, context) {
        val activity = context as? Activity
        if (navState.isNavigating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Android 15 Predictive Back handling
    BackHandler(enabled = isSearchExpanded || selectedPlace != null) {
        when {
            isSearchExpanded -> isSearchExpanded = false
            selectedPlace != null && !navState.isNavigating -> viewModel.clearSelectedPlace()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed MapLibre Native Vector Map
        MapLibreMapContainer(
            installedPmtiles = installedPmtiles,
            isNightMode = isNightMode,
            useDevOnlineBasemap = settings.devOnlineOregonMode,
            packsChangedTick = packsTick,
            gpsFix = gpsFix,
            snappedNavLocation = if (navState.isNavigating) navState.snappedLocation else null,
            candidateRoutes = if (!navState.isNavigating) candidateRoutes else emptyList(),
            selectedRouteIndex = selectedRouteIdx,
            activeNavRoute = if (navState.isNavigating) navState.route else null,
            selectedPlace = selectedPlace,
            userReports = activeReports,
            cameraCommand = cameraCmd,
            onMapLongClick = { lat, lon -> viewModel.dropPinOnMap(lat, lon) },
            onRouteTapped = { idx -> viewModel.selectCandidateRoute(idx) },
            onUserMovedMap = { viewModel.pauseCameraFollow() },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Overlay: Active Turn-by-Turn HUD OR Floating Search & Status Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (navState.isNavigating && navState.currentManeuver != null) {
                TurnByTurnTopHud(
                    maneuver = navState.currentManeuver!!,
                    thenManeuver = navState.thenManeuver,
                    distanceMeters = navState.distanceToNextManeuverMeters,
                    unit = settings.distanceUnit,
                    isRerouting = navState.isRerouting,
                    showLaneStrip = navState.showLaneStrip,
                    showJunctionPreview = navState.showJunctionPreview,
                    routeShape = navState.route?.shape.orEmpty(),
                    onDismissJunctionPreview = { viewModel.dismissJunctionPreview() }
                )

                // Small tunnel / GPS signal loss notice
                if (navState.isGpsSignalLost) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AlertAmber,
                        shadowElevation = 4.dp,
                        modifier = Modifier.testTag("gps_signal_lost_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GpsOff,
                                contentDescription = strings.gpsSignalLost,
                                tint = Color(0xFF0F172A),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = strings.gpsSignalLost,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Approaching Road / User Alert Banner
                navState.activeAheadAlert?.let { alert ->
                    AheadAlertBanner(
                        alert = alert,
                        unit = settings.distanceUnit,
                        onConfirmReport = { viewModel.confirmUserReport(it) },
                        onDismissReport = { viewModel.dismissUserReport(it) }
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
                            viewModel.onSearchQueryChanged(it)
                            isSearchExpanded = true
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
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = strings.searchHint,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                if (searchQuery.isNotEmpty() || isSearchExpanded) {
                                    IconButton(
                                        onClick = {
                                            viewModel.onSearchQueryChanged("")
                                            isSearchExpanded = false
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
                                viewModel.executeSearchNow(searchQuery)
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
                                    isSearchExpanded = true
                                    viewModel.executeSearchNow(queryText)
                                }
                                .testTag("quick_cat_$label")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                // Status badges row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (settings.devOnlineOregonMode && installedPmtiles.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AlertAmber,
                            shadowElevation = 4.dp,
                            modifier = Modifier.testTag("dev_online_badge")
                        ) {
                            Text(
                                text = strings.devOnlineBadge,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else if (installedPmtiles.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CockpitMidnight.copy(alpha = 0.86f),
                            border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "${strings.offlineReadyBadge} • ${BilingualFormatter.formatNumber(installedPmtiles.size, language)}",
                                color = CyanBeacon,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!hasLocationPermission) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AlertRed,
                            modifier = Modifier
                                .clickable(onClick = onRequestLocationPermission)
                                .testTag("grant_gps_chip")
                        ) {
                            Text(
                                text = strings.grantLocationPermission,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Graceful prompt when no offline map pack is installed and dev online mode is off
                if (installedPmtiles.isEmpty() && !settings.devOnlineOregonMode && !isSearchExpanded) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = strings.noMapPackLoaded,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strings.searchNoPacksBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = onNavigateToMaps,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("empty_pack_download_button")
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.downloadOrImportAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.setDevOnlineOregonMode(true) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("enable_dev_oregon_button")
                                ) {
                                    Text(strings.enableDevOregonQuickAction, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Expanded Search Results & Saved Places Panel
                AnimatedVisibility(visible = isSearchExpanded) {
                    SearchResultsOverlayCard(
                        query = searchQuery,
                        results = searchResults,
                        savedPlaces = savedPlaces,
                        hasAnyPlacesSource = installedPlacesCount > 0 || settings.devOnlineOregonMode,
                        distanceUnit = settings.distanceUnit,
                        onSelectPlace = { place ->
                            isSearchExpanded = false
                            keyboard?.hide()
                            viewModel.selectPlace(place)
                        },
                        onSelectSaved = { saved ->
                            isSearchExpanded = false
                            keyboard?.hide()
                            viewModel.selectPlace(
                                PlaceSearchResult(
                                    id = "saved_${saved.id}",
                                    name = saved.name,
                                    brand = "",
                                    category = saved.category,
                                    address = saved.address,
                                    city = "",
                                    stateCode = saved.stateCode,
                                    postcode = "",
                                    lat = saved.lat,
                                    lon = saved.lon,
                                    distanceMeters = 0.0,
                                    relevanceScore = 100.0,
                                    source = "saved_${saved.type.lowercase()}",
                                    savedId = saved.id
                                )
                            )
                        },
                        onGoToMaps = {
                            isSearchExpanded = false
                            onNavigateToMaps()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .imePadding()
                    )
                }
            }
        }

        // 3. Left / Start side: Speed Limit & Live Speedometer Cluster
        if (navState.isNavigating || gpsFix != null) {
            SpeedometerAndLimitCluster(
                currentSpeedMps = navState.currentSpeedMps.takeIf { navState.isNavigating } ?: (gpsFix?.speedMps ?: 0f),
                speedLimitMph = navState.currentSpeedLimitMph,
                isOverspeed = navState.isOverspeed,
                unit = settings.distanceUnit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = if (navState.isNavigating) 118.dp else if (selectedPlace != null) 275.dp else 44.dp)
            )
        }

        // 4. Right / End side: Driving Control Rail
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = if (navState.isNavigating) 118.dp else if (selectedPlace != null) 275.dp else 44.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Simulate Wrong Turn button (only visible when Developer Simulate Drive is active)
            if (navState.isNavigating && navState.isSimulatingDrive) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AlertRed,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .clickable { viewModel.triggerSimulatedWrongTurn() }
                        .testTag("simulate_wrong_turn_button")
                ) {
                    Text(
                        text = strings.simulateWrongTurn,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            // Repeat last spoken instruction button during navigation
            if (navState.isNavigating) {
                SmallCockpitButton(
                    icon = Icons.Filled.Replay,
                    contentDescription = strings.repeatVoice,
                    onClick = { viewModel.repeatLastVoiceInstruction() },
                    testTag = "repeat_voice_button"
                )
            }

            // Voice mute/unmute
            SmallCockpitButton(
                icon = if (settings.voiceGuidanceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (settings.voiceGuidanceEnabled) strings.muteVoice else strings.unmuteVoice,
                onClick = { viewModel.setVoiceEnabled(!settings.voiceGuidanceEnabled) },
                testTag = "toggle_voice_button"
            )

            // 3D / 2D camera toggle
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(50.dp)
                    .clickable { viewModel.toggle3DView() }
                    .testTag("toggle_3d_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (settings.is3DView) strings.view3D else strings.view2D,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Recenter GPS (highlighted cyan when user panned map away during navigation)
            SmallCockpitButton(
                icon = Icons.Filled.MyLocation,
                contentDescription = strings.recenter,
                onClick = { viewModel.recenterCamera() },
                highlight = navState.isNavigating && !isCameraFollowing,
                testTag = "recenter_gps_button"
            )

            // Large Waze-style One-Tap Report Button (8 report types)
            FloatingActionButton(
                onClick = { showReportSheet = true },
                containerColor = AlertAmber,
                contentColor = Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier
                    .size(62.dp)
                    .testTag("open_report_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.ReportProblem,
                    contentDescription = strings.reportButton,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // 5. Required OpenStreetMap + Overture Maps attribution badge
        if (!navState.isNavigating && selectedPlace == null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp)
                    .clickable { showAttributionDialog = true }
                    .testTag("map_attribution_badge")
            ) {
                Text(
                    text = strings.mapAttributionShort,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 6. Bottom Cockpit Panel: Active Navigation ETA Bar OR Selected Place / Route Preview Card
        if (navState.isNavigating) {
            ActiveNavigationBottomBar(
                destinationName = navState.destinationName,
                destinationAddress = navState.destinationAddress,
                remainingDistanceMeters = navState.remainingDistanceMeters,
                remainingTimeSeconds = navState.remainingTimeSeconds,
                hasArrived = navState.hasArrived,
                unit = settings.distanceUnit,
                onEndNavigation = { viewModel.stopNavigation() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        } else if (selectedPlace != null) {
            PlaceAndRoutePreviewCard(
                place = selectedPlace!!,
                routes = candidateRoutes,
                selectedRouteIndex = selectedRouteIdx,
                isCalculatingRoute = isCalculatingRoute,
                routeError = routeError,
                missingStates = missingStatesForRoute,
                avoidTolls = settings.avoidTolls,
                avoidHighways = settings.avoidHighways,
                avoidFerries = settings.avoidFerries,
                unit = settings.distanceUnit,
                onClose = { viewModel.clearSelectedPlace() },
                onCalculateRoutes = { viewModel.calculateRoutesToSelectedPlace() },
                onSelectRoute = { viewModel.selectCandidateRoute(it) },
                onStartNavigation = { viewModel.startNavigation() },
                onToggleAvoidTolls = { viewModel.setAvoidTolls(it) },
                onToggleAvoidHighways = { viewModel.setAvoidHighways(it) },
                onToggleAvoidFerries = { viewModel.setAvoidFerries(it) },
                onSaveAs = { type -> viewModel.savePlaceAs(selectedPlace!!, type) },
                onDownloadMissingState = { stateCode ->
                    viewModel.requestDownloadStatePack(stateCode)
                    onNavigateToMaps()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        }
    }

    // One-Tap Driving Report Bottom Sheet (All 8 Waze-style report types)
    if (showReportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReportSheet = false }
        ) {
            OneTapReportGrid(
                onSelectReport = { reportType ->
                    viewModel.submitUserReport(reportType)
                    showReportSheet = false
                },
                onClose = { showReportSheet = false }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Required Map Licenses Attribution Dialog
    if (showAttributionDialog) {
        AlertDialog(
            onDismissRequest = { showAttributionDialog = false },
            title = { Text(strings.aboutTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.osmAttributionFull, style = MaterialTheme.typography.bodyMedium)
                    Text(strings.overtureAttributionFull, style = MaterialTheme.typography.bodyMedium)
                    Text(strings.openSourceLicensesBody, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttributionDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

@Composable
private fun SmallCockpitButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    highlight: Boolean = false
) {
    Surface(
        shape = CircleShape,
        color = if (highlight) CyanBeacon else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(50.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlight) Color(0xFF0F172A) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SearchResultsOverlayCard(
    query: String,
    results: List<PlaceSearchResult>,
    savedPlaces: List<SavedPlaceEntity>,
    hasAnyPlacesSource: Boolean,
    distanceUnit: DistanceUnit,
    onSelectPlace: (PlaceSearchResult) -> Unit,
    onSelectSaved: (SavedPlaceEntity) -> Unit,
    onGoToMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = modifier.testTag("search_results_panel")
    ) {
        if (query.isBlank()) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val home = savedPlaces.find { it.type == SavedPlaceType.HOME.name }
                        val work = savedPlaces.find { it.type == SavedPlaceType.WORK.name }
                        SavedShortcutChip(
                            label = strings.savedHome,
                            sublabel = home?.name ?: strings.setHome,
                            icon = Icons.Filled.Home,
                            enabled = home != null,
                            onClick = { home?.let(onSelectSaved) },
                            modifier = Modifier.weight(1f)
                        )
                        SavedShortcutChip(
                            label = strings.savedWork,
                            sublabel = work?.name ?: strings.setWork,
                            icon = Icons.Filled.Work,
                            enabled = work != null,
                            onClick = { work?.let(onSelectSaved) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val favsAndRecent = savedPlaces.filter {
                    it.type == SavedPlaceType.FAVORITE.name || it.type == SavedPlaceType.RECENT.name
                }
                if (favsAndRecent.isNotEmpty()) {
                    items(favsAndRecent.take(12), key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectSaved(item) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (item.type == SavedPlaceType.FAVORITE.name) Icons.Filled.Star else Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.address.isNotBlank()) {
                                    Text(item.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        } else if (results.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (!hasAnyPlacesSource) strings.searchNoPacksTitle else strings.searchNoResults,
                    style = MaterialTheme.typography.titleMedium
                )
                if (!hasAnyPlacesSource) {
                    Text(
                        text = strings.searchNoPacksBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onGoToMaps) {
                        Text(strings.downloadOrImportAction)
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(results, key = { it.id }) { place ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPlace(place) }
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                            .testTag("search_result_item"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Place,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = place.formattedAddress.ifBlank { place.category },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = BilingualFormatter.formatDistance(place.distanceMeters, distanceUnit, language),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun SavedShortcutChip(
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.9f else 0.45f),
        modifier = modifier
            .height(58.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PlaceAndRoutePreviewCard(
    place: PlaceSearchResult,
    routes: List<OfflineRoute>,
    selectedRouteIndex: Int,
    isCalculatingRoute: Boolean,
    routeError: String?,
    missingStates: List<UsStateInfo>,
    avoidTolls: Boolean,
    avoidHighways: Boolean,
    avoidFerries: Boolean,
    unit: DistanceUnit,
    onClose: () -> Unit,
    onCalculateRoutes: () -> Unit,
    onSelectRoute: (Int) -> Unit,
    onStartNavigation: () -> Unit,
    onToggleAvoidTolls: (Boolean) -> Unit,
    onToggleAvoidHighways: (Boolean) -> Unit,
    onToggleAvoidFerries: (Boolean) -> Unit,
    onSaveAs: (SavedPlaceType) -> Unit,
    onDownloadMissingState: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    var showTurnsList by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("place_preview_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (place.formattedAddress.isNotBlank()) {
                        Text(
                            place.formattedAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("close_place_preview_button")) {
                    Icon(Icons.Filled.Close, contentDescription = strings.close)
                }
            }

            // Save shortcuts (Home / Work / Favorite)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = false,
                        onClick = { onSaveAs(SavedPlaceType.FAVORITE) },
                        label = { Text(strings.addFavorite, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { onSaveAs(SavedPlaceType.HOME) },
                        label = { Text(strings.setHome, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { onSaveAs(SavedPlaceType.WORK) },
                        label = { Text(strings.setWork, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Work, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Route avoid options
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = avoidTolls,
                        onClick = { onToggleAvoidTolls(!avoidTolls) },
                        label = { Text(strings.avoidTolls, fontSize = 12.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = avoidHighways,
                        onClick = { onToggleAvoidHighways(!avoidHighways) },
                        label = { Text(strings.avoidHighways, fontSize = 12.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = avoidFerries,
                        onClick = { onToggleAvoidFerries(!avoidFerries) },
                        label = { Text(strings.avoidFerries, fontSize = 12.sp) }
                    )
                }
            }

            // Missing state pack warning with direct Download button (Phase 4)
            if (missingStates.isNotEmpty()) {
                val missingLabel = missingStates.joinToString(", ") {
                    if (language == AppLanguage.PERSIAN) it.nameFa else it.nameEn
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AlertAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, AlertAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("missing_states_route_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${strings.missingStateRouteTitle}: $missingLabel",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = { onDownloadMissingState(missingStates.first().code) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("download_missing_state_button")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${strings.downloadMissingStateAction}: ${missingStates.first().abbr}")
                        }
                    }
                }
            } else if (routeError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = routeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Up to 3 color-coded route cards with duration, distance, and arrival time
            if (routes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    routes.take(3).forEachIndexed { idx, route ->
                        val isSelected = idx == selectedRouteIndex
                        val routeAccent = RoutePreviewColors.getOrElse(idx) { CyanBeacon }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) routeAccent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) routeAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectRoute(idx) }
                                .testTag("route_card_$idx")
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(routeAccent)
                                    )
                                    Text(
                                        text = BilingualFormatter.formatDuration(route.durationSeconds, language),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = BilingualFormatter.formatDistance(route.distanceMeters, unit, language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${strings.arrivalLabel} ${BilingualFormatter.formatArrivalTime(route.durationSeconds, language)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = routeAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Expandable Turn-by-Turn Steps list before starting navigation
                val selectedRoute = routes.getOrNull(selectedRouteIndex)
                if (selectedRoute != null && selectedRoute.maneuvers.isNotEmpty()) {
                    AnimatedVisibility(visible = showTurnsList) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("route_steps_list"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(selectedRoute.maneuvers, key = { it.index }) { step ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = maneuverIconFor(step.type),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = step.streetNames.firstOrNull()?.ifBlank { step.instructionEn }
                                            ?: step.instructionEn,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = BilingualFormatter.formatDistance(step.lengthMeters, unit, language),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Primary Action Row: Directions OR (Steps Toggle + Start Navigation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (routes.isEmpty()) {
                    Button(
                        onClick = onCalculateRoutes,
                        enabled = !isCalculatingRoute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("directions_button")
                    ) {
                        if (isCalculatingRoute) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(strings.calculatingRoute)
                        } else {
                            Icon(Icons.Filled.Directions, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.previewRoute, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showTurnsList = !showTurnsList },
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("toggle_steps_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showTurnsList) strings.hideTurns else strings.showTurns)
                    }
                    Button(
                        onClick = onStartNavigation,
                        colors = ButtonDefaults.buttonColors(containerColor = AlertSafeGreen),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("start_navigation_button")
                    ) {
                        Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.startNavigation, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveNavigationBottomBar(
    destinationName: String,
    destinationAddress: String,
    remainingDistanceMeters: Double,
    remainingTimeSeconds: Double,
    hasArrived: Boolean,
    unit: DistanceUnit,
    onEndNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CockpitMidnight.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.45f)),
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_nav_bottom_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (hasArrived) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.arrivedTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = AlertSafeGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = destinationAddress.ifBlank { destinationName.ifBlank { strings.arrivedSubtitle } },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = BilingualFormatter.formatDuration(remainingTimeSeconds, language),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = if (language == AppLanguage.PERSIAN) MaterialTheme.typography.titleLarge.fontFamily else JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = AlertSafeGreen
                        )
                        Text(
                            text = "${BilingualFormatter.formatDistance(remainingDistanceMeters, unit, language)} • ${strings.arrivalLabel} ${BilingualFormatter.formatArrivalTime(remainingTimeSeconds, language)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Button(
                onClick = onEndNavigation,
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("end_navigation_button")
            ) {
                Text(strings.endNavigation, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
