@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Taking back a note handed out as a link says what really happened: a mint that answered but wouldn't is never told as one that couldn't
 * be reached ("Couldn't connect"), and its own words come with its refusal. A take back the user asked for while the mint didn't know the
 * note, which the mint refuses once it knows it again, keeps the wish. Against a fake mint whose answers each test sets.
 */
class TakeBackTest {
    private val clock = 1_800_000_000_000L

    /** Drops every request while [offline]. */
    private class Wire : Interceptor {
        var offline = false

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) throw IOException("offline")
            return chain.proceed(chain.request())
        }
    }

    private class Phone(val wallet: Wallet, val accountId: String, val wire: Wire)

    private suspend fun phone(mint: LookupMint): Phone {
        val wire = Wire()
        val client = LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(wire).build())
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client, now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        assertNotNull(wallet.mintOf(accountId, mint.host)?.mintPubkey, "connected, its key pinned")
        return Phone(wallet, accountId, wire)
    }

    /** A ₿5 note of [phone]'s at wallet key 900, handed out as a link while its mint had it; its key. */
    private suspend fun handedOut(phone: Phone, mint: LookupMint): String {
        val q = phone.wallet.keys(phone.accountId, mint.host).pubkey(KeySet.Wallet, 900).toHex()
        phone.wallet.store.upsertNotes(
            listOf(
                Note(q, phone.accountId, mint.host, NoteKind.KeyPath, KeySet.Wallet.purpose, 900, 5_000, null, NoteState.Outstanding, clock)
            )
        )
        mint.lookups[q] = note(5_000)
        assertIs<ShareNoteResult.Shared>(phone.wallet.shareNote(q))
        return q
    }

    private suspend fun Phone.sentRow(id: String) =
        wallet.activity.first().single { it.kind == ActivityKind.SentNote && it.sentNoteId == id }

    /** Still handed out, as before the take back: its link shown, out of the balance. */
    private suspend fun Phone.stillOut(id: String, message: String) {
        val shown = assertNotNull(wallet.sharedNote(id), message)
        assertEquals(SharedNoteState.Waiting, shown.state, message)
        assertNotNull(shown.link, message)
        assertEquals(NoteState.Shared, wallet.store.note(id)?.state, message)
        assertEquals(ActivityStatus.Pending, sentRow(id).status, message)
        assertEquals(0, wallet.balanceMsat.first(), message)
    }

    @Test
    fun `a take back the mint refuses in its own words says so, with its words, never couldn't connect`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val q = handedOut(phone, mint)
                // Its lookup refused: a spent note in its words proves nothing, like any other refusal.
                for (reason in listOf("Closed for maintenance", "Note already spent.", "Rate limited")) {
                    mint.lookups[q] = error(reason)
                    assertEquals(ReclaimResult.Failed(PayProblem.Refused, reason), phone.wallet.reclaimNote(q), reason)
                    phone.stillOut(q, reason)
                }
                // Its lookup answered, then the take back itself was refused.
                mint.lookups[q] = note(5_000)
                mint.callback = error("Try again in a minute")
                assertEquals(ReclaimResult.Failed(PayProblem.Refused, "Try again in a minute"), phone.wallet.reclaimNote(q))
                phone.stillOut(q, "refused")
                // Being paid out: a refusal the wallet knows, in the app's words.
                mint.lookups[q] = error("pending")
                assertEquals(ReclaimResult.Failed(PayProblem.Refused), phone.wallet.reclaimNote(q))
            }
        }

    @Test
    fun `a take back on a mint whose signing key changed says so, and asks nothing`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val q = handedOut(phone, mint)
                val walletId = phone.wallet.walletOf(phone.accountId)
                val pinned = assertNotNull(phone.wallet.store.mint(walletId, mint.host))
                phone.wallet.store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
                // As a refresh finds it: the mint presents another key than the pinned one.
                phone.wallet.sawKey(walletId, mint.host, changed = true)
                val asked = mint.lookupsAsked.get()
                assertEquals(ReclaimResult.Failed(PayProblem.KeyChanged), phone.wallet.reclaimNote(q))
                assertEquals(asked, mint.lookupsAsked.get(), "no key was looked up")
                assertEquals(0, mint.callbacks, "nothing was sent")
                assertEquals(NoteState.Shared, phone.wallet.store.note(q)?.state)
            }
        }

    @Test
    fun `only a mint that couldn't be reached is told as one`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val q = handedOut(phone, mint)
                phone.wire.offline = true
                assertEquals(ReclaimResult.Failed(PayProblem.Unreachable), phone.wallet.reclaimNote(q))
                phone.wire.offline = false
                phone.stillOut(q, "offline")
            }
        }

    @Test
    fun `a take back asked for while the mint didn't know the note, refused once it knows it, keeps the wish until it goes through`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val wallet = phone.wallet
                val q = handedOut(phone, mint)
                mint.lookups.remove(q)
                assertEquals(ReclaimResult.WhenKnown, wallet.reclaimNote(q))
                assertEquals(HandedOut.TakeBack, wallet.store.note(q)?.handedOut)

                // Known again, but the take back is refused: its own outcome, the wish kept.
                mint.lookups[q] = note(5_000)
                mint.callback = error("Busy, try later")
                assertEquals(NoteCheck.TakeBackRefused("Busy, try later"), wallet.recheckNote(q))
                val kept = assertNotNull(wallet.store.note(q))
                assertEquals(NoteState.Shared, kept.state)
                assertEquals(HandedOut.TakeBack, kept.handedOut)
                val shown = assertNotNull(wallet.sharedNote(q))
                assertEquals(SharedNoteState.Waiting, shown.state)
                assertTrue(shown.takeBackAsked)
                assertNull(shown.link, "the user asked to take it back: no longer shown")
                assertEquals(ActivityStatus.Pending, phone.sentRow(q).status)
                assertEquals(0, wallet.balanceMsat.first())

                // Still refused on refresh: the wish stays.
                wallet.refresh()
                assertEquals(HandedOut.TakeBack, wallet.store.note(q)?.handedOut)
                assertEquals(ActivityStatus.Pending, phone.sentRow(q).status)

                // The next refresh it goes through.
                mint.callback = """{"status":"OK"}"""
                wallet.refresh()
                assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(q)?.state)
                assertEquals(ActivityStatus.Cancelled, phone.sentRow(q).status)
                assertEquals(5_000, wallet.balanceMsat.first())
            }
        }

    @Test
    fun `checking a note set aside counts a take back apart, gone through or refused`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val wallet = phone.wallet
                val q = handedOut(phone, mint)
                mint.lookups.remove(q)
                assertEquals(ReclaimResult.WhenKnown, wallet.reclaimNote(q))

                mint.lookups[q] = note(5_000)
                mint.callback = error("Busy, try later")
                assertEquals(
                    NotesCheck(checked = 0, gone = 0, unreachable = emptyList(), takeBackRefused = 1),
                    wallet.recheckUnknownNotes(),
                )

                // Unknown again, and known again: this time it goes through.
                mint.lookups.remove(q)
                assertEquals(ReclaimResult.WhenKnown, wallet.reclaimNote(q))
                mint.lookups[q] = note(5_000)
                mint.callback = """{"status":"OK"}"""
                assertEquals(NotesCheck(checked = 0, gone = 0, unreachable = emptyList(), takenBack = 1), wallet.recheckUnknownNotes())
                assertEquals(5_000, wallet.balanceMsat.first())
            }
        }

    @Test
    fun `a single note's check tells a take back that went through`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val q = handedOut(phone, mint)
                mint.lookups.remove(q)
                assertEquals(ReclaimResult.WhenKnown, phone.wallet.reclaimNote(q))
                mint.lookups[q] = note(5_000)
                mint.callback = """{"status":"OK"}"""
                assertEquals(NoteCheck.TakenBack(5_000), phone.wallet.recheckNote(q))
                assertEquals(5_000, phone.wallet.balanceMsat.first())
            }
        }

    @Test
    fun `checking every note tells handed-out notes apart from the balance's`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                val wallet = phone.wallet
                val q = handedOut(phone, mint)

                // Its mint no longer knows it: set aside, never counted as out of the balance.
                mint.lookups.remove(q)
                assertEquals(NotesCheck(checked = 0, gone = 0, unreachable = emptyList(), handedOutUnknown = 1), wallet.recheckNotes())
                assertEquals(NoteState.Unknown, wallet.store.note(q)?.state)

                // The user asks to take it back; the mint knows it again: taken back, never counted as still there.
                assertEquals(ReclaimResult.WhenKnown, wallet.reclaimNote(q))
                mint.lookups[q] = note(5_000)
                mint.callback = """{"status":"OK"}"""
                val check = wallet.recheckNotes()
                assertEquals(1, check.takenBack, "$check")
                assertEquals(0, check.unknown, "$check")
                assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(q)?.state)
            }
        }
}
