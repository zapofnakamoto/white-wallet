package whitewallet.feature.settings

/** A settings page the hub can open. The app maps each to its route. */
enum class SettingsTarget {
    /** Every wallet on the phone; a wallet's page and an account's page open from there. */
    Wallets,
    Backup,
    Addresses,
    AccountMints,
    Notes,
    NoteList,
    Mints,

    /** Looking through the active wallet's keys at its mints again. */
    Scan,
    Delete,
    Display,
    Security,
    About,
    Developer,

    /** The active account's derived keys, a developer page. */
    Keys,
}

/** Where a settings row leads: a settings page, a wallet's or an account's page, or another page (a brand's) by route. */
sealed interface SettingsDestination {
    data class Page(val target: SettingsTarget) : SettingsDestination

    data class Wallet(val walletId: String) : SettingsDestination

    data class Account(val accountId: String) : SettingsDestination

    data class Route(val route: String) : SettingsDestination
}
