package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** The notes page: combine, split, and move notes to another service. */
class NotesTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch) {
        var next = 0L

        suspend fun fund(vararg sats: Long) {
            sats.forEach { mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, next++)), it * 1000) }
            wallet.refresh()
        }

        suspend fun outstanding() = store.notes("a").first().filter { it.state == NoteState.Outstanding }
        suspend fun on(host: String) = outstanding().filter { it.host == host }.map { it.amountMsat }.sorted()
        suspend fun history() = store.activities("a").first()
    }

    private fun withMint(vararg flags: String, test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start(*flags).use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            wallet.payWaitMillis = 3_000
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    @Test
    fun `combining notes leaves one, with the merge refund`() = withMint("--baseFeeMsat=1000") {
        fund(10, 20, 30)
        assertIs<NoteActionResult.Done>(wallet.combineNotes(outstanding().map { it.id }))
        assertEquals(listOf(62_000L), on(mint.host), "60 + 2 refunded base fees")
        val combined = history().single { it.kind == ActivityKind.Combined }
        assertEquals(3, combined.noteCount)
        assertEquals(false, combined.automatic)
    }

    @Test
    fun `combining needs two notes on one service`() = withMint {
        fund(10)
        assertEquals(NoteActionResult.Failed(NoteProblem.TooFewNotes), wallet.combineNotes(outstanding().map { it.id }))
        assertEquals(NoteActionResult.Failed(NoteProblem.NotFound), wallet.combineNotes(listOf("nope", outstanding().single().id)))
    }

    @Test
    fun `a note is split into the amount and the change, minus the split fee`() = withMint("--baseFeeMsat=1000") {
        fund(100)
        val id = outstanding().single().id
        assertEquals(SplitLimits(1, 98_999, 1_000), wallet.splitLimits(id))
        assertEquals(NoteActionResult.Failed(NoteProblem.InvalidAmount), wallet.splitNote(id, 99_500))
        assertIs<NoteActionResult.Done>(wallet.splitNote(id, 30_000))
        assertEquals(listOf(30_000L, 69_000L), on(mint.host))
        assertEquals(ActivityKind.Split, history().first().kind)
        assertEquals(true, history().first().kind.isMaintenance)
    }

    @Test
    fun `notes move to another service, merged and melted here, minted there to our own key`() = withMint("--baseFeeMsat=1000") {
        fund(30, 20)
        TargetService(feeMsat = 2_000).use { target ->
            val ids = outstanding().map { it.id }
            val quote = assertIs<ServiceMoveQuote.Ready>(wallet.quoteServiceMove(ids, target.host))
            assertEquals(51_000, quote.amountMsat, "merged, with the refund")
            assertEquals(49_000, quote.expectedMsat, "the target's 2 sat mint fee")

            assertEquals(ServiceMoveResult.Moved(49_000, 2_000), wallet.moveToService(ids, target.host))
            assertEquals(emptyList(), on(mint.host))
            assertEquals(listOf(49_000L), on(target.host))
            val arrived = outstanding().single()
            assertEquals(NotePurpose.Wallet.value, arrived.purpose, "a wallet key of this account on the target")
            assertEquals(arrived.id, target.mintedTo)
            val moved = history().single { it.kind == ActivityKind.MovedService }
            assertEquals(ActivityStatus.Done, moved.status)
            assertEquals(target.host, moved.counterparty)
            assertEquals(2_000, moved.feeMsat)
        }
    }

    @Test
    fun `a move whose payment fails gives the money back and expects nothing on the target`() = withMint("--meltAlwaysFails") {
        fund(30)
        TargetService(feeMsat = 0, mints = false).use { target ->
            val id = outstanding().single().id
            assertEquals(ServiceMoveResult.Failed(NoteProblem.PaymentFailed), wallet.moveToService(listOf(id), target.host))
            assertEquals(listOf(30_000L), on(mint.host))
            assertEquals(ActivityStatus.Failed, history().single { it.kind == ActivityKind.MovedService }.status)
            assertEquals(0, store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).size, "nothing left to reconcile")
        }
    }

    @Test
    fun `moving needs another, working service`() = withMint {
        fund(30)
        val ids = outstanding().map { it.id }
        assertEquals(ServiceMoveQuote.Invalid(NoteProblem.SameService), wallet.quoteServiceMove(ids, mint.host))
        assertEquals(ServiceMoveQuote.Invalid(NoteProblem.Unreachable), wallet.quoteServiceMove(ids, "localhost:1"))
        TargetService(feeMsat = 0, wrongInvoice = true).use { target ->
            assertEquals(ServiceMoveResult.Failed(NoteProblem.InvoiceMismatch), wallet.moveToService(ids, target.host))
            assertEquals(listOf(30_000L), on(mint.host), "nothing was melted")
        }
    }

    /**
     * Another LNURLcash service, just enough of one: a minting payRequest whose invoices (test invoices, unpayable)
     * mint the commented key once requested, unless [mints] is off, and lookups by `p`.
     */
    class TargetService(private val feeMsat: Long, private val mints: Boolean = true, private val wrongInvoice: Boolean = false) : AutoCloseable {
        private val server = MockWebServer()
        val host get() = "localhost:${server.port}"
        private val minted = mutableMapOf<String, Long>()
        var mintedTo: String? = null
        private val metadata get() = """[["text/plain","target"],["text/plain","Mint fees: $feeMsat,0"]]"""
        private val pubkey = "034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val base = "http://$host"
                    val url = request.url
                    return when (url.encodedPath) {
                        "/.well-known/lnurlp/_" -> MockResponse(body = """{"tag":"payRequest","callback":"$base/p/cb","minSendable":1000,"maxSendable":100000000,"metadata":"${metadata.replace("\"", "\\\"")}","withdrawLink":"$base/w","commentAllowed":64}""")
                        "/.well-known/lnurlw/_" -> MockResponse(body = """{"tag":"withdrawRequest","callback":"$base/w/cb","minWithdrawable":0,"maxWithdrawable":1000,"mintPubkey":"$pubkey"}""")
                        "/p/cb" -> {
                            val amount = url.queryParameter("amount")!!.toLong()
                            val q = Cp1.decode(url.queryParameter("comment")!!)!!.toHex()
                            if (mints) { minted[q] = amount - feeMsat; mintedTo = q }
                            val hash = if (wrongInvoice) "00".repeat(32) else Payer.sha256Hex(metadata)
                            MockResponse(body = """{"pr":"${TestInvoices.invoice(amount, null, hash)}","routes":[]}""")
                        }
                        "/w" -> {
                            val q = url.queryParameter("p")?.let { Cp1.decode(it)?.toHex() }
                            val value = minted[q] ?: return MockResponse(body = """{"status":"ERROR","reason":"Unknown note."}""")
                            MockResponse(body = """{"tag":"withdrawRequest","callback":"$base/w/cb","minWithdrawable":$value,"maxWithdrawable":$value,"mintPubkey":"$pubkey"}""")
                        }
                        else -> MockResponse(code = 404)
                    }
                }
            }
            server.start()
        }

        override fun close() = server.close()
    }
}
