package whitewallet.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.Account
import whitewallet.core.model.AccountChoice
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Mint
import whitewallet.core.wallet.AddressShare
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MintUse
import whitewallet.core.wallet.TrustDefault

@RunWith(RobolectricTestRunner::class)
class SettingsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val mint =
        Mint(
            walletId = "w",
            host = "mint.example",
            withdrawUrl = "https://mint.example/w",
            mintPubkey = "02" + "ab".repeat(32),
            minSendableMsat = 1_000,
            maxSendableMsat = 1_000_000_000,
            feeBaseMsat = 1_000,
            feePpm = 0,
        )

    private fun details(reachability: MintDetails.Reachability) =
        MintDetails("mint.example", mint, reachability, "A test mint", 999_000, 1, isHome = true)

    @Test
    fun mintDetailsShowMoneyAndFees() {
        compose.setContent {
            MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false), onBack = {}, onRefresh = {})
        }
        compose.onNodeWithText(text(R.string.mint_reachable)).assertExists()
        compose.onNodeWithText("₿999 in 1 note").assertExists()
    }

    @Test
    fun aMintsPageSendsFromItAndReceivesOnIt() {
        val sent = mutableListOf<String>()
        val received = mutableListOf<String>()
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, canReceive = true),
                onBack = {},
                onRefresh = {},
                onSend = { sent += it },
                onReceive = { received += it },
            )
        }
        compose.onNodeWithText(text(R.string.mint_send)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mint_receive)).performScrollTo().performClick()
        assertEquals(listOf("mint.example"), sent)
        assertEquals(listOf("mint.example"), received)
    }

    @Test
    fun aMintWithNoMoneyOfTheAccountsWhereItCannotReceiveOffersNeither() {
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable).copy(balanceMsat = 0, noteCount = 0), loading = false),
                {},
                {},
            )
        }
        compose.onNodeWithText(text(R.string.mint_send)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mint_receive)).assertDoesNotExist()
    }

    @Test
    fun anAcceptedKeyIsSaidOnTheMintsPageOnce() {
        var shown = 0
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, keyAccepted = true),
                onBack = {},
                onRefresh = {},
                onKeyAcceptedShown = { shown++ },
            )
        }
        compose.onNodeWithText(text(DesignR.string.mint_key_accepted, "mint.example")).assertExists()
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithText(text(DesignR.string.mint_key_accepted, "mint.example")).assertDoesNotExist()
        assertEquals(1, shown)
    }

    @Test
    fun aKeyThatCouldNotBeAcceptedIsSaidOnTheMintsPageOnce() {
        var shown = 0
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, keyNotAccepted = true),
                onBack = {},
                onRefresh = {},
                onKeyAcceptedShown = { shown++ },
            )
        }
        compose.onNodeWithText(text(DesignR.string.mint_key_not_accepted, "mint.example")).assertExists()
        compose.mainClock.advanceTimeBy(20_000)
        assertEquals(1, shown)
    }

    @Test
    fun aMintThatPresentsAnotherKeyIsNotPaidFrom() {
        compose.setContent { MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.KeyChanged), loading = false), {}, {}) }
        compose.onNodeWithText(text(R.string.mint_send)).assertDoesNotExist()
    }

    @Test
    fun theKeyPageHasATabPerAddressNamedWhenThePhoneKnowsIt() {
        val index = IndexActions({}, {}, {}, {}, {}, {}, {}, {})
        val state =
            DerivedKeysState(
                hosts = listOf("mint.example"),
                host = "mint.example",
                shares = listOf(AddressShare(0, "anna@mint.example"), AddressShare(1, null)),
            )
        var chosen: KeySet? = null
        compose.setContent {
            DerivedKeysScreen(state, {}, {}, { chosen = it }, {}, {}, index, RotateActions({}, {}, {}, {}))
        }
        compose.onNodeWithText(text(R.string.keys_address_number, 1)).assertExists()
        compose.onNodeWithText("anna@mint.example").assertExists()
        compose.onNodeWithText(text(R.string.keys_address_number, 2)).performClick()
        assertEquals(KeySet.Share(1), chosen)
    }

    @Test
    fun theKeyPageAsksNothingOfAMintWhoseSigningKeyDoesNotMatch() {
        val index = IndexActions({}, {}, {}, {}, {}, {}, {}, {})
        val state = DerivedKeysState(hosts = listOf("mint.example"), host = "mint.example", keyChanged = true)
        compose.setContent { DerivedKeysScreen(state, {}, {}, {}, {}, {}, index, RotateActions({}, {}, {}, {})) }
        compose.onNodeWithText(text(DesignR.string.mint_key_changed, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.keys_scan_ahead, 0)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.keys_recheck_all)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.keys_find_index)).assertIsNotEnabled()
        // Setting the index by hand asks nothing.
        compose.onNodeWithText(text(R.string.keys_set_index)).assertIsEnabled()
    }

    @Test
    fun aChangedSigningKeyIsAWarning() {
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.KeyChanged).copy(presentedKey = "03" + "cd".repeat(32)), loading = false),
                {},
                {},
            )
        }
        compose.onNodeWithText(text(DesignR.string.key_mismatch_short)).assertExists()
        compose.onNodeWithText("doesn't match the one your wallet knows", substring = true).assertExists()
    }

    @Test
    fun aMintThatNoLongerShowsItsKeySaysSoAndHasNothingToAccept() {
        compose.setContent {
            MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.KeyChanged), loading = false), {}, {})
        }
        compose.onNodeWithText(text(DesignR.string.mint_key_changed, "mint.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_remembered)).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_new)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).assertDoesNotExist()
    }

    @Test
    fun aChangedSigningKeyIsUsedOnlyAfterTheUserConfirms() {
        val presented = "03" + "cd".repeat(32)
        val accepted = mutableListOf<String>()
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.KeyChanged).copy(presentedKey = presented), loading = false),
                onBack = {},
                onRefresh = {},
                onAcceptKey = { accepted += it },
            )
        }
        compose.onNodeWithText(text(DesignR.string.mint_key_remembered)).assertExists()
        compose.onNodeWithText(presented.chunked(4).joinToString(" ")).assertExists()
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).performClick()
        compose.onNodeWithText("Only do this if you know why mint.example changed its key", substring = true).assertExists()
        compose.onNodeWithText(text(DesignR.string.cancel)).performClick()
        assertEquals(emptyList<String>(), accepted)
        compose.onNodeWithText(text(DesignR.string.mint_key_accept)).performClick()
        compose.onNodeWithText(text(DesignR.string.mint_key_accept_confirm)).performClick()
        assertEquals(listOf(presented), accepted)
    }

    @Test
    fun appLockNeedsAScreenLock() {
        compose.setContent { SecuritySettingsScreen(appLock = false, available = false, onBack = {}, onAppLock = {}) }
        compose.onNodeWithText(text(R.string.security_no_screen_lock)).assertExists()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun theHintSaysWhenTheLockAsksAgain() {
        compose.setContent {
            SecuritySettingsScreen(appLock = false, available = true, onBack = {}, relockAfterMillis = 120_000, onAppLock = {})
        }
        compose
            .onNodeWithText("Ask for your fingerprint, face or screen lock when opening the app, and when returning to it after 2 minutes.")
            .assertExists()
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun aLockTheBrandAlwaysWantsHasNoSwitch() {
        compose.setContent {
            SecuritySettingsScreen(appLock = false, available = true, onBack = {}, always = true, relockAfterMillis = 0, onAppLock = {})
        }
        compose.onNodeWithText(text(R.string.security_app_lock_hint_always)).assertExists()
        compose.onNodeWithText(text(R.string.security_app_lock_on_always)).assertExists()
        compose.onNode(isToggleable()).assertDoesNotExist()
    }

    @Test
    fun aBackupDuringSetupCantBeLeft() {
        var left = false
        compose.setContent {
            BackupScreen(BackupState(), onBack = { left = true }, {}, {}, {}, {}, {}, {}, required = true)
        }
        compose.onNodeWithText(text(R.string.backup_show)).assertExists()
        compose.onNodeWithText(text(R.string.backup_not_now)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(DesignR.string.back), substring = true, ignoreCase = true).assertDoesNotExist()
        assertEquals(false, left)
    }

    @Test
    fun aCheckedBackupEndsOnTheSuccessScreenEvenDuringSetup() {
        val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        var left = 0
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                BackupScreen(BackupState(step = BackupStep.Success), onBack = { left++ }, {}, {}, {}, {}, {}, {}, required = true)
            }
        }
        assertEquals(listOf(Anchor.BackupDone to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(R.string.backup_success_title)).assertExists()
        compose.onNodeWithText(text(R.string.backup_success_body)).assertExists()
        compose.onNodeWithText(text(DesignR.string.done)).performClick()
        assertEquals(1, left)
    }

    @Test
    fun theCopiedPhraseSaysWhenTheClipboardIsCleared() {
        compose.setContent {
            BackupScreen(
                BackupState(step = BackupStep.Display(List(12) { "word$it" }), clipboardClearMillis = 30_000),
                {},
                {},
                {},
                {},
                {},
                {},
                {},
            )
        }
        compose.onNodeWithText(text(R.string.backup_copy)).performScrollTo().performClick()
        compose
            .onNodeWithText("Writing the words down is safer. The clipboard is cleared after 30 seconds.", substring = true)
            .assertExists()
    }

    @Test
    fun theHomeMintCanBeChangedFromItsPageWhenTheBrandAllowsIt() {
        var asked: String? = "unset"
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, canChangeHome = true),
                {},
                {},
                onChangeHome = { asked = it },
            )
        }
        compose.onNodeWithText(text(R.string.mint_change_home)).performScrollTo().performClick()
        assertEquals(null, asked)
        compose.onNodeWithText(text(R.string.mint_make_home)).assertDoesNotExist()
    }

    @Test
    fun anotherMintCanBecomeTheHome() {
        var asked: String? = null
        compose.setContent {
            MintDetailsScreen(
                MintDetailsState(details(MintDetails.Reachability.Reachable).copy(isHome = false), loading = false, canChangeHome = true),
                {},
                {},
                onChangeHome = { asked = it },
            )
        }
        compose.onNodeWithText(text(R.string.mint_make_home)).performScrollTo().performClick()
        assertEquals("mint.example", asked)
    }

    @Test
    fun aMintWhoseSigningKeyDoesNotMatchIsNotOfferedAsHomeButCanBeLeft() {
        val defaults = AppDefaults(mintSuggestions = emptyList(), allowMintChange = true)
        val other = details(MintDetails.Reachability.KeyChanged).copy(isHome = false)
        assertEquals(false, canChangeHome(defaults, other))
        assertEquals(true, canChangeHome(defaults, other.copy(reachability = MintDetails.Reachability.Reachable)))
        // The home can still be left.
        assertEquals(true, canChangeHome(defaults, details(MintDetails.Reachability.KeyChanged)))
    }

    @Test
    fun aLockedBrandOffersNoChangeOfTheHomeMint() {
        compose.setContent {
            MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false), {}, {})
        }
        compose.onNodeWithText(text(R.string.mint_change_home)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanAmountsPutTheSignAfterTheNumber() {
        compose.setContent {
            MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false), {}, {})
        }
        compose.onNodeWithText("999 ₿ in 1 Schein").assertExists()
    }

    @Test
    fun whileCollectingTheHomePageOffersNoMove() {
        var state by mutableStateOf(MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, collects = true))
        compose.setContent { MintDetailsScreen(state, {}, {}) }
        compose.onNodeWithText(text(R.string.mint_move_money)).assertDoesNotExist()
        state = state.copy(collects = false)
        compose.onNodeWithText(text(R.string.mint_move_money)).assertExists()
        state = state.copy(collects = true, details = state.details!!.copy(host = "other.example", isHome = false))
        compose.onNodeWithText(text(R.string.mint_move_money)).assertExists()
    }

    @Test
    fun removeIsOnEveryMintsPageAndAsksFirst() {
        var removed = false
        val removable = MintInfo("other.example", mint.mintPubkey, trusted = false, trustedByDefault = null, removable = true)
        var state by
            mutableStateOf(
                MintDetailsState(
                    details(MintDetails.Reachability.Reachable).copy(host = "other.example", isHome = false),
                    loading = false,
                    canChangeHome = true,
                    mint = removable,
                )
            )
        compose.setContent { MintDetailsScreen(state, {}, {}, onRemove = { removed = true }) }
        // The one page for a mint: what the account has there, its trust, and removing it.
        compose.onNodeWithText(text(R.string.mint_your_money)).assertExists()
        compose.onNodeWithText(text(R.string.mint_make_home)).assertExists()
        compose.onNodeWithText(text(R.string.mints_remove_mint)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mints_remove_confirm)).performClick()
        assertEquals(true, removed)

        // While the wallet gets to it (it may be busy with something else), the question stays, and can't be left or asked again.
        state = state.copy(removing = true)
        compose.onNodeWithText(text(R.string.mints_remove_title, "other.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.cancel)).assertIsNotEnabled()

        // Something ties an account to it by now: the question goes, and the page says it can't be removed.
        state = state.copy(removing = false, mint = removable.copy(removable = false, isHome = true))
        compose.onNodeWithText(text(R.string.mints_remove_title, "other.example")).assertDoesNotExist()
        compose.onNodeWithText("Can't be removed", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun aRefusedRemovalSaysWhyOnce() {
        val removable = MintInfo("other.example", mint.mintPubkey, trusted = false, trustedByDefault = null, removable = true)
        var state by
            mutableStateOf(
                MintDetailsState(
                    details(MintDetails.Reachability.Reachable).copy(host = "other.example", isHome = false),
                    loading = false,
                    mint = removable.copy(removable = false),
                    removeRefused = MintUse.Address,
                )
            )
        compose.setContent { MintDetailsScreen(state, {}, {}) }
        // An address there by now: the row says it can't be removed, and the refusal doesn't say it again.
        compose.onAllNodesWithText(text(R.string.mints_remove_in_use)).assertCountEquals(1)

        // An unfinished payment there: the row still offers it, so the refusal says why.
        state = state.copy(mint = removable, removeRefused = MintUse.OpenOperation)
        compose.onAllNodesWithText(text(R.string.mints_remove_in_use)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.mints_remove_mint)).assertExists()
    }

    private fun info(trusted: Boolean = true, default: TrustDefault? = null, pubkey: String? = mint.mintPubkey) =
        MintInfo("mint.example", pubkey, trusted = trusted, trustedByDefault = default, isHome = true)

    private fun trustPage(info: MintInfo?, reachability: MintDetails.Reachability = MintDetails.Reachability.Reachable) =
        MintDetailsState(details(reachability), loading = false, mint = info)

    private fun trustSwitch() = compose.onNode(isToggleable()).performScrollTo()

    @Test
    fun theTrustSwitchIsOnForATrustedMintAndTurnsItOff() {
        val asked = mutableListOf<Boolean>()
        compose.setContent { MintDetailsScreen(trustPage(info()), {}, {}, onTrusted = { asked += it }) }
        compose.onNodeWithText(text(DesignR.string.notes_trusted)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.mint_trusted_hint)).performScrollTo().assertExists()
        trustSwitch().assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf(false), asked)
    }

    @Test
    fun theTrustSwitchIsOffAndDisabledWithoutAKey() {
        compose.setContent { MintDetailsScreen(trustPage(info(trusted = false, pubkey = null)), {}, {}) }
        trustSwitch().assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun theTrustSwitchIsDisabledWhileTheKeyDoesntMatch() {
        compose.setContent { MintDetailsScreen(trustPage(info(trusted = false), MintDetails.Reachability.KeyChanged), {}, {}) }
        trustSwitch().assertIsNotEnabled()
    }

    @Test
    fun whileTheKeyDoesntMatchATrustedMintIsShownNotTrustedAndNoDefaultIsClaimed() {
        // Trust is in the pinned key; the mint signs with another one now, so it isn't trusted until the new key is accepted.
        compose.setContent { MintDetailsScreen(trustPage(info(default = TrustDefault.Home), MintDetails.Reachability.KeyChanged), {}, {}) }
        trustSwitch().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mints_default_home)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_untrusted_default)).assertDoesNotExist()
    }

    @Test
    fun aKeyKnownNotToMatchKeepsTheSwitchOffEvenWhenTheMintDidntAnswerNow() {
        // The lists mark it from what the wallet saw; the page says the same while the mint can't be asked.
        compose.setContent {
            MintDetailsScreen(
                trustPage(info(default = TrustDefault.Home), MintDetails.Reachability.Unreachable).copy(keyChanged = true),
                {},
                {},
            )
        }
        trustSwitch().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mints_default_home)).assertDoesNotExist()
    }

    @Test
    fun aHomesDefaultTrustSaysAnAccountsHome() {
        compose.setContent { MintDetailsScreen(trustPage(info(default = TrustDefault.Home)), {}, {}) }
        compose.onNodeWithText(text(R.string.mints_default_home)).performScrollTo().assertExists()
    }

    @Test
    fun aBrandsDefaultSaysInThisApp() {
        compose.setContent { MintDetailsScreen(trustPage(info(default = TrustDefault.Brand)), {}, {}) }
        compose.onNodeWithText(text(R.string.mints_default_brand)).performScrollTo().assertExists()
    }

    @Test
    fun anUntrustedDefaultSaysSo() {
        compose.setContent { MintDetailsScreen(trustPage(info(trusted = false, default = TrustDefault.Brand)), {}, {}) }
        compose.onNodeWithText(text(R.string.mints_untrusted_default)).performScrollTo().assertExists()
    }

    @Test
    fun theTrustRowIsThereInTheFirstDrawnFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MintDetailsScreen(trustPage(info()), {}, {}) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(isToggleable()).assertExists()
    }

    @Test
    fun noMakeThisMyHomeWithoutLeaveToChangeIt() {
        val other = details(MintDetails.Reachability.Reachable).copy(host = "other.example", isHome = false)
        compose.setContent { MintDetailsScreen(MintDetailsState(other, loading = false, canChangeHome = false, mint = info()), {}, {}) }
        compose.onNodeWithText(text(R.string.mint_make_home)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_remove_mint)).performScrollTo().assertExists()
    }

    @Test
    fun theAccountMarkShowsByTheRuleAndSwitches() {
        val choice =
            AccountChoice(
                listOf(
                    AccountSummary(Account("a", "Savings", 0, walletId = "w"), 0),
                    AccountSummary(Account("b", "Daily", 0, walletId = "w"), 0),
                ),
                "a",
                emptyMap(),
                shown = true,
            )
        var switched: String? = null
        var state by mutableStateOf(trustPage(info()).copy(accounts = choice))
        compose.setContent { MintDetailsScreen(state, {}, {}, accountActions = AccountActions({ switched = it }, {}, {})) }
        compose.onNodeWithContentDescription("Savings").performClick()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("b", switched)
        state = state.copy(accounts = choice.copy(shown = false))
        compose.onNodeWithContentDescription("Savings").assertDoesNotExist()
    }

    @Test
    fun theHomePageOffersToMoveAllMoneyHereWhenThereIsSomeElsewhere() {
        var moved = false
        var state by mutableStateOf(MintDetailsState(details(MintDetails.Reachability.Reachable), loading = false, moneyElsewhere = true))
        compose.setContent { MintDetailsScreen(state, {}, {}, onMoveAllHere = { moved = true }) }
        compose.onNodeWithText(text(R.string.mint_move_all_here)).performClick()
        assertEquals(true, moved)
        state = state.copy(moneyElsewhere = false)
        compose.onNodeWithText(text(R.string.mint_move_all_here)).assertDoesNotExist()
    }

    @Test
    fun pagesDrawNothingUntilWhatTheyShowIsRead() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SecuritySettingsScreen(appLock = null, available = true, onBack = {}, onAppLock = {})
                DisplaySettingsScreen(null, {}, {})
                DeleteWalletScreen(DeleteWalletState(loaded = false), {}, {}, {}, {}, {}, {})
                MintDetailsScreen(MintDetailsState(), {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.security_app_lock)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_amounts_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.delete_what)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mint_title)).assertDoesNotExist()
    }
}
