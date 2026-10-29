package whitewallet.core.wallet

import whitewallet.brand.ReleaseStage
import whitewallet.core.model.AmountUnit

/* Brand-level defaults, provided by the app module from brand/brand.properties. */

/** Whether users see Developer options (brand `developer.options`). */
enum class DeveloperOptions {
    /** Always, in Settings. */
    Shown,

    /** Hidden until the user taps the version in About seven times. */
    Unlock,

    /** Never (release builds; a debug build treats it as [Unlock], so development stays possible). */
    Hidden;

    companion object {
        fun fromConfig(value: String, debug: Boolean): DeveloperOptions {
            val configured = configEnum<DeveloperOptions>("developer.options", value)
            return if (debug && configured == Hidden) Unlock else configured
        }
    }
}

/**
 * The brand's `app.releaseStage`: `alpha`, `beta` or `stable` as set, or `auto` (the default) for beta while [versionName]
 * is below 1.0.0 and stable from then on.
 */
fun releaseStageFromConfig(value: String, versionName: String): ReleaseStage = when (val v = value.trim().lowercase()) {
    "auto" -> {
        val major = versionName.substringBefore('.').toIntOrNull()
            ?: error("app.releaseStage=auto needs an app.version.name that starts with a number, not '$versionName'")
        if (major < 1) ReleaseStage.Beta else ReleaseStage.Stable
    }
    else -> ReleaseStage.entries.firstOrNull { it.name.lowercase() == v }
        ?: error("app.releaseStage must be auto, alpha, beta or stable, not '$value'")
}

/** Where the notes view lives (brand `notes.view`). */
enum class NotesView {
    /** A Notes tab next to Wallet and History. */
    Tab,

    /** A page under Settings → Account. */
    Settings,

    /** Nowhere: the note strategy keeps the notes, and users only see their balance and history. */
    Hidden;

    companion object {
        fun fromConfig(value: String): NotesView = configEnum("notes.view", value)
    }
}

/** Which filters the History tab offers (brand `history.filter`). */
enum class HistoryFilterOption {
    /** Received, sent, moved and note maintenance. */
    Types,

    /** Only showing or hiding note maintenance. */
    Maintenance,

    /** None; note maintenance stays hidden. */
    None;

    companion object {
        fun fromConfig(value: String): HistoryFilterOption = configEnum("history.filter", value)
    }
}

/** What users can do with their notes in the notes view, and with a handed-out one (brand `notes.actions`). */
enum class NoteAction(
    /** Done on several selected notes at once; without any such action there is no selection. */
    val bulk: Boolean,
) {
    /** Hand the whole note out as a QR code. */
    Share(bulk = false),
    Split(bulk = false),
    Combine(bulk = true),

    /** To another mint; needs `mints.visible`, as it names the mint. */
    Move(bulk = true),

    /** Ask the mint about a note, or about the selected ones. */
    Recheck(bulk = true),

    /** A fresh key for a note. */
    Renew(bulk = false),

    /** Print a handed-out note on its page: its face, QR code and bearer warning, on paper. */
    Print(bulk = false);

    companion object {
        /**
         * The brand's comma-separated list ([value] null when unset: all, without [Move] when mints are hidden). An
         * empty list leaves the notes to look at. Fails on an unknown action, and on `move` with [showMints] false.
         */
        fun fromConfig(value: String?, showMints: Boolean): Set<NoteAction> {
            if (value == null) return entries.filter { showMints || it != Move }.toSet()
            val actions = value.split(',').map(String::trim).filter(String::isNotEmpty).map { configEnum<NoteAction>("notes.actions", it) }.toSet()
            check(showMints || Move !in actions) { "notes.actions can't include move with mints.visible=false: it names the mint" }
            return actions
        }
    }
}

/** Whether accounts have a Lightning address (brand `addresses.lightning`). */
enum class AddressPolicy {
    /** Setup asks for a name and can't continue without one. */
    Required,

    /** Setup asks for a name, and the user may continue without one (receiving by invoice until they add one). */
    Optional,

    /** Setup only asks for the mint; users can still get an address later in Settings. */
    None;

    companion object {
        fun fromConfig(value: String): AddressPolicy = configEnum("addresses.lightning", value)
    }
}

