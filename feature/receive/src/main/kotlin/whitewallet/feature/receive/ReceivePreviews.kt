package whitewallet.feature.receive

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews

// The Receive screen's success screen (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is the screen's own.

/** A payment arrived. */
internal val receiveSuccessState = ReceiveState("satoshi@mint.example", receivedMsat = 21_000_000)

@WalletPreviews @Moment @Composable private fun ReceiveSuccessPreview() = ReceiveScreen(receiveSuccessState, onBack = {}, onClaim = {})
