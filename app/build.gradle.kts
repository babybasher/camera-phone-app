plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "net.homecam.phone"
    compileSdk = 34
    defaultConfig {
        applicationId = "net.homecam.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE") ?: "release.jks")
            storePassword = System.getenv("KS_PASS")
            keyAlias = "camera"
            keyPassword = System.getenv("KS_PASS")
        }
    }
    buildTypes {
        release {
            // Phones and tablets are ARM; leaving out emulator (x86) code halves the download.
            // (Test builds keep it, for the emulator.)
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}
