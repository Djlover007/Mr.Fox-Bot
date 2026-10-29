plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.amazonmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.amazonmonitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 43
        versionName = "5.2"
    }

    // Fester Signaturschlüssel aus GitHub-Secrets (siehe README). Ohne Secrets wird wie bisher
    // mit einem zufälligen Debug-Schlüssel signiert – dann sind Updates nur mit Neuinstallation möglich.
    val ksPath = System.getenv("SIGNING_KEYSTORE_FILE")
    val ksFile = ksPath?.let { file(it) }
    val fixedSigning = if (ksFile != null && ksFile.exists()) {
        signingConfigs.create("fixed") {
            storeFile = ksFile
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        debug {
            if (fixedSigning != null) signingConfig = fixedSigning
        }
        release {
            isMinifyEnabled = false
            if (fixedSigning != null) signingConfig = fixedSigning
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jsoup:jsoup:1.18.1")
}
