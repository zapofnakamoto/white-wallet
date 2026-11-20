package whitewallet.feature.send

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.PayDestination
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayResult

// The Send screen's success screen (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is the screen's own.

/** A payment to a Lightning address went out, with a fee. */
internal val sendSuccessState =
    SendState(
        preview =
            PayPreview.Ready(
                destination = PayDestination.PayLink("https://mint.example/.well-known/lnurlp/hal", "", "", 1_000, 1_000_000_000, 0, false),
                fixedAmountMsat = null,
                minMsat = 1_000,
                maxMsat = 1_000_000_000,
                commentMaxLength = 0,
                payee = "hal@mint.example",
                description = null,
                availableMsat = 100_000_000,
            ),
        result = PayResult.Paid(amountMsat = 21_000_000, feeMsat = 2_000),
    )

@WalletPreviews
@Moment
@Composable
private fun SendSuccessPreview() =
    SendScreen(sendSuccessState, onBack = {}, onDone = {}, actions = SendActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}))
