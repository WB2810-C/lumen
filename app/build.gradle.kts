import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Jarvis's API keys never go in the app or on GitHub: you type them on the phone remote and the TV
// keeps them encrypted (see JarvisKeys.kt).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// Optional free AI on your laptop (Ollama), as:  ollama.host=192.168.1.20
// (or, for the cloud build, lumen.ollamaHost=192.168.1.20 in gradle.properties: an address on your own Wi-Fi isn't secret)
val ollamaHost: String = (localProperties.getProperty("ollama.host")
    ?: (project.findProperty("lumen.ollamaHost") as String?) ?: "").trim()

android {
    namespace = "com.wolfram.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wolfram.lumen"
        minSdk = 26          // Android TV 8.0 and newer
        targetSdk = 35
        versionCode = 33
        versionName = "21.4.3"
        buildConfigField("String", "OLLAMA_HOST", "\"$ollamaHost\"")
        // The GitHub build number, so PlaizierOS knows when a newer build is out (self-updates).
        buildConfigField("int", "BUILD_NUMBER", System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key (app/lumen.jks), so every new build installs over the old one and keeps your settings.
    signingConfigs {
        create("lumen") {
            storeFile = file("lumen.jks")
            storePassword = "lumen-tv"
            keyAlias = "lumen"
            keyPassword = "lumen-tv"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lumen")
        }
        release {
            // Speed: R8 removes what isn't used and tightens the rest, which makes the app start and scroll faster.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lumen")
        }
    }

    // A personal app: don't stop the build over store-only lint warnings.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The home screen tests run on a pretend 1920 x 1080 TV (Robolectric) and need the app's resources.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Draws the QR code for the phone remote.
    implementation("com.google.zxing:core:3.5.3")

    // Talks to Apple's calendar server (CalDAV needs PROPFIND and REPORT requests).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Installs the baseline profile (src/main/baseline-prof.txt): faster from the very first start.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Your photos as scenes: finds the person or thing in front, on the TV itself (no internet).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // Unit tests for the pure logic (run by GitHub before every build).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Tests that drive the home screen with the remote's arrow keys.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// When a test fails, the build log shows exactly which check and why.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
