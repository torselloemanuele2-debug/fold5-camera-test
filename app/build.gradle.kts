plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.camtest.fold"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.camtest.fold"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    val chiave = rootProject.file("keystore.p12")
    if (chiave.exists()) {
        signingConfigs {
            create("fissa") {
                storeFile = chiave
                storePassword = "fotoscenari"
                keyAlias = "fotoscenari"
                keyPassword = "fotoscenari"
                storeType = "PKCS12"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("fissa")
            }
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
    // Riconoscimento della persona per l'effetto ritratto (modello incluso nell'app).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
