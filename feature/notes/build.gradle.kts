plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.notes"
}

dependencies {
    testImplementation(testFixtures(project(":core:wallet")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
