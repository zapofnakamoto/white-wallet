package whitewallet.feature.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.ScanProgress

/** Adding a mint, step by step: pick, what it is, the scan, what was found. */
@RunWith(RobolectricTestRunner::class)
class AddMintScreenTest {
    @get:Rule val compose = createComposeRule()

    private val options = listOf(MintOption.Suggested("mint.example"), MintOption.Custom)
    private val pick = AddMintState(mintOptions = options, selectedMint = options.first(), host = "mint.example")
    private val preview =
        MintPreview.Ready(
            host = "mint.example",
            description = "A test mint",
            feeBaseMsat = 1000,
            feePpm = 5000,
            minSendableMsat = 1000,
            maxSendableMsat = 1_000_000_000,
            addressFeeMsat = null,
            signingKey = "02aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            knownKey = null,
            legacy = false,
        )

    private fun show(state: AddMintState, actions: AddMintActions = AddMintActions()) = compose.setContent { AddMintScreen(state, actions) }

    @Test
    fun inTheSetupAMintMayBeSkippedWhereTheBrandAllows() {
        var skipped = false
        show(pick.copy(setup = true, canSkip = true), AddMintActions(onSkip = { skipped = true }))
        compose.onNodeWithText(text(R.string.add_mint_setup_title)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_skip_mint_hint)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.onboarding_skip_mint)).performScrollTo().performClick()
        assertTrue(skipped)
    }

    @Test
    fun outsideTheSetupOrWithoutTheBrandsLeaveThereIsNoSkip() {
        show(pick.copy(setup = true, canSkip = false))
        compose.onNodeWithText(text(R.string.onboarding_skip_mint)).assertDoesNotExist()
    }

    @Test
    fun backOnTheSetupsPickAsksBeforeTheWalletGoes() {
        var abandoned = false
        show(pick.copy(setup = true, confirmAbandon = true), AddMintActions(onAbandon = { abandoned = true }))
        compose.onNodeWithText(text(R.string.setup_abandon_body)).assertExists()
        compose.onNodeWithText(text(R.string.setup_abandon_confirm)).performClick()
        assertTrue(abandoned)
    }

    @Test
    fun anUnreachableMintSaysWhy() {
        show(pick.copy(error = AddMintError.Unreachable("timeout")))
        compose.onNodeWithText(text(R.string.add_mint_error_unreachable, "timeout")).assertExists()
    }

    @Test
    fun whatTheMintIsShowsBeforeAnythingIsAdded() {
        var added = false
        var back = false
        show(pick.copy(step = AddMintStep.Info, preview = preview), AddMintActions(onAdd = { added = true }, onBack = { back = true }))
        compose.onNodeWithText("A test mint").assertExists()
        compose.onNodeWithText(text(R.string.add_mint_fees)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_address_free)).assertExists()
        compose.onNodeWithText("02aabbcc…66778899").assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint_custody)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.add_mint_legacy)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_add)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.add_mint_other)).performScrollTo().performClick()
        assertTrue(added && back)
    }

    @Test
    fun aMintTheWalletHasSaysSoAndAnOldOneToo() {
        show(pick.copy(step = AddMintStep.Info, preview = preview.copy(legacy = true), known = setOf("mint.example")))
        compose.onNodeWithText(text(R.string.add_mint_known)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.add_mint_legacy)).performScrollTo().assertExists()
    }

    @Test
    fun theScanShowsEachAccount() {
        val progress = ScanProgress(listOf(ScanProgress.AccountScan(0, 12, 5_000_000, true), ScanProgress.AccountScan(1, 3, 0, false)))
        show(pick.copy(step = AddMintStep.Adding, preview = preview, progress = progress))
        compose.onNodeWithText(text(R.string.add_mint_adding_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_account, 1)).assertExists()
        compose.onNodeWithText(plural(R.plurals.add_mint_keys_checked, 12, 12)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_account, 2)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_account_looking)).assertExists()
    }

    @Test
    fun theResultListsTheAccountsWithMoney() {
        var done = false
        show(
            pick.copy(
                step = AddMintStep.Result,
                preview = preview,
                progress = ScanProgress(emptyList()),
                found = listOf(FoundAccount("Main", 2_000_000), FoundAccount("Account 2", 1_000_000)),
            ),
            AddMintActions(onDone = { done = true }),
        )
        compose.onNodeWithText(plural(R.plurals.add_mint_found_accounts, 2, 2)).assertExists()
        compose.onNodeWithText("Account 2").assertExists()
        compose.onNodeWithText(text(R.string.add_mint_ok)).performScrollTo().performClick()
        assertTrue(done)
    }

    @Test
    fun aWalletMadeHereIsNotScannedAndSaysTheMintIsAdded() {
        show(pick.copy(step = AddMintStep.Result, preview = preview, progress = null))
        compose.onNodeWithText(text(R.string.add_mint_added_body)).assertExists()
    }

    @Test
    fun nothingFoundSaysSo() {
        show(pick.copy(step = AddMintStep.Result, preview = preview, progress = ScanProgress(emptyList())))
        compose.onNodeWithText(text(R.string.add_mint_found_none)).assertExists()
    }

    @Test
    fun aKeyThatChangedSinceItWasShownStopsTheAdd() {
        show(pick.copy(step = AddMintStep.Result, preview = preview, failed = "changed", keyChanged = true))
        compose.onNodeWithText(text(R.string.add_mint_failed_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_failed_key)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_try_again)).assertExists()
    }
}
