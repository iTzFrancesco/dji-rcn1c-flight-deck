plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.drone.rcn1cbridge"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.drone.rcn1cbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "3.2.0"

        ndk {
            // Keep the APK small: real Android phones/tablets only.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            aidl.srcDirs("src")
            res.srcDirs("res")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Release-compatible builds restore the stable key into ~/.android/debug.keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
