package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.AppLanguage
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.JunctionScene
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.HighwaySignBorder
import com.example.ui.theme.JetBrainsMonoFamily

/**
 * J3: Phone DriveScreen Sygic-style highway junction view card rendered via [JunctionViewRenderer].
 * Shows the 2:1 highway junction bitmap with counting-down localized distance below it.
 * Tapping anywhere on the card dismisses it for the current maneuver only.
 */
@Composable
fun JunctionViewCard(
    scene: JunctionScene,
    distanceText: String,
    isNightMode: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    laneChangeCuePulseKey: Long = 0L
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val cuePulse = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(laneChangeCuePulseKey) {
        if (laneChangeCuePulseKey > 0L) {
            repeat(5) {
                cuePulse.animateTo(1f, androidx.compose.animation.core.tween(300))
                cuePulse.animateTo(0.25f, androidx.compose.animation.core.tween(300))
            }
            cuePulse.animateTo(0f, androidx.compose.animation.core.tween(200))
        }
    }
    val imageBitmap = remember(scene, isNightMode) {
        JunctionViewRenderer.render(
            scene = scene,
            widthPx = 720,
            heightPx = 360,
            isNightMode = isNightMode
        ).asImageBitmap()
    }
    val subtitle = scene.destinationLines.firstOrNull().orEmpty()
    val exitPrefix = scene.exitNumber?.let { "${strings.exitLabel} $it" }.orEmpty()
    val borderColor = if (cuePulse.value > 0.05f) {
        CyanBeacon.copy(alpha = (0.55f + 0.45f * cuePulse.value).coerceIn(0f, 1f))
    } else {
        HighwaySignBorder.copy(alpha = 0.78f)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CockpitMidnight,
        border = BorderStroke(if (cuePulse.value > 0.05f) 3.dp else 1.5.dp, borderColor),
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .testTag("junction_preview_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = subtitle.ifBlank { strings.settingsJunctionViewTitle },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.0f)
                )
                if (cuePulse.value > 0.05f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(CyanBeacon.copy(alpha = 0.16f * cuePulse.value))
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CockpitMidnight)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = if (language == AppLanguage.PERSIAN) {
                                MaterialTheme.typography.titleMedium.fontFamily
                            } else {
                                JetBrainsMonoFamily
                            },
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = CyanBeacon,
                        maxLines = 1
                    )
                    val summary = listOf(exitPrefix, subtitle).filter { it.isNotBlank() }.joinToString(" • ")
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = strings.close,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
