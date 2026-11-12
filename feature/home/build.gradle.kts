plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.home"
}

dependencies {
    // The Move funds view model over a real Wallet (MoveFundsViewModelTest).
    testImplementation(testFixtures(project(":core:wallet")))
    testImplementation(libs.kotlinx.coroutines.test)
    // A mint's answers, given by the test in its place.
    testImplementation(libs.okhttp)
}
