package whitewallet.feature.claim

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason
import whitewallet.core.wallet.ClaimFailure
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.ReclaimResult
import whitewallet.core.wallet.WithdrawLink
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.RejectionKind

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

// Every other way a claim or a take back ends: a problem or a wait, on the same layout as the success screens.

private val keptNote =
    ReceivedNote("q", "acc", "cash.example", "w", "k1", null, null, 4_000_000, null, null, ReceivedState.Waiting, null, 1, 1)

@Composable private fun Outcome(state: ClaimState) = ClaimScreen(state, onBack = {}, onInputChange = {}, onCheck = {}, onClaim = {})

/** Someone else claimed it first, or its sender took it back. */
@WalletPreviews
@Composable
private fun ClaimSpentPreview() =
    Outcome(ClaimState(result = ClaimResult.Failed(RejectionKind.AlreadyInUse, "already in use", ClaimFailure.Spent)))

/** Refused without proof it is gone: kept and tried again. */
@WalletPreviews
@Composable
private fun ClaimRetryingPreview() =
    Outcome(ClaimState(result = ClaimResult.Failed(RejectionKind.RateLimited, "Too many requests.", ClaimFailure.Retrying)))

/** Refused for a reason the app has no words of its own for. */
@WalletPreviews @Composable private fun ClaimRefusedPreview() = Outcome(ClaimState(result = ClaimResult.Failed(RejectionKind.Other, null)))

/** A withdraw link that paid nothing over Lightning. */
@WalletPreviews
@Composable
private fun ClaimNothingReceivedPreview() =
    Outcome(
        ClaimState(
            preview =
                ClaimPreview.Melt(
                    WithdrawLink("lnbits.example", "https://lnbits.example/w", "https://lnbits.example/cb", "tok", 1_000, 4_000_000, null),
                    reason = null,
                    target = "mint.example",
                    mints = listOf("mint.example"),
                    amountMsat = 4_000_000,
                    expectedMsat = 3_999_000,
                ),
            result = ClaimResult.Failed(RejectionKind.Other, "Invoice expired."),
        )
    )

/** Kept: claimed on its own as soon as it can be. */
@WalletPreviews @Composable private fun ClaimKeptPreview() = Outcome(ClaimState(result = ClaimResult.Kept(keptNote)))

/** Kept while its lock holds. */
@WalletPreviews
@Composable
private fun ClaimLockedPreview() = Outcome(ClaimState(result = ClaimResult.Kept(keptNote.copy(state = ReceivedState.Locked))))

/** Kept until its mint's signing key matches again. */
@WalletPreviews
@Composable
private fun ClaimKeyChangedPreview() = Outcome(ClaimState(result = ClaimResult.Kept(keptNote.copy(state = ReceivedState.KeyChanged))))

/** Its mint won't keep it: Lightning can still bring it in. */
@WalletPreviews
@Composable
private fun ClaimLightningOnlyPreview() =
    Outcome(
        ClaimState(
            result = ClaimResult.Kept(keptNote.copy(state = ReceivedState.LightningOnly, unclaimedReason = UnclaimedReason.NoSigningKey))
        )
    )

/** No answer yet: asked again on the next refresh. */
@WalletPreviews @Composable private fun ClaimPendingPreview() = Outcome(ClaimState(result = ClaimResult.Pending))

/** Taking a note back that someone claimed meanwhile. */
@WalletPreviews @Composable private fun TakeBackSpentPreview() = Outcome(ClaimState(takenBack = ReclaimResult.AlreadySpent))

/** Taking back refused, with the mint's words. */
@WalletPreviews
@Composable
private fun TakeBackFailedPreview() =
    Outcome(ClaimState(takenBack = ReclaimResult.Failed(PayProblem.Refused, mintMessage = "Try again later.")))

/** Taken back once its mint knows it again. */
@WalletPreviews @Composable private fun TakeBackWhenKnownPreview() = Outcome(ClaimState(takenBack = ReclaimResult.WhenKnown))
