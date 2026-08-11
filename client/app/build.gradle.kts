import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

val localPropsFile = rootProject.file("local.properties")
val props = Properties().apply {
    if (!localPropsFile.exists()) {
        error("Missing local.properties in the project root. Add BACKEND_URL to file.")
    }
    load(localPropsFile.inputStream())
}

// If values are missing, fail fast with a readable error
val backendUrl = props.getProperty("BACKEND_URL")
    ?: error("local.properties is missing BACKEND_URL")

android {
    namespace = "com.vonage.verify2.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vonage.verify2.test"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose and UI
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Vonage Client SDK
    implementation("com.vonage:client-library:1.0.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.code.gson:gson:2.13.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
