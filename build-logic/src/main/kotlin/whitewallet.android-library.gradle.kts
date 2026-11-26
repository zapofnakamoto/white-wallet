plugins {
    id("com.android.library")
}

android {
    configureAndroid()
}

configureTestJvms()

configureRobolectricOffline()

configureKotlinWarnings()

if (path != ":brand-code") apply(plugin = "whitewallet.detekt")
