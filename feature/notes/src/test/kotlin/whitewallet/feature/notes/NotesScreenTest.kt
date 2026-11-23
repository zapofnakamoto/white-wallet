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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.ProblemKind
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
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.SimulatedFailure

@RunWith(RobolectricTestRunner::class)
class NotesScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun note(id: String, host: String, sats: Long, state: NoteState = NoteState.Outstanding, certified: Boolean = true) =
        Note(id, "a", host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xff, sats * 1000, if (certified) "cs1" else null, state, 0)

    private val home = listOf(note("h1", "home.example", 30_000), note("h2", "home.example", 15_000))
    private val other = listOf(note("o1", "other.example", 2_000), note("o2", "other.example", 430, certified = false))

    private fun state(vararg changes: (NotesState) -> NotesState): NotesState {
        val base =
            NotesState(
                loaded = true,
                mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other)),
                knownHosts = listOf("home.example", "other.example"),
            )
        return changes.fold(base) { s, change -> change(s) }
    }

    /** The page of [host]'s notes is open. */
    private fun onPage(host: String = "home.example") = { s: NotesState -> s.copy(mintPage = host) }

    @Test
    fun theOverviewShowsEachMintAsAPileWithItsTotalAndRange() {
        var opened: String? = null
        compose.setContent { NotesContent(state(), NotesActions(openMint = { opened = it })) }
        compose.onNodeWithText("₿47,430").assertExists()
        compose.onNodeWithText("4 notes · 2 mints").assertExists()
        compose.onNodeWithText("home.example").assertExists()
        compose.onNodeWithText("Home mint · 2 notes").assertExists()
        compose.onNodeWithText("₿45,000").assertExists()
        compose.onNodeWithText("From ₿15,000 to ₿30,000").assertExists()
        // The notes themselves, and what's unusual about them, are on the mint's page.
        compose.onNodeWithText(text(DesignR.string.uncertified)).assertDoesNotExist()
        compose.onNodeWithText("home.example").performClick()
        assertEquals("home.example", opened)
    }

    @Test
    fun aMintsPageListsItsNotesWithOnlyUnusualStatusAndLeadsBack() {
        var closed = 0
        compose.setContent { NotesContent(state(onPage("other.example")), NotesActions(closeMint = { closed++ })) }
        compose.onNodeWithText("other.example").assertExists()
        compose.onNodeWithText("2 notes").assertExists()
        compose.onNodeWithText("₿2,000").assertExists()
        compose.onNodeWithText("₿430").assertExists()
        compose.onNodeWithText(text(DesignR.string.uncertified)).assertExists()
        compose.onNodeWithText(text(R.string.notes_sort_largest)).assertExists()
        compose.onNodeWithContentDescription(text(DesignR.string.back)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun severalMintsAreSwipedThroughOrMovedByTheArrows() {
        compose.setContent { NotesContent(state(), NotesActions()) }
        compose.onNodeWithContentDescription(text(R.string.notes_mint_position, 1, 2)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.notes_mint_previous)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.notes_mint_next)).performClick()
        compose.onNodeWithContentDescription(text(R.string.notes_mint_position, 2, 2)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.notes_mint_next)).assertIsNotEnabled()
    }

    /** Notes home.example doesn't know, one on other.example waiting for an answer, and other.example's key changed. */
    private fun troubled(s: NotesState) =
        withUnknown(s)
            .copy(
                mints =
                    withUnknown(s).mints +
                        MintNotes("other.example", false, listOf(note("a1", "other.example", 1_000, NoteState.Ambiguous))),
                keyChangedHosts = setOf("other.example"),
            )

    @Test
    fun theAttentionCardSaysWhatNeedsTheUserAndOneLinkShowsIt() {
        var opened = 0
        compose.setContent { NotesContent(state(::troubled), NotesActions(openAttention = { opened++ })) }
        compose.onNodeWithText(text(R.string.notes_attention_title)).assertExists()
        compose.onNodeWithText("home.example doesn't know 2 of your notes").assertExists()
        compose.onNodeWithText(plural(R.plurals.notes_attention_waiting, 1, 1)).assertExists()
        compose.onNodeWithText("The signing key of other.example doesn't match").assertExists()
        compose.onNodeWithText(text(R.string.notes_attention_open)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theAttentionPageListsWhatNeedsTheUserByMint() {
        var tapped: Note? = null
        var details: String? = null
        var closed = 0
        compose.setContent {
            NotesContent(
                state(::troubled, { it.copy(attentionOpen = true) }),
                NotesActions(tap = { tapped = it }, openMintDetails = { details = it }, closeAttention = { closed++ }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_attention_title)).assert(isHeading())
        compose.onNodeWithText("home.example").assert(isHeading())
        compose.onNodeWithText("other.example").assert(isHeading())
        // Only the notes that need the user: not the good ones next to them.
        compose.onNodeWithText("₿30,000").assertDoesNotExist()
        compose.onNodeWithText("₿5,000").performClick()
        assertEquals("u1", tapped?.id)
        // The other note home.example doesn't know, and other.example's waiting one.
        compose.onAllNodesWithText("₿1,000", substring = true).assertCountEquals(2)
        compose.onNodeWithText(text(DesignR.string.mint_key_changed_nothing_sent)).performClick()
        assertEquals("other.example", details)
        compose.onNodeWithContentDescription(text(DesignR.string.back)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun aReceivedLinkThatNeedsTheUserIsOnTheAttentionPageWithItsActions() {
        val onlyLightning = waiting().copy(state = ReceivedState.LightningOnly)
        compose.setContent { NotesContent(state({ it.copy(received = listOf(onlyLightning), attentionOpen = true) }), NotesActions()) }
        compose.onNodeWithText(text(DesignR.string.received_title)).assert(isHeading())
        compose.onNodeWithText(text(DesignR.string.received_receive)).assertExists()
    }

    @Test
    fun aMintNotAnsweringNeedsAttentionButNotWhileOffline() {
        var s by mutableStateOf(state({ it.copy(notAnsweringHosts = setOf("other.example", "elsewhere.example")) }))
        compose.setContent { NotesContent(s, NotesActions()) }
        compose.onNodeWithText("other.example isn't answering").assertExists()
        s = s.copy(offline = true)
        compose.onNodeWithText("other.example isn't answering").assertDoesNotExist()
    }

    @Test
    fun aReceivedLinkThatNeedsTheUserIsInTheCardAndOpensItsSection() {
        val onlyLightning = waiting().copy(state = ReceivedState.LightningOnly)
        compose.setContent { NotesContent(state({ it.copy(received = listOf(onlyLightning)) }), NotesActions()) }
        compose.onNodeWithText(plural(R.plurals.notes_attention_received, 1, 1)).assertExists()
        // Its section is open by itself: the link's own actions are right there.
        compose.onNodeWithText(text(DesignR.string.received_receive)).assertExists()
    }

    @Test
    fun uncertifiedNotesNeedNoAttention() {
        // other.example holds one that isn't certified: spent like any other, nothing to do.
        compose.setContent { NotesContent(state(), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_attention_title)).assertDoesNotExist()
    }

    @Test
    fun theNoteSettingsAreARowAtTheBottom() {
        var opened = 0
        compose.setContent { NotesContent(state(), NotesActions(openSettings = { opened++ })) }
        compose.onNodeWithText(text(R.string.notes_settings)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aTrustedMintIsMarkedOnItsGroupAndInTheNote() {
        compose.setContent {
            NotesContent(
                state({
                    it.copy(
                        mints = listOf(MintNotes("home.example", true, home, trusted = true), MintNotes("other.example", false, other)),
                        open = "h1",
                    )
                }),
                NotesActions(),
            )
        }
        compose.onNodeWithText("Home mint · Trusted · 2 notes").assertExists()
        compose.onNodeWithText(text(R.string.notes_trusted)).assertExists()
    }

    @Test
    fun emptyWalletSaysSoAndLeadsToReceive() {
        var receiving = 0
        compose.setContent { NotesContent(NotesState(loaded = true), NotesActions(openReceive = { receiving++ })) }
        compose.onNodeWithText(text(R.string.notes_empty)).assertExists()
        compose.onNodeWithText(text(R.string.notes_receive)).performClick()
        assertEquals(1, receiving)
    }

    @Test
    fun tappingANoteOpensItsActions() {
        var shared: String? = null
        var renewed: String? = null
        compose.setContent {
            NotesContent(state({ it.copy(open = "h2") }), NotesActions(share = { shared = it }, renew = { renewed = it }))
        }
        compose.onNodeWithText(text(DesignR.string.received_on, "home.example · "), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.notes_share)).performClick()
        compose.onNodeWithText(text(R.string.notes_renew)).performClick()
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
            NotesContent(
                state({ it.copy(open = "h2", developerMode = true, failuresOffered = true, simulatedFailure = armed) }),
                NotesActions(
                    simulateFailure = {
                        chosen = it
                        armed = it
                    }
                ),
            )
        }
        compose.onNodeWithText(text(R.string.notes_dev_section)).assertExists()
        compose.onNodeWithText(text(R.string.notes_dev_fail_lost)).performScrollTo().performClick()
        assertEquals(SimulatedFailure.Kind.AnswerLost, chosen)
        // Tapped again, it is taken back.
        compose.onNodeWithText(text(R.string.notes_dev_fail_lost)).performScrollTo().performClick()
        assertEquals(null, chosen)
    }

    @Test
    fun aFailureInTheSheetIsShownWithoutScrolling() {
        var s by mutableStateOf(state({ it.copy(open = "h2", developerMode = true, failuresOffered = true) }))
        compose.setContent { NotesContent(s, NotesActions()) }
        s = s.copy(outcome = NotesOutcome.Failed(NoteProblem.Unreachable))
        compose.onNodeWithText(text(DesignR.string.done)).assertIsDisplayed()
    }

    @Test
    fun theSheetScrollsToItsLastLine() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true, failuresOffered = true) }), NotesActions()) }
        compose.onNodeWithText("Key 0/", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun developerModeAloneOffersNoFailures() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2", developerMode = true) }), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_dev_section)).assertDoesNotExist()
    }

    @Test
    fun aNoteBeingPaidCanBeOpenedButNotActedOn() {
        val paying = note("p", "home.example", 1_000, NoteState.Paying)
        compose.setContent {
            NotesContent(state({ it.copy(mints = listOf(MintNotes("home.example", true, listOf(paying))), open = "p") }), NotesActions())
        }
        compose.onNodeWithText(text(R.string.notes_share)).assertIsNotEnabled()
        compose.onNodeWithText(text(DesignR.string.check_again)).assertIsNotEnabled()
    }

    @Test
    fun aConfirmingNoteCanOnlyBeRechecked() {
        val confirming = note("c", "home.example", 1_000, NoteState.Ambiguous)
        var rechecked: String? = null
        compose.setContent {
            NotesContent(
                state({ it.copy(mints = listOf(MintNotes("home.example", true, listOf(confirming))), open = "c") }),
                NotesActions(recheck = { rechecked = it }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_share)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.notes_renew)).assertIsNotEnabled()
        compose.onNodeWithText(text(DesignR.string.check_again)).assertIsEnabled().performClick()
        assertEquals("c", rechecked)
    }

    @Test
    fun aSettledChangeSaysHowItEnded() {
        compose.setContent { NotesContent(state({ it.copy(outcome = NotesOutcome.Settled(landed = false)) }), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_settled_back)).assertExists()
    }

    @Test
    fun longPressStartsSelectingAndTheBarActsOnTheSelection() {
        var pressed: Note? = null
        var combined = false
        var s by mutableStateOf(state(onPage()))
        compose.setContent { NotesContent(s, NotesActions(longPress = { pressed = it }, combine = { combined = true })) }
        compose.onNodeWithText("₿30,000").performTouchInput { longClick() }
        assertEquals("h1", pressed?.id)

        s = state(onPage(), { it.copy(selecting = true, selected = setOf("h1")) })
        compose.onNodeWithText("1 note selected · ₿30,000").assertExists()
        compose.onNodeWithText(text(R.string.notes_combine)).assertIsNotEnabled()
        s = state(onPage(), { it.copy(selecting = true, selected = setOf("h1", "h2")) })
        compose.onNodeWithText(text(R.string.notes_combine)).assertIsEnabled().performClick()
        assertEquals(true, combined)
    }

    @Test
    fun aMintCollectingSkippedOffersMoveAnyway() {
        var moved: String? = null
        val skipped = NotCollected.Reason.FeeTooHigh(feeMsat = 2_000, capPercent = 2)
        compose.setContent {
            NotesContent(
                state(
                    onPage("other.example"),
                    {
                        it.copy(mints = listOf(MintNotes("home.example", true, home), MintNotes("other.example", false, other, skipped)))
                    },
                ),
                NotesActions(moveAnyway = { moved = it }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_group_not_moved, "₿2", 2)).assertExists()
        compose.onNodeWithText(text(R.string.notes_move_anyway)).performClick()
        assertEquals("other.example", moved)
    }

    @Test
    fun sharedNotesWaitInTheirOwnSection() {
        var opened: String? = null
        val shared = note("s", "home.example", 3_333, NoteState.Shared)
        compose.setContent { NotesContent(state({ it.copy(shared = listOf(shared)) }), NotesActions(openShared = { opened = it })) }
        compose.onNodeWithText(text(R.string.notes_outside_title)).assertExists()
        // A closed row with what it holds, opened by a tap.
        compose.onNodeWithText("₿3,333 · 1 note").assertExists()
        compose.onNodeWithText(text(R.string.notes_shared_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.handed_out_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.notes_shared_hint)).assertExists()
        compose.onNodeWithText("₿3,333").performScrollTo().performClick()
        assertEquals("s", opened)
    }

    @Test
    fun aBrandDrawsHandedOutNotesLikeTheOthers() {
        val faces = mutableListOf<Triple<NoteFaceSize, NoteStatus, NoteFacePlace>>()
        val design =
            object : BrandDesign {
                override val components =
                    object : BrandComponents {
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
        compose.onNodeWithText(text(DesignR.string.handed_out_title)).performScrollTo().performClick()
        compose.onNodeWithText("zap ₿3,333 home.example").performScrollTo()
        assertTrue(Triple(NoteFaceSize.Row, NoteStatus.Waiting, NoteFacePlace.Shared) in faces)
        // The tap stays the app's.
        compose.onNodeWithText("zap ₿3,333 home.example").performClick()
        assertEquals("s", opened)
    }

    @Test
    fun checkingReportsNotesThatAreGone() {
        compose.setContent {
            NotesContent(
                state({ it.copy(outcome = NotesOutcome.Checked(ok = 3, gone = 1, unreachable = listOf("other.example"))) }),
                NotesActions(),
            )
        }
        compose
            .onNodeWithText(
                "1 note is no longer there: it was spent elsewhere, for example with this recovery phrase on another phone. other.example isn't answering. Try again later."
            )
            .assertExists()
    }

    @Test
    fun twoMintsNotAnsweringAreSaidInThePlural() {
        compose.setContent {
            NotesContent(
                state({ it.copy(outcome = NotesOutcome.Checked(ok = 3, gone = 0, unreachable = listOf("a.example", "b.example"))) }),
                NotesActions(),
            )
        }
        compose.onNodeWithText("a.example and b.example aren't answering. Try again later.", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        var s by mutableStateOf(state())
        compose.setContent { NotesContent(s, NotesActions()) }
        compose.onNodeWithText("4 Scheine · 2 Mints").assertExists()
        compose.onNodeWithText("Haupt-Mint · 2 Scheine").assertExists()
        s = state(onPage(), { it.copy(selecting = true, selected = setOf("h1", "h2")) })
        compose.onNodeWithText("2 Scheine · Haupt-Mint").assertExists()
        compose.onNodeWithText("2 Scheine ausgewählt · 45.000 ₿").assertExists()
        compose.onNodeWithText("Zusammenlegen").assertExists()
    }

    @Test
    fun aNoteOffersOnlyTheActionsTheBrandAllows() {
        compose.setContent {
            NotesContent(state({ it.copy(open = "h2", noteActions = setOf(NoteAction.Share, NoteAction.Renew)) }), NotesActions())
        }
        compose.onNodeWithText(text(R.string.notes_share)).assertExists()
        compose.onNodeWithText(text(R.string.notes_renew)).assertExists()
        compose.onNodeWithText(text(R.string.notes_split)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.check_again)).assertDoesNotExist()
        // The hand-out hint tells users to split first only where they can.
        compose.onNodeWithText(text(R.string.notes_share_hint)).assertExists()
        compose.onNodeWithText(text(R.string.notes_share_hint_split)).assertDoesNotExist()
    }

    @Test
    fun handingOutSaysFirstThatTheNoteLeavesTheBalance() {
        compose.setContent { NotesContent(state({ it.copy(open = "h2") }), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_share_hint_split)).assertExists()
        compose.onNodeWithText(text(R.string.notes_share_hint)).assertDoesNotExist()
    }

    @Test
    fun withoutAnActionOnSeveralNotesThereIsNoSelecting() {
        var pressed: Note? = null
        compose.setContent {
            NotesContent(
                state(onPage(), { it.copy(noteActions = setOf(NoteAction.Share, NoteAction.Split)) }),
                NotesActions(longPress = { pressed = it }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_select)).assertDoesNotExist()
        compose.onNodeWithText("₿30,000").performTouchInput { longClick() }
        assertEquals(null, pressed)
    }

    @Test
    fun theSelectionBarOffersOnlyTheAllowedBulkActions() {
        compose.setContent {
            NotesContent(
                state(onPage(), { it.copy(selecting = true, selected = setOf("h1"), noteActions = setOf(NoteAction.Recheck)) }),
                NotesActions(),
            )
        }
        compose.onNodeWithText(text(DesignR.string.check_again)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.notes_combine)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_move)).assertDoesNotExist()
    }

    @Test
    fun withoutMintsOneMintsNotesAreAPlainList() {
        compose.setContent {
            NotesContent(
                state({ it.copy(mints = listOf(MintNotes("home.example", true, home, trusted = true)), showMints = false, open = "h2") }),
                NotesActions(),
            )
        }
        // The balance's count, and the pile's title where it has no name.
        compose.onAllNodesWithText("2 notes").assertCountEquals(2)
        compose.onNodeWithText("home.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_home_mint), substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_trusted)).assertDoesNotExist()
    }

    @Test
    fun withoutMintsSeveralMintsAreSeparatedButNotNamed() {
        compose.setContent {
            NotesContent(
                state({
                    it.copy(showMints = false, outcome = NotesOutcome.Checked(ok = 3, gone = 0, unreachable = listOf("other.example")))
                }),
                NotesActions(),
            )
        }
        compose.onNodeWithText("4 notes").assertExists()
        compose.onNodeWithText("₿2,430").assertExists()
        compose.onNodeWithText("other.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.mint_not_answering_unnamed)).assertExists()
    }

    /** A brand that draws notes and their headers its own way: only the texts it's given, in its own layout. */
    private val bills =
        object : BrandDesign {
            override val components =
                object : BrandComponents {
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
        var s by mutableStateOf(state(onPage()))
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides bills) { NotesContent(s, NotesActions(tap = { tapped = it })) }
        }
        compose.onNodeWithText("bank home.example ₿45,000").assert(isHeading())
        compose.onNodeWithText("bill ₿30,000 Ready").performClick()
        assertEquals("h1", tapped?.id)

        // Selecting: the app says which are selected, whatever the brand draws.
        s = state(onPage(), { it.copy(selecting = true, selected = setOf("h1")) })
        compose
            .onNodeWithText("bill ₿30,000 Ready ✓")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose
            .onNodeWithText("bill ₿15,000 Ready")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun aBrandLearnsWhichNotesAreCertifiedWhateverTheirStatus() {
        val brand =
            object : BrandDesign {
                override val components =
                    object : BrandComponents {
                        @Composable
                        override fun NoteFace(params: NoteFaceParams, modifier: Modifier) {
                            Text("bill ${params.amount} ${if (params.certified) "sealed" else "plain"}", modifier)
                        }
                    }
            }
        val notes =
            listOf(
                note("c1", "home.example", 3_000, NoteState.Ambiguous),
                note("c2", "home.example", 2_000, NoteState.Ambiguous, certified = false),
                note("c3", "home.example", 1_000, certified = false),
            )
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides brand) {
                NotesContent(
                    state({ it.copy(mints = listOf(MintNotes("home.example", true, notes))) }, onPage()),
                    NotesActions(),
                )
            }
        }
        compose.onNodeWithText("bill ₿3,000 sealed").assertExists()
        compose.onNodeWithText("bill ₿2,000 plain").assertExists()
        compose.onNodeWithText("bill ₿1,000 plain").assertExists()
    }

    @Test
    fun aBrandDecoratesTheTopOfTheNotesAndAnEmptyView() {
        val seen = mutableListOf<Pair<Anchor, WalletStatus>>()
        val bodies = mutableListOf<Pair<Page, WalletStatus>>()
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    if (anchor == Anchor.NotesHeader || anchor == Anchor.NotesEmpty) seen += anchor to context.status
                }

                @Composable
                override fun Body(page: Page, context: DecorationContext, content: @Composable () -> Unit) {
                    bodies += page to context.status
                    content()
                }
            }
        val mint = MintNotes("mint.example", true, listOf(note("a", "mint.example", 1_000)), trusted = true)
        var state by mutableStateOf(NotesState(loaded = true))
        compose.setContent { CompositionLocalProvider(LocalBrandDesign provides design) { NotesContent(state, NotesActions()) } }
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.Empty, Anchor.NotesEmpty to WalletStatus.Empty), seen.toSet())
        assertEquals(Page.Notes to WalletStatus.Empty, bodies.last())

        seen.clear()
        state = NotesState(loaded = true, mints = listOf(mint), shared = listOf(note("s", "mint.example", 500, NoteState.Shared)))
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.WaitingForRecipient), seen.toSet())
        assertEquals(Page.Notes to WalletStatus.WaitingForRecipient, bodies.last())

        seen.clear()
        state = NotesState(loaded = true, mints = listOf(mint))
        compose.waitForIdle()
        assertEquals(setOf(Anchor.NotesHeader to WalletStatus.Idle), seen.toSet())
    }

    @Test
    fun receivedNotesAreListedApartWithWhereTheyStand() {
        val locked =
            ReceivedNote(
                "r".repeat(64),
                "a",
                "mint.example",
                "w",
                "k1",
                null,
                null,
                4_000_000,
                1_893_456_000_000,
                null,
                ReceivedState.Locked,
                null,
                1,
                1,
            )
        compose.setContent { NotesContent(NotesState(loaded = true, received = listOf(locked)), NotesActions()) }
        compose.onNodeWithText("₿4,000 · 1 note").assertExists()
        // It only waits: nothing to do, so its section stays closed until opened.
        compose.onNodeWithText(text(R.string.notes_attention_title)).assertDoesNotExist()
        compose.onNodeWithText("Locked until ", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.received_title)).performClick()
        compose.onNodeWithText("Locked until ", substring = true).assertExists()
        compose.onNodeWithText(text(DesignR.string.received_on, "mint.example")).assertExists()
        compose.onNodeWithText("No notes yet", substring = true).assertDoesNotExist()
    }

    private fun waiting() =
        ReceivedNote("w".repeat(64), "a", "mint.example", "w", "k1", null, null, 4_000_000, null, null, ReceivedState.Waiting, null, 1, 1)

    @Test
    fun offlineTheNotesViewSaysSoInItsStatusAndRows() {
        val state = NotesState(loaded = true, offline = true, received = listOf(waiting()))
        assertEquals(WalletStatus.Problem(ProblemKind.Offline), notesStatus(state))
        compose.setContent { NotesContent(state, NotesActions()) }
        compose.onNodeWithText(text(DesignR.string.received_title)).performClick()
        compose.onNodeWithText(text(DesignR.string.received_waiting_offline)).assertExists()
    }

    @Test
    fun anEmptyNotesViewStaysEmptyOffline() {
        // Its empty state draws the brand's mark already; offline holds nothing up here, so the header doesn't draw a second one.
        assertEquals(WalletStatus.Empty, notesStatus(NotesState(loaded = true, offline = true)))
    }

    @Test
    fun aWaitingReceivedNoteOnASilentMintSaysSoAndOffersNoArchive() {
        compose.setContent {
            NotesContent(NotesState(loaded = true, notAnsweringHosts = setOf("mint.example"), received = listOf(waiting())), NotesActions())
        }
        compose.onNodeWithText(text(DesignR.string.received_title)).performClick()
        compose.onNodeWithText(text(DesignR.string.received_waiting_mint, "mint.example")).assertExists()
        compose.onNodeWithText(text(DesignR.string.received_hint)).assertExists()
        compose.onNodeWithText(text(DesignR.string.received_archive)).assertDoesNotExist()
    }

    @Test
    fun theArchiveIsOneRowThatOpensItAndOnlyWhenItHoldsAny() {
        var opened = false
        var links by mutableStateOf(0)
        var notes by mutableStateOf(0)
        compose.setContent {
            NotesContent(state({ it.copy(archivedLinks = links, archivedNotes = notes) }), NotesActions(openArchive = { opened = true }))
        }
        compose.onNodeWithText(text(R.string.archive_title)).assertDoesNotExist()
        links = 2
        compose.onNodeWithText(text(R.string.archive_title)).performScrollTo()
        compose.onNodeWithText(plural(R.plurals.notes_archived_links, 2, 2)).assertExists()
        compose.onNodeWithText(plural(R.plurals.notes_archived_notes, 1, 1)).assertDoesNotExist()
        notes = 1
        compose.onNodeWithText(plural(R.plurals.notes_archived_notes, 1, 1)).assertExists()
        compose.onNodeWithText(text(R.string.archive_title)).performClick()
        assertTrue(opened)
    }

    private val unknown = listOf(note("u1", "home.example", 5_000, NoteState.Unknown), note("u2", "home.example", 1_000, NoteState.Unknown))

    private fun withUnknown(s: NotesState) = s.copy(mints = listOf(MintNotes("home.example", true, home + unknown)))

    @Test
    fun selectedNotesTheirMintDoesNotKnowCanOnlyBeArchivedOrRechecked() {
        var archived = 0
        compose.setContent {
            NotesContent(
                state(::withUnknown, onPage(), { it.copy(selecting = true, selected = setOf("u1", "u2")) }),
                NotesActions(archiveSelected = { archived++ }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_combine)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_move)).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.check_again)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive)).performClick()
        assertEquals(1, archived)
    }

    @Test
    fun selectedNotesInTheBalanceAreNotOfferedTheArchive() {
        compose.setContent {
            NotesContent(state(::withUnknown, onPage(), { it.copy(selecting = true, selected = setOf("h1")) }), NotesActions())
        }
        compose.onNodeWithText(text(R.string.notes_combine)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive)).assertDoesNotExist()
    }

    @Test
    fun aNoteItsMintDoesNotKnowCanBeArchivedFromItsDetailsWhateverTheBrandOffers() {
        var archived: String? = null
        compose.setContent {
            NotesContent(
                state(::withUnknown, { it.copy(open = "u1", noteActions = emptySet()) }),
                NotesActions(archive = { archived = it }),
            )
        }
        compose.onNodeWithText(text(R.string.notes_archive_hint)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive)).performClick()
        assertEquals("u1", archived)
    }

    private fun withHandedOut(handedOut: HandedOut) = { s: NotesState ->
        s.copy(
            mints =
                listOf(MintNotes("home.example", true, home + unknown.map { if (it.id == "u1") it.copy(handedOut = handedOut) else it }))
        )
    }

    @Test
    fun aHandedOutNoteItsMintDoesNotKnowSaysItWaitsToBeClaimedNotThatItComesBack() {
        compose.setContent { NotesContent(state(withHandedOut(HandedOut.Waiting), { it.copy(open = "u1") }), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_state_unknown_handed_out_detail)).assertExists()
        compose.onNodeWithText(text(R.string.notes_state_unknown_detail)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.notes_archive)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive_hint_handed_out)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive_hint)).assertDoesNotExist()
    }

    @Test
    fun aHandedOutNoteItsMintDoesNotKnowIsListedWithTheHandedOutOnesNotInTheWarning() {
        var opened: Note? = null
        val handedOut = note("u1", "home.example", 5_000, NoteState.Unknown).copy(handedOut = HandedOut.Waiting)
        compose.setContent { NotesContent(state({ it.copy(handedOutUnknown = listOf(handedOut)) }), NotesActions(tap = { opened = it })) }
        compose.onNodeWithText(text(R.string.notes_attention_title)).assertDoesNotExist()
        compose.onNodeWithText("₿5,000 · 1 note").assertExists()
        compose.onNodeWithText(text(DesignR.string.handed_out_title)).performScrollTo().performClick()
        // Its details, not its QR code: nobody can claim it until its mint knows it again.
        compose.onNodeWithText(text(R.string.notes_state_unknown)).performScrollTo().performClick()
        assertEquals("u1", opened?.id)
    }

    @Test
    fun notesUnknownToSeveralMintsAreCountedTogether() {
        compose.setContent {
            NotesContent(
                state(
                    ::withUnknown,
                    {
                        it.copy(
                            mints =
                                it.mints + MintNotes("other.example", false, listOf(note("u3", "other.example", 1_000, NoteState.Unknown)))
                        )
                    },
                ),
                NotesActions(),
            )
        }
        compose.onNodeWithText("Your mints don't know 3 of your notes").assertExists()
    }

    @Test
    fun aNoteItsMintDoesNotKnowOffersOnlyRecheckAndArchive() {
        compose.setContent { NotesContent(state(::withUnknown, { it.copy(open = "u1") }), NotesActions()) }
        compose.onNodeWithText(text(DesignR.string.check_again)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive)).assertExists()
        for (gone in
            listOf(
                R.string.notes_share,
                R.string.notes_split,
                R.string.notes_renew,
                R.string.notes_share_hint,
                R.string.notes_share_hint_split,
                R.string.notes_renew_hint,
            )) compose.onNodeWithText(text(gone)).assertDoesNotExist()
    }

    @Test
    fun aHandedOutNoteToTakeBackSaysItIsTakenBackOnceItsMintKnowsIt() {
        compose.setContent { NotesContent(state(withHandedOut(HandedOut.TakeBack), { it.copy(open = "u1") }), NotesActions()) }
        compose.onNodeWithText(text(DesignR.string.note_take_back_when_known)).assertExists()
        compose.onNodeWithText(text(R.string.notes_archive_hint_take_back)).assertExists()
    }

    @Test
    fun checkingTellsWhatHappenedToHandedOutNotesApartFromTheBalance() {
        val checked =
            NotesOutcome.Checked(ok = 0, gone = 0, unreachable = emptyList(), handedOutSpent = 1, handedOutUnknown = 1, takenBack = 1)
        compose.setContent { NotesContent(state({ it.copy(outcome = checked) }), NotesActions()) }
        compose.onNodeWithText(plural(R.plurals.notes_checked_taken_back, 1, 1), substring = true).assertExists()
        compose.onNodeWithText(plural(R.plurals.notes_checked_handed_out_spent, 1, 1), substring = true).assertExists()
        compose.onNodeWithText(plural(R.plurals.notes_checked_handed_out_unknown, 1, 1), substring = true).assertExists()
        compose.onNodeWithText("spent elsewhere", substring = true).assertDoesNotExist()
        compose.onNodeWithText("out of your balance", substring = true).assertDoesNotExist()
    }

    @Test
    fun checkingSaysWhenHandedOutNotesAreKnownAgain() {
        compose.setContent {
            NotesContent(
                state({ it.copy(outcome = NotesOutcome.Checked(ok = 0, gone = 0, unreachable = emptyList(), handedOut = 1)) }),
                NotesActions(),
            )
        }
        compose.onNodeWithText(plural(R.plurals.notes_checked_handed_out, 1, 1)).assertExists()
        compose.onNodeWithText("Checked", substring = true).assertDoesNotExist()
    }

    @Test
    fun aNoteInTheBalanceIsNotOfferedTheArchive() {
        compose.setContent { NotesContent(state({ it.copy(open = "h1") }), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_archive)).assertDoesNotExist()
    }

    @Test
    fun archivingSaysWhereTheNotesWent() {
        compose.setContent { NotesContent(state({ it.copy(outcome = NotesOutcome.Archived(2)) }), NotesActions()) }
        compose.onNodeWithText(plural(R.plurals.notes_archived_done, 2, 2)).assertExists()
    }

    @Test
    fun untilTheNotesAreReadNothingIsDrawnNotATotalOfNothing() {
        compose.setContent { NotesContent(NotesState(loaded = false), NotesActions()) }
        compose.onNodeWithText(text(R.string.notes_empty)).assertDoesNotExist()
    }
}
