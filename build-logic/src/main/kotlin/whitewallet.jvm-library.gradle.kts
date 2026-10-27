// Pure Kotlin/JVM module: no Android. Used by protocol/* and the platform-free core modules.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Line and branch coverage of the tests that ran: `just coverage` (build/reports/jacoco/test/html).
    jacoco
}

val libs = versionCatalogs.named("libs")

java {
    // Android consumes these modules, so emit Java 17 bytecode even when built on a newer JDK.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    if (path.startsWith(":protocol")) explicitApi()
}

// Tests run on the JDK 21 toolchain, and secp256k1-kmp-jni-jvm (test-only) requires it.
configurations.named("testRuntimeClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Opt-in integration tests read these; read through providers so the configuration cache tracks them.
    // LNURLCASH_CONFORMANCE: path to a prepared lnurlcash-conformance checkout (mock mint tests).
    // LNURLCASH_REGTEST=1: the local regtest stack from dev/regtest is up (real mint tests).
    for (name in listOf("LNURLCASH_CONFORMANCE", "LNURLCASH_REGTEST")) {
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        value.orNull?.let { environment(name, it) }
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
