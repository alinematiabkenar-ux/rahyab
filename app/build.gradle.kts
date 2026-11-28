import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

fun sha256HexOf(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().buffered().use { input ->
    val buf = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n <= 0) break
      digest.update(buf, 0, n)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

fun ensurePinnedArtifact(dest: File, url: String, expectedSha256: String, expectedSize: Long): File {
  if (dest.isFile && dest.length() == expectedSize && sha256HexOf(dest) == expectedSha256) {
    return dest
  }
  dest.parentFile?.mkdirs()
  val tmp = File(dest.parentFile, "${dest.name}.part")
  if (tmp.exists()) tmp.delete()
  var currentUrl = url
  var redirects = 0
  while (true) {
    val conn = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
      instanceFollowRedirects = false
      connectTimeout = 30_000
      readTimeout = 120_000
      setRequestProperty("User-Agent", "RahYab-Gradle-Build/1.0")
    }
    try {
      val code = conn.responseCode
      if (code in listOf(301, 302, 303, 307, 308)) {
        val loc = conn.getHeaderField("Location")
          ?: error("HTTP $code without Location header for $currentUrl")
        currentUrl = URI(currentUrl).resolve(loc).toString()
        redirects += 1
        require(redirects <= 10) { "Too many redirects downloading $url" }
        continue
      }
      require(code in 200..299) { "HTTP $code downloading $currentUrl" }
      conn.inputStream.buffered().use { input ->
        tmp.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
      }
      break
    } finally {
      conn.disconnect()
    }
  }
  val actualSha = sha256HexOf(tmp)
  require(tmp.length() == expectedSize && actualSha == expectedSha256) {
    "Checksum mismatch for ${dest.name}: size=${tmp.length()} (expected $expectedSize), sha256=$actualSha (expected $expectedSha256)"
  }
  if (dest.exists()) dest.delete()
  if (!tmp.renameTo(dest)) {
    tmp.copyTo(dest, overwrite = true)
    tmp.delete()
  }
  return dest
}

abstract class DownloadKhadijahModelTask : DefaultTask() {
  @get:Input abstract val downloadUrl: Property<String>
  @get:Input abstract val expectedSha256: Property<String>
  @get:Input abstract val expectedSizeBytes: Property<Long>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun fetch() {
    val target = File(outputDir.get().asFile, "tts/khadijah/model.onnx")
    if (target.isFile && target.length() == expectedSizeBytes.get() && computeSha256(target) == expectedSha256.get()) {
      return
    }
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.part")
    if (tmp.exists()) tmp.delete()
    var currentUrl = downloadUrl.get()
    var redirects = 0
    while (true) {
      val conn = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 30_000
        readTimeout = 180_000
        setRequestProperty("User-Agent", "RahYab-Gradle-Build/1.0")
      }
      try {
        val code = conn.responseCode
        if (code in listOf(301, 302, 303, 307, 308)) {
          val loc = conn.getHeaderField("Location")
            ?: error("HTTP $code without Location header for $currentUrl")
          currentUrl = URI(currentUrl).resolve(loc).toString()
          redirects += 1
          require(redirects <= 10) { "Too many redirects downloading ${downloadUrl.get()}" }
          continue
        }
        require(code in 200..299) { "HTTP $code downloading $currentUrl" }
        conn.inputStream.buffered().use { input ->
          tmp.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
        }
        break
      } finally {
        conn.disconnect()
      }
    }
    val actualSha = computeSha256(tmp)
    require(tmp.length() == expectedSizeBytes.get() && actualSha == expectedSha256.get()) {
      "Checksum mismatch for model.onnx: size=${tmp.length()}, sha256=$actualSha"
    }
    if (target.exists()) target.delete()
    if (!tmp.renameTo(target)) {
      tmp.copyTo(target, overwrite = true)
      tmp.delete()
    }
  }

  private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

val sherpaOnnxAarFile: File = ensurePinnedArtifact(
  dest = layout.buildDirectory.file("offline-tts/sherpa-onnx-static-link-onnxruntime-1.12.40.aar").get().asFile,
  url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.40/sherpa-onnx-static-link-onnxruntime-1.12.40.aar",
  expectedSha256 = "f6c225258b8528ab7537ff92fbb452df4c68d31632c19f45c8ddb9b56eea238d",
  expectedSize = 38_120_299L
)

val downloadKhadijahModelTask = tasks.register<DownloadKhadijahModelTask>("downloadKhadijahAcousticModel") {
  downloadUrl.set("https://huggingface.co/csukuangfj/matcha-tts-fa_en-khadijah/resolve/main/model.onnx")
  expectedSha256.set("f787627c08c782fc47b7d3a8131cfa25e58eb3a9de117a39723f36cab9c682bc")
  expectedSizeBytes.set(74_274_830L)
  outputDir.set(layout.buildDirectory.dir("generated/offline-tts-assets"))
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
      downloadKhadijahModelTask,
      DownloadKhadijahModelTask::outputDir
    )
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.rahyab.kqzmxv"
    minSdk = 24
    targetSdk = 36
    versionCode = 45
    versionName = "45.0"

    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    checkReleaseBuilds = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  implementation(libs.maplibre.android.sdk)
  implementation(libs.valhalla.mobile)
  implementation(libs.valhalla.models)
  implementation(libs.valhalla.models.config)
  implementation(libs.androidx.sqlite.bundled)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.projected)
  implementation(files(sherpaOnnxAarFile))
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
