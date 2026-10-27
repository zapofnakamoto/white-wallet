import java.util.Properties

// The app module, branded from brand/brand.properties and brand/res (see docs/WHITE_LABEL.md).
// Build another brand without editing brand/ with -PbrandDir=/path/to/brand.

plugins {
    id("com.android.application")
}

val brandDir = brandDir()
val brand = brandProperties()
fun brand(key: String): String = checkNotNull(brand.getProperty(key)) { "brand/brand.properties is missing '$key'" }.trim()

// Release signing, never committed: environment variables (CI) or keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without either, the release APK is built unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(env: String, key: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)
val releaseStoreFile = signing("WW_KEYSTORE", "storeFile")

android {
    configureAndroid()
    defaultConfig {
        applicationId = brand("applicationId")
        targetSdk = COMPILE_SDK
        versionCode = brand("versionCode").toInt()
        versionName = brand("versionName")
        resValue("string", "app_name", brand("appName"))
        buildConfigField("String", "DEFAULT_SERVICE_HOST", "\"${brand("defaultServiceHost")}\"")
        buildConfigField("boolean", "ALLOW_SERVICE_CHANGE", brand("allowServiceChange"))
        buildConfigField("String", "SERVICE_SUGGESTIONS", "\"${brand.getProperty("serviceSuggestions", "").trim()}\"")
        buildConfigField("String", "SUPPORT_URL", "\"${brand("supportUrl")}\"")
        buildConfigField("String", "DEFAULT_AMOUNT_UNIT", "\"${brand.getProperty("defaultAmountUnit", "bitcoin").trim()}\"")
        buildConfigField("boolean", "REQUIRE_ADDRESS", brand.getProperty("requireAddress", "true").trim().toBooleanStrict().toString())
        buildConfigField("String", "COLLECT_AT_HOME_MINT", "\"${brand.getProperty("collectAtHomeMint", "off").trim()}\"")
        buildConfigField("int", "COLLECT_FEE_CAP", brand.getProperty("collectFeeCap", "2").trim())
        buildConfigField("String", "TRUSTED_MINTS", "\"" + brand.getProperty("trustedMints", "").trim() + "\"")
        buildConfigField("String", "NOTES_VIEW", "\"${brand.getProperty("notesView", "tab").trim()}\"")
        buildConfigField("String", "HISTORY_FILTER", "\"${brand.getProperty("historyFilter", "types").trim()}\"")
        buildConfigField("boolean", "ALLOW_UNIT_CHANGE", brand.getProperty("allowUnitChange", "true").trim().toBooleanStrict().toString())
        buildConfigField("String", "DEVELOPER_OPTIONS", "\"${brand.getProperty("developerOptions", "shown").trim()}\"")
        buildConfigField("boolean", "SHOW_MINTS", brand.getProperty("showMints", "true").trim().toBooleanStrict().toString())
        buildConfigField("int", "MAX_ACCOUNTS", brand.getProperty("maxAccounts", "0").trim())
        buildConfigField("int", "MAX_ADDRESSES", brand.getProperty("maxAddresses", "1").trim())
        buildConfigField("String", "NOTE_STRATEGIES", "\"${brand.getProperty("noteStrategies", "single,manual").trim()}\"")
        buildConfigField("String", "DEFAULT_NOTE_STRATEGY", "\"${brand.getProperty("defaultNoteStrategy", "single").trim()}\"")
        val appearance = brand.getProperty("appearance", "system").trim()
        check(appearance in setOf("system", "light", "dark")) { "brand.properties: appearance must be system, light or dark, not '$appearance'" }
        buildConfigField("String", "APPEARANCE", "\"$appearance\"")
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
            buildConfigField("String", "LOCAL_DEV_SERVICES", "\"localhost:8111,localhost:8112,localhost:8113\"")
            // dev/regtest's faucet, which pays the app's own address or invoice from Receive in developer mode.
            buildConfigField("String", "LOCAL_DEV_FAUCET", "\"localhost:8114\"")
        }
        getByName("release") {
            buildConfigField("String", "LOCAL_DEV_SERVICES", "\"\"")
            buildConfigField("String", "LOCAL_DEV_FAUCET", "\"\"")
            isMinifyEnabled = true
            vcsInfo.include = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("main").res.directories.add(brandDir.resolve("res").path)
}

// The brand's own code, if its directory has a code/ module (included by settings.gradle.kts as :brand-code).
rootProject.findProject(":brand-code")?.let { brandCode ->
    dependencies { "implementation"(brandCode) }
}
