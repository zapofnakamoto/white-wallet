@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.concurrent.thread
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ArchiveReason
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink

/**
 * Withdraw links received over Lightning (LUD-03; LUD-25's melt) into a mint of the account: links of another kind of service (answered on
 * the wire by [WithdrawService], which pays from user-lnd), and real notes on a mint that publishes no signing key ([Keyless] strips it
 * from the second mint's answers).
 */
class RegtestWithdrawLinksTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /**
     * A withdraw link of another kind of service, at `lnbits.example`: it hands out [ownK1] and pays any invoice sent with it from user-lnd
     * (or refuses, with [refuse]). With [k1InLink] its link carries that k1 too, and with [needsK1] it answers nothing useful without it.
     * Every request it sees is in [requests].
     */
    private class WithdrawService(
        val ownK1: String = "x7Yh2LnBitsToken",
        val minMsat: Long = 1_000_000,
        val maxMsat: Long = 5_000_000,
        val k1InLink: Boolean = false,
        val needsK1: Boolean = false,
    ) : Interceptor {
        var refuse = false
        val requests = mutableListOf<HttpUrl>()
        var paid: String? = null

        val link: String
            get() = "lnurlw://lnbits.example/withdraw/abc" + if (k1InLink) "?k1=$ownK1" else ""

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (url.host != "lnbits.example") return chain.proceed(chain.request())
            requests += url
            return when (url.encodedPath) {
                "/withdraw/abc" ->
                    if (needsK1 && url.queryParameter("k1") != ownK1) respond(chain, "<html>Not found</html>", 404)
                    else
                        respond(
                            chain,
                            """{"tag":"withdrawRequest","callback":"https://lnbits.example/withdraw/cb","k1":"$ownK1",""" +
                                """"minWithdrawable":$minMsat,"maxWithdrawable":$maxMsat,"defaultDescription":"LNbits voucher"}""",
                        )
                "/withdraw/cb" -> {
                    val pr = url.queryParameter("pr")
                    if (refuse || url.queryParameter("k1") != ownK1 || pr == null)
                        respond(chain, """{"status":"ERROR","reason":"Withdraw link is spent."}""")
                    else {
                        paid = pr
                        // LUD-03: answer at once, pay in the background.
                        thread { Regtest.pay(pr, 0) }
                        respond(chain, """{"status":"OK"}""")
                    }
                }
                else -> respond(chain, "", 404)
            }
        }

        /** The requests that carried a k1 (the link's own, or any other). */
        val withK1: List<HttpUrl>
            get() = requests.filter { it.queryParameter("k1") != null }

        private fun respond(chain: Interceptor.Chain, body: String, code: Int = 200): Response =
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
    }

    /** The mint at [host] answers as if it published no signing key. */
    private class Keyless(val host: String = HOST2) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val url = chain.request().url
            return if ("${url.host}:${url.port}" == host) Regtest.withoutSigningKey(response) else response
        }
    }

    /** A client whose requests pass through each of [all], in order. */
    private fun client(vararg all: Interceptor): LnurlcashClient =
        LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().apply { all.forEach(::addInterceptor) }.build())

    private suspend fun received(phone: Regtest.Phone) = phone.store.receivedNotes(phone.store.currentAccountId()!!).first()

    @Test
    fun `a withdraw link of another kind of service is received into the home mint over Lightning, after one confirm`() =
        runBlocking<Unit> {
            val service = WithdrawService()
            val bob = Regtest.phone(client = Regtest.client(service))
            val before = bob.balance()

            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(service.link))
            assertEquals(MeltReason.Plain, preview.reason)
            assertEquals(HOST, preview.target)
            assertNull(preview.problem)
            assertEquals(5_000_000, preview.amountMsat, "as much as the link pays and the mint takes")
            // The home mint's base fee (1 sat) comes off.
            assertEquals(4_999_000, preview.expectedMsat)
            assertTrue(service.paid == null && service.withK1.isEmpty(), "nothing sent before the confirm: ${service.requests}")

            val result = assertIs<ClaimResult.Claimed>(bob.wallet.melt(preview))
            assertEquals(4_999_000, result.amountMsat)
            assertEquals(before + 4_999_000, bob.balance())
            assertEquals(listOf(service.ownK1), service.withK1.map { it.queryParameter("k1") })
            assertEquals(emptyList(), received(bob), "no note link to keep")
        }

    @Test
    fun `a bech32 link opens as what its service says - a withdraw link here, a pay link handed to Send`() =
        runBlocking<Unit> {
            val service = WithdrawService()
            val payee = LightningAddressPayee()
            val bob = Regtest.phone(client = client(service, payee))

            val withdraw = Lnurl.encodeBech32("https://lnbits.example/withdraw/abc")
            assertIs<IncomingLink.Claim>(IncomingLink.classify("LIGHTNING:$withdraw"))
            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(withdraw))
            assertEquals(MeltReason.Plain, preview.reason)
            assertIs<ClaimResult.Claimed>(bob.wallet.melt(preview))

            val pay = Lnurl.encodeBech32("https://payee.example/.well-known/lnurlp/carol")
            assertEquals(ClaimPreview.PayLink(pay), bob.wallet.previewClaim(pay))
            assertIs<PayPreview.Ready>(bob.wallet.previewPayment(pay))
            // Send hands a withdraw link to Claim rather than calling it unrecognised.
            assertEquals(PayPreview.Invalid(PayProblem.WithdrawLink), bob.wallet.previewPayment(withdraw))
            assertEquals(
                PayPreview.Invalid(PayProblem.WithdrawLink),
                bob.wallet.previewPayment("lnurlw://lnbits.example/withdraw/abc?k1=abc"),
            )
        }

    @Test
    fun `a link with a k1 shaped like a note's spend is asked by its key, and its k1 goes out only with the melt the user agreed to`() =
        runBlocking<Unit> {
            val service = WithdrawService(ownK1 = "cd".repeat(32), k1InLink = true)
            val bob = Regtest.phone(client = Regtest.client(service))
            assertIs<IncomingLink.Claim>(IncomingLink.classify(service.link))

            // Read as a bearer note: looked up by its key, which the service ignores, answering with its own k1.
            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(service.link))
            assertEquals(MeltReason.NotANote, preview.reason)
            assertNotNull(preview.link.note)
            assertEquals(5_000_000, preview.amountMsat)
            assertEquals(emptyList(), service.withK1, "the k1 stays here until the user agrees")
            assertEquals(emptyList(), received(bob), "nothing kept by looking")

            val result = assertIs<ClaimResult.Claimed>(bob.wallet.melt(preview))
            assertEquals(4_999_000, result.amountMsat)
            assertEquals(1, service.withK1.size)
            // Kept as a received note while it was received, then claimed.
            val kept = received(bob).single()
            assertEquals(ReceivedState.Claimed, kept.state)
            assertNull(kept.k1)
        }

    @Test
    fun `a note-shaped link whose service won't answer without it is shown to that service only once the user agrees`() =
        runBlocking<Unit> {
            val service = WithdrawService(ownK1 = "ef".repeat(32), k1InLink = true, needsK1 = true)
            val bob = Regtest.phone(client = Regtest.client(service))

            val unknown = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(service.link))
            assertEquals(MeltReason.NotANote, unknown.reason)
            assertNull(unknown.amountMsat, "what it pays is only known by asking with the link")
            assertEquals(HOST, unknown.target)
            assertEquals(emptyList(), service.withK1)

            val asked = assertIs<ClaimPreview.Melt>(bob.wallet.askLink(unknown))
            assertEquals(1, service.withK1.size, "asked once, with the link")
            assertEquals(5_000_000, asked.amountMsat)
            assertIs<ClaimResult.Claimed>(bob.wallet.melt(asked))
        }

    @Test
    fun `a melt the service refuses leaves a note-shaped link waiting for the user, and a link paying less than the mint takes is refused up front`() =
        runBlocking<Unit> {
            val service = WithdrawService(ownK1 = "ab".repeat(32), k1InLink = true)
            val bob = Regtest.phone(client = Regtest.client(service))
            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(service.link))
            service.refuse = true
            assertIs<ClaimResult.Failed>(bob.wallet.melt(preview))
            val kept = received(bob).single()
            assertEquals(ReceivedState.LightningOnly, kept.state)
            assertEquals(ArchiveReason.NotANote, kept.archivedReason)
            assertNotNull(kept.k1, "the link is kept")
            // A refresh doesn't try it by itself; opening it again offers the melt again.
            bob.wallet.refresh()
            assertEquals(ReceivedState.LightningOnly, received(bob).single().state)
            assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(assertNotNull(bob.wallet.receivedNoteLink(kept.id))))

            val tiny = WithdrawService(minMsat = 500, maxMsat = 1_500)
            val carol = Regtest.phone(client = Regtest.client(tiny))
            val out = assertIs<ClaimPreview.Melt>(carol.wallet.previewClaim(tiny.link))
            assertEquals(MeltProblem.OutOfRange, out.problem)
            assertEquals(2_000, out.acceptMinMsat)
        }

    @Test
    fun `a note on a mint without a signing key is received into the home mint over Lightning while such mints aren't allowed`() =
        runBlocking<Unit> {
            val link = Regtest.note(3000, second = true)
            val bob = Regtest.phone(client = Regtest.client(Keyless()))
            val before = bob.balance()

            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(link))
            assertEquals(MeltReason.NoSigningKey, preview.reason)
            assertEquals(HOST, preview.target)
            val value = assertNotNull(preview.amountMsat)
            assertEquals(value, preview.link.minMsat, "a note is melted whole")

            val result = assertIs<ClaimResult.Claimed>(bob.wallet.melt(preview))
            assertEquals(value - 1_000, result.amountMsat, "the home mint's base fee comes off")
            assertEquals(before + result.amountMsat, bob.balance())
            assertNull(bob.wallet.mintDetails(HOST2).mint, "the mint without a key isn't added")
            assertEquals(ReceivedState.Claimed, received(bob).single().state)
            // Spent at its mint.
            val q = assertNotNull(NoteLink.parse(link)).spend.q
            assertTrue(runCatching { LnurlcashClient().lookup("http://$HOST2/w", q) }.isFailure)
        }

    @Test
    fun `with such mints allowed, the note is kept at its mint unpinned, and the brand decides whether it can be home`() =
        runBlocking<Unit> {
            val bob = Regtest.phone(client = Regtest.client(Keyless()))
            bob.wallet.setAllowsMintsWithoutSigningKey(true)
            val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(Regtest.note(3000, second = true)))
            assertTrue(!preview.certified && !preview.trusted)
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))
            val mint = assertNotNull(bob.wallet.mintDetails(HOST2).mint)
            assertNull(mint.mintPubkey, "nothing to pin")
            assertTrue(bob.notes(HOST2).isNotEmpty())
            assertIs<ConnectResult.Connected>(bob.wallet.setHomeMint(HOST2).result)

            val carol = Regtest.phone(client = Regtest.client(Keyless()), withoutSigningKey = MintsWithoutSigningKey(home = false))
            carol.wallet.setAllowsMintsWithoutSigningKey(true)
            assertIs<ClaimResult.Claimed>(carol.wallet.claim(assertIs(carol.wallet.previewClaim(Regtest.note(3000, second = true)))))
            val refused = assertIs<ConnectResult.Failed>(carol.wallet.setHomeMint(HOST2).result)
            assertEquals(OnboardResult.MintInvalid(MINT_WITHOUT_SIGNING_KEY_HOME), refused.result)
            assertEquals(HOST, carol.wallet.homeHost.first())
        }

    @Test
    fun `a note kept offline at a mint without a signing key waits for the user, and is claimed at its mint once such mints are allowed`() =
        runBlocking<Unit> {
            val link = Regtest.note(3000, second = true)
            var offline = true
            val connection = Interceptor { chain ->
                if (offline && chain.request().url.port == 8112) Regtest.dropped()
                Keyless().intercept(chain)
            }
            val bob = Regtest.phone(client = Regtest.client(connection))
            val kept = assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(link))
            assertIs<ClaimResult.Kept>(bob.wallet.keepClaim(kept))

            offline = false
            bob.wallet.refresh()
            val waiting = received(bob).single()
            assertEquals(ReceivedState.LightningOnly, waiting.state, "asked of the user, not melted by itself")
            assertEquals(ArchiveReason.NoSigningKey, waiting.archivedReason)

            bob.wallet.setAllowsMintsWithoutSigningKey(true)
            bob.wallet.refresh()
            assertEquals(ReceivedState.Claimed, received(bob).single().state)
            assertTrue(bob.notes(HOST2).isNotEmpty())
        }
}
