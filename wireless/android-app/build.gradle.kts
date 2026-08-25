plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.drone.rcn1cbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drone.rcn1cbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "3.3.0-beta12"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("res")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getenv("RCN1C_KEYSTORE_PATH")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
