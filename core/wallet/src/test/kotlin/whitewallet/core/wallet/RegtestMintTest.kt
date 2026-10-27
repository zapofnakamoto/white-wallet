package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2

/** What the wallet knows about the real mints: fees, limits, the pinned signing key, and trust. */
class RegtestMintTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `connecting pins the signing key, and the details show fees, limits and the money there`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000, 2000)
        val home = alice.wallet.mintDetails(HOST)
        assertEquals(MintDetails.Reachability.Reachable, home.reachability)
        assertTrue(home.isHome)
        val known = assertNotNull(home.mint)
        assertEquals(1_000, known.feeBaseMsat)
        assertNotNull(known.mintPubkey)
        assertEquals(2_998_000, home.balanceMsat)
        assertEquals(2, home.noteCount)

        assertIs<ConnectResult.Connected>(alice.wallet.connectMint(HOST2))
        val second = alice.wallet.mintDetails(HOST2)
        assertEquals(2_000, second.mint?.feeBaseMsat, "the second mint's 2 sat fee")
        assertTrue(second.mint?.mintPubkey != known.mintPubkey, "each mint signs with its own key")
        assertEquals(false, second.isHome)
    }

    @Test
    fun `the home mint is trusted by default, another one only once the user trusts it`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        val link = Regtest.note(1000, second = true)
        assertEquals(false, assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link)).trusted)
        assertEquals(setOf(HOST), alice.wallet.trustedHosts.first())

        // Trust names the mint's key, so the mint is added (and its key pinned) first, as the app does.
        assertIs<ConnectResult.Connected>(alice.wallet.addMint(HOST2))
        alice.wallet.setMintTrusted(HOST2, true)
        assertEquals(true, assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link)).trusted)
        alice.wallet.setMintTrusted(HOST, false)
        assertEquals(setOf(HOST2), alice.wallet.trustedHosts.first())
    }

    @Test
    fun `a mint that presents another signing key gets nothing sent until it is the pinned key again`() = runBlocking<Unit> {
        // On the wire, the mint's answers carry a different signing key (the generator point, a valid key nobody pins).
        var changed = false
        var sent = 0
        val alice = Regtest.phone(client = Regtest.client { chain ->
            if (chain.request().url.isMutation) sent++
            val response = chain.proceed(chain.request())
            if (!changed) return@client response
            val body = response.body.string().replace(Regex("\"mintPubkey\":\\s*\"[0-9a-fA-F]{66}\""), "\"mintPubkey\":\"$OTHER_KEY\"")
            response.newBuilder().body(body.toResponseBody(response.body.contentType())).build()
        })
        alice.fund(5000)
        val note = alice.notes().single()
        changed = true
        sent = 0

        assertEquals(MintDetails.Reachability.KeyChanged, alice.wallet.mintDetails(HOST).reachability)
        assertEquals(NoteCheck.KeyChanged(HOST), alice.wallet.recheckNote(note.id))
        assertIs<ClaimPreview.Invalid>(alice.wallet.previewClaim(Regtest.note(1000)))
        assertTrue(alice.wallet.rotateNote(note.id) !is RotateResult.Rotated)
        assertTrue(alice.wallet.splitNote(note.id, 1_000_000) !is NoteActionResult.Done)
        // The whole note's amount: no split, so the payment goes straight to the melt.
        val preview = alice.wallet.previewPayment(Regtest.invoice(4999))
        if (preview is PayPreview.Ready) assertTrue(alice.wallet.pay(preview, 4_999_000) !is PayResult.Paid)
        assertEquals(0, sent, "no rotate, split or melt went out to a mint whose key changed")
        assertEquals(listOf(note.id), alice.notes().map { it.id }, "the note is untouched")

        // The pinned key again: everything works as before.
        changed = false
        assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(note.id))
        assertEquals(4_999_000, alice.balance())
    }

    @Test
    fun `the user can accept a changed signing key, and the mint is used again for sending and receiving`() = runBlocking<Unit> {
        var changed = false
        val alice = Regtest.phone(client = changedKey { changed })
        alice.fund(5000)
        val note = alice.notes().single()
        changed = true

        val details = alice.wallet.mintDetails(HOST)
        assertEquals(MintDetails.Reachability.KeyChanged, details.reachability)
        assertEquals(OTHER_KEY, details.presentedKey, "the key the user is shown, and accepts")
        assertTrue(details.mint?.mintPubkey != OTHER_KEY)
        assertEquals(false, alice.wallet.acceptMintKey(HOST, "03" + "11".repeat(32)), "not a key the mint presents")
        assertEquals(MintDetails.Reachability.KeyChanged, alice.wallet.mintDetails(HOST).reachability)

        assertTrue(alice.wallet.acceptMintKey(HOST, OTHER_KEY))
        val after = alice.wallet.mintDetails(HOST)
        assertEquals(MintDetails.Reachability.Reachable, after.reachability)
        assertEquals(OTHER_KEY, after.mint?.mintPubkey)
        assertEquals(null, after.presentedKey)
        assertEquals(setOf(HOST), alice.wallet.trustedHosts.first(), "the home mint stays trusted with its new key")

        // Sending, renewing and receiving work again.
        assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(note.id))
        val invoice = Regtest.invoice(1000)
        assertIs<PayResult.Paid>(alice.wallet.pay(assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoice)), 1_000_000))
        assertTrue(Regtest.settled(invoice))
        Regtest.pay(alice.address, 2000)
        assertEquals(1_999_000, alice.wallet.refresh().receivedMsat)
    }

    @Test
    fun `a trusted mint stays trusted once its new signing key is accepted`() = runBlocking<Unit> {
        var changed = false
        val alice = Regtest.phone(client = changedKey { changed })
        assertIs<ConnectResult.Connected>(alice.wallet.addMint(HOST2))
        alice.wallet.setMintTrusted(HOST2, true)
        changed = true
        assertEquals(MintDetails.Reachability.KeyChanged, alice.wallet.mintDetails(HOST2).reachability)
        assertTrue(alice.wallet.acceptMintKey(HOST2, OTHER_KEY))
        assertTrue(HOST2 in alice.wallet.trustedHosts.first())
        assertEquals(true, assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(Regtest.note(1000, second = true))).trusted)
    }

    /** A client to which, while [changed], every mint presents [OTHER_KEY] as its signing key. */
    private fun changedKey(changed: () -> Boolean) = Regtest.client { chain ->
        val response = chain.proceed(chain.request())
        if (!changed()) return@client response
        val body = response.body.string().replace(Regex("\"mintPubkey\":\\s*\"[0-9a-fA-F]{66}\""), "\"mintPubkey\":\"$OTHER_KEY\"")
        response.newBuilder().body(body.toResponseBody(response.body.contentType())).build()
    }

    private companion object {
        const val OTHER_KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }
}
