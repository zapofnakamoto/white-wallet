package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.CanonicalSpend
import whitewallet.lnurlcash.Cw1
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Notes with more than one script path, on the real mint: one note, several valid spends. */
class RegtestScriptNoteTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `a note with two script paths can't be combined with itself`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val note = TwoPathNote()
        alice.lockTo(note.q)
        val withdrawUrl = assertNotNull(alice.store.mint(HOST)).withdrawUrl
        val domain = spendDomain(HOST)

        // Two different spends of the one note, each valid on its own.
        val (a, b) = note.spendA(domain) to note.spendB(domain)
        assertNotEquals(a.k1, b.k1)
        assertEquals(note.q, a.q)
        assertEquals(note.q, b.q)
        assertEquals(1_999_000, alice.wallet.client.noteInfo(withdrawUrl, a.k1).maxWithdrawableMsat)
        assertEquals(1_999_000, alice.wallet.client.noteInfo(withdrawUrl, b.k1).maxWithdrawableMsat)

        // Together in one merge, the mint must see the same note twice and refuse.
        val output = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)).xOnlyPublicKey().value
        val callback = alice.wallet.client.lookup(withdrawUrl, note.q).callback
        val outcome = alice.wallet.client.mutate(MutationType.Merge, Requests.merge(callback, listOf(a.k1, b.k1), output))
        // It says the second spend's note is spent, as for the same k1 twice: it knows the note by Q, not by k1.
        assertEquals(RejectionKind.Spent, assertIs<MutationOutcome.Rejected>(outcome, "one note counted twice").kind)
        assertEquals(1_999_000, alice.wallet.client.lookup(withdrawUrl, note.q).maxWithdrawableMsat, "nothing burned")
        val minted = assertFailsWith<NoteLookupError>("nothing minted") { alice.wallet.client.lookup(withdrawUrl, output) }
        assertEquals(RejectionKind.NoteUnknown, minted.kind, minted.reason)

        // Path B alone spends it: the refusal was about the duplicate, not a bad spend.
        val info = alice.wallet.client.lookup(withdrawUrl, note.q)
        assertIs<MutationResult.Done>(NoteMutator(alice.wallet).rotateIn(alice.wallet.activeId(), HOST, withdrawUrl, info, b, info.maxWithdrawableMsat, sent = null))
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a note with two script paths is claimed through either, and the preview says it may be spent another way`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val note = TwoPathNote()
        alice.lockTo(note.q)
        val withdrawUrl = assertNotNull(alice.store.mint(HOST)).withdrawUrl
        val link = NoteLink(withdrawUrl, note.spendA(spendDomain(HOST)), null, null).encode()

        val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(link))
        assertTrue(preview.hasOtherPaths)
        assertEquals(ClaimResult.Claimed(1_999_000, true), alice.wallet.claim(preview))
        assertEquals(1_999_000, alice.balance())
        // The other path's link is the same note: known as claimed.
        assertIs<ClaimPreview.AlreadyInWallet>(alice.wallet.previewClaim(NoteLink(withdrawUrl, note.spendB(spendDomain(HOST)), null, null).encode()))
    }

    /** `<pkA> OP_CHECKSIG` and `<pkB> OP_CHECKSIG`, two leaves under the NUMS key: either key alone spends it. */
    private class TwoPathNote {
        private val keyA = key()
        private val keyB = key()
        private val leafA = leaf(keyA)
        private val leafB = leaf(keyB)
        private val hashA = BearerNote.tapleafHash(leafA)
        private val hashB = BearerNote.tapleafHash(leafB)
        private val output = XonlyPublicKey(BearerNote.NUMS).outputKey(branch(hashA, hashB))
        val q: ByteVector32 = output.first.value

        fun spendA(domain: String): Spend.Script = spend(domain, keyA, leafA, sibling = hashB)

        fun spendB(domain: String): Spend.Script = spend(domain, keyB, leafB, sibling = hashA)

        private fun spend(domain: String, key: PrivateKey, leaf: ByteVector, sibling: ByteVector32): Spend.Script {
            val sig = CanonicalSpend.transaction(domain)
                .signInputTaprootScriptPath(key, 0, listOf(CanonicalSpend.spentOutput(q)), SigHash.SIGHASH_DEFAULT, BearerNote.tapleafHash(leaf))
            val control = (byteArrayOf((BearerNote.LEAF_VERSION or if (output.second) 1 else 0).toByte()) + BearerNote.NUMS.toByteArray() + sibling.toByteArray()).byteVector()
            val cw1 = Cw1(0, CanonicalSpend.DEFAULT_SEQUENCE, leaf, control, listOf(sig))
            return assertIs<Spend.Script>(Spend.parse(cw1.encode())).also { assertTrue(it.verify(domain), "checks out locally, as at the mint") }
        }

        private companion object {
            fun key() = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes))

            fun leaf(key: PrivateKey): ByteVector = Script.write(listOf(OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG)).byteVector()

            /** BIP-341 TapBranch: the two child hashes, smaller first. */
            fun branch(a: ByteVector32, b: ByteVector32): ByteVector32 {
                val (first, second) = if (a.toHex() < b.toHex()) a to b else b to a
                return Crypto.taggedHash(first.toByteArray() + second.toByteArray(), "TapBranch")
            }
        }
    }

    @Test
    fun `a note whose leaf is longer than 252 bytes is claimed through the wallet`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val note = LockedNote.longLeaf()
        alice.lockTo(note.q)
        val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(note.link(HOST, assertNotNull(alice.store.mint(HOST)).withdrawUrl)))
        assertEquals(ClaimResult.Claimed(1_999_000, true), alice.wallet.claim(preview))
        assertEquals(1_999_000, alice.balance())
    }
}
