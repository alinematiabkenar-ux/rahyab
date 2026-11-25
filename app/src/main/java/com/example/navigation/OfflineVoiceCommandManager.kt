package com.example.navigation

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceListenUiState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val rmsDb: Float = 0f,
    val offlineModelMissing: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val errorMessage: String? = null
)

/**
 * VC1: On-device offline English (`en-US`) SpeechRecognizer manager for phone voice commands.
 *
 * - Always interacts with [SpeechRecognizer] on the main looper.
 * - Uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] on Android 12+ when supported.
 * - On Android 13+, checks `checkRecognitionSupport` before listening and supports
 *   `triggerModelDownload` + Android Speech Settings deep-link if the offline `en-US` pack is absent.
 * - Acquires exclusive transient audio focus while listening and abandons it on completion/cancel.
 */
class OfflineVoiceCommandManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _uiState = MutableStateFlow(VoiceListenUiState())
    val uiState: StateFlow<VoiceListenUiState> = _uiState.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var activeTranscriptCallback: ((String) -> Unit)? = null
    private var activeNotUnderstoodCallback: (() -> Unit)? = null

    fun startListening(
        onBeforeStart: () -> Unit,
        onTranscript: (String) -> Unit,
        onNoSpeechOrNoMatch: () -> Unit
    ) {
        mainHandler.post {
            runCatching { onBeforeStart() }
            activeTranscriptCallback = onTranscript
            activeNotUnderstoodCallback = onNoSpeechOrNoMatch

            val sr = ensureRecognizerOnMain()
            if (sr == null) {
                _uiState.value = VoiceListenUiState(offlineModelMissing = true)
                return@post
            }

            val intent = buildOfflineEnglishIntent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    sr.checkRecognitionSupport(
                        intent,
                        ContextCompat.getMainExecutor(appContext),
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                val installed = recognitionSupport.installedOnDeviceLanguages
                                val hasEnglish = installed.any { tag ->
                                    val lower = tag.lowercase()
                                    lower == "en-us" || lower == "en_us" || lower.startsWith("en-") || lower == "en"
                                }
                                if (hasEnglish || installed.isEmpty() && recognitionSupport.supportedOnDeviceLanguages.isEmpty()) {
                                    beginListeningOnMain(sr, intent)
                                } else {
                                    abandonAudioFocus()
                                    _uiState.value = VoiceListenUiState(offlineModelMissing = true)
                                }
                            }

                            override fun onError(error: Int) {
                                // Fallback gracefully to starting recognizer directly if support query is unsupported
                                beginListeningOnMain(sr, intent)
                            }
                        }
                    )
                }.onFailure {
                    beginListeningOnMain(sr, intent)
                }
            } else {
                beginListeningOnMain(sr, intent)
            }
        }
    }

    fun triggerOfflineEnglishDownload() {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sr = ensureRecognizerOnMain()
                if (sr != null) {
                    _uiState.value = _uiState.value.copy(isDownloadingModel = true)
                    runCatching { sr.triggerModelDownload(buildOfflineEnglishIntent()) }
                }
            }
        }
    }

    fun cancelListening() {
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            abandonAudioFocus()
            activeTranscriptCallback = null
            activeNotUnderstoodCallback = null
            _uiState.value = VoiceListenUiState()
        }
    }

    fun dismissUi() {
        cancelListening()
    }

    fun destroy() {
        mainHandler.post {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
            abandonAudioFocus()
            _uiState.value = VoiceListenUiState()
        }
    }

    private fun beginListeningOnMain(sr: SpeechRecognizer, intent: Intent) {
        requestListeningAudioFocus()
        _uiState.value = VoiceListenUiState(isListening = true)
        runCatching {
            sr.startListening(intent)
        }.onFailure {
            abandonAudioFocus()
            _uiState.value = VoiceListenUiState(offlineModelMissing = true)
        }
    }

    private fun ensureRecognizerOnMain(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) -> {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                }
                SpeechRecognizer.isRecognitionAvailable(appContext) -> {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
                else -> null
            }
        }.getOrNull() ?: return null

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _uiState.value = _uiState.value.copy(
                    isListening = true,
                    offlineModelMissing = false,
                    errorMessage = null
                )
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (_uiState.value.isListening) {
                    _uiState.value = _uiState.value.copy(rmsDb = rmsdB.coerceIn(0f, 12f))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                abandonAudioFocus()
                val noMatchCb = activeNotUnderstoodCallback
                activeTranscriptCallback = null
                activeNotUnderstoodCallback = null
                when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        _uiState.value = VoiceListenUiState(offlineModelMissing = true)
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        _uiState.value = VoiceListenUiState()
                        noMatchCb?.invoke()
                    }
                    else -> {
                        _uiState.value = VoiceListenUiState()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                abandonAudioFocus()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                val cb = activeTranscriptCallback
                val noMatchCb = activeNotUnderstoodCallback
                activeTranscriptCallback = null
                activeNotUnderstoodCallback = null
                _uiState.value = VoiceListenUiState()
                if (best.isNotEmpty()) {
                    cb?.invoke(best)
                } else {
                    noMatchCb?.invoke()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty() && _uiState.value.isListening) {
                    _uiState.value = _uiState.value.copy(partialText = partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = created
        return created
    }

    private fun buildOfflineEnglishIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    private fun requestListeningAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    companion object {
        fun createSpeechSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
