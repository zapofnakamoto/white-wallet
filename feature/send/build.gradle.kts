plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.send"
}

dependencies {
    implementation(project(":core:scanner"))
}
