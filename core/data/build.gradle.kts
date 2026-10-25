plugins {
    id("whitewallet.android-library")
    id("whitewallet.hilt")
}

android {
    namespace = "whitewallet.core.data"
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:wallet"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // The database is encrypted at rest (brief §10); its passphrase lives in the Keystore, see DatabaseKey.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.datastore.preferences)
}

// On a device (./gradlew :core:data:connectedDebugAndroidTest): the Keystore and SQLCipher can't run on the JVM.
dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
}
