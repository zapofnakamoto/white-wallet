// The previews of every screen module, drawn in a brand's design: see whitewallet.screenshots.
plugins {
    id("whitewallet.screenshots")
}

android {
    namespace = "whitewallet.screenshots"
}

dependencies {
    implementation(project(":core:brand-ui"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:wallet"))
    implementation(project(":feature:claim"))
    implementation(project(":feature:home"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:receive"))
    implementation(project(":feature:send"))
    implementation(project(":feature:settings"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.preview.scanner)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}
