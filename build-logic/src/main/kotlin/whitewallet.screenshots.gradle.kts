// The screenshots module (screenshots/): previews drawn in a brand's design into build/screenshots/render/, which
// dev/screenshots.py files under build/screenshots/<set>/<brand>/ (docs/DEVELOPMENT.md, "Screenshots"). Nothing here runs unless asked:
// `just
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

// The render: one folder whatever the brand and set, so the build cache keeps one per brand and set, and switching between
// brands restores it instead of drawing again. dev/screenshots.py --render files it per set and brand.
val render = layout.buildDirectory.dir("screenshots/render")

tasks.withType<Test>().configureEach {
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    systemProperty("screenshots.set", screenshotSet ?: "check")
    systemProperty("screenshots.dir", render.get().asFile.path)
    // Where the brand's design is looked for: its code module's classes (built under its directory).
    systemProperty("screenshots.brandCode", rootProject.findProject(":brand-code")?.projectDir?.path.orEmpty())
    systemProperty("roborazzi.test.record", "true")
    outputs.dir(render)
    // Robolectric only draws: the brand's resources and code are inputs already, through the classpath and BuildConfig.
    val renderDir = render.get().asFile
    doFirst { renderDir.deleteRecursively() }
}

tasks.register("renderScreenshots") {
    description = "Draws the previews in the brand's design into build/screenshots/render (-Pscreenshots=check|full)."
    dependsOn("testDebugUnitTest")
}
