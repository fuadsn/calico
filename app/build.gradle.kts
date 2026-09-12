plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hackathon.calico"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hackathon.calico"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // .task model must not be compressed or MediaPipe can't mmap it
    androidResources { noCompress += "task" }
}

dependencies {
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    testImplementation("junit:junit:4.13.2")
}
