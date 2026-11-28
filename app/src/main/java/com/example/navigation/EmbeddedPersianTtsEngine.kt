package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.example.util.CrashLogManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Generated PCM waveform from the embedded offline Persian TTS engine.
 */
data class SynthesizedPcmAudio(
    val samples: FloatArray,
    val sampleRate: Int
) {
    val durationMs: Long
        get() = if (sampleRate > 0) (samples.size * 1000L) / sampleRate else 0L
}

/**
 * Telemetry captured for each embedded Persian synthesis & playback run, used by
 * diagnostics and automated verification to confirm non-silent PCM generation and
 * full completion through the final audio frame on a background thread.
 */
data class EmbeddedSynthesisReport(
    val utteranceId: String,
    val rawText: String,
    val normalizedText: String,
    val workerThreadName: String,
    val sampleRate: Int,
    val sampleCount: Int,
    val durationMs: Long,
    val peakAmplitude: Float,
    val rmsAmplitude: Float,
    val completedToLastFrame: Boolean,
    val interrupted: Boolean
)

/**
 * Pluggable offline neural synthesis backend (production uses `sherpa-onnx` Khadijah Matcha-TTS).
 */
interface OfflinePersianSynthesisBackend {
    fun ensureInitialized(context: Context): Boolean
    fun synthesize(
        normalizedText: String,
        speed: Float,
        shouldContinue: () -> Boolean
    ): SynthesizedPcmAudio?
    fun release()
}

/**
 * Pluggable PCM playback sink (production uses Android [AudioTrack] with navigation audio attributes).
 */
interface EmbeddedPcmAudioSink {
    fun playPcm16(
        pcm16: ShortArray,
        sampleRate: Int,
        isCancelled: () -> Boolean,
        onPlaybackStarted: () -> Unit
    ): Boolean
    fun stopImmediate()
}

/**
 * Built-in offline bilingual Persian/English TTS engine backed by `sherpa-onnx` and
 * the Khadijah Matcha-TTS + HiFi-GAN models bundled inside the APK.
 *
 * Runs all asset extraction, ONNX synthesis, and [AudioTrack] streaming strictly off the
 * UI thread on a dedicated single-threaded executor.
 */
