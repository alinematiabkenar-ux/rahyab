package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.OfflineVoiceCommandManager
import com.example.navigation.VoiceListenUiState
import com.example.ui.RahYabViewModel
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CyanBeacon

/**
 * VC1: Returns a click callback that requests RECORD_AUDIO strictly on first tap and launches
 * offline English voice command listening once granted.
 */
@Composable
fun rememberVoiceCommandTrigger(viewModel: RahYabViewModel): () -> Unit {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceCommandListening()
        } else {
            viewModel.showSnackbar(strings.voiceMicPermissionNeeded)
        }
    }
    return {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            viewModel.startVoiceCommandListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/**
 * VC1: Listening animation & offline English model setup helper card.
 */
@Composable
fun VoiceCommandListeningCard(
    state: VoiceListenUiState,
    onCancel: () -> Unit,
    onDownloadModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shadowElevation = 12.dp,
        border = BorderStroke(1.5.dp, if (state.isListening) CyanBeacon else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_command_listening_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isListening) {
                    PulsingMicIndicator(rmsDb = state.rmsDb)
                } else {
                    Surface(shape = CircleShape, color = AlertRed.copy(alpha = 0.14f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = AlertRed, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.voiceListeningTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.partialText.isNotBlank()) {
                        Text(
                            text = state.partialText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (state.offlineModelMissing) {
                        Text(
                            text = strings.voiceOfflineModelMissing,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("voice_listen_cancel_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = strings.close)
                }
            }

            if (state.offlineModelMissing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onDownloadModel,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_download_model_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.voiceDownloadModelButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            val opened = runCatching {
                                context.startActivity(OfflineVoiceCommandManager.createSpeechSettingsIntent())
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("voice_speech_settings_button")
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.voiceOpenSpeechSettingsButton, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingMicIndicator(rmsDb: Float) {
    val infinite = rememberInfiniteTransition(label = "voice_mic_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "voice_mic_scale"
    )
    val dynamicScale = (pulse + (rmsDb / 40f)).coerceAtMost(1.38f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(dynamicScale)
                .background(CyanBeacon.copy(alpha = 0.25f), CircleShape)
        )
        Surface(shape = CircleShape, color = CyanBeacon, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * VC3: Cancellable 5-second auto-start countdown banner shown above route preview after a strong voice match.
 */
@Composable
fun VoiceAutoStartCountdownPill(
    secondsLeft: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_autostart_countdown_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${strings.voiceAutoStartCountdownPrefix} ${BilingualFormatter.formatNumber(secondsLeft, language)}s",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = AlertRed),
                modifier = Modifier.testTag("voice_autostart_cancel_button")
            ) {
                Text(strings.voiceCancelAutoStart, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
