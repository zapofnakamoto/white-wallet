package whitewallet.feature.send

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandComponents
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.DefaultComponents
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.NoteFaceParams
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteStatus
import whitewallet.brand.ui.QrCodeCoverParams
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.SharedNoteState

@RunWith(RobolectricTestRunner::class)
class SharedNoteScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(state: SharedNoteState) = SharedNote(
        id = "n1", host = "mint.example", amountMsat = 1_000_000, certified = true, state = state, createdAtMillis = 0,
        link = if (state == SharedNoteState.Waiting) "https://mint.example/note#secret" else null,
    )

    private fun show(note: SharedNote) = compose.setContent {
        SharedNoteScreen(SharedNoteUiState(note = note, loading = false), onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})
    }

    @Test
    fun aNoteHandedOutOfflineSaysItsMintHasntConfirmedItYet() {
        show(note(SharedNoteState.Waiting).copy(confirmed = false))
        compose.onNodeWithText(text(R.string.note_unconfirmed)).assertExists()
        compose.onNodeWithText(text(R.string.note_unknown_to_mint)).assertDoesNotExist()
    }

    @Test
    fun aWaitingNoteSaysWhenItsMintLastAnswered() {
        show(note(SharedNoteState.Waiting).copy(checkedAtMillis = System.currentTimeMillis()))
        compose.onNodeWithText("Last checked: ", substring = true).assertExists()
    }

    @Test
    fun aNoteNeverCheckedSaysNothingAboutIt() {
        show(note(SharedNoteState.Waiting).copy(confirmed = false))
        compose.onNodeWithText("Last checked", substring = true).assertDoesNotExist()
    }

    @Test
    fun aNoteItsMintDoesntKnowSaysSo() {
        show(note(SharedNoteState.Waiting).copy(unknownToMint = true))
        compose.onNodeWithText(text(R.string.note_unknown_to_mint)).assertExists()
        compose.onNodeWithText("Its mint hasn't confirmed", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSpentNoteDoesntClaimWhoSpentIt() {
        show(note(SharedNoteState.Spent))
        compose.onNodeWithText("Spent, usually by the recipient", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanSaysANoteHandedOutOfflineIsUnconfirmed() {
        show(note(SharedNoteState.Waiting).copy(confirmed = false))
        compose.onNodeWithText("Die Mint hat diesen Schein noch nicht bestätigt", substring = true).assertExists()
    }

    @Test
    fun theBrandSeesTheNoteWaitBeSpentOrComeBack() {
        val statuses = mutableListOf<WalletStatus>()
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                if (anchor == Anchor.NoteShared) {
                    statuses += context.status
                    Text("brand mark")
                }
            }
        }
        var state by mutableStateOf(SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false))
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                SharedNoteScreen(state, onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})
            }
        }
        compose.onNodeWithText("brand mark", useUnmergedTree = true).assertExists()
        assertEquals(WalletStatus.WaitingForRecipient, statuses.last())

        state = state.copy(note = note(SharedNoteState.Spent))
        compose.waitForIdle()
        assertEquals(WalletStatus.Sent(1_000_000), statuses.last())

        state = state.copy(note = note(SharedNoteState.Reclaimed))
        compose.waitForIdle()
        assertEquals(WalletStatus.Idle, statuses.last())

        state = state.copy(reclaimPending = true)
        compose.waitForIdle()
        assertEquals(WalletStatus.None, statuses.last())

        state = state.copy(note = note(SharedNoteState.Waiting), reclaimPending = false, problem = PayProblem.Unreachable)
        compose.waitForIdle()
        assertEquals(WalletStatus.Problem(ProblemKind.Offline), statuses.last())
    }

    @Test
    fun withoutABrandDesignNothingIsAdded() {
        compose.setContent {
            SharedNoteScreen(
                SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false),
                onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
            )
        }
        compose.onNodeWithText("₿1,000").assertExists()
        compose.onNodeWithText("brand mark").assertDoesNotExist()
    }

    @Test
    fun aBrandFramesTheNoteAndTheAppKeepsTheQrCodeTheWarningAndTheActions() {
        var shared: String? = null
        var askedBack = false
        val bill = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                    Column(modifier) {
                        Text("bill ${params.amount} ${params.status}")
                        params.qrCode?.invoke()
                    }
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides bill) {
                SharedNoteScreen(
                    SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false),
                    onBack = {}, onShare = { shared = it }, onCopy = {}, onAskReclaim = { askedBack = true }, onDismissReclaim = {}, onReclaim = {},
                )
            }
        }
        compose.onNodeWithText("bill ₿1,000 Waiting").assertExists()
        // The QR code is the app's, covered until a tap, and never reads the secret link out.
        compose.onNodeWithText(text(R.string.note_qr_show)).performClick()
        compose.onNodeWithContentDescription("QR code of the note").assertExists()
        compose.onNodeWithText("https://mint.example/note#secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.note_bearer_warning), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.note_share)).performScrollTo().performClick()
        assertEquals("https://mint.example/note#secret", shared)
        compose.onNodeWithText(text(R.string.note_reclaim)).performScrollTo().performClick()
        assertTrue(askedBack)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "night")
    fun printingDrawsTheBrandsNoteOnWhitePaperEvenInDarkMode() {
        val pages = mutableListOf<Bitmap>()
        val faces = mutableListOf<NoteStatus>()
        val bill = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                    if (params.place == NoteFacePlace.Paper) faces += params.status
                    DefaultComponents.noteFace(params, modifier)
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides bill) {
                SharedNoteScreen(
                    SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false, canPrint = true),
                    onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {}, onPrint = { pages += it },
                )
            }
        }
        faces.clear()
        compose.onNodeWithText(text(R.string.note_print)).performScrollTo().performClick()
        // Robolectric draws only when asked; a phone draws the next frame by itself.
        compose.onRoot().captureToImage()
        compose.waitUntil(5_000) { pages.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(1, pages.size)
        val page = pages.single()
        // Laid out 360 dp wide at the print density, white around, the QR code's black inside.
        assertEquals(1440, page.width)
        assertEquals(Color.WHITE, page.getPixel(2, 2))
        assertEquals(Color.WHITE, page.getPixel(page.width - 3, page.height - 3))
        assertTrue((0 until page.height step 8).any { y -> page.getPixel(page.width / 2, y) == Color.BLACK })
        assertTrue(NoteStatus.Waiting in faces)
        // The page was only drawn for paper, QR code included though the screen still covers it.
        compose.onAllNodesWithContentDescription("QR code of the note").assertCountEquals(0)
        compose.onNodeWithText(text(R.string.note_qr_show)).assertExists()
    }

    @Test
    fun withoutPrintAmongTheNoteActionsThereIsNoPrinting() {
        compose.setContent {
            SharedNoteScreen(
                SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false, canPrint = false),
                onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
            )
        }
        compose.onNodeWithText(text(R.string.note_share)).assertExists()
        compose.onNodeWithText(text(R.string.note_print)).assertDoesNotExist()
    }

    @Test
    fun theQrCodeIsCoveredUntilATapAndAgainOnceTheAppLeaves() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SharedNoteScreen(
                    SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false),
                    onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
                )
            }
        }
        compose.onNodeWithContentDescription("QR code of the note").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.note_qr_show)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("QR code of the note").assertExists()
        compose.onNodeWithText(text(R.string.note_qr_show)).assertDoesNotExist()
        // Leaving the app (another app, the home screen, the screen off) covers it again.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithContentDescription("QR code of the note").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.note_qr_show)).assertExists()
    }

    @Test
    fun aBrandDrawsTheCoverAndTheAppKeepsTheTap() {
        val spark = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun QrCodeCover(params: QrCodeCoverParams, modifier: Modifier) {
                    Column(modifier) { Text("spark cover: ${params.label}") }
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides spark) {
                SharedNoteScreen(
                    SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false),
                    onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
                )
            }
        }
        compose.onNodeWithText("spark cover: Tap to show the QR code").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("QR code of the note").assertExists()
    }

    @Test
    fun theBrandKnowsTheNoteIsOnItsPageOrOnPaperAndWhetherTheCodeIsCovered() {
        val seen = mutableListOf<Pair<NoteFacePlace, Boolean>>()
        val card = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                    seen += params.place to params.qrCovered
                    DefaultComponents.noteFace(params, modifier)
                }
            }
        }
        var state by mutableStateOf(SharedNoteUiState(note = note(SharedNoteState.Waiting), loading = false))
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides card) {
                SharedNoteScreen(state, onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {})
            }
        }
        assertEquals(NoteFacePlace.Shared to true, seen.last())
        compose.onNodeWithText(text(R.string.note_qr_show)).performClick()
        compose.waitForIdle()
        assertEquals(NoteFacePlace.Shared to false, seen.last())
        // Once spent there is no code, so nothing is covered either.
        compose.onNodeWithText(text(R.string.note_qr_show)).assertDoesNotExist()
        state = state.copy(note = note(SharedNoteState.Spent))
        compose.waitForIdle()
        assertEquals(NoteFacePlace.Shared to false, seen.last())
    }

    @Test
    fun aClaimedNoteIsNeverCovered() {
        val seen = mutableListOf<Boolean>()
        val card = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                    seen += params.qrCovered
                    DefaultComponents.noteFace(params, modifier)
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides card) {
                SharedNoteScreen(
                    SharedNoteUiState(note = note(SharedNoteState.Spent), loading = false),
                    onBack = {}, onShare = {}, onCopy = {}, onAskReclaim = {}, onDismissReclaim = {}, onReclaim = {},
                )
            }
        }
        assertEquals(listOf(false), seen.distinct())
    }
}
