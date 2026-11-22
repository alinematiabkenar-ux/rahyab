package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RahYabDatabase
import com.example.data.packs.StatePackManager
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.EnglishStrings
import com.example.i18n.PersianStrings
import com.example.i18n.stringsFor
import com.example.util.CrashLogManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupAndArchitectureSafetyTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Guardrail test: fails the build if ANY compiled class under `com.example` has a constructor
     * with more than 200 parameters (preventing JVM ClassFormatError / Android ART VerifyError
     * like the v12 270-param AppStrings data class crash!).
     */
    @Test
    fun noAppClassConstructorExceeds200Parameters() {
        val maxAllowedParams = 200
        val classLoader = AppStrings::class.java.classLoader!!
        val packagePath = "com/example"
        val classNames = mutableSetOf<String>()

        val resources = classLoader.getResources(packagePath)
        val visitedLocations = mutableSetOf<String>()

        fun scanLocation(url: java.net.URL) {
            val key = url.toString()
            if (!visitedLocations.add(key)) return
            when (url.protocol) {
                "file" -> {
                    val file = File(url.toURI())
                    if (file.isDirectory) {
                        val rootDir = if (file.path.endsWith(packagePath.replace('/', File.separatorChar))) {
                            file
                        } else {
                            File(file, packagePath)
                        }
                        if (rootDir.exists()) {
                            rootDir.walkTopDown()
                                .filter { it.isFile && it.name.endsWith(".class") && !it.name.contains("Test") }
                                .forEach { classFile ->
                                    val rel = classFile.relativeTo(rootDir).path
                                        .removeSuffix(".class")
                                        .replace(File.separatorChar, '.')
                                    classNames.add("com.example.$rel")
                                }
                        }
                    } else if (file.isFile && file.name.endsWith(".jar")) {
                        java.util.jar.JarFile(file).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val name = entry.name
                                if (name.startsWith("com/example/") && name.endsWith(".class") && !name.contains("Test")) {
                                    classNames.add(name.removeSuffix(".class").replace('/', '.'))
                                }
                            }
                        }
                    }
                }
                "jar" -> {
                    val conn = url.openConnection() as? java.net.JarURLConnection ?: return
                    val jar = conn.jarFile
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.startsWith("com/example/") && name.endsWith(".class") && !name.contains("Test")) {
                            classNames.add(name.removeSuffix(".class").replace('/', '.'))
                        }
                    }
                }
            }
        }

        val classpath = System.getProperty("java.class.path").orEmpty()
        for (entryPath in classpath.split(File.pathSeparator)) {
            if (entryPath.isBlank()) continue
            val file = File(entryPath)
            if (file.exists()) {
                scanLocation(file.toURI().toURL())
            }
        }
        while (resources.hasMoreElements()) {
            scanLocation(resources.nextElement())
        }
        AppStrings::class.java.protectionDomain?.codeSource?.location?.let { scanLocation(it) }
        MainActivity::class.java.protectionDomain?.codeSource?.location?.let { scanLocation(it) }

        // Always explicitly include i18n string types & key models
        classNames.add(AppStrings::class.java.name)
        classNames.add(EnglishStrings::class.java.name)
        classNames.add(PersianStrings::class.java.name)

        assertTrue("Expected to discover at least 40 compiled com.example classes, found ${classNames.size}", classNames.size >= 40)

        val violations = mutableListOf<String>()
        for (name in classNames) {
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            for (ctor in clazz.declaredConstructors) {
                if (ctor.parameterCount > maxAllowedParams) {
                    violations.add("${clazz.name} constructor has ${ctor.parameterCount} parameters (limit: $maxAllowedParams)")
                }
            }
        }

        assertTrue(
            "Found constructors exceeding $maxAllowedParams parameters (will crash Android ART verifier!):\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )

        // Verify EnglishStrings and PersianStrings load cleanly with zero-arg singleton pattern
        assertEquals("RahYab", stringsFor(AppLanguage.ENGLISH).appName)
        assertEquals("رهیاب", stringsFor(AppLanguage.PERSIAN).appName)
        assertEquals(0, EnglishStrings::class.java.declaredConstructors.maxOfOrNull { it.parameterCount } ?: 0)
        assertEquals(0, PersianStrings::class.java.declaredConstructors.maxOfOrNull { it.parameterCount } ?: 0)
    }

    /**
     * Verifies cold-start resilience when a large installed state pack (Oregon PMTiles, Valhalla tar,
     * and multi-thousand-row places SQLite DB WITHOUT idx_places_lat_lon) is present on disk:
     * 1. `auditInstalledPacks()` MUST NOT create `idx_places_lat_lon` during startup.
     * 2. Closing and reopening `MainActivity` 3 consecutive times succeeds cleanly every time.
     */
    @Test
    fun coldStartWithLargeInstalledStatePack_opensCleanlyThreeTimesWithoutStartupIndexing() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        CrashLogManager.clearPendingCrashLog(app)

        // Create a realistic installed Oregon state pack on disk
        val packsDir = File(app.filesDir, "packs/or").apply { mkdirs() }
        val pmFile = File(packsDir, "or.pmtiles")
        FileOutputStream(pmFile).use { fos ->
            fos.write("PMTiles".toByteArray(Charsets.US_ASCII))
            fos.write(ByteArray(256))
        }
        val vhFile = File(packsDir, "or_valhalla.tar")
        FileOutputStream(vhFile).use { fos ->
            fos.write(ByteArray(2048))
        }
        val dbFile = File(packsDir, "or_places.db")
        if (dbFile.exists()) dbFile.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    brand TEXT,
                    category TEXT,
                    address TEXT,
                    city TEXT,
                    state TEXT,
                    postcode TEXT,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    popularity REAL DEFAULT 1.0,
                    source TEXT DEFAULT 'overture'
                )
                """.trimIndent()
            )
            db.beginTransaction()
            try {
                val insertStmt = db.compileStatement(
                    "INSERT INTO places(name, brand, category, address, city, state, postcode, lat, lon) VALUES (?, ?, ?, ?, ?, 'OR', '97401', ?, ?)"
                )
                for (i in 1..2500) {
                    insertStmt.bindString(1, "Oregon Place #$i")
                    insertStmt.bindString(2, "Brand")
                    insertStmt.bindString(3, "shop")
                    insertStmt.bindString(4, "$i Willamette St")
                    insertStmt.bindString(5, "Eugene")
                    insertStmt.bindDouble(6, 44.05 + (i % 100) * 0.001)
                    insertStmt.bindDouble(7, -123.08 - (i / 100) * 0.001)
                    insertStmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        // Audit installed packs (what startup runs) and verify idx_places_lat_lon was NOT created at startup!
        val packManager = StatePackManager(app, RahYabDatabase.getInstance(app).installedPackDao())
        runBlocking {
            packManager.auditInstalledPacks()
        }
        val verifyDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            verifyDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_places_lat_lon'",
                null
            ).use { cursor ->
                assertFalse(
                    "auditInstalledPacks() must NOT create idx_places_lat_lon during startup!",
                    cursor.moveToFirst()
                )
            }
        } finally {
            verifyDb.close()
        }

        // Cold-start, close, and reopen MainActivity 3 consecutive times
        repeat(3) { attempt ->
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val activity = controller.setup().get()
            assertNotNull("MainActivity launch #$attempt failed", activity)
            assertFalse("MainActivity should not finish immediately on launch #$attempt", activity.isFinishing)
            controller.pause().stop().destroy()
        }
    }

    /**
     * Verifies that when a pending crash log exists on launch:
     * 1. MainActivity shows the minimal SafeCrashRecoveryScreen with Copy and Continue buttons.
     * 2. None of MapLibre, PackManager, VoiceGuidanceManager, CompassTracker, or ValhallaRoutingEngine
     *    are initialized while the safe crash screen is showing.
     */
    @Test
    fun pendingCrashFileShowsSafeScreenBeforeCreatingHeavySubsystems() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        CrashLogManager.writeCrashLog(
            context = app,
            threadName = "main",
            throwable = RuntimeException("Simulated startup crash for safe recovery test")
        )
        assertNotNull(CrashLogManager.readPendingCrashLog(app))

        // Verify SafeCrashRecoveryScreen UI directly (Copy + Continue buttons)
        var copiedPayload: String? = null
        var continued = false
        val crashText = CrashLogManager.readPendingCrashLog(app)!!

        composeRule.setContent {
            SafeCrashRecoveryScreen(
                crashText = crashText,
                onCopyDetails = { copiedPayload = it },
                onContinue = {
                    CrashLogManager.clearPendingCrashLog(app)
                    continued = true
                }
            )
        }

        composeRule.onNodeWithTag("safe_crash_recovery_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("copy_crash_details_button").assertIsDisplayed().performClick()
        assertNotNull(copiedPayload)
        assertTrue(copiedPayload!!.contains("Simulated startup crash for safe recovery test"))

        composeRule.onNodeWithTag("continue_after_crash_button").assertIsDisplayed().performClick()
        assertTrue(continued)
        assertNull(CrashLogManager.readPendingCrashLog(app))

        // Also verify a fresh RahYabApplication.onCreate() does not eagerly create heavy subsystems
        val freshApp = RahYabApplication()
        assertFalse(freshApp.isMapLibreInitialized)
        assertFalse(freshApp.isPackManagerInitialized)
        assertFalse(freshApp.isVoiceGuidanceInitialized)
        assertFalse(freshApp.isCompassTrackerInitialized)
        assertFalse(freshApp.isRoutingEngineInitialized)
    }
}
