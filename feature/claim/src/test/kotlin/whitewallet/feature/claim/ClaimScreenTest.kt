package whitewallet.feature.claim

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.FaucetState
import whitewallet.core.wallet.ReclaimResult
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink

@RunWith(RobolectricTestRunner::class)
class ClaimScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: ClaimState, onCheck: () -> Unit = {}, onTakeBack: () -> Unit = {}) = compose.setContent {
        ClaimScreen(state, onBack = {}, onInputChange = {}, onCheck = onCheck, onClaim = {}, onTakeBack = onTakeBack)
    }

    private val ready =
        ClaimPreview.Ready(
            NoteLink("https://other.example/w", Spend.Bearer(ByteVector32(Hex.decode("66".repeat(32)))), 5_000_000, null),
            NoteInfo(
                "https://other.example/cb",
                5_000_000,
                PublicKey(Hex.decode("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")),
                null,
            ),
            amountMsat = 5_000_000,
            certified = true,
            isOwnMint = false,
            trusted = true,
        )

    @Test
    fun inDeveloperModeTheFaucetMakesANoteOnAnyLocalMint() {
        val asked = mutableListOf<String>()
        val mints = listOf("localhost:8111", "localhost:8112")
        var label = ""
        compose.setContent {
            label = stringResource(R.string.regtest_note, formatAmount(FAUCET_SATS * 1_000), "localhost:8112")
            ClaimScreen(
                ClaimState(faucetMints = mints),
                onBack = {},
                onInputChange = {},
                onCheck = {},
                onClaim = {},
                onFaucet = { asked += it },
            )
        }
        compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(listOf("localhost:8112"), asked)
    }

    @Test
    fun withoutDeveloperModeThereIsNoFaucet() {
        show(ClaimState())
        compose.onNodeWithText("localhost", substring = true).assertDoesNotExist()
        compose.onNodeWithText("regtest", substring = true).assertDoesNotExist()
    }

    @Test
    fun aFaucetThatMadeNoNoteSaysWhy() {
        show(ClaimState(faucetMints = listOf("localhost:8112"), faucet = FaucetState(error = "connection refused")))
        compose.onNodeWithText(text(R.string.regtest_note_failed, "connection refused")).assertExists()
    }

    @Test
    fun aCheckedNoteHasNoFaucetButtons() {
        show(ClaimState(input = "x", preview = ready, faucetMints = listOf("localhost:8112")))
        compose.onNodeWithText("localhost:8112", substring = true).assertDoesNotExist()
    }

    @Test
    fun aCheckedNoteSaysWhereItIsAndWhetherItsMintIsTrusted() {
        show(ClaimState(input = "x", preview = ready))
        compose.onNodeWithText(text(R.string.claim_mint, "other.example")).assertExists()
        compose.onNodeWithText(text(R.string.claim_trusted)).assertExists()
        compose.onNodeWithText(text(R.string.claim_other_mint, "other.example")).assertExists()
        compose.onNodeWithText("certified", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun withoutMintsOnlyWhatNeedsTheUserIsShown() {
        show(ClaimState(input = "x", preview = ready.copy(certified = false), showMints = false))
        compose.onNodeWithText(text(R.string.claim_mint, "other.example")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_trusted)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_other_mint, "other.example")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_not_certified)).assertExists()
    }

    @Test
    fun checkNeedsALink() {
        show(ClaimState())
        compose.onNodeWithText(text(R.string.claim_check)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.claim_scan)).assertIsEnabled()
    }

    @Test
    fun aLinkIsChecked() {
        var checked = false
        show(ClaimState(input = "lnurlw://mint.example/w?k1=00"), onCheck = { checked = true })
        compose.onNodeWithText(text(R.string.claim_check)).performClick()
        assertTrue(checked)
    }

    @Test
    fun theUsersOwnSharedNoteIsTakenBackNotClaimed() {
        var takenBack = false
        show(ClaimState(input = "x", preview = ClaimPreview.OwnShared("q", 5_000_000, reachable = true)), onTakeBack = { takenBack = true })
        compose.onNodeWithText(text(R.string.claim_own_shared)).assertExists()
        compose.onAllNodesWithText("Claim", substring = true).assertCountEquals(1) // the title only
        compose.onNodeWithText(text(R.string.claim_keep)).assertDoesNotExist()
        compose.onNodeWithText("Take back", substring = true).performScrollTo().performClick()
        assertTrue(takenBack)
    }

    @Test
    fun theUsersOwnSharedNoteOfflineOffersNothingButToCheckAgain() {
        show(ClaimState(input = "x", preview = ClaimPreview.OwnShared("q", 5_000_000, reachable = false)))
        compose.onNodeWithText(text(R.string.claim_own_offline)).assertExists()
        compose.onNodeWithText("Take back", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_keep)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_check)).assertIsEnabled()
    }

    @Test
    fun takingBackSaysHowItWent() {
        show(ClaimState(takenBack = ReclaimResult.AlreadySpent))
        compose.onNodeWithText(text(R.string.claim_spent)).assertExists()
        compose.onNodeWithText(text(R.string.claim_finish)).assertExists()
    }

    @Test
    fun aSpentNoteSaysSo() {
        show(ClaimState(input = "lnurlw://mint.example/w?k1=00", preview = ClaimPreview.AlreadySpent))
        compose.onNodeWithText(text(R.string.claim_spent)).assertExists()
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
        ClaimScreen(
            ClaimState(input = "x", preview = preview),
            onBack = {},
            onInputChange = {},
            onCheck = {},
            onClaim = {},
            onKeep = onKeep,
        )
    }

    @Test
    fun aNoteOpenedOfflineCanBeKept() {
        var kept = false
        showKeep(ClaimPreview.Offline(link, 5_000_000, 5_000_000), onKeep = { kept = true })
        compose.onNodeWithText(text(R.string.claim_offline)).assertExists()
        // A certified amount is shown plainly, without a word about it.
        compose.onNodeWithText("certified", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claim_kept_hint)).assertExists()
        compose.onNodeWithText(text(R.string.claim_keep)).performScrollTo().performClick()
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
        compose.onNodeWithText(text(R.string.claim_locked_for, "2 days")).assertExists()
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
        compose.onNodeWithText(text(R.string.claim_keep)).performScrollTo().performClick()
        assertTrue(kept)
    }

    @Test
    fun aNoteKeptForAChangedKeySaysWhatClaimsIt() {
        val note =
            ReceivedNote("q", "acc", "mint.example", "w", "k1", null, null, 5_000_000, null, null, ReceivedState.KeyChanged, null, 1, 1)
        show(ClaimState(result = ClaimResult.Kept(note)))
        compose.onNodeWithText(text(R.string.claim_kept_key_changed)).assertExists()
    }

    @Test
    fun aLinkThatDoesntUnlockItsNoteSaysSo() {
        show(ClaimState(input = "x", preview = ClaimPreview.NotSpendable))
        compose.onNodeWithText(text(R.string.claim_not_spendable)).assertExists()
    }

    @Test
    fun aNoteWithOtherPathsWarns() {
        show(ClaimState(input = "x", preview = ready.copy(hasOtherPaths = true)))
        compose.onNodeWithText(text(R.string.claim_other_paths)).assertExists()
    }

    @Test
    fun aKeptNoteSaysWhenItIsClaimed() {
        val note = ReceivedNote("q", "acc", "mint.example", "w", "k1", null, null, 5_000_000, null, null, ReceivedState.Waiting, null, 1, 1)
        show(ClaimState(result = ClaimResult.Kept(note)))
        compose.onNodeWithText(text(R.string.claim_kept)).assertExists()
        compose.onNodeWithText(text(R.string.claim_kept_hint)).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanIsCompleteForKeptNotes() {
        showKeep(ClaimPreview.Locked(link, null, 3_600, null))
        compose.onNodeWithText("Bis zu 1 Stunde gesperrt. Behalte ihn, und deine Wallet löst ihn ein, sobald er frei ist.").assertExists()
        compose.onNodeWithText("Behalten und später einlösen").assertExists()
    }
}
