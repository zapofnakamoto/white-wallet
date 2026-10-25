// A feature screen module: Compose UI + Hilt ViewModels over core:wallet.
plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
    id("whitewallet.hilt")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:wallet"))
    "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    "implementation"(libs.findLibrary("androidx-hilt-navigation-compose").get())

    // Screen tests on the JVM: Compose UI tests under Robolectric (JUnit 4, run by the vintage engine), next to
    // plain JUnit 5 tests.
    val junitBom = platform(libs.findLibrary("junit-bom").get())
    "testImplementation"(junitBom)
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(kotlin("test-junit5"))
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("robolectric").get())
    "testImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
    "testRuntimeOnly"(libs.findLibrary("junit-vintage-engine").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.directories.add(rootProject.file("build-logic/test-resources").path)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric reaches into JDK internals for file descriptors.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}
