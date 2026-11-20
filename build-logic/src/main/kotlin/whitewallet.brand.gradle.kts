// A brand's look in an Android module: its resources (brandDir/res, over the app's own) and its own code (brandDir/code,
// included by settings.gradle.kts as :brand-code), so the module draws what the brand's app draws. The app applies it,
// and so does :screenshots.

extensions.configure<com.android.build.api.dsl.CommonExtension>("android") {
    sourceSets.getByName("main").res.directories.add(brandDir().resolve("res").path)
}

rootProject.findProject(":brand-code")?.let { brandCode ->
    dependencies { "implementation"(brandCode) }
}
