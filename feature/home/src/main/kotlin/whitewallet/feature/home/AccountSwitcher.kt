package whitewallet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSheet
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.AccountSummary

/** What the account switcher can ask for. */
class AccountActions(val onSwitch: (String) -> Unit, val onAdd: () -> Unit, val onMove: () -> Unit, val onAddWallet: () -> Unit = {})

/** Bottom sheet listing every account with its address and balance (Design Guide: wallet switcher). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcher(
    accounts: List<AccountSummary>,
    activeId: String?,
    /** False once the active wallet holds as many accounts as the brand allows. */
    canAdd: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
    /** False once the app holds as many wallets as the brand allows. */
    canAddWallet: Boolean = false,
    onAddWallet: () -> Unit = {},
) {
    val wallets = accounts.groupBy { it.account.walletId }.values.toList()
    WalletSheet(onDismissRequest = onDismiss) {
        // Scrolls: with many accounts, the buttons and the backup hint below them would be cut off.
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.accounts_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // One choice among the accounts: each row is a radio button to screen readers; the active one's mark has a check.
            Column(Modifier.selectableGroup()) {
                wallets.forEachIndexed { number, members ->
                    if (wallets.size > 1) {
                        Text(
                            stringResource(R.string.accounts_wallet, number + 1),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                        )
                    }
                    members.forEach { summary ->
                        val active = summary.account.id == activeId
                        WalletListItem(
                            leadingContent = { WalletAccountMark(summary.account.name, accounts.indexOf(summary), active = active) },
                            headlineContent = { Text(summary.account.name) },
                            trailingContent = { Text(formatAmount(summary.balanceMsat)) },
                            modifier = Modifier.selectable(selected = active, role = Role.RadioButton) { onSelect(summary.account.id) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canAdd)
                    WalletButton(kind = ButtonKind.Secondary, onClick = onAdd, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_add))
                    }
                WalletButton(kind = ButtonKind.Secondary, onClick = onMove, enabled = accounts.size > 1, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accounts_move))
                }
            }
            if (canAddWallet) {
                WalletButton(kind = ButtonKind.Text, onClick = onAddWallet, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.accounts_add_wallet))
                }
            }
            Text(
                stringResource(R.string.accounts_separate_backups),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}
