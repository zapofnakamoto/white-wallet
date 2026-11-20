// The screenshots module (screenshots/): previews drawn in a brand's design and saved under
// build/screenshots/<brand>/current/ (docs/DEVELOPMENT.md, "Screenshots"). Nothing here runs unless asked: `just
// screenshots` (every configuration) and `just check` (a small set) pass -Pscreenshots=full or =check.
plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
    id("whitewallet.brand")
}

val screenshotSet = providers.gradleProperty("screenshots").orNull

check(screenshotSet == null || screenshotSet in setOf("check", "full")) { "-Pscreenshots must be check or full, not '$screenshotSet'" }

// Not part of `./gradlew test` or a build: without -Pscreenshots, none of this module's tasks run.
tasks.configureEach { enabled = screenshotSet != null }

val brand = brandProperties()

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    buildFeatures.buildConfig = true
    defaultConfig {
        // What the app takes from brand.properties and shows on these screens (see whitewallet.android-application).
        buildConfigField("String", "APP_NAME", "\"${brand.getProperty("app.name").trim()}\"")
        buildConfigField("String", "VERSION_NAME", "\"${brand.getProperty("app.version.name").trim()}\"")
        buildConfigField("String", "RELEASE_STAGE", "\"${brand.getProperty("app.releaseStage", "auto").trim()}\"")
        buildConfigField("String", "DEFAULT_AMOUNT_UNIT", "\"${brand.getProperty("amounts.unit.default", "bitcoin").trim()}\"")
        buildConfigField("String", "APPEARANCE", "\"${brand.getProperty("app.appearance", "system").trim()}\"")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.directories.add(rootProject.file("build-logic/test-resources").path)
}

// One folder per brand, named after its directory: brand, acme, zap-of-nakamoto.
val current = layout.buildDirectory.dir("screenshots/${brandDir().name}/current")

tasks.withType<Test>().configureEach {
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    systemProperty("screenshots.set", screenshotSet ?: "check")
    systemProperty("screenshots.dir", current.get().asFile.path)
    systemProperty("roborazzi.test.record", "true")
    systemProperty("screenshots.brandCode", rootProject.findProject(":brand-code") != null)
    // The images are this task's output: an unchanged module, brand and set is up to date.
    inputs.property("screenshotSet", screenshotSet ?: "")
    inputs.dir(brandDir()).withPropertyName("brandDir")
    outputs.dir(current)
}
