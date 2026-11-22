package whitewallet.feature.claim

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.ReclaimResult
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink

// Claiming a note, in its everyday state, and its success screens (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text
// is the screen's own.

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

/** The note claimed: its money is in. */
internal val noteClaimedState = claimState.copy(result = ClaimResult.Claimed(4_000_000, certified = false))

@WalletPreviews
@Moment
@Composable
private fun NoteClaimedPreview() = ClaimScreen(noteClaimedState, onBack = {}, onInputChange = {}, onCheck = {}, onClaim = {})

/** A note the user had handed out, taken back from its link. */
internal val noteTakenBackState = ClaimState(takenBack = ReclaimResult.Reclaimed(4_000_000))

@WalletPreviews
@Moment
@Composable
private fun NoteTakenBackPreview() = ClaimScreen(noteTakenBackState, onBack = {}, onInputChange = {}, onCheck = {}, onClaim = {})
