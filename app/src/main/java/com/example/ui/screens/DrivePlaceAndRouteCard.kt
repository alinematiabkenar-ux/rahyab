package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SavedPlaceType
import com.example.data.model.UsStateInfo
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.OfflineRoute
import com.example.ui.components.maneuverIconFor
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertSafeGreen
import com.example.ui.theme.CyanBeacon

private val RoutePreviewColors = listOf(
    Color(0xFF00A8E8), // Route 1 (Fastest — Cyan)
    Color(0xFF10B981), // Route 2 (Shortest / Alt 1 — Emerald)
    Color(0xFFA855F7), // Route 3 (Alternate 2 — Purple)
    Color(0xFFF59E0B)  // Route 4 (Alternate 3 — Amber)
)

@Composable
internal fun PlaceAndRoutePreviewCard(
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
    selectedTravelMode: com.example.navigation.TravelMode = com.example.navigation.TravelMode.DRIVE,
    modePreviewSummaries: Map<com.example.navigation.TravelMode, ModePreviewSummary> = emptyMap(),
    onSelectTravelMode: (com.example.navigation.TravelMode) -> Unit = {},
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

            // M3: Drive / Walk / Bike mode chips with offline duration & arrival time
            TravelModeChipsRow(
                selectedMode = selectedTravelMode,
                summaries = modePreviewSummaries,
                onSelectMode = onSelectTravelMode
            )

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

            // Route avoid options (Drive mode only)
            if (selectedTravelMode == com.example.navigation.TravelMode.DRIVE) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = avoidTolls, onClick = { onToggleAvoidTolls(!avoidTolls) }, label = { Text(strings.avoidTolls, fontSize = 12.sp) })
                    }
                    item {
                        FilterChip(selected = avoidHighways, onClick = { onToggleAvoidHighways(!avoidHighways) }, label = { Text(strings.avoidHighways, fontSize = 12.sp) })
                    }
                    item {
                        FilterChip(selected = avoidFerries, onClick = { onToggleAvoidFerries(!avoidFerries) }, label = { Text(strings.avoidFerries, fontSize = 12.sp) })
                    }
                }
            }

            // Missing state pack warning with direct Download button
            if (missingStates.isNotEmpty()) {
                val missingLabel = missingStates.joinToString(", ") {
                    if (language == AppLanguage.PERSIAN) it.nameFa else it.nameEn
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AlertAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, AlertAmber),
                    modifier = Modifier.fillMaxWidth().testTag("missing_states_route_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${strings.missingStateRouteTitle}: $missingLabel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = { onDownloadMissingState(missingStates.first().code) },
                            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("download_missing_state_button")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${strings.downloadMissingStateAction}: ${missingStates.first().abbr}")
                        }
                    }
                }
            } else if (routeError != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(routeError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(10.dp))
                }
            }

            // Up to 4 color-coded route cards with route type, duration, distance, and arrival time
            if (routes.isNotEmpty()) {
                val visibleRoutes = routes.take(4)
                val safeSelectedIdx = selectedRouteIndex.coerceIn(0, visibleRoutes.lastIndex)

                if (visibleRoutes.size <= 3) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleRoutes.forEachIndexed { idx, route ->
                            RouteOptionCard(idx, route, idx == safeSelectedIdx, unit, language, onSelectRoute, Modifier.weight(1f))
                        }
                    }
                } else {
                    LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleRoutes.size, key = { visibleRoutes[it].id }) { idx ->
                            RouteOptionCard(idx, visibleRoutes[idx], idx == safeSelectedIdx, unit, language, onSelectRoute, Modifier.width(122.dp))
                        }
                    }
                }

                // Expandable Turn-by-Turn Steps list before starting navigation
                val selectedRoute = visibleRoutes.getOrNull(safeSelectedIdx)
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
                                    Icon(maneuverIconFor(step.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = step.streetNames.firstOrNull()?.ifBlank { step.instructionEn } ?: step.instructionEn,
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (routes.isEmpty()) {
                    Button(
                        onClick = onCalculateRoutes,
                        enabled = !isCalculatingRoute,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("directions_button")
                    ) {
                        if (isCalculatingRoute) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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
                        modifier = Modifier.height(52.dp).testTag("toggle_steps_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showTurnsList) strings.hideTurns else strings.showTurns)
                    }
                    Button(
                        onClick = onStartNavigation,
                        colors = ButtonDefaults.buttonColors(containerColor = AlertSafeGreen),
                        modifier = Modifier.weight(1f).height(52.dp).testTag("start_navigation_button")
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
private fun RouteOptionCard(
    idx: Int,
    route: OfflineRoute,
    isSelected: Boolean,
    unit: DistanceUnit,
    language: AppLanguage,
    onSelectRoute: (Int) -> Unit,
    modifier: Modifier
) {
    val strings = LocalAppStrings.current
    val routeAccent = RoutePreviewColors.getOrElse(idx) { CyanBeacon }
    val routeBadgeLabel = when {
        route.isFastestRoute && route.isShortestRoute -> "${strings.routePrimary} • ${strings.routeShortest}"
        route.isShortestRoute -> strings.routeShortest
        route.isFastestRoute -> strings.routePrimary
        else -> strings.routeAlternate
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) routeAccent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) routeAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = modifier.clickable { onSelectRoute(idx) }.testTag("route_card_$idx")
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(routeBadgeLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = routeAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(routeAccent))
                Text(BilingualFormatter.formatDuration(route.durationSeconds, language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(BilingualFormatter.formatDistance(route.distanceMeters, unit, language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("${strings.arrivalLabel} ${BilingualFormatter.formatArrivalTime(route.durationSeconds, language)}", style = MaterialTheme.typography.labelSmall, color = routeAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
