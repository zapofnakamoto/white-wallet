plugins {
    id("whitewallet.jvm-library")
}

// wallet.properties as the data class WalletSettings (see "Wallet settings" in docs/DEVELOPMENT.md).
val walletSettings =
    tasks.register<GenerateWalletSettings>("generateWalletSettings") {
        source.set(rootProject.layout.projectDirectory.file("wallet.properties"))
        overrides.set(walletSettingOverrides())
        outputDir.set(layout.buildDirectory.dir("generated/walletSettings"))
    }

kotlin.sourceSets.named("main") { kotlin.srcDir(walletSettings.map { it.outputDir }) }

dependencies {
    api(project(":core:model"))
    api(project(":core:brand-api"))
    api(project(":protocol:lnurlcash-client"))
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(project(":protocol:lnurlcash-testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
