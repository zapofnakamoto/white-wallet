package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.ButtonKind

/** A mint [MintChoiceSheet] offers: its host, a line on what it has for this (money, an address), and whether it can be picked. */
data class MintOption(val host: String, val detail: String?, val enabled: Boolean = true)

/**
 * Which mint a payment or receive is on, said quietly with a way to pick another: "On mint.example.com · Change". [text] is the whole
 * sentence; [onChange] null leaves only the sentence. It wraps as one line of words.
 */
@Composable
fun MintChoiceLine(text: String, onChange: (() -> Unit)?, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onChange != null) {
            WalletButton(kind = ButtonKind.Text, onClick = onChange) { Text(stringResource(R.string.mint_choice_change)) }
        }
    }
}

/**
 * Picks the mint for one payment or receive: [automatic], when given, leaves it to the wallet (its title and line), then each of [options].
 * [selected] is the picked host, null for [automatic]. Picking closes the sheet.
 */
@Composable
fun MintChoiceSheet(
    title: String,
    options: List<MintOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    automatic: Pair<String, String>? = null,
) {
    WalletSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            // One choice: each row is a radio button to screen readers.
            Column(Modifier.selectableGroup()) {
                automatic?.let { (name, line) -> Choice(name, line, selected == null, enabled = true) { onSelect(null) } }
                options.forEach { option ->
                    Choice(option.host, option.detail, selected == option.host, option.enabled) { onSelect(option.host) }
                }
            }
        }
    }
}

@Composable
private fun Choice(name: String, line: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    WalletListItem(
        leadingContent = { WalletRadioButton(selected = selected, onClick = null, enabled = enabled) },
        headlineContent = { Text(name) },
        supportingContent = line?.let { { Text(it) } },
        // One that can't be picked stays in the list, quieter: its line says why.
        modifier =
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
    )
}

private const val DISABLED_ALPHA = 0.38f
