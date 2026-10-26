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
        val home = alice.wallet.serviceDetails(HOST)
        assertEquals(ServiceDetails.Reachability.Reachable, home.reachability)
        assertTrue(home.isHome)
        val service = assertNotNull(home.service)
        assertEquals(1_000, service.feeBaseMsat)
        assertNotNull(service.mintPubkey)
        assertEquals(2_998_000, home.balanceMsat)
        assertEquals(2, home.noteCount)

        assertIs<ConnectResult.Connected>(alice.wallet.connectService(HOST2))
        val second = alice.wallet.serviceDetails(HOST2)
        assertEquals(2_000, second.service?.feeBaseMsat, "the second mint's 2 sat fee")
        assertTrue(second.service?.mintPubkey != service.mintPubkey, "each mint signs with its own key")
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

        assertEquals(ServiceDetails.Reachability.KeyChanged, alice.wallet.serviceDetails(HOST).reachability)
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

    private companion object {
        const val OTHER_KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }
}
