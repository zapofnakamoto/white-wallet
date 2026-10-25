plugins {
    id("whitewallet.jvm-library")
    id("whitewallet.serialization")
}

dependencies {
    api(project(":protocol:lnurl"))
    api(project(":protocol:lnurlcash-core"))
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
}
