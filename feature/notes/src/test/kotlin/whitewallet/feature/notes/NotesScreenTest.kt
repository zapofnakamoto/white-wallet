package whitewallet.feature.notes

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandComponents
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.NoteFaceParams
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteGroupParams
import whitewallet.brand.ui.NoteStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.SimulatedFailure

@RunWith(RobolectricTestRunner::class)
class NotesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(id: String, host: String, sats: Long, state: NoteState = NoteState.Outstanding, certified: Boolean = true) =
        Note(id, "a", host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xff, sats * 1000, if (certified) "cs1" else null, state, 0)

    private val home = listOf(note("h1", "home.example", 30_000), note("h2", "home.example", 15_000))
    private val other = listOf(note("o1", "other.example", 2_000), note("o2", "other.example", 430, certified = false))

    private fun state(vararg changes: (NotesState) -> NotesState): NotesState {
        val base = NotesState(
            loaded = true,
            mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other)),
            knownHosts = listOf("home.example", "other.example"),
        )
        return changes.fold(base) { s, change -> change(s) }
    }

    @Test
    fun theOverviewShowsTheTotalPerMintAndOnlyUnusualStatus() {
        compose.setContent { NotesContent(state(), NotesActions()) }
        compose.onNodeWithText("₿47,430").assertExists()
        compose.onNodeWithText("4 notes · 2 mints").assertExists()
        compose.onNodeWithText("home.example").assertExists()
        compose.onNodeWithText("Home mint").assertExists()
        compose.onNodeWithText("₿45,000").assertExists()
        compose.onNodeWithText("Amount not certified").assertExists()
    }

    @Test
    fun aTrustedMintIsMarkedOnItsGroupAndInTheNote() {
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, home, trusted = true), MintNotes("other.example", false, other)), open = "h1") }), NotesActions())
        }
        compose.onNodeWithText("Home mint · Trusted").assertExists()
        compose.onNodeWithText("Trusted").assertExists()
    }

    @Test
    fun emptyWalletSaysSo() {
        compose.setContent { NotesContent(NotesState(loaded = true), NotesActions()) }
        compose.onNodeWithText("No notes yet. Notes appear here once you receive money.").assertExists()
    }

    @Test
    fun tappingANoteOpensItsActions() {
        var shared: String? = null
        var renewed: String? = null
        compose.setContent { NotesContent(state({ it.copy(open = "h2") }), NotesActions(share = { shared = it }, renew = { renewed = it })) }
        compose.onNodeWithText("On home.example · ", substring = true).assertExists()
        compose.onNodeWithText("Share as QR").performClick()
        compose.onNodeWithText("Renew").performClick()
        assertEquals("h2", shared)
        assertEquals("h2", renewed)
        compose.onNodeWithText("Key 0/", substring = true).assertDoesNotExist()
    }

    @Test
    fun developerModeShowsTheKey() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true) }), NotesActions()) }
        compose.onNodeWithText("Key 0/", substring = true).assertExists()
    }

    @Test
    fun aDebugBuildInDeveloperModeMakesTheNextActionFailOnRequest() {
        var chosen: SimulatedFailure.Kind? = null
        var armed by mutableStateOf<SimulatedFailure.Kind?>(null)
        compose.setContent {
            NotesContent(state({ it.copy(open = "h2", developerMode = true, failuresOffered = true, simulatedFailure = armed) }),
                NotesActions(simulateFailure = { chosen = it; armed = it }))
        }
        compose.onNodeWithText("Developer · debug builds only").assertExists()
        compose.onNodeWithText("Answer lost").performClick()
        assertEquals(SimulatedFailure.Kind.AnswerLost, chosen)
        // Tapped again, it is taken back.
        compose.onNodeWithText("Answer lost").performClick()
        assertEquals(null, chosen)
    }

    @Test
    fun theSheetScrollsToItsLastLine() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true, failuresOffered = true) }), NotesActions()) }
        compose.onNodeWithText("Key 0/", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun developerModeAloneOffersNoFailures() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true) }), NotesActions()) }
        compose.onNodeWithText("Developer · debug builds only").assertDoesNotExist()
    }

    @Test
    fun aNoteBeingPaidCanBeOpenedButNotActedOn() {
        val paying = note("p", "home.example", 1_000, NoteState.Paying)
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, listOf(paying))), open = "p") }), NotesActions())
        }
        compose.onNodeWithText("Share as QR").assertIsNotEnabled()
        compose.onNodeWithText("Recheck").assertIsNotEnabled()
    }

    @Test
    fun aConfirmingNoteCanOnlyBeRechecked() {
        val confirming = note("c", "home.example", 1_000, NoteState.Ambiguous)
        var rechecked: String? = null
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, listOf(confirming))), open = "c") }), NotesActions(recheck = { rechecked = it }))
        }
        compose.onNodeWithText("Share as QR").assertIsNotEnabled()
        compose.onNodeWithText("Renew").assertIsNotEnabled()
        compose.onNodeWithText("Recheck").assertIsEnabled().performClick()
        assertEquals("c", rechecked)
    }

    @Test
    fun aSettledChangeSaysHowItEnded() {
        compose.setContent { NotesContent(state({ it.copy(outcome = NotesOutcome.Settled(landed = false)) }), NotesActions()) }
        compose.onNodeWithText("The change didn't go through, so nothing changed.").assertExists()
    }

    @Test
    fun longPressStartsSelectingAndTheBarActsOnTheSelection() {
        var pressed: Note? = null
        var combined = false
        var s by mutableStateOf(state())
        compose.setContent { NotesContent(s, NotesActions(longPress = { pressed = it }, combine = { combined = true })) }
        compose.onNodeWithText("₿30,000").performTouchInput { longClick() }
        assertEquals("h1", pressed?.id)

        s = state({ it.copy(selecting = true, selected = setOf("h1")) })
        compose.onNodeWithText("1 note selected · ₿30,000").assertExists()
        compose.onNodeWithText("Combine").assertIsNotEnabled()
        s = state({ it.copy(selecting = true, selected = setOf("h1", "h2")) })
        compose.onNodeWithText("Combine").assertIsEnabled().performClick()
        assertEquals(true, combined)
    }

    @Test
    fun aMintCollectingSkippedOffersMoveAnyway() {
        var moved: String? = null
        val skipped = NotCollected.Reason.FeeTooHigh(feeMsat = 2_000, capPercent = 2)
        compose.setContent {
            NotesContent(
                state({ it.copy(mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other, skipped))) }),
                NotesActions(moveAnyway = { moved = it }),
            )
        }
        compose.onNodeWithText("Not moved home: the fee of ₿2 is more than 2%.").assertExists()
        compose.onNodeWithText("Move anyway").performClick()
        assertEquals("other.example", moved)
    }

    @Test
    fun sharedNotesWaitInTheirOwnSection() {
        var opened: String? = null
        val shared = note("s", "home.example", 3_333, NoteState.Shared)
        compose.setContent { NotesContent(state({ it.copy(shared = listOf(shared)) }), NotesActions(openShared = { opened = it })) }
        compose.onNodeWithText("₿3,333 shared, not redeemed yet").assertExists()
        // The list is lazy: rows below the screen exist only once scrolled to.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tap one to show its QR code again or take it back."))
        compose.onNodeWithText("Shared, not redeemed yet").assertExists()
        compose.onNodeWithText("₿3,333").performClick()
        assertEquals("s", opened)
    }

    @Test
    fun aBrandDrawsHandedOutNotesLikeTheOthers() {
        val faces = mutableListOf<Triple<NoteFaceSize, NoteStatus, NoteFacePlace>>()
        val design = object : BrandDesign {
            override val components = object : BrandComponents {
                @Composable
                override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                    faces += Triple(params.size, params.status, params.place)
                    Text("zap ${params.amount} ${params.mint}", modifier)
                }
            }
        }
        var opened: String? = null
        val shared = note("s", "home.example", 3_333, NoteState.Shared)
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                NotesContent(state({ it.copy(shared = listOf(shared)) }), NotesActions(openShared = { opened = it }))
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("zap ₿3,333 home.example"))
        assertTrue(Triple(NoteFaceSize.Row, NoteStatus.Waiting, NoteFacePlace.Shared) in faces)
        // The tap stays the app's.
        compose.onNodeWithText("zap ₿3,333 home.example").performClick()
        assertEquals("s", opened)
    }

    @Test
    fun checkingReportsNotesThatAreGone() {
        compose.setContent { NotesContent(state({ it.copy(outcome = NotesOutcome.Checked(ok = 3, gone = 1, unreachable = listOf("other.example"))) }), NotesActions()) }
        compose.onNodeWithText(
            "1 note is no longer there: it was spent elsewhere, for example with this recovery phrase on another phone. No answer from other.example. Try again later.",
        ).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        compose.setContent { NotesContent(state({ it.copy(selecting = true, selected = setOf("h1", "h2")) }), NotesActions()) }
        compose.onNodeWithText("4 Scheine · 2 Mints").assertExists()
        compose.onNodeWithText("Haupt-Mint").assertExists()
        compose.onNodeWithText("2 Scheine ausgewählt · 45.000 ₿").assertExists()
        compose.onNodeWithText("Zusammenlegen").assertExists()
    }

    @Test
    fun aNoteOffersOnlyTheActionsTheBrandAllows() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", noteActions = setOf(NoteAction.Share, NoteAction.Renew)) }), NotesActions()) }
        compose.onNodeWithText("Share as QR").assertExists()
        compose.onNodeWithText("Renew").assertExists()
        compose.onNodeWithText("Split").assertDoesNotExist()
        compose.onNodeWithText("Recheck").assertDoesNotExist()
        // The share hint tells users to split first, which they can't here.
        compose.onNodeWithText("To give part of it, split it first.", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutAnActionOnSeveralNotesThereIsNoSelecting() {
        var pressed: Note? = null
        compose.setContent { NotesContent(state({ it.copy(noteActions = setOf(NoteAction.Share, NoteAction.Split)) }), NotesActions(longPress = { pressed = it })) }
        compose.onNodeWithText("Select").assertDoesNotExist()
        compose.onNodeWithText("₿30,000").performTouchInput { longClick() }
        assertEquals(null, pressed)
    }

    @Test
    fun theSelectionBarOffersOnlyTheAllowedBulkActions() {
        compose.setContent {
            NotesContent(state({ it.copy(selecting = true, selected = setOf("h1"), noteActions = setOf(NoteAction.Recheck)) }), NotesActions())
        }
        compose.onNodeWithText("Recheck").assertIsEnabled()
        compose.onNodeWithText("Combine").assertDoesNotExist()
        compose.onNodeWithText("Move").assertDoesNotExist()
    }

    @Test
    fun withoutMintsOneMintsNotesAreAPlainList() {
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, home, trusted = true)), showMints = false, open = "h2") }), NotesActions())
        }
        compose.onNodeWithText("2 notes").assertExists()
        compose.onNodeWithText("home.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Home mint", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Trusted").assertDoesNotExist()
    }

    @Test
    fun withoutMintsSeveralMintsAreSeparatedButNotNamed() {
        compose.setContent {
            NotesContent(
                state({ it.copy(showMints = false, outcome = NotesOutcome.Checked(ok = 3, gone = 0, unreachable = listOf("other.example"))) }),
                NotesActions(),
            )
        }
        compose.onNodeWithText("4 notes").assertExists()
        compose.onNodeWithText("₿2,000").assertExists()
        compose.onNodeWithText("other.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No answer. Try again later.").assertExists()
    }

    /** A brand that draws notes and their headers its own way: only the texts it's given, in its own layout. */
    private val bills = object : BrandDesign {
        override val components = object : BrandComponents {
            @Composable
            override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                Text("bill ${params.amount} ${params.status}${if (params.selected == true) " ✓" else ""}", modifier)
            }

            @Composable
            override fun NoteGroupHeader(params: NoteGroupParams, modifier: Modifier) {
                Text("bank ${params.mint} ${params.total}", modifier)
            }
        }
    }

    @Test
    fun aBrandDrawsTheNotesAndTheAppKeepsWhatTheyDo() {
        var tapped: Note? = null
        var s by mutableStateOf(state())
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides bills) { NotesContent(s, NotesActions(tap = { tapped = it })) }
        }
        compose.onNodeWithText("bank home.example ₿45,000").assert(isHeading())
        compose.onNodeWithText("bill ₿430 Uncertified").assertExists()
        compose.onNodeWithText("bill ₿30,000 Ready").performClick()
        assertEquals("h1", tapped?.id)

        // Selecting: the app says which are selected, whatever the brand draws.
        s = state({ it.copy(selecting = true, selected = setOf("h1")) })
        compose.onNodeWithText("bill ₿30,000 Ready ✓").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithText("bill ₿15,000 Ready").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun aBrandDecoratesTheTopOfTheNotesAndAnEmptyView() {
        val seen = mutableListOf<Pair<Anchor, WalletStatus>>()
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                if (anchor == Anchor.NotesHeader || anchor == Anchor.NotesEmpty) seen += anchor to context.status
            }
        }
        val mint = MintNotes("mint.example", true, listOf(note("a", "mint.example", 1_000)), trusted = true)
        var state by mutableStateOf(NotesState(loaded = true))
        compose.setContent { CompositionLocalProvider(LocalBrandDesign provides design) { NotesContent(state, NotesActions()) } }
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.Empty, Anchor.NotesEmpty to WalletStatus.Empty), seen.toSet())

        seen.clear()
        state = NotesState(loaded = true, mints = listOf(mint), shared = listOf(note("s", "mint.example", 500, NoteState.Shared)))
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.WaitingForRecipient), seen.toSet())

        seen.clear()
        state = NotesState(loaded = true, mints = listOf(mint))
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.Idle), seen.toSet())
    }
}
