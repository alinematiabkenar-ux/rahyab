package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

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
 * Minimal handle over Android [AudioTrack] allowing deterministic unit testing of PCM write,
 * drain-to-last-frame completion, premature stop, deadline expiration, and thread interruption.
 */
internal interface NavigationPcmTrack {
    val isInitialized: Boolean
    val playState: Int
    val playbackHeadPosition: Int
    val bufferSizeInFrames: Int
    fun play()
    fun writeBlocking(pcm16: ShortArray, offset: Int, count: Int): Int
    fun pauseFlushStop()
    fun release()
}

private class AndroidAudioTrackHandle(
    private val track: AudioTrack,
    override val bufferSizeInFrames: Int
) : NavigationPcmTrack {
    override val isInitialized: Boolean
        get() = track.state == AudioTrack.STATE_INITIALIZED

    override val playState: Int
        get() = track.playState

    override val playbackHeadPosition: Int
        get() = track.playbackHeadPosition

    override fun play() {
        track.play()
    }

    override fun writeBlocking(pcm16: ShortArray, offset: Int, count: Int): Int =
        track.write(pcm16, offset, count, AudioTrack.WRITE_BLOCKING)

    override fun pauseFlushStop() {
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
    }

    override fun release() {
        runCatching { track.release() }
    }
}

/**
 * Production Android [AudioTrack] PCM sink configured with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`
 * so audio routes cleanly through Phone speakers, Bluetooth HFP/A2DP, and Android Auto.
 *
 * Strictly verifies that all PCM frames are written and that the playback head genuinely reaches
 * the final frame (`pcm16.size`) before returning `true`; any early deadline expiration, playback
 * stop, write failure, cancellation, or thread interruption returns `false`.
 */
internal class AndroidNavigationAudioTrackSink(
    private val trackFactory: (sampleRate: Int, bufferBytes: Int) -> NavigationPcmTrack = ::createPlatformTrack,
    private val minBufferSizeProvider: (sampleRate: Int) -> Int = ::defaultMinBufferSizeBytes,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) }
) : EmbeddedPcmAudioSink {
    private val trackLock = Any()
    @Volatile
    private var activeTrack: NavigationPcmTrack? = null

    override fun playPcm16(
        pcm16: ShortArray,
        sampleRate: Int,
        isCancelled: () -> Boolean,
        onPlaybackStarted: () -> Unit
    ): Boolean {
        if (pcm16.isEmpty() || sampleRate <= 0) return false
        val bufferBytes = computeAlignedBufferBytes(sampleRate, minBufferSizeProvider(sampleRate))
        val track = runCatching { trackFactory(sampleRate, bufferBytes) }.getOrNull() ?: return false
        if (!track.isInitialized) {
            track.release()
            return false
        }

        synchronized(trackLock) { activeTrack = track }
        return try {
            if (isCancelled()) return false
            track.play()
            onPlaybackStarted()

            var writtenFrames = 0
            val chunkSize = 2048
            while (writtenFrames < pcm16.size && !isCancelled()) {
                val count = (pcm16.size - writtenFrames).coerceAtMost(chunkSize)
                val written = track.writeBlocking(pcm16, writtenFrames, count)
                if (written <= 0) return false
                writtenFrames += written
            }
            if (isCancelled() || writtenFrames != pcm16.size) return false

            // In MODE_STREAM, very short clips smaller than the AudioTrack buffer may not cross
            // the HAL start threshold until the buffer fills; pad silence so head advances past pcm16.size.
            val startThresholdFrames = track.bufferSizeInFrames.coerceAtLeast(0)
            if (writtenFrames < startThresholdFrames && !isCancelled()) {
                val padNeeded = startThresholdFrames - writtenFrames
                val silence = ShortArray(padNeeded.coerceAtMost(chunkSize))
                var padRemaining = padNeeded
                while (padRemaining > 0 && !isCancelled()) {
                    val count = padRemaining.coerceAtMost(silence.size)
                    val written = track.writeBlocking(silence, 0, count)
                    if (written <= 0) break
                    padRemaining -= written
                }
                if (isCancelled()) return false
            }

            val targetFrames = pcm16.size.toLong()
            var lastHeadFrames = readUnsignedHeadFrames(track)
            val remainingFrames = (targetFrames - lastHeadFrames).coerceAtLeast(0L)
            val remainingDurationMs = (remainingFrames * 1000L) / sampleRate
            // Never cap remaining audio duration at 4s: long bilingual highway exit instructions
            // routinely span 6-9 seconds and must drain completely to the final frame.
            val drainDeadlineMs = clockMs() + remainingDurationMs + DRAIN_GRACE_MS

            var interrupted = false
            while (!isCancelled() &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                lastHeadFrames < targetFrames &&
                clockMs() < drainDeadlineMs
            ) {
                try {
                    sleepMs(DRAIN_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    Thread.currentThread().interrupt()
                    break
                }
                val head = readUnsignedHeadFrames(track)
                if (head > lastHeadFrames) {
                    lastHeadFrames = head
                }
            }

            if (!isCancelled() && !interrupted) {
                val finalHead = readUnsignedHeadFrames(track)
                if (finalHead > lastHeadFrames) {
                    lastHeadFrames = finalHead
                }
            }

            val reachedFinalFrame = lastHeadFrames >= targetFrames
            !isCancelled() && !interrupted && writtenFrames == pcm16.size && reachedFinalFrame
        } finally {
            synchronized(trackLock) {
                if (activeTrack === track) activeTrack = null
            }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pauseFlushStop()
            }
            track.release()
        }
    }

    override fun stopImmediate() {
        val track = synchronized(trackLock) { activeTrack } ?: return
        track.pauseFlushStop()
    }

    companion object {
        private const val BYTES_PER_FRAME = 2
        private const val DRAIN_POLL_INTERVAL_MS = 15L
        private const val DRAIN_GRACE_MS = 2_500L

        internal fun computeAlignedBufferBytes(sampleRate: Int, minBufferSize: Int): Int {
            val safeMin = minBufferSize.coerceAtLeast(4096)
            val rawBytes = (safeMin * 2).coerceAtLeast(sampleRate / 2)
            return ((rawBytes + BYTES_PER_FRAME - 1) / BYTES_PER_FRAME) * BYTES_PER_FRAME
        }

        private fun readUnsignedHeadFrames(track: NavigationPcmTrack): Long =
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

        private fun defaultMinBufferSizeBytes(sampleRate: Int): Int =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        private fun createPlatformTrack(sampleRate: Int, bufferBytes: Int): NavigationPcmTrack {
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
            return AndroidAudioTrackHandle(track, bufferBytes / BYTES_PER_FRAME)
        }
    }
}
