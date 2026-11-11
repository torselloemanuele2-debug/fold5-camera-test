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
        versionCode = 2
        versionName = "2.0"
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
