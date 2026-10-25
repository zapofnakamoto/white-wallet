package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.JournalStatus
import whitewallet.lnurl.Bolt11
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient

/** A brand without required addresses (`requireAddress=false`): accounts with just a mint, receiving by invoice. */
class NoAddressTest {
    private var clock = 1_800_000_000_000L

    private fun withMint(feeMsat: Long = 0, test: suspend (FakeMint, InMemoryWalletStore, Wallet) -> Unit) = runBlocking<Unit> {
        FakeMint(feeMsat) { clock }.use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock }, requireAddress = false)
            test(mint, store, wallet)
        }
    }

    @Test
    fun `an account is set up with just its mint`() = withMint { mint, store, wallet ->
        assertFalse(wallet.isSetUp())
        val created = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main"))
        assertTrue(wallet.isSetUp())
        assertFalse(wallet.needsAddress(), "no address step")
        assertEquals(mint.host, store.account(created.accountId)?.host)
        assertNull(wallet.profile.first())
        assertEquals(mint.host, wallet.homeHost.first())
        assertEquals(12, wallet.recoveryPhrase().size, "it has its own recovery phrase")
        wallet.refresh()
    }

    @Test
    fun `a paid invoice mints a note to the account and shows in History`() = withMint(feeMsat = 1_000) { mint, store, wallet ->
        wallet.createAccount(mint.host, "Main")
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(21_000))
        assertEquals(21_000, Bolt11.decode(request.invoice)?.amountMsat)
        assertEquals(20_000, request.expectedMsat, "less the mint's 1 sat fee")
        assertEquals(InvoiceStatus.Waiting, wallet.invoiceStatus(request.id))
        assertEquals(0, wallet.unresolvedOperations(), "an open invoice isn't stuck")

        mint.payAll()
        assertEquals(InvoiceStatus.Paid(20_000), wallet.invoiceStatus(request.id))
        assertEquals(20_000, wallet.balanceMsat.first())
        val row = store.activities(wallet.activeAccount.first()!!.account.id).first().single()
        assertEquals(ActivityKind.Received, row.kind)
        assertEquals(20_000, row.amountMsat)
        assertEquals(1_000, row.feeMsat)
    }

    @Test
    fun `an invoice paid while the screen was closed arrives with the next refresh`() = withMint { mint, _, wallet ->
        wallet.createAccount(mint.host, "Main")
        assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(8_000))
        mint.payAll()
        wallet.refresh()
        assertEquals(8_000, wallet.balanceMsat.first())
    }

    @Test
    fun `an unpaid invoice is dropped once it expired`() = withMint { mint, store, wallet ->
        wallet.createAccount(mint.host, "Main")
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(5_000))
        assertEquals(InvoiceStatus.Waiting, wallet.invoiceStatus(request.id), "still within the grace period")
        clock = request.expiresAtMillis + 60 * 60_000L
        assertEquals(InvoiceStatus.Expired, wallet.invoiceStatus(request.id))
        assertEquals(JournalStatus.Rejected, store.journal(JournalStatus.entries.toSet()).single().status)
        assertEquals(0, wallet.balanceMsat.first())
        assertTrue(store.activities(wallet.activeAccount.first()!!.account.id).first().isEmpty(), "nothing in History")
    }

    @Test
    fun `amounts the mint doesn't take are refused before an invoice is made`() = withMint { mint, store, wallet ->
        wallet.createAccount(mint.host, "Main")
        assertEquals(InvoiceRequest.OutOfRange(1_000, 100_000_000), wallet.requestInvoice(500))
        assertTrue(store.journal(JournalStatus.entries.toSet()).isEmpty())
    }

    @Test
    fun `accounts without addresses respect the account limit, and require-address brands can't create them`() = runBlocking {
        val store = InMemoryWalletStore()
        store.addAccount("a", "Main", null)
        val limited = Wallet(store, InMemorySeedStore(), LnurlcashClient(), requireAddress = false, maxAccounts = 1)
        assertEquals(CreateAccountResult.AccountLimitReached(1), limited.createAccount("mint.example", "Second"))
        assertFailsWith<IllegalStateException> { Wallet(store, InMemorySeedStore(), LnurlcashClient()).createAccount("mint.example", "Second") }
        assertTrue(limited.isSetUp(), "an account without an address is set up")
        assertFalse(Wallet(store, InMemorySeedStore(), LnurlcashClient()).isSetUp(), "unless the brand requires an address")
    }

    /** A mint whose invoices (valid BOLT11, 10 minutes) mint to the commented key once [payAll] pays them. */
    class FakeMint(private val feeMsat: Long, private val now: () -> Long) : AutoCloseable {
        private val server = MockWebServer()
        val host get() = "localhost:${server.port}"
        private val invoiced = mutableMapOf<String, Long>()
        private val minted = mutableMapOf<String, Long>()
        private val metadata get() = if (feeMsat > 0) """[["text/plain","fake"],["text/plain","Mint fees: $feeMsat,0"]]""" else """[["text/plain","fake"]]"""
        private val pubkey = "034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"

        fun payAll() = synchronized(this) { invoiced.forEach { (q, amount) -> minted[q] = amount - feeMsat }; invoiced.clear() }

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeMint) {
                    val base = "http://$host"
                    val url = request.url
                    when (url.encodedPath) {
                        "/.well-known/lnurlp/_" -> MockResponse(body = """{"tag":"payRequest","callback":"$base/p/cb","minSendable":1000,"maxSendable":100000000,"metadata":"${metadata.replace("\"", "\\\"")}","withdrawLink":"$base/w","commentAllowed":64}""")
                        "/.well-known/lnurlw/_" -> MockResponse(body = """{"tag":"withdrawRequest","callback":"$base/w/cb","minWithdrawable":0,"maxWithdrawable":1000,"mintPubkey":"$pubkey"}""")
                        "/p/cb" -> {
                            val amount = url.queryParameter("amount")!!.toLong()
                            invoiced[Cp1.decode(url.queryParameter("comment")!!)!!.toHex()] = amount
                            val pr = TestInvoices.invoice(amount, "fake", timestampSeconds = now() / 1000, expirySeconds = 600)
                            MockResponse(body = """{"pr":"$pr","routes":[]}""")
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
