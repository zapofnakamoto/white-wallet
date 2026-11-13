package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.core.wallet.Regtest.OTHER_KEY

/** What the wallet knows about the real mints: fees, limits, the pinned signing key, and trust. */
class RegtestMintTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `connecting pins the signing key, and the details show fees, limits and the money there`() =
        runBlocking<Unit> {
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
    fun `the home mint is trusted by default, another one only once the user trusts it`() =
        runBlocking<Unit> {
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
    fun `a mint that presents another signing key gets nothing sent until it is the pinned key again`() =
        runBlocking<Unit> {
            // On the wire, the mint's answers carry a different signing key (the generator point, a valid key nobody pins).
            var changed = false
            var sent = 0
            var spends = 0
            val alice =
                Regtest.phone(
                    client =
                        Regtest.client { chain ->
                            if (chain.request().url.isMutation) sent++
                            if (chain.request().url.queryParameter("k1") != null) spends++
                            val response = chain.proceed(chain.request())
                            if (changed) Regtest.presentingOtherKey(response) else response
                        }
                )
            alice.fund(5000)
            val note = alice.notes().single()
            changed = true
            sent = 0
            spends = 0

            assertEquals(MintDetails.Reachability.KeyChanged, alice.wallet.mintDetails(HOST).reachability)
            assertEquals(NoteCheck.KeyChanged(HOST), alice.wallet.recheckNote(note.id))
            assertIs<ClaimPreview.KeyChanged>(alice.wallet.previewClaim(Regtest.note(1000)))
            assertTrue(alice.wallet.rotateNote(note.id) !is RotateResult.Rotated)
            assertTrue(alice.wallet.splitNote(note.id, 1_000_000) !is NoteActionResult.Done)
            // The whole note's amount: no split, so the payment goes straight to the melt.
            val preview = alice.wallet.previewPayment(Regtest.invoice(4999))
            if (preview is PayPreview.Ready) assertTrue(alice.wallet.pay(preview, 4_999_000) !is PayResult.Paid)
            assertEquals(0, sent, "no rotate, split or melt went out to a mint whose key changed")
            assertEquals(0, spends, "no spend went out at all, not even to look a note up")
            assertEquals(listOf(note.id), alice.notes().map { it.id }, "the note is untouched")

            // The pinned key again: everything works as before.
            changed = false
            assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(note.id))
            assertEquals(4_999_000, alice.balance())
        }

    @Test
    fun `a pinned mint that stops showing its signing key gets nothing sent, and has no key to accept`() =
        runBlocking<Unit> { withoutItsKey(Regtest::withoutSigningKey) }

    @Test
    fun `a pinned mint that shows a broken signing key is treated as one showing none`() =
        runBlocking<Unit> { withoutItsKey(Regtest::presentingBrokenKey) }

    /** The checks of a mint that presents another key, while its answers go through [strip], which takes the key away. */
    private suspend fun withoutItsKey(strip: (Response) -> Response) {
        var stripped = false
        var sent = 0
        var spends = 0
        val alice =
            Regtest.phone(
                client =
                    Regtest.client { chain ->
                        if (chain.request().url.isMutation) sent++
                        if (chain.request().url.queryParameter("k1") != null) spends++
                        val response = chain.proceed(chain.request())
                        if (stripped) strip(response) else response
                    }
            )
        alice.fund(5000)
        val note = alice.notes().single()
        val pinned = assertNotNull(alice.wallet.mintDetails(HOST).mint?.mintPubkey)
        stripped = true
        sent = 0
        spends = 0

        val details = alice.wallet.mintDetails(HOST)
        assertEquals(MintDetails.Reachability.KeyChanged, details.reachability)
        assertNull(details.presentedKey, "nothing to accept")
        assertEquals(false, alice.wallet.acceptMintKey(HOST, OTHER_KEY))
        assertEquals(OnboardResult.MintInvalid(MINT_KEY_CHANGED), assertIs<ConnectResult.Failed>(alice.wallet.addMint(HOST)).result)
        // Kept, never offered over Lightning: claimed at the mint once it shows its key again.
        val claim = alice.wallet.previewClaim(Regtest.note(1000))
        assertTrue(claim is ClaimPreview.KeyChanged || claim is ClaimPreview.Offline, "kept for its mint, not received another way: $claim")
        assertTrue(alice.wallet.rotateNote(note.id) !is RotateResult.Rotated)
        assertTrue(alice.wallet.splitNote(note.id, 1_000_000) !is NoteActionResult.Done)
        val preview = alice.wallet.previewPayment(Regtest.invoice(4999))
        if (preview is PayPreview.Ready) assertTrue(alice.wallet.pay(preview, 4_999_000) !is PayResult.Paid)
        alice.wallet.refresh()
        assertEquals(0, sent, "no rotate, split or melt went out to a mint without its key")
        assertEquals(0, spends, "no spend went out at all")
        assertEquals(listOf(note.id), alice.notes().map { it.id }, "the note is untouched")
        assertEquals(pinned, alice.wallet.mintDetails(HOST).mint?.mintPubkey, "the pinned key stays")

        // Its key again: everything works as before.
        stripped = false
        assertEquals(MintDetails.Reachability.Reachable, alice.wallet.mintDetails(HOST).reachability)
        assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(note.id))
    }

    @Test
    fun `the user can accept a changed signing key, and the mint is used again for sending and receiving`() =
        runBlocking<Unit> {
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
    fun `a trusted mint stays trusted once its new signing key is accepted`() =
        runBlocking<Unit> {
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

    @Test
    fun `a restored phone finds what the phrase has on a mint it adds, spends it, and is paid there from then on`() =
        runBlocking<Unit> {
            // Phone 1 has an address on the second mint, paid there, and moved a note over.
            val phone1 = Regtest.phone()
            val name = Regtest.name()
            Regtest.registered(phone1.wallet, phone1.wallet.addAddress(name, limit = 2, host = HOST2))
            Regtest.untilRegistered(name, HOST2)
            Regtest.pay("$name@$HOST2", 3000)
            phone1.fund(4000)
            val ids = phone1.notes().map { it.id }
            assertIs<MintMoveQuote.Ready>(phone1.wallet.quoteMintMove(ids, HOST2))
            assertIs<MintMoveResult.Moved>(phone1.wallet.moveToMint(ids, HOST2))
            val held = phone1.notes(HOST2)
            assertEquals(setOf(0, 2), held.map { it.purpose }.toSet())

            val phone2 = Regtest.restored(phone1)
            assertEquals(0, phone2.balance(), "the restore looked at the home mint only")
            assertIs<ConnectResult.Connected>(phone2.wallet.addMint(HOST2))
            assertEquals(held.map { it.id }.toSet(), phone2.notes(HOST2).map { it.id }.toSet())
            assertEquals(phone1.balance(), phone2.balance())

            // The notes are the phone's own, and the new key is past every key phone 1 used there.
            assertIs<RotateResult.Rotated>(phone2.wallet.rotateNote(held.first().id))

            // The phone doesn't know the name, and the payment arrives all the same.
            Regtest.pay("$name@$HOST2", 2000)
            phone2.refreshUntil { phone2.notes(HOST2).size == held.size + 1 }
        }

    /** A client to which, while [changed], every mint presents [OTHER_KEY] as its signing key. */
    private fun changedKey(changed: () -> Boolean) = Regtest.client { chain ->
        val response = chain.proceed(chain.request())
        if (changed()) Regtest.presentingOtherKey(response) else response
    }
}
