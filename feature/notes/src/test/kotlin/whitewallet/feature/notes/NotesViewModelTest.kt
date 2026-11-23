package whitewallet.feature.notes

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** The notes view's pages and selection as the notes change underneath them. Runs without any mint: nobody answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private val home = "home.example"
    private val other = "other.example"
    private val store = InMemoryWalletStore()
    private val wallet =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(
                http =
                    LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(Interceptor { throw IOException("no answer") }).build()
            ),
        )

    private fun note(id: String, at: String, msat: Long, state: NoteState = NoteState.Outstanding) =
        Note(id, "a", at, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xff, msat, "cs1", state, 0)

    // The view model runs on the test's dispatcher: a test waits for a state as it comes, with no clock of its own.
    @BeforeTest fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** One account at [home], with two notes there and two on [other]. */
    private suspend fun screen(): NotesViewModel {
        store.addAccount("a", "Main", null, home)
        store.upsertNotes(listOf(note("h1", home, 3_000), note("h2", home, 2_000), note("o1", other, 5_000), note("o2", other, 1_000)))
        return NotesViewModel(wallet, AppPreferences.InMemory(), AppDefaults(emptyList(), allowMintChange = true)).also { screen ->
            CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect {} }
        }
    }

    private suspend fun NotesViewModel.await(settled: (NotesState) -> Boolean): NotesState = state.first(settled)

    @Test
    fun `a mint's page whose notes are all gone closes for good, with its selection`() = runTest {
        val screen = screen()
        screen.await { it.mints.size == 2 }
        screen.openMint(other)
        screen.startSelecting()
        screen.await { it.selecting }
        screen.tap(note("o1", other, 5_000))
        assertEquals(setOf("o1"), screen.await { it.selected.isNotEmpty() }.selected)

        // Both notes there are spent elsewhere: the overview, and nothing left selected on it.
        store.upsertNotes(listOf(note("o1", other, 5_000, NoteState.Spent), note("o2", other, 1_000, NoteState.Spent)))
        val overview = screen.await { it.mints.size == 1 && !it.selecting }
        assertNull(overview.mintPage)
        assertTrue(overview.selected.isEmpty())

        // A note lands there again: the page doesn't open by itself.
        store.upsertNotes(listOf(note("o3", other, 4_000)))
        val again = screen.await { it.mints.size == 2 }
        assertNull(again.mintPage)
        assertFalse(again.selecting)
    }

    @Test
    fun `another mint's page opens without the selection made on the last one`() = runTest {
        val screen = screen()
        screen.await { it.mints.size == 2 }
        screen.openMint(home)
        screen.select(note("h1", home, 3_000))
        screen.await { it.selecting && it.selected == setOf("h1") }

        screen.openMint(other)
        val page = screen.await { it.mintPage == other }
        assertFalse(page.selecting)
        assertTrue(page.selected.isEmpty())
    }

    @Test
    fun `leaving a mint's page ends its selection`() = runTest {
        val screen = screen()
        screen.await { it.mints.size == 2 }
        screen.openMint(home)
        screen.startSelecting()
        screen.await { it.selecting }

        screen.closeMint()
        val overview = screen.await { it.mintPage == null }
        assertFalse(overview.selecting)
    }

    @Test
    fun `the home mint's notes get the first bill colours, the others theirs in turn`() = runTest {
        val screen = screen()
        val state = screen.await { it.mints.size == 2 }
        assertEquals(listOf(home, other), state.mints.map { it.host })
        assertEquals(0, state.colorIndex(home))
        assertEquals(1, state.colorIndex(other))
    }

    @Test
    fun `the count and total of the balance leave out notes their mint doesn't know`() = runTest {
        val screen = screen()
        store.upsertNotes(listOf(note("u1", home, 7_000, NoteState.Unknown)))
        val state = screen.await { it.unknownNotes.isNotEmpty() }
        assertEquals(4, state.balanceNoteCount)
        assertEquals(11_000, state.totalMsat)
        assertEquals(5, state.noteCount)
    }

    @Test
    fun `a handed-out note its mint doesn't know is listed with the handed-out ones, not on its mint`() = runTest {
        val screen = screen()
        store.upsertNotes(listOf(note("u1", home, 7_000, NoteState.Unknown).copy(handedOut = HandedOut.Waiting)))
        val state = screen.await { it.handedOutUnknown.isNotEmpty() }
        assertEquals(listOf("u1"), state.handedOutUnknown.map { it.id })
        assertTrue(state.unknownNotes.isEmpty(), "not a warning: it was never in the balance")
        assertTrue(state.mints.flatMap { it.notes }.none { it.id == "u1" })
        assertEquals(7_000, state.handedOutMsat)

        // Its details still open, from the handed-out list.
        screen.tap(state.note("u1")!!)
        assertEquals("u1", screen.await { it.openNote != null }.openNote?.id)
    }

    @Test
    fun `the page of what needs the user closes once nothing does, and doesn't open again by itself`() = runTest {
        val screen = screen()
        store.upsertNotes(listOf(note("u1", home, 7_000, NoteState.Unknown)))
        screen.await { it.needsAttention }
        screen.openAttention()
        screen.await { it.attentionOpen }

        // The mint knows it after all: nothing needs the user, the overview again.
        store.upsertNotes(listOf(note("u1", home, 7_000)))
        screen.await { !it.needsAttention }

        // Another note it doesn't know: the card says so, the page stays closed.
        store.upsertNotes(listOf(note("u2", home, 1_000, NoteState.Unknown)))
        assertFalse(screen.await { it.needsAttention }.attentionOpen)
    }

    @Test
    fun `a moment offline doesn't close the page of a mint that isn't answering`() {
        val notes = listOf(MintNotes(other, false, listOf(note("o1", other, 5_000))))
        val silent = NotesState(loaded = true, mints = notes, notAnsweringHosts = setOf(other))
        assertTrue(silent.needsAttention)
        // Offline, no mint is said to be not answering; the page stays all the same.
        assertFalse(silent.copy(offline = true).needsAttention)
        assertTrue(silent.copy(offline = true).keepsAttentionOpen)
        assertFalse(silent.copy(notAnsweringHosts = emptySet()).keepsAttentionOpen)
    }
}
