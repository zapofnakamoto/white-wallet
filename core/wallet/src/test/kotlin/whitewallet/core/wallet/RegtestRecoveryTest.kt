package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * When the answer doesn't come back, a request never leaves, or two phones share a phrase: the journal and
 * reconciliation against the real mint settle what happened, and no money is lost or counted twice.
 */
class RegtestRecoveryTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** Breaks every note-changing request while [armed]: [lose] = the mint acts but the answer is lost, else dropped before it leaves. */
    private class Breaker(var armed: Boolean = false, val lose: Boolean = true, val only: (okhttp3.HttpUrl) -> Boolean = { it.isMutation }) {
        /** How many requests it broke. */
        var hits = 0
        val client = Regtest.client { chain ->
            val request = chain.request()
            if (!armed || !only(request.url)) return@client chain.proceed(request)
            hits++
            if (lose) {
                chain.proceed(request).close()
                Regtest.lost()
            }
            Regtest.dropped()
        }

        suspend fun <T> during(block: suspend () -> T): T {
            armed = true
            try {
                return block()
            } finally {
                armed = false
            }
        }
    }

    /**
     * The phone goes offline while [during]: the first request [matching] reaches the mint and acts (or, without
     * [reaches], never leaves), its answer is lost, and every request after it (the replay, the lookups that would
     * settle it) fails. The operation is left in flight, for the next refresh to settle.
     */
    private class Outage(val reaches: Boolean = true, val matching: (okhttp3.HttpUrl) -> Boolean = { it.isMutation }) {
        private var armed = false
        private var down = false

        /** How many requests reached the mint and lost their answer. */
        var hits = 0
        val client = Regtest.client { chain ->
            val request = chain.request()
            if (down) Regtest.dropped()
            if (!armed || !matching(request.url)) return@client chain.proceed(request)
            hits++
            down = true
            if (!reaches) Regtest.dropped()
            chain.proceed(request).close()
            Regtest.lost()
        }

        suspend fun <T> during(block: suspend () -> T): T {
            armed = true
            try {
                return block()
            } finally {
                armed = false
                down = false
            }
        }
    }

    @Test
    fun `a payment to a user on the same mint that loses the connection is pending, then settled as paid once`() = runBlocking<Unit> {
        val bob = Regtest.phone()
        val outage = Outage()
        val alice = Regtest.phone(client = outage.client)
        alice.fund(2000)
        val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address))
        assertTrue(preview.internal)

        assertEquals(PayResult.Pending(1_999_000), outage.during { alice.wallet.pay(preview, 1_999_000) })
        assertEquals(1, outage.hits)
        assertEquals(0, alice.balance(), "out of the balance while in flight")
        assertEquals(listOf(ActivityStatus.Pending), alice.wallet.activity.first().filter { it.kind == ActivityKind.Sent }.map { it.status })

        // The payee's key can't tell (another payer may sit there); the burned inputs can.
        alice.wallet.refresh()
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(listOf(ActivityStatus.Done), alice.wallet.activity.first().filter { it.kind == ActivityKind.Sent }.map { it.status })
        assertEquals(0, alice.balance())
        bob.wallet.refresh()
        assertEquals(1_999_000, bob.balance(), "paid once")
    }

    @Test
    fun `a renewal that never reached the mint before the connection went is pending, then the note is spendable again`() = runBlocking<Unit> {
        val outage = Outage(reaches = false)
        val alice = Regtest.phone(client = outage.client)
        alice.fund(2000)
        val note = alice.notes().single()

        assertEquals(RotateResult.Pending, outage.during { alice.wallet.rotateNote(note.id) })
        assertEquals(NoteState.Ambiguous, alice.store.note(note.id)?.state)
        assertEquals(0, alice.balance(), "not counted while it may be burned")

        alice.wallet.refresh()
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(listOf(note.id), alice.notes().map { it.id }, "the same note, outstanding again")
        assertEquals(1_999_000, alice.balance())
        assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(note.id))
    }

    @Test
    fun `a note made to send that loses the connection is not handed out, and its money comes back`() = runBlocking<Unit> {
        val outage = Outage()
        val alice = Regtest.phone(client = outage.client)
        alice.fund(5000)

        assertEquals(CreateNoteResult.Pending, outage.during { alice.wallet.createNote(1_000_000) })
        assertEquals(1, outage.hits)
        alice.wallet.refresh()
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(emptyList(), alice.wallet.activity.first().filter { it.kind == ActivityKind.SentNote }, "no link was ever shown")
        assertEquals(4_999_000 - 1_000, alice.balance(), "both halves of the split are ours, less its fee")
        assertEquals(2, alice.notes().size)
    }

    @Test
    fun `a move to another account that loses the connection is pending, then lands in the other account`() = runBlocking<Unit> {
        val outage = Outage()
        val phone = Regtest.phone(client = outage.client)
        val main = phone.store.currentAccountId()!!
        assertIs<OnboardResult.Ready>(phone.wallet.onboard(Regtest.name(), HOST, newAccountName = "Savings"))
        val savings = phone.store.currentAccountId()!!
        phone.wallet.switchAccount(main)
        phone.fund(2000)

        assertEquals(MoveResult.Pending, outage.during { phone.wallet.moveFunds(savings, null) })
        assertEquals(listOf(ActivityStatus.Pending), phone.wallet.activity.first().filter { it.kind == ActivityKind.MovedOut }.map { it.status })
        phone.wallet.refresh()
        assertEquals(0, phone.wallet.unresolvedOperations())
        assertEquals(mapOf(main to 0L, savings to 1_999_000L), phone.wallet.accounts.first().associate { it.account.id to it.balanceMsat })
        assertEquals(listOf(ActivityStatus.Done), phone.wallet.activity.first().filter { it.kind == ActivityKind.MovedOut }.map { it.status })
    }

    @Test
    fun `a move to another mint whose melt loses the connection is pending, then arrives on the other mint`() = runBlocking<Unit> {
        val outage = Outage(matching = { it.isMelt })
        val alice = Regtest.phone(client = outage.client)
        alice.wallet.payWaitMillis = 1_000
        alice.fund(3000)
        val ids = alice.notes().map { it.id }
        val quote = assertIs<MintMoveQuote.Ready>(alice.wallet.quoteMintMove(ids, HOST2))

        assertEquals(MintMoveResult.Pending, outage.during { alice.wallet.moveToMint(ids, HOST2) })
        assertEquals(1, outage.hits, "a melt is never sent twice")
        assertEquals(0, alice.balance(), "out of the balance while in flight")

        // The melt is settled at the first mint, and the note expected at our key is found on the second.
        alice.refreshUntil { alice.notes(HOST2).isNotEmpty() && alice.wallet.unresolvedOperations() == 0 }
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(emptyList(), alice.notes(HOST))
        assertEquals(listOf(quote.expectedMsat), alice.notes(HOST2).map { it.amountMsat })
        assertEquals(listOf(ActivityStatus.Done), alice.wallet.activity.first().filter { it.kind == ActivityKind.MovedMint }.map { it.status })
    }

    @Test
    fun `a claim whose answer is lost is settled by reconciliation`() = runBlocking<Unit> {
        val breaker = Breaker()
        val alice = Regtest.phone(client = breaker.client)
        val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(Regtest.note(2000)))
        val result = breaker.during { alice.wallet.claim(preview) }
        assertTrue(breaker.hits > 0)
        assertTrue(result is ClaimResult.Claimed || result is ClaimResult.Pending, "$result")
        alice.wallet.refresh()
        assertEquals(1_999_000, alice.balance())
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(listOf(ActivityStatus.Done), alice.wallet.activity.first().filter { it.kind == ActivityKind.Claimed }.map { it.status })
        assertIs<ClaimPreview.AlreadySpent>(alice.wallet.previewClaim(preview.link.encode()), "the link is ours now")
    }

    @Test
    fun `a claim that never reaches the mint changes nothing, and the note can be claimed again`() = runBlocking<Unit> {
        val breaker = Breaker(lose = false)
        val alice = Regtest.phone(client = breaker.client)
        val link = Regtest.note(2000)
        val result = breaker.during { alice.wallet.claim(assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))) }
        assertIs<ClaimResult.Failed>(result)
        assertTrue(breaker.hits > 0)
        alice.wallet.refresh()
        assertEquals(0, alice.balance())
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(emptyList(), alice.wallet.activity.first().filter { it.kind == ActivityKind.Claimed })

        assertIs<ClaimResult.Claimed>(alice.wallet.claim(assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))))
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a rotation whose answer is lost is settled by reconciliation`() = runBlocking<Unit> {
        val breaker = Breaker()
        val alice = Regtest.phone(client = breaker.client)
        alice.fund(2000)
        val old = alice.notes().single()
        val result = breaker.during { alice.wallet.rotateNote(old.id) }
        assertTrue(breaker.hits > 0)
        assertTrue(result is RotateResult.Rotated || result is RotateResult.Pending, "$result")
        alice.wallet.refresh()
        assertEquals(0, alice.wallet.unresolvedOperations())
        val renewed = alice.notes().single()
        assertTrue(renewed.id != old.id, "the note lives at the new key")
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a payment whose answer is lost is settled by reconciliation, paid once`() = runBlocking<Unit> {
        val breaker = Breaker(only = { it.isMelt })
        val alice = Regtest.phone(client = breaker.client)
        alice.fund(2000)
        val invoice = Regtest.invoice(1999)
        val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoice))
        val result = breaker.during { alice.wallet.pay(preview, 1_999_000) }
        assertEquals(1, breaker.hits, "a melt is never sent twice")
        assertTrue(result is PayResult.Paid || result is PayResult.Pending, "$result")
        alice.refreshUntil { alice.wallet.activity.first().single { it.kind == ActivityKind.Sent }.status != ActivityStatus.Pending }
        assertEquals(ActivityStatus.Done, alice.wallet.activity.first().single { it.kind == ActivityKind.Sent }.status)
        assertTrue(Regtest.settled(invoice))
        assertEquals(0, alice.balance())
        assertEquals(0, alice.wallet.unresolvedOperations())
    }

    @Test
    fun `keys the same phrase used on another phone are found when the mint says already in use`() = runBlocking<Unit> {
        val phone1 = Regtest.phone()
        var inUse = 0
        val watching = Regtest.client { chain ->
            chain.proceed(chain.request()).also { if ("in use" in it.peekBody(4096).string()) inUse++ }
        }
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), watching, policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(phone2.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))

        // Both phones now hand out the same next wallet key. Phone 1 claims first; phone 2's claim is refused for
        // that key, finds the keys in use, and lands on a free one.
        assertIs<ClaimResult.Claimed>(phone1.wallet.claim(assertIs<ClaimPreview.Ready>(phone1.wallet.previewClaim(Regtest.note(2000)))))
        assertIs<ClaimResult.Claimed>(phone2.claim(assertIs<ClaimPreview.Ready>(phone2.previewClaim(Regtest.note(3000)))))
        assertEquals(1, inUse, "the mint refused phone 2's first key once")
        phone1.wallet.refresh()
        phone2.refresh()
        assertEquals(1_999_000, phone1.store.allNotes().filter { it.state == NoteState.Outstanding }.sumOf { it.amountMsat })
        assertTrue(phone2.balanceMsat.first() >= 2_999_000, "phone 2 has its claim, and may also have found phone 1's")
    }

    @Test
    fun `restore finds the notes of every purpose and goes on past their keys`() = runBlocking<Unit> {
        // Phone 1 uses all three key purposes: an address payment (2), the change of a payment (1), a claim (0).
        val phone1 = Regtest.phone()
        phone1.fund(5000)
        assertIs<PayResult.Paid>(phone1.wallet.pay(assertIs<PayPreview.Ready>(phone1.wallet.previewPayment(Regtest.invoice(1000))), 1_000_000))
        assertIs<ClaimResult.Claimed>(phone1.wallet.claim(assertIs<ClaimPreview.Ready>(phone1.wallet.previewClaim(Regtest.note(2000)))))
        val held = phone1.notes()
        assertEquals(setOf(0, 1), held.map { it.purpose }.toSet())

        val store = InMemoryWalletStore()
        val phone2 = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val restored = assertIs<RestoreResult.Restored>(phone2.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        assertEquals(held.size, restored.notes)
        assertEquals(phone1.balance(), phone2.balanceMsat.first())
        assertEquals(held.map { it.id }.toSet(), store.allNotes().filter { it.state == NoteState.Outstanding }.map { it.id }.toSet())

        // New notes on phone 2 take keys past the restored ones: no clash with what phone 1 used.
        val before = phone2.balanceMsat.first()
        assertIs<ClaimResult.Claimed>(phone2.claim(assertIs<ClaimPreview.Ready>(phone2.previewClaim(Regtest.note(1000)))))
        assertIs<PayResult.Paid>(phone2.pay(assertIs<PayPreview.Ready>(phone2.previewPayment(Regtest.invoice(500))), 500_000))
        assertTrue(phone2.balanceMsat.first() >= before + 999_000 - 500_000 - 1_000)
    }

    @Test
    fun `an operation the app died during, before sending it, is rejected on the next refresh`() = runBlocking<Unit> {
        // The journal entry is written, then the app is gone before the request leaves.
        var dying = false
        val alice = Regtest.phone(client = Regtest.client { chain ->
            if (dying && chain.request().url.isMutation) throw IllegalStateException("the app was killed")
            chain.proceed(chain.request())
        })
        val link = Regtest.note(2000)
        val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))
        dying = true
        assertTrue(runCatching { alice.wallet.claim(preview) }.isFailure)
        dying = false
        assertEquals(1, alice.wallet.unresolvedOperations(), "prepared, never sent")

        alice.wallet.refresh()
        assertEquals(0, alice.wallet.unresolvedOperations())
        assertEquals(0, alice.balance())
        assertIs<ClaimResult.Claimed>(alice.wallet.claim(assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))))
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a restore that can't reach the mint leaves nothing behind`() = runBlocking<Unit> {
        val phone1 = Regtest.phone()
        phone1.fund(1000)
        val store = InMemoryWalletStore()
        val offline = Wallet(store, InMemorySeedStore(), Regtest.client { Regtest.dropped() }, policies = NotePolicies.InMemory(ManualStrategy.ID))
        val result = offline.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main")
        assertTrue(result is RestoreResult.NetworkError || result is RestoreResult.MintInvalid, "$result")
        assertEquals(emptyList(), store.accounts.first())
        assertEquals(emptyList(), store.allNotes())
    }

    @Test
    fun `a lost answer is recovered by sending the same request again, which the mint answers as before`() = runBlocking<Unit> {
        // Only the first answer is lost; the client's one replay of the byte-identical request goes through.
        val urls = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val alice = Regtest.phone(client = Regtest.client { chain ->
            val request = chain.request()
            if (!request.url.isMutation) return@client chain.proceed(request)
            urls += request.url.toString()
            val response = chain.proceed(request)
            if (urls.size == 1) {
                response.close()
                Regtest.lost()
            }
            response.also { replies += it.peekBody(4096).string() }
        })
        alice.fund(2000)
        val old = alice.notes().single()
        assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(old.id))
        assertEquals(2, urls.size)
        assertEquals(urls[0], urls[1], "the replay is the same request")
        assertTrue("\"OK\"" in replies.single(), "the mint answers the replay with the original success: ${replies.single()}")
        assertEquals(1_999_000, alice.balance())
        assertEquals(0, alice.wallet.unresolvedOperations())
    }
}
