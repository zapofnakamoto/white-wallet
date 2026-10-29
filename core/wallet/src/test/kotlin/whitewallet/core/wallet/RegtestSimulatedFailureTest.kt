package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.SimulatedFailure.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** A failure a developer arms on a note's sheet takes the wallet's real path, against the real mint. */
class RegtestSimulatedFailureTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `with no answer nothing is sent and the note stays as it was`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val note = alice.notes().single()
        val failed = assertIs<NoteActionResult.Failed>(SimulatedFailure.during(Kind.Unreachable) { alice.wallet.splitNote(note.id, 1_500_000) })
        assertEquals(NoteProblem.Unreachable, failed.problem)
        assertEquals(listOf(note.id), alice.notes().map { it.id })
        // Used up: the next split goes through.
        assertIs<NoteActionResult.Done>(alice.wallet.splitNote(note.id, 1_500_000))
    }

    @Test
    fun `a refusal leaves the note as it was`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val note = alice.notes().single()
        val failed = assertIs<NoteActionResult.Failed>(SimulatedFailure.during(Kind.Refused) { alice.wallet.splitNote(note.id, 1_500_000) })
        assertEquals(NoteProblem.Refused, failed.problem)
        alice.wallet.refresh()
        assertEquals(listOf(note.id), alice.notes().map { it.id }, "still outstanding at the mint")
    }

    @Test
    fun `a lost answer leaves the split open until the next refresh settles it`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val note = alice.notes().single()
        assertEquals(NoteActionResult.Pending, SimulatedFailure.during(Kind.AnswerLost) { alice.wallet.splitNote(note.id, 1_500_000) })
        assertEquals(NoteState.Ambiguous, alice.store.note(note.id)?.state)
        assertEquals(emptyList(), alice.notes(), "nothing to spend while open")
        alice.wallet.refresh()
        assertEquals(listOf(1_500_000L, 4_999_000L - 1_500_000 - 1_000).sorted(), alice.notes().map { it.amountMsat }.sorted(), "it landed")
        assertNotEquals(NoteState.Outstanding, alice.store.note(note.id)?.state)
    }

    @Test
    fun `a recheck goes unanswered whatever failure is armed`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val note = alice.notes().single()
        for (kind in Kind.entries) assertEquals(NoteCheck.Unreachable, SimulatedFailure.during(kind) { alice.wallet.recheckNote(note.id) }, "$kind")
        assertIs<NoteCheck.Ok>(alice.wallet.recheckNote(note.id))
    }

    @Test
    fun `a recheck settles a confirming note whose change landed`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val note = alice.notes().single()
        assertEquals(NoteActionResult.Pending, SimulatedFailure.during(Kind.AnswerLost) { alice.wallet.splitNote(note.id, 1_500_000) })
        // No answer yet either: it stays open.
        assertEquals(NoteCheck.Unreachable, SimulatedFailure.during(Kind.Unreachable) { alice.wallet.recheckNote(note.id) })
        assertEquals(NoteState.Ambiguous, alice.store.note(note.id)?.state)

        assertEquals(NoteCheck.Landed, alice.wallet.recheckNote(note.id))
        assertEquals(NoteState.Spent, alice.store.note(note.id)?.state)
        assertEquals(listOf(1_500_000L, 3_498_000L).sorted(), alice.notes().map { it.amountMsat }.sorted())
        assertEquals(4_998_000L, alice.balance(), "the split's fee off, nothing else")
    }

    @Test
    fun `a recheck gives a confirming note back when its change never landed`() = runBlocking<Unit> {
        // The split never reaches the mint, and nothing after it does either until the connection is back: the
        // wallet can't tell whether it landed.
        var cut = false
        var down = false
        val alice = Regtest.phone(client = Regtest.client(Interceptor { chain ->
            if (cut && chain.request().url.isMutation) down = true
            if (down) throw IOException("connection dropped") else chain.proceed(chain.request())
        }))
        alice.fund(5000)
        val note = alice.notes().single()
        cut = true
        assertEquals(NoteActionResult.Pending, alice.wallet.splitNote(note.id, 1_500_000))
        assertEquals(NoteState.Ambiguous, alice.store.note(note.id)?.state)
        cut = false
        down = false
        assertEquals(NoteCheck.NotLanded, alice.wallet.recheckNote(note.id))
        assertEquals(listOf(note.id), alice.notes().map { it.id }, "spendable again")
    }

    @Test
    fun `combine and renew fail as armed too`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000, 3000)
        val notes = alice.notes()
        assertEquals(NoteProblem.Refused, assertIs<NoteActionResult.Failed>(SimulatedFailure.during(Kind.Refused) { alice.wallet.combineNotes(notes.map { it.id }) }).problem)
        val renew = assertIs<RotateResult.Failed>(SimulatedFailure.during(Kind.Unreachable) { alice.wallet.rotateNote(notes.first().id) })
        assertEquals(true, renew.noAnswer)
        assertEquals(notes.map { it.id }.toSet(), alice.notes().map { it.id }.toSet())
    }
}
