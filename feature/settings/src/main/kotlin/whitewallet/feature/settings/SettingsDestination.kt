package whitewallet.feature.settings

/** A settings page the hub can open. The app maps each to its route. */
enum class SettingsTarget {
    Account,
    Backup,
    Addresses,
    AccountMints,
    Notes,
    NoteList,
    Mints,
    Delete,
    Display,
    Security,
    About,
    Developer,
}

/** Where a hub row leads: a settings page, or another page (a brand's) by route. */
sealed interface SettingsDestination {
    data class Page(val target: SettingsTarget) : SettingsDestination

    data class Route(val route: String) : SettingsDestination
}
