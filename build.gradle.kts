// Plugins are applied through the convention plugins in build-logic/.

plugins {
    alias(libs.plugins.spotless)
}

// Formatting: ktfmt's kotlinlang style (4-space indent), 140 wide, for every Kotlin source and build script.
// `./gradlew spotlessApply` formats; `spotlessCheck` fails on code that isn't formatted (CI runs it).
// Build outputs are skipped while walking, not filtered afterwards: a compile writing into build/ would break the walk.
fun sources(pattern: String) = fileTree(rootDir) { include(pattern).exclude("**/build/**", "**/.gradle/**") }

spotless {
    kotlin {
        target(sources("**/*.kt"))
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(140) }
    }
    kotlinGradle {
        target(sources("**/*.gradle.kts"))
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(140) }
    }
}
