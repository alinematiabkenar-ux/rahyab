package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.car.CarScreenDiagnosticsSnapshot
import com.example.i18n.AppStrings
import java.util.Locale

/**
 * A4: Diagnostics "Car screen" card showing live Android Auto surface size, density,
 * camera zoom/tilt, visible/stable areas, API level, top screen class, last template,
 * and last template error.
 */
@Composable
fun DiagnosticsCarScreenCard(
    diag: CarScreenDiagnosticsSnapshot,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("diag_car_screen_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(strings.diagSectionCarScreen, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (!diag.isConnected && diag.surfaceWidth == 0 && diag.lastTemplateError == null) {
                Text(
                    text = strings.diagCarNotConnected,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                CarDiagRow("Surface", "${diag.surfaceWidth}x${diag.surfaceHeight} @ ${diag.dpi} dpi (ratio ${String.format(Locale.US, "%.2f", diag.pixelRatio)})")
                CarDiagRow("Car zoom / tilt", "${diag.currentZoom?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} / ${diag.currentTilt?.let { String.format(Locale.US, "%.0f°", it) } ?: "—"}")
                CarDiagRow("Visible area", diag.visibleArea)
                CarDiagRow("Stable area", diag.stableArea)
                CarDiagRow("Car API level", if (diag.carApiLevel > 0) diag.carApiLevel.toString() else "—")
                CarDiagRow("Top screen", diag.topScreenClass)
                val stepFlag = diag.lastRoutingInfoHadStep?.let { if (it) "step=yes" else "step=no" } ?: "step=—"
                CarDiagRow("Last template", "${diag.lastTemplateType} ($stepFlag)")
                CarDiagRow("Last template error", diag.lastTemplateError ?: "—")
            }
        }
    }
}

@Composable
private fun CarDiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1.1f)
        )
    }
}

fun formatCarScreenDiagnosticsSection(
    diag: CarScreenDiagnosticsSnapshot,
    strings: AppStrings
): String = buildString {
    appendLine("[${strings.diagSectionCarScreen}]")
    if (!diag.isConnected && diag.surfaceWidth == 0 && diag.lastTemplateError == null) {
        appendLine(strings.diagCarNotConnected)
    } else {
        appendLine("Connected: ${diag.isConnected}")
        appendLine("Surface: ${diag.surfaceWidth}x${diag.surfaceHeight} @ ${diag.dpi} dpi (pixelRatio=${String.format(Locale.US, "%.2f", diag.pixelRatio)})")
        appendLine("Zoom/Tilt: ${diag.currentZoom?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} / ${diag.currentTilt?.let { String.format(Locale.US, "%.0f°", it) } ?: "—"}")
        appendLine("VisibleArea: ${diag.visibleArea}")
        appendLine("StableArea: ${diag.stableArea}")
        appendLine("CarApiLevel: ${diag.carApiLevel}")
        appendLine("TopScreen: ${diag.topScreenClass}")
        appendLine("LastTemplate: ${diag.lastTemplateType} (routingInfoHadStep=${diag.lastRoutingInfoHadStep ?: "null"})")
        appendLine("LastTemplateError: ${diag.lastTemplateError ?: "none"}")
    }
}.trimEnd()
