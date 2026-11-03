plugins {
    id("whitewallet.android-application")
    id("whitewallet.android-compose")
    id("whitewallet.hilt")
}

android {
    namespace = "whitewallet.app"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
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

    // On the JVM: what the app hands to Android (the background sync), under Robolectric (JUnit 4, run by the vintage
    // engine).
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    // On a device (./gradlew :app:connectedDebugAndroidTest): the native secp256k1 the app ships.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(project(":protocol:lnurlcash-core"))
    androidTestImplementation(libs.bitcoin.kmp)
}
