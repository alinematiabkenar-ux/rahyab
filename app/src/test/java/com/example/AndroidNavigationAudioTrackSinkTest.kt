package com.example

import android.content.Context
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.navigation.AndroidNavigationAudioTrackSink
import com.example.navigation.EmbeddedPersianTtsEngine
import com.example.navigation.EmbeddedSynthesisReport
import com.example.navigation.NavigationPcmTrack
import com.example.navigation.OfflinePersianSynthesisBackend
import com.example.navigation.SynthesizedPcmAudio
import com.example.navigation.TtsUtteranceCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic regression tests for [AndroidNavigationAudioTrackSink.playPcm16] verifying
 * that premature drain exit (deadline expiration, early playback stop, thread interruption,
 * write failure) NEVER reports false completion, while long (>4s) and short clips drain
 * completely through the final PCM frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNavigationAudioTrackSinkTest {

    private class FakePcmTrack(
        override val bufferSizeInFrames: Int = 2048,
        override var isInitialized: Boolean = true,
        private val writeReturnOverride: Int? = null,
        private val requireFullBufferToAdvance: Boolean = false
    ) : NavigationPcmTrack {
        override var playState: Int = AudioTrack.PLAYSTATE_STOPPED
        override var playbackHeadPosition: Int = 0
        var totalWrittenFrames: Int = 0
        var released: Boolean = false
        var playCalls: Int = 0

        override fun play() {
            playCalls++
            playState = AudioTrack.PLAYSTATE_PLAYING
        }

        override fun writeBlocking(pcm16: ShortArray, offset: Int, count: Int): Int {
            writeReturnOverride?.let { return it }
            totalWrittenFrames += count
            return count
        }

        override fun pauseFlushStop() {
            playState = AudioTrack.PLAYSTATE_STOPPED
        }

        override fun release() {
            released = true
        }

        fun canAdvance(): Boolean = !requireFullBufferToAdvance || totalWrittenFrames >= bufferSizeInFrames
    }

    private fun constantToneBackend(durationSeconds: Double, sampleRate: Int = 22050): OfflinePersianSynthesisBackend =
        object : OfflinePersianSynthesisBackend {
            override fun ensureInitialized(context: Context): Boolean = true
            override fun synthesize(
                normalizedText: String,
                speed: Float,
                shouldContinue: () -> Boolean
            ): SynthesizedPcmAudio {
                val n = (sampleRate * durationSeconds).toInt()
                val samples = FloatArray(n) { i -> (0.4 * sin(2.0 * PI * 300.0 * i / sampleRate)).toFloat() }
                return SynthesizedPcmAudio(samples = samples, sampleRate = sampleRate)
            }
            override fun release() = Unit
        }

    @Test
    fun playPcm16_deadlineExpiresBeforeFinalFrame_returnsFalseAndEmitsErrorNotDone() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fakeClock = AtomicLong(10_000L)
        val fakeTrack = FakePcmTrack()
        val sampleRate = 22050
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> fakeTrack },
            minBufferSizeProvider = { 4096 },
            clockMs = { fakeClock.get() },
            sleepMs = { dt ->
                fakeClock.addAndGet(dt)
                // Advance head only partway (stuck at 60% of written frames)
                val cap = (fakeTrack.totalWrittenFrames * 6) / 10
                if (fakeTrack.playbackHeadPosition < cap) {
                    fakeTrack.playbackHeadPosition = (fakeTrack.playbackHeadPosition + 300).coerceAtMost(cap)
                }
            }
        )

        val engine = EmbeddedPersianTtsEngine(ctx, backend = constantToneBackend(1.5, sampleRate), audioSink = sink)
        val doneCalled = AtomicBoolean(false)
        val capturedErrorCode = AtomicInteger(0)
        val terminalLatch = CountDownLatch(1)
        var capturedReport: EmbeddedSynthesisReport? = null
        engine.onSynthesisReport = { capturedReport = it }

        val started = engine.speak(
            rawText = "در ۵۰۰ متری، به راست بپیچید و وارد Oak Street شوید.",
            utteranceId = "utt_deadline",
            callbacks = object : TtsUtteranceCallbacks {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    doneCalled.set(true)
                    terminalLatch.countDown()
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    terminalLatch.countDown()
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    capturedErrorCode.set(errorCode)
                    terminalLatch.countDown()
                }
            }
        )

        assertTrue(started)
        assertTrue(terminalLatch.await(3, TimeUnit.SECONDS))
        assertFalse("Deadline expiry before final frame must NOT emit onDone", doneCalled.get())
        assertEquals("Must emit ERROR_OUTPUT on incomplete drain", TextToSpeech.ERROR_OUTPUT, capturedErrorCode.get())
        assertFalse("completedToLastFrame must be false", capturedReport!!.completedToLastFrame)
        assertTrue("Track must be released", fakeTrack.released)
        engine.shutdown()
    }

    @Test
    fun playPcm16_playStateStopsBeforeFinalFrame_returnsFalseAndEmitsErrorNotDone() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fakeClock = AtomicLong(20_000L)
        val fakeTrack = FakePcmTrack()
        var pollCount = 0
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> fakeTrack },
            minBufferSizeProvider = { 4096 },
            clockMs = { fakeClock.get() },
            sleepMs = { dt ->
                fakeClock.addAndGet(dt)
                pollCount++
                fakeTrack.playbackHeadPosition += 330
                // Audio HAL stops playback prematurely after 5 polls
                if (pollCount == 5) {
                    fakeTrack.playState = AudioTrack.PLAYSTATE_STOPPED
                }
            }
        )

        val engine = EmbeddedPersianTtsEngine(ctx, backend = constantToneBackend(2.0), audioSink = sink)
        val doneCalled = AtomicBoolean(false)
        val capturedErrorCode = AtomicInteger(0)
        val terminalLatch = CountDownLatch(1)
        var capturedReport: EmbeddedSynthesisReport? = null
        engine.onSynthesisReport = { capturedReport = it }

        engine.speak(
            rawText = "در ۵۰۰ متری، به راست بپیچید و وارد Oak Street شوید.",
            utteranceId = "utt_early_stop",
            callbacks = object : TtsUtteranceCallbacks {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    doneCalled.set(true)
                    terminalLatch.countDown()
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    terminalLatch.countDown()
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    capturedErrorCode.set(errorCode)
                    terminalLatch.countDown()
                }
            }
        )

        assertTrue(terminalLatch.await(3, TimeUnit.SECONDS))
        assertFalse("Premature AudioTrack stop must NOT emit onDone", doneCalled.get())
        assertEquals(TextToSpeech.ERROR_OUTPUT, capturedErrorCode.get())
        assertFalse("completedToLastFrame must be false", capturedReport!!.completedToLastFrame)
        assertTrue(fakeTrack.released)
        engine.shutdown()
    }

    @Test
    fun playPcm16_waitInterruptedBeforeFinalFrame_returnsFalseAndRestoresInterruptFlag() {
        val fakeClock = AtomicLong(30_000L)
        val fakeTrack = FakePcmTrack()
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> fakeTrack },
            minBufferSizeProvider = { 4096 },
            clockMs = { fakeClock.get() },
            sleepMs = { throw InterruptedException("simulated interrupt") }
        )

        val pcm = ShortArray(22050) { 1000 }
        val completed = sink.playPcm16(
            pcm16 = pcm,
            sampleRate = 22050,
            isCancelled = { false },
            onPlaybackStarted = {}
        )
        val interruptWasPreserved = Thread.interrupted()

        assertFalse("Interrupted drain before final frame must return false", completed)
        assertTrue("Thread interrupt status must be restored", interruptWasPreserved)
        assertTrue(fakeTrack.released)
    }

    @Test
    fun playPcm16_longBilingualUtteranceOver7Seconds_drainsCompletelyPastOld4sCap() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fakeClock = AtomicLong(40_000L)
        val fakeTrack = FakePcmTrack()
        val sampleRate = 22050
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> fakeTrack },
            minBufferSizeProvider = { 4096 },
            clockMs = { fakeClock.get() },
            sleepMs = { dt ->
                fakeClock.addAndGet(dt)
                // Simulate real-time playback head advancement at 22050 Hz
                val framesForTick = ((sampleRate * dt) / 1000L).toInt().coerceAtLeast(1)
                fakeTrack.playbackHeadPosition += framesForTick
            }
        )

        // 7.5-second highway exit instruction with English street name + Persian tail
        val engine = EmbeddedPersianTtsEngine(ctx, backend = constantToneBackend(7.5, sampleRate), audioSink = sink)
        val doneCalled = AtomicBoolean(false)
        val errorCalled = AtomicBoolean(false)
        val terminalLatch = CountDownLatch(1)
        var capturedReport: EmbeddedSynthesisReport? = null
        engine.onSynthesisReport = { capturedReport = it }

        engine.speak(
            rawText = "در پانصد متری، از خط دوم از راست وارد خروجی 195B به سمت Beltline Highway West شوید.",
            utteranceId = "utt_long_7_5s",
            callbacks = object : TtsUtteranceCallbacks {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    doneCalled.set(true)
                    terminalLatch.countDown()
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    terminalLatch.countDown()
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    errorCalled.set(true)
                    terminalLatch.countDown()
                }
            }
        )

        assertTrue(terminalLatch.await(3, TimeUnit.SECONDS))
        assertTrue("Long 7.5s utterance must complete to last frame without hitting 4s cap", doneCalled.get())
        assertFalse(errorCalled.get())
        assertTrue("completedToLastFrame must be true", capturedReport!!.completedToLastFrame)
        assertTrue(
            "Elapsed simulated drain time must exceed 7500ms (was ${fakeClock.get() - 40_000L}ms)",
            fakeClock.get() - 40_000L >= 7500L
        )
        assertTrue(fakeTrack.released)
        engine.shutdown()
    }

    @Test
    fun playPcm16_shortClipSmallerThanBuffer_padsSilenceToStartThresholdAndCompletes() {
        val fakeClock = AtomicLong(50_000L)
        val fakeTrack = FakePcmTrack(bufferSizeInFrames = 5513, requireFullBufferToAdvance = true)
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> fakeTrack },
            minBufferSizeProvider = { 4096 },
            clockMs = { fakeClock.get() },
            sleepMs = { dt ->
                fakeClock.addAndGet(dt)
                if (fakeTrack.canAdvance()) {
                    fakeTrack.playbackHeadPosition += 350
                }
            }
        )

        val shortClip = ShortArray(1200) { 1200 }
        val completed = sink.playPcm16(
            pcm16 = shortClip,
            sampleRate = 22050,
            isCancelled = { false },
            onPlaybackStarted = {}
        )

        assertTrue("Short clip must pad silence to reach start threshold and drain completely", completed)
        assertTrue(
            "Total written frames (${fakeTrack.totalWrittenFrames}) must reach bufferSizeInFrames",
            fakeTrack.totalWrittenFrames >= fakeTrack.bufferSizeInFrames
        )
        assertTrue(fakeTrack.playbackHeadPosition >= shortClip.size)
        assertTrue(fakeTrack.released)
    }

    @Test
    fun playPcm16_zeroOrErrorWrite_returnsFalseWithoutHanging_andBufferBytesAlwaysEven() {
        val evenAt22050 = AndroidNavigationAudioTrackSink.computeAlignedBufferBytes(
            sampleRate = 22050,
            minBufferSize = 4096
        )
        assertEquals("Buffer bytes must be frame-aligned (even) for 16-bit mono PCM", 0, evenAt22050 % 2)
        assertTrue("Buffer bytes must be >= sampleRate/2", evenAt22050 >= 11026)

        val zeroWriteTrack = FakePcmTrack(writeReturnOverride = 0)
        val sink = AndroidNavigationAudioTrackSink(
            trackFactory = { _, _ -> zeroWriteTrack },
            minBufferSizeProvider = { 4096 }
        )
        assertFalse(
            "Zero write return must abort immediately with false",
            sink.playPcm16(ShortArray(2048) { 500 }, 22050, { false }, {})
        )
        assertTrue(zeroWriteTrack.released)
    }
}
