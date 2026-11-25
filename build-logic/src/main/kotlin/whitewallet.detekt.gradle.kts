// detekt, Kotlin's static analysis (`just detekt`): the rule sets that find bugs, from detekt.yml, with types. A finding
// fails the build.
plugins {
    id("dev.detekt")
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

// detektMain and detektTest cover each variant of an Android module; release has no sources of its own, so debug is enough.
tasks.matching { it.name.startsWith("detektRelease") }.configureEach { enabled = false }
