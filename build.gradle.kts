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

// Robolectric's Android runtimes (android-all-instrumented, one per SDK level the tests emulate), fetched by Gradle like any
// dependency: cached with the rest (CI keeps it), retried, and checked. Robolectric runs offline from this folder
// (configureRobolectricOffline), instead of downloading each one itself at test time, outside Gradle and its cache.
// The versions are Robolectric's own for those SDK levels (its DefaultSdkProvider): change them with the robolectric
// version. A test that needs another one fails naming the file it looked for.
val robolectricRuntimes =
    listOf(
        // SDK 36: build-logic/test-resources/robolectric.properties, which every module's tests read.
        "16-robolectric-13921718-i7"
    )

tasks.register<Sync>("robolectricRuntimes") {
    // One configuration each: in one, Gradle would take the newest version of the module for all.
    robolectricRuntimes.forEach { version ->
        from(
            configurations.detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$version")).apply {
                isTransitive = false
            }
        )
    }
    into(layout.buildDirectory.dir("robolectric"))
}
