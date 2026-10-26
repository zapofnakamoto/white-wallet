package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.testkit.MockMint

/** Sending a note as a link: made exact, out of the balance while shared, redeemed by someone else, or taken back. */
class SendNoteTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch) {
        var next = 0L

        suspend fun fund(vararg sats: Long) {
            sats.forEach { mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, next++)), it * 1000) }
            wallet.refresh()
        }

        suspend fun notes(state: NoteState) = store.notes("a").first().filter { it.state == state }.map { it.amountMsat }.sorted()
        suspend fun sentRow() = store.activities("a").first().single { it.kind == ActivityKind.SentNote }

        /** Someone else's wallet on the same service. */
        suspend fun recipient(words: List<String>): Pair<InMemoryWalletStore, Wallet> {
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("b" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            store.addAccount("b", "Bob", Profile("bob", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            return store to wallet
        }
    }

    private fun withMint(vararg flags: String, strategy: String = ManualStrategy.ID, test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "LNURLCASH_CONFORMANCE not set")
        MockMint.start(*flags).use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to alice)), LnurlcashClient(), policies = NotePolicies.InMemory(strategy))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(alice, ""), mint.host)).test()
        }
    }

    @Test
    fun `a note is split off, shared out of the balance, and redeemed by someone else`() = withMint("--baseFeeMsat=1000", strategy = SingleNoteStrategy.ID) {
        fund(100)
        assertEquals(PayQuote.Ok(30_000, 1_000), wallet.quoteNote(30_000))
        val created = assertIs<CreateNoteResult.Created>(wallet.createNote(30_000))
        assertEquals(1_000, created.feeMsat)
        val note = created.note
        assertEquals(SharedNoteState.Waiting, note.state)
        assertEquals(30_000, note.amountMsat)
        assertEquals(true, note.certified)

        // The link is a plain note link: the spend, the amount and the certificate.
        val link = assertNotNull(NoteLink.parse(assertNotNull(note.link)))
        assertEquals(mint.host, link.host)
        assertEquals(30_000, link.declaredAmountMsat)
        assertNotNull(link.certificate)

        assertEquals(69_000, wallet.balanceMsat.first(), "the shared note is out of the balance")
        assertEquals(listOf(30_000L), notes(NoteState.Shared))
        wallet.refresh()
        assertEquals(listOf(30_000L), notes(NoteState.Shared), "keep-one-note leaves a shared note alone")
        assertEquals(ActivityStatus.Pending, sentRow().status)

        val (bobStore, bobWallet) = recipient(bob)
        assertIs<ClaimResult.Claimed>(bobWallet.claim(assertIs<ClaimPreview.Ready>(bobWallet.previewClaim(note.link!!))))
        assertEquals(30_000, bobWallet.balanceMsat.first())
        assertEquals(1, bobStore.notes("b").first().size)

        wallet.refresh()
        assertEquals(emptyList(), notes(NoteState.Shared))
        assertEquals(ActivityStatus.Done, sentRow().status)
        val redeemed = assertNotNull(wallet.sharedNote(note.id))
        assertEquals(SharedNoteState.Redeemed, redeemed.state)
        assertNull(redeemed.link, "a redeemed note's link is no longer shown")
        assertIs<ReclaimResult.AlreadyRedeemed>(wallet.reclaimNote(note.id))
    }

    @Test
    fun `an exact note is shared as is, without a fee`() = withMint("--baseFeeMsat=1000") {
        fund(20, 50)
        val created = assertIs<CreateNoteResult.Created>(wallet.createNote(20_000))
        assertEquals(0, created.feeMsat)
        assertEquals(listOf(50_000L), notes(NoteState.Outstanding))
        assertEquals(null, sentRow().feeMsat)
    }

    @Test
    fun `a shared note nobody redeemed is taken back into the balance`() = withMint {
        fund(40)
        val note = assertIs<CreateNoteResult.Created>(wallet.createNote(15_000)).note
        assertEquals(25_000, wallet.balanceMsat.first())

        assertEquals(ReclaimResult.Reclaimed(15_000), wallet.reclaimNote(note.id))
        assertEquals(40_000, wallet.balanceMsat.first())
        assertEquals(ActivityStatus.Cancelled, sentRow().status)
        assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(note.id)?.state)

        // The old link is worthless now.
        val (_, bobWallet) = recipient(bob)
        assertIs<ClaimPreview.AlreadySpent>(bobWallet.previewClaim(note.link!!))
    }

    @Test
    fun `a shared note claimed back through its own link counts as taken back`() = withMint(strategy = SingleNoteStrategy.ID) {
        fund(40)
        val note = assertIs<CreateNoteResult.Created>(wallet.createNote(15_000)).note
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(note.link!!))))
        assertEquals(40_000, wallet.balanceMsat.first())
        wallet.refresh()
        assertEquals(ActivityStatus.Cancelled, sentRow().status)
        assertEquals(emptyList(), store.activities("a").first().filter { it.kind == ActivityKind.Claimed })
        assertEquals(SharedNoteState.Reclaimed, wallet.sharedNote(note.id)?.state)
    }

    @Test
    fun `amounts the notes can't make are refused before anything is split`() = withMint("--baseFeeMsat=1000") {
        fund(10)
        assertEquals(PayQuote.Invalid(PayProblem.TooMuch, 10_000), wallet.quoteNote(20_000))
        assertIs<PayQuote.Unreachable>(wallet.quoteNote(9_500))
        assertEquals(CreateNoteResult.Failed(PayProblem.NotExact), wallet.createNote(9_500))
        assertEquals(listOf(10_000L), notes(NoteState.Outstanding))
        assertEquals(PayQuote.Invalid(PayProblem.BelowMinimum, 1), wallet.quoteNote(0))
    }
}
