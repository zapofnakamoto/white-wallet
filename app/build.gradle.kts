plugins {
    id("whitewallet.android-application")
    id("whitewallet.android-compose")
    id("whitewallet.hilt")
}

android {
    namespace = "whitewallet.app"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:wallet"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:receive"))
    implementation(project(":feature:claim"))
    implementation(project(":feature:send"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    // Native secp256k1 for bitcoin-kmp on Android.
    runtimeOnly(libs.secp256k1.jni.android)

    // On a device (./gradlew :app:connectedDebugAndroidTest): the native secp256k1 the app ships.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(project(":protocol:lnurlcash-core"))
    androidTestImplementation(libs.bitcoin.kmp)
}
