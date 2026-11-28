package com.example.navigation

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Verifies bundled Khadijah offline Persian TTS assets inside the APK and extracts the
 * small (~1.1 MB) `espeak-ng-data` directory once to internal app storage so native
 * `espeak-ng` can open phoneme/dictionary files via POSIX paths.
 *
 * The large ONNX model and vocoder remain inside APK assets and are loaded directly via
 * [AssetManager] by `sherpa-onnx` without duplicating ~78 MB on internal storage.
 */
internal object EmbeddedTtsAssetExtractor {

    const val ASSET_ROOT = "tts/khadijah"
    const val ASSET_ACOUSTIC_MODEL = "$ASSET_ROOT/model.onnx"
    const val ASSET_VOCODER = "$ASSET_ROOT/vocoder.onnx"
    const val ASSET_TOKENS = "$ASSET_ROOT/tokens.txt"
    const val ASSET_ESPEAK_DIR = "$ASSET_ROOT/espeak-ng-data"

    private const val EXTRACT_VERSION_MARKER = ".khadijah_espeak_v1"

    private val REQUIRED_CORE_ASSETS = listOf(
        ASSET_ACOUSTIC_MODEL,
        ASSET_VOCODER,
        ASSET_TOKENS,
        "$ASSET_ESPEAK_DIR/phondata",
        "$ASSET_ESPEAK_DIR/phontab",
        "$ASSET_ESPEAK_DIR/phonindex",
        "$ASSET_ESPEAK_DIR/fa_dict",
        "$ASSET_ESPEAK_DIR/en_dict",
        "$ASSET_ESPEAK_DIR/lang/ira/fa"
    )

    private val extractLock = Any()

    /**
     * Fast, zero-network check that all required Khadijah model, vocoder, token, and
     * Persian/English eSpeak NG data files are packaged inside the APK assets.
     */
    fun areBundledAssetsPresent(context: Context): Boolean {
        val assets = context.assets ?: return false
        return REQUIRED_CORE_ASSETS.all { path ->
            runCatching {
                assets.open(path).use { true }
            }.getOrDefault(false)
        }
    }

    /**
     * Extracts `assets/tts/khadijah/espeak-ng-data` to `filesDir/tts/khadijah/espeak-ng-data`
     * once per version stamp. Must only be called on a background I/O/worker thread.
     */
    fun ensureEspeakDataExtracted(context: Context): File {
        synchronized(extractLock) {
            val targetRoot = File(context.filesDir, ASSET_ESPEAK_DIR)
            val stamp = File(targetRoot, EXTRACT_VERSION_MARKER)
            if (stamp.isFile && File(targetRoot, "phondata").isFile && File(targetRoot, "fa_dict").isFile) {
                return targetRoot
            }
            val stagingDir = File(context.filesDir, "$ASSET_ESPEAK_DIR.tmp")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            copyAssetTree(context.assets, ASSET_ESPEAK_DIR, stagingDir)
            File(stagingDir, EXTRACT_VERSION_MARKER).writeText("v1")

            if (targetRoot.exists()) targetRoot.deleteRecursively()
            targetRoot.parentFile?.mkdirs()
            if (!stagingDir.renameTo(targetRoot)) {
                // Fallback copy if rename across mount points ever occurs
                stagingDir.copyRecursively(targetRoot, overwrite = true)
                stagingDir.deleteRecursively()
            }
            return targetRoot
        }
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, destDir: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetTree(assets, "$assetPath/$child", File(destDir, child))
        }
    }
}
