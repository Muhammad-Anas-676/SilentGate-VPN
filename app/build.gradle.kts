plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional built-in primary server (one or more share links separated by spaces). Never hardcode it in source:
// pass -PsgPrimaryServer="vless://..." or set the SG_PRIMARY_SERVER env var / GitHub secret. Empty = none.
val sgPrimaryServer: String = ((project.findProperty("sgPrimaryServer") as String?) ?: System.getenv("SG_PRIMARY_SERVER") ?: "")
    .replace("\r", " ").replace("\n", " ").trim()

android {
    namespace = "com.silentgatevpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silentgatevpn.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.2.1"
        buildConfigField("String", "PRIMARY_SERVER", "\"" + sgPrimaryServer.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