class EmbeddedPersianTtsEngine(
    private val appContext: Context,
    private val backend: OfflinePersianSynthesisBackend = SherpaOnnxKhadijahBackend(),
    private val audioSink: EmbeddedPcmAudioSink = AndroidNavigationAudioTrackSink()
) {
    @Volatile
    var onSynthesisReport: ((EmbeddedSynthesisReport) -> Unit)? = null

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val activeGeneration = AtomicLong(0L)
    private val speaking = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val assetsVerified: Boolean by lazy {
        EmbeddedTtsAssetExtractor.areBundledAssetsPresent(appContext)
    }

    @Volatile
    private var activeUtteranceId: String? = null
    @Volatile
    private var activeCallbacks: TtsUtteranceCallbacks? = null

    val isAvailable: Boolean
        get() = !released.get() && (backend !is SherpaOnnxKhadijahBackend || assetsVerified)

    val isSpeaking: Boolean
        get() = speaking.get()

    /** Pre-extracts eSpeak NG data and initializes the ONNX model asynchronously off the UI thread. */
    fun warmUpAsync() {
        if (!isAvailable || released.get()) return
        runCatching {
            worker.execute {
                if (!released.get()) {
                    runCatching { backend.ensureInitialized(appContext) }
                        .onFailure { err ->
                            CrashLogManager.logError(appContext, "TTS", "Embedded Persian TTS warm-up failed", err)
                        }
                }
            }
        }
    }

    /**
     * Synthesizes and plays a complete Persian (or bilingual Persian+English) navigation
     * utterance on the dedicated background TTS thread, invoking [callbacks] with the exact
     * same `onStart`/`onDone`/`onStop`/`onError` contract as Android [TextToSpeech].
     */
    fun speak(
        rawText: String,
        utteranceId: String,
        callbacks: TtsUtteranceCallbacks,
        speed: Float = DEFAULT_SPEECH_SPEED
    ): Boolean {
        if (released.get() || !isAvailable || rawText.isBlank()) return false
        val normalized = PersianTtsTextNormalizer.normalize(rawText)
        if (normalized.isBlank()) return false

        val gen = activeGeneration.incrementAndGet()
        audioSink.stopImmediate()
        activeUtteranceId = utteranceId
        activeCallbacks = callbacks
        speaking.set(true)

        return runCatching {
            worker.execute {
                runSynthesisAndPlayback(gen, rawText, normalized, utteranceId, callbacks, speed)
            }
            true
        }.onFailure { err ->
            speaking.set(false)
            CrashLogManager.logError(appContext, "TTS", "Failed to schedule embedded Persian TTS", err)
        }.getOrDefault(false)
    }

    fun stopAll() {
        val interruptedUtterance = activeUtteranceId
        val cb = activeCallbacks
        activeGeneration.incrementAndGet()
        activeUtteranceId = null
        activeCallbacks = null
        val wasSpeaking = speaking.getAndSet(false)
        audioSink.stopImmediate()
        if (wasSpeaking && interruptedUtterance != null && cb != null) {
            cb.onStop(interruptedUtterance, true)
        }
    }

    fun shutdown() {
        if (released.getAndSet(true)) return
        stopAll()
        runCatching {
            worker.execute { runCatching { backend.release() } }
            worker.shutdown()
        }
    }

    private fun runSynthesisAndPlayback(
        gen: Long,
        rawText: String,
        normalizedText: String,
        utteranceId: String,
        callbacks: TtsUtteranceCallbacks,
        speed: Float
    ) {
        val isCancelled: () -> Boolean = { released.get() || activeGeneration.get() != gen }
        try {
            if (isCancelled()) return
            if (!backend.ensureInitialized(appContext)) {
                finishError(gen, utteranceId, callbacks, TextToSpeech.ERROR_NOT_INSTALLED_YET)
                return
            }
            if (isCancelled()) return

            val audio = backend.synthesize(normalizedText, speed) { !isCancelled() }
            if (isCancelled()) return
            if (audio == null || audio.samples.isEmpty() || audio.sampleRate <= 0) {
                finishError(gen, utteranceId, callbacks, TextToSpeech.ERROR_SYNTHESIS)
                return
            }

            val (peak, rms) = measureAmplitude(audio.samples)
            val pcm16 = floatToPcm16WithTrailingPad(audio.samples, audio.sampleRate)
            var started = false
            val completed = audioSink.playPcm16(
                pcm16 = pcm16,
                sampleRate = audio.sampleRate,
                isCancelled = isCancelled,
                onPlaybackStarted = {
                    if (!isCancelled() && !started) {
                        started = true
                        callbacks.onStart(utteranceId)
                    }
                }
            )

            onSynthesisReport?.invoke(
                EmbeddedSynthesisReport(
                    utteranceId = utteranceId,
                    rawText = rawText,
                    normalizedText = normalizedText,
                    workerThreadName = Thread.currentThread().name,
                    sampleRate = audio.sampleRate,
                    sampleCount = audio.samples.size,
                    durationMs = audio.durationMs,
                    peakAmplitude = peak,
                    rmsAmplitude = rms,
                    completedToLastFrame = completed && !isCancelled(),
                    interrupted = isCancelled()
                )
            )

            if (isCancelled()) return
            if (completed) {
                if (!started) callbacks.onStart(utteranceId)
                clearActiveIfGenerationMatches(gen)
                callbacks.onDone(utteranceId)
            } else {
                finishError(gen, utteranceId, callbacks, TextToSpeech.ERROR_OUTPUT)
            }
        } catch (t: Throwable) {
            CrashLogManager.logError(appContext, "TTS", "Embedded Persian TTS error for $utteranceId", t)
            if (!isCancelled()) {
                finishError(gen, utteranceId, callbacks, TextToSpeech.ERROR)
            }
        }
    }

    private fun finishError(gen: Long, utteranceId: String, callbacks: TtsUtteranceCallbacks, code: Int) {
        clearActiveIfGenerationMatches(gen)
        callbacks.onError(utteranceId, code)
    }

    private fun clearActiveIfGenerationMatches(gen: Long) {
        if (activeGeneration.get() == gen) {
            speaking.set(false)
            activeUtteranceId = null
            activeCallbacks = null
        }
    }

    companion object {
        const val WORKER_THREAD_NAME = "rahyab-persian-tts"
        const val DEFAULT_SPEECH_SPEED = 1.0f
        const val ENGINE_PACKAGE_ID = "com.aistudio.rahyab.embedded.khadijah"
        const val ENGINE_DISPLAY_LABEL = "Built-in Offline Persian (Khadijah)"
        private const val TRAILING_SILENCE_MS = 180

        internal fun floatToPcm16WithTrailingPad(samples: FloatArray, sampleRate: Int): ShortArray {
            val padSamples = ((sampleRate.toLong() * TRAILING_SILENCE_MS) / 1000L).toInt().coerceAtLeast(0)
            val out = ShortArray(samples.size + padSamples)
            var peak = 0f
            for (s in samples) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            val gain = if (peak in 0.05f..0.70f) (0.90f / peak).coerceAtMost(1.45f) else 1.0f
            for (i in samples.indices) {
                val scaled = (samples[i] * gain).coerceIn(-0.98f, 0.98f)
                out[i] = (scaled * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            return out
        }

        internal fun measureAmplitude(samples: FloatArray): Pair<Float, Float> {
            if (samples.isEmpty()) return 0f to 0f
            var peak = 0f
            var sumSq = 0.0
            for (s in samples) {
                val a = abs(s)
                if (a > peak) peak = a
                sumSq += (s * s).toDouble()
            }
            return peak to sqrt(sumSq / samples.size).toFloat()
        }
    }
}

/**
 * Production `sherpa-onnx` Matcha-TTS backend loading the bundled Khadijah acoustic model,
 * HiFi-GAN vocoder, tokens, and extracted `espeak-ng-data` with zero network access.
 */
internal class SherpaOnnxKhadijahBackend : OfflinePersianSynthesisBackend {
    private val lock = Any()
    @Volatile
    private var offlineTts: OfflineTts? = null

    override fun ensureInitialized(context: Context): Boolean {
        if (offlineTts != null) return true
        synchronized(lock) {
            if (offlineTts != null) return true
            if (!EmbeddedTtsAssetExtractor.areBundledAssetsPresent(context)) return false
            val espeakDir = EmbeddedTtsAssetExtractor.ensureEspeakDataExtracted(context)
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = EmbeddedTtsAssetExtractor.ASSET_ACOUSTIC_MODEL,
                        vocoder = EmbeddedTtsAssetExtractor.ASSET_VOCODER,
                        tokens = EmbeddedTtsAssetExtractor.ASSET_TOKENS,
                        dataDir = espeakDir.absolutePath
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
            offlineTts = OfflineTts(assetManager = context.assets, config = config)
            return offlineTts != null
        }
    }

    override fun synthesize(
        normalizedText: String,
        speed: Float,
        shouldContinue: () -> Boolean
    ): SynthesizedPcmAudio? {
        val engine = offlineTts ?: return null
        val generated = engine.generateWithCallback(
            text = normalizedText,
            sid = 0,
            speed = speed,
            callback = { if (shouldContinue()) 1 else 0 }
        )
        if (!shouldContinue()) return null
        return SynthesizedPcmAudio(samples = generated.samples, sampleRate = generated.sampleRate)
    }

    override fun release() {
        synchronized(lock) {
            runCatching { offlineTts?.release() }
            offlineTts = null
        }
    }
}

/**
 * Production Android [AudioTrack] PCM sink configured with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`
 * so audio routes cleanly through Phone speakers, Bluetooth HFP/A2DP, and Android Auto,
 * draining all frames through the trailing silence pad before completing.
 */
internal class AndroidNavigationAudioTrackSink : EmbeddedPcmAudioSink {
    private val trackLock = Any()
    @Volatile
    private var activeTrack: AudioTrack? = null

    override fun playPcm16(
        pcm16: ShortArray,
        sampleRate: Int,
        isCancelled: () -> Boolean,
        onPlaybackStarted: () -> Unit
    ): Boolean {
        if (pcm16.isEmpty() || sampleRate <= 0) return false
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufferBytes = (minBuf * 2).coerceAtLeast(sampleRate / 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synchronized(trackLock) { activeTrack = track }
        return try {
            if (isCancelled()) return false
            track.play()
            onPlaybackStarted()

            var offset = 0
            val chunkSize = 2048
            while (offset < pcm16.size && !isCancelled()) {
                val count = (pcm16.size - offset).coerceAtMost(chunkSize)
                val written = track.write(pcm16, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) return false
                offset += written
            }
            if (isCancelled()) return false

            // Wait for hardware buffer drain up to the audio duration + small safety margin
            val expectedDurationMs = (pcm16.size * 1000L) / sampleRate
            val drainDeadlineMs = System.currentTimeMillis() + expectedDurationMs.coerceAtMost(4000L) + 250L
            while (!isCancelled() &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < pcm16.size &&
                System.currentTimeMillis() < drainDeadlineMs
            ) {
                try {
                    Thread.sleep(15L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            !isCancelled()
        } finally {
            synchronized(trackLock) {
                if (activeTrack === track) activeTrack = null
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
            runCatching { track.release() }
        }
    }

    override fun stopImmediate() {
        val track = synchronized(trackLock) { activeTrack } ?: return
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
    }
}
