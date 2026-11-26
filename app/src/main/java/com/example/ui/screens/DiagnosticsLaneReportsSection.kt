package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.WrongLaneReportEntity
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.BilingualFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsLaneReportsCard(
    reports: List<WrongLaneReportEntity>,
    strings: AppStrings,
    language: AppLanguage,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("diagnostics_lane_reports_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = strings.diagSectionLaneReports,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (reports.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("diagnostics_clear_lane_reports_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = strings.diagClearLaneReports,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.diagClearLaneReports)
                    }
                }
            }

            if (reports.isEmpty()) {
                Text(
                    text = strings.diagNoLaneReports,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                reports.forEachIndexed { idx, report ->
                    if (idx > 0) HorizontalDivider()
                    val timeFormatted = BilingualFormatter.localizeDigits(
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(report.timestampMs)),
                        language
                    )
                    val roadLabel = report.roadNames.ifBlank { report.instructionText.ifBlank { report.maneuverType } }
                    val reasonSummary = buildString {
                        append(report.maneuverType)
                        append(" • ")
                        append(report.laneSource)
                        append(" • ")
                        append(report.confidence)
                        if (report.confidenceReasons.isNotBlank()) {
                            append(" (").append(report.confidenceReasons).append(')')
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "$timeFormatted — $roadLabel",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = reasonSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (report.approachWayId > 0L) {
                            val osmUrl = "https://www.openstreetmap.org/way/${report.approachWayId}"
                            Text(
                                text = osmUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    textDecoration = TextDecoration.Underline
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(osmUrl))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatLaneReportsForCopyAll(
    reports: List<WrongLaneReportEntity>,
    strings: AppStrings
): String = buildString {
    appendLine("[${strings.diagSectionLaneReports}]")
    if (reports.isEmpty()) {
        appendLine(strings.diagNoLaneReports)
        return@buildString
    }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    reports.forEachIndexed { idx, r ->
        appendLine("#${idx + 1} ${fmt.format(Date(r.timestampMs))} | roads=${r.roadNames.ifBlank { "—" }} | maneuver=${r.maneuverType} | mapVersion=${r.mapVersion}")
        appendLine("  loc=${String.format(Locale.US, "%.6f,%.6f", r.lat, r.lon)} bearing=${r.bearingDegrees.toInt()}° | source=${r.laneSource} | confidence=${r.confidence} | reasons=${r.confidenceReasons.ifBlank { "none" }}")
        appendLine("  targets=[${r.computedTargets}] edgeLaneCount=${r.edgeLaneCount} way=${if (r.approachWayId > 0L) "https://www.openstreetmap.org/way/${r.approachWayId}" else "unknown"}")
        appendLine("  instruction=${r.instructionText}")
        appendLine("  rawLaneJson=${r.rawLaneJson}")
    }
}.trimEnd()
