package whitewallet.feature.claim

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink

@RunWith(RobolectricTestRunner::class)
class ClaimScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ClaimState, onCheck: () -> Unit = {}) = compose.setContent {
        ClaimScreen(state, onBack = {}, onInputChange = {}, onCheck = onCheck, onClaim = {})
    }

    private val ready = ClaimPreview.Ready(
        NoteLink("https://other.example/w", Spend.Bearer(ByteVector32(Hex.decode("66".repeat(32)))), 5_000_000, null),
        NoteInfo("https://other.example/cb", 5_000_000, PublicKey(Hex.decode("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")), null),
        amountMsat = 5_000_000, certified = true, isOwnMint = false, trusted = true,
    )

    @Test
    fun aCheckedNoteSaysWhereItIsAndWhetherItsMintIsTrusted() {
        show(ClaimState(input = "x", preview = ready))
        compose.onNodeWithText("On other.example").assertExists()
        compose.onNodeWithText("✓ Trusted").assertExists()
        compose.onNodeWithText("This note is held by other.example and stays there.").assertExists()
    }

    @Test
    fun withoutMintsOnlyWhatNeedsTheUserIsShown() {
        show(ClaimState(input = "x", preview = ready.copy(certified = false), showMints = false))
        compose.onNodeWithText("On other.example").assertDoesNotExist()
        compose.onNodeWithText("✓ Trusted").assertDoesNotExist()
        compose.onNodeWithText("This note is held by other.example and stays there.").assertDoesNotExist()
        compose.onNodeWithText("Not certified: the amount can't be verified.").assertExists()
    }

    @Test
    fun checkNeedsALink() {
        show(ClaimState())
        compose.onNodeWithText("Check note").assertIsNotEnabled()
        compose.onNodeWithText("Scan QR code").assertIsEnabled()
    }

    @Test
    fun aLinkIsChecked() {
        var checked = false
        show(ClaimState(input = "lnurlw://mint.example/w?k1=00"), onCheck = { checked = true })
        compose.onNodeWithText("Check note").performClick()
        assertTrue(checked)
    }

    @Test
    fun aSpentNoteSaysSo() {
        show(ClaimState(input = "lnurlw://mint.example/w?k1=00", preview = ClaimPreview.AlreadySpent))
        compose.onNodeWithText("This note has already been spent.").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanIsComplete() {
        show(ClaimState(input = "x", preview = ClaimPreview.AlreadyInWallet))
        compose.onNodeWithText("Schein einlösen").assertExists()
        compose.onNodeWithText("Dieser Schein ist bereits in deiner Wallet.").assertExists()
    }

    private val link = ready.link

    private fun showKeep(preview: ClaimPreview, onKeep: () -> Unit = {}) = compose.setContent {
        ClaimScreen(ClaimState(input = "x", preview = preview), onBack = {}, onInputChange = {}, onCheck = {}, onClaim = {}, onKeep = onKeep)
    }

    @Test
    fun aNoteOpenedOfflineCanBeKept() {
        var kept = false
        showKeep(ClaimPreview.Offline(link, 5_000_000, 5_000_000), onKeep = { kept = true })
        compose.onNodeWithText("No connection right now. Keep it, and your wallet claims it as soon as it's back online.").assertExists()
        compose.onNodeWithText(", certified", substring = true).assertExists()
        compose.onNodeWithText("Until then it isn't in your balance, your recovery phrase doesn't cover it, and whoever gave you the link can still spend it.").assertExists()
        compose.onNodeWithText("Keep and claim later").performScrollTo().performClick()
        assertTrue(kept)
    }

    @Test
    fun anAmountOnlyTheLinkStatesIsNotConfirmed() {
        showKeep(ClaimPreview.Offline(link, 5_000_000, null))
        compose.onNodeWithText("as the link says; not confirmed yet", substring = true).assertExists()
    }

    @Test
    fun aLockedNoteSaysUntilWhenOrForHowLong() {
        showKeep(ClaimPreview.Locked(link, null, 2 * 86_400L, 5_000_000))
        compose.onNodeWithText("Locked for up to 2 days. Keep it, and your wallet claims it once it opens.").assertExists()
    }

    @Test
    fun aWaitUnderAnHourIsInMinutes() {
        showKeep(ClaimPreview.Locked(link, null, 512, null))
        compose.onNodeWithText("Locked for up to 9 minutes", substring = true).assertExists()
    }

    @Test
    fun aLockByDateNamesTheDate() {
        showKeep(ClaimPreview.Locked(link, 1_893_456_000_000, null, 5_000_000))
        compose.onNodeWithText("Locked until ", substring = true).assertExists()
    }

    @Test
    fun aNoteOnAMintWhoseKeyChangedCanBeKeptUntilTheUserDecides() {
        var kept = false
        showKeep(ClaimPreview.KeyChanged(link, "other.example", 5_000_000), onKeep = { kept = true })
        compose.onNodeWithText("other.example now signs with another key than the one your wallet knows", substring = true).assertExists()
        compose.onNodeWithText("Keep and claim later").performScrollTo().performClick()
        assertTrue(kept)
    }

    @Test
    fun aNoteKeptForAChangedKeySaysWhatClaimsIt() {
        val note = ReceivedNote("q", "acc", "mint.example", "w", "k1", null, null, 5_000_000, null, null, ReceivedState.KeyChanged, null, 1, 1)
        show(ClaimState(result = ClaimResult.Kept(note)))
        compose.onNodeWithText("Kept. Your wallet claims it once you accept the mint's new signing key in its details.").assertExists()
    }

    @Test
    fun aLinkThatDoesntUnlockItsNoteSaysSo() {
        show(ClaimState(input = "x", preview = ClaimPreview.NotSpendable))
        compose.onNodeWithText("This link doesn't unlock the note it names, so it can't be claimed.").assertExists()
    }

    @Test
    fun aNoteWithOtherPathsWarns() {
        show(ClaimState(input = "x", preview = ready.copy(hasOtherPaths = true)))
        compose.onNodeWithText("This note can also be spent another way, so someone else may take it before you claim it.").assertExists()
    }

    @Test
    fun aKeptNoteSaysWhenItIsClaimed() {
        val note = ReceivedNote("q", "acc", "mint.example", "w", "k1", null, null, 5_000_000, null, null, ReceivedState.Waiting, null, 1, 1)
        show(ClaimState(result = ClaimResult.Kept(note)))
        compose.onNodeWithText("Kept. Your wallet claims it on its own as soon as it can.").assertExists()
        compose.onNodeWithText("Until then it isn't in your balance, your recovery phrase doesn't cover it, and whoever gave you the link can still spend it.").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanIsCompleteForKeptNotes() {
        showKeep(ClaimPreview.Locked(link, null, 3_600, null))
        compose.onNodeWithText("Bis zu 1 Stunde gesperrt. Behalte ihn, und deine Wallet löst ihn ein, sobald er frei ist.").assertExists()
        compose.onNodeWithText("Behalten und später einlösen").assertExists()
    }
}
