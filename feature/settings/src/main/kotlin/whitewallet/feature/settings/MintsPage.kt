package whitewallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.model.AccountChoice
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.noHome

/**
 * The Mints page: the account in use's [home] mint, every [others] mint of the wallet with the account's money there, and the way to add
 * one. [noHome]: the account has none, and what is missing. [accounts]: the account mark in the top bar. Nothing is drawn until [loaded].
 */
data class MintsPageState(
    val home: MintCard? = null,
    val others: List<MintCard> = emptyList(),
    val noHome: NoHome? = null,
    /** The brand's suggested mints not in the wallet yet. */
    val offered: List<String> = emptyList(),
    /** The brand lets users add a mint by typing it (`mints.custom`); without, only [offered] ones can be added. */
    val custom: Boolean = true,
    /** The home can be changed here: the brand lets users change it, and allows another mint. */
    val canChangeHome: Boolean = false,
    val accounts: AccountChoice? = null,
    val loaded: Boolean = true,
) {
    /** Whether there is anything to add: without [custom], only while a suggested mint is missing from the wallet. */
    val canAdd: Boolean
        get() = custom || offered.isNotEmpty()

    /** No mint at all: the page is its empty state. */
    val empty: Boolean
        get() = home == null && others.isEmpty()
}

@HiltViewModel
class MintsPageViewModel @Inject constructor(private val wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    // One state from every source, so the home, the other mints and the account mark are drawn together, never one after the other. Read
    // afresh for each account in use: after a switch, the page shows the last account until everything of the new one is read.
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MintsPageState> =
        wallet.activeAccount
            .map { it?.account?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> forAccount(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MintsPageState(custom = defaults.customMints, loaded = false))

    private fun forAccount(accountId: String?) =
        combine(wallet.mints, wallet.accountMints, wallet.mintsWithChangedKey, wallet.noHome, wallet.accountChoice) {
                mints,
                held,
                changed,
                noHome,
                accounts ->
                val cards = mintCards(mints, held, changed)
                val home = cards.firstOrNull { it.isHome }
                val known = cards.map { it.host }
                MintsPageState(
                    home = home,
                    others = cards - listOfNotNull(home).toSet(),
                    noHome = noHome,
                    offered = defaults.mintSuggestions.filter { it !in known },
                    custom = defaults.customMints,
                    canChangeHome = home != null && defaults.allowMintChange && defaults.otherMintsAllowed(home.host),
                    accounts = accounts,
                )
            }
            // A source still on the account before: not this account's page yet.
            .filter { it.accounts?.activeId == accountId }

    /** Makes [accountId] the account in use; the page then shows its mints and money. */
    fun switchAccount(accountId: String) {
        viewModelScope.launch { wallet.switchAccount(accountId) }
    }
}
