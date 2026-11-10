plugins {
    id("com.android.application")
}

android {
    namespace = "app.screentranslator"
    // Same build setup as Google's AI Edge Gallery app, which uses the same on-device AI library.
    compileSdk { this.version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "app.screentranslator"
        minSdk = 31          // Android 12+, required by the on-device AI library
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
        ndk {
            abiFilters += "arm64-v8a"   // all Android 12+ phones; keeps the APK small
        }
    }

    // A fixed debug key, so a newer build installs over an older one
    // instead of asking you to uninstall first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/liblitertlm_jni.so")
        }
    }
}

dependencies {
    // On-device AI: runs Google's Gemma 4 model on the phone (LiteRT-LM).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // On-device text recognition for the screen (reads Korean and Latin-alphabet text).
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
