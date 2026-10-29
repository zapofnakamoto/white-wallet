import java.util.Properties

// The app module, branded from brand/brand.properties and brand/res (see docs/WHITE_LABEL.md).
// Build another brand without editing brand/ with -PbrandDir=/path/to/brand.

plugins {
    id("com.android.application")
}

val brandDir = brandDir()
val brand = brandProperties()

// Keys from before they were grouped, and what they are now. A brand still setting one fails.
val renamedKeys = mapOf(
    "appName" to "app.name", "applicationId" to "app.id", "versionCode" to "app.version.code", "versionName" to "app.version.name",
    "releaseStage" to "app.releaseStage", "appearance" to "app.appearance", "supportUrl" to "app.supportUrl",
    "developerOptions" to "developer.options",
    "defaultMintHost" to "mints.suggestions", "mints.default" to "mints.suggestions (its first entry)",
    "mintSuggestions" to "mints.suggestions", "allowMintChange" to "mints.changeable",
    "showMints" to "mints.visible", "trustedMints" to "mints.trusted",
    "maxAccounts" to "accounts.max", "maxAddresses" to "addresses.max", "lightningAddress" to "addresses.lightning",
    "requireAddress" to "addresses.lightning=required|optional|none",
    "defaultAmountUnit" to "amounts.unit.default", "allowUnitChange" to "amounts.unit.changeable",
    "noteStrategies" to "notes.strategies", "defaultNoteStrategy" to "notes.strategy.default", "notesView" to "notes.view",
    "noteActions" to "notes.actions", "collectAtHomeMint" to "notes.collect.atHomeMint", "collectFeeCap" to "notes.collect.feeCap",
    "historyFilter" to "history.filter",
)
renamedKeys.filterKeys(brand::containsKey).takeIf { it.isNotEmpty() }?.let { old ->
    error("brand.properties uses keys that were renamed (see docs/WHITE_LABEL.md): " + old.entries.joinToString { (from, to) -> "$from → $to" })
}
fun brand(key: String): String = checkNotNull(brand.getProperty(key)) { "brand/brand.properties is missing '$key'" }.trim()

// Release signing, never committed: environment variables (CI) or keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without either, the release APK is built unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(env: String, key: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)
val releaseStoreFile = signing("WW_KEYSTORE", "storeFile")

// Which checkout a debug build came from, shown beside the title: v0.3.0 on the tag, v0.3.0-21-gb1593f9 after it,
// with -dirty for uncommitted changes. Empty outside a git checkout.
val buildLabel = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

