package whitewallet.core.wallet

import kotlinx.coroutines.runBlocking
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.SimulatedFailure.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** A failure a developer arms on a note's sheet takes the wallet's real path, against the real mint. */
class RegtestSimulatedFailureTest {
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
