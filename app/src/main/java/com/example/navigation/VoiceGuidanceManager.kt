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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var activeFocusRequest: AudioFocusRequest? = null

    private val _isPersianVoiceAvailable = MutableStateFlow(true)
    val isPersianVoiceAvailable: StateFlow<Boolean> = _isPersianVoiceAvailable.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    private var currentLanguage: AppLanguage = AppLanguage.ENGLISH
    private var voiceEnabled: Boolean = true
    private var lastSpokenKey: String = ""
    private var lastSpokenTimeMs: Long = 0L
    private var lastOverspeedBeepMs: Long = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestDuckingFocus()
                }

                override fun onDone(utteranceId: String?) {
                    abandonDuckingFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    abandonDuckingFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    abandonDuckingFocus()
                }
            })
            checkPersianAvailability()
            applyLanguage(currentLanguage)
        }
    }

    private fun requestDuckingFocus() {
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
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonDuckingFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    fun checkPersianAvailability(): Boolean {
        val engine = tts ?: return false
        val faLocale = Locale.forLanguageTag("fa-IR")
        val result = engine.isLanguageAvailable(faLocale)
        val hasVoice = result >= TextToSpeech.LANG_AVAILABLE ||
            (engine.voices?.any { it.locale.language.equals("fa", ignoreCase = true) } == true)
        _isPersianVoiceAvailable.value = hasVoice
        return hasVoice
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        if (isInitialized) {
            applyLanguage(language)
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled) {
            tts?.stop()
            abandonDuckingFocus()
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        val engine = tts ?: return
        if (language == AppLanguage.PERSIAN && checkPersianAvailability()) {
            engine.language = Locale.forLanguageTag("fa-IR")
        } else {
            engine.language = Locale.US
        }
    }

    private fun resolveEffectiveLanguage(requested: AppLanguage): AppLanguage {
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
            includeLaneGuidance = includeLaneGuidance
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

    fun speakAlert(alert: AheadAlert, language: AppLanguage): String {
        val effectiveLanguage = resolveEffectiveLanguage(language)
        val text = formatAlertAnnouncement(alert, effectiveLanguage)
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

    fun playOverspeedChime() {
        val now = System.currentTimeMillis()
        if (now - lastOverspeedBeepMs < 6000L) return
        lastOverspeedBeepMs = now
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        }
    }

    private fun speakRaw(text: String, effectiveLanguage: AppLanguage) {
        val engine = tts ?: return
        applyLanguage(effectiveLanguage)
        requestDuckingFocus()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rahyab_${System.currentTimeMillis()}")
    }

    fun resetDeduplication() {
        lastSpokenKey = ""
        lastSpokenTimeMs = 0L
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
            abandonDuckingFocus()
        }
        tts = null
    }

    companion object {
        fun formatReroutingPrompt(language: AppLanguage): String =
            if (language == AppLanguage.PERSIAN) "در حال مسیریابی مجدد." else "Rerouting."

        fun formatAlertAnnouncement(alert: AheadAlert, language: AppLanguage): String {
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

        /**
         * Builds natural American English or natural spoken Persian turn-by-turn prompts,
         * integrating real lane guidance when available (and omitting lane advice when absent).
         */
        fun formatSpokenInstruction(
            maneuver: RouteManeuver,
            distanceMeters: Double,
            unit: DistanceUnit,
            language: AppLanguage,
            includeLaneGuidance: Boolean = true
        ): String {
            val distPhrase = formatSpokenDistance(distanceMeters, unit, language)
            val lanePhrase = if (includeLaneGuidance) buildLaneVoiceClause(maneuver.lanes, language) else null
            val exitNum = maneuver.signpost?.primaryExitNumber
            val toward = maneuver.signpost?.exitTowards?.firstOrNull().orEmpty()
            val roadName = maneuver.streetNames.firstOrNull().orEmpty()

            return if (language == AppLanguage.PERSIAN) {
                buildPersianInstruction(
                    type = maneuver.type,
                    distPhrase = distPhrase,
                    lanePhrase = lanePhrase,
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

            val sentence = if (!lanePhrase.isNullOrBlank()) {
                "${prefix}use $lanePhrase to $core."
            } else {
                "${prefix}$core."
            }
            return sentence.replaceFirstChar { it.uppercase() }
        }

        private fun buildOnto(verb: String, roadName: String): String =
            if (roadName.isNotBlank()) "$verb onto $roadName" else verb

        private fun buildPersianInstruction(
            type: ManeuverType,
            distPhrase: String,
            lanePhrase: String?,
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
            val activeIndices = lanes.mapIndexedNotNull { idx, lane -> if (lane.isRecommended) idx else null }
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

        private fun formatSpokenDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String {
            if (meters < 75.0) return ""
            return when (unit) {
                DistanceUnit.IMPERIAL -> {
                    val miles = meters / 1609.344
                    val feet = meters * 3.28084
                    if (language == AppLanguage.PERSIAN) {
                        when {
                            miles in 1.75..2.3 -> "دو مایلی"
                            miles in 0.85..1.2 -> "یک مایلی"
                            miles in 0.42..0.60 -> "نیم مایلی"
                            miles in 0.20..0.33 -> "یک‌چهارم مایلی"
                            miles >= 0.8 -> "${String.format(Locale.US, "%.1f", miles).removeSuffix(".0")} مایلی"
                            feet in 850.0..1150.0 -> "هزار فوتی"
                            else -> "${((feet / 100.0).roundToInt() * 100).coerceAtLeast(100)} فوتی"
                        }
                    } else {
                        when {
                            miles in 1.75..2.3 -> "2 miles"
                            miles in 0.85..1.2 -> "1 mile"
                            miles in 0.42..0.60 -> "half a mile"
                            miles in 0.20..0.33 -> "a quarter mile"
                            miles >= 0.8 -> "${String.format(Locale.US, "%.1f", miles).removeSuffix(".0")} miles"
                            feet in 850.0..1150.0 -> "1,000 feet"
                            else -> "${((feet / 100.0).roundToInt() * 100).coerceAtLeast(100)} feet"
                        }
                    }
                }
                DistanceUnit.METRIC -> {
                    val km = meters / 1000.0
                    if (language == AppLanguage.PERSIAN) {
                        when {
                            km >= 1.0 -> "${String.format(Locale.US, "%.1f", km).removeSuffix(".0")} کیلومتری"
                            else -> "${((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)} متری"
                        }
                    } else {
                        when {
                            km >= 1.0 -> "${String.format(Locale.US, "%.1f", km).removeSuffix(".0")} kilometers"
                            else -> "${((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)} meters"
                        }
                    }
                }
            }
        }
    }
}
