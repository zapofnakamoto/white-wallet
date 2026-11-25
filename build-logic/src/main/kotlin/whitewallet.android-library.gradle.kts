plugins {
    id("com.android.library")
}

android {
    configureAndroid()
}

configureTestJvms()

configureKotlinWarnings()

if (path != ":brand-code") apply(plugin = "whitewallet.detekt")
