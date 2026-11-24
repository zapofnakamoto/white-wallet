package whitewallet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.MintPattern
import whitewallet.brand.ui.NoteColors
import whitewallet.brand.ui.mintSeal
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.HostText
import whitewallet.core.designsystem.MintKeyWarning
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletMintMark
import whitewallet.core.designsystem.formatAmount

/**
 * The account's home mint as the first thing on the Mints page: a card in the home notes' colour with the mint's seal, its money large, and
 * [onChangeHome] inside (null: the home can't be changed here). A key that doesn't match turns the whole card the error colour and says so
 * in place of its notes; it stays the home card, in its place.
 */
@Composable
internal fun HomeMintCard(card: MintCard, onOpen: (String) -> Unit, onChangeHome: (() -> Unit)?) {
    val colors = cardColors(card, MaterialTheme.walletColors.note(true))
    val pattern = remember(card.host) { MintPattern.of(card.host) }
    Column(
        Modifier.fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.paper)
            .drawWithCache {
                // The mint's seal, faint behind the top right: the same mark its notes carry.
                val radius = 44.dp.toPx()
                val seal = mintSeal(pattern, Offset(size.width - radius - 16.dp.toPx(), radius + 16.dp.toPx()), radius, 2.dp.toPx())
                onDrawBehind { seal(colors.ink.copy(alpha = 0.15f), null) }
            }
            .clickable { onOpen(card.host) }
            .padding(20.dp)
    ) {
        Text(
            stringResource(R.string.settings_home_mint).toUpperCase(Locale.current),
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(8.dp))
        HostText(card.host, style = MaterialTheme.typography.titleMedium.copy(color = colors.ink))
        CardAmount(card.balanceMsat, MaterialTheme.typography.headlineMedium, colors)
        CardDetails(card, colors)
        onChangeHome?.let { change ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WalletButton(kind = ButtonKind.Secondary, onClick = change) {
                    Text(stringResource(R.string.mint_change_home), color = colors.ink)
                }
            }
        }
    }
}

/**
 * Another mint of the wallet, quieter than the home: its mark and name, the account's money there, and ✓ when trusted. One the account
 * doesn't use says so in place of an amount; one whose key doesn't match turns the error colour, as the home card does.
 */
@Composable
internal fun OtherMintCard(card: MintCard, onOpen: (String) -> Unit) {
    val colors = cardColors(card, NoteColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface))
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.paper)
            .clickable { onOpen(card.host) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // The amount at its own width, at most half the room beside the mark: a huge one gets smaller rather than squeezing the name.
        val amountMax = (maxWidth - 40.dp - 24.dp) / 2
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletMintMark(card.host, home = false, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HostText(
                        card.host,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    )
                    if (card.trusted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painterResource(DesignR.drawable.ic_check_circle),
                            contentDescription = stringResource(DesignR.string.notes_trusted),
                            Modifier.size(18.dp),
                            tint = colors.ink,
                        )
                    }
                }
                CardDetails(card, colors)
            }
            if (card.used) {
                Spacer(Modifier.width(12.dp))
                CardAmount(card.balanceMsat, MaterialTheme.typography.titleMedium, colors, Modifier.widthIn(max = amountMax))
            }
        }
    }
}

/** The account's money at a mint: one line, smaller where a large font or a long amount wouldn't fit, as on the bills. */
@Composable
private fun CardAmount(msat: Long, style: TextStyle, colors: NoteColors, modifier: Modifier = Modifier) =
    BasicText(
        formatAmount(msat),
        modifier,
        style = style.copy(color = colors.ink),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
    )

/** The error colours for a mint whose key doesn't match, whatever card it is; [usual] otherwise. */
@Composable
private fun cardColors(card: MintCard, usual: NoteColors) =
    if (card.keyChanged) NoteColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) else usual

/**
 * The line under a card's name: the key warning when it doesn't match; else "Not used in this account"; else its notes, addresses, and "Not
 * answering" when the mint didn't answer, in Home's words. Nothing when there is nothing to say.
 */
@Composable
private fun CardDetails(card: MintCard, colors: NoteColors) {
    val style = MaterialTheme.typography.bodySmall
    val quiet = colors.ink.copy(alpha = 0.8f)
    if (card.keyChanged) {
        MintKeyWarning(Modifier.padding(top = 2.dp), style = style, color = colors.ink)
        return
    }
    if (!card.used) {
        Text(stringResource(R.string.mints_card_unused), style = style, color = quiet)
        return
    }
    val parts = buildList {
        if (card.noteCount > 0 || card.isHome) add(pluralStringResource(DesignR.plurals.notes_count, card.noteCount, card.noteCount))
        if (card.addresses > 0) add(pluralStringResource(R.plurals.account_mints_addresses, card.addresses, card.addresses))
        if (!card.answering) add(stringResource(DesignR.string.mint_not_answering_short))
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(stringResource(DesignR.string.notes_detail_separator)), style = style, color = quiet)
}
