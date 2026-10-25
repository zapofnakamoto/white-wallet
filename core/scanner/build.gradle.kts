plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
}

android {
    namespace = "whitewallet.core.scanner"
}

dependencies {
    // Scanning without Google Play Services (no ML Kit): CameraX frames decoded by ZXing.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
