import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.baselineprofile)
}

// Load release signing credentials from keystore.properties (gitignored) if present.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.fenyx.jtv"
    compileSdk = 37          // Required by lifecycle 2.11 / Compose BOM 2026.06; compile-only, does not change runtime
    defaultConfig {
        applicationId = "com.fenyx.jtv"
        minSdk = 24          // Android 7.0 — comfortably covers the user's Android 10 TV
        targetSdk = 36       // Android 16 (latest)
        versionCode = 209   // v2 track (2.0-beta.x = 200..); stable 1.5.x stays below
        versionName = "2.0-beta.10"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the release keystore when credentials are available; otherwise fall back to debug signing.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    // Only ship the UI languages JTV supports; drops ~80 locales of AndroidX/Material strings.
    androidResources {
      localeFilters += listOf("en", "hi")
    }

    // Hindi must stay complete: the release build's lint-vital pass fails on an untranslated string.
    lint {
      fatal += listOf("MissingTranslation", "ExtraTranslation", "StringFormatMatches", "StringFormatCount")
    }

    packaging {
      resources {
        excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // Already in the APK via lifecycle-runtime (runtime scope); declared so LAN sync can use ProcessLifecycleOwner.
  implementation(libs.androidx.lifecycle.process)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // TV Compose. Note: androidx.tv.foundation is deprecated — its lazy layouts were folded into
  // androidx.compose.foundation 1.7+, which we use directly (LazyColumn/LazyVerticalGrid +
  // Modifier.focusRestorer). Only androidx.tv.material3 is needed for TV-tuned components.
  implementation(libs.androidx.tv.material)

  // Media3 ExoPlayer
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.datasource)
  // Streams go over one shared OkHttp client (HTTP/2, pooled; data/Net.kt), the same one Coil uses.
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.okhttp)
  implementation(libs.androidx.media3.ui)

  // DataStore
  implementation(libs.androidx.datastore.preferences)

  // Image Loading
  implementation(libs.coil.compose)

  // Baseline Profile: ProfileInstaller applies the AOT profile at first run (AGP embeds the profile
  // generated by the :baselineprofile module into the release APK/AAB). Speeds up launch → browse →
  // play on weak TV CPUs.
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":baselineprofile"))
}

// Dev convenience: after every RELEASE build (fast, minified, release-signed), copy the APK to the folder
// named by the `jtvLabApkDir` Gradle property (set per machine in ~/.gradle/gradle.properties, not in the
// repo) as JTV-v<versionName>-build<N>.apk, where N counts up on this machine (~/.gradle/jtv-build-number).
providers.gradleProperty("jtvLabApkDir").orNull?.let { labDir ->
  tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
      val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
      if (apk.exists()) {
        val counter = File(System.getProperty("user.home"), ".gradle/jtv-build-number")
        val n = (counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        counter.writeText(n.toString())
        val dest = file(labDir).apply { mkdirs() }
        val out = dest.resolve("JTV-v${android.defaultConfig.versionName}-build$n.apk")
        apk.copyTo(out, overwrite = true)
        println("Release APK copied to $out")
      }
    }
  }
}
