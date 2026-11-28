package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
