package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ParkedCarEntity
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import java.util.Locale

private val ParkedBlue = Color(0xFF2563EB)

/**
 * Main-screen compact pill allowing the user to manually save their parked car spot ("I parked here").
 */
@Composable
fun ManualParkHereChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, ParkedBlue.copy(alpha = 0.55f)),
        shadowElevation = 5.dp,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag("manual_park_here_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LocalParking,
                contentDescription = null,
                tint = ParkedBlue,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = strings.parkedCarMenuAction,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * F4.3 DriveScreen card displayed when a parked car spot is stored and the user is > 50 m away.
 * Shows distance + relative parked time, optional note editor, "Walk to car", Share (geo:), and Clear.
 */
@Composable
fun ParkedCarCard(
    spot: ParkedCarEntity,
    distanceMeters: Double,
    unit: DistanceUnit,
    onWalkToCar: () -> Unit,
    onUpdateNote: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current

    var showNoteDialog by remember { mutableStateOf(false) }
    var noteDraft by remember(spot.note) { mutableStateOf(spot.note) }

    val summary = formatParkedCarSummary(
        distanceMeters = distanceMeters,
        parkedAtEpochMs = spot.parkedAtEpochMs,
        nowMs = System.currentTimeMillis(),
        unit = unit,
        language = language
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        border = BorderStroke(1.2.dp, ParkedBlue.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("parked_car_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ParkedBlue,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalParking,
                        contentDescription = strings.parkedCarLabel,
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (spot.note.isNotBlank()) {
                        Text(
                            text = spot.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("parked_car_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = strings.parkedCarClear,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onWalkToCar,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("parked_car_walk_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.parkedCarWalkToCar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        noteDraft = spot.note
                        showNoteDialog = true
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("parked_car_note_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.EditNote,
                        contentDescription = strings.parkedCarEditNote,
                        modifier = Modifier.size(18.dp)
                    )
                }

                OutlinedButton(
                    onClick = {
                        val geoUri = String.format(
                            Locale.US,
                            "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                            spot.lat,
                            spot.lon,
                            spot.lat,
                            spot.lon,
                            strings.parkedCarLabel
                        )
                        val shareText = if (spot.note.isNotBlank()) "$geoUri\n${spot.note}" else geoUri
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, strings.parkedCarLabel)
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, strings.parkedCarShare))
                        }
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("parked_car_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = strings.parkedCarShare,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text(strings.parkedCarEditNote) },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it.take(120) },
                    placeholder = { Text(strings.parkedCarNoteHint, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("parked_car_note_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateNote(noteDraft)
                        showNoteDialog = false
                    }
                ) {
                    Text(strings.saveNote)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

fun formatParkedCarSummary(
    distanceMeters: Double,
    parkedAtEpochMs: Long,
    nowMs: Long,
    unit: DistanceUnit,
    language: AppLanguage
): String {
    val distStr = BilingualFormatter.formatDistance(distanceMeters, unit, language)
    val elapsedMin = ((nowMs - parkedAtEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
    return if (language == AppLanguage.PERSIAN) {
        val timePart = when {
            elapsedMin < 1 -> "هم‌اکنون پارک شد"
            elapsedMin < 60 -> "${BilingualFormatter.formatNumber(elapsedMin, language)} دقیقه پیش پارک شد"
            elapsedMin < 24 * 60 -> "${BilingualFormatter.formatNumber(elapsedMin / 60, language)} ساعت پیش پارک شد"
            else -> "${BilingualFormatter.formatNumber(elapsedMin / (24 * 60), language)} روز پیش پارک شد"
        }
        "ماشین شما: $distStr · $timePart"
    } else {
        val timePart = when {
            elapsedMin < 1 -> "parked just now"
            elapsedMin < 60 -> "parked $elapsedMin min ago"
            elapsedMin < 24 * 60 -> "parked ${elapsedMin / 60} h ago"
            else -> "parked ${elapsedMin / (24 * 60)} d ago"
        }
        "Your car: $distStr · $timePart"
    }
}
