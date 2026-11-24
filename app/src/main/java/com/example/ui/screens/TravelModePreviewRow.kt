package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.OfflineRoute
import com.example.navigation.TravelMode
import com.example.ui.theme.AlertSafeGreen
import com.example.ui.theme.CyanBeacon

data class ModePreviewSummary(
    val mode: TravelMode,
    val routes: List<OfflineRoute> = emptyList(),
    val isLoading: Boolean = false,
    val isTooFar: Boolean = false,
    val errorMessage: String? = null
)

@Composable
internal fun TravelModeChipsRow(
    selectedMode: TravelMode,
    summaries: Map<TravelMode, ModePreviewSummary>,
    onSelectMode: (TravelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TravelMode.entries.forEach { mode ->
            val summary = summaries[mode]
            val isSelected = mode == selectedMode
            val accent = when (mode) {
                TravelMode.DRIVE -> CyanBeacon
                TravelMode.WALK -> Color(0xFF2563EB)
                TravelMode.BIKE -> AlertSafeGreen
            }
            val icon = when (mode) {
                TravelMode.DRIVE -> Icons.Filled.DirectionsCar
                TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
                TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
            }
            val label = when (mode) {
                TravelMode.DRIVE -> strings.travelModeDrive
                TravelMode.WALK -> strings.travelModeWalk
                TravelMode.BIKE -> strings.travelModeBike
            }
            val subtitle = when {
                summary == null || summary.isLoading -> "…"
                summary.isTooFar -> strings.travelModeTooFar
                summary.routes.isNotEmpty() -> {
                    val primary = summary.routes.first()
                    val dur = BilingualFormatter.formatDuration(primary.durationSeconds, language)
                    val eta = BilingualFormatter.formatArrivalTime(primary.durationSeconds, language)
                    "$dur · $eta"
                }
                else -> "—"
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectMode(mode) }
                    .testTag("travel_mode_chip_${mode.code}")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = if (summary?.isTooFar == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
