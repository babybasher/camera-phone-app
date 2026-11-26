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
    // The detection model is read straight from the app file, so it must not be compressed.
    androidResources { noCompress += "tflite" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // "What moved": on-phone object detection (no internet; the model is in the app).
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}

// The detection model (EfficientDet-Lite2, int8, COCO), fetched once at build time and checked
// against its known checksum; kept out of git (app/src/main/assets/detect.tflite).
val fetchModel = tasks.register("fetchModel") {
    doLast {
        val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite"
        val want = "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896"
        val f = file("src/main/assets/detect.tflite")
        fun sha(x: File) = java.security.MessageDigest.getInstance("SHA-256").digest(x.readBytes()).joinToString("") { "%02x".format(it) }
        if (f.exists() && sha(f) == want) return@doLast
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".part")
        java.net.URI(url).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        check(sha(tmp) == want) { "detection model: checksum doesn't match" }
        tmp.renameTo(f)
    }
}
tasks.named("preBuild") { dependsOn(fetchModel) }
