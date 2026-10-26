package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy
import whitewallet.brand.ServiceNotes
import whitewallet.core.model.ActivityKind
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient

/** What the notes page and the note strategies do with notes, on the real mint. */
class RegtestNotesTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `combining notes leaves one, and the row shows what the mint kept or paid back`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000, 2000, 3000)
        val notes = alice.notes()
        val inputs = notes.sumOf { it.amountMsat }
        val done = assertIs<NoteActionResult.Done>(alice.wallet.combineNotes(notes.map { it.id }))
        val combined = done.notes.single()
        assertNotNull(combined.certificate)
        assertEquals(listOf(combined.id), alice.notes().map { it.id })
        assertEquals(combined.amountMsat, alice.balance())
        val row = alice.wallet.activity.first().single { it.kind == ActivityKind.Combined }
        assertEquals((inputs - combined.amountMsat).takeIf { it != 0L }, row.feeMsat)
        assertEquals(false, row.automatic, "done by the user")
    }

    @Test
    fun `a note is split into the amount and the change, minus the split fee`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val note = alice.notes().single()
        val limits = assertNotNull(alice.wallet.splitLimits(note.id))
        assertEquals(1_000, limits.feeMsat)
        val done = assertIs<NoteActionResult.Done>(alice.wallet.splitNote(note.id, 1_500_000))
        assertEquals(listOf(1_500_000L, 4_999_000 - 1_500_000 - 1_000), done.notes.map { it.amountMsat })
        assertEquals(done.notes.map { it.id }.toSet(), alice.notes().map { it.id }.toSet())
    }

    @Test
    fun `renewing a note moves it to a fresh wallet key, same amount`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val old = alice.notes().single()
        val rotated = assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(old.id))
        assertEquals(1_999_000, rotated.amountMsat)
        val renewed = alice.notes().single()
        assertTrue(renewed.id != old.id)
        assertEquals(0, renewed.purpose)
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a whole note is shared as it is, without a fee, and can be taken back`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000, 2000)
        val small = alice.notes().single { it.amountMsat == 999_000L }
        val shared = assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(small.id)).note
        assertEquals(SharedNoteState.Waiting, shared.state)
        assertEquals(1_999_000, alice.balance(), "out of the balance while shared")

        // Making a note of exactly an existing note's amount also shares it as is.
        val exact = assertIs<CreateNoteResult.Created>(alice.wallet.createNote(1_999_000))
        assertEquals(0, exact.feeMsat)
        assertEquals(0, alice.balance())

        assertEquals(ReclaimResult.Reclaimed(999_000), alice.wallet.reclaimNote(shared.id))
        assertEquals(ReclaimResult.Reclaimed(1_999_000), alice.wallet.reclaimNote(exact.note.id))
        assertEquals(2_998_000, alice.balance())
    }

    @Test
    fun `notes spent with the same phrase on another phone are noticed, and not handed out`() = runBlocking<Unit> {
        val phone1 = Regtest.phone()
        phone1.fund(1000, 2000, 3000)
        assertIs<NoteCheck.Ok>(phone1.wallet.recheckNote(phone1.notes().first().id))

        // Phone 2 restores the phrase and renews every note: on phone 1 they are all spent now.
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(phone2.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        val ids = phone1.notes().map { it.id }
        ids.forEach { assertIs<RotateResult.Rotated>(phone2.rotateNote(it)) }

        assertEquals(NoteCheck.Spent, phone1.wallet.recheckNote(ids[0]))
        assertEquals(ShareNoteResult.AlreadySpent, phone1.wallet.shareNote(ids[1]))
        val check = phone1.wallet.recheckNotes()
        assertEquals(1, check.gone)
        assertEquals(emptyList(), check.unreachable)
        assertEquals(0, phone1.balance())
    }

    @Test
    fun `keep only one note merges many notes in batches`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        val payments = (1..11).map { 1000L + it }
        alice.fund(*payments.toLongArray())
        val note = alice.notes().single()
        assertTrue(note.amountMsat >= payments.sumOf { (it - 1) * 1000 }, "a merge never loses value")
        val merges = alice.wallet.activity.first().filter { it.kind == ActivityKind.Combined }
        assertEquals(listOf(2, 10), merges.map { it.noteCount }.sortedBy { it }, "at most 10 notes per merge")
        assertTrue(merges.all { it.automatic })
    }

    @Test
    fun `a brand strategy reshapes incoming notes, round after round`() = runBlocking<Unit> {
        // Keeps notes at or below 1000 sats: splits 1000 sats off any bigger note.
        val chunks = object : NoteStrategy {
            override val id = "chunks"
            override val title = 0
            override val description = 0
            override fun plan(service: ServiceNotes): List<NoteStep> =
                service.notes.filter { it.amountMsat > 1_000_000 + service.feeBaseMsat }.map { NoteStep.Split(it.id, 1_000_000) }
        }
        val store = InMemoryWalletStore()
        val strategies = NoteStrategies(listOf(ManualStrategy(), chunks), listOf(ManualStrategy.ID, chunks.id), chunks.id)
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), strategies = strategies)
        val name = Regtest.name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, HOST))
        Regtest.pay("$name@$HOST", 3504)
        wallet.refresh()
        // 3503 sats: three splits of 1000, each 1 sat fee, leave 500.
        assertEquals(listOf(1_000_000L, 1_000_000, 1_000_000, 500_000), store.allNotes().filter { it.state == whitewallet.core.model.NoteState.Outstanding }
            .map { it.amountMsat }.sortedDescending())
        assertEquals(3, assertNotNull(wallet.lastPolicyRun.first()).split)
    }

    @Test
    fun `keeping one note and always collecting puts a note from another mint into the one note at home`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID, collectDefaults = CollectDefaults(offered = false, always = true))
        alice.fund(5000)
        assertIs<ClaimResult.Claimed>(alice.wallet.claim(assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(Regtest.note(3000, second = true)))))
        assertEquals(emptyList(), alice.notes(Regtest.HOST2), "nothing left on the other mint")
        val home = alice.notes().single()
        assertEquals(home.amountMsat, alice.balance())
        assertTrue(home.amountMsat > 4_999_000 + 2_000_000, "the claimed note arrived, less the move's fees: ${home.amountMsat}")
        assertEquals(true, alice.wallet.activity.first().single { it.kind == ActivityKind.MovedService }.automatic)
    }
}
