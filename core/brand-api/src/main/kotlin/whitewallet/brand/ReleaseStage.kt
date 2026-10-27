package whitewallet.brand

/**
 * How finished the app is (brand `releaseStage`): [Alpha] and [Beta] are shown to users, in About and at setup, so
 * they know the app may still have bugs; [Stable] shows nothing.
 */
enum class ReleaseStage {
    /** Still being built. */
    Alpha,

    /** Still being tested. */
    Beta,

    /** Released. */
    Stable,
}
