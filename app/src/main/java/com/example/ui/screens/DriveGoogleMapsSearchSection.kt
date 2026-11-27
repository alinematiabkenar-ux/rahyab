package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.search.PlaceSearchResult
import com.example.i18n.LocalAppStrings
import com.example.ui.theme.CyanBeacon

internal data class GoogleMapsSearchUiState(
    val results: List<PlaceSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val statusMessage: String? = null,
    val isOnline: Boolean = false,
    val isEnabledInSettings: Boolean = true,
    val isApiKeyConfigured: Boolean = false
)

@Composable
internal fun GoogleMapsOnlineSearchActionRow(
    query: String,
    state: GoogleMapsSearchUiState,
    onSearchGoogleMapsOnline: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (query.trim().length < 2) return
    if (!state.isApiKeyConfigured && state.statusMessage.isNullOrBlank()) return
    val strings = LocalAppStrings.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.isEnabledInSettings && state.isApiKeyConfigured && state.isOnline) {
            OutlinedButton(
                onClick = onSearchGoogleMapsOnline,
                enabled = !state.isSearching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("search_google_maps_online_button")
            ) {
                if (state.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.searchGoogleMapsSearching, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Icon(
                        imageVector = Icons.Filled.TravelExplore,
                        contentDescription = strings.searchGoogleMapsOnlineAction,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(strings.searchGoogleMapsOnlineAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (state.isEnabledInSettings && state.isApiKeyConfigured && !state.isOnline) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().testTag("google_maps_offline_hint")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = strings.googleMapsOfflineUnavailable,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = strings.googleMapsOfflineUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        state.statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("google_maps_search_status")
            )
        }
    }
}

@Composable
internal fun GoogleMapsResultsHeader(modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .testTag("google_maps_results_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = strings.googleMapsOnlineSectionHeader,
                tint = CyanBeacon,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = strings.googleMapsOnlineSectionHeader,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = strings.googleMapsPoweredByAttribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("google_maps_attribution_label")
        )
    }
}
