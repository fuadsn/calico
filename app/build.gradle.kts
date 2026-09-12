import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hackathon.calico"
    compileSdk = 36
    ndkVersion = "29.0.13846066"
    defaultConfig {
        applicationId = "com.hackathon.calico"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release",
                    "-DFETCHCONTENT_BASE_DIR=${rootProject.layout.buildDirectory.get().asFile.invariantSeparatorsPath}/native-deps")
                targets += "calico_coach"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // .task model must not be compressed or MediaPipe can't mmap it
    androidResources { noCompress += "task" }
}

val wakeLibrary = rootProject.layout.buildDirectory.file("voice-deps/sherpa.aar")
val prepareWakeLibrary by tasks.registering {
    outputs.file(wakeLibrary)
    doLast {
        val file = wakeLibrary.get().asFile
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, "sherpa.download")
        if(file.exists()) {
            val existing=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if(existing=="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96") return@doLast
        }
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL().openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        val hash = MessageDigest.getInstance("SHA-256").digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96") { "Wake library checksum mismatch" }
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }
}

dependencies {
    implementation(files(wakeLibrary).builtBy(prepareWakeLibrary))
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation(project(":roomscan"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
