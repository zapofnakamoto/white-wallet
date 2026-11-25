// Pure Kotlin/JVM module: no Android. Used by protocol/* and the platform-free core modules.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Android lint's Kotlin checks, run with the app's lint (`just lint`), which looks at every module it uses.
    id("com.android.lint")
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

configureTestJvms()

configureKotlinWarnings()

if (path != ":brand-code") apply(plugin = "whitewallet.detekt")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // A test that hangs fails by its name, long before the run's own limit. The slowest take about a minute; a regtest key rotation waits
    // up to two for its mint.
    systemProperty("junit.jupiter.execution.timeout.default", "10 m")
    // The mock mint tests run against the conformance suite that dev/conformance.sh prepares in .conformance/; the
    // lockfile it installed from is an input, so they run again once it is there.
    val conformance = rootDir.resolve(".conformance")
    inputs.property(
        "conformance",
        providers
            .fileContents(rootProject.layout.projectDirectory.file(".conformance/node_modules/.package-lock.prepared"))
            .asText
            .orElse(""),
    )
    environment("LNURLCASH_CONFORMANCE", conformance.path)
    // LNURLCASH_REGTEST=1: the local regtest stack from dev/regtest is up (real mint tests). Read through a provider
    // so the configuration cache tracks it.
    val regtest = providers.environmentVariable("LNURLCASH_REGTEST")
    inputs.property("LNURLCASH_REGTEST", regtest.orElse(""))
    regtest.orNull?.let { environment("LNURLCASH_REGTEST", it) }
    // A regtest result is about the running stack, which Gradle can't see: never reuse one.
    if (regtest.isPresent) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("regtest tests depend on the running stack") { true }
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
