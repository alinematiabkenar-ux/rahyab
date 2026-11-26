package com.example.navigation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.util.CrashLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bilingual Android Text-to-Speech voice guidance coordinator for American English (en-US)
 * and Persian (fa-IR). Delegates engine binding/focus to [VoiceTtsEngineSelector],
 * priority-aware sequencing & playback telemetry to [VoiceSpeechQueue], and phrasing to [VoicePhraseBuilder].
 */
class VoiceGuidanceManager(
    context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    data class SpeechSegment(val text: String, val isEnglish: Boolean)

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

    private var currentLanguage: AppLanguage = AppLanguage.ENGLISH
    private var useEnglishVoiceInPersianMode: Boolean = false
    private var voiceEnabled: Boolean = true
    private var lastSpokenKey: String = ""
    private var lastSpokenTimeMs: Long = 0L
    private var lastOverspeedBeepMs: Long = 0L
    private var lastCameraChimeMs: Long = 0L
    private val laneChangeTonePlayer = LaneChangeTonePlayer()
    private val turnFocusAudioPlayer = TurnFocusAudioPlayer(appContext)

    private lateinit var speechQueue: VoiceSpeechQueue
    private val engineSelector: VoiceTtsEngineSelector = VoiceTtsEngineSelector(
        appContext = appContext,
        onUtteranceStarted = {
            engineSelector.requestDuckingFocus()
            speechQueue.handleUtteranceStarted()
        },
        onUtteranceFinished = { id -> speechQueue.handleUtteranceFinished(id) }
    )

    val isPersianVoiceAvailable: StateFlow<Boolean> = engineSelector.isPersianVoiceAvailable
    val ttsDiagnostics: StateFlow<TtsDiagnosticsInfo> = engineSelector.ttsDiagnostics
    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    init {
        speechQueue = VoiceSpeechQueue(appContext, engineSelector, { voiceEnabled }, clockMs)
        engineSelector.createBaseEngine(this)
    }

    fun setPlaybackSink(sink: SpeechPlaybackSink?) { speechQueue.playbackSink = sink }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                engineSelector.markBaseInitialized()
                engineSelector.applyLanguage(resolveEffectiveLanguage(currentLanguage))
            }.onFailure { CrashLogManager.logError(appContext, "TTS", "TextToSpeech onInit setup failed", it) }
        }
    }

    fun refreshTtsDiagnostics(): TtsDiagnosticsInfo = engineSelector.refreshTtsDiagnostics()

    fun checkPersianAvailability(): Boolean {
        val info = refreshTtsDiagnostics()
        return info.isPersianAvailable && info.isPersianInstalled
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        if (engineSelector.isInitialized) engineSelector.applyLanguage(resolveEffectiveLanguage(language))
    }

    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) {
        useEnglishVoiceInPersianMode = enabled
        if (engineSelector.isInitialized) engineSelector.applyLanguage(resolveEffectiveLanguage(currentLanguage))
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled) {
            stopSpeaking()
            engineSelector.abandonDuckingFocus()
        }
    }

    fun isSpeakingOrQueued(): Boolean = speechQueue.isSpeakingOrQueued()

    private fun resolveEffectiveLanguage(requested: AppLanguage): AppLanguage {
        if (requested == AppLanguage.PERSIAN && useEnglishVoiceInPersianMode) return AppLanguage.ENGLISH
        return if (requested == AppLanguage.PERSIAN && isPersianVoiceAvailable.value) AppLanguage.PERSIAN else AppLanguage.ENGLISH
    }

    fun speakManeuver(
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        announcementStage: String,
        includeLaneGuidance: Boolean = true,
        nextManeuver: RouteManeuver? = null,
        laneDecision: LaneGuidanceDecision? = null,
        routeId: String? = null
    ): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatSpokenInstruction(
            maneuver, distanceMeters, unit, effectiveLanguage, includeLaneGuidance, announcementStage, nextManeuver, laneDecision
        )
        _lastSpokenText.value = utterance
        if (!voiceEnabled || !engineSelector.isInitialized) return utterance

        val key = "${routeId.orEmpty()}_${maneuver.index}_$announcementStage"
        val now = clockMs()
        if (key == lastSpokenKey && now - lastSpokenTimeMs < 7000L) return utterance

        val priority = if (VoicePhraseBuilder.isFinalTurnStage(announcementStage) ||
            announcementStage == NavigationSessionController.STAGE_ARRIVED
        ) SpeechPriority.IMMINENT_MANEUVER else SpeechPriority.PREPARATION_MANEUVER

        val spoken = speechQueue.speakRaw(
            text = utterance,
            effectiveLanguage = effectiveLanguage,
            priority = priority,
            context = SpeechContextTag(routeId = routeId, maneuverIndex = maneuver.index, stage = announcementStage)
        )
        if (spoken) {
            lastSpokenKey = key
            lastSpokenTimeMs = now
        }
        return utterance
    }

    fun speakStartInstruction(route: OfflineRoute, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatStartInstruction(route, effectiveLanguage)
        if (utterance.isBlank()) return ""
        _lastSpokenText.value = utterance
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(
                utterance, effectiveLanguage, SpeechPriority.PREPARATION_MANEUVER,
                SpeechContextTag(routeId = route.id, maneuverIndex = 0, stage = "start")
            )
            lastSpokenKey = "route_start_${route.id}"
            lastSpokenTimeMs = clockMs()
        }
        return utterance
    }

    fun speakRerouting(language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatReroutingPrompt(effectiveLanguage)
        _lastSpokenText.value = utterance
        stopSpeaking()
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(utterance, effectiveLanguage, SpeechPriority.REROUTE, SpeechContextTag(stage = "rerouting"))
        }
        return utterance
    }

    fun speakArrival(maneuverType: ManeuverType, destinationName: String, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatArrivalPrompt(maneuverType, destinationName, effectiveLanguage)
        _lastSpokenText.value = utterance
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(utterance, effectiveLanguage, SpeechPriority.IMMINENT_MANEUVER)
        }
        return utterance
    }

    fun speakCustomText(text: String, language: AppLanguage): String {
        if (text.isBlank()) return ""
        val effectiveLanguage = resolveEffectiveLanguage(language)
        _lastSpokenText.value = text
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(text, effectiveLanguage, SpeechPriority.PREPARATION_MANEUVER)
        }
        return text
    }

    fun speakAfterCurrentSpeech(
        text: String,
        language: AppLanguage,
        context: SpeechContextTag = SpeechContextTag(),
        isStillValid: () -> Boolean = { true }
    ): String {
        if (text.isBlank() || !voiceEnabled || !engineSelector.isInitialized) return text
        val effectiveLanguage = resolveEffectiveLanguage(language)
        if (isSpeakingOrQueued()) {
            speechQueue.enqueueAfterCurrentSpeech(context = context, isStillValid = isStillValid) {
                if (voiceEnabled && engineSelector.isInitialized && isStillValid()) {
                    speechQueue.speakRaw(text, effectiveLanguage, SpeechPriority.LOW_PRIORITY_ALERT, context)
                }
            }
        } else if (isStillValid()) {
            speechQueue.speakRaw(text, effectiveLanguage, SpeechPriority.LOW_PRIORITY_ALERT, context)
        }
        return text
    }

    fun cancelStaleForManeuverOrReroute(activeRouteId: String?, activeManeuverIndex: Int?) {
        laneChangeTonePlayer.cancelPending()
        turnFocusAudioPlayer.cancelPending()
        speechQueue.cancelStaleForContext(activeRouteId, activeManeuverIndex)
    }

    fun stopSpeaking() {
        laneChangeTonePlayer.cancelPending()
        turnFocusAudioPlayer.cancelPending()
        speechQueue.stopAllSpeech(clearPendingActions = true)
    }

    fun repeatLastInstruction(language: AppLanguage) {
        val text = _lastSpokenText.value
        if (text.isBlank() || !engineSelector.isInitialized) return
        speechQueue.speakRaw(text, resolveEffectiveLanguage(language), SpeechPriority.PREPARATION_MANEUVER)
    }

    fun speakAlert(alert: AheadAlert, language: AppLanguage, unit: DistanceUnit = DistanceUnit.IMPERIAL): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage, unit)
        val priority = when (alert.kind) {
            RoadAlertKind.SPEED_CAMERA, RoadAlertKind.RED_LIGHT_CAMERA, RoadAlertKind.RAILROAD_CROSSING,
            RoadAlertKind.USER_CRASH, RoadAlertKind.USER_ROAD_CLOSURE -> SpeechPriority.CRITICAL_ALERT
            else -> SpeechPriority.LOW_PRIORITY_ALERT
        }
        if (voiceEnabled && engineSelector.isInitialized) speechQueue.speakRaw(text, effectiveLanguage, priority)
        return text
    }

    fun updateUserMotionForAlerts(lat: Double, lon: Double, headingDegrees: Float) =
        speechQueue.updateUserMotionForAlerts(lat, lon, headingDegrees)

    fun speakQueuedAlert(alert: AheadAlert, language: AppLanguage, unit: DistanceUnit = DistanceUnit.IMPERIAL): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage, unit)
        if (!voiceEnabled || !engineSelector.isInitialized || text.isBlank()) return text
        if (isSpeakingOrQueued()) {
            speechQueue.offerLowPriorityAlert(text, effectiveLanguage, alert.lat, alert.lon)
        } else {
            speechQueue.speakRaw(text, effectiveLanguage, SpeechPriority.LOW_PRIORITY_ALERT)
        }
        return text
    }

    fun speakWeatherAlert(title: String, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = if (effectiveLanguage == AppLanguage.PERSIAN) "هشدار هواشناسی شدید: $title" else "Severe weather alert: $title"
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(text, effectiveLanguage, SpeechPriority.LOW_PRIORITY_ALERT)
        }
        return text
    }

    fun speakTestSample(language: AppLanguage) {
        if (!engineSelector.isInitialized) return
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val sample = if (effectiveLanguage == AppLanguage.PERSIAN) {
            "در یک مایلی، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید."
        } else {
            "In 1 mile, use the right two lanes to take exit 194B toward Portland."
        }
        _lastSpokenText.value = sample
        speechQueue.speakRaw(sample, effectiveLanguage, SpeechPriority.PREPARATION_MANEUVER)
    }

    fun speakExplicitLanguageTest(targetLanguage: AppLanguage): Boolean {
        if (!engineSelector.isInitialized) return false
        val info = refreshTtsDiagnostics()
        if (targetLanguage == AppLanguage.PERSIAN && !info.isPersianAvailable) return false
        val sample = if (targetLanguage == AppLanguage.PERSIAN) {
            "آزمایش صدای فارسی رهیاب. در نیم مایلی، به راست بپیچید و وارد Main Street شوید."
        } else {
            "RahYab English voice test. In half a mile, use the right lane to turn right onto Main Street."
        }
        _lastSpokenText.value = sample
        speechQueue.speakRaw(sample, targetLanguage, SpeechPriority.PREPARATION_MANEUVER)
        return true
    }

    fun playOverspeedChime() {
        val now = clockMs()
        if (now - lastOverspeedBeepMs < 6000L) return
        lastOverspeedBeepMs = now
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).startTone(ToneGenerator.TONE_PROP_BEEP2, 220) }
    }

    fun playCameraAlertSound() {
        if (!voiceEnabled) return
        val now = clockMs()
        if (now - lastCameraChimeMs < 2000L) return
        lastCameraChimeMs = now
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 180) }
    }

    fun playLaneChangeCue(
        event: LaneChangeCueEvent,
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        routeId: String? = null,
        isStillValid: () -> Boolean = { true }
    ): String {
        val effectiveLang = resolveEffectiveLanguage(language)
        val ctx = SpeechContextTag(routeId = routeId, maneuverIndex = maneuver.index, stage = "lane_cue")
        if (event.kind == LaneChangeCueKind.LAST_CHANCE) {
            if (voiceEnabled && isStillValid()) {
                if (isSpeakingOrQueued()) {
                    speechQueue.enqueueAfterCurrentSpeech(ctx, isStillValid) { if (isStillValid()) laneChangeTonePlayer.playSingleBeep() }
                } else laneChangeTonePlayer.playSingleBeep()
            }
            return ""
        }
        val phrase = if (event.mergeWithHalfMile) {
            LaneChangeTonePlayer.appendNowToPrompt(
                formatSpokenInstruction(maneuver, distanceMeters, unit, effectiveLang, true, NavigationSessionController.STAGE_HW_HALF_MI),
                effectiveLang
            )
        } else LaneChangeTonePlayer.formatCuePhrase(event.targetSide, effectiveLang)
        val dispatch: () -> Unit = {
            if (voiceEnabled && isStillValid()) {
                if (event.mergeWithHalfMile && engineSelector.isInitialized && phrase.isNotBlank()) {
                    _lastSpokenText.value = phrase
                    speechQueue.speakRaw(phrase, effectiveLang, SpeechPriority.LANE_CUE, ctx)
                    speechQueue.enqueueAfterCurrentSpeech(ctx, isStillValid) { if (isStillValid()) laneChangeTonePlayer.playTripleBeep() }
                } else {
                    laneChangeTonePlayer.playTripleBeep {
                        if (voiceEnabled && engineSelector.isInitialized && phrase.isNotBlank() && isStillValid()) {
                            _lastSpokenText.value = phrase
                            speechQueue.speakRaw(phrase, effectiveLang, SpeechPriority.LANE_CUE, ctx)
                        }
                    }
                }
            }
        }
        if (voiceEnabled && isStillValid()) {
            if (isSpeakingOrQueued()) speechQueue.enqueueAfterCurrentSpeech(ctx, isStillValid, dispatch) else dispatch()
        }
        return phrase
    }

    /**
     * F2: Plays 3 short Turn Focus beeps (waiting <= 4 s for voice), then re-checks [isStillValid]
     * and resolves [phraseProvider] LIVE right before playback so "Get in the blue lane" ONLY speaks
     * when the same maneuver is still active and the active display actually shows the blue lane target.
     */
    fun playTurnFocusBeeps(
        language: AppLanguage,
        speakShortPhrase: Boolean,
        context: SpeechContextTag = SpeechContextTag(),
        isStillValid: () -> Boolean = { true },
        phraseProvider: (() -> String?)? = null,
        onAllBeepsFinished: () -> Unit
    ) {
        if (!voiceEnabled) {
            if (isStillValid()) onAllBeepsFinished()
            return
        }
        val effectiveLang = resolveEffectiveLanguage(language)
        turnFocusAudioPlayer.playTurnFocusBeeps(
            isVoiceSpeaking = { isSpeakingOrQueued() },
            isStillValid = isStillValid
        ) {
            if (!isStillValid()) return@playTurnFocusBeeps
            onAllBeepsFinished()
            if (speakShortPhrase && voiceEnabled && engineSelector.isInitialized && isStillValid()) {
                val phrase = phraseProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return@playTurnFocusBeeps
                _lastSpokenText.value = phrase
                speechQueue.speakRaw(phrase, effectiveLang, SpeechPriority.LANE_CUE, context)
            }
        }
    }

    fun cancelTurnFocusBeeps() = turnFocusAudioPlayer.cancelPending()
    fun resetDeduplication() { lastSpokenKey = ""; lastSpokenTimeMs = 0L }
    fun shutdown() { stopSpeaking(); engineSelector.shutdown() }

    companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        fun splitPersianAndEnglishSegments(text: String): List<SpeechSegment> = VoiceSpeechQueue.splitPersianAndEnglishSegments(text)
        fun formatReroutingPrompt(language: AppLanguage): String = VoicePhraseBuilder.formatReroutingPrompt(language)
        fun formatAlertAnnouncement(alert: AheadAlert, language: AppLanguage, unit: DistanceUnit = DistanceUnit.IMPERIAL): String = VoicePhraseBuilder.formatAlertAnnouncement(alert, language, unit)
        fun formatArrivalPrompt(type: ManeuverType, destinationName: String, language: AppLanguage): String = VoicePhraseBuilder.formatArrivalPrompt(type, destinationName, language)
        fun formatStartInstruction(route: OfflineRoute, language: AppLanguage): String = VoicePhraseBuilder.formatStartInstruction(route, language)
        fun isEarlyPrepareStage(stage: String): Boolean = VoicePhraseBuilder.isEarlyPrepareStage(stage)
        fun formatSpokenInstruction(
            maneuver: RouteManeuver, distanceMeters: Double, unit: DistanceUnit, language: AppLanguage,
            includeLaneGuidance: Boolean = true, announcementStage: String = "",
            nextManeuver: RouteManeuver? = null, laneDecision: LaneGuidanceDecision? = null
        ): String = VoicePhraseBuilder.formatSpokenInstruction(
            maneuver, distanceMeters, unit, language, includeLaneGuidance, announcementStage, nextManeuver, laneDecision
        )
        fun buildLaneVoiceClause(lanes: List<LaneInfo>, language: AppLanguage): String? = VoicePhraseBuilder.buildLaneVoiceClause(lanes, language)
        internal fun formatSpokenDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String = VoicePhraseBuilder.formatSpokenDistance(meters, unit, language)
    }
}
