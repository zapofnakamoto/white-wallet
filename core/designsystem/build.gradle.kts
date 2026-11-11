plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
}

android {
    namespace = "whitewallet.core.designsystem"
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.directories.add(rootProject.file("build-logic/test-resources").path)
}

dependencies {
    api(project(":core:brand-ui"))
    implementation(project(":core:model"))
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)

    // Component tests on the JVM: Compose UI tests under Robolectric, as in the feature modules.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}
