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

    private fun show(state: ScanState, onBack: () -> Unit = {}, onScan: () -> Unit = {}) = compose.setContent {
        ScanScreen(state, onBack, onScan)
    }

    private fun everyMintSaysWhereItsScanIs() {
        var scans = 0
        show(ScanState(mints), onScan = { scans++ })
        compose.onNodeWithText(text(R.string.scan_notes_title)).assertExists()
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
        val progress = ScanProgress(listOf(ScanProgress.AccountScan(0, 12, 0, true), ScanProgress.AccountScan(1, 3, 0, false)))
        show(ScanState(mints, scanning = true, host = "new.example", progress = progress))
        compose.onNodeWithText(text(R.string.scan_mint_scanning)).assertExists()
        compose.onNodeWithText(text(R.string.scan_mint_never)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_account, 2)).assertExists()
        // An account that is through may hold money already: "Nothing new", not "Nothing".
        compose.onNodeWithText(text(R.string.scan_account_nothing_new)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_account_empty)).assertDoesNotExist()
        compose.onNodeWithText(plural(R.plurals.add_mint_keys_checked, 12, 12)).assertExists()
        compose.onNodeWithText(text(R.string.scan_action)).assertDoesNotExist()
    }

    @Test
    fun whatWasFoundShowsAndDoneLeaves() {
        var left = 0
        var scans = 0
        show(ScanState(mints, result = ScanResult(foundMsat = 5_000_000, stoppedAt = emptyList())), { left++ }, { scans++ })
        compose.onNodeWithText(text(R.string.scan_result_none)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_result_stopped)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_done)).performScrollTo().performClick()
        assertEquals(1, left)
        compose.onNodeWithText(text(R.string.scan_again)).performScrollTo().performClick()
        assertEquals(1, scans)
    }

    private fun nothingNewSaysSoAndDoneLeaves() {
        var left = 0
        show(ScanState(mints, result = ScanResult(foundMsat = 0, stoppedAt = emptyList())), { left++ })
        compose.onNodeWithText(text(R.string.scan_result_none)).assertExists()
        compose.onNodeWithText(text(R.string.scan_again)).assertExists()
        compose.onNodeWithText(text(R.string.scan_done)).performScrollTo().performClick()
        assertEquals(1, left)
    }

    @Test fun nothingNewSaysSoAndDoneLeavesInEnglish() = nothingNewSaysSoAndDoneLeaves()

    @Test @Config(qualifiers = "de") fun nothingNewSaysSoAndDoneLeavesInGerman() = nothingNewSaysSoAndDoneLeaves()

    @Test
    fun aMintThatDidNotAnswerSaysToTryAgainOrLater() {
        var left = 0
        var scans = 0
        show(ScanState(mints, result = ScanResult(foundMsat = 0, stoppedAt = listOf("down.example"))), { left++ }, { scans++ })
        compose.onNodeWithText(text(R.string.scan_result_stopped)).assertExists()
        compose.onNodeWithText(text(R.string.scan_result_none)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_done)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_again)).performScrollTo().performClick()
        assertEquals(1, scans)
        compose.onNodeWithText(text(R.string.add_mint_later)).performScrollTo().performClick()
        assertEquals(1, left)
    }

    @Test
    fun untilTheMintsAreReadThePageShowsNoneAndNoButton() {
        show(ScanState(loaded = false))
        compose.onNodeWithText(text(R.string.scan_body)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_action)).assertDoesNotExist()
    }
}
