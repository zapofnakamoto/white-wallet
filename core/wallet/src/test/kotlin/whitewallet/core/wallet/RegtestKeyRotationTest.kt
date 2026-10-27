package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import fr.acinq.bitcoin.PublicKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST3
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * A mint that really changes its signing key (the third mint, moved to another Lightning node with its notes): notes
 * signed with the old key and with the new one keep working once the user accepts the new key, on a restored phone too.
 */
class RegtestKeyRotationTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `notes signed before and after the mint changed its key keep working, also on a restored phone`() = runBlocking<Unit> {
        val alice = Regtest.phone(host = HOST3)
        alice.fund(2000, 3000)
        val (old, toRenew) = alice.notes(HOST3).sortedBy { it.amountMsat }
        assertTrue(old.certificate != null && toRenew.certificate != null, "both signed with the first key")
        val link = Regtest.note3(1000)
        val firstKey = assertNotNull(alice.wallet.mintDetails(HOST3).mint?.mintPubkey)

        val newKey = Regtest.rotateMint3()
        assertTrue(newKey != firstKey)
        val details = alice.wallet.mintDetails(HOST3)
        assertEquals(MintDetails.Reachability.KeyChanged, details.reachability)
        assertEquals(newKey, details.presentedKey)
        assertTrue(alice.wallet.acceptMintKey(HOST3, newKey))

        // Signed with the new key: a renewed note, a claimed link minted before the change, and a new payment.
        val renewed = assertIs<RotateResult.Rotated>(alice.wallet.rotateNote(toRenew.id))
        assertTrue(renewed.certified, "the renewed note is signed with the new key")
        assertIs<ClaimResult.Claimed>(alice.wallet.claim(assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))))
        alice.fund(4000)
        // Signed with the old key: still there, still the user's, and the mint now vouches for it with the new key.
        assertEquals(NoteCheck.Ok(old.amountMsat, certified = true), alice.wallet.recheckNote(old.id))
        val held = alice.notes(HOST3)
        assertEquals(4, held.size)

        // A fresh install restores every note, and pins the key the mint presents now: it has no older one to compare.
        val store = InMemoryWalletStore()
        val phone2 = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val restored = assertIs<RestoreResult.Restored>(phone2.restore(alice.wallet.recoveryPhrase().joinToString(" "), HOST3, "Main"))
        assertEquals(held.size, restored.notes)
        assertEquals(alice.balance(), phone2.balanceMsat.first())
        assertEquals(newKey, phone2.mintDetails(HOST3).mint?.mintPubkey)
        assertTrue(store.allNotes().all { it.certificate != null }, "every note, the old one too, verified against the new key")

        // Every note spends there, the old one included: an invoice of exactly its amount is paid with it.
        val invoice = Regtest.invoiceForMint3(old.amountMsat / 1000)
        assertIs<PayResult.Paid>(phone2.pay(assertIs<PayPreview.Ready>(phone2.previewPayment(invoice)), old.amountMsat))
        assertTrue(Regtest.settled(invoice))
        assertEquals(NoteState.Spent, store.note(old.id)?.state, "paid with the old note")
        val rest = store.allNotes().filter { it.state == NoteState.Outstanding }
        assertEquals(3, rest.size)
        assertIs<NoteActionResult.Done>(phone2.combineNotes(rest.map { it.id }))
    }

    @Test
    fun `a note from before the key change, shared once the new key is accepted, is verified for its recipient`() = runBlocking<Unit> {
        val alice = Regtest.phone(host = HOST3)
        alice.fund(2000)
        val note = alice.notes(HOST3).single()
        val newKey = Regtest.rotateMint3()
        assertTrue(alice.wallet.acceptMintKey(HOST3, newKey))

        // The link carries the note's certificate; the recipient checks it against the key the mint presents now.
        val link = assertNotNull(assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(note.id)).note.link)
        val bob = Regtest.phone()
        val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
        assertTrue(preview.certified, "the amount is verified")
        assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))
    }

    @Test
    fun `a link shared before the key change fails its certificate check for the recipient, and claiming renews the note`() = runBlocking<Unit> {
        // Links are meant to be short-lived: a certificate of a key the mint no longer signs with must not pass.
        val alice = Regtest.phone(host = HOST3)
        alice.fund(2000)
        val oldKey = Regtest.mintKey(HOST3)
        val link = assertNotNull(assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(alice.notes(HOST3).single().id)).note.link)
        val newKey = PublicKey.fromHex(Regtest.rotateMint3())

        val bob = Regtest.phone()
        val preview = assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))
        val certificate = assertNotNull(preview.link.certificate?.let(Cs1::decode), "the link carries a certificate")
        assertTrue(Certificates.verify(certificate, preview.link.spend.q, PublicKey.fromHex(oldKey)), "a genuine one, of the old key")
        assertEquals(preview.info.mintPubkey, newKey)
        assertEquals(false, preview.certified, "it doesn't count: the mint signs with another key now")

        // Claiming still works, and the note lands at Bob's own key, signed with the new key; the link is spent.
        val claimed = assertIs<ClaimResult.Claimed>(bob.wallet.claim(preview))
        assertTrue(claimed.certified)
        assertIs<ClaimPreview.AlreadySpent>(bob.wallet.previewClaim(link))
    }

    @Test
    fun `a mint the brand trusts is trusted again only by the user after its key changed, and its notes keep working`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID),
            collectDefaults = CollectDefaults(offered = false), brandTrusted = listOf(MintKey(HOST3, Regtest.mintKey(HOST3))))
        assertIs<OnboardResult.Ready>(wallet.onboard(Regtest.name(), HOST))
        val first = assertIs<ClaimPreview.Ready>(wallet.previewClaim(Regtest.note3(2000)))
        assertEquals(true, first.trusted, "trusted by the brand")
        assertIs<ClaimResult.Claimed>(wallet.claim(first))
        val old = store.allNotes().single { it.host == HOST3 && it.state == NoteState.Outstanding }

        val newKey = Regtest.rotateMint3()
        assertTrue(wallet.acceptMintKey(HOST3, newKey))
        val link = Regtest.note3(1000)
        assertEquals(false, assertIs<ClaimPreview.Ready>(wallet.previewClaim(link)).trusted, "the brand trusts the old key only")
        assertIs<RotateResult.Rotated>(wallet.rotateNote(old.id), "the note claimed before the change works")

        wallet.setMintTrusted(HOST3, true)
        assertEquals(true, assertIs<ClaimPreview.Ready>(wallet.previewClaim(link)).trusted)
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(link))))
    }
}
