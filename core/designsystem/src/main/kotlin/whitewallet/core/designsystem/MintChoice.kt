package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/**
 * A mint [MintChoiceSheet] offers: its host, whether it is the [home] mint (its mark's colours), a line on what it has for this (money, an
 * address), and whether it can be picked. [keyChanged]: its signing key doesn't match, said with [MintKeyWarning] in place of the line.
 */
data class MintOption(
    val host: String,
    val home: Boolean,
    val detail: String?,
    val enabled: Boolean = true,
    val keyChanged: Boolean = false,
)

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
                // No mark for the wallet's own pick, but its room: the names stay in one column.
                automatic?.let { (name, line) ->
                    Choice({ Text(name) }, line, selected == null, enabled = true, mark = { Spacer(Modifier.size(MARK_SIZE)) }) {
                        onSelect(null)
                    }
                }
                options.forEach { option ->
                    Choice(
                        { HostText(option.host) },
                        option.detail,
                        selected == option.host,
                        option.enabled,
                        keyChanged = option.keyChanged,
                        mark = { WalletMintMark(option.host, option.home, size = MARK_SIZE) },
                    ) {
                        onSelect(option.host)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(
    name: @Composable () -> Unit,
    line: String?,
    selected: Boolean,
    enabled: Boolean,
    keyChanged: Boolean = false,
    mark: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    WalletListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WalletRadioButton(selected = selected, onClick = null, enabled = enabled)
                mark()
            }
        },
        headlineContent = name,
        // A signing key that doesn't match is said as everywhere, in place of the line.
        supportingContent = if (keyChanged) ({ MintKeyWarning() }) else line?.let { { Text(it) } },
        // One that can't be picked stays in the list, quieter: its line says why.
        modifier =
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
    )
}

private const val DISABLED_ALPHA = 0.38f

private val MARK_SIZE = 32.dp
