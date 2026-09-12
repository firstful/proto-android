// PROTO Android — WebView client for proto.ffilt.us (proto broker UI).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.ByteArrayOutputStream

val gitCommitCount: Int = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", project.rootDir.parent, "rev-list", "--count", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim().toInt()
    }
} catch (_: Exception) { 1 }

val gitCommitHash: String = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", project.rootDir.parent, "rev-parse", "--short", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim()
    }
} catch (_: Exception) { "unknown" }

// ── Release signing (CI-provided env; local builds stay unsigned) ──
val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val ksStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
val ksKeyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "sutliff"
val ksKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ksStorePassword
val hasKeystore = ksFile != null && File(ksFile).exists()

android {
    namespace = "com.reedsutliff.protoapp"
    compileSdk = 36

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(ksFile!!)
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.reedsutliff.protoapp"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "1.0.$gitCommitCount-$gitCommitHash"

        // Bundled Cloudflare Access service token (proto.ffilt.us ONLY — the
        // dedicated Access app scopes this token to that one hostname).
        buildConfigField("String", "CF_ACCESS_CLIENT_ID", "\"${project.findProperty("cfAccessClientId") ?: ""}\"")
        buildConfigField("String", "CF_ACCESS_CLIENT_SECRET", "\"${project.findProperty("cfAccessClientSecret") ?: ""}\"")
        buildConfigField("String", "PROTO_UI_URL", "\"${project.findProperty("protoUiUrl") ?: "https://proto.ffilt.us/"}\"")
        buildConfigField("String", "TELEMETRY_URL", "\"${project.findProperty("telemetryUrl") ?: "https://telemetry.ffilt.us/"}\"")
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
