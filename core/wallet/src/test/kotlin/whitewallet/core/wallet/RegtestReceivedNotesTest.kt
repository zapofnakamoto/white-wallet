package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.ReceivedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Notes received as links are kept with their spend, whatever happens to the connection, and claimed by a refresh
 * once their mint can be reached: on the real mint, with a connection that can be cut.
 */
class RegtestReceivedNotesTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** A connection that drops every request while [offline]; the mint never sees them. */
    private class Connection : Interceptor {
        var offline = false

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            return chain.proceed(chain.request())
        }
    }

    /** Alice's 3000 sats as a note link, sent to someone. */
    private suspend fun aNote(): String {
        val alice = Regtest.phone()
        alice.fund(3000)
        val sent = assertIs<CreateNoteResult.Created>(alice.wallet.createNote(1_000_000)).note
        return assertNotNull(sent.link)
    }

    @Test
    fun `a note opened offline is kept, shown as incoming, and claimed once the connection is back`() = runBlocking<Unit> {
        val link = aNote()
        val connection = Connection()
        var syncAsked = 0
        val bob = Regtest.phone(client = Regtest.client(connection), onWaitingForNetwork = { syncAsked++ })

        connection.offline = true
        val preview = assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link))
        assertEquals(1_000_000, preview.declaredMsat)
        assertEquals(1_000_000, preview.certifiedMsat, "its certificate checks out against the mint's pinned key")
        val kept = bob.wallet.keepClaim(preview).note
        assertEquals(ReceivedState.Waiting, kept.state)
        assertEquals(1, syncAsked, "a sync is asked for when the device is online again")
        assertEquals(1_000_000, bob.wallet.incomingMsat.first())
        assertEquals(0, bob.balance())

        runCatching { bob.wallet.refresh() }
        assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state, "still offline: still waiting")

        connection.offline = false
        bob.wallet.refresh()
        val claimed = bob.wallet.receivedNotes.first().single()
        assertEquals(ReceivedState.Claimed, claimed.state)
        assertNull(claimed.k1, "the spend is worth nothing now, and isn't kept")
        assertEquals(1_000_000, bob.balance())
        assertEquals(0, bob.wallet.incomingMsat.first())
        assertEquals(listOf(ActivityKind.Claimed to ActivityStatus.Done), bob.wallet.activity.first().map { it.kind to it.status })
    }

    @Test
    fun `a claim whose request never reached the mint is tried again, and lands`() = runBlocking<Unit> {
        val link = aNote()
        val connection = Connection()
        val bob = Regtest.phone(client = Regtest.client(connection))
        val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))

        // Cut right after the check: the claim can't be sent, nor its outcome asked about.
        connection.offline = true
        assertEquals(ClaimResult.Pending, bob.wallet.claim(preview))
        assertEquals(ReceivedState.Claiming, bob.wallet.receivedNotes.first().single().state)
        assertEquals(1_000_000, bob.wallet.incomingMsat.first())

        connection.offline = false
        bob.wallet.refresh()
        assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().single().state)
        assertEquals(1_000_000, bob.balance())
    }

    @Test
    fun `a claim whose answer was lost is settled as claimed`() = runBlocking<Unit> {
        val link = aNote()
        var loseNext = false
        val connection = object : Interceptor {
            var offline = false

            override fun intercept(chain: Interceptor.Chain): Response {
                if (offline) Regtest.dropped()
                val response = chain.proceed(chain.request())
                // The claim lands at the mint, then the connection goes, before its answer arrives.
                if (loseNext && chain.request().url.queryParameter("p1") != null) {
                    response.close()
                    offline = true
                    Regtest.lost()
                }
                return response
            }
        }
        val bob = Regtest.phone(client = Regtest.client(connection))
        val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
        loseNext = true
        assertEquals(ClaimResult.Pending, bob.wallet.claim(preview))
        assertEquals(ReceivedState.Claiming, bob.wallet.receivedNotes.first().single().state)
        assertEquals(0, bob.balance())

        loseNext = false
        connection.offline = false
        bob.wallet.refresh()
        assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().single().state)
        assertEquals(1_000_000, bob.balance())
    }

    @Test
    fun `a note someone else claims first, while ours was in flight, is gone, and its claim settled`() = runBlocking<Unit> {
        val link = aNote()
        val connection = Connection()
        val bob = Regtest.phone(client = Regtest.client(connection))
        val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
        connection.offline = true
        assertEquals(ClaimResult.Pending, bob.wallet.claim(preview))

        val carol = Regtest.phone()
        assertIs<ClaimResult.Claimed>(carol.wallet.claim(assertIs<ClaimPreview.Ready>(carol.wallet.previewClaim(link))))

        connection.offline = false
        bob.wallet.refresh()
        val gone = bob.wallet.receivedNotes.first().single()
        assertEquals(ReceivedState.Gone, gone.state)
        assertNull(gone.k1)
        assertEquals(0, bob.wallet.incomingMsat.first())
        assertEquals(0, bob.balance())
        assertEquals(0, bob.store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).size, "settled, not unknown forever")
        assertEquals(JournalStatus.Rejected, bob.store.journal(setOf(JournalStatus.Rejected)).single().status)
        assertEquals(ActivityStatus.Failed, bob.wallet.activity.first().single { it.kind == ActivityKind.Claimed }.status)

        bob.wallet.dismissReceivedNote(gone.id)
        assertEquals(emptyList(), bob.wallet.receivedNotes.first())
    }
}
