package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.HostText
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletMintMark
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.noHome

@HiltViewModel
class AccountMintsViewModel @Inject constructor(wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    /** Null until read. */
    val mints: StateFlow<List<AccountMint>?> =
        wallet.accountMints
            .map<List<AccountMint>, List<AccountMint>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Without any mint of its own: what is missing, for the empty page's way forward. */
    val noHome: StateFlow<NoHome?> = wallet.noHome.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The account can move its home away from [home]: the brand lets users change it, and allows another mint (as on a mint's page). */
    fun canChangeHome(home: String): Boolean = defaults.allowMintChange && defaults.otherMintsAllowed(home)
}

@Composable
fun AccountMintsRoute(
    onBack: () -> Unit,
    onOpen: (host: String) -> Unit,
    onNoHome: (NoHome) -> Unit = {},
    onChangeHome: () -> Unit = {},
    viewModel: AccountMintsViewModel = hiltViewModel(),
) {
    val mints by viewModel.mints.collectAsStateWithLifecycle()
    val noHome by viewModel.noHome.collectAsStateWithLifecycle()
    // Known with the mints (the brand's settings): the button never comes in after the list.
    val changeable = mints?.firstOrNull { it.isHome }?.let { viewModel.canChangeHome(it.host) } ?: false
    AccountMintsScreen(mints, onBack, onOpen, noHome, onNoHome, onChangeHome.takeIf { changeable })
}

/**
 * The active account's mints: its home first, then wherever it has money or an address, each with its money there, and [onChangeHome] below
 * them (null: the home can't be changed). A mint's page from here has what the account can do on it (change home, move money); the device's
 * list of mints is Settings → Mints.
 */
@Composable
fun AccountMintsScreen(
    mints: List<AccountMint>?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    noHome: NoHome? = null,
    onNoHome: (NoHome) -> Unit = {},
    onChangeHome: (() -> Unit)? = null,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_account_mints), onBack) }) { padding ->
        // Not read yet (a moment): nothing, rather than an empty list first.
        if (!pageLoaded(mints != null, Modifier.padding(padding))) return@Scaffold
        mints ?: return@Scaffold
        BrandBody(Page.AccountMints, if (mints.isEmpty()) WalletStatus.Empty else WalletStatus.Idle) {
            LazyColumn(Modifier.padding(padding).fillMaxWidth()) {
                if (mints.isEmpty()) {
                    // No home and no money anywhere: what is missing, and the way to it, as on Receive.
                    item {
                        val mint = noHome != NoHome.PickHome
                        EmptyState(
                            stringResource(if (mint) R.string.mints_empty else R.string.settings_no_home),
                            action =
                                noHome?.let { missing ->
                                    {
                                        PrimaryButton(
                                            stringResource(if (mint) R.string.mints_add else R.string.settings_no_home_action),
                                            { onNoHome(missing) },
                                        )
                                    }
                                },
                        )
                    }
                    return@LazyColumn
                }
                item {
                    Text(
                        stringResource(R.string.account_mints_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(mints, key = { it.host }) { mint ->
                    val hint = MaterialTheme.typography.bodySmall
                    WalletListItem(
                        leadingContent = { WalletMintMark(mint.host, mint.isHome) },
                        headlineContent = { HostText(mint.host) },
                        supportingContent = {
                            Column {
                                if (mint.isHome)
                                    Text(
                                        stringResource(R.string.settings_home_mint),
                                        style = hint,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                Text(
                                    pluralStringResource(
                                        R.plurals.mint_balance,
                                        mint.noteCount,
                                        formatAmount(mint.balanceMsat),
                                        mint.noteCount,
                                    ),
                                    style = hint,
                                )
                                if (mint.addresses > 0)
                                    Text(
                                        pluralStringResource(R.plurals.account_mints_addresses, mint.addresses, mint.addresses),
                                        style = hint,
                                    )
                            }
                        },
                        modifier = Modifier.clickable { onOpen(mint.host) },
                    )
                    HorizontalDivider()
                }
                // The home is the account's own choice: one level up from its page, where people look for it.
                onChangeHome?.let { change ->
                    item {
                        WalletButton(kind = ButtonKind.Secondary, onClick = change, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.mint_change_home))
                        }
                    }
                }
            }
        }
    }
}
