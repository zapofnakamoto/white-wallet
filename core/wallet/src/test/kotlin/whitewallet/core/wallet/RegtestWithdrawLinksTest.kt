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
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.Requests

/**
 * Withdraw links received over Lightning (LUD-03; LUD-25's melt) into a mint of the account: links of another kind of service (answered on
 * the wire by [WithdrawService], which pays from user-lnd), and real notes on a mint that publishes no signing key ([Keyless] strips it
 * from the second mint's answers).
 */
class RegtestWithdrawLinksTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /**
     * A withdraw link of another kind of service, at `lnbits.example`: it hands out [ownK1] and pays any invoice sent with it from user-lnd
     * (or refuses, with [refuse]; or only takes it, with [payLater]: the test pays [paid] then, or never). With [k1InLink] its link carries
     * that k1 too, and with [needsK1] it answers nothing useful without it. Every request it sees is in [requests].
     */
    private class WithdrawService(
        val ownK1: String = "x7Yh2LnBitsToken",
        val minMsat: Long = 1_000_000,
        val maxMsat: Long = 5_000_000,
        val k1InLink: Boolean = false,
        val needsK1: Boolean = false,
    ) : Interceptor {
        var refuse = false
        var payLater = false
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
                        if (!payLater) thread { Regtest.pay(pr, 0) }
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
            assertNull(preview.reason, "no note in it")
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
            val row = bob.store.activities(bob.store.currentAccountId()!!).first().single { it.kind == ActivityKind.Received }
            assertEquals("lnbits.example", row.counterparty, "History names the link's service")
            assertEquals(ActivityStatus.Done, row.status)
            assertEquals(4_999_000, row.amountMsat)
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
            assertNull(preview.reason, "no note in it")
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
            assertEquals(UnclaimedReason.NotANote, preview.reason)
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
            assertEquals(UnclaimedReason.NotANote, unknown.reason)
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
            assertEquals(UnclaimedReason.NotANote, kept.unclaimedReason)
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
    fun `a note link the service pays only later is melting until a refresh finds its invoice paid, and waits for the user again if it expires`() =
        runBlocking<Unit> {
            val later = WithdrawService(ownK1 = "12".repeat(32), k1InLink = true).apply { payLater = true }
            val bob = Regtest.phone(client = Regtest.client(later))
            bob.wallet.payWaitMillis = 0
            val before = bob.balance()
            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(later.link))
            assertEquals(ClaimResult.Pending, bob.wallet.melt(preview))
            val melting = received(bob).single()
            assertEquals(ReceivedState.Melting, melting.state)
            assertEquals(UnclaimedReason.NotANote, melting.unclaimedReason)
            assertNotNull(melting.journalId)

            Regtest.pay(assertNotNull(later.paid), 0)
            bob.wallet.refresh()
            val claimed = received(bob).single()
            assertEquals(ReceivedState.Claimed, claimed.state)
            assertNull(claimed.unclaimedReason)
            assertNull(claimed.k1)
            assertEquals(before + 4_999_000, bob.balance(), "the home mint's base fee comes off")

            var clock = System.currentTimeMillis()
            val never = WithdrawService(ownK1 = "34".repeat(32), k1InLink = true).apply { payLater = true }
            val carol = Regtest.phone(client = Regtest.client(never), now = { clock })
            carol.wallet.payWaitMillis = 0
            assertEquals(ClaimResult.Pending, carol.wallet.melt(assertIs(carol.wallet.previewClaim(never.link))))
            assertEquals(ReceivedState.Melting, received(carol).single().state)
            // Its invoice expires unpaid (past its expiry, and the margin for a payment still on its way): back to waiting for the user,
            // with why, and its link kept.
            clock = assertNotNull(Bolt11.decode(assertNotNull(never.paid))).expiresAtSeconds * 1000 + 11 * 60_000
            carol.wallet.refresh()
            val back = received(carol).single()
            assertEquals(ReceivedState.LightningOnly, back.state)
            assertEquals(UnclaimedReason.NotANote, back.unclaimedReason)
            assertNull(back.journalId)
            assertNotNull(back.k1)
        }

    @Test
    fun `a note on a mint without a signing key is received into the home mint over Lightning`() =
        runBlocking<Unit> {
            val link = Regtest.note(3000, second = true)
            val bob = Regtest.phone(client = Regtest.client(Keyless()))
            val before = bob.balance()

            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(link))
            assertEquals(UnclaimedReason.NoSigningKey, preview.reason)
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
    fun `an account without a home mint picks the mint to receive into, and a note's value is checked there, its spend not shown first`() =
        runBlocking<Unit> {
            val link = Regtest.note(3000, second = true)
            val shown = mutableListOf<HttpUrl>()
            val watched = Interceptor { chain ->
                val url = chain.request().url
                if ("${url.host}:${url.port}" == HOST2 && url.queryParameter("k1") != null) shown += url
                Keyless().intercept(chain)
            }
            val bob = Regtest.phone(client = Regtest.client(watched))
            bob.store.saveAccount(assertNotNull(bob.store.account(bob.store.currentAccountId()!!)).copy(homeHost = null))

            val preview = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(link))
            assertNull(preview.target)
            assertEquals(listOf(HOST), preview.mints)
            val into = assertIs<ClaimPreview.Melt>(bob.wallet.meltInto(preview, HOST))
            assertEquals(HOST, into.target)
            assertNull(into.problem)
            val value = assertNotNull(into.amountMsat, "its mint's answer said what it is worth: nothing to ask")
            assertEquals(value - 1_000, into.expectedMsat, "the picked mint's base fee comes off")
            assertEquals(emptyList(), shown, "the note's spend goes to its mint only with the melt")

            assertEquals(value - 1_000, assertIs<ClaimResult.Claimed>(bob.wallet.melt(into)).amountMsat)
        }

    @Test
    fun `a melt of such a note that fails keeps it, and it can be received another time`() =
        runBlocking<Unit> {
            val link = Regtest.note(3000, second = true)
            var refuse = true
            val connection = Interceptor { chain ->
                val url = chain.request().url
                if (refuse && "${url.host}:${url.port}" == HOST2 && url.queryParameter("pr") != null) {
                    Regtest.answer(chain, """{"status":"ERROR","reason":"no route"}""")
                } else Keyless().intercept(chain)
            }
            val bob = Regtest.phone(client = Regtest.client(connection))
            val before = bob.balance()

            assertIs<ClaimResult.Failed>(bob.wallet.melt(assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(link))))
            val kept = received(bob).single()
            assertEquals(ReceivedState.LightningOnly, kept.state)
            assertEquals(UnclaimedReason.NoSigningKey, kept.unclaimedReason)
            assertNotNull(kept.k1, "the link is kept")
            assertEquals(before, bob.balance())
            // A refresh doesn't try it by itself.
            bob.wallet.refresh()
            assertEquals(ReceivedState.LightningOnly, received(bob).single().state)

            refuse = false
            val again = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(assertNotNull(bob.wallet.receivedNoteLink(kept.id))))
            assertEquals(UnclaimedReason.NoSigningKey, again.reason)
            val result = assertIs<ClaimResult.Claimed>(bob.wallet.melt(again))
            assertEquals(before + result.amountMsat, bob.balance())
            assertEquals(ReceivedState.Claimed, received(bob).single().state)
            assertNull(bob.wallet.mintDetails(HOST2).mint)
        }

    @Test
    fun `a mint without a signing key is never added, nor made home`() =
        runBlocking<Unit> {
            val bob = Regtest.phone(client = Regtest.client(Keyless()))
            assertEquals(
                OnboardResult.MintInvalid(MINT_WITHOUT_SIGNING_KEY),
                assertIs<ConnectResult.Failed>(bob.wallet.addMint(HOST2)).result,
            )
            assertEquals(
                OnboardResult.MintInvalid(MINT_WITHOUT_SIGNING_KEY),
                assertIs<ConnectResult.Failed>(bob.wallet.setHomeMint(HOST2).result).result,
            )
            assertNull(bob.wallet.mintDetails(HOST2).mint)
            assertEquals(HOST, bob.wallet.homeHost.first())
        }

    @Test
    fun `a new mint that can't be asked for its signing key isn't added, so it never holds money unchecked`() =
        runBlocking<Unit> {
            var askable = false
            val discovery = Requests.serviceWithdrawDiscoveryUrl(HOST2)
            val connection = Interceptor { chain ->
                if (!askable && chain.request().url.toString() == discovery) Regtest.dropped()
                Keyless().intercept(chain)
            }
            val bob = Regtest.phone(client = Regtest.client(connection))
            assertIs<OnboardResult.NetworkError>(assertIs<ConnectResult.Failed>(bob.wallet.addMint(HOST2)).result)
            assertNull(bob.wallet.mintDetails(HOST2).mint, "not added unpinned")

            // So a note there is still received over Lightning, and asked again, the mint is refused for what it is.
            assertEquals(
                UnclaimedReason.NoSigningKey,
                assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(Regtest.note(3000, second = true))).reason,
            )
            askable = true
            assertEquals(
                OnboardResult.MintInvalid(MINT_WITHOUT_SIGNING_KEY),
                assertIs<ConnectResult.Failed>(bob.wallet.addMint(HOST2)).result,
            )
        }

    @Test
    fun `a note kept offline at a mint without a signing key waits for the user, who receives it over Lightning`() =
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
            assertEquals(UnclaimedReason.NoSigningKey, waiting.unclaimedReason)
            bob.wallet.refresh()
            assertEquals(ReceivedState.LightningOnly, received(bob).single().state, "nor claimed at its mint")

            val melt = assertIs<ClaimPreview.Melt>(bob.wallet.previewClaim(assertNotNull(bob.wallet.receivedNoteLink(waiting.id))))
            assertIs<ClaimResult.Claimed>(bob.wallet.melt(melt))
            assertEquals(ReceivedState.Claimed, received(bob).single().state)
            assertNull(bob.wallet.mintDetails(HOST2).mint)
        }
}
