package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** The test tag of [MintKeyWarning]. */
const val MINT_KEY_WARNING_TAG = "mint-key-warning"

/**
 * A mint whose signing key doesn't match, said the same way in every list, picker and card: a warning sign and [text], in [color] (the
 * error colour, or the ink of the error surface it is drawn on). Read as its words; the sign is decorative.
 */
@Composable
fun MintKeyWarning(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.key_mismatch_short),
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.error,
) {
    Row(
        modifier.testTag(MINT_KEY_WARNING_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(16.dp), tint = color)
        Text(text, style = style, color = color)
    }
}
