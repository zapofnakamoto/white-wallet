plugins {
    id("whitewallet.jvm-library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:brand-api"))
    api(project(":protocol:lnurlcash-client"))
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
