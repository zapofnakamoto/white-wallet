package whitewallet.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.Mint
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.AddressShare
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo

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
        assertEquals(KeySet.share(1), chosen)
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
        compose.onNodeWithText(text(R.string.mint_key_changed_short)).assertExists()
        compose.onNodeWithText("doesn't match the one your wallet knows", substring = true).assertExists()
    }

    @Test
    fun aMintThatNoLongerShowsItsKeySaysSoAndHasNothingToAccept() {
        compose.setContent {
            MintDetailsScreen(MintDetailsState(details(MintDetails.Reachability.KeyChanged), loading = false), {}, {})
        }
        compose.onNodeWithText(text(R.string.mint_key_changed, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.mint_key_remembered)).assertExists()
        compose.onNodeWithText(text(R.string.mint_key_new)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mint_key_accept)).assertDoesNotExist()
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
        compose.onNodeWithText(text(R.string.mint_key_remembered)).assertExists()
        compose.onNodeWithText(presented.chunked(4).joinToString(" ")).assertExists()
        compose.onNodeWithText(text(R.string.mint_key_accept)).performClick()
        compose.onNodeWithText("Only do this if you know why mint.example changed its key", substring = true).assertExists()
        compose.onNodeWithText(text(R.string.backup_cancel)).performClick()
        assertEquals(emptyList<String>(), accepted)
        compose.onNodeWithText(text(R.string.mint_key_accept)).performClick()
        compose.onNodeWithText(text(R.string.mint_key_accept_confirm)).performClick()
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
    fun fromTheDevicesListAMintPageShowsNoAccountButWhoseHomeItIsAndRemoving() {
        var removed = false
        val removable = MintInfo("other.example", mint.mintPubkey, trusted = false, trustedByDefault = null, removable = true)
        var state by
            mutableStateOf(
                MintDetailsState(
                    details(MintDetails.Reachability.Reachable).copy(host = "other.example", isHome = false),
                    loading = false,
                    canChangeHome = true,
                    device = true,
                    mint = removable,
                )
            )
        compose.setContent { MintDetailsScreen(state, {}, {}, onRemove = { removed = true }) }
        compose.onNodeWithText("Your money").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mint_make_home)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_remove_mint)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.mints_remove_confirm)).performClick()
        assertEquals(true, removed)

        state = state.copy(mint = removable.copy(removable = false, isHome = true))
        compose.onNodeWithText(text(R.string.settings_home_mint)).assertExists()
        compose.onNodeWithText("Can't be removed", substring = true).performScrollTo().assertExists()
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
    fun anAccountsMintsShowItsMoneyHomeFirst() {
        var opened: String? = null
        val mints =
            listOf(
                AccountMint("home.example", isHome = true, balanceMsat = 21_000, noteCount = 2, addresses = 1, trusted = true),
                AccountMint("other.example", isHome = false, balanceMsat = 5_000, noteCount = 1, addresses = 0, trusted = false),
            )
        compose.setContent { AccountMintsScreen(mints, {}, { opened = it }) }
        compose.onNodeWithText("₿21 in 2 notes").assertExists()
        compose.onNodeWithText("1 address").assertExists()
        compose.onNodeWithText("other.example").performClick()
        assertEquals("other.example", opened)
    }

    @Test
    fun pagesDrawNothingUntilWhatTheyShowIsRead() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SecuritySettingsScreen(appLock = null, available = true, onBack = {}, onAppLock = {})
                DisplaySettingsScreen(null, {}, {})
                AccountMintsScreen(null, {}, {})
                DeleteWalletScreen(DeleteWalletState(loaded = false), {}, {}, {}, {}, {}, {})
                MintDetailsScreen(MintDetailsState(), {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.security_app_lock)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_amounts_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_mints_intro)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.delete_what)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mint_title)).assertDoesNotExist()
    }
}
