plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
}

android {
    namespace = "whitewallet.core.designsystem"
}

dependencies {
    api(project(":core:brand-ui"))
    implementation(project(":core:model"))
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
}
