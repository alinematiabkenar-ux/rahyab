package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.online.PhotonSearchClient
import com.example.data.search.OfflineAddressSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.CyanBeacon

@Composable
internal fun SearchResultsOverlayCard(
    query: String,
    results: List<PlaceSearchResult>,
    savedPlaces: List<SavedPlaceEntity>,
    hasAnyPlacesSource: Boolean,
    distanceUnit: DistanceUnit,
    onSelectPlace: (PlaceSearchResult) -> Unit,
    onSelectSaved: (SavedPlaceEntity) -> Unit,
    onGoToMaps: () -> Unit,
    googleMapsState: GoogleMapsSearchUiState = GoogleMapsSearchUiState(),
    onSearchGoogleMapsOnline: () -> Unit = {},
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
                                    Text(
                                        item.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (results.isEmpty() && googleMapsState.results.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
                GoogleMapsOnlineSearchActionRow(
                    query = query,
                    state = googleMapsState,
                    onSearchGoogleMapsOnline = onSearchGoogleMapsOnline
                )
            }
        } else {
            val offlineItems = results.filter { it.source != PhotonSearchClient.SOURCE_ONLINE_PHOTON }
            val onlineItems = results.filter { it.source == PhotonSearchClient.SOURCE_ONLINE_PHOTON }
            LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(offlineItems, key = { it.id }) { place ->
                    SearchResultPlaceRow(
                        place = place,
                        isOnlineResult = false,
                        onlineBadgeLabel = strings.onlineSearchResultBadge,
                        distanceUnit = distanceUnit,
                        language = language,
                        onSelectPlace = onSelectPlace
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                if (onlineItems.isNotEmpty()) {
                    item(key = "online_search_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Cloud,
                                contentDescription = strings.onlineSearchResultBadge,
                                tint = CyanBeacon,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = strings.onlineSearchSectionHeader,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(onlineItems, key = { it.id }) { place ->
                        SearchResultPlaceRow(
                            place = place,
                            isOnlineResult = true,
                            onlineBadgeLabel = strings.onlineSearchResultBadge,
                            distanceUnit = distanceUnit,
                            language = language,
                            onSelectPlace = onSelectPlace
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
                if (googleMapsState.results.isNotEmpty()) {
                    item(key = "google_maps_results_header") { GoogleMapsResultsHeader() }
                    items(googleMapsState.results, key = { it.id }) { place ->
                        SearchResultPlaceRow(
                            place = place,
                            isOnlineResult = true,
                            onlineBadgeLabel = strings.onlineSearchResultBadge,
                            distanceUnit = distanceUnit,
                            language = language,
                            onSelectPlace = onSelectPlace
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
                item(key = "google_maps_explicit_action") {
                    GoogleMapsOnlineSearchActionRow(
                        query = query,
                        state = googleMapsState,
                        onSearchGoogleMapsOnline = onSearchGoogleMapsOnline
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchResultPlaceRow(
    place: PlaceSearchResult,
    isOnlineResult: Boolean,
    onlineBadgeLabel: String,
    distanceUnit: DistanceUnit,
    language: AppLanguage,
    onSelectPlace: (PlaceSearchResult) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectPlace(place) }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(if (isOnlineResult) "online_search_result_item" else "search_result_item"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val isGnisLandmark = place.source == com.example.data.search.GnisPlaceSearchHelper.SOURCE_GNIS_LANDMARK
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isOnlineResult -> CyanBeacon.copy(alpha = 0.16f)
                        isGnisLandmark -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isOnlineResult -> Icons.Filled.Cloud
                    isGnisLandmark -> com.example.ui.components.gnisIconForFeatureClass(place.category)
                    else -> Icons.Filled.Place
                },
                contentDescription = if (isOnlineResult) onlineBadgeLabel else place.category.takeIf { isGnisLandmark },
                tint = when {
                    isOnlineResult -> CyanBeacon
                    isGnisLandmark -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val strings = LocalAppStrings.current
                val isApproxAddress = place.source == OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS
                if (isOnlineResult) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CyanBeacon.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = onlineBadgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanBeacon,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (isApproxAddress) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AlertAmber.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = strings.addressApproximateBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = AlertAmber,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (isGnisLandmark) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = place.category.ifBlank { strings.gnisLandmarkBadge },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
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
}

@Composable
internal fun SavedShortcutChip(
    label: String,
    sublabel: String,
    icon: ImageVector,
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
