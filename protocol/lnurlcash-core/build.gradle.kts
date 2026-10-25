plugins {
    id("whitewallet.jvm-library")
}

dependencies {
    api(libs.bitcoin.kmp)
    // The JNI binding is chosen by the consumer: secp256k1-jni-jvm here and in tests, -jni-android in the app.
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
}
