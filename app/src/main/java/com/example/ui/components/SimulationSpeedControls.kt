package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings

/**
 * S1: Pure Kotlin simulator speed stepper (1, 2, 4, 8, 16, 32) synced with the side menu.
 */
object SimulationSpeedStepper {
    val STEPS: List<Int> = listOf(1, 2, 4, 8, 16, 32)

    fun clampToValidStep(current: Int): Int =
        STEPS.minByOrNull { kotlin.math.abs(it - current) } ?: STEPS.first()

    fun stepUp(current: Int): Int {
        val clamped = clampToValidStep(current)
        val idx = STEPS.indexOf(clamped)
        return STEPS[(idx + 1).coerceAtMost(STEPS.lastIndex)]
    }

    fun stepDown(current: Int): Int {
        val clamped = clampToValidStep(current)
        val idx = STEPS.indexOf(clamped)
        return STEPS[(idx - 1).coerceAtLeast(0)]
    }

    fun canStepUp(current: Int): Boolean = clampToValidStep(current) < STEPS.last()

    fun canStepDown(current: Int): Boolean = clampToValidStep(current) > STEPS.first()

    fun formatSpeedBadge(speed: Int, language: AppLanguage): String {
        val safe = clampToValidStep(speed)
        return "×${BilingualFormatter.localizeDigits(safe.toString(), language)}"
    }
}

/**
 * S1: Two round buttons ("−" and "+") with the current simulation speed ("×4") between them,
 * placed on the left side above the bottom bar while a simulation is running.
 */
@Composable
fun SimulationSpeedStepControls(
    currentSpeed: Int,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val canDecrease = SimulationSpeedStepper.canStepDown(currentSpeed)
    val canIncrease = SimulationSpeedStepper.canStepUp(currentSpeed)

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
        modifier = modifier.testTag("sim_speed_controls")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(PaddingValues(horizontal = 6.dp, vertical = 4.dp))
        ) {
            FilledTonalIconButton(
                onClick = onStepDown,
                enabled = canDecrease,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = strings.simSpeedDecreaseDesc }
                    .testTag("sim_speed_minus_button")
            ) {
                Text(
                    text = "−",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = SimulationSpeedStepper.formatSpeedBadge(currentSpeed, language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(min = 42.dp)
                    .padding(horizontal = 4.dp)
                    .testTag("sim_speed_value_label")
            )

            FilledTonalIconButton(
                onClick = onStepUp,
                enabled = canIncrease,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = strings.simSpeedIncreaseDesc }
                    .testTag("sim_speed_plus_button")
            ) {
                Text(
                    text = "+",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
