plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

import java.io.ByteArrayOutputStream

// ── Auto versioning from git + date ──────────────────────────
// When built via agent-stack controller, version props are passed as -P flags.
// When running standalone (e.g. Android Studio), fall back to git.
val repoRoot: String = project.rootDir.parent  // up from android/ to talaria/

val providedVersionCode = project.findProperty("versionCode")?.toString()
val providedVersionName = project.findProperty("versionName")?.toString()

val gitCommitCount: Int = providedVersionCode?.toIntOrNull() ?: try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", repoRoot, "rev-list", "--count", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim().toInt()
    }
} catch (_: Exception) { 1 }

val gitCommitHash: String = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", repoRoot, "rev-parse", "--short", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim()
    }
} catch (_: Exception) { "unknown" }

val buildDate: String = project.findProperty("buildDate")?.toString() ?: try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", repoRoot, "log", "-1", "--format=%cd", "--date=format:%Y%m%d")
            standardOutput = baos
        }
        baos.toString().trim()
    }
} catch (_: Exception) { "00000000" }

val buildTime: String = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", repoRoot, "log", "-1", "--format=%cd", "--date=format:%H%M")
            standardOutput = baos
        }
        baos.toString().trim()
    }
} catch (_: Exception) { "0000" }

// Final version name: prefer controller-provided, else compose from git
val versionNameStr = providedVersionName ?: "${gitCommitCount}.${buildDate}.${buildTime}-${gitCommitHash}"

android {
    namespace = "com.reedsutliff.talaria"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reedsutliff.talaria"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = versionNameStr

        // Cloudflare Access service token — set via local.properties or env
        buildConfigField("String", "CF_ACCESS_CLIENT_ID", "\"${project.findProperty("cfAccessClientId") ?: ""}\"")
        buildConfigField("String", "CF_ACCESS_CLIENT_SECRET", "\"${project.findProperty("cfAccessClientSecret") ?: ""}\"")
        buildConfigField("String", "TALARIA_WS_URL", "\"${project.findProperty("talariaWsUrl") ?: "wss://talaria.ffilt.us/v1/ptt"}\"")
        // Auto versioning fields
        buildConfigField("int", "VERSION_CODE", "$gitCommitCount")
        buildConfigField("String", "VERSION_NAME", "\"${versionNameStr}\"")
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
        buildConfigField("String", "BUILD_DATE", "\"${buildDate}T${buildTime}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // OkHttp WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ExoPlayer / Media3 for audio playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
