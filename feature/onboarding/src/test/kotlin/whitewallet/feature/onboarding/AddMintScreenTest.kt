package whitewallet.feature.onboarding

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.MintRefusal
import whitewallet.core.wallet.OnboardResult
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
            keyAnswered = true,
            knownKey = null,
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
        show(pick.copy(error = AddMintError.Unreachable))
        compose.onNodeWithText(text(R.string.add_mint_error_unreachable)).assertExists()
    }

    @Test
    fun offlineNoMintIsAskedAndTheScreenSaysSoBeforeTheUserTries() {
        show(pick.copy(offline = true))
        compose.onNodeWithText(text(R.string.add_mint_offline)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_next)).assertIsNotEnabled()
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
        compose.onNodeWithText(text(R.string.add_mint_add)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.add_mint_other)).performScrollTo().performClick()
        assertTrue(added && back)
    }

    @Test
    fun aMintTheWalletHasSaysSo() {
        show(pick.copy(step = AddMintStep.Info, preview = preview, known = setOf("mint.example")))
        compose.onNodeWithText(text(R.string.add_mint_known)).performScrollTo().assertExists()
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

    /** The anchors the screen asks the brand for, with their status. */
    private val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()

    private fun showDecorated(state: AddMintState, actions: AddMintActions = AddMintActions()) = compose.setContent {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        CompositionLocalProvider(LocalBrandDesign provides design) { AddMintScreen(state, actions) }
    }

    @Test
    fun aMintAddedOutsideTheSetupEndsOnTheSuccessScreen() {
        var done = false
        showDecorated(
            pick.copy(
                step = AddMintStep.Result,
                preview = preview,
                progress = ScanProgress(emptyList()),
                found = listOf(FoundAccount("Main", 2_000_000), FoundAccount("Account 2", 1_000_000)),
            ),
            AddMintActions(onDone = { done = true }),
        )
        assertEquals(listOf(Anchor.MintAdded to WalletStatus.Done(3_000_000)), decorated.distinct())
        compose.onNodeWithText(text(R.string.mint_added_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_found_total, "₿3,000")).assertExists()
        compose.onNodeWithText(plural(R.plurals.add_mint_found_accounts, 2, 2)).assertDoesNotExist()
        compose.onNodeWithText("Main").assertExists()
        compose.onNodeWithText("Account 2").assertExists()
        compose.onNodeWithText(text(DesignR.string.done)).performClick()
        assertTrue(done)
    }

    @Test
    fun backOnTheSuccessScreenIsDone() {
        var done = false
        var back = false
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AddMintScreen(
                pick.copy(step = AddMintStep.Result, preview = preview, progress = ScanProgress(emptyList())),
                AddMintActions(onBack = { back = true }, onDone = { done = true }),
            )
        }
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(done)
        assertFalse(back)
    }

    @Test
    fun theSetupsResultIsNotASuccessScreen() {
        showDecorated(pick.copy(setup = true, step = AddMintStep.Result, preview = preview, progress = ScanProgress(emptyList())))
        compose.onNodeWithText(text(R.string.add_mint_found_none)).assertExists()
        assertTrue(decorated.none { it.first == Anchor.MintAdded })
    }

    @Test
    fun theSetupsResultSaysTheMintIsAddedAndLeadsOn() {
        var next = 0
        show(pick.copy(setup = true, step = AddMintStep.Result, preview = preview), AddMintActions(onDone = { next++ }))
        // More steps follow: nothing says "Done", and the button is the one every step goes on with.
        compose.onNodeWithText(text(R.string.mint_added_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_done_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_added_body)).assertExists()
        compose.onNodeWithText(text(DesignR.string.done)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_next)).performClick()
        assertEquals(1, next)
    }

    @Test
    fun oneAccountWithMoneyIsNamedOnce() {
        showDecorated(
            pick.copy(
                step = AddMintStep.Result,
                preview = preview,
                progress = ScanProgress(emptyList()),
                found = listOf(FoundAccount("Main", 2_000_000)),
            )
        )
        compose.onNodeWithText(text(R.string.add_mint_found_total, "₿2,000")).assertExists()
        compose.onNodeWithText("Main").assertExists()
        compose.onNodeWithText("₿2,000").assertDoesNotExist()
    }

    @Test
    fun aWalletMadeHereIsNotScannedAndTheTitleSaysItAll() {
        showDecorated(pick.copy(step = AddMintStep.Result, preview = preview, progress = null))
        compose.onNodeWithText(text(R.string.mint_added_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_added_body)).assertDoesNotExist()
        assertEquals(listOf(Anchor.MintAdded to WalletStatus.Done(0)), decorated.distinct())
    }

    @Test
    fun nothingFoundSaysSoAsTheScanDoes() {
        showDecorated(pick.copy(step = AddMintStep.Result, preview = preview, progress = ScanProgress(emptyList())))
        compose.onNodeWithText(text(R.string.scan_result_none)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_found_none)).assertDoesNotExist()
        assertEquals(listOf(Anchor.MintAdded to WalletStatus.Done(0)), decorated.distinct())
    }

    @Test
    fun aKeyThatChangedSinceItWasShownStopsTheAdd() {
        var again = 0
        var cancelled = 0
        var back = 0
        show(
            pick.copy(
                step = AddMintStep.Result,
                preview = preview,
                failed = OnboardResult.MintInvalid(MintRefusal.KeyChanged),
                keyChanged = true,
            ),
            AddMintActions(onTryAgain = { again++ }, onCancel = { cancelled++ }, onBack = { back++ }),
        )
        // Nothing was added, so nothing went wrong with a restore: the title stays the flow's own.
        compose.onNodeWithText(text(R.string.add_mint_failed_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_failed_key)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_later)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.try_again)).performClick()
        assertEquals(1, again)
        compose.onNodeWithText(text(DesignR.string.cancel)).performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithContentDescriptionBack().performClick()
        assertEquals(1, back)
    }

    @Test
    fun inTheSetupAFailedAddLeadsToAnotherMint() {
        var picked = 0
        show(
            pick.copy(setup = true, step = AddMintStep.Result, preview = preview, failed = OnboardResult.NetworkError("timeout")),
            AddMintActions(onDone = { picked++ }),
        )
        compose.onNodeWithText(text(DesignR.string.cancel)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_other)).performClick()
        assertEquals(1, picked)
    }

    @Test
    fun aMintTheWalletHasWithAnotherKeyShowsBothKeysAndAddsNothing() {
        var accepted: String? = null
        var back = 0
        val remembered = "03" + "11".repeat(32)
        show(
            pick.copy(step = AddMintStep.Info, preview = preview.copy(knownKey = remembered)),
            AddMintActions(onAcceptKey = { accepted = it }, onBack = { back++ }),
        )
        compose.onNodeWithText(text(DesignR.string.mint_key_changed, "mint.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_remembered)).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_new)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_known)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_add)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_other)).performScrollTo().performClick()
        assertEquals(1, back)
        // Accepting asks first; cancelled, nothing is accepted.
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).performScrollTo().performClick()
        compose.onNodeWithText(text(DesignR.string.cancel)).performClick()
        assertEquals(null, accepted)
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).performScrollTo().performClick()
        compose.onNodeWithText(text(DesignR.string.mint_key_accept_confirm)).performClick()
        assertEquals(preview.signingKey, accepted)
    }

    @Test
    fun aMintTheWalletHasWithTheSameKeyIsAddedAgain() {
        show(pick.copy(step = AddMintStep.Info, preview = preview.copy(knownKey = preview.signingKey)))
        compose.onNodeWithText(text(R.string.add_mint_known)).performScrollTo().assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_add)).performScrollTo().assertExists()
    }

    @Test
    fun anAcceptedKeyEndsOnItsOwnSuccessScreen() {
        var done = 0
        showDecorated(pick.copy(step = AddMintStep.Result, preview = preview, keyAccepted = true), AddMintActions(onDone = { done++ }))
        assertEquals(listOf(Anchor.MintKeyAccepted to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(DesignR.string.mint_key_accepted_title)).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_accepted, "mint.example")).assertExists()
        // The mint was known before: not "added".
        compose.onNodeWithText(text(R.string.mint_added_title)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.done)).performClick()
        assertEquals(1, done)
    }

    @Test
    fun inASetupAnAcceptedKeySaysSoAndGoesOn() {
        var done = 0
        showDecorated(
            pick.copy(step = AddMintStep.Result, preview = preview, keyAccepted = true, setup = true),
            AddMintActions(onDone = { done++ }),
        )
        assertTrue(decorated.none { it.first == Anchor.MintKeyAccepted })
        compose.onNodeWithText(text(DesignR.string.mint_key_accepted, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_next)).performScrollTo().performClick()
        assertEquals(1, done)
    }

    @Test
    fun aScanThatStoppedIsTriedAgainRightThereOrLater() {
        var again = 0
        var done = 0
        show(
            pick.copy(step = AddMintStep.Result, preview = preview, failed = OnboardResult.NetworkError("timeout"), kept = true),
            AddMintActions(onAdd = { again++ }, onDone = { done++ }),
        )
        compose.onNodeWithText(text(R.string.add_mint_failed_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_failed_body)).assertExists()
        // The system's words for why are no one's to read.
        compose.onNodeWithText("timeout", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.try_again)).performClick()
        compose.onNodeWithText(text(R.string.add_mint_later)).performClick()
        assertEquals(1 to 1, again to done)
    }

    @Test
    fun aMintThatShowsNoSigningKeyCantBeAddedAndIsCheckedAgain() {
        var again = 0
        var other = 0
        show(
            pick.copy(step = AddMintStep.Info, preview = preview.copy(signingKey = null)),
            AddMintActions(onCheckAgain = { again++ }, onBack = { other++ }),
        )
        compose.onNodeWithText(text(R.string.add_mint_key_none)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_error_mint_without_key)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.add_mint_add)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_mint_custody)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.check_again)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.add_mint_other)).performScrollTo().performClick()
        assertEquals(1 to 1, again to other)
    }

    @Test
    fun aMintThatCantBeAskedForItsKeyIsntAnswering() {
        show(pick.copy(setup = true, step = AddMintStep.Info, preview = preview.copy(signingKey = null, keyAnswered = false)))
        compose.onNodeWithText(text(R.string.add_mint_key_unanswered, "mint.example")).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.onboarding_error_mint_without_key)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_use)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.check_again)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aHeldMintThatCantBeAskedForItsKeyIsNotAChangedKey() {
        val held = preview.copy(knownKey = preview.signingKey, signingKey = null, keyAnswered = false)
        show(pick.copy(step = AddMintStep.Info, preview = held))
        compose.onNodeWithText(text(DesignR.string.mint_key_remembered)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_key_unanswered, "mint.example")).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.add_mint_add)).assertDoesNotExist()
    }

    @Test
    fun aHeldMintThatAnswersWithoutItsKeyStillShowsTheChangedKey() {
        show(pick.copy(step = AddMintStep.Info, preview = preview.copy(knownKey = preview.signingKey, signingKey = null)))
        compose.onNodeWithText(text(DesignR.string.mint_key_remembered)).performScrollTo().assertExists()
        compose.onNodeWithText(text(DesignR.string.check_again)).assertDoesNotExist()
    }

    @Test
    fun whileAMintIsCheckedAgainThePageStaysAndNothingCanBeTapped() {
        show(pick.copy(step = AddMintStep.Info, preview = preview.copy(signingKey = null), busy = true))
        compose.onNodeWithText(text(R.string.onboarding_error_mint_without_key)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.add_mint_other)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun offlineAMintWithoutItsKeyCantBeCheckedAgain() {
        show(pick.copy(step = AddMintStep.Info, preview = preview.copy(signingKey = null), offline = true))
        compose.onNodeWithText(text(R.string.add_mint_offline)).performScrollTo().assertExists()
        compose.onNodeWithText(text(DesignR.string.check_again)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aMintThatIsntOneSaysSoInTheAppsWords() {
        show(pick.copy(step = AddMintStep.Result, preview = preview, failed = OnboardResult.MintInvalid(MintRefusal.NotAMint)))
        compose.onNodeWithText(text(R.string.add_mint_error_invalid)).assertExists()
    }

    @Test
    fun aMintThatRefusedIsQuotedOnlyInItsOwnWords() {
        show(pick.copy(step = AddMintStep.Result, preview = preview, failed = OnboardResult.MintInvalid(MintRefusal.Refused, "closed")))
        compose.onNodeWithText(text(R.string.onboarding_error_mint, "closed")).assertExists()
    }

    @Test
    fun aMintThatRefusesWhenPickedIsQuotedInItsOwnWords() {
        show(pick.copy(error = AddMintError.Invalid(MintRefusal.Refused, "closed")))
        compose.onNodeWithText(text(R.string.onboarding_error_mint, "closed")).assertExists()
    }

    @Test
    fun aMintThatRefusesWithoutWordsSaysSoInTheAppsWords() {
        show(pick.copy(error = AddMintError.Invalid(MintRefusal.Refused)))
        compose.onNodeWithText(text(R.string.onboarding_error_mint_plain)).assertExists()
    }

    @Test
    fun aMintThatWasntAddedInASetupKeepsTheSetupsTitle() {
        show(
            pick.copy(
                setup = true,
                step = AddMintStep.Result,
                preview = preview,
                failed = OnboardResult.MintInvalid(MintRefusal.WithoutSigningKey),
                withoutSigningKey = true,
            )
        )
        compose.onNodeWithText(text(R.string.add_mint_setup_title)).assertExists()
        compose.onNodeWithText(text(R.string.mint_added_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_error_mint_without_key)).assertExists()
    }

    @Test
    fun aPickedMintWithoutItsKeySaysSoBeforeGoingOn() {
        var next = 0
        show(pick.copy(error = AddMintError.WithoutKey("mint.example", asked = true)), AddMintActions(onNext = { next++ }))
        compose.onNodeWithText(text(R.string.add_mint_pick_without_key, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_next)).performClick()
        assertEquals(1, next)
    }

    @Test
    fun aPickedMintThatCouldntBeAskedForItsKeyIsntAnswering() {
        show(pick.copy(error = AddMintError.WithoutKey("mint.example", asked = false)))
        compose.onNodeWithText(text(R.string.add_mint_key_unanswered, "mint.example")).assertExists()
    }

    @Test
    fun theListSaysWhatIsKnownToBeWrongWithAMint() {
        val several =
            listOf(MintOption.Suggested("mint.example"), MintOption.Suggested("down.example"), MintOption.Suggested("keyless.example"))
        val problems = mapOf("down.example" to MintProblem.NotAnswering, "keyless.example" to MintProblem.NoSigningKey)
        show(pick.copy(mintOptions = several, selectedMint = several.first(), problems = problems))
        compose.onNodeWithText(text(R.string.add_mint_row_not_answering)).assertDoesNotExist()
        compose.onNodeWithText("mint.example").performClick()
        compose.onNodeWithText("down.example").assertExists()
        compose.onNodeWithText(text(R.string.add_mint_row_not_answering)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_row_without_key)).assertExists()
    }

    @Test
    fun untilTheMintsToPickFromAreKnownOnlyTheTitleShows() {
        show(AddMintState(host = "mint.example", loaded = false))
        compose.onNodeWithText("mint.example").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_title)).assertExists()
        compose.onNodeWithText(text(R.string.add_mint_body)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_mint_next)).assertDoesNotExist()
    }
}
