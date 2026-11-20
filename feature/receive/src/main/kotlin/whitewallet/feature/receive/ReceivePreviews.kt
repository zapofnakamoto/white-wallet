package whitewallet.feature.receive

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews

// The Receive screen, ready to receive, and its success screen (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is
// the screen's own.

/** A payment arrived. */
internal val receiveSuccessState = ReceiveState("satoshi@mint.example", receivedMsat = 21_000_000)

@WalletPreviews @Moment @Composable private fun ReceiveSuccessPreview() = ReceiveScreen(receiveSuccessState, onBack = {}, onClaim = {})

/** The account's Lightning address and its QR code, on the home mint; another mint to pick. */
internal val receiveState =
    ReceiveState(
        address = "satoshi@mint.example",
        addressCount = 1,
        homeHost = "mint.example",
        mints =
            listOf(
                ReceiveMint("mint.example", isHome = true, address = "satoshi@mint.example"),
                ReceiveMint("cash.example", isHome = false, address = null),
            ),
    )

@WalletPreviews @Composable private fun ReceivePreview() = ReceiveScreen(receiveState, onBack = {}, onClaim = {})
