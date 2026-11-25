@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.Requests

/**
 * A mint answers 0 for a note being paid out over Lightning, as for a spent one: on the real mint, with a hold invoice keeping a payment in
 * flight. A note received from someone else and answered 0 is spent for good, whatever becomes of that payment. The wallet's own note in a
 * payment of its own, answered 0 too, stays that payment, and is in the balance again if it fails.
 */
class RegtestZeroAnswerTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private suspend fun Regtest.Phone.ready(input: String) = assertIs<PayPreview.Ready>(wallet.previewPayment(input))

    private suspend fun Regtest.Phone.sent() = wallet.activity.first().filter { it.kind == ActivityKind.Sent }

    /** Alice's 1000 sat note as a link, and Alice. */
    private suspend fun aNote(): Pair<Regtest.Phone, SharedNote> {
        val alice = Regtest.phone()
        alice.fund(3000)
        return alice to assertIs<CreateNoteResult.Created>(alice.wallet.createNote(1_000_000)).note
    }

    /** Whoever has [link] pays [hold] with it, outside any wallet: the mint holds the note as being paid out. */
    private suspend fun payOut(link: String, hold: Regtest.HoldInvoice) {
        val note = assertNotNull(NoteLink.parse(link))
        val client = LnurlcashClient()
        val callback = client.lookup(note.withdrawUrl, note.spend.q).callback
        val outcome = client.mutate(MutationType.Melt, Requests.melt(callback, note.spend.k1, hold.invoice))
        assertIs<MutationOutcome.Confirmed>(outcome, "the mint took the payment")
    }

    /** Cancels [hold], and waits until the mint knows [link]'s note again, worth its value: the payment failed. */
    private suspend fun fail(hold: Regtest.HoldInvoice, link: String) {
        hold.cancel()
        val note = assertNotNull(NoteLink.parse(link))
        val client = LnurlcashClient()
        val deadline = System.currentTimeMillis() + 30_000
        while (runSuspendCatching { client.lookup(note.withdrawUrl, note.spend.q) }.isFailure) {
            check(System.currentTimeMillis() < deadline) { "the note never came back to its maker" }
            delay(500)
        }
    }

    @Test
    fun `a note paid out by someone else while Bob looks at it is spent for good for Bob, even once that payment fails`() =
        runBlocking<Unit> {
            val (alice, sent) = aNote()
            val link = assertNotNull(sent.link)
            val bob = Regtest.phone()
            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))

            // Someone else with the same link pays an invoice with it; the payment is held in flight.
            val hold = Regtest.holdInvoice(1000)
            payOut(link, hold)
            assertEquals(ClaimPreview.AlreadySpent, bob.wallet.previewClaim(link), "answered 0, as a spent note")

            val failed = assertIs<ClaimResult.Failed>(bob.wallet.claim(preview))
            assertEquals(ClaimFailure.Spent, failed.outcome)
            val gone = bob.wallet.receivedNotes.first().single()
            assertEquals(ReceivedState.Gone, gone.state)
            assertNull(gone.k1, "never tried again: its spend is dropped")
            assertEquals(0, bob.balance())
            assertEquals(0, bob.wallet.incomingMsat.first())

            // That payment fails, and the note is worth 1000 sats at its mint again: not Bob's to claim.
            fail(hold, link)
            bob.wallet.refresh()
            assertEquals(ReceivedState.Gone, bob.wallet.receivedNotes.first().single().state)
            assertEquals(0, bob.balance())

            // Alice, who made it, still can take it back.
            val before = alice.balance()
            assertEquals(ReclaimResult.Reclaimed(1_000_000), alice.wallet.reclaimNote(sent.id))
            assertEquals(before + 1_000_000, alice.balance())

            bob.wallet.removeReceivedNote(gone.id)
            assertEquals(emptyList(), bob.wallet.receivedNotes.first())
        }

    @Test
    fun `a note Bob keeps offline, paid out by someone else meanwhile, is spent for good when Bob's wallet tries it`() =
        runBlocking<Unit> {
            val (_, sent) = aNote()
            val link = assertNotNull(sent.link)
            val connection = Offline()
            val bob = Regtest.phone(client = Regtest.client(connection))
            connection.offline = true
            bob.wallet.keepClaim(assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link)))
            assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state)

            val hold = Regtest.holdInvoice(1000)
            payOut(link, hold)

            connection.offline = false
            bob.wallet.refresh()
            val gone = bob.wallet.receivedNotes.first().single()
            assertEquals(ReceivedState.Gone, gone.state)
            assertNull(gone.k1)
            assertEquals(0, bob.balance())

            fail(hold, link)
            bob.wallet.refresh()
            assertEquals(ReceivedState.Gone, bob.wallet.receivedNotes.first().single().state, "not asked about again")
            assertEquals(0, bob.balance())
        }

    @Test
    fun `a gone note an older version kept its spend for loses it on a refresh, and the note is left to its maker`() =
        runBlocking<Unit> {
            val (alice, sent) = aNote()
            val connection = Offline()
            val bob = Regtest.phone(client = Regtest.client(connection))
            // As an older version left it: archived as spent, its spend kept to claim it should it come back.
            connection.offline = true
            val kept = bob.wallet.keepClaim(assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(assertNotNull(sent.link))))
            connection.offline = false
            val stored = assertNotNull(bob.store.receivedNote(kept.note.id))
            bob.store.putReceivedNote(stored.copy(state = ReceivedState.Archived, unclaimedReason = UnclaimedReason.Spent))

            bob.wallet.refresh()
            val gone = assertNotNull(bob.store.receivedNote(kept.note.id))
            assertEquals(ReceivedState.Gone, gone.state)
            assertNull(gone.k1)
            assertEquals(0, bob.balance(), "never claimed, though the note is worth 1000 sats")
            assertEquals(ReclaimResult.Reclaimed(1_000_000), alice.wallet.reclaimNote(sent.id))
        }

    @Test
    fun `Bob's own payment, answered 0 while in flight, is never taken for spent by someone else, and if it fails the money stays, on a fresh key`() =
        runBlocking<Unit> {
            val bob = Regtest.phone()
            bob.wallet.payWaitMillis = 3_000
            bob.fund(2000)
            val hold = Regtest.holdInvoice(1999)
            assertEquals(PayResult.Pending(1_999_000), bob.wallet.pay(bob.ready(hold.invoice), 1_999_000))
            val paying = bob.store.allNotes().single()
            assertEquals(NoteState.Paying, paying.state)

            // Refreshes and a scan while the mint answers 0 for it: still Bob's payment, neither spent nor back.
            bob.wallet.refresh()
            bob.wallet.scan()
            assertEquals(NoteState.Paying, bob.store.note(paying.id)?.state)
            assertEquals(ActivityStatus.Pending, bob.sent().single().status)
            assertEquals(0, bob.balance())

            hold.cancel()
            bob.refreshUntil { bob.sent().single().status != ActivityStatus.Pending }
            assertEquals(ActivityStatus.Failed, bob.sent().single().status)
            assertEquals(1_999_000, bob.balance(), "nothing was paid: the money stayed in the wallet")
            // Its spend went out with the payment: renewed on a fresh key, for free.
            assertEquals(NoteState.Spent, bob.store.note(paying.id)?.state)
            val renewed = bob.notes().single()
            assertEquals(1_999_000, renewed.amountMsat)
            assert(renewed.id != paying.id) { "a fresh key" }
            val rotated = bob.wallet.activity.first().single { it.kind == ActivityKind.Rotated }
            assertEquals(ActivityStatus.Done, rotated.status)
            assert(rotated.automatic)

            bob.wallet.refresh()
            assertEquals(listOf(renewed.id), bob.notes().map { it.id }, "renewed once")
        }

    /** A connection that drops every request while [offline]; the mint never sees them. */
    private class Offline : okhttp3.Interceptor {
        var offline = false

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            if (offline) Regtest.dropped()
            return chain.proceed(chain.request())
        }
    }
}
