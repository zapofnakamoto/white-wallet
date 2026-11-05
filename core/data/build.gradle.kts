plugins {
    id("whitewallet.android-library")
    id("whitewallet.hilt")
}

android {
    namespace = "whitewallet.core.data"
}

ksp {
    arg("room.generateKotlin", "true")
    // One file per database version, kept in git: the migration tests build old databases from them.
    arg("room.schemaLocation", "$projectDir/schemas")
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

// On the JVM: the Room store under Robolectric (without SQLCipher), on its own and on regtest (LNURLCASH_REGTEST=1),
// where the app restarts over the same database.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.getByName("test").resources.directories.add(rootProject.file("build-logic/test-resources").path)
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(kotlin("test-junit"))
    testRuntimeOnly(libs.secp256k1.jni.jvm)
}

tasks.withType<Test>().configureEach {
    val regtest = providers.environmentVariable("LNURLCASH_REGTEST")
    inputs.property("LNURLCASH_REGTEST", regtest.orElse(""))
    regtest.orNull?.let { environment("LNURLCASH_REGTEST", it) }
    // A regtest result is about the running stack, which Gradle can't see: never reuse one.
    if (regtest.isPresent) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("regtest tests depend on the running stack") { true }
    }
    // REGTEST_PROTOCOL=current|legacy: which mint stack is up (dev/regtest/regtest.sh), so protocol-bound tests know.
    val protocol = providers.environmentVariable("REGTEST_PROTOCOL")
    inputs.property("REGTEST_PROTOCOL", protocol.orElse(""))
    protocol.orNull?.let { environment("REGTEST_PROTOCOL", it) }
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

// On a device (./gradlew :core:data:connectedDebugAndroidTest): the Keystore and SQLCipher can't run on the JVM.
dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
}
