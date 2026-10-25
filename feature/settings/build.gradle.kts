plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.settings"
}

dependencies {
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
}
