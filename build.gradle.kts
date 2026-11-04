// Plugins are applied through the convention plugins in build-logic/.

plugins {
    alias(libs.plugins.spotless)
}

// Formatting: ktfmt's kotlinlang style (4-space indent), 140 wide, for every Kotlin source and build script.
// `./gradlew spotlessApply` formats; `spotlessCheck` fails on code that isn't formatted (CI runs it).
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(140) }
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(140) }
    }
}
