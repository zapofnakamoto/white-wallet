plugins {
    id("whitewallet.android-feature")
}

android {
    namespace = "whitewallet.feature.onboarding"
}

dependencies {
    // The setup's view models over a real Wallet, against the regtest mints (Regtest*FlowTest).
    testImplementation(testFixtures(project(":core:wallet")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.secp256k1.jni.jvm)
}

tasks.withType<Test>().configureEach {
    // LNURLCASH_REGTEST=1: the regtest stack from dev/regtest is up. A result is about the running stack, which Gradle
    // can't see: never reuse one. REGTEST_PROTOCOL says which stack (see whitewallet.jvm-library).
    val regtest = providers.environmentVariable("LNURLCASH_REGTEST")
    inputs.property("LNURLCASH_REGTEST", regtest.orElse(""))
    regtest.orNull?.let { environment("LNURLCASH_REGTEST", it) }
    if (regtest.isPresent) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("regtest tests depend on the running stack") { true }
    }
    val protocol = providers.environmentVariable("REGTEST_PROTOCOL")
    inputs.property("REGTEST_PROTOCOL", protocol.orElse(""))
    protocol.orNull?.let { environment("REGTEST_PROTOCOL", it) }
}
