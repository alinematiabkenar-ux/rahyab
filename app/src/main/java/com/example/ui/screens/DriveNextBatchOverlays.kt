package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.navigation.ActiveNavigationState
import com.example.ui.RahYabViewModel
import com.example.ui.components.ArrivedAtParkingWalkPromptCard
import com.example.ui.components.DestinationParkingOptionsCard
import com.example.ui.components.VoiceAutoStartCountdownPill
import com.example.ui.components.VoiceCommandListeningCard

/**
 * Hosts PK2/PK3 destination parking overlays and VC1/VC3 offline voice command overlays outside
 * [DriveScreen] so [DriveScreen] stays clean and well under 400 lines.
 */
@Composable
internal fun DriveNextBatchOverlays(
    viewModel: RahYabViewModel,
    navState: ActiveNavigationState,
    modifier: Modifier = Modifier
) {
    val parkingOptions by viewModel.destinationParkingOptions.collectAsStateWithLifecycle()
    val showParkingCard by viewModel.showDestinationParkingCard.collectAsStateWithLifecycle()
    val showWalkPrompt by viewModel.showWalkToOriginalDestinationPrompt.collectAsStateWithLifecycle()
    val originalDest by viewModel.originalFinalDestination.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceListenState.collectAsStateWithLifecycle()
    val voiceCountdownSec by viewModel.voiceAutoStartSecondsLeft.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        if (voiceCountdownSec > 0 && !navState.isNavigating) {
            VoiceAutoStartCountdownPill(
                secondsLeft = voiceCountdownSec,
                onCancel = { viewModel.cancelVoiceAutoStartCountdown() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 286.dp)
            )
        }

        if (showParkingCard && parkingOptions.isNotEmpty() && !showWalkPrompt) {
            DestinationParkingOptionsCard(
                options = parkingOptions,
                onSelectOption = { viewModel.selectDestinationParking(it) },
                onDismiss = { viewModel.dismissDestinationParkingCard() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = if (navState.isNavigating) 114.dp else 284.dp
                    )
            )
        }

        if (showWalkPrompt && originalDest != null) {
            ArrivedAtParkingWalkPromptCard(
                originalDestinationName = originalDest?.name.orEmpty(),
                onWalkToDestination = { viewModel.startWalkFromParkingToOriginalDestination() },
                onDismiss = { viewModel.dismissWalkToOriginalDestinationPrompt() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 114.dp)
            )
        }

        if (voiceState.isListening || voiceState.offlineModelMissing || voiceState.errorMessage != null) {
            VoiceCommandListeningCard(
                state = voiceState,
                onCancel = { viewModel.cancelVoiceCommandListening() },
                onDownloadModel = { viewModel.downloadOfflineEnglishModel() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 74.dp)
            )
        }
    }
}
