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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
 * The account's home mint as the first thing on the Mints page: a card in the home notes' colour with the mint's seal, ✓ when trusted (as
 * on the other cards), its money large, and [onChangeHome] inside (null: the home can't be changed here). A key that doesn't match turns
 * the whole card the error colour and says so in place of its notes; it stays the home card, in its place.
 */
@Composable
internal fun HomeMintCard(card: MintCard, onOpen: (String) -> Unit, onChangeHome: (() -> Unit)?) {
    val colors = cardColors(card, MaterialTheme.walletColors.note(true))
    val pattern = remember(card.host) { MintPattern.of(card.host) }
    val spoken = spokenCard(card)
    val open = stringResource(R.string.mints_card_open)
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
            .clickable(onClickLabel = open, role = Role.Button) { onOpen(card.host) }
            .semantics { contentDescription = spoken }
            .padding(20.dp)
    ) {
        // Read out as one (above), the label in its own case: its lines aren't, so nothing is said twice. Changing the home stays a button.
        Column(Modifier.semantics { hideFromAccessibility() }) {
            Text(
                stringResource(R.string.settings_home_mint).toUpperCase(Locale.current),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
            )
            Spacer(Modifier.height(8.dp))
            HostWithTrust(card, MaterialTheme.typography.titleMedium, colors)
            CardAmount(card.balanceMsat, MaterialTheme.typography.headlineMedium, colors)
            CardDetails(card, colors)
        }
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
    val spoken = spokenCard(card)
    val open = stringResource(R.string.mints_card_open)
    val amountStyle = MaterialTheme.typography.titleMedium
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.paper)
            .clickable(onClickLabel = open, role = Role.Button) { onOpen(card.host) }
            .semantics { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // The amount at its own width, at most half the room beside the mark: a huge one gets smaller rather than squeezing the name, and
        // goes under the name where it wouldn't fit even at its smallest.
        val amountMax = (maxWidth - 40.dp - 24.dp) / 2
        val amount = formatAmount(card.balanceMsat)
        val beside = card.used && fitsOnOneLine(amount, amountStyle, amountMax)
        // Read out as one (above): its lines aren't, so nothing is said twice.
        Row(Modifier.semantics { hideFromAccessibility() }, verticalAlignment = Alignment.CenterVertically) {
            WalletMintMark(card.host, home = false, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                HostWithTrust(card, MaterialTheme.typography.bodyLarge, colors)
                if (card.used && !beside) CardAmount(card.balanceMsat, amountStyle, colors)
                CardDetails(card, colors)
            }
            if (beside) {
                Spacer(Modifier.width(12.dp))
                CardAmount(card.balanceMsat, amountStyle, colors, Modifier.widthIn(max = amountMax))
            }
        }
    }
}

/** A card's mint by name, with ✓ after it when trusted: the same on every card, the home's too. */
@Composable
private fun HostWithTrust(card: MintCard, style: TextStyle, colors: NoteColors) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        HostText(card.host, Modifier.weight(1f, fill = false), style = style.copy(color = colors.ink))
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

/**
 * The account's money at a mint: one line, smaller where a large font or a long amount wouldn't fit, as on the bills; at its smallest on
 * more lines where even that wouldn't. An amount is never cut.
 */
@Composable
private fun CardAmount(msat: Long, style: TextStyle, colors: NoteColors, modifier: Modifier = Modifier) =
    BoxWithConstraints(modifier) {
        val amount = formatAmount(msat)
        val ink = style.copy(color = colors.ink)
        if (fitsOnOneLine(amount, style, maxWidth))
            BasicText(
                amount,
                style = ink,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = AMOUNT_MIN, maxFontSize = style.fontSize),
            )
        else BasicText(amount, style = ink.copy(fontSize = AMOUNT_MIN))
    }

/** The smallest an amount on a card gets. */
private val AMOUNT_MIN = 10.sp

/** Whether [text] fits in [width] on one line at [AMOUNT_MIN]. */
@Composable
private fun fitsOnOneLine(text: String, style: TextStyle, width: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { width.roundToPx() }.coerceAtLeast(0)
    return remember(text, style, px) {
        !measurer.measure(text, style.copy(fontSize = AMOUNT_MIN), maxLines = 1, constraints = Constraints(maxWidth = px)).hasVisualOverflow
    }
}

/** The error colours for a mint whose key doesn't match, whatever card it is; [usual] otherwise. */
@Composable
private fun cardColors(card: MintCard, usual: NoteColors) =
    if (card.keyChanged) NoteColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) else usual

/**
 * The line under a card's name: the key warning when it doesn't match; else [cardDetails], in Home's words. Nothing when there is nothing
 * to say.
 */
@Composable
private fun CardDetails(card: MintCard, colors: NoteColors) {
    val style = MaterialTheme.typography.bodySmall
    if (card.keyChanged) {
        MintKeyWarning(Modifier.padding(top = 2.dp), style = style, color = colors.ink)
        return
    }
    val parts = cardDetails(card)
    if (parts.isNotEmpty())
        Text(
            parts.joinToString(stringResource(DesignR.string.notes_detail_separator)),
            style = style,
            color = colors.ink,
        )
}

/**
 * What a card says under its name, the key warning aside: "Not used in this account"; else its notes (when it has some), addresses, and
 * "Not answering" when the mint didn't answer.
 */
@Composable
private fun cardDetails(card: MintCard): List<String> =
    when {
        card.keyChanged -> emptyList()
        !card.used -> listOf(stringResource(R.string.mints_card_unused))
        else ->
            buildList {
                if (card.noteCount > 0) add(pluralStringResource(DesignR.plurals.notes_count, card.noteCount, card.noteCount))
                if (card.addresses > 0) add(pluralStringResource(R.plurals.account_mints_addresses, card.addresses, card.addresses))
                if (!card.answering) add(stringResource(DesignR.string.mint_not_answering_short))
            }
    }

/**
 * What a screen reader says for a card, in its own words and case: "Home mint" on the home's, the host, the amount where it shows, what it
 * says under its name, "Trusted" for its ✓, then the key warning.
 */
@Composable
private fun spokenCard(card: MintCard): String = buildList {
    if (card.isHome) add(stringResource(R.string.settings_home_mint))
    add(card.host)
    if (card.used) add(formatAmount(card.balanceMsat))
    addAll(cardDetails(card))
    if (card.trusted) add(stringResource(DesignR.string.notes_trusted))
    if (card.keyChanged) add(stringResource(DesignR.string.key_mismatch_short))
}
    .joinToString(stringResource(R.string.mints_card_read_separator))
