package whitewallet.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.ScanResult

/** The scan page: each mint says whether it was looked through, one button looks through all of them. */
@RunWith(RobolectricTestRunner::class)
class ScanScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun mint(host: String, scan: MintScan, at: Long? = null) =
        MintInfo(host, pubkey = "02aa", trusted = true, trustedByDefault = null, scan = scan, scannedAtMillis = at)

    private val then = 1_700_000_000_000
    private val mints =
        listOf(
            mint("home.example", MintScan.Finished, 0),
            mint("dated.example", MintScan.Finished, then),
            mint("new.example", MintScan.Never),
            mint("down.example", MintScan.Unfinished),
        )

    private fun show(state: ScanState, onScan: () -> Unit = {}) = compose.setContent { ScanScreen(state, {}, onScan) }

    private fun everyMintSaysWhereItsScanIs() {
        var scans = 0
        show(ScanState(mints)) { scans++ }
        compose.onNodeWithText(text(R.string.scan_title)).assertExists()
        compose.onNodeWithText(text(R.string.scan_body)).assertExists()
        // A scan from before its time was recorded says only that it was done.
        compose.onNodeWithText(text(R.string.scan_mint_finished)).assertExists()
        compose.onNodeWithText(text(R.string.scan_mint_finished_at, recentTime(then))).assertExists()
        compose.onNodeWithText(text(R.string.scan_mint_never)).assertExists()
        compose.onNodeWithText(text(R.string.scan_mint_unfinished)).assertExists()
        compose.onNodeWithText(text(R.string.scan_action)).performScrollTo().performClick()
        assertEquals(1, scans)
    }

    @Test fun everyMintSaysWhereItsScanIsInEnglish() = everyMintSaysWhereItsScanIs()

    @Test @Config(qualifiers = "de") fun everyMintSaysWhereItsScanIsInGerman() = everyMintSaysWhereItsScanIs()

    @Test
    fun whileItScansTheMintAndItsAccountsShow() {
        val progress = ScanProgress(listOf(ScanProgress.AccountScan(0, 12, 5_000_000, true), ScanProgress.AccountScan(1, 3, 0, false)))
        show(ScanState(mints, scanning = true, host = "new.example", progress = progress))
        compose.onNodeWithText(text(R.string.scan_mint_scanning)).assertExists()
        compose.onNodeWithText(text(R.string.scan_mint_never)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_account, 2)).assertExists()
        compose.onNodeWithText(plural(R.plurals.add_mint_keys_checked, 12, 12)).assertExists()
        compose.onNodeWithText(text(R.string.scan_action)).assertDoesNotExist()
    }

    @Test
    fun whatWasFoundShowsAndItCanBeScannedAgain() {
        show(ScanState(mints, result = ScanResult(foundMsat = 5_000_000, stoppedAt = emptyList())))
        compose.onNodeWithText(text(R.string.scan_result_none)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_result_stopped)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_again)).assertExists()
    }

    @Test
    fun nothingNewSaysSo() {
        show(ScanState(mints, result = ScanResult(foundMsat = 0, stoppedAt = emptyList())))
        compose.onNodeWithText(text(R.string.scan_result_none)).assertExists()
    }

    @Test
    fun aMintThatDidNotAnswerSaysToTryAgain() {
        show(ScanState(mints, result = ScanResult(foundMsat = 0, stoppedAt = listOf("down.example"))))
        compose.onNodeWithText(text(R.string.scan_result_stopped)).assertExists()
        compose.onNodeWithText(text(R.string.scan_result_none)).assertDoesNotExist()
    }
}
