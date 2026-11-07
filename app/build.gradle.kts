import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Your Claude API key lives in local.properties (never in the code), as:  claude.apiKey=sk-ant-...
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val claudeApiKey: String = localProperties.getProperty("claude.apiKey", "").trim()
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
        versionCode = 8
        versionName = "5.0"
        buildConfigField("String", "CLAUDE_API_KEY", "\"$claudeApiKey\"")
        buildConfigField("String", "OLLAMA_HOST", "\"$ollamaHost\"")
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
            isMinifyEnabled = false
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
}
