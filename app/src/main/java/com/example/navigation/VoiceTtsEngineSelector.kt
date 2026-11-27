package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.i18n.AppLanguage
import com.example.util.CrashLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages Android TextToSpeech engines (default, Sherpa Persian, Google English),
 * diagnostics inspection, full UtteranceProgressListener callbacks (`onStart`, `onDone`,
 * `onStop`, `onError`), optional [TtsEngineDriver] test hook, and ref-counted shared
 * transient-ducking audio focus across speech and Turn Focus cues.
 */
internal class VoiceTtsEngineSelector(
    private val appContext: Context,
    private val callbacks: TtsUtteranceCallbacks
) {
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var tts: TextToSpeech? = null
        private set
    @Volatile
    private var baseInitialized: Boolean = false

    @Volatile
    var testEngineDriver: TtsEngineDriver? = null

    val isInitialized: Boolean
        get() = testEngineDriver?.isReady ?: baseInitialized

    var persianTts: TextToSpeech? = null
        private set
    @Volatile
    private var isPersianEngineReady = false
    private var persianEnginePkgCandidate: String = ""
    private var persianEngineLabelCandidate: String = ""

    var englishTts: TextToSpeech? = null
        private set
    @Volatile
    private var isEnglishEngineReady = false
    private var englishEnginePkgCandidate: String = ""
    private var englishEngineLabelCandidate: String = ""

    private val focusLock = Any()
    private val activeFocusOwners = mutableSetOf<String>()
    private var activeFocusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private val _isPersianVoiceAvailable = MutableStateFlow(true)
    val isPersianVoiceAvailable: StateFlow<Boolean> = _isPersianVoiceAvailable.asStateFlow()

    private val _ttsDiagnostics = MutableStateFlow(VoiceGuidanceManager.TtsDiagnosticsInfo())
    val ttsDiagnostics: StateFlow<VoiceGuidanceManager.TtsDiagnosticsInfo> = _ttsDiagnostics.asStateFlow()

    fun createBaseEngine(listener: TextToSpeech.OnInitListener) {
        tts = runCatching {
            TextToSpeech(appContext, listener)
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "TextToSpeech engine creation failed", err)
        }.getOrNull()
    }

    fun markBaseInitialized() {
        baseInitialized = true
        configureTtsInstance(tts)
        discoverAndBindSpecializedEngines()
        refreshTtsDiagnostics()
    }

    fun createProgressListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let { callbacks.onStart(it) }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { callbacks.onDone(it) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { callbacks.onStop(it, interrupted) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { callbacks.onError(it, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { callbacks.onError(it, errorCode) }
        }
    }

    fun configureTtsInstance(engine: TextToSpeech?) {
        if (engine == null) return
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(createProgressListener())
        }
    }

    private fun discoverAndBindSpecializedEngines() {
        val baseEngine = tts ?: return
        val engines = runCatching { baseEngine.engines?.toList().orEmpty() }.getOrDefault(emptyList())
        val defaultPkg = runCatching { baseEngine.defaultEngine.orEmpty() }.getOrDefault("")

        val sherpaEngine = engines.firstOrNull { it.name.contains("sherpa", ignoreCase = true) }
        if (sherpaEngine != null) {
            persianEnginePkgCandidate = sherpaEngine.name
            persianEngineLabelCandidate = sherpaEngine.label?.takeIf { it.isNotBlank() } ?: sherpaEngine.name
            if (sherpaEngine.name == defaultPkg) {
                if (supportsPersian(baseEngine)) {
                    persianTts = baseEngine
                    isPersianEngineReady = true
                }
            } else {
                runCatching {
                    persianTts = TextToSpeech(appContext, { initStatus ->
                        if (initStatus == TextToSpeech.SUCCESS) {
                            runCatching {
                                val candidate = persianTts
                                if (candidate != null && supportsPersian(candidate)) {
                                    configureTtsInstance(candidate)
                                    selectPersianLocale(candidate)
                                    isPersianEngineReady = true
                                } else {
                                    isPersianEngineReady = false
                                    runCatching { candidate?.shutdown() }
                                    persianTts = null
                                }
                            }.onFailure { err ->
                                isPersianEngineReady = false
                                CrashLogManager.logError(appContext, "TTS", "Sherpa Persian TTS setup failed", err)
                            }
                        } else {
                            isPersianEngineReady = false
                            runCatching { persianTts?.shutdown() }
                            persianTts = null
                        }
                        refreshTtsDiagnostics()
                    }, sherpaEngine.name)
                }.onFailure { err ->
                    isPersianEngineReady = false
                    CrashLogManager.logError(appContext, "TTS", "Failed to bind Sherpa TTS engine", err)
                }
            }
        }

        val googleEngine = engines.firstOrNull {
            it.name.equals(VoiceGuidanceManager.GOOGLE_TTS_PACKAGE, ignoreCase = true)
        }
        if (googleEngine != null) {
            englishEnginePkgCandidate = googleEngine.name
            englishEngineLabelCandidate = googleEngine.label?.takeIf { it.isNotBlank() } ?: googleEngine.name
            if (googleEngine.name == defaultPkg) {
                englishTts = baseEngine
                isEnglishEngineReady = true
            } else {
                runCatching {
                    englishTts = TextToSpeech(appContext, { initStatus ->
                        if (initStatus == TextToSpeech.SUCCESS) {
                            runCatching {
                                val candidate = englishTts
                                if (candidate != null) {
                                    configureTtsInstance(candidate)
                                    candidate.language = Locale.US
                                    isEnglishEngineReady = true
                                }
                            }.onFailure { err ->
                                isEnglishEngineReady = false
                                CrashLogManager.logError(appContext, "TTS", "Google English TTS setup failed", err)
                            }
                        } else {
                            isEnglishEngineReady = false
                            runCatching { englishTts?.shutdown() }
                            englishTts = null
                        }
                        refreshTtsDiagnostics()
                    }, googleEngine.name)
                }.onFailure { err ->
                    isEnglishEngineReady = false
                    CrashLogManager.logError(appContext, "TTS", "Failed to bind Google TTS engine", err)
                }
            }
        }
    }

    fun supportsPersian(engine: TextToSpeech): Boolean {
        val faIrStatus = runCatching { engine.isLanguageAvailable(Locale.forLanguageTag("fa-IR")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faIrStatus >= TextToSpeech.LANG_AVAILABLE) return true
        val faStatus = runCatching { engine.isLanguageAvailable(Locale.forLanguageTag("fa")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faStatus >= TextToSpeech.LANG_AVAILABLE) return true
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        return voices.any { it.locale.language.equals("fa", ignoreCase = true) }
    }

    fun selectPersianLocale(engine: TextToSpeech) {
        val faIr = Locale.forLanguageTag("fa-IR")
        val faIrStatus = runCatching { engine.isLanguageAvailable(faIr) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faIrStatus >= TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.language = faIr }
        } else {
            runCatching { engine.language = Locale.forLanguageTag("fa") }
        }
    }

    fun activePersianEngine(): TextToSpeech? =
        if (isPersianEngineReady && persianTts != null) persianTts else tts

    fun activeEnglishEngine(): TextToSpeech? =
        if (isEnglishEngineReady && englishTts != null) englishTts else tts

    fun hasDistinctFallbackEngine(isEnglish: Boolean): Boolean {
        if (testEngineDriver != null) return true
        val primary = if (isEnglish) activeEnglishEngine() else activePersianEngine()
        return tts != null && primary !== tts
    }

    fun speakSegment(
        text: String,
        isEnglish: Boolean,
        utteranceId: String,
        useFallbackEngine: Boolean = false
    ): Boolean {
        val driver = testEngineDriver
        if (driver != null) {
            return runCatching {
                driver.speakSegment(text, isEnglish, useFallbackEngine, utteranceId, callbacks)
            }.getOrDefault(false)
        }
        val engine = (if (useFallbackEngine) {
            tts
        } else if (isEnglish) {
            activeEnglishEngine() ?: tts
        } else {
            activePersianEngine() ?: tts
        }) ?: return false
        return runCatching {
            if (isEnglish) {
                engine.language = Locale.US
            } else {
                selectPersianLocale(engine)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "Segment speak failed (en=$isEnglish, fb=$useFallbackEngine)", err)
        }.getOrDefault(false)
    }

    fun isAnyEngineSpeaking(): Boolean {
        testEngineDriver?.let { return it.isSpeaking }
        return runCatching {
            (tts?.isSpeaking == true) ||
                (activeEnglishEngine()?.isSpeaking == true) ||
                (activePersianEngine()?.isSpeaking == true)
        }.getOrDefault(false)
    }

    fun acquireNavigationAudioFocus(owner: String) {
        val shouldRequest = synchronized(focusLock) {
            val wasEmpty = activeFocusOwners.isEmpty()
            activeFocusOwners.add(owner)
            wasEmpty
        }
        if (!shouldRequest) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                synchronized(focusLock) { activeFocusRequest = req }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    fun releaseNavigationAudioFocus(owner: String) {
        val reqToAbandon: AudioFocusRequest?
        val shouldAbandon = synchronized(focusLock) {
            activeFocusOwners.remove(owner)
            if (activeFocusOwners.isEmpty()) {
                reqToAbandon = activeFocusRequest
                activeFocusRequest = null
                true
            } else {
                reqToAbandon = null
                false
            }
        }
        if (!shouldAbandon) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reqToAbandon?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    fun requestDuckingFocus() = acquireNavigationAudioFocus(FOCUS_OWNER_SPEECH)

    fun abandonDuckingFocus() = releaseNavigationAudioFocus(FOCUS_OWNER_SPEECH)

    fun refreshTtsDiagnostics(): VoiceGuidanceManager.TtsDiagnosticsInfo {
        val baseEngine = tts
        if (baseEngine == null || !baseInitialized) {
            val empty = VoiceGuidanceManager.TtsDiagnosticsInfo(isInitialized = isInitialized)
            _ttsDiagnostics.value = empty
            return empty
        }
        return runCatching {
            val defaultPkg = runCatching { baseEngine.defaultEngine.orEmpty() }.getOrDefault("")
            val enginesList = runCatching { baseEngine.engines?.toList().orEmpty() }.getOrDefault(emptyList())
            fun labelForPackage(pkg: String): String {
                if (pkg.isBlank()) return ""
                val matched = enginesList.firstOrNull { it.name == pkg }
                return matched?.label?.takeIf { it.isNotBlank() } ?: runCatching {
                    appContext.packageManager.getApplicationLabel(appContext.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            val defaultLabel = labelForPackage(defaultPkg)
            val faEngine = activePersianEngine() ?: baseEngine
            val enEngine = activeEnglishEngine() ?: baseEngine
            val faEnginePkg = if (isPersianEngineReady && persianTts != null) persianEnginePkgCandidate else defaultPkg
            val faEngineLabel = if (isPersianEngineReady && persianTts != null) persianEngineLabelCandidate.ifBlank { labelForPackage(faEnginePkg) } else defaultLabel
            val enEnginePkg = if (isEnglishEngineReady && englishTts != null) englishEnginePkgCandidate else defaultPkg
            val enEngineLabel = if (isEnglishEngineReady && englishTts != null) englishEngineLabelCandidate.ifBlank { labelForPackage(enEnginePkg) } else defaultLabel
            val faStatus = maxOf(
                runCatching { faEngine.isLanguageAvailable(Locale.forLanguageTag("fa-IR")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED),
                runCatching { faEngine.isLanguageAvailable(Locale.forLanguageTag("fa")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            )
            val faVoices = runCatching { faEngine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter { it.locale.language.equals("fa", ignoreCase = true) }
            val faAvailable = faStatus >= TextToSpeech.LANG_AVAILABLE || faVoices.isNotEmpty()
            val faInstalled = faVoices.any { !it.isNetworkConnectionRequired } || (faVoices.isEmpty() && faStatus >= TextToSpeech.LANG_AVAILABLE)
            val enStatus = runCatching { enEngine.isLanguageAvailable(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val enVoices = runCatching { enEngine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter { it.locale.language.equals("en", ignoreCase = true) && it.locale.country.equals("US", ignoreCase = true) }
            val enAvailable = enStatus >= TextToSpeech.LANG_AVAILABLE || enVoices.isNotEmpty()
            val enInstalled = enVoices.any { !it.isNetworkConnectionRequired } || (enVoices.isEmpty() && enStatus >= TextToSpeech.LANG_AVAILABLE)
            _isPersianVoiceAvailable.value = faAvailable && faInstalled
            VoiceGuidanceManager.TtsDiagnosticsInfo(
                enginePackage = defaultPkg, engineLabel = defaultLabel.ifBlank { defaultPkg },
                persianEnginePackage = faEnginePkg, persianEngineLabel = faEngineLabel.ifBlank { faEnginePkg },
                englishEnginePackage = enEnginePkg, englishEngineLabel = enEngineLabel.ifBlank { enEnginePkg },
                isInitialized = true, isPersianAvailable = faAvailable, isPersianInstalled = faInstalled,
                isEnglishUsAvailable = enAvailable, isEnglishUsInstalled = enInstalled
            ).also { _ttsDiagnostics.value = it }
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "refreshTtsDiagnostics failed", err)
        }.getOrElse {
            VoiceGuidanceManager.TtsDiagnosticsInfo(isInitialized = isInitialized).also { _ttsDiagnostics.value = it }
        }
    }

    fun applyLanguage(language: AppLanguage) {
        if (language == AppLanguage.PERSIAN && _isPersianVoiceAvailable.value) {
            activePersianEngine()?.let { selectPersianLocale(it) }
        } else {
            runCatching { activeEnglishEngine()?.language = Locale.US }
        }
    }

    fun stopEngines() {
        testEngineDriver?.let {
            runCatching { it.stopAll() }
            return
        }
        runCatching { tts?.stop() }
        if (persianTts != null && persianTts !== tts) runCatching { persianTts?.stop() }
        if (englishTts != null && englishTts !== tts && englishTts !== persianTts) {
            runCatching { englishTts?.stop() }
        }
    }

    fun shutdown() {
        runCatching {
            testEngineDriver?.stopAll()
            if (persianTts != null && persianTts !== tts) persianTts?.shutdown()
            if (englishTts != null && englishTts !== tts && englishTts !== persianTts) englishTts?.shutdown()
            tts?.shutdown()
            synchronized(focusLock) { activeFocusOwners.clear() }
            abandonDuckingFocus()
        }
        persianTts = null
        englishTts = null
        tts = null
        isPersianEngineReady = false
        isEnglishEngineReady = false
        baseInitialized = false
    }

    companion object {
        const val FOCUS_OWNER_SPEECH = "speech"
        const val FOCUS_OWNER_BEEPS = "beeps"
    }
}
