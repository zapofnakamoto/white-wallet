package whitewallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.brand.NoteStrategy
import whitewallet.core.model.Profile
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.Wallet

/** What the settings hub summarizes; each part is edited on its own page. */
data class SettingsState(
    val accountName: String? = null,
    /** The account's place among the accounts, oldest first: its mark's colour. */
    val accountPlace: Int = 0,
    val profile: Profile? = null,
    val backupVerified: Boolean = false,
    val addressStatus: AddressStatus? = null,
    /** How many addresses the account has besides the primary one. */
    val otherAddresses: Int = 0,
    val developerMode: Boolean = false,
    /** The account's note strategy; null until loaded. */
    val noteStrategy: NoteStrategy? = null,
    val autoRotate: Boolean = false,
    val lastPolicyRun: PolicyRun? = null,
    val appLock: Boolean = false,
    /** The account's home mint. */
    val homeHost: String? = null,
    /** Notes on other mints are moved to [homeHost] automatically. */
    val collectAtHome: Boolean = false,
    /** Pages this brand leaves out (see [hiddenPages]): no row, no search result. */
    val hidden: Set<SettingsTarget> = setOf(SettingsTarget.NoteList),
    /** Mints the wallet knows, and how many of them are trusted. */
    val mintCount: Int = 0,
    val trustedCount: Int = 0,
)

/**
 * The pages a brand's configuration leaves out: the note list unless it lives in settings (`notesView`), the mint pages
 * without `showMints`, Amounts when the unit is fixed (`allowUnitChange`), Developer options unless the brand shows them
 * (`developerOptions`), and Note settings when there is nothing to choose (one strategy that also renews the address's
 * notes, and no switch for collecting).
 */
internal fun hiddenPages(defaults: AppDefaults, wallet: Wallet, strategy: NoteStrategy?, developer: Boolean, unlocked: Boolean): Set<SettingsTarget> = buildSet {
    if (!defaults.developerOptionsVisible(developer, unlocked)) add(SettingsTarget.Developer)
    if (defaults.notesView != NotesView.Settings) add(SettingsTarget.NoteList)
    if (!defaults.showMints) addAll(listOf(SettingsTarget.Mints, SettingsTarget.AccountMints))
    if (!defaults.allowUnitChange) add(SettingsTarget.Display)
    val nothingToChoose = wallet.noteStrategies.size <= 1 && strategy?.rotatesAddressNotes == true && !wallet.collectDefaults.offered
    if (nothingToChoose) add(SettingsTarget.Notes)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val wallet: Wallet, preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    private val notes = combine(wallet.noteStrategy, wallet.autoRotate, wallet.lastPolicyRun, ::Triple)
    private val address = combine(wallet.profile, wallet.addressStatus, wallet.addresses, ::Triple)
    private val account = combine(wallet.activeAccount, wallet.backupVerified, wallet.accounts) { active, backedUp, accounts ->
        Triple(active, backedUp, accounts.indexOfFirst { it.account.id == active?.account?.id }.coerceAtLeast(0))
    }
    private val developer = combine(preferences.developerMode, preferences.developerOptionsUnlocked, ::Pair)
    private val app = combine(developer, preferences.appLock, wallet.collectAtHome, ::Triple)

    val state: StateFlow<SettingsState> = combine(account, address, notes, app, wallet.mints) {
            (account, backedUp, place), (profile, status, addresses), (strategy, rotate, run), (developerState, appLock, collect), mints ->
        val (developer, unlocked) = developerState
        SettingsState(
            account?.account?.name, place, profile, backedUp, status, (addresses.size - 1).coerceAtLeast(0), developer, strategy, rotate, run, appLock,
            account?.account?.host, collect, hidden = hiddenPages(defaults, wallet, strategy, developer, unlocked), mintCount = mints.size, trustedCount = mints.count { it.trusted },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    init {
        // Refreshing also re-checks that the address still points at this wallet.
        viewModelScope.launch { runCatching { wallet.refresh() } }
    }
}
