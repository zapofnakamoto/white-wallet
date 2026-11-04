@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A note handed out as a link, offline or online: checked by its key only, confirmed or found spent once the wallet can
 * ask, taken back only when the user says so, and never touched by note maintenance.
 */
class RegtestSharedNotesTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** Drops every request while [offline]; writes down every request that carries a spend. */
    private class Wire : Interceptor {
        var offline = false
        val spends = mutableListOf<HttpUrl>()

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            chain.request().url.takeIf { it.queryParameter("k1") != null }?.let(spends::add)
            return chain.proceed(chain.request())
        }
    }

    private suspend fun Regtest.Phone.sentRow(id: String) =
        wallet.activity.first().single { it.kind == ActivityKind.SentNote && it.sentNoteId == id }

    @Test
    fun `a note handed out offline is confirmed by its mint once the wallet is online, and stays the recipient's`() = runBlocking<Unit> {
        val wire = Wire()
        val alice = Regtest.phone(client = Regtest.client(wire))
        alice.fund(2000)
        val note = alice.notes().single()

        wire.offline = true
        val shared = assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(note.id)).note
        assertFalse(shared.confirmed)
        assertEquals(SharedNoteState.Waiting, shared.state)
        assertNotNull(shared.link, "the link is there offline: it derives from the seed")
        assertEquals(null, shared.checkedAtMillis, "its mint hasn't answered for it yet")
        assertEquals(NoteState.SharedUnconfirmed, alice.store.note(note.id)?.state)
        assertEquals(0, alice.balance())

        wire.offline = false
        alice.wallet.refresh()
        val confirmed = assertNotNull(alice.wallet.sharedNote(note.id))
        assertTrue(confirmed.confirmed)
        assertNotNull(confirmed.checkedAtMillis, "the screen can say when its mint last answered")
        assertEquals(SharedNoteState.Waiting, confirmed.state)
        assertEquals(NoteState.Shared, alice.store.note(note.id)?.state, "not taken back on its own")
        assertEquals(ActivityStatus.Pending, alice.sentRow(note.id).status)
        assertEquals(emptyList(), wire.spends, "checked by its key only")

        // The user takes it back.
        assertIs<ReclaimResult.Reclaimed>(alice.wallet.reclaimNote(note.id))
        assertEquals(SharedNoteState.Reclaimed, alice.wallet.sharedNote(note.id)?.state)
        assertEquals(note.amountMsat, alice.balance())
    }

    @Test
    fun `a note handed out offline but spent meanwhile on another device shows as spent once online`() = runBlocking<Unit> {
        val wire = Wire()
        val alice = Regtest.phone(client = Regtest.client(wire))
        alice.fund(2000)
        val note = alice.notes().single()

        wire.offline = true
        assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(note.id))

        // The same recovery phrase on another device renews the note.
        val other = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        assertIs<RestoreResult.Restored>(other.restore(alice.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        assertIs<RotateResult.Rotated>(other.rotateKey(HOST, assertNotNull(note.purpose), assertNotNull(note.index)))

        wire.offline = false
        alice.wallet.refresh()
        assertEquals(SharedNoteState.Spent, alice.wallet.sharedNote(note.id)?.state)
        assertEquals(ActivityStatus.Done, alice.sentRow(note.id).status)
        assertEquals(NoteState.Spent, alice.store.note(note.id)?.state)
    }

    @Test
    fun `a wallet's own note opened in it offline is taken back once online, not received`() = runBlocking<Unit> {
        val wire = Wire()
        val alice = Regtest.phone(client = Regtest.client(wire))
        alice.fund(2000)
        val sent = assertIs<CreateNoteResult.Created>(alice.wallet.createNote(1_000_000)).note
        val before = alice.balance()

        wire.offline = true
        val preview = assertIs<ClaimPreview.Offline>(alice.wallet.previewClaim(assertNotNull(sent.link)))
        alice.wallet.keepClaim(preview)

        wire.offline = false
        alice.wallet.refresh()
        assertEquals(ReceivedState.Claimed, alice.wallet.receivedNotes.first().single().state)
        assertEquals(SharedNoteState.Reclaimed, alice.wallet.sharedNote(sent.id)?.state)
        assertEquals(ActivityStatus.Cancelled, alice.sentRow(sent.id).status, "taken back")
        assertTrue(alice.wallet.activity.first().none { it.kind == ActivityKind.Claimed }, "not received from someone")
        assertEquals(before + 1_000_000, alice.balance())
    }

    @Test
    fun `note maintenance never touches a shared note`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000, 1000)
        val (shared, kept) = alice.notes()
        assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(shared.id))

        // A strategy that wants one note: it may only combine what is in the balance.
        alice.wallet.setNoteStrategy(SingleNoteStrategy.ID)
        alice.wallet.refresh()
        assertEquals(NoteState.Shared, alice.store.note(shared.id)?.state)
        assertEquals(SharedNoteState.Waiting, alice.wallet.sharedNote(shared.id)?.state)
        assertNotNull(alice.wallet.sharedNote(shared.id)?.checkedAtMillis, "shared online: checked by its mint just now")
        assertEquals(kept.amountMsat, alice.balance())
    }
}
