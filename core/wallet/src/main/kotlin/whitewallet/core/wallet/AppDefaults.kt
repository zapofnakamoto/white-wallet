package whitewallet.core.wallet

import whitewallet.brand.ReleaseStage
import whitewallet.core.model.AmountUnit

/* Brand-level defaults, provided by the app module from brand/brand.properties. */

/** Whether users see Developer options (brand `developerOptions`). */
enum class DeveloperOptions {
    /** Always, in Settings. */
    Shown,

    /** Hidden until the user taps the version in About seven times. */
    Unlock,

    /** Never (release builds; a debug build treats it as [Unlock], so development stays possible). */
    Hidden;

    companion object {
        fun fromConfig(value: String, debug: Boolean): DeveloperOptions {
            val configured = configEnum<DeveloperOptions>("developerOptions", value)
            return if (debug && configured == Hidden) Unlock else configured
        }
    }
}

/**
 * The brand's `releaseStage`: `alpha`, `beta` or `stable` as set, or `auto` (the default) for beta while [versionName]
 * is below 1.0.0 and stable from then on.
 */
fun releaseStageFromConfig(value: String, versionName: String): ReleaseStage = when (val v = value.trim().lowercase()) {
    "auto" -> {
        val major = versionName.substringBefore('.').toIntOrNull()
            ?: error("releaseStage=auto needs a versionName that starts with a number, not '$versionName'")
        if (major < 1) ReleaseStage.Beta else ReleaseStage.Stable
    }
    else -> ReleaseStage.entries.firstOrNull { it.name.lowercase() == v }
        ?: error("releaseStage must be auto, alpha, beta or stable, not '$value'")
}

/** Where the notes view lives (brand `notesView`). */
enum class NotesView {
    /** A Notes tab next to Wallet and History. */
    Tab,

    /** A page under Settings → Account. */
    Settings,

    /** Nowhere: the note strategy keeps the notes, and users only see their balance and history. */
    Hidden;

    companion object {
        fun fromConfig(value: String): NotesView = configEnum("notesView", value)
    }
}

/** Which filters the History tab offers (brand `historyFilter`). */
enum class HistoryFilterOption {
    /** Received, sent, moved and note maintenance. */
    Types,

    /** Only showing or hiding note maintenance. */
    Maintenance,

    /** None; note maintenance stays hidden. */
    None;

    companion object {
        fun fromConfig(value: String): HistoryFilterOption = configEnum("historyFilter", value)
    }
}

data class AppDefaults(
    val defaultMintHost: String,
    val allowMintChange: Boolean,
    /** Mints offered in the onboarding dropdown, default first. */
    val mintSuggestions: List<String> = listOf(defaultMintHost),
    /** The local regtest mints, offered in developer mode in debug builds; empty in release builds. */
    val localDevMints: List<String> = emptyList(),
    /** How amounts are shown until the user picks a unit (brand `defaultAmountUnit`). */
    val defaultAmountUnit: AmountUnit = AmountUnit.Bitcoin,
    /** Whether users may pick another unit under Settings → Amounts (brand `allowUnitChange`); else always [defaultAmountUnit]. */
    val allowUnitChange: Boolean = true,
    /** Accounts this wallet may hold (brand `maxAccounts`); [UNLIMITED] for no limit. Enforced by [Wallet.maxAccounts]. */
    val maxAccounts: Int = UNLIMITED,
    /** Lightning addresses an account may hold per mint (brand `maxAddresses`); [UNLIMITED] for no limit. */
    val maxAddresses: Int = 1,
    /** Whether setup asks for a Lightning address (brand `requireAddress`); see [Wallet.requireAddress]. */
    val requireAddress: Boolean = true,
    /** Where users see and manage their notes (brand `notesView`). */
    val notesView: NotesView = NotesView.Tab,
    /** Which filters the History tab offers (brand `historyFilter`). */
    val historyFilter: HistoryFilterOption = HistoryFilterOption.Types,
    /**
     * Whether users see mints at all (brand `showMints`): Settings → Mints, the home mint's page, which mint a claimed
     * note is on and whether it's trusted. Without, the app is just a wallet; the mints work the same underneath.
     */
    val showMints: Boolean = true,
    /** Whether Settings shows Developer options (brand `developerOptions`); see [developerOptionsVisible]. */
    val developerOptions: DeveloperOptions = DeveloperOptions.Shown,
    /** How finished the app is (brand `releaseStage`), shown at setup and in About unless [ReleaseStage.Stable]. */
    val releaseStage: ReleaseStage = ReleaseStage.Stable,
) {
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
