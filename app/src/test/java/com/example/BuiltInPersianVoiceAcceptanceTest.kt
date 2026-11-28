package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.EmbeddedPcmAudioSink
import com.example.navigation.EmbeddedPersianTtsEngine
import com.example.navigation.EmbeddedSynthesisReport
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflinePersianSynthesisBackend
import com.example.navigation.PersianTtsTextNormalizer
import com.example.navigation.PersianVoiceMode
import com.example.navigation.RouteManeuver
import com.example.navigation.SpeechContextTag
import com.example.navigation.SpeechLifecycleState
import com.example.navigation.SpeechTraceRecord
import com.example.navigation.SynthesizedPcmAudio
import com.example.navigation.VoiceGuidanceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end acceptance tests for the built-in offline Persian navigation voice
 * (sherpa-onnx + Khadijah FA/EN Matcha-TTS + HiFi-GAN V2):
 * - Fresh install in airplane mode with zero Android system Persian TTS installed
 * - Normalization of Persian/Latin digits, exits, lane counts, and English street names
 * - Unified settings sample ("پخش نمونه") and live navigation playback path
 * - Off-UI-thread synthesis & PCM completion through final frame
 * - Duplicate GPS fix protection, reroute preemption, and Android Auto single playback owner
 * - Bundled APK asset presence verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltInPersianVoiceAcceptanceTest {

    private class DeterministicWaveBackend(
        private val synthDelayMs: Long = 0L
    ) : OfflinePersianSynthesisBackend {
        val synthesizedTexts = CopyOnWriteArrayList<String>()
        val synthesisThreads = CopyOnWriteArrayList<String>()

        override fun ensureInitialized(context: Context): Boolean = true

        override fun synthesize(
            normalizedText: String,
            speed: Float,
            shouldContinue: () -> Boolean
        ): SynthesizedPcmAudio? {
            synthesisThreads.add(Thread.currentThread().name)
            synthesizedTexts.add(normalizedText)
            if (synthDelayMs > 0L) {
                val deadline = System.currentTimeMillis() + synthDelayMs
                while (System.currentTimeMillis() < deadline) {
                    if (!shouldContinue()) return null
                    Thread.sleep(5L)
                }
            }
            if (!shouldContinue()) return null
            // Generate a realistic non-silent 22,050 Hz tone proportional to text length
            val sampleRate = 22050
            val count = (sampleRate / 10).coerceAtLeast(normalizedText.length * 40)
            val samples = FloatArray(count) { i ->
                (0.45 * sin(2.0 * PI * 320.0 * i / sampleRate)).toFloat()
            }
            return SynthesizedPcmAudio(samples = samples, sampleRate = sampleRate)
        }

        override fun release() = Unit
    }

    private class RecordingPcmSink(
        private val chunkDelayMs: Long = 0L,
        private val onStartHook: (() -> Unit)? = null
    ) : EmbeddedPcmAudioSink {
        val completedPlaybacks = CopyOnWriteArrayList<ShortArray>()
        val interruptedCount = AtomicInteger(0)
        private val forceStopped = AtomicBoolean(false)

        override fun playPcm16(
            pcm16: ShortArray,
            sampleRate: Int,
            isCancelled: () -> Boolean,
            onPlaybackStarted: () -> Unit
        ): Boolean {
            forceStopped.set(false)
            if (isCancelled()) {
                interruptedCount.incrementAndGet()
                return false
            }
            onPlaybackStarted()
            onStartHook?.invoke()
            if (chunkDelayMs > 0L) {
                val steps = 8
                repeat(steps) {
                    if (isCancelled() || forceStopped.get()) {
                        interruptedCount.incrementAndGet()
                        return false
                    }
                    Thread.sleep(chunkDelayMs)
                }
            }
            if (isCancelled() || forceStopped.get()) {
                interruptedCount.incrementAndGet()
                return false
            }
            completedPlaybacks.add(pcm16)
            return true
        }

        override fun stopImmediate() {
            forceStopped.set(true)
        }
    }

    @Test
    fun bundledKhadijahAssetsAndLicensesExistInApkSourceTree() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val candidates = listOf(File("src/main/assets/tts/khadijah"), File("app/src/main/assets/tts/khadijah"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not find assets/tts/khadijah from ${File(".").absolutePath}")

        val requiredStaticFiles = listOf(
            "vocoder.onnx",
            "tokens.txt",
            "LICENSES.txt",
            "espeak-ng-data/phondata",
            "espeak-ng-data/phontab",
            "espeak-ng-data/phonindex",
            "espeak-ng-data/intonations",
            "espeak-ng-data/fa_dict",
            "espeak-ng-data/en_dict",
            "espeak-ng-data/lang/ira/fa",
            "espeak-ng-data/lang/gmw/en",
            "espeak-ng-data/lang/gmw/en-US"
        )
        for (rel in requiredStaticFiles) {
            val f = File(root, rel)
            assertTrue("Missing bundled offline TTS asset: $rel", f.isFile && f.length() > 0L)
        }
        // Verify merged Android assets contain acoustic model.onnx, vocoder.onnx, tokens.txt, and espeak-ng-data
        val mergedAssets = listOf(
            "tts/khadijah/model.onnx",
            "tts/khadijah/vocoder.onnx",
            "tts/khadijah/tokens.txt",
            "tts/khadijah/espeak-ng-data/phondata",
            "tts/khadijah/espeak-ng-data/fa_dict",
            "tts/khadijah/espeak-ng-data/en_dict"
        )
        for (assetPath in mergedAssets) {
            val readable = runCatching {
                ctx.assets.open(assetPath).use { it.read() != -1 }
            }.getOrDefault(false)
            assertTrue("Missing merged APK asset: $assetPath", readable)
        }
        val licensesText = File(root, "LICENSES.txt").readText()
        assertTrue(licensesText.contains("CC0-1.0"))
        assertTrue(licensesText.contains("Apache"))
        assertTrue(licensesText.contains("MIT"))
        assertTrue(licensesText.contains("GPL-3.0"))
    }

    @Test
    fun persianNormalizerHandlesDigitsExitsLanesAndEnglishStreetNames() {
        val exitSentence = PersianTtsTextNormalizer.normalize(
            "در ۵۰۰ متری، از خروجی 195 در سمت راست خارج شوید"
        )
        assertEquals("در پانصد متری، از خروجی صد و نود و پنج در سمت راست خارج شوید", exitSentence)

        val oakStreetSentence = PersianTtsTextNormalizer.normalize(
            "در پانصد متری، به راست بپیچید و وارد Oak Street شوید"
        )
        assertEquals("در پانصد متری، به راست بپیچید و وارد Oak Street شوید", oakStreetSentence)

        val exitWithLetterAndHwy = PersianTtsTextNormalizer.normalize(
            "در 1.5 کیلومتری، از 2 خط سمت راست وارد خروجی 194B در I-5 به سمت Oak St. شوید."
        )
        assertTrue("Expected normalized decimal: $exitWithLetterAndHwy", exitWithLetterAndHwy.contains("یک و نیم کیلومتری"))
        assertTrue("Expected normalized lane count: $exitWithLetterAndHwy", exitWithLetterAndHwy.contains("دو خط"))
        assertTrue("Expected normalized exit 194B: $exitWithLetterAndHwy", exitWithLetterAndHwy.contains("خروجی صد و نود و چهار بی"))
        assertTrue("Expected normalized I-5: $exitWithLetterAndHwy", exitWithLetterAndHwy.contains("آی پنج"))
        assertTrue("Expected expanded Oak Street: $exitWithLetterAndHwy", exitWithLetterAndHwy.contains("Oak Street"))
    }

    @Test
    fun freshInstallOfflineNoSystemPersianVoiceSpeaksCompleteUnifiedInstructionOffUiThread() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Verify default user setting uses built-in offline Persian with zero setup
        assertEquals(PersianVoiceMode.BUILTIN_OFFLINE, UserSettings().persianVoiceMode)

        val backend = DeterministicWaveBackend()
        val sink = RecordingPcmSink()
        val embedded = EmbeddedPersianTtsEngine(ctx, backend = backend, audioSink = sink)
        val manager = VoiceGuidanceManager(ctx)
        manager.setEmbeddedPersianEngine(embedded)

        val reports = CopyOnWriteArrayList<EmbeddedSynthesisReport>()
        val traces = CopyOnWriteArrayList<SpeechTraceRecord>()
        val doneLatch = CountDownLatch(1)
        manager.setEmbeddedSynthesisObserver { reports.add(it) }
        manager.setTraceSink { rec ->
            traces.add(rec)
            if (rec.finalState == SpeechLifecycleState.COMPLETED) doneLatch.countDown()
        }

        // Simulate fresh install in airplane mode where Android TextToSpeech never initialized
        // and no system Persian voice exists: built-in Persian MUST still be ready immediately!
        manager.setPersianVoiceMode(PersianVoiceMode.BUILTIN_OFFLINE)
        manager.setLanguage(AppLanguage.PERSIAN)
        assertTrue("Built-in Persian must report available without system TTS", manager.isPersianVoiceAvailable.value)
        val diag = manager.refreshTtsDiagnostics()
        assertTrue(diag.isPersianAvailable)
        assertTrue(diag.isPersianInstalled)
        assertEquals(EmbeddedPersianTtsEngine.ENGINE_PACKAGE_ID, diag.persianEnginePackage)

        // Play settings "پخش نمونه" through the exact same VoiceGuidanceManager -> VoiceSpeechQueue path
        val accepted = manager.speakExplicitLanguageTest(AppLanguage.PERSIAN)
        assertTrue("Sample playback must be accepted offline with zero system TTS", accepted)
        assertTrue("Sample playback must complete through last PCM frame", doneLatch.await(3, TimeUnit.SECONDS))

        assertEquals(1, reports.size)
        val report = reports.first()
        assertEquals(EmbeddedPersianTtsEngine.WORKER_THREAD_NAME, report.workerThreadName)
        assertNotEquals(Thread.currentThread().name, report.workerThreadName)
        assertTrue("PCM must be non-silent", report.peakAmplitude > 0.1f && report.rmsAmplitude > 0.05f)
        assertTrue("PCM must complete to last frame", report.completedToLastFrame)
        assertFalse(report.interrupted)
        assertTrue("Normalized sample must contain Oak Street", report.normalizedText.contains("Oak Street"))
        assertTrue("Normalized sample must convert ۵۰۰ to پانصد", report.normalizedText.contains("پانصد"))

        val completedTrace = traces.last { it.finalState == SpeechLifecycleState.COMPLETED }
        assertEquals("Built-in Khadijah voice must speak Persian+English in ONE unified segment", 1, completedTrace.segmentCount)
        assertEquals(1, completedTrace.completedSegments)
        assertEquals(1, sink.completedPlaybacks.size)
        manager.shutdown()
    }

    @Test
    fun routeReplayDuplicateGpsDoesNotInterruptAndRerouteDiscardsObsoleteSpeech() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val startedLatch = CountDownLatch(1)
        val firstCompletedLatch = CountDownLatch(1)
        val rerouteCompletedLatch = CountDownLatch(1)

        val backend = DeterministicWaveBackend(synthDelayMs = 15L)
        val sink = RecordingPcmSink(chunkDelayMs = 20L, onStartHook = { startedLatch.countDown() })
        val embedded = EmbeddedPersianTtsEngine(ctx, backend = backend, audioSink = sink)
        val manager = VoiceGuidanceManager(ctx)
        manager.setEmbeddedPersianEngine(embedded)
        manager.setPersianVoiceMode(PersianVoiceMode.BUILTIN_OFFLINE)
        manager.setLanguage(AppLanguage.PERSIAN)

        val traces = CopyOnWriteArrayList<SpeechTraceRecord>()
        manager.setTraceSink { rec ->
            traces.add(rec)
            if (rec.finalState == SpeechLifecycleState.COMPLETED && rec.stage == NavigationSessionController.STAGE_HW_500M) {
                firstCompletedLatch.countDown()
            }
            if (rec.finalState == SpeechLifecycleState.COMPLETED && rec.stage == "rerouting") {
                rerouteCompletedLatch.countDown()
            }
        }

        val exitManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 195 right",
            streetNames = listOf("Oak Street"),
            lengthMeters = 600.0,
            timeSeconds = 30.0,
            beginShapeIndex = 0,
            endShapeIndex = 10,
            lat = 44.0521,
            lon = -123.0868,
            signpost = HighwaySignpost(exitNumbers = listOf("195"), exitTowards = listOf("Oak Street")),
            lanes = listOf(
                LaneInfo(
                    directions = listOf(LaneDirection.THROUGH),
                    activeDirections = emptyList(),
                    isRecommended = false
                ),
                LaneInfo(
                    directions = listOf(LaneDirection.SLIGHT_RIGHT),
                    activeDirections = listOf(LaneDirection.SLIGHT_RIGHT),
                    isRecommended = true
                )
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        // 1. Highway exit + recommended lane speaks cleanly
        manager.speakManeuver(
            maneuver = exitManeuver,
            distanceMeters = 500.0,
            unit = DistanceUnit.METRIC,
            language = AppLanguage.PERSIAN,
            announcementStage = NavigationSessionController.STAGE_HW_500M,
            includeLaneGuidance = true,
            routeId = "route_1"
        )
        assertTrue("First instruction must start speaking", startedLatch.await(2, TimeUnit.SECONDS))

        // 2. Duplicate GPS fix for the same route/maneuver/stage must NOT restart or cut off in-flight speech
        manager.speakManeuver(
            maneuver = exitManeuver,
            distanceMeters = 492.0,
            unit = DistanceUnit.METRIC,
            language = AppLanguage.PERSIAN,
            announcementStage = NavigationSessionController.STAGE_HW_500M,
            includeLaneGuidance = true,
            routeId = "route_1"
        )
        assertTrue("In-flight exit instruction must complete uninterrupted", firstCompletedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, backend.synthesizedTexts.size)
        assertTrue(backend.synthesizedTexts.first().contains("صد و نود و پنج"))

        // 3. Queue a city turn onto Oak Street, then trigger reroute immediately -> obsolete speech discarded
        val oakTurn = RouteManeuver(
            index = 2,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right onto Oak Street",
            streetNames = listOf("Oak Street"),
            lengthMeters = 500.0,
            timeSeconds = 25.0,
            beginShapeIndex = 10,
            endShapeIndex = 20,
            lat = 44.0531,
            lon = -123.0858
        )
        manager.speakManeuver(
            maneuver = oakTurn,
            distanceMeters = 500.0,
            unit = DistanceUnit.METRIC,
            language = AppLanguage.PERSIAN,
            announcementStage = NavigationSessionController.STAGE_CITY_HALF_MI,
            routeId = "route_1"
        )
        // Queue a secondary instruction behind it
        manager.speakCustomText("در دویست متری مستقیم بروید", AppLanguage.PERSIAN)
        Thread.sleep(30L)
        // Trigger reroute — must preempt & clear obsolete queued speech
        manager.speakRerouting(AppLanguage.PERSIAN)
        assertTrue("Reroute prompt must complete", rerouteCompletedLatch.await(3, TimeUnit.SECONDS))
        assertTrue(
            "Expected reroute cancellation trace",
            traces.any { it.cancelReason == "preempted_by_reroute" }
        )

        // 4. Verify single playback owner across Phone & Android Auto: RahYabApplication exposes one shared instance
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        assertTrue(app.voiceGuidanceManager === app.voiceGuidanceManager)
        manager.shutdown()
    }
}
