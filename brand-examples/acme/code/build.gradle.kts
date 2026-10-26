// Acme's own code: built into the app because this brand directory has a code/ module. It may use core:brand-api and
// core:brand-ui only.
plugins {
    id("whitewallet.brand-code")
}

android {
    namespace = "com.example.acmecash.code"
}