android {
    configureAndroid()
    defaultConfig {
        applicationId = brand("app.id")
        targetSdk = COMPILE_SDK
        versionCode = brand("app.version.code").toInt()
        versionName = brand("app.version.name")
        resValue("string", "app_name", brand("app.name"))
        // The mints offered at setup, the first preselected; without mints.custom, the only ones users can use.
        val mintSuggestions = brand.getProperty("mints.suggestions", "").split(',').map(String::trim).filter(String::isNotEmpty)
        val customMints = brand.getProperty("mints.custom", "true").trim().toBooleanStrict()
        check(mintSuggestions.isNotEmpty() || customMints) { "brand.properties: mints.suggestions is empty and mints.custom=false, so there is no mint to set up with" }
        buildConfigField("String", "MINT_SUGGESTIONS", "\"${mintSuggestions.joinToString(",")}\"")
        buildConfigField("boolean", "CUSTOM_MINTS", customMints.toString())
        buildConfigField("boolean", "ALLOW_MINT_CHANGE", brand("mints.changeable"))
        buildConfigField("String", "SUPPORT_URL", "\"${brand("app.supportUrl")}\"")
        buildConfigField("String", "DEFAULT_AMOUNT_UNIT", "\"${brand.getProperty("amounts.unit.default", "bitcoin").trim()}\"")
        buildConfigField("String", "LIGHTNING_ADDRESS", "\"${brand.getProperty("addresses.lightning", "optional").trim()}\"")
        buildConfigField("String", "COLLECT_AT_HOME_MINT", "\"${brand.getProperty("notes.collect.atHomeMint", "off").trim()}\"")
        buildConfigField("int", "COLLECT_FEE_CAP", brand.getProperty("notes.collect.feeCap", "2").trim())
        buildConfigField("String", "TRUSTED_MINTS", "\"" + brand.getProperty("mints.trusted", "").trim() + "\"")
        buildConfigField("String", "NOTES_VIEW", "\"${brand.getProperty("notes.view", "tab").trim()}\"")
        buildConfigField("String", "MAINTENANCE_NOTICES", "\"${brand.getProperty("notes.maintenance.notices", "on").trim()}\"")
        buildConfigField("boolean", "MAINTENANCE_NOTICES_WHEN_NOTES_HIDDEN", brand.getProperty("notes.maintenance.notices.whenNotesHidden", "true").trim().toBooleanStrict().toString())
        buildConfigField("String", "HISTORY_FILTER", "\"${brand.getProperty("history.filter", "types").trim()}\"")
        buildConfigField("boolean", "ALLOW_UNIT_CHANGE", brand.getProperty("amounts.unit.changeable", "true").trim().toBooleanStrict().toString())
        buildConfigField("String", "DEVELOPER_OPTIONS", "\"${brand.getProperty("developer.options", "shown").trim()}\"")
        // null when unset: the app then offers every action that fits mints.visible.
        buildConfigField("String", "NOTE_ACTIONS", brand.getProperty("notes.actions")?.let { "\"${it.trim()}\"" } ?: "null")
        buildConfigField("boolean", "SHOW_MINTS",brand.getProperty("mints.visible", "true").trim().toBooleanStrict().toString())
        buildConfigField("int", "MAX_ACCOUNTS", brand.getProperty("accounts.max", "0").trim())
        buildConfigField("int", "MAX_ADDRESSES", brand.getProperty("addresses.max", "1").trim())
        buildConfigField("String", "NOTE_STRATEGIES", "\"${brand.getProperty("notes.strategies", "single,manual").trim()}\"")
        buildConfigField("String", "DEFAULT_NOTE_STRATEGY", "\"${brand.getProperty("notes.strategy.default", "single").trim()}\"")
        val appearance = brand.getProperty("app.appearance", "system").trim()
        check(appearance in setOf("system", "light", "dark")) { "brand.properties: app.appearance must be system, light or dark, not '$appearance'" }
        buildConfigField("String", "APPEARANCE", "\"$appearance\"")
        val releaseStage = brand.getProperty("app.releaseStage", "auto").trim()
        check(releaseStage in setOf("auto", "alpha", "beta", "stable")) { "brand.properties: app.releaseStage must be auto, alpha, beta or stable, not '$releaseStage'" }
        buildConfigField("String", "RELEASE_STAGE", "\"$releaseStage\"")
    }
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = signing("WW_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signing("WW_KEY_ALIAS", "keyAlias")
            keyPassword = signing("WW_KEY_PASSWORD", "keyPassword")
        }
    }
    // Reproducible builds: nothing machine- or checkout-specific in the APK (Zapstore, F-Droid style verification).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // Lists the app's languages for the per-app language setting (from res/resources.properties).
    androidResources.generateLocaleConfig = true
    buildFeatures.buildConfig = true
    buildFeatures.resValues = true
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            // en-XA (long, accented) and ar-XB (right to left) to check layouts without a translator.
            isPseudoLocalesEnabled = true
            // The local regtest mints (dev/regtest: mint, mint2), offered in developer mode; release builds can't reach
            // plain http.
            buildConfigField("String", "LOCAL_DEV_MINTS", "\"localhost:8111,localhost:8112,localhost:8113\"")
            // dev/regtest's faucet, which pays the app's own address or invoice from Receive in developer mode.
            buildConfigField("String", "LOCAL_DEV_FAUCET", "\"localhost:8114\"")
            buildConfigField("String", "BUILD_LABEL", "\"$buildLabel\"")
        }
        getByName("release") {
            buildConfigField("String", "LOCAL_DEV_MINTS", "\"\"")
            buildConfigField("String", "LOCAL_DEV_FAUCET", "\"\"")
            // Reproducible: nothing about the checkout in a release.
            buildConfigField("String", "BUILD_LABEL", "\"\"")
            isMinifyEnabled = true
            vcsInfo.include = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("main").res.directories.add(brandDir.resolve("res").path)
}

// Installs the debug build and opens it, like `just run`: what the shared Android Studio run configurations in .run/
// call, since Studio's own app configuration can't pass -PbrandDir.
tasks.register<Exec>("openDebug") {
    group = "install"
    description = "Installs the debug build and opens it on the connected device."
    dependsOn("installDebug")
    executable(androidComponents.sdkComponents.adb.get().asFile)
    args("shell", "monkey", "-p", "${brand("app.id")}.debug", "-c", "android.intent.category.LAUNCHER", "1")
}

// The brand's own code, if its directory has a code/ module (included by settings.gradle.kts as :brand-code).
rootProject.findProject(":brand-code")?.let { brandCode ->
    dependencies { "implementation"(brandCode) }
}
