package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.NotePurpose

/**
 * A note the wallet holds but the mint doesn't know (as after the mint lost its records), against the real reference
 * mint (dev/regtest, LNURLCASH_REGTEST=1): it is set aside as unknown, and never stops the rest of the wallet.
 */
class RegtestUnknownNoteTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** A note at a wallet key the mint has never seen, recorded as outstanding; [updatedAtMillis] orders it for strategies. */
    private suspend fun Regtest.Phone.unknownNote(sats: Long, updatedAtMillis: Long = 0, index: Long = 900): Note {
        val accountId = store.currentAccountId()!!
        val q = wallet.branch(accountId, Regtest.HOST).notePubkey(NotePurpose.Wallet, index).toHex()
        return Note(q, accountId, Regtest.HOST, NoteKind.KeyPath, NotePurpose.Wallet.value, index, sats * 1000, null, NoteState.Outstanding, updatedAtMillis)
            .also { store.upsertNotes(listOf(it)) }
    }

    private suspend fun Regtest.Phone.state(note: Note) = store.note(note.id)?.state

    @Test
    fun `keeping only one note sets aside a note the mint doesn't know when it is asked about it first`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        alice.fund(1000)
        // Oldest, so the strategy names it first: the mint won't even say what it is worth.
        val unknown = alice.unknownNote(5000, updatedAtMillis = 0)
        alice.fund(1000)
        assertEquals(NoteState.Unknown, alice.state(unknown))
        val combined = alice.notes().single()
        assertTrue(combined.amountMsat >= 1_998_000, "the two real notes are combined (LUD-25 may refund base fees)")
        assertEquals(combined.amountMsat, alice.balance(), "an unknown note isn't in the balance")
    }

    @Test
    fun `keeping only one note sets aside a note the mint doesn't know when it refuses the combine`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        alice.fund(1000)
        // Newest, so the combine is sent and refused ("Invalid or already spent k1.").
        val unknown = alice.unknownNote(5000, updatedAtMillis = Long.MAX_VALUE / 2)
        alice.fund(1000)
        assertEquals(NoteState.Unknown, alice.state(unknown))
        assertTrue(alice.notes().single().amountMsat >= 1_998_000)
    }

    @Test
    fun `a payment planned from a note the mint doesn't know is made from the others`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        // Worth exactly the invoice: the planner melts it as is, and the mint doesn't know it.
        val unknown = alice.unknownNote(1500)
        val preview = alice.wallet.previewPayment(Regtest.invoice(1500)) as PayPreview.Ready
        assertEquals(PayResult.Paid(1_500_000, 1_000), alice.wallet.pay(preview, 1_500_000))
        assertEquals(NoteState.Unknown, alice.state(unknown))
        assertEquals(1_999_000 - 1_500_000 - 1_000, alice.balance())
    }

    @Test
    fun `a payment that would split a note the mint doesn't know splits another one`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        // The largest note, so the planner splits it.
        val unknown = alice.unknownNote(5000)
        val preview = alice.wallet.previewPayment(Regtest.invoice(1000)) as PayPreview.Ready
        assertEquals(PayResult.Paid(1_000_000, 1_000), alice.wallet.pay(preview, 1_000_000))
        assertEquals(NoteState.Unknown, alice.state(unknown))
    }

    @Test
    fun `rechecking a note the mint doesn't know sets it aside`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000)
        val unknown = alice.unknownNote(5000)
        assertEquals(NoteCheck.Unknown, alice.wallet.recheckNote(unknown.id))
        assertEquals(NoteState.Unknown, alice.state(unknown))
        assertEquals(999_000, alice.balance())
        assertEquals(1 to 5_000_000L, alice.wallet.accountMints.first().single { it.isHome }.let { it.unknownCount to it.unknownMsat })
    }

    @Test
    fun `an unknown note the mint knows again is back in the balance on the next refresh`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000)
        val note = alice.notes().single()
        // As if the mint had said it doesn't know it, and then recovered its records.
        alice.store.upsertNotes(listOf(note.copy(state = NoteState.Unknown)))
        assertEquals(0, alice.balance())
        alice.wallet.refresh()
        assertEquals(NoteState.Outstanding, alice.state(note))
        assertEquals(999_000, alice.balance())
    }

    @Test
    fun `asking about unknown notes now brings back the ones the mint knows`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000)
        val note = alice.notes().single()
        alice.store.upsertNotes(listOf(note.copy(state = NoteState.Unknown)))
        val unknown = alice.unknownNote(5000)
        assertEquals(NoteCheck.Unknown, alice.wallet.recheckNote(unknown.id))
        assertEquals(NotesCheck(checked = 1, gone = 0, unreachable = emptyList(), unknown = 1), alice.wallet.recheckUnknownNotes())
        assertEquals(NoteState.Outstanding, alice.state(note))
        assertEquals(NoteState.Unknown, alice.state(unknown))
    }
}
