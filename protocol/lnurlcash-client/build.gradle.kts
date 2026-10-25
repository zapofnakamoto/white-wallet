plugins {
    id("whitewallet.jvm-library")
}

dependencies {
    api(project(":protocol:lnurlcash-protocol"))
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
