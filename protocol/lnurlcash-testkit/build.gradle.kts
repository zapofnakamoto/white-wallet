plugins {
    id("whitewallet.jvm-library")
    id("whitewallet.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