data class AppDefaults(
    /**
     * The mints offered at setup and restore (brand `mints.suggestions`), the first preselected; empty when users bring
     * their own. Without [customMints], also the only mints that can be a home, hold an address or receive moved money.
     */
    val mintSuggestions: List<String>,
    /** Whether users may change their mint after setup (brand `mints.changeable`): addresses elsewhere, a new home, moving money. */
    val allowMintChange: Boolean,
    /** Whether users may use mints the brand doesn't suggest (brand `mints.custom`): type one at setup, add one, make it home. */
    val customMints: Boolean = true,
    /** The local regtest mints, offered in developer mode in debug builds; empty in release builds. */
    val localDevMints: List<String> = emptyList(),
    /**
     * Debug builds: developer mode offers making a note action fail on purpose ([SimulatedFailure]) and a bad
     * connection to the mints (`NetworkConditions`); never in release builds.
     */
    val simulatedFailures: Boolean = false,
    /** How amounts are shown until the user picks a unit (brand `amounts.unit.default`). */
    val defaultAmountUnit: AmountUnit = AmountUnit.Bitcoin,
    /** Whether users may pick another unit under Settings → Amounts (brand `amounts.unit.changeable`); else always [defaultAmountUnit]. */
    val allowUnitChange: Boolean = true,
    /** Accounts this wallet may hold (brand `accounts.max`); [UNLIMITED] for no limit. Enforced by [Wallet.maxAccounts]. */
    val maxAccounts: Int = UNLIMITED,
    /** Lightning addresses an account may hold per mint (brand `addresses.max`); [UNLIMITED] for no limit. */
    val maxAddresses: Int = 1,
    /** Whether setup asks for a Lightning address and may go on without one (brand `addresses.lightning`); see [Wallet.requireAddress]. */
    val address: AddressPolicy = AddressPolicy.Optional,
    /** Where users see and manage their notes (brand `notes.view`). */
    val notesView: NotesView = NotesView.Tab,
    /** Which filters the History tab offers (brand `history.filter`). */
    val historyFilter: HistoryFilterOption = HistoryFilterOption.Types,
    /** Whether users are told what the wallet did to their notes by itself (brand `notes.maintenance.notices`). */
    val maintenanceNotices: MaintenanceNoticeDefaults = MaintenanceNoticeDefaults(),
    /**
     * Whether users see mints at all (brand `mints.visible`): Settings → Mints, the home mint's page, which mint a claimed
     * note is on and whether it's trusted. Without, the app is just a wallet; the mints work the same underneath.
     */
    val showMints: Boolean = true,
    /** What users can do with their notes in the notes view (brand `notes.actions`). */
    val noteActions: Set<NoteAction> = NoteAction.entries.toSet(),
    /** Whether Settings shows Developer options (brand `developer.options`); see [developerOptionsVisible]. */
    val developerOptions: DeveloperOptions = DeveloperOptions.Shown,
    /** How finished the app is (brand `app.releaseStage`), shown at setup and in About unless [ReleaseStage.Stable]. */
    val releaseStage: ReleaseStage = ReleaseStage.Stable,
) {
    init {
        require(mintSuggestions.isNotEmpty() || customMints) { "mints.suggestions is empty and mints.custom=false: no mint to set up with" }
    }

    /** The mint preselected at setup and restore; empty when users bring their own. */
    val defaultMintHost: String get() = mintSuggestions.firstOrNull().orEmpty()

    /**
     * Whether [host] may be a home, hold an address or receive moved money: any mint with [customMints], else only the
     * suggested ones (and the local regtest mints, which only debug builds have). Notes on any mint can still be claimed.
     */
    fun mintAllowed(host: String): Boolean {
        if (customMints) return true
        val h = Onboarding.normalizeHost(host) ?: return false
        return (mintSuggestions + localDevMints).any { Onboarding.normalizeHost(it) == h }
    }

    /** Whether a mint other than [home] may become a home or hold an address: any with [customMints], else another suggested one. */
    fun otherMintsAllowed(home: String?): Boolean =
        customMints || (mintSuggestions + localDevMints).any { Onboarding.normalizeHost(it) != home?.let(Onboarding::normalizeHost) }

    /** The address limit in effect: the developer setting while developer mode is on (if set), else the brand's. */
    fun addressLimit(developerMode: Boolean, developerLimit: Int?): Int = developerLimit?.takeIf { developerMode } ?: maxAddresses

    /**
     * Whether Developer options (and with them developer mode) are reachable: always with [DeveloperOptions.Shown]; with
     * [DeveloperOptions.Unlock] once the user unlocked them in About, or while developer mode is on (debug builds start
     * with it on); never with [DeveloperOptions.Hidden].
     */
    fun developerOptionsVisible(developerMode: Boolean, unlocked: Boolean): Boolean = when (developerOptions) {
        DeveloperOptions.Shown -> true
        DeveloperOptions.Unlock -> unlocked || developerMode
        DeveloperOptions.Hidden -> false
    }

    /** The unit amounts are shown in: the user's [chosen] one if the brand lets them choose, else the brand's. */
    fun amountUnit(chosen: AmountUnit?): AmountUnit = chosen?.takeIf { allowUnitChange } ?: defaultAmountUnit

    companion object {
        const val UNLIMITED = Int.MAX_VALUE

        /** A brand.properties limit: 0 (or less) means no limit. */
        fun limit(configured: Int): Int = if (configured <= 0) UNLIMITED else configured
    }
}

/** The entry of [E] a brand.properties [key] names, ignoring case; fails the build's startup with the allowed values otherwise. */
private inline fun <reified E : Enum<E>> configEnum(key: String, value: String): E =
    enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: error("$key must be ${enumValues<E>().joinToString { it.name.lowercase() }}, not '$value'")
