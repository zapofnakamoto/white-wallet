@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.RevealsNoteSecret

/**
 * A note handed out as a link while offline that its mint doesn't know, against the real reference mint (dev/regtest, LNURLCASH_REGTEST=1):
 * once the wallet can ask, it is set aside with the other unknown notes; a take back waits until the mint knows it; known again, it is
 * taken back if the user asked, and otherwise waits to be claimed again, never back in the balance.
 */
class RegtestHandedOutUnknownTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** Drops every request while [offline]. */
    private class Wire : Interceptor {
        var offline = false

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            return chain.proceed(chain.request())
        }
    }

    /** What a note of [SATS] minted on the mint is worth: its fee off. */
    private val worth = SATS * 1000 - 1000

    /** A note at a wallet key the mint has never seen, recorded as outstanding (as after the mint lost its records). */
    private suspend fun Regtest.Phone.unknownNote(index: Long = 900): Note {
        val accountId = store.currentAccountId()!!
        val q = wallet.keys(accountId, Regtest.HOST).pubkey(KeySet.Wallet, index).toHex()
        return Note(q, accountId, Regtest.HOST, NoteKind.KeyPath, KeySet.Wallet.purpose, index, worth, null, NoteState.Outstanding, 0)
            .also { store.upsertNotes(listOf(it)) }
    }

    /** Hands [note] out while offline: unconfirmed, as its mint couldn't be asked. */
    private suspend fun Regtest.Phone.shareOffline(wire: Wire, note: Note) {
        wire.offline = true
        assertIs<ShareNoteResult.Shared>(wallet.shareNote(note.id))
        assertEquals(NoteState.SharedUnconfirmed, store.note(note.id)?.state)
        wire.offline = false
    }

    private suspend fun Regtest.Phone.sentRow(id: String) =
        wallet.activity.first().single { it.kind == ActivityKind.SentNote && it.sentNoteId == id }

    @Test
    fun `a note handed out offline that its mint doesn't know is set aside once online, and taken back as soon as the mint knows it`() =
        runBlocking<Unit> {
            val wire = Wire()
            // Unknown notes asked about again on every refresh a second apart.
            val alice =
                Regtest.phone(
                    client = Regtest.client(wire),
                    settings = WalletSettings(unknownNoteCheckIntervalInGracePeriodMillis = 1000),
                )
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)

            alice.wallet.refresh()
            val setAside = assertNotNull(alice.store.note(note.id))
            assertEquals(NoteState.Unknown, setAside.state)
            assertEquals(HandedOut.Waiting, setAside.handedOut)
            val waiting = assertNotNull(alice.wallet.sharedNote(note.id))
            assertEquals(SharedNoteState.Waiting, waiting.state, "not spent: nobody claimed it")
            assertTrue(waiting.unknownToMint)
            assertFalse(waiting.confirmed)
            assertFalse(waiting.takeBackAsked)
            assertNotNull(waiting.link, "still the recipient's, should the mint know it again")
            assertNotNull(waiting.checkedAtMillis)
            assertEquals(ActivityStatus.Pending, alice.sentRow(note.id).status)
            // Home warns of the balance only: this note was out of it already, its page and the notes view tell of it.
            assertEquals(0 to 0L, alice.wallet.accountMints.first().single { it.isHome }.let { it.unknownCount to it.unknownMsat })
            assertEquals(0, alice.balance())

            // Taking it back can't be done now: the mint answered, so not "couldn't connect"; the wish is kept.
            assertEquals(ReclaimResult.WhenKnown, alice.wallet.reclaimNote(note.id))
            val asked = assertNotNull(alice.wallet.sharedNote(note.id))
            assertEquals(SharedNoteState.Waiting, asked.state)
            assertTrue(asked.takeBackAsked)
            assertNull(asked.link, "no longer shown once the user asked to take it back")
            assertEquals(1, alice.wallet.archiveUnknownNotes(setOf(note.id)), "archived like any other unknown note")
            assertEquals(ActivityStatus.Pending, alice.sentRow(note.id).status)

            // The mint knows the note again: the next refresh asks, and takes it back.
            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            delay(1_100)
            alice.wallet.refresh()
            assertEquals(SharedNoteState.Reclaimed, alice.wallet.sharedNote(note.id)?.state)
            assertEquals(ActivityStatus.Cancelled, alice.sentRow(note.id).status, "taken back")
            val old = assertNotNull(alice.store.note(note.id))
            assertEquals(NoteState.Spent, old.state, "rotated to a fresh key")
            assertNull(old.handedOut)
            assertNull(old.archivedAtMillis)
            assertTrue(alice.balance() in (worth - 1000)..worth, "back in the balance (a rotate may cost the mint's fee)")
            assertEquals(0, alice.wallet.accountMints.first().single { it.isHome }.unknownCount)
        }

    @Test
    fun `a note handed out offline that its mint knows again waits to be claimed again, never in the balance, and the recipient claims it`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)

            // The shared note's screen asks.
            val polled = assertNotNull(alice.wallet.checkSharedNote(note.id))
            assertEquals(SharedNoteState.Waiting, polled.state)
            assertTrue(polled.unknownToMint)
            assertEquals(NoteState.Unknown, alice.store.note(note.id)?.state)
            val link = assertNotNull(polled.link)

            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            assertEquals(
                NotesCheck(checked = 0, gone = 0, unreachable = emptyList(), unknown = 0, handedOutAgain = 1),
                alice.wallet.recheckUnknownNotes(),
                "not counted as back in the balance",
            )
            val again = assertNotNull(alice.store.note(note.id))
            assertEquals(NoteState.Shared, again.state)
            assertNull(again.handedOut)
            assertEquals(0, alice.balance(), "the link is out there: never back in the balance")
            val shown = assertNotNull(alice.wallet.sharedNote(note.id))
            assertEquals(SharedNoteState.Waiting, shown.state)
            assertFalse(shown.unknownToMint)
            assertTrue(shown.confirmed)
            // The same spend; its mint's certificate comes with it now.
            assertEquals(link.substringBefore("&"), shown.link?.substringBefore("&"))
            assertEquals(ActivityStatus.Pending, alice.sentRow(note.id).status)

            // Note maintenance and refreshes leave it alone; the recipient claims it.
            alice.wallet.refresh()
            assertEquals(NoteState.Shared, alice.store.note(note.id)?.state)
            val bob = Regtest.phone()
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))))
            assertEquals(SharedNoteState.Spent, alice.wallet.checkSharedNote(note.id)?.state)
            assertEquals(ActivityStatus.Done, alice.sentRow(note.id).status)
            assertEquals(ReclaimResult.AlreadySpent, alice.wallet.reclaimNote(note.id))
            assertEquals(0, alice.balance())
        }

    @Test
    fun `taking back a note handed out offline that its mint doesn't know waits for the mint, and Check again takes it back`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)

            // Online, before any refresh: the take back finds out the mint doesn't know it.
            assertEquals(ReclaimResult.WhenKnown, alice.wallet.reclaimNote(note.id))
            val setAside = assertNotNull(alice.store.note(note.id))
            assertEquals(NoteState.Unknown, setAside.state)
            assertEquals(HandedOut.TakeBack, setAside.handedOut)
            assertEquals(ReclaimResult.WhenKnown, alice.wallet.reclaimNote(note.id), "and again: asked again, still unknown")
            assertEquals(NoteCheck.Unknown, alice.wallet.recheckNote(note.id))

            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            val back = assertIs<NoteCheck.TakenBack>(alice.wallet.recheckNote(note.id))
            assertEquals(back.amountMsat, alice.balance(), "taken back into the balance")
            assertEquals(SharedNoteState.Reclaimed, alice.wallet.sharedNote(note.id)?.state)
            assertEquals(ActivityStatus.Cancelled, alice.sentRow(note.id).status)
        }

    @Test
    fun `the shared note's screen takes back a note the user asked to take back once its mint knows it`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)
            alice.wallet.refresh()
            assertEquals(ReclaimResult.WhenKnown, alice.wallet.reclaimNote(note.id))

            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            assertEquals(SharedNoteState.Reclaimed, alice.wallet.checkSharedNote(note.id)?.state)
            assertEquals(ActivityStatus.Cancelled, alice.sentRow(note.id).status)
            assertTrue(alice.balance() > 0)
        }

    @Test
    fun `the wallet's own link to a note set aside is taken back, not received, once its mint knows it`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)
            alice.wallet.refresh()
            val link = assertNotNull(alice.wallet.sharedNote(note.id)?.link)

            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            val own = assertIs<ClaimPreview.OwnShared>(alice.wallet.previewClaim(link))
            assertTrue(own.reachable)
            assertIs<ReclaimResult.Reclaimed>(alice.wallet.reclaimNote(own.id))
            assertEquals(SharedNoteState.Reclaimed, alice.wallet.sharedNote(note.id)?.state)
            assertEquals(emptyList(), alice.wallet.receivedNotes.first(), "never kept as a received note")
            assertTrue(alice.balance() > 0)
        }

    @Test
    fun `the recipient claims a note while the wallet has it set aside as unknown, and it is spent, done, never in the balance`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)
            alice.wallet.refresh()
            assertEquals(NoteState.Unknown, alice.store.note(note.id)?.state)
            val link = assertNotNull(alice.wallet.sharedNote(note.id)?.link)

            // The mint knows it again, and the recipient claims it before the wallet asks.
            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            val bob = Regtest.phone()
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))))
            assertEquals(NoteState.Unknown, alice.store.note(note.id)?.state, "still set aside")

            // Asked again: burned (the lookup's `already in use`).
            assertEquals(NotesCheck(checked = 0, gone = 0, unreachable = emptyList()), alice.wallet.recheckUnknownNotes())
            val spent = assertNotNull(alice.store.note(note.id))
            assertEquals(NoteState.Spent, spent.state)
            assertNull(spent.handedOut)
            assertEquals(ActivityStatus.Done, alice.sentRow(note.id).status)
            assertEquals(SharedNoteState.Spent, alice.wallet.sharedNote(note.id)?.state)
            assertEquals(0, alice.balance(), "never in the balance")
            assertEquals(ReclaimResult.AlreadySpent, alice.wallet.reclaimNote(note.id))
        }

    @Test
    fun `a take back asked for in one account is carried out by a refresh while another account of the wallet is active`() =
        runBlocking<Unit> {
            val wire = Wire()
            // Unknown notes asked about again on every refresh a second apart.
            val alice =
                Regtest.phone(
                    client = Regtest.client(wire),
                    settings = WalletSettings(unknownNoteCheckIntervalInGracePeriodMillis = 1000),
                )
            val first = alice.store.currentAccountId()!!
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)
            alice.wallet.refresh()
            assertEquals(ReclaimResult.WhenKnown, alice.wallet.reclaimNote(note.id))
            assertEquals(HandedOut.TakeBack, alice.store.note(note.id)?.handedOut)

            // Another account of the same wallet (recovery phrase) is active now.
            val second = assertIs<CreateAccountResult.Created>(alice.wallet.createAccount("Savings")).accountId
            assertEquals(second, alice.store.currentAccountId())
            val secondNotes = alice.store.notes(second).first()
            val secondActivity = alice.store.activities(second).first()

            Regtest.mintTo(Regtest.HOST, ByteVector32(note.id), SATS)
            delay(1_100)
            alice.wallet.refresh()

            // Taken back into the first account's balance, on its own records.
            val old = assertNotNull(alice.store.note(note.id))
            assertEquals(first, old.accountId)
            assertEquals(NoteState.Spent, old.state, "rotated to a fresh key")
            assertNull(old.handedOut)
            val row = alice.store.activities(first).first().single { it.kind == ActivityKind.SentNote && it.sentNoteId == note.id }
            assertEquals(ActivityStatus.Cancelled, row.status, "taken back")
            val back = alice.store.notes(first).first().filter { it.state == NoteState.Outstanding }
            assertEquals(1, back.size)
            assertTrue(back.single().amountMsat in (worth - 1000)..worth, "back in the first account's balance")
            assertEquals(KeySet.Wallet.purpose, back.single().purpose)

            // The active account's records are untouched.
            assertEquals(second, alice.store.currentAccountId())
            assertEquals(secondNotes, alice.store.notes(second).first())
            assertEquals(secondActivity, alice.store.activities(second).first())
            assertEquals(0, alice.balance())
        }

    @Test
    fun `a handed-out note removed from the archive for good leaves History as sent`() =
        runBlocking<Unit> {
            val wire = Wire()
            val alice = Regtest.phone(client = Regtest.client(wire))
            val note = alice.unknownNote()
            alice.shareOffline(wire, note)
            alice.wallet.refresh()
            assertEquals(1, alice.wallet.archiveUnknownNotes(setOf(note.id)))
            assertTrue(alice.wallet.removeArchivedNote(note.id))
            assertNull(alice.store.note(note.id))
            assertEquals(ActivityStatus.Unverified, alice.sentRow(note.id).status, "gone for good: nobody knows whether it was claimed")
            assertEquals(SharedNoteState.Unverified, alice.wallet.sharedNote(note.id)?.state)
            assertEquals(0, alice.balance(), "never back in the balance")
        }

    private companion object {
        const val SATS = 1000L
    }
}
