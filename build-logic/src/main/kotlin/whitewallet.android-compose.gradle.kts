// Adds Compose to an Android application or library module.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = versionCatalogs.named("libs")

extensions.configure<com.android.build.api.dsl.CommonExtension>("android") {
    buildFeatures.compose = true
}

dependencies {
    val bom = platform(libs.findLibrary("compose-bom").get())
    "implementation"(bom)
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
}
