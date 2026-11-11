plugins {
    id("whitewallet.jvm-library")
    // The in-memory stores (src/testFixtures), for this module's tests and for a feature's tests that run a real Wallet.
    `java-test-fixtures`
}

// wallet.properties as the data class WalletSettings (see "Wallet settings" in docs/DEVELOPMENT.md).
val walletSettings =
    tasks.register<GenerateWalletSettings>("generateWalletSettings") {
        source.set(rootProject.layout.projectDirectory.file("wallet.properties"))
        overrides.set(walletSettingOverrides())
        outputDir.set(layout.buildDirectory.dir("generated/walletSettings"))
    }

kotlin.sourceSets.named("main") { kotlin.srcDir(walletSettings.map { it.outputDir }) }

// Test classes run side by side; the tests that share the third mint hold ThirdMintLock. The legacy mints can't keep up: one at a time.
val legacyStack = providers.environmentVariable("REGTEST_PROTOCOL").orNull == "legacy"

tasks.named<Test>("test") { maxParallelForks = if (legacyStack) 1 else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4) }

dependencies {
    api(project(":core:model"))
    api(project(":core:brand-api"))
    api(project(":protocol:lnurlcash-client"))
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// `just regtest-fund-test-wallet`: notes for the test phrase on the regtest stack that is up (see FundTestWallet.kt).
tasks.register<JavaExec>("fundTestWallet") {
    description = "Pays notes into an account of the BIP-39 test phrase on the regtest stack"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("whitewallet.core.wallet.FundTestWalletKt")
    workingDir = projectDir
}
