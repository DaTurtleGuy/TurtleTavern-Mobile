import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Signing credentials live in app/signing/signing.properties
// (storeFile / storePassword / keyAlias / keyPassword) — an artifact to keep
// out of any published repo; without it the release variant skips signing.
val signingProps = Properties().apply {
    val f = rootProject.file("app/signing/signing.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    signingConfigs {
        create("release") {
            storeFile = signingProps.getProperty("storeFile")?.let { file(it) }
            storePassword = signingProps.getProperty("storePassword") ?: ""
            keyAlias = signingProps.getProperty("keyAlias") ?: ""
            keyPassword = signingProps.getProperty("keyPassword") ?: ""
        }
    }

    namespace = "com.daturtleguy.turtletavern"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.daturtleguy.turtletavern"
        minSdk = 30
        targetSdk = 37
        versionCode = 12
        versionName = "0.1.0-beta"

        // arm64 only: Android's x86_64 seccomp blocks the stat syscall family
        // used by SQLite, so x86_64 devices/emulators are unsupported.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(files("libs/gotavern.aar"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
