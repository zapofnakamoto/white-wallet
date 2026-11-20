package whitewallet.feature.claim

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.ClaimPreview
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink

// Claiming a note, in its everyday state (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is the screen's own.

private val link = "lnurlw://cash.example/w?k1=" + "ab".repeat(32) + "&amount=4000000"

/** A note link checked at its mint: what it is worth, one tap from claiming. */
internal val claimState =
    ClaimState(
        input = link,
        preview =
            ClaimPreview.Ready(
                link = checkNotNull(NoteLink.parse(link)),
                info = NoteInfo("https://cash.example/w/callback", 4_000_000, mintPubkey = null, certificate = null),
                amountMsat = 4_000_000,
                certified = false,
                isOwnMint = false,
                trusted = true,
            ),
        home = "mint.example",
    )

@WalletPreviews
@Composable
private fun ClaimNotePreview() = ClaimScreen(claimState, onBack = {}, onInputChange = {}, onCheck = {}, onClaim = {})
