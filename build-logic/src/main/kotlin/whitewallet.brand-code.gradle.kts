// A brand's own code module (brandDir/code, see docs/WHITE_LABEL.md "Custom code"). It sees core:brand-api and
// nothing else of the app, and contributes to it through Hilt (e.g. `@Provides @IntoSet fun x(): NoteStrategy`).

plugins {
    id("whitewallet.android-library")
    id("whitewallet.hilt")
}

android {
    if (namespace == null) namespace = "whitewallet.brand.code"
}

dependencies {
    "implementation"(project(":core:brand-api"))
}
