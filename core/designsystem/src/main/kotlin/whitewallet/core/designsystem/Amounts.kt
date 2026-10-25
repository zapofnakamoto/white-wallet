package whitewallet.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import whitewallet.core.model.AmountUnit

/** The unit amounts are shown in; the app provides the user's choice (or the brand's default) at its root. */
val LocalAmountUnit = staticCompositionLocalOf { AmountUnit.Bitcoin }

/**
 * An amount in the current [LocalAmountUnit], as a whole number of the smallest unit with the locale's digit
 * grouping: "₿12,450" (BIP-177) or "12,450 sats". Sub-sat amounts are rounded down. Where the symbol goes, and the
 * plural of "sats", come from string resources, so each language can follow its own custom.
 */
@Composable
@ReadOnlyComposable
fun formatAmount(msat: Long): String {
    val sats = msat / 1000
    val number = NumberFormat.getIntegerInstance().format(sats)
    return when (LocalAmountUnit.current) {
        AmountUnit.Bitcoin -> stringResource(R.string.amount_bitcoin, number)
        AmountUnit.Sats -> pluralStringResource(R.plurals.amount_sats, quantity(sats), number)
    }
}

/** [formatAmount] as a screen reader should say it ("12,450 bitcoins" rather than "bitcoin sign 12,450"). */
@Composable
@ReadOnlyComposable
fun spokenAmount(msat: Long): String {
    val sats = msat / 1000
    val number = NumberFormat.getIntegerInstance().format(sats)
    return when (LocalAmountUnit.current) {
        AmountUnit.Bitcoin -> pluralStringResource(R.plurals.amount_bitcoin_spoken, quantity(sats), number)
        AmountUnit.Sats -> pluralStringResource(R.plurals.amount_sats, quantity(sats), number)
    }
}

/** The label of a field where an amount is typed (whole numbers of the smallest unit, in either unit). */
@Composable
@ReadOnlyComposable
fun amountInputLabel(): String = stringResource(
    when (LocalAmountUnit.current) {
        AmountUnit.Bitcoin -> R.string.amount_input_bitcoin
        AmountUnit.Sats -> R.string.amount_input_sats
    },
)

/** Plural rules take an Int; larger amounts use the form for a large count. */
private fun quantity(sats: Long): Int = sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
