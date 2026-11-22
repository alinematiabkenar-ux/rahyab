package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.util.CrashLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Bilingual Android Text-to-Speech voice guidance engine for natural American English (en-US)
 * and natural spoken Persian (fa-IR).
 *
 * Covers Phases 2, 3, and 4:
 * - Natural American English & natural spoken Persian turn-by-turn phrasing (street names verbatim)
 * - Phase 3 early lane guidance voice instructions (strictly when real turn:lanes exist!)
 * - Phase 3 highway signpost exit number & main destination voice phrasing
 * - Phase 3 road alerts (speed limit drops, sharp curves, tolls, railroad, school zones, stop signs)
 * - Phase 4 Waze-style user report announcements (all 8 types in EN & FA)
 * - Audio focus ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), Repeat, Mute/Unmute, Rerouting, Arrival
 */
class VoiceGuidanceManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // Default fallback engine (also used for engine enumeration via getEngines())
    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false

    // Dedicated Persian engine (e.g. external Sherpa TTS app when installed and supports fa/fa-IR)
    private var persianTts: TextToSpeech? = null
    @Volatile
    private var isPersianEngineReady = false
    private var persianEnginePkgCandidate: String = ""
    private var persianEngineLabelCandidate: String = ""

    // Dedicated English engine (Google TTS `com.google.android.tts` when installed)
    private var englishTts: TextToSpeech? = null
    @Volatile
    private var isEnglishEngineReady = false
    private var englishEnginePkgCandidate: String = ""
    private var englishEngineLabelCandidate: String = ""

    private var activeFocusRequest: AudioFocusRequest? = null

    data class SpeechSegment(
        val text: String,
        val isEnglish: Boolean
    )

    data class TtsDiagnosticsInfo(
        val enginePackage: String = "",
        val engineLabel: String = "",
        val persianEnginePackage: String = "",
        val persianEngineLabel: String = "",
        val englishEnginePackage: String = "",
        val englishEngineLabel: String = "",
        val isInitialized: Boolean = false,
        val isPersianAvailable: Boolean = false,
        val isPersianInstalled: Boolean = false,
        val isEnglishUsAvailable: Boolean = false,
        val isEnglishUsInstalled: Boolean = false
    )

    private val _isPersianVoiceAvailable = MutableStateFlow(true)
    val isPersianVoiceAvailable: StateFlow<Boolean> = _isPersianVoiceAvailable.asStateFlow()

    private val _ttsDiagnostics = MutableStateFlow(TtsDiagnosticsInfo())
    val ttsDiagnostics: StateFlow<TtsDiagnosticsInfo> = _ttsDiagnostics.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    private var currentLanguage: AppLanguage = AppLanguage.ENGLISH
    private var useEnglishVoiceInPersianMode: Boolean = false
    private var voiceEnabled: Boolean = true
    private var lastSpokenKey: String = ""
    private var lastSpokenTimeMs: Long = 0L
    private var lastOverspeedBeepMs: Long = 0L
    private var lastCameraChimeMs: Long = 0L

    private val speechGeneration = AtomicLong(0L)
    private val sequenceLock = Any()
    private val pendingSegments = ArrayDeque<SpeechSegment>()
    @Volatile
    private var activeSequenceGeneration: Long = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    init {
        tts = runCatching {
            TextToSpeech(appContext, this)
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "TextToSpeech engine creation failed", err)
        }.getOrNull()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                isInitialized = true
                configureTtsInstance(tts)
                discoverAndBindSpecializedEngines()
                refreshTtsDiagnostics()
                applyLanguage(resolveEffectiveLanguage(currentLanguage))
            }.onFailure { err ->
                CrashLogManager.logError(appContext, "TTS", "TextToSpeech onInit setup failed", err)
            }
        }
    }

    private fun configureTtsInstance(engine: TextToSpeech?) {
        if (engine == null) return
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestDuckingFocus()
                }

                override fun onDone(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleUtteranceFinished(utteranceId)
                }
            })
        }
    }

    private fun discoverAndBindSpecializedEngines() {
        val baseEngine = tts ?: return
        val engines = runCatching { baseEngine.engines?.toList().orEmpty() }.getOrDefault(emptyList())
        val defaultPkg = runCatching { baseEngine.defaultEngine.orEmpty() }.getOrDefault("")

        // 1. Persian: prefer any installed engine whose package name contains "sherpa"
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

        // 2. English: prefer Google's engine (com.google.android.tts) if installed, else default
        val googleEngine = engines.firstOrNull { it.name.equals(GOOGLE_TTS_PACKAGE, ignoreCase = true) }
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

    private fun supportsPersian(engine: TextToSpeech): Boolean {
        val faIrStatus = runCatching { engine.isLanguageAvailable(Locale.forLanguageTag("fa-IR")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faIrStatus >= TextToSpeech.LANG_AVAILABLE) return true
        val faStatus = runCatching { engine.isLanguageAvailable(Locale.forLanguageTag("fa")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faStatus >= TextToSpeech.LANG_AVAILABLE) return true
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        return voices.any { it.locale.language.equals("fa", ignoreCase = true) }
    }

    private fun selectPersianLocale(engine: TextToSpeech) {
        val faIr = Locale.forLanguageTag("fa-IR")
        val faIrStatus = runCatching { engine.isLanguageAvailable(faIr) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (faIrStatus >= TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.language = faIr }
        } else {
            runCatching { engine.language = Locale.forLanguageTag("fa") }
        }
    }

    private fun activePersianEngine(): TextToSpeech? =
        if (isPersianEngineReady && persianTts != null) persianTts else tts

    private fun activeEnglishEngine(): TextToSpeech? =
        if (isEnglishEngineReady && englishTts != null) englishTts else tts

    private fun requestDuckingFocus() {
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
                activeFocusRequest = req
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

    private fun abandonDuckingFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    fun refreshTtsDiagnostics(): TtsDiagnosticsInfo {
        val baseEngine = tts
        if (baseEngine == null || !isInitialized) {
            val empty = TtsDiagnosticsInfo(isInitialized = isInitialized)
            _ttsDiagnostics.value = empty
            return empty
        }
        return runCatching {
            val defaultPkg = runCatching { baseEngine.defaultEngine.orEmpty() }.getOrDefault("")
            val enginesList = runCatching { baseEngine.engines?.toList().orEmpty() }.getOrDefault(emptyList())

            fun labelForPackage(pkg: String): String {
                if (pkg.isBlank()) return ""
                val matched = enginesList.firstOrNull { it.name == pkg }
                return matched?.label?.takeIf { it.isNotBlank() }
                    ?: runCatching {
                        val pm = appContext.packageManager
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    }.getOrDefault(pkg)
            }

            val defaultLabel = labelForPackage(defaultPkg)
            val faEngine = activePersianEngine() ?: baseEngine
            val enEngine = activeEnglishEngine() ?: baseEngine

            val faEnginePkg = if (isPersianEngineReady && persianTts != null) persianEnginePkgCandidate else defaultPkg
            val faEngineLabel = if (isPersianEngineReady && persianTts != null) {
                persianEngineLabelCandidate.ifBlank { labelForPackage(faEnginePkg) }
            } else {
                defaultLabel
            }

            val enEnginePkg = if (isEnglishEngineReady && englishTts != null) englishEnginePkgCandidate else defaultPkg
            val enEngineLabel = if (isEnglishEngineReady && englishTts != null) {
                englishEngineLabelCandidate.ifBlank { labelForPackage(enEnginePkg) }
            } else {
                defaultLabel
            }

            val faLocaleIr = Locale.forLanguageTag("fa-IR")
            val faLocale = Locale.forLanguageTag("fa")
            val faStatus = maxOf(
                runCatching { faEngine.isLanguageAvailable(faLocaleIr) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED),
                runCatching { faEngine.isLanguageAvailable(faLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            )
            val faVoices = runCatching { faEngine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter { it.locale.language.equals("fa", ignoreCase = true) }
            val faAvailable = faStatus >= TextToSpeech.LANG_AVAILABLE || faVoices.isNotEmpty()
            val faInstalled = faVoices.any { !it.isNetworkConnectionRequired } ||
                (faVoices.isEmpty() && faStatus >= TextToSpeech.LANG_AVAILABLE)

            val enStatus = runCatching { enEngine.isLanguageAvailable(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val enVoices = runCatching { enEngine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter {
                    it.locale.language.equals("en", ignoreCase = true) &&
                        it.locale.country.equals("US", ignoreCase = true)
                }
            val enAvailable = enStatus >= TextToSpeech.LANG_AVAILABLE || enVoices.isNotEmpty()
            val enInstalled = enVoices.any { !it.isNetworkConnectionRequired } ||
                (enVoices.isEmpty() && enStatus >= TextToSpeech.LANG_AVAILABLE)

            _isPersianVoiceAvailable.value = faAvailable && faInstalled
            val info = TtsDiagnosticsInfo(
                enginePackage = defaultPkg,
                engineLabel = defaultLabel.ifBlank { defaultPkg },
                persianEnginePackage = faEnginePkg,
                persianEngineLabel = faEngineLabel.ifBlank { faEnginePkg },
                englishEnginePackage = enEnginePkg,
                englishEngineLabel = enEngineLabel.ifBlank { enEnginePkg },
                isInitialized = true,
                isPersianAvailable = faAvailable,
                isPersianInstalled = faInstalled,
                isEnglishUsAvailable = enAvailable,
                isEnglishUsInstalled = enInstalled
            )
            _ttsDiagnostics.value = info
            info
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "refreshTtsDiagnostics failed", err)
        }.getOrElse {
            TtsDiagnosticsInfo(isInitialized = isInitialized).also { _ttsDiagnostics.value = it }
        }
    }

    fun checkPersianAvailability(): Boolean {
        val info = refreshTtsDiagnostics()
        return info.isPersianAvailable && info.isPersianInstalled
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        if (isInitialized) {
            applyLanguage(resolveEffectiveLanguage(language))
        }
    }

    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) {
        useEnglishVoiceInPersianMode = enabled
        if (isInitialized) {
            applyLanguage(resolveEffectiveLanguage(currentLanguage))
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled) {
            stopAllSpeech()
            abandonDuckingFocus()
        }
    }

    private fun stopAllSpeech() {
        speechGeneration.incrementAndGet()
        synchronized(sequenceLock) {
            pendingSegments.clear()
        }
        runCatching { tts?.stop() }
        if (persianTts != null && persianTts !== tts) runCatching { persianTts?.stop() }
        if (englishTts != null && englishTts !== tts && englishTts !== persianTts) {
            runCatching { englishTts?.stop() }
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        if (language == AppLanguage.PERSIAN && _isPersianVoiceAvailable.value) {
            activePersianEngine()?.let { selectPersianLocale(it) }
        } else {
            runCatching { activeEnglishEngine()?.language = Locale.US }
        }
    }

    private fun resolveEffectiveLanguage(requested: AppLanguage): AppLanguage {
        if (requested == AppLanguage.PERSIAN && useEnglishVoiceInPersianMode) {
            return AppLanguage.ENGLISH
        }
        return if (requested == AppLanguage.PERSIAN && _isPersianVoiceAvailable.value) {
            AppLanguage.PERSIAN
        } else {
            AppLanguage.ENGLISH
        }
    }

    fun speakManeuver(
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        announcementStage: String,
        includeLaneGuidance: Boolean = true
    ): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatSpokenInstruction(
            maneuver = maneuver,
            distanceMeters = distanceMeters,
            unit = unit,
            language = effectiveLanguage,
            includeLaneGuidance = includeLaneGuidance,
            announcementStage = announcementStage
        )
        _lastSpokenText.value = utterance

        if (!voiceEnabled || !isInitialized) return utterance
        val key = "${maneuver.index}_$announcementStage"
        val now = System.currentTimeMillis()
        if (key == lastSpokenKey && now - lastSpokenTimeMs < 7000L) return utterance

        speakRaw(utterance, effectiveLanguage)
        lastSpokenKey = key
        lastSpokenTimeMs = now
        return utterance
    }

    /**
     * Speaks the initial departure instruction when navigation starts, describing the initial
     * compass direction and starting street plus the first upcoming turn (e.g.,
     * "Head east on East 22nd Avenue, then turn right onto Hilyard Street." and Persian equivalent).
     */
    fun speakStartInstruction(route: OfflineRoute, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatStartInstruction(route, effectiveLanguage)
        if (utterance.isBlank()) return ""
        _lastSpokenText.value = utterance
        if (voiceEnabled && isInitialized) {
            speakRaw(utterance, effectiveLanguage)
            lastSpokenKey = "route_start_${route.id}"
            lastSpokenTimeMs = System.currentTimeMillis()
        }
        return utterance
    }

    fun speakRerouting(language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatReroutingPrompt(effectiveLanguage)
        _lastSpokenText.value = utterance
        if (voiceEnabled && isInitialized) {
            speakRaw(utterance, effectiveLanguage)
        }
        return utterance
    }

    fun speakArrival(maneuverType: ManeuverType, destinationName: String, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatArrivalPrompt(maneuverType, destinationName, effectiveLanguage)
        _lastSpokenText.value = utterance
        if (voiceEnabled && isInitialized) {
            speakRaw(utterance, effectiveLanguage)
        }
        return utterance
    }

    fun repeatLastInstruction(language: AppLanguage) {
        val text = _lastSpokenText.value
        if (text.isBlank() || !isInitialized) return
        val effectiveLanguage = resolveEffectiveLanguage(language)
        speakRaw(text, effectiveLanguage)
    }

    fun speakAlert(
        alert: AheadAlert,
        language: AppLanguage,
        unit: DistanceUnit = DistanceUnit.IMPERIAL
    ): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage, unit)
        if (voiceEnabled && isInitialized) {
            speakRaw(text, effectiveLanguage)
        }
        return text
    }

    fun speakTestSample(language: AppLanguage) {
        if (!isInitialized) return
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val sample = if (effectiveLanguage == AppLanguage.PERSIAN) {
            "در یک مایلی، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید."
        } else {
            "In 1 mile, use the right two lanes to take exit 194B toward Portland."
        }
        _lastSpokenText.value = sample
        speakRaw(sample, effectiveLanguage)
    }

    /**
     * Explicitly tests either Persian (`fa-IR`, using Sherpa + Google English for street names when
     * available) or American English (`en-US`, using Google TTS when installed) from Diagnostics.
     */
    fun speakExplicitLanguageTest(targetLanguage: AppLanguage): Boolean {
        if (!isInitialized) return false
        val info = refreshTtsDiagnostics()
        if (targetLanguage == AppLanguage.PERSIAN && !info.isPersianAvailable) {
            return false
        }
        val sample = if (targetLanguage == AppLanguage.PERSIAN) {
            "آزمایش صدای فارسی رهیاب. در نیم مایلی، به راست بپیچید و وارد Main Street شوید."
        } else {
            "RahYab English voice test. In half a mile, use the right lane to turn right onto Main Street."
        }
        _lastSpokenText.value = sample
        speakRaw(sample, targetLanguage)
        return true
    }

    fun playOverspeedChime() {
        val now = System.currentTimeMillis()
        if (now - lastOverspeedBeepMs < 6000L) return
        lastOverspeedBeepMs = now
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        }
    }

    fun playCameraAlertSound() {
        if (!voiceEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastCameraChimeMs < 2000L) return
        lastCameraChimeMs = now
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        }
    }

    private fun speakRaw(text: String, effectiveLanguage: AppLanguage) {
        if (text.isBlank()) return
        runCatching {
            stopAllSpeech()
            val gen = speechGeneration.incrementAndGet()
            activeSequenceGeneration = gen

            val segments = if (effectiveLanguage == AppLanguage.PERSIAN) {
                splitPersianAndEnglishSegments(text)
            } else {
                listOf(SpeechSegment(text = text, isEnglish = true))
            }

            if (segments.isEmpty()) return
            synchronized(sequenceLock) {
                pendingSegments.clear()
                segments.forEach { pendingSegments.addLast(it) }
            }
            requestDuckingFocus()
            dispatchNextSegment(gen)
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "speakRaw failed, falling back to default engine", err)
            runCatching {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rahyab_fb_${System.currentTimeMillis()}")
            }
        }
    }

    private fun handleUtteranceFinished(utteranceId: String?) {
        val gen = utteranceId
            ?.substringAfter("gen_", missingDelimiterValue = "")
            ?.substringBefore("_")
            ?.toLongOrNull()
        if (gen == null || gen != activeSequenceGeneration) {
            return
        }
        dispatchNextSegment(gen)
    }

    private fun dispatchNextSegment(gen: Long) {
        if (gen != activeSequenceGeneration) return
        val next = synchronized(sequenceLock) {
            if (gen != activeSequenceGeneration || pendingSegments.isEmpty()) null
            else pendingSegments.removeFirst()
        }
        if (next == null) {
            abandonDuckingFocus()
            return
        }

        val engine = if (next.isEnglish) {
            activeEnglishEngine() ?: tts
        } else {
            activePersianEngine() ?: tts
        }
        if (engine == null) {
            abandonDuckingFocus()
            return
        }

        val utteranceId = "rahyab_gen_${gen}_${System.nanoTime()}"
        val spokenOk = runCatching {
            if (next.isEnglish) {
                engine.language = Locale.US
            } else {
                selectPersianLocale(engine)
            }
            val res = engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            res == TextToSpeech.SUCCESS
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "Segment speak failed (${next.isEnglish})", err)
        }.getOrDefault(false)

        if (!spokenOk) {
            // Fall back to default engine for this segment, or advance immediately so queue never stalls
            val fallbackEngine = tts
            val fbOk = if (fallbackEngine != null && fallbackEngine !== engine) {
                runCatching {
                    fallbackEngine.language = if (next.isEnglish) Locale.US else Locale.forLanguageTag("fa-IR")
                    fallbackEngine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
            } else false
            if (!fbOk) {
                dispatchNextSegment(gen)
            }
        }
    }

    fun resetDeduplication() {
        lastSpokenKey = ""
        lastSpokenTimeMs = 0L
    }

    fun shutdown() {
        stopAllSpeech()
        runCatching {
            if (persianTts != null && persianTts !== tts) {
                persianTts?.shutdown()
            }
            if (englishTts != null && englishTts !== tts && englishTts !== persianTts) {
                englishTts?.shutdown()
            }
            tts?.shutdown()
            abandonDuckingFocus()
        }
        persianTts = null
        englishTts = null
        tts = null
        isPersianEngineReady = false
        isEnglishEngineReady = false
        isInitialized = false
    }

    companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        private val ENGLISH_TOKEN_REGEX = Regex(
            """(?:[A-Za-z][A-Za-z0-9'\-./]*|\d+[A-Za-z]+(?:-[A-Za-z0-9]+)*)(?:\s+(?:[A-Za-z0-9][A-Za-z0-9'\-./]*|&))*"""
        )

        /**
         * Splits a Persian navigation instruction containing English street names, highway numbers,
         * or exit codes (e.g. `I-5`, `194B`, `East 22nd Avenue`, `Portland`) into ordered Persian
         * and English speech segments so each runs on its native TTS engine without long pauses.
         */
        fun splitPersianAndEnglishSegments(text: String): List<SpeechSegment> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()

            val segments = mutableListOf<SpeechSegment>()
            var cursor = 0
            for (match in ENGLISH_TOKEN_REGEX.findAll(trimmed)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (start > cursor) {
                    val faPart = trimmed.substring(cursor, start).trim()
                    if (faPart.isNotEmpty() && faPart.any { it.isLetterOrDigit() }) {
                        segments.add(SpeechSegment(faPart, isEnglish = false))
                    }
                }
                val enPart = match.value.trim().trimEnd('.', ',', '،', ';', ':')
                if (enPart.isNotEmpty()) {
                    segments.add(SpeechSegment(enPart, isEnglish = true))
                }
                cursor = end
            }
            if (cursor < trimmed.length) {
                val tail = trimmed.substring(cursor).trim()
                if (tail.isNotEmpty() && tail.any { it.isLetterOrDigit() }) {
                    segments.add(SpeechSegment(tail, isEnglish = false))
                }
            }
            return if (segments.isEmpty()) {
                listOf(SpeechSegment(trimmed, isEnglish = false))
            } else {
                segments
            }
        }
        fun formatReroutingPrompt(language: AppLanguage): String =
            if (language == AppLanguage.PERSIAN) "در حال مسیریابی مجدد." else "Rerouting."

        fun formatAlertAnnouncement(
            alert: AheadAlert,
            language: AppLanguage,
            unit: DistanceUnit = DistanceUnit.IMPERIAL
        ): String {
            if (alert.kind == RoadAlertKind.SPEED_CAMERA) {
                val limitMph = alert.newSpeedLimitMph
                if (limitMph != null && limitMph > 0) {
                    val shownLimit = if (unit == DistanceUnit.METRIC) {
                        (limitMph * 1.60934).roundToInt()
                    } else {
                        limitMph
                    }
                    return if (language == AppLanguage.PERSIAN) {
                        val unitText = if (unit == DistanceUnit.METRIC) "کیلومتر" else "مایل"
                        "دوربین سرعت در پیش است، سرعت مجاز $shownLimit $unitText بر ساعت."
                    } else {
                        val unitText = if (unit == DistanceUnit.METRIC) "kilometers" else "miles"
                        "Speed camera ahead, speed limit $shownLimit $unitText per hour."
                    }
                }
                return if (language == AppLanguage.PERSIAN) "دوربین سرعت در پیش است." else "Speed camera ahead."
            }
            if (alert.kind == RoadAlertKind.RED_LIGHT_CAMERA) {
                return if (language == AppLanguage.PERSIAN) "دوربین چراغ قرمز در پیش است." else "Red light camera ahead."
            }
            return if (language == AppLanguage.PERSIAN) {
                alert.titleFa.ifBlank { alert.titleEn }
            } else {
                alert.titleEn
            }
        }

        fun formatArrivalPrompt(type: ManeuverType, destinationName: String, language: AppLanguage): String {
            return if (language == AppLanguage.PERSIAN) {
                when (type) {
                    ManeuverType.ARRIVE_RIGHT -> "به مقصد رسیدید. مقصد سمت راست شماست."
                    ManeuverType.ARRIVE_LEFT -> "به مقصد رسیدید. مقصد سمت چپ شماست."
                    else -> if (destinationName.isNotBlank()) "به $destinationName رسیدید." else "به مقصد رسیدید."
                }
            } else {
                when (type) {
                    ManeuverType.ARRIVE_RIGHT -> "You have arrived. Your destination is on the right."
                    ManeuverType.ARRIVE_LEFT -> "You have arrived. Your destination is on the left."
                    else -> if (destinationName.isNotBlank()) "You have arrived at $destinationName." else "You have arrived at your destination."
                }
            }
        }

        /**
         * Formats the starting departure instruction, e.g.:
         * EN: "Head east on East 22nd Avenue, then turn right onto Hilyard Street."
         * FA: "به سمت شرق در East 22nd Avenue حرکت کنید، سپس به راست بپیچید و وارد Hilyard Street شوید."
         */
        fun formatStartInstruction(route: OfflineRoute, language: AppLanguage): String {
            val firstManeuver = route.maneuvers.firstOrNull() ?: return ""
            val nextManeuver = route.maneuvers.getOrNull(1)
            val startRoad = firstManeuver.streetNames.firstOrNull().orEmpty()
            val startBearing = computeInitialRouteBearing(route.shape)
            val cardinal = startBearing?.let { cardinalDirectionLabel(it, language) }

            val departClause = if (language == AppLanguage.PERSIAN) {
                when {
                    cardinal != null && startRoad.isNotBlank() -> "به سمت $cardinal در $startRoad حرکت کنید"
                    cardinal != null -> "به سمت $cardinal حرکت کنید"
                    startRoad.isNotBlank() -> "در $startRoad حرکت کنید"
                    else -> "مسیر را شروع کنید"
                }
            } else {
                when {
                    cardinal != null && startRoad.isNotBlank() -> "Head $cardinal on $startRoad"
                    cardinal != null -> "Head $cardinal"
                    startRoad.isNotBlank() -> "Head along $startRoad"
                    else -> "Start on the route"
                }
            }

            val thenClause = if (nextManeuver != null &&
                nextManeuver.type != ManeuverType.DEPART &&
                nextManeuver.type != ManeuverType.ARRIVE &&
                nextManeuver.type != ManeuverType.ARRIVE_LEFT &&
                nextManeuver.type != ManeuverType.ARRIVE_RIGHT
            ) {
                val nextSentence = formatSpokenInstruction(
                    maneuver = nextManeuver,
                    distanceMeters = 0.0,
                    unit = DistanceUnit.IMPERIAL,
                    language = language,
                    includeLaneGuidance = false
                ).trimEnd('.')
                if (language == AppLanguage.PERSIAN) {
                    "سپس $nextSentence"
                } else {
                    "then ${nextSentence.replaceFirstChar { it.lowercase() }}"
                }
            } else {
                null
            }

            return if (!thenClause.isNullOrBlank()) {
                if (language == AppLanguage.PERSIAN) "$departClause، $thenClause." else "$departClause, $thenClause."
            } else {
                "$departClause."
            }
        }

        private fun computeInitialRouteBearing(shape: List<LatLon>): Double? {
            if (shape.size < 2) return null
            val start = shape.first()
            val target = shape.drop(1).firstOrNull { pt ->
                com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
                    start.lat, start.lon, pt.lat, pt.lon
                ) >= 12.0
            } ?: shape[1]
            return ValhallaResponseParser.bearingDegrees(start, target)
        }

        private fun cardinalDirectionLabel(bearingDeg: Double, language: AppLanguage): String {
            val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
            val idx = (((normalized + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)
            return if (language == AppLanguage.PERSIAN) {
                when (idx) {
                    0 -> "شمال"
                    1 -> "شمال شرق"
                    2 -> "شرق"
                    3 -> "جنوب شرق"
                    4 -> "جنوب"
                    5 -> "جنوب غرب"
                    6 -> "غرب"
                    else -> "شمال غرب"
                }
            } else {
                when (idx) {
                    0 -> "north"
                    1 -> "northeast"
                    2 -> "east"
                    3 -> "southeast"
                    4 -> "south"
                    5 -> "southwest"
                    6 -> "west"
                    else -> "northwest"
                }
            }
        }

        fun isEarlyPrepareStage(stage: String): Boolean =
            stage == NavigationSessionController.STAGE_CITY_1MI ||
                stage == NavigationSessionController.STAGE_CITY_HALF_MI ||
                stage == NavigationSessionController.STAGE_HW_1MI ||
                stage == NavigationSessionController.STAGE_HW_HALF_MI ||
                stage == NavigationSessionController.STAGE_HW_2MI

        /**
         * Builds natural American English or natural spoken Persian turn-by-turn prompts,
         * integrating real lane guidance when available, and using directional "prepare to..."
         * phrasing for early warnings when lane data is absent (NEVER inventing lanes).
         */
        fun formatSpokenInstruction(
            maneuver: RouteManeuver,
            distanceMeters: Double,
            unit: DistanceUnit,
            language: AppLanguage,
            includeLaneGuidance: Boolean = true,
            announcementStage: String = ""
        ): String {
            val distPhrase = formatSpokenDistance(distanceMeters, unit, language)
            val lanePhrase = if (includeLaneGuidance) buildLaneVoiceClause(maneuver.lanes, language) else null
            val isEarlyPrepare = lanePhrase.isNullOrBlank() && isEarlyPrepareStage(announcementStage)
            val exitNum = maneuver.signpost?.primaryExitNumber
            val toward = maneuver.signpost?.exitTowards?.firstOrNull().orEmpty()
            val roadName = maneuver.streetNames.firstOrNull().orEmpty()

            return if (language == AppLanguage.PERSIAN) {
                buildPersianInstruction(
                    type = maneuver.type,
                    distPhrase = distPhrase,
                    lanePhrase = lanePhrase,
                    isEarlyPrepare = isEarlyPrepare,
                    exitNum = exitNum,
                    toward = toward,
                    roadName = roadName,
                    roundaboutExit = maneuver.roundaboutExitCount
                )
            } else {
                buildEnglishInstruction(
                    type = maneuver.type,
                    distPhrase = distPhrase,
                    lanePhrase = lanePhrase,
                    isEarlyPrepare = isEarlyPrepare,
                    exitNum = exitNum,
                    toward = toward,
                    roadName = roadName,
                    roundaboutExit = maneuver.roundaboutExitCount
                )
            }
        }

        private fun buildEnglishInstruction(
            type: ManeuverType,
            distPhrase: String,
            lanePhrase: String?,
            isEarlyPrepare: Boolean,
            exitNum: String?,
            toward: String,
            roadName: String,
            roundaboutExit: Int?
        ): String {
            val prefix = if (distPhrase.isNotEmpty()) "In $distPhrase, " else ""

            val core = when (type) {
                ManeuverType.LEFT -> buildOnto("turn left", roadName)
                ManeuverType.SLIGHT_LEFT -> buildOnto("bear left", roadName)
                ManeuverType.SHARP_LEFT -> buildOnto("make a sharp left", roadName)
                ManeuverType.RIGHT -> buildOnto("turn right", roadName)
                ManeuverType.SLIGHT_RIGHT -> buildOnto("bear right", roadName)
                ManeuverType.SHARP_RIGHT -> buildOnto("make a sharp right", roadName)
                ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                    if (roadName.isNotBlank()) "make a U-turn onto $roadName" else "make a U-turn"
                ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                    val base = if (!exitNum.isNullOrBlank()) "take exit $exitNum" else "take the exit"
                    when {
                        toward.isNotBlank() -> "$base toward $toward"
                        roadName.isNotBlank() -> "$base onto $roadName"
                        else -> base
                    }
                }
                ManeuverType.RAMP_RIGHT -> {
                    val base = "take the ramp on the right"
                    if (roadName.isNotBlank()) "$base onto $roadName" else base
                }
                ManeuverType.RAMP_LEFT -> {
                    val base = "take the ramp on the left"
                    if (roadName.isNotBlank()) "$base onto $roadName" else base
                }
                ManeuverType.STAY_LEFT -> {
                    val base = "keep left at the fork"
                    if (toward.isNotBlank()) "$base toward $toward"
                    else if (roadName.isNotBlank()) "$base onto $roadName" else base
                }
                ManeuverType.STAY_RIGHT -> {
                    val base = "keep right at the fork"
                    if (toward.isNotBlank()) "$base toward $toward"
                    else if (roadName.isNotBlank()) "$base onto $roadName" else base
                }
                ManeuverType.MERGE -> if (roadName.isNotBlank()) "merge onto $roadName" else "merge"
                ManeuverType.ROUNDABOUT -> {
                    val ordinal = englishOrdinal(roundaboutExit ?: 1)
                    val base = "at the roundabout, take the $ordinal exit"
                    if (roadName.isNotBlank()) "$base onto $roadName" else base
                }
                ManeuverType.ARRIVE_RIGHT -> return if (distPhrase.isNotEmpty()) {
                    "In $distPhrase, your destination will be on the right."
                } else {
                    "Your destination is on the right."
                }
                ManeuverType.ARRIVE_LEFT -> return if (distPhrase.isNotEmpty()) {
                    "In $distPhrase, your destination will be on the left."
                } else {
                    "Your destination is on the left."
                }
                ManeuverType.ARRIVE -> return if (distPhrase.isNotEmpty()) {
                    "In $distPhrase, you will arrive at your destination."
                } else {
                    "You have arrived at your destination."
                }
                else -> if (roadName.isNotBlank()) "continue on $roadName" else "continue straight"
            }

            val sentence = when {
                !lanePhrase.isNullOrBlank() -> "${prefix}use $lanePhrase to $core."
                isEarlyPrepare && type != ManeuverType.STRAIGHT && type != ManeuverType.DEPART -> {
                    if (type == ManeuverType.ROUNDABOUT) {
                        val ordinal = englishOrdinal(roundaboutExit ?: 1)
                        val exitTarget = if (roadName.isNotBlank()) " onto $roadName" else ""
                        "${prefix}prepare to take the $ordinal exit at the roundabout$exitTarget."
                    } else {
                        "${prefix}prepare to $core."
                    }
                }
                else -> "${prefix}$core."
            }
            return sentence.replaceFirstChar { it.uppercase() }
        }

        private fun buildOnto(verb: String, roadName: String): String =
            if (roadName.isNotBlank()) "$verb onto $roadName" else verb

        private fun buildPersianInstruction(
            type: ManeuverType,
            distPhrase: String,
            lanePhrase: String?,
            isEarlyPrepare: Boolean,
            exitNum: String?,
            toward: String,
            roadName: String,
            roundaboutExit: Int?
        ): String {
            val prefix = if (distPhrase.isNotEmpty()) "در $distPhrase، " else ""

            // Natural spoken Persian weaving lane advice directly into the verb phrase
            if (!lanePhrase.isNullOrBlank()) {
                val integrated = when (type) {
                    ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                        val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                        when {
                            toward.isNotBlank() -> "${prefix}از $lanePhrase وارد $exitPart به سمت $toward شوید."
                            roadName.isNotBlank() -> "${prefix}از $lanePhrase وارد $exitPart و $roadName شوید."
                            else -> "${prefix}از $lanePhrase وارد $exitPart شوید."
                        }
                    }
                    ManeuverType.LEFT ->
                        if (roadName.isNotBlank()) "${prefix}از $lanePhrase به چپ بپیچید و وارد $roadName شوید."
                        else "${prefix}از $lanePhrase به چپ بپیچید."
                    ManeuverType.RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}از $lanePhrase به راست بپیچید و وارد $roadName شوید."
                        else "${prefix}از $lanePhrase به راست بپیچید."
                    ManeuverType.STAY_LEFT ->
                        if (toward.isNotBlank()) "${prefix}از $lanePhrase سمت چپ به سمت $toward بمانید."
                        else "${prefix}از $lanePhrase سمت چپ بمانید."
                    ManeuverType.STAY_RIGHT ->
                        if (toward.isNotBlank()) "${prefix}از $lanePhrase سمت راست به سمت $toward بمانید."
                        else "${prefix}از $lanePhrase سمت راست بمانید."
                    ManeuverType.MERGE ->
                        if (roadName.isNotBlank()) "${prefix}از $lanePhrase وارد بزرگراه $roadName شوید."
                        else "${prefix}از $lanePhrase وارد مسیر اصلی شوید."
                    else -> null
                }
                if (integrated != null) return integrated
            }

            // Early warning WITHOUT lane data: never invent lanes — advise only the direction to prepare for
            if (isEarlyPrepare) {
                val prepareSentence = when (type) {
                    ManeuverType.RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}برای پیچیدن به راست به سمت $roadName آماده شوید."
                        else "${prefix}برای پیچیدن به راست آماده شوید."
                    ManeuverType.LEFT ->
                        if (roadName.isNotBlank()) "${prefix}برای پیچیدن به چپ به سمت $roadName آماده شوید."
                        else "${prefix}برای پیچیدن به چپ آماده شوید."
                    ManeuverType.SLIGHT_RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}برای متمایل شدن به راست به سمت $roadName آماده شوید."
                        else "${prefix}برای متمایل شدن به راست آماده شوید."
                    ManeuverType.SLIGHT_LEFT ->
                        if (roadName.isNotBlank()) "${prefix}برای متمایل شدن به چپ به سمت $roadName آماده شوید."
                        else "${prefix}برای متمایل شدن به چپ آماده شوید."
                    ManeuverType.SHARP_RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}برای پیچ تند به راست به سمت $roadName آماده شوید."
                        else "${prefix}برای پیچ تند به راست آماده شوید."
                    ManeuverType.SHARP_LEFT ->
                        if (roadName.isNotBlank()) "${prefix}برای پیچ تند به چپ به سمت $roadName آماده شوید."
                        else "${prefix}برای پیچ تند به چپ آماده شوید."
                    ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}برای دور زدن به سمت $roadName آماده شوید."
                        else "${prefix}برای دور زدن آماده شوید."
                    ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                        val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                        when {
                            toward.isNotBlank() -> "${prefix}برای ورود به $exitPart به سمت $toward آماده شوید."
                            roadName.isNotBlank() -> "${prefix}برای ورود به $exitPart به سمت $roadName آماده شوید."
                            else -> "${prefix}برای ورود به $exitPart آماده شوید."
                        }
                    }
                    ManeuverType.RAMP_RIGHT ->
                        if (roadName.isNotBlank()) "${prefix}برای ورود به رمپ سمت راست به سمت $roadName آماده شوید."
                        else "${prefix}برای ورود به رمپ سمت راست آماده شوید."
                    ManeuverType.RAMP_LEFT ->
                        if (roadName.isNotBlank()) "${prefix}برای ورود به رمپ سمت چپ به سمت $roadName آماده شوید."
                        else "${prefix}برای ورود به رمپ سمت چپ آماده شوید."
                    ManeuverType.STAY_RIGHT -> {
                        val target = toward.ifBlank { roadName }
                        if (target.isNotBlank()) "${prefix}برای ماندن در سمت راست به سمت $target آماده شوید."
                        else "${prefix}برای ماندن در سمت راست در دوراهی آماده شوید."
                    }
                    ManeuverType.STAY_LEFT -> {
                        val target = toward.ifBlank { roadName }
                        if (target.isNotBlank()) "${prefix}برای ماندن در سمت چپ به سمت $target آماده شوید."
                        else "${prefix}برای ماندن در سمت چپ در دوراهی آماده شوید."
                    }
                    ManeuverType.MERGE ->
                        if (roadName.isNotBlank()) "${prefix}برای ورود به $roadName آماده شوید."
                        else "${prefix}برای ورود به مسیر اصلی آماده شوید."
                    else -> null
                }
                if (prepareSentence != null) return prepareSentence
            }

            val core = when (type) {
                ManeuverType.LEFT ->
                    if (roadName.isNotBlank()) "به چپ بپیچید و وارد $roadName شوید" else "به چپ بپیچید"
                ManeuverType.SLIGHT_LEFT ->
                    if (roadName.isNotBlank()) "کمی به چپ بروید و وارد $roadName شوید" else "کمی به چپ متمایل شوید"
                ManeuverType.SHARP_LEFT ->
                    if (roadName.isNotBlank()) "پیچ تند به چپ بگیرید و وارد $roadName شوید" else "پیچ تند به چپ بگیرید"
                ManeuverType.RIGHT ->
                    if (roadName.isNotBlank()) "به راست بپیچید و وارد $roadName شوید" else "به راست بپیچید"
                ManeuverType.SLIGHT_RIGHT ->
                    if (roadName.isNotBlank()) "کمی به راست بروید و وارد $roadName شوید" else "کمی به راست متمایل شوید"
                ManeuverType.SHARP_RIGHT ->
                    if (roadName.isNotBlank()) "پیچ تند به راست بگیرید و وارد $roadName شوید" else "پیچ تند به راست بگیرید"
                ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                    if (roadName.isNotBlank()) "دور بزنید و وارد $roadName شوید" else "دور بزنید"
                ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                    val exitPart = if (!exitNum.isNullOrBlank()) "از خروجی $exitNum" else "از خروجی"
                    when {
                        toward.isNotBlank() -> "$exitPart به سمت $toward خارج شوید"
                        roadName.isNotBlank() -> "$exitPart وارد $roadName شوید"
                        else -> "$exitPart خارج شوید"
                    }
                }
                ManeuverType.RAMP_RIGHT ->
                    if (roadName.isNotBlank()) "از رمپ سمت راست وارد $roadName شوید" else "وارد رمپ سمت راست شوید"
                ManeuverType.RAMP_LEFT ->
                    if (roadName.isNotBlank()) "از رمپ سمت چپ وارد $roadName شوید" else "وارد رمپ سمت چپ شوید"
                ManeuverType.STAY_LEFT -> {
                    val base = "در دوراهی، سمت چپ بمانید"
                    if (toward.isNotBlank()) "$base به سمت $toward"
                    else if (roadName.isNotBlank()) "$base به سمت $roadName" else base
                }
                ManeuverType.STAY_RIGHT -> {
                    val base = "در دوراهی، سمت راست بمانید"
                    if (toward.isNotBlank()) "$base به سمت $toward"
                    else if (roadName.isNotBlank()) "$base به سمت $roadName" else base
                }
                ManeuverType.MERGE ->
                    if (roadName.isNotBlank()) "وارد $roadName شوید" else "وارد مسیر اصلی شوید"
                ManeuverType.ROUNDABOUT -> {
                    val ord = persianOrdinal(roundaboutExit ?: 1)
                    val base = "در میدان، از خروجی $ord خارج شوید"
                    if (roadName.isNotBlank()) "$base و وارد $roadName شوید" else base
                }
                ManeuverType.ARRIVE_RIGHT -> return if (distPhrase.isNotEmpty()) {
                    "در $distPhrase، مقصد سمت راست شماست."
                } else {
                    "مقصد سمت راست شماست."
                }
                ManeuverType.ARRIVE_LEFT -> return if (distPhrase.isNotEmpty()) {
                    "در $distPhrase، مقصد سمت چپ شماست."
                } else {
                    "مقصد سمت چپ شماست."
                }
                ManeuverType.ARRIVE -> return if (distPhrase.isNotEmpty()) {
                    "در $distPhrase، به مقصد خواهید رسید."
                } else {
                    "به مقصد رسیدید."
                }
                else -> if (roadName.isNotBlank()) "در $roadName مستقیم ادامه دهید" else "مستقیم ادامه دهید"
            }

            return if (!lanePhrase.isNullOrBlank()) {
                "${prefix}از $lanePhrase استفاده کنید؛ $core."
            } else {
                "${prefix}$core."
            }
        }

        private fun englishOrdinal(n: Int): String = when (n) {
            1 -> "first"
            2 -> "second"
            3 -> "third"
            4 -> "fourth"
            5 -> "fifth"
            else -> "${n}th"
        }

        private fun persianOrdinal(n: Int): String = when (n) {
            1 -> "اول"
            2 -> "دوم"
            3 -> "سوم"
            4 -> "چهارم"
            5 -> "پنجم"
            else -> "${n}م"
        }

        fun buildLaneVoiceClause(lanes: List<LaneInfo>, language: AppLanguage): String? {
            if (lanes.size < 2) return null
            val activeIndices = lanes.mapIndexedNotNull { idx, lane ->
                if (lane.highlightTier == LaneHighlightTier.ACTIVE) idx else null
            }.ifEmpty {
                lanes.mapIndexedNotNull { idx, lane -> if (lane.isRecommended) idx else null }
            }
            if (activeIndices.isEmpty()) return null
            if (activeIndices.size == lanes.size) {
                return if (language == AppLanguage.PERSIAN) "هر خطی" else "any lane"
            }

            val count = activeIndices.size
            val fromRight = activeIndices.last() == lanes.lastIndex
            val fromLeft = activeIndices.first() == 0

            return if (language == AppLanguage.PERSIAN) {
                val countWord = when (count) {
                    1 -> "خط"
                    2 -> "دو خط"
                    3 -> "سه خط"
                    else -> "$count خط"
                }
                when {
                    fromRight -> "$countWord سمت راست"
                    fromLeft -> "$countWord سمت چپ"
                    else -> "$countWord میانی"
                }
            } else {
                val countWord = when (count) {
                    1 -> "lane"
                    2 -> "two lanes"
                    3 -> "three lanes"
                    else -> "$count lanes"
                }
                when {
                    fromRight && count == 1 -> "the right lane"
                    fromRight -> "the right $countWord"
                    fromLeft && count == 1 -> "the left lane"
                    fromLeft -> "the left $countWord"
                    count == 1 -> "the middle lane"
                    else -> "the middle $countWord"
                }
            }
        }

        internal fun formatSpokenDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String {
            if (meters < 75.0) return ""
            return when (unit) {
                DistanceUnit.IMPERIAL -> {
                    val miles = meters / 1609.344
                    val feet = meters * 3.28084
                    if (language == AppLanguage.PERSIAN) {
                        when {
                            miles in 1.80..2.25 -> "دو مایلی"
                            miles in 0.88..1.15 -> "یک مایلی"
                            miles in 0.44..0.58 -> "نیم مایلی"
                            miles in 0.21..0.31 -> "یک‌چهارم مایلی"
                            miles >= 0.31 -> "${String.format(Locale.US, "%.1f", miles).removeSuffix(".0")} مایلی"
                            feet in 850.0..1150.0 -> "هزار فوتی"
                            else -> "${((feet / 100.0).roundToInt() * 100).coerceAtLeast(100)} فوتی"
                        }
                    } else {
                        when {
                            miles in 1.80..2.25 -> "2 miles"
                            miles in 0.88..1.15 -> "1 mile"
                            miles in 0.44..0.58 -> "half a mile"
                            miles in 0.21..0.31 -> "a quarter mile"
                            miles >= 0.31 -> {
                                val formatted = String.format(Locale.US, "%.1f", miles).removeSuffix(".0")
                                if (formatted == "1") "1 mile" else "$formatted miles"
                            }
                            feet in 850.0..1150.0 -> "1,000 feet"
                            else -> "${((feet / 100.0).roundToInt() * 100).coerceAtLeast(100)} feet"
                        }
                    }
                }
                DistanceUnit.METRIC -> {
                    val km = meters / 1000.0
                    if (language == AppLanguage.PERSIAN) {
                        when {
                            meters in 2950.0..3250.0 -> "3 کیلومتری"
                            meters in 1380.0..1650.0 -> "1.5 کیلومتری"
                            meters in 720.0..880.0 -> "800 متری"
                            meters in 350.0..460.0 -> "400 متری"
                            meters in 250.0..349.0 -> "300 متری"
                            km >= 1.0 -> "${String.format(Locale.US, "%.1f", km).removeSuffix(".0")} کیلومتری"
                            else -> "${((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)} متری"
                        }
                    } else {
                        when {
                            meters in 2950.0..3250.0 -> "3 kilometers"
                            meters in 1380.0..1650.0 -> "1.5 kilometers"
                            meters in 720.0..880.0 -> "800 meters"
                            meters in 350.0..460.0 -> "400 meters"
                            meters in 250.0..349.0 -> "300 meters"
                            km >= 1.0 -> {
                                val formatted = String.format(Locale.US, "%.1f", km).removeSuffix(".0")
                                if (formatted == "1") "1 kilometer" else "$formatted kilometers"
                            }
                            else -> "${((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)} meters"
                        }
                    }
                }
            }
        }
    }
}
