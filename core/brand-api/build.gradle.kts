// What a brand's own code may plug into (see docs/WHITE_LABEL.md, "Custom code"). Plain Kotlin and kept small: brand
// code depends on this module only, never on the wallet itself.
plugins {
    id("whitewallet.jvm-library")
}
