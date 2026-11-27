import java.net.URI
import java.security.MessageDigest

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
        debug {
            // Emulator tests: the detection library has no 64-bit emulator (x86_64) code, only 32-bit
            // x86, so test builds leave x86_64 out and the emulator runs the app as 32-bit.
            ndk { abiFilters += listOf("x86", "arm64-v8a", "armeabi-v7a") }
        }
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
        // (file in assets, address, sha256): the object detector, and the picture classifier for
        // the wildlife check (foxes, kangaroos, rabbits, snakes, lizards). Both MediaPipe's, int8.
        val models = listOf(
            Triple("detect.tflite", "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite", "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896"),
            Triple("wildlife.tflite", "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/int8/1/efficientnet_lite0.tflite", "bc2ffe19c1118de0c0c2a9088992da5589722656e0fba81421385300a4a34b16"),
        )
        fun sha(x: File) = MessageDigest.getInstance("SHA-256").digest(x.readBytes()).joinToString("") { "%02x".format(it) }
        for ((name, url, want) in models) {
            val f = file("src/main/assets/$name")
            if (f.exists() && sha(f) == want) continue
            f.parentFile.mkdirs()
            val tmp = File(f.path + ".part")
            URI(url).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            check(sha(tmp) == want) { "$name: checksum doesn't match" }
            tmp.renameTo(f)
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchModel) }
