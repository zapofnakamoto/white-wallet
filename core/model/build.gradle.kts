plugins {
    id("whitewallet.jvm-library")
}

dependencies {
    // RevealsNoteSecret, on the fields that hold a note's spend.
    implementation(project(":protocol:lnurlcash-core"))
}
