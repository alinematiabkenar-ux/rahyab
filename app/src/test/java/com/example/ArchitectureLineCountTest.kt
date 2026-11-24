package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM R15 architecture guardrail ensuring all non-exempt Kotlin source files under
 * `app/src/main/java` remain <= 450 lines. Also invoked from [StartupAndArchitectureSafetyTest].
 */
class ArchitectureLineCountTest {

    @Test
    fun noNonExemptKotlinSourceExceeds450Lines() {
        val violations = findLineCountViolations()
        assertTrue(
            "Found non-exempt Kotlin source files exceeding $MAX_LINES lines:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    companion object {
        const val MAX_LINES = 450

        /**
         * Exempt files: string tables + the 9 DEFERRED Phase R files (R6–R14).
         */
        val EXEMPT_FILE_NAMES: Set<String> = setOf(
            // String tables
            "AppStrings.kt",
            "EnglishStrings.kt",
            "PersianStrings.kt",
            // DEFERRED R6–R14 files
            "SettingsAndAboutScreen.kt",
            "MapLibreMapContainer.kt",
            "NavigationHudComponents.kt",
            "DownloadMapsScreen.kt",
            "OfflinePlaceSearchEngine.kt",
            "DiagnosticsScreen.kt",
            "MainActivity.kt",
            "LaneGuidanceBannerRow.kt",
            "RahYabViewModel.kt"
        )

        fun locateMainJavaDir(): File {
            val candidates = listOf(
                File("src/main/java"),
                File("app/src/main/java")
            )
            return candidates.firstOrNull { it.isDirectory }
                ?: error("Could not locate app/src/main/java from working directory ${File(".").absolutePath}")
        }

        fun findLineCountViolations(maxLines: Int = MAX_LINES): List<String> {
            val root = locateMainJavaDir()
            val kotlinFiles = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
            require(kotlinFiles.size >= 30) {
                "Expected at least 30 Kotlin files in ${root.absolutePath}, found ${kotlinFiles.size}"
            }
            val violations = mutableListOf<String>()
            for (file in kotlinFiles) {
                if (file.name in EXEMPT_FILE_NAMES) continue
                val lineCount = file.readLines().size
                if (lineCount > maxLines) {
                    val rel = file.relativeTo(root).path
                    violations.add("$rel: $lineCount lines (limit $maxLines)")
                }
            }
            return violations.sorted()
        }
    }
}
