package whitewallet.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.model.AccountChoice
import whitewallet.core.model.AccountSummary

/** What the account switcher can ask for: another account, the page where wallets and accounts are managed, moving funds. */
class AccountActions(val onSwitch: (String) -> Unit, val onManage: () -> Unit, val onMove: () -> Unit)

/**
 * Bottom sheet for switching account (Design Guide: wallet switcher): the accounts of the wallet in use, each with its balance. Another
 * wallet's accounts are a step away, under "Other wallets": using one of them changes the recovery phrase in use. It switches and moves
 * funds; adding, renaming and removing are in Settings → Wallets ([onManage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcher(
    accounts: List<AccountSummary>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
    /** The names the user gave wallets, by wallet id; a wallet without one shows its number. */
    walletNames: Map<String, String> = emptyMap(),
) {
    val wallets = accounts.groupBy { it.account.walletId }.toList()
    val activeWallet = accounts.firstOrNull { it.account.id == activeId }?.account?.walletId ?: wallets.firstOrNull()?.first
    var showOthers by rememberSaveable { mutableStateOf(false) }
    WalletSheet(onDismissRequest = onDismiss, openFully = true) {
        // Scrolls: with many accounts, the buttons and the backup hint below them would be cut off.
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.accounts_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // One choice among the accounts: each row is a radio button to screen readers; the active one's mark has a check.
            Column(Modifier.selectableGroup()) {
                wallets.forEachIndexed { number, (walletId, members) ->
                    if (walletId != activeWallet && !showOthers) return@forEachIndexed
                    if (wallets.size > 1) {
                        Text(
                            walletNames[walletId] ?: stringResource(R.string.accounts_wallet, number + 1),
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
            // The other wallets stay folded away until asked for: the sheet is about the wallet in use.
            if (wallets.size > 1 && !showOthers) {
                val others = wallets.size - 1
                WalletListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.accounts_other_wallets, others, others)) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { showOthers = true },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WalletButton(kind = ButtonKind.Secondary, onClick = onManage, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accounts_manage))
                }
                WalletButton(kind = ButtonKind.Secondary, onClick = onMove, enabled = accounts.size > 1, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accounts_move))
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

/**
 * The account in use as its mark, in the top bar of every page that shows one account's things: opens the [AccountSwitcher]. Read out by
 * its name (and wallet, with several). Nothing while [choice] isn't [AccountChoice.shown] or no account is in use.
 */
@Composable
fun WalletAccountButton(choice: AccountChoice, onClick: () -> Unit) {
    val active = choice.active?.takeIf { choice.shown } ?: return
    val name =
        if (choice.walletCount > 1)
            stringResource(
                R.string.account_in_wallet,
                choice.walletNames[active.account.walletId] ?: stringResource(R.string.accounts_wallet, choice.activeWalletNumber),
                active.account.name,
            )
        else active.account.name
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.account_switch), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        WalletAccountMark(active.account.name, choice.accounts.indexOf(active), size = 32.dp)
    }
}
