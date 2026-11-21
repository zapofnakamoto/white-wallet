package whitewallet.feature.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.screenLoaded
import whitewallet.core.designsystem.walletName
import whitewallet.core.wallet.Wallet

/** The wallet a setup just ended for, as its ready screen says it. */
data class WalletReadyState(
    /** The wallet's name, if the user gave it one, and its number among the wallets. */
    val name: String? = null,
    val number: Int = 1,
    /** The phone holds other wallets too: this one became the wallet in use, and the screen says so by its name. */
    val several: Boolean = false,
    /** What the wallet holds by now: what a restore found at its mint. */
    val foundMsat: Long = 0,
    /** Its phrase is written down (a restore): no backup step comes next. */
    val backedUp: Boolean = false,
    /** The wallet was read: until then the screen draws nothing. */
    val loaded: Boolean = true,
)

/** Reads the wallet in use once: the setup that just ended was its own. */
@HiltViewModel
class WalletReadyViewModel @Inject constructor(wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(WalletReadyState(loaded = false))
    val state: StateFlow<WalletReadyState> = _state

    init {
        viewModelScope.launch {
            val groups = wallet.walletGroups.first().filter { it.accounts.isNotEmpty() }
            val active = wallet.activeAccount.first()?.account?.id
            val group = groups.firstOrNull { group -> group.accounts.any { it.account.id == active } }
            _state.value =
                WalletReadyState(
                    name = group?.wallet?.name,
                    number = group?.number ?: 1,
                    several = groups.size > 1,
                    foundMsat = group?.balanceMsat ?: 0,
                    backedUp = wallet.backupVerified.first(),
                )
        }
    }
}

/**
 * Where every wallet's setup ends, the first wallet's and an added one's, created or restored: the wallet is ready. [address]: the
 * Lightning address the setup added, checked to reach this wallet. [onOpen] goes on to the wallet, with what decides the way there.
 */
@Composable
fun WalletReadyRoute(address: String?, onOpen: (WalletReadyState) -> Unit, viewModel: WalletReadyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WalletReadyScreen(state, address) { onOpen(state) }
}

@Composable
fun WalletReadyScreen(state: WalletReadyState, address: String?, onOpen: () -> Unit) {
    if (!screenLoaded(state.loaded)) return
    Scaffold { padding ->
        SuccessScreen(
            anchor = Anchor.WalletReady,
            status = WalletStatus.Done(state.foundMsat),
            title = stringResource(R.string.wallet_ready_title),
            done = SuccessAction(stringResource(R.string.wallet_ready_open), onOpen),
            modifier = Modifier.padding(padding),
            lines =
                listOfNotNull(
                    // With one wallet there is nothing to tell apart: it is the wallet.
                    if (state.several) stringResource(R.string.wallet_ready_in_use, walletName(state.name, state.number)) else null,
                    state.foundMsat.takeIf { it > 0 }?.let { stringResource(R.string.add_mint_found_total, formatAmount(it)) },
                ),
            content = address?.let { { AddressReady(it) } },
        )
    }
}
