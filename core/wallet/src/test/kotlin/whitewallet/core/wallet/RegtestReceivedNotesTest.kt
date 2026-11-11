@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.MintScan
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.RejectionKind

/**
 * Notes received as links are kept with their spend, whatever happens to the connection, and claimed by a refresh once their mint can be
 * reached: on the real mint, with a connection that can be cut.
 */
class RegtestReceivedNotesTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** A connection that drops every request while [offline]; the mint never sees them. */
    private class Connection : Interceptor {
        var offline = false

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            return chain.proceed(chain.request())
        }
    }

    /**
     * A connection that writes down every request carrying a spend (`k1`), and drops requests while [offline]. While [changed], every mint
     * presents [Regtest.OTHER_KEY] as its signing key.
     */
    private class Watched : Interceptor {
        var offline = false
        var changed = false
        val spends = mutableListOf<HttpUrl>()

        override fun intercept(chain: Interceptor.Chain): Response {
            if (offline) Regtest.dropped()
            val url = chain.request().url
            if (url.queryParameter("k1") != null) spends += url
            val response = chain.proceed(chain.request())
            return if (changed) Regtest.presentingOtherKey(response) else response
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
    fun `a note opened offline is kept, shown as incoming, and claimed once the connection is back`() =
        runBlocking<Unit> {
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
    fun `a claim whose request never reached the mint is tried again, and lands`() =
        runBlocking<Unit> {
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
    fun `a claim whose answer was lost is settled as claimed`() =
        runBlocking<Unit> {
            val link = aNote()
            var loseNext = false
            val connection =
                object : Interceptor {
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
    fun `a note someone else claims first, while ours was in flight, is gone, and its claim settled`() =
        runBlocking<Unit> {
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

    @Test
    fun `a note's spend goes out only with its claim, never to look at it or to try again`() =
        runBlocking<Unit> {
            val connection = Watched()
            val bob = Regtest.phone(client = Regtest.client(connection))

            val first = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(aNote()))
            assertEquals(emptyList(), connection.spends, "looked at by its key alone")
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(first))
            assertTrue(connection.spends.single().isMutation, "sent once, with the claim")

            // Kept offline, then claimed by a refresh: the spend goes out with that claim only.
            connection.spends.clear()
            val link = aNote()
            connection.offline = true
            bob.wallet.keepClaim(assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link)))
            runCatching { bob.wallet.refresh() }
            connection.offline = false
            bob.wallet.refresh()
            assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().first().state)
            assertTrue(connection.spends.single().isMutation)
            assertEquals(2_000_000, bob.balance())
        }

    @Test
    fun `a note on a mint whose key changed is kept without sending its spend, and claimed once the user accepts the key`() =
        runBlocking<Unit> {
            val link = aNote()
            val connection = Watched()
            val bob = Regtest.phone(client = Regtest.client(connection))
            connection.changed = true

            val preview = assertIs<ClaimPreview.KeyChanged>(bob.wallet.previewClaim(link))
            assertEquals(HOST, preview.host)
            assertEquals(1_000_000, preview.amountMsat)
            assertEquals(ReceivedState.KeyChanged, bob.wallet.keepClaim(preview).note.state)
            assertEquals(1_000_000, bob.wallet.incomingMsat.first())

            runCatching { bob.wallet.refresh() }
            assertEquals(
                ReceivedState.KeyChanged,
                bob.wallet.receivedNotes.first().single().state,
                "nothing claimed from a mint with another key",
            )
            assertEquals(emptyList(), connection.spends, "nor the spend sent there")
            assertEquals(
                1_000_000,
                bob.wallet.client
                    .lookup(assertNotNull(bob.store.mint(HOST)).withdrawUrl, NoteLink.parse(link)!!.spend.q)
                    .maxWithdrawableMsat,
                "still the sender's to take back, but still there",
            )

            assertTrue(bob.wallet.acceptMintKey(HOST, Regtest.OTHER_KEY))
            bob.wallet.refresh()
            assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().single().state)
            assertEquals(1_000_000, bob.balance())
            assertTrue(connection.spends.single().isMutation)
        }

    @Test
    fun `offline, a certificate counts only if it verifies against the key pinned for its mint`() =
        runBlocking<Unit> {
            val link = aNote()
            val connection = Connection()
            val bob = Regtest.phone(client = Regtest.client(connection))
            bob.store.saveMint(assertNotNull(bob.store.mint(HOST)).copy(mintPubkey = Regtest.OTHER_KEY))
            connection.offline = true
            val preview = assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link))
            assertNull(preview.certifiedMsat, "signed with a key this wallet doesn't know for the mint")
            assertEquals(1_000_000, preview.declaredMsat)
        }

    @Test
    fun `a note on a mint the wallet doesn't know yet is claimed, and the key that mint presents is pinned`() =
        runBlocking<Unit> {
            val link = aNote()
            val bob = Regtest.phone(host = HOST2)
            assertNull(bob.store.mint(HOST))
            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
            assertTrue(preview.certified, "its certificate verifies against the key the mint presents")
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))
            assertEquals(Regtest.mintKey(HOST), assertNotNull(bob.store.mint(HOST)).mintPubkey)
            assertEquals(1_000_000, bob.notes(HOST).sumOf { it.amountMsat })
        }

    @Test
    fun `a note on a mint the wallet doesn't know yet lands past the keys its phrase already used there, and their notes are found`() =
        runBlocking<Unit> {
            // Carol's phrase holds two notes on its wallet keys at the first mint.
            val carol = Regtest.phone()
            carol.fund(2000, 2000)
            assertEquals(2, carol.wallet.rotateAllKeys(HOST, Regtest.addressSet).rotated)
            val there = carol.notes(HOST)
            val link = aNote()

            // The same phrase on another phone, which only knows the second mint.
            val bob = Regtest.restored(carol, host = HOST2)
            assertNull(bob.store.mint(HOST))
            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))

            // Both mints are on one domain and share a counter, which the restore set: the first key is refused as in use, and the keys
            // are looked through then. (On a domain of its own they are looked through before the first request, see KeyIndexTest.)
            assertEquals(
                listOf(JournalStatus.Rejected, JournalStatus.Confirmed),
                bob.store.journal.values.map { it.status },
                "one refusal, then the first free key",
            )
            val claimed = bob.notes(HOST).single { note -> there.none { it.id == note.id } }
            assertEquals(there.maxOf { it.index!! } + 1, claimed.index, "the first key past the ones in use")
            assertEquals(there.sumOf { it.amountMsat } + 1_000_000, bob.notes(HOST).sumOf { it.amountMsat })
            assertEquals(MintScan.Never, assertNotNull(bob.store.mint(HOST)).scan, "only the wallet's keys were looked through")
        }

    @Test
    fun `online, the mint's answer decides, a link carrying another note's certificate, or a broken one, changes nothing`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(3000)
            val one = NoteLink.parse(assertNotNull(assertIs<CreateNoteResult.Created>(alice.wallet.createNote(1_000_000)).note.link))!!
            val other = NoteLink.parse(assertNotNull(assertIs<CreateNoteResult.Created>(alice.wallet.createNote(500_000)).note.link))!!
            val bob = Regtest.phone()
            assertTrue(assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(one.encode())).certified)

            val swapped =
                assertIs<ClaimPreview.Ready>(
                    bob.wallet.previewClaim(one.copy(certificate = other.certificate, declaredAmountMsat = 500_000).encode())
                )
            assertEquals(true, swapped.certified, "the mint's own certificate for the note")
            assertEquals(1_000_000, swapped.amountMsat, "what the mint says, not the link")
            assertEquals(
                true,
                assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(one.copy(certificate = "cs92233720368qqqq").encode())).certified,
            )
        }

    @Test
    fun `a note kept on one account is claimed into that account, once its wallet is active`() =
        runBlocking<Unit> {
            val link = aNote()
            val connection = Connection()
            val bob = Regtest.phone(client = Regtest.client(connection))
            val first = bob.wallet.activeId()
            connection.offline = true
            bob.wallet.keepClaim(assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link)))
            connection.offline = false

            assertIs<OnboardResult.Ready>(bob.wallet.onboard(Regtest.name(), HOST, "Second"))
            val second = bob.wallet.activeId()
            assertEquals(emptyList(), bob.wallet.receivedNotes.first(), "the first account's, not shown in the second")
            assertEquals(0, bob.wallet.incomingMsat.first())

            bob.wallet.refresh()
            assertEquals(0, bob.balance(), "not claimed into the active account")
            bob.wallet.switchAccount(first)
            assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state, "another wallet's refresh left it alone")
            bob.wallet.refresh()
            assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().single().state)
            assertEquals(1_000_000, bob.balance())
            assertEquals(listOf(ActivityKind.Claimed), bob.wallet.activity.first().map { it.kind })
            bob.wallet.switchAccount(second)
            assertEquals(emptyList(), bob.wallet.activity.first().filter { it.kind == ActivityKind.Claimed })
        }

    @Test
    fun `a link opened again while it waits is claimed, and stays one note`() =
        runBlocking<Unit> {
            val link = aNote()
            val connection = Connection()
            val bob = Regtest.phone(client = Regtest.client(connection))
            connection.offline = true
            val kept = bob.wallet.keepClaim(assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link))).note
            connection.offline = false

            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))
            val claimed = bob.wallet.receivedNotes.first().single()
            assertEquals(ReceivedState.Claimed, claimed.state)
            assertEquals(kept.receivedAtMillis, claimed.receivedAtMillis, "received when first kept")
            assertEquals(1_000_000, bob.balance())
            assertEquals(ClaimPreview.AlreadyInWallet, bob.wallet.previewClaim(link))
        }

    @Test
    fun `a note its mint keeps refusing for another reason is tried for a week, then given up and can be removed`() =
        runBlocking<Unit> {
            val link = aNote()
            var now = System.currentTimeMillis()
            // The mint refuses every claim with a reason that is neither spent, unknown nor a lock.
            val bob =
                Regtest.phone(
                    client =
                        Regtest.client { chain ->
                            if (chain.request().url.isMutation)
                                Regtest.answer(chain, """{"status":"ERROR","reason":"service temporarily unavailable"}""")
                            else chain.proceed(chain.request())
                        },
                    now = { now },
                )

            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
            assertEquals(RejectionKind.Other, assertIs<ClaimResult.Failed>(bob.wallet.claim(preview)).kind)
            val refused = bob.wallet.receivedNotes.first().single()
            assertEquals(ReceivedState.Waiting, refused.state, "tried again")
            assertEquals(now, refused.refusedSinceMillis)

            now += 6 * DAY
            bob.wallet.refresh()
            assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state, "six days on, still tried")
            assertEquals(1_000_000, bob.wallet.incomingMsat.first())

            now += DAY
            bob.wallet.refresh()
            val given = bob.wallet.receivedNotes.first().single()
            assertEquals(ReceivedState.Refused, given.state, "a week after the first refusal")
            assertNotNull(given.k1, "kept until the user removes it")
            assertEquals(0, bob.wallet.incomingMsat.first())
            bob.wallet.refresh()
            assertEquals(ReceivedState.Refused, bob.wallet.receivedNotes.first().single().state, "no longer tried")

            bob.wallet.dismissReceivedNote(given.id)
            assertEquals(emptyList(), bob.wallet.receivedNotes.first())
            assertEquals(
                1_000_000,
                bob.wallet.client
                    .lookup(assertNotNull(bob.store.mint(HOST)).withdrawUrl, NoteLink.parse(link)!!.spend.q)
                    .maxWithdrawableMsat,
                "never claimed: still at the mint",
            )
        }

    @Test
    fun `a brand gives up on a refused note sooner`() =
        runBlocking<Unit> {
            val link = aNote()
            var now = System.currentTimeMillis()
            val bob =
                Regtest.phone(
                    client =
                        Regtest.client { chain ->
                            if (chain.request().url.isMutation)
                                Regtest.answer(chain, """{"status":"ERROR","reason":"service temporarily unavailable"}""")
                            else chain.proceed(chain.request())
                        },
                    now = { now },
                    receivedGiveUpMillis = 2 * HOUR,
                )
            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
            assertIs<ClaimResult.Failed>(bob.wallet.claim(preview))

            now += HOUR
            bob.wallet.refresh()
            assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state, "an hour on, still tried")
            now += HOUR
            bob.wallet.refresh()
            assertEquals(ReceivedState.Refused, bob.wallet.receivedNotes.first().single().state, "given up after the brand's two hours")
        }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
