// What a brand's own code may plug into to shape the app's look (see docs/WHITE_LABEL.md, "Design"): its theme,
// components, decorations and screens. Compose only, nothing of the wallet.
plugins {
    id("whitewallet.android-library")
    id("whitewallet.android-compose")
}

android {
    namespace = "whitewallet.brand.ui"
}

dependencies {
    api(project(":core:brand-api"))
    implementation(libs.kotlinx.coroutines.core)
}
