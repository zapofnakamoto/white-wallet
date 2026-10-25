package whitewallet.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.AccountSummary

/** What the account switcher can ask for. */
class AccountActions(val onSwitch: (String) -> Unit, val onAdd: () -> Unit, val onMove: () -> Unit)

/** Bottom sheet listing every account with its address and balance (Design Guide: wallet switcher). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcher(
    accounts: List<AccountSummary>,
    activeId: String?,
    /** False once the wallet holds as many accounts as the brand allows. */
    canAdd: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.accounts_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            accounts.forEach { summary ->
                ListItem(
                    leadingContent = { RadioButton(selected = summary.account.id == activeId, onClick = { onSelect(summary.account.id) }) },
                    headlineContent = { Text(summary.account.name) },
                    supportingContent = { Text(summary.profile?.address ?: summary.account.host) },
                    trailingContent = { Text(formatAmount(summary.balanceMsat)) },
                    modifier = Modifier.clickable { onSelect(summary.account.id) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canAdd) OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.accounts_add)) }
                OutlinedButton(onClick = onMove, enabled = accounts.size > 1, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.accounts_move)) }
            }
            Text(
                stringResource(R.string.accounts_separate_backups),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}
