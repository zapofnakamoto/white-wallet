package whitewallet.feature.claim

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import whitewallet.core.wallet.ClaimPreview
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
        amountMsat = 5_000_000, certified = true, isOwnService = false, trusted = true,
    )

    @Test
    fun aCheckedNoteSaysWhereItIsAndWhetherItsMintIsTrusted() {
        show(ClaimState(input = "x", preview = ready))
        compose.onNodeWithText("On other.example").assertExists()
        compose.onNodeWithText("✓ A service you trust").assertExists()
        compose.onNodeWithText("This note is held by other.example and stays there.").assertExists()
    }

    @Test
    fun withoutMintsOnlyWhatNeedsTheUserIsShown() {
        show(ClaimState(input = "x", preview = ready.copy(certified = false), showMints = false))
        compose.onNodeWithText("On other.example").assertDoesNotExist()
        compose.onNodeWithText("✓ A service you trust").assertDoesNotExist()
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
}
