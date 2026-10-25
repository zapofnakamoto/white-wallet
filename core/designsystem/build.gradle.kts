plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
}

android {
    namespace = "whitewallet.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
}
