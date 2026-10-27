package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class PayTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch) {
        var next = 0L

        /** Notes as address payments (keys 0, 1, ...), found by a refresh. */
        suspend fun fund(vararg sats: Long) {
            sats.forEach { mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, next++)), it * 1000) }
            wallet.refresh()
        }

        suspend fun balance() = wallet.balanceMsat.first()
        suspend fun sent() = store.activities("a").first().filter { it.kind == ActivityKind.Sent }
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

    private suspend fun Fixture.ready(input: String) = assertIs<PayPreview.Ready>(wallet.previewPayment(input))

    @Test
    fun `an invoice is paid from a note worth exactly its amount, at no fee`() = withMint {
        fund(21)
        val preview = ready(TestInvoices.invoice(21_000, "coffee"))
        assertEquals(21_000, preview.fixedAmountMsat)
        assertEquals("coffee", preview.description)
        assertEquals(PayQuote.Ok(21_000, 0), wallet.quotePayment(preview, 21_000))

        assertEquals(PayResult.Paid(21_000, 0), wallet.pay(preview, 21_000))
        assertEquals(0, balance())
        val sent = sent().single()
        assertEquals(21_000, sent.amountMsat)
        assertEquals(ActivityStatus.Done, sent.status)
    }

    @Test
    fun `a bigger note is split first, and the split fee is shown and charged`() = withMint("--baseFeeMsat=1000") {
        fund(100)
        val preview = ready(TestInvoices.invoice(30_000))
        assertEquals(PayQuote.Ok(30_000, 1_000), wallet.quotePayment(preview, 30_000))
        assertEquals(PayResult.Paid(30_000, 1_000), wallet.pay(preview, 30_000))
        assertEquals(69_000, balance(), "100 - 30 - the 1 sat split fee")
        assertEquals(1_000, sent().single().feeMsat)
    }

    @Test
    fun `notes are merged and split for an amount no single note covers`() = withMint("--baseFeeMsat=1000") {
        fund(30, 20, 5)
        val preview = ready(TestInvoices.invoice(40_000))
        assertEquals(PayResult.Paid(40_000, 0), wallet.pay(preview, 40_000), "the merge refund pays for the split")
        assertEquals(15_000, balance(), "5 untouched + 10 change")
    }

    @Test
    fun `an amount the notes can't make exactly says what can be paid`() = withMint("--baseFeeMsat=1000") {
        fund(30, 20)
        val preview = ready(TestInvoices.invoice(50_500))
        assertEquals(PayQuote.Unreachable(maxWithChangeMsat = 49_999, allMsat = 51_000), wallet.quotePayment(preview, 50_500))
        assertEquals(PayQuote.Invalid(PayProblem.TooMuch, 51_000), wallet.quotePayment(ready(TestInvoices.invoice(60_000)), 60_000))
        assertEquals(PayResult.Failed(PayProblem.NotExact), wallet.pay(preview, 50_500))
        assertEquals(50_000, balance(), "nothing touched")
    }

    @Test
    fun `a failed payment gives the money back`() = withMint("--meltAlwaysFails") {
        fund(21)
        assertEquals(PayResult.Failed(PayProblem.PaymentFailed), wallet.pay(ready(TestInvoices.invoice(21_000)), 21_000))
        assertEquals(21_000, balance())
        assertEquals(ActivityStatus.Failed, sent().single().status)
    }

    @Test
    fun `a payment that doesn't settle in time is pending, out of the balance, and settles on a later refresh`() = withMint("--meltNeverSettles") {
        fund(21)
        assertEquals(PayResult.Pending(21_000), wallet.pay(ready(TestInvoices.invoice(21_000)), 21_000))
        assertEquals(0, balance(), "the note is being paid")
        assertEquals(NoteState.Paying, store.notes("a").first().single().state)
        assertEquals(ActivityStatus.Pending, sent().single().status)
        wallet.refresh()
        assertEquals(ActivityStatus.Pending, sent().single().status, "still in flight")
    }

    @Test
    fun `invoices that can't be paid are explained up front`() = withMint {
        fund(21)
        assertEquals(PayPreview.Invalid(PayProblem.NoAmount), wallet.previewPayment(TestInvoices.invoice(null)))
        assertEquals(PayPreview.Invalid(PayProblem.Expired), wallet.previewPayment(TestInvoices.invoice(1_000, timestampSeconds = 1_000_000)))
        assertEquals(PayPreview.Invalid(PayProblem.NotRecognized), wallet.previewPayment("hello"))
        assertIs<PayPreview.Ready>(wallet.previewPayment("bitcoin:bcrt1qxyz?amount=0.0001&lightning=" + TestInvoices.invoice(1_000)))
    }

    @Test
    fun `a pay link gets an invoice for the chosen amount, with the comment`() = withMint {
        fund(50)
        PayLinkServer().use { payee ->
            val preview = ready("carol@${payee.host}")
            assertEquals("carol@${payee.host}", preview.payee)
            assertEquals("Pay Carol", preview.description)
            assertNull(preview.fixedAmountMsat)
            assertEquals(140, preview.commentMaxLength)
            assertEquals(PayResult.Paid(12_000, 0), wallet.pay(preview, 12_000, "thanks"))
            assertEquals("12000", payee.lastAmount)
            assertEquals("thanks", payee.lastComment)
            assertEquals(38_000, balance())
            assertEquals("carol@${payee.host}", sent().single().counterparty)
        }
    }

    @Test
    fun `an invoice for another amount than asked is refused before anything is split`() = withMint {
        fund(50)
        PayLinkServer(wrongAmount = true).use { payee ->
            assertEquals(PayResult.Failed(PayProblem.InvoiceMismatch), wallet.pay(ready("carol@${payee.host}"), 12_000))
            assertEquals(1, store.notes("a").first().size, "no split happened")
            assertEquals(50_000, balance())
        }
    }

    @Test
    fun `a payee on the same service gets the notes directly, at their published key`() = withMint("--registeredAddress") {
        fund(50)
        val preview = ready("mint@${mint.host}")
        assertTrue(preview.internal)
        assertEquals(0, preview.commentMaxLength, "an internal transfer carries no comment")
        assertEquals(PayResult.Paid(20_000, 0), wallet.pay(preview, 20_000))
        assertEquals(30_000, balance())

        // The note sits at the payee's purpose-2 key the service hinted at.
        val pay = assertIs<LnurlResponse.PayRequest>(LnurlcashClient().get("${mint.url}/.well-known/lnurlp/mint"))
        val cpub = pay.metadataEntries.single { it.first == "text/cpub" }.second
        val payeeKey = CashBranch.fromCx1(cpub.substringBeforeLast(':'))!!.notePubkey(NotePurpose.LightningAddress, 0)
        val service = store.serviceMap[mint.host]!!
        assertEquals(20_000, LnurlcashClient().lookup(service.withdrawUrl, payeeKey).maxWithdrawableMsat)
        assertTrue(store.notes("a").first().none { it.id == payeeKey.toHex() }, "never recorded as our note")
    }

    /** A LUD-16 payee on another service, issuing test invoices that commit to its metadata. */
    private class PayLinkServer(private val wrongAmount: Boolean = false) : AutoCloseable {
        private val server = MockWebServer()
        var lastAmount: String? = null
        var lastComment: String? = null
        val host get() = "localhost:${server.port}"
        private val metadata = """[["text/plain","Pay Carol"],["text/identifier","carol@HOST"]]"""

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val meta = metadata.replace("HOST", host)
                    val json = meta.replace("\"", "\\\"")
                    return when (request.url.encodedPath) {
                        "/.well-known/lnurlp/carol" -> MockResponse(body = """{"tag":"payRequest","callback":"http://$host/cb","minSendable":1000,"maxSendable":1000000,"metadata":"$json","commentAllowed":140}""")
                        "/cb" -> {
                            lastAmount = request.url.queryParameter("amount")
                            lastComment = request.url.queryParameter("comment")
                            val amount = lastAmount!!.toLong() + if (wrongAmount) 1_000 else 0
                            MockResponse(body = """{"pr":"${TestInvoices.invoice(amount, null, Payer.sha256Hex(meta))}","routes":[]}""")
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
