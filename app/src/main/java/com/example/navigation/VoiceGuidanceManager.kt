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
 * Bilingual Android Text-to-Speech voice guidance coordinator for natural American English (en-US)
 * and natural spoken Persian (fa-IR). Delegates engine binding/focus to [VoiceTtsEngineSelector],
 * bilingual segment sequencing to [VoiceSpeechQueue], and phrasing to [VoicePhraseBuilder].
 */
class VoiceGuidanceManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

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
        onUtteranceStarted = { requestDuckingFocus() },
        onUtteranceFinished = { id -> speechQueue.handleUtteranceFinished(id) }
    )

    val isPersianVoiceAvailable: StateFlow<Boolean> = engineSelector.isPersianVoiceAvailable
    val ttsDiagnostics: StateFlow<TtsDiagnosticsInfo> = engineSelector.ttsDiagnostics

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    init {
        speechQueue = VoiceSpeechQueue(appContext, engineSelector) { voiceEnabled }
        engineSelector.createBaseEngine(this)
    }

    private fun requestDuckingFocus() {
        engineSelector.requestDuckingFocus()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                engineSelector.markBaseInitialized()
                engineSelector.applyLanguage(resolveEffectiveLanguage(currentLanguage))
            }.onFailure { err ->
                CrashLogManager.logError(appContext, "TTS", "TextToSpeech onInit setup failed", err)
            }
        }
    }

    fun refreshTtsDiagnostics(): TtsDiagnosticsInfo = engineSelector.refreshTtsDiagnostics()

    fun checkPersianAvailability(): Boolean {
        val info = refreshTtsDiagnostics()
        return info.isPersianAvailable && info.isPersianInstalled
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        if (engineSelector.isInitialized) {
            engineSelector.applyLanguage(resolveEffectiveLanguage(language))
        }
    }

    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) {
        useEnglishVoiceInPersianMode = enabled
        if (engineSelector.isInitialized) {
            engineSelector.applyLanguage(resolveEffectiveLanguage(currentLanguage))
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled) {
            speechQueue.stopAllSpeech()
            engineSelector.abandonDuckingFocus()
        }
    }

    fun isSpeakingOrQueued(): Boolean =
        engineSelector.isAnyEngineSpeaking() || speechQueue.hasPendingSegments()

    private fun resolveEffectiveLanguage(requested: AppLanguage): AppLanguage {
        if (requested == AppLanguage.PERSIAN && useEnglishVoiceInPersianMode) {
            return AppLanguage.ENGLISH
        }
        return if (requested == AppLanguage.PERSIAN && isPersianVoiceAvailable.value) {
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

        if (!voiceEnabled || !engineSelector.isInitialized) return utterance
        val key = "${maneuver.index}_$announcementStage"
        val now = System.currentTimeMillis()
        if (key == lastSpokenKey && now - lastSpokenTimeMs < 7000L) return utterance

        speechQueue.speakRaw(utterance, effectiveLanguage)
        lastSpokenKey = key
        lastSpokenTimeMs = now
        return utterance
    }

    fun speakStartInstruction(route: OfflineRoute, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatStartInstruction(route, effectiveLanguage)
        if (utterance.isBlank()) return ""
        _lastSpokenText.value = utterance
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(utterance, effectiveLanguage)
            lastSpokenKey = "route_start_${route.id}"
            lastSpokenTimeMs = System.currentTimeMillis()
        }
        return utterance
    }

    fun speakRerouting(language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatReroutingPrompt(effectiveLanguage)
        _lastSpokenText.value = utterance
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(utterance, effectiveLanguage)
        }
        return utterance
    }

    fun speakArrival(maneuverType: ManeuverType, destinationName: String, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val utterance = formatArrivalPrompt(maneuverType, destinationName, effectiveLanguage)
        _lastSpokenText.value = utterance
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(utterance, effectiveLanguage)
        }
        return utterance
    }

    fun speakCustomText(text: String, language: AppLanguage): String {
        if (text.isBlank()) return ""
        val effectiveLanguage = resolveEffectiveLanguage(language)
        _lastSpokenText.value = text
        if (voiceEnabled && engineSelector.isInitialized) speechQueue.speakRaw(text, effectiveLanguage)
        return text
    }

    fun speakAfterCurrentSpeech(text: String, language: AppLanguage): String {
        if (text.isBlank() || !voiceEnabled || !engineSelector.isInitialized) return text
        val effectiveLanguage = resolveEffectiveLanguage(language)
        if (isSpeakingOrQueued()) {
            speechQueue.enqueueAfterCurrentSpeech {
                if (voiceEnabled && engineSelector.isInitialized) speechQueue.speakRaw(text, effectiveLanguage)
            }
        } else {
            speechQueue.speakRaw(text, effectiveLanguage)
        }
        return text
    }

    fun stopSpeaking() {
        laneChangeTonePlayer.cancelPending()
        turnFocusAudioPlayer.cancelPending()
        speechQueue.stopAllSpeech()
    }

    fun repeatLastInstruction(language: AppLanguage) {
        val text = _lastSpokenText.value
        if (text.isBlank() || !engineSelector.isInitialized) return
        speechQueue.speakRaw(text, resolveEffectiveLanguage(language))
    }

    fun speakAlert(
        alert: AheadAlert,
        language: AppLanguage,
        unit: DistanceUnit = DistanceUnit.IMPERIAL
    ): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage, unit)
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(text, effectiveLanguage)
        }
        return text
    }

    fun updateUserMotionForAlerts(lat: Double, lon: Double, headingDegrees: Float) {
        speechQueue.updateUserMotionForAlerts(lat, lon, headingDegrees)
    }

    fun speakQueuedAlert(
        alert: AheadAlert,
        language: AppLanguage,
        unit: DistanceUnit = DistanceUnit.IMPERIAL
    ): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage, unit)
        if (!voiceEnabled || !engineSelector.isInitialized || text.isBlank()) return text
        if (engineSelector.isAnyEngineSpeaking() || speechQueue.hasPendingSegments()) {
            speechQueue.offerLowPriorityAlert(
                text = text,
                effectiveLanguage = effectiveLanguage,
                targetLat = alert.lat,
                targetLon = alert.lon
            )
        } else {
            speechQueue.speakRaw(text, effectiveLanguage)
        }
        return text
    }

    fun speakWeatherAlert(title: String, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = if (effectiveLanguage == AppLanguage.PERSIAN) {
            "هشدار هواشناسی شدید: $title"
        } else {
            "Severe weather alert: $title"
        }
        if (voiceEnabled && engineSelector.isInitialized) {
            speechQueue.speakRaw(text, effectiveLanguage)
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
        speechQueue.speakRaw(sample, effectiveLanguage)
    }

    fun speakExplicitLanguageTest(targetLanguage: AppLanguage): Boolean {
        if (!engineSelector.isInitialized) return false
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
        speechQueue.speakRaw(sample, targetLanguage)
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

    fun playLaneChangeCue(
        event: LaneChangeCueEvent,
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage
    ): String {
        val effectiveLang = resolveEffectiveLanguage(language)
        if (event.kind == LaneChangeCueKind.LAST_CHANCE) {
            if (voiceEnabled) {
                if (isSpeakingOrQueued()) speechQueue.enqueueAfterCurrentSpeech { laneChangeTonePlayer.playSingleBeep() }
                else laneChangeTonePlayer.playSingleBeep()
            }
            return ""
        }
        val phrase = if (event.mergeWithHalfMile) {
            LaneChangeTonePlayer.appendNowToPrompt(
                formatSpokenInstruction(maneuver, distanceMeters, unit, effectiveLang, true, NavigationSessionController.STAGE_HW_HALF_MI),
                effectiveLang
            )
        } else {
            LaneChangeTonePlayer.formatCuePhrase(event.targetSide, effectiveLang)
        }
        val dispatch: () -> Unit = {
            if (voiceEnabled) {
                if (event.mergeWithHalfMile && engineSelector.isInitialized && phrase.isNotBlank()) {
                    _lastSpokenText.value = phrase
                    speechQueue.speakRaw(phrase, effectiveLang)
                    speechQueue.enqueueAfterCurrentSpeech { laneChangeTonePlayer.playTripleBeep() }
                } else {
                    laneChangeTonePlayer.playTripleBeep {
                        if (voiceEnabled && engineSelector.isInitialized && phrase.isNotBlank()) {
                            _lastSpokenText.value = phrase; speechQueue.speakRaw(phrase, effectiveLang)
                        }
                    }
                }
            }
        }
        if (voiceEnabled) {
            if (isSpeakingOrQueued()) speechQueue.enqueueAfterCurrentSpeech(dispatch) else dispatch()
        }
        return phrase
    }

    /** F2: Plays 3 short Turn Focus beeps (waiting <= 4 s for voice), optionally speaks blue-lane cue, then calls [onAllBeepsFinished]. */
    fun playTurnFocusBeeps(language: AppLanguage, speakShortPhrase: Boolean, onAllBeepsFinished: () -> Unit) {
        if (!voiceEnabled) { onAllBeepsFinished(); return }
        val effectiveLang = resolveEffectiveLanguage(language)
        turnFocusAudioPlayer.playTurnFocusBeeps(isVoiceSpeaking = { isSpeakingOrQueued() }) {
            onAllBeepsFinished()
            if (speakShortPhrase && voiceEnabled && engineSelector.isInitialized) {
                val phrase = com.example.i18n.stringsFor(effectiveLang).turnFocusShortPhraseSpoken
                _lastSpokenText.value = phrase; speechQueue.speakRaw(phrase, effectiveLang)
            }
        }
    }

    fun cancelTurnFocusBeeps() = turnFocusAudioPlayer.cancelPending()
    fun resetDeduplication() { lastSpokenKey = ""; lastSpokenTimeMs = 0L }
    fun shutdown() { laneChangeTonePlayer.cancelPending(); turnFocusAudioPlayer.cancelPending(); speechQueue.stopAllSpeech(); engineSelector.shutdown() }

    companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        fun splitPersianAndEnglishSegments(text: String): List<SpeechSegment> = VoiceSpeechQueue.splitPersianAndEnglishSegments(text)
        fun formatReroutingPrompt(language: AppLanguage): String = VoicePhraseBuilder.formatReroutingPrompt(language)
        fun formatAlertAnnouncement(alert: AheadAlert, language: AppLanguage, unit: DistanceUnit = DistanceUnit.IMPERIAL): String = VoicePhraseBuilder.formatAlertAnnouncement(alert, language, unit)
        fun formatArrivalPrompt(type: ManeuverType, destinationName: String, language: AppLanguage): String = VoicePhraseBuilder.formatArrivalPrompt(type, destinationName, language)
        fun formatStartInstruction(route: OfflineRoute, language: AppLanguage): String = VoicePhraseBuilder.formatStartInstruction(route, language)
        fun isEarlyPrepareStage(stage: String): Boolean = VoicePhraseBuilder.isEarlyPrepareStage(stage)
        fun formatSpokenInstruction(maneuver: RouteManeuver, distanceMeters: Double, unit: DistanceUnit, language: AppLanguage, includeLaneGuidance: Boolean = true, announcementStage: String = ""): String =
            VoicePhraseBuilder.formatSpokenInstruction(maneuver, distanceMeters, unit, language, includeLaneGuidance, announcementStage)
        fun buildLaneVoiceClause(lanes: List<LaneInfo>, language: AppLanguage): String? = VoicePhraseBuilder.buildLaneVoiceClause(lanes, language)
        internal fun formatSpokenDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String = VoicePhraseBuilder.formatSpokenDistance(meters, unit, language)
    }
}
