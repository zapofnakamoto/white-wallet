plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.claim"
}

dependencies {
    implementation(project(":core:scanner"))
}
