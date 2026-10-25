pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "white-wallet"

// A brand's own code: brandDir/code (see docs/WHITE_LABEL.md, "Custom code"), built into the app when present.
val brandCode = file(providers.gradleProperty("brandDir").getOrElse("brand")).resolve("code")
if (brandCode.resolve("build.gradle.kts").exists()) {
    include(":brand-code")
    project(":brand-code").projectDir = brandCode
}

include(
    ":app",
    ":core:brand-api",
    ":core:data",
    ":core:designsystem",
    ":core:scanner",
    ":core:model",
    ":core:wallet",
    ":feature:claim",
    ":feature:home",
    ":feature:notes",
    ":feature:onboarding",
    ":feature:receive",
    ":feature:send",
    ":feature:settings",
    ":protocol:lnurl",
    ":protocol:lnurlcash-core",
    ":protocol:lnurlcash-protocol",
    ":protocol:lnurlcash-client",
    ":protocol:lnurlcash-testkit",
)
