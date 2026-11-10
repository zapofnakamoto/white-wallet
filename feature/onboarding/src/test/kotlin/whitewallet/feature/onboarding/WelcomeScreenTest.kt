package whitewallet.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.ReleaseStage
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.wallet.ScanProgress

/** The welcome screen (create or restore a wallet), the phrase-only restore and the new account's name. */
@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun welcome(
        state: CreateState = CreateState(),
        logo: @Composable () -> Unit = { Text("logo") },
        onCreate: () -> Unit = {},
        onRestore: () -> Unit = {},
        onBack: (() -> Unit)? = null,
    ) = compose.setContent { WelcomeScreen("Wallet", logo, state, onCreate, onRestore, onBack) }

    @Test
    fun theWelcomeOffersOnlyCreateAndRestore() {
        var created = false
        var restoring = false
        welcome(onCreate = { created = true }, onRestore = { restoring = true })
        compose.onNodeWithText(text(R.string.onboarding_tagline)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_username)).assertDoesNotExist()
        compose.onNodeWithContentDescriptionBack().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.welcome_create)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.welcome_restore)).performScrollTo().performClick()
        assertTrue(created && restoring)
    }

    @Test
    fun fromSettingsTheWelcomeHasAWayBack() {
        var back = false
        welcome(onBack = { back = true })
        compose.onNodeWithContentDescriptionBack().performClick()
        assertTrue(back)
    }

    @Test
    fun atTheWalletLimitCreatingSaysSo() {
        welcome(CreateState(limit = 3))
        compose.onNodeWithText(plural(R.plurals.onboarding_error_account_limit, 3, 3)).assertExists()
        compose.onNodeWithText(text(R.string.welcome_create)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun withoutABrandDesignTheLogoAndNameShow() {
        welcome()
        // Decorations are decorative: the default logo is drawn but not read out; the name is a heading.
        compose.onNodeWithText("logo").assertDoesNotExist()
        compose.onNode(hasContentDescription("Wallet") and isHeading()).assertExists()
    }

    @Test
    fun aBrandDesignDrawsItsOwnHeaderAndTitle() {
        val anchors = mutableListOf<Anchor>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    anchors += anchor
                    Text("brand ${anchor.id} ${context.appName}", Modifier.testTag(anchor.id))
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design, LocalAppName provides "Wallet") {
                WelcomeScreen("Wallet", { Text("logo") }, CreateState(), {}, {})
            }
        }
        compose.onNodeWithTag(Anchor.OnboardingHeader.id, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("brand onboarding.title Wallet", useUnmergedTree = true).assertExists()
        // Whatever the brand draws, screen readers still get the name as a heading.
        compose.onNode(hasContentDescription("Wallet") and isHeading()).assertExists()
        assertTrue(Anchor.OnboardingHeader in anchors && Anchor.OnboardingTitle in anchors)
    }

    @Test
    fun anAlphaSaysSoBeforeTheUserStarts() {
        var stage: ReleaseStage? = null
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    stage = context.releaseStage
                    if (anchor == Anchor.OnboardingTitle) Text("wordmark") else default()
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design, LocalReleaseStage provides ReleaseStage.Alpha) {
                WelcomeScreen("Wallet", { Text("logo") }, CreateState(), {}, {})
            }
        }
        // The badge sits by the brand's own title, once.
        compose.onNodeWithText("wordmark", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(DesignR.string.release_stage_alpha)).assertExists()
        compose.onNodeWithText(text(DesignR.string.release_notice_alpha)).performScrollTo().assertExists()
        // A brand drawing its own title knows the stage too.
        assertEquals(ReleaseStage.Alpha, stage)
    }

    @Test
    fun aStableReleaseShowsNoStageAtSetup() {
        welcome()
        compose.onNodeWithText(text(DesignR.string.release_stage_beta)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.release_stage_alpha)).assertDoesNotExist()
    }

    @Test
    fun aRestoreAsksOnlyForThePhrase() {
        var restored = false
        val phrase = List(11) { "abandon" }.plus("about").joinToString(" ")
        compose.setContent {
            RestoreScreen(RestoreState(phrase = phrase), onBack = {}, onPhraseChange = {}, onRestore = { restored = true })
        }
        compose.onNodeWithText(text(R.string.onboarding_mint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.restore_start)).performScrollTo().performClick()
        assertTrue(restored)
    }

    @Test
    fun aNewAccountNeedsOnlyItsName() {
        var created: String? = null
        compose.setContent { AccountNameScreen(CreateState(), "Savings", onBack = {}, onCreate = { created = it }) }
        compose.onNodeWithText(text(R.string.account_name_body)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint)).assertDoesNotExist()
        compose.onNodeWithText("Savings").performTextReplacement("Travel")
        compose.onNodeWithText(text(R.string.onboarding_create_account)).performClick()
        assertEquals("Travel", created)
    }

    @Test
    fun aNewAccountWithoutANameCantBeCreated() {
        compose.setContent { AccountNameScreen(CreateState(), "", onBack = {}, onCreate = {}) }
        compose.onNodeWithText(text(R.string.onboarding_create_account)).assertIsNotEnabled()
    }

    @Test
    fun atTheAccountLimitTheNameScreenSaysSo() {
        compose.setContent { AccountNameScreen(CreateState(limit = 2), "Savings", onBack = {}, onCreate = {}) }
        compose.onNodeWithText(plural(R.plurals.account_name_limit, 2, 2)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_create_account)).assertIsNotEnabled()
    }

    @Test
    fun whileANewAccountIsLookedForTheScreenSaysHowFarItIs() {
        val scan = ScanProgress.AccountScan(index = 1, keysChecked = 40, foundMsat = 0, done = false)
        compose.setContent { AccountNameScreen(CreateState(busy = true, scan = scan), "Savings", onBack = {}, onCreate = {}) }
        compose.onNodeWithText(text(R.string.account_name_looking)).assertExists()
        compose.onNodeWithText(plural(R.plurals.add_mint_keys_checked, 40, 40)).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun aNewAccountThatCouldntBeLookedForIsNotAddedAndCanBeTriedAgain() {
        var tried: String? = null
        compose.setContent {
            AccountNameScreen(CreateState(scanFailed = "mint.example"), "Sparen", onBack = {}, onCreate = { tried = it })
        }
        compose.onNodeWithText("mint.example war nicht erreichbar", substring = true).assertExists()
        compose.onNodeWithText("Noch einmal versuchen").performClick()
        assertEquals("Sparen", tried)
    }
}
