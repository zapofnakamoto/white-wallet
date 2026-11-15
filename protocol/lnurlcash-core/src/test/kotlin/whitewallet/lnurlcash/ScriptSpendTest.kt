@file:OptIn(RevealsNoteSecret::class)

package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_CHECKLOCKTIMEVERIFY
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import whitewallet.testkit.Vectors
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str
import whitewallet.testkit.strOrNull

/** What the wallet tells from a script-path spend on its own, without reading its script or asking the mint. */
class ScriptSpendTest {
    private val domain = "mint.example"
    private val key = PrivateKey(ByteArray(32) { 1 })
    private val other = PrivateKey(ByteArray(32) { 2 })
    private val t = 1_700_000_000L
    private val day = (1L shl 22) or 169 // 169 × 512 s, a little over a day

    @Test
    fun `a lock until a date verifies when the spend claims that date, in either order of the script`() {
        for (leaf in listOf(cltv(t, key), cltvReordered(t, key))) {
            val spend = ScriptNote(leaf).spend(domain, key, lockTime = t)
            assertTrue(spend.verify(domain))
            assertEquals(t, spend.lockedUntilSeconds)
            assertNull(spend.lockedForSeconds)
            assertTrue(spend.isValidLockClaim)
            assertFalse(spend.hasOtherPaths)
            assertTrue(ScriptNote(leaf).spend(domain, key, lockTime = t + 3600).verify(domain), "a later date satisfies it too")
        }
    }

    @Test
    fun `a lock until a date fails when the spend claims an earlier one, or none`() {
        val note = ScriptNote(cltv(t, key))
        assertFalse(note.spend(domain, key, lockTime = t - 1).verify(domain))
        assertFalse(note.spend(domain, key, lockTime = 0).verify(domain))
        assertFalse(
            note.spend(domain, key, lockTime = t, sequence = CanonicalSpend.DEFAULT_SEQUENCE).verify(domain),
            "a final sequence disables the lock",
        )
    }

    @Test
    fun `a wait after crediting verifies when the spend claims it, and its length is read from the spend`() {
        val note = ScriptNote(csv(day, key))
        val spend = note.spend(domain, key, sequence = day)
        assertTrue(spend.verify(domain))
        assertEquals(169 * 512L, spend.lockedForSeconds)
        assertNull(spend.lockedUntilSeconds)
        assertTrue(spend.isValidLockClaim)
        assertFalse(note.spend(domain, key, sequence = (1L shl 22) or 168).verify(domain), "a shorter wait")
    }

    @Test
    fun `block heights and waits in blocks are not a time the mint honours`() {
        val height = ScriptNote(cltv(800_000, key)).spend(domain, key, lockTime = 800_000)
        assertTrue(height.verify(domain), "the script itself is satisfied")
        assertFalse(height.isValidLockClaim)

        val blocks = ScriptNote(csv(144, key)).spend(domain, key, sequence = 144)
        assertTrue(blocks.verify(domain))
        assertNull(blocks.lockedForSeconds)
        assertFalse(blocks.isValidLockClaim)
    }

    @Test
    fun `a spend signed by the wrong key, for another mint, or tampered with does not verify`() {
        val note = ScriptNote(checksig(key))
        assertTrue(note.spend(domain, key).verify(domain))
        assertFalse(note.spend(domain, other).verify(domain), "wrong key")
        assertFalse(note.spend("other.example", key).verify(domain), "signed for another mint")
        val good = note.spend(domain, key)
        val tampered = good.cw1.copy(witness = listOf(ByteVector(ByteArray(64))))
        assertFalse(assertIs<Spend.Script>(Spend.parse(tampered.encode())).verify(domain))
        val retimed = good.cw1.copy(lockTime = t)
        assertFalse(assertIs<Spend.Script>(Spend.parse(retimed.encode())).verify(domain), "the signature commits to the claimed time")
    }

    @Test
    fun `a note with other leaves or a real internal key may be spent another way`() {
        val two = ScriptNote(checksig(key), checksig(other))
        val a = two.spend(domain, key, leaf = 0)
        val b = two.spend(domain, other, leaf = 1)
        assertEquals(a.q, b.q)
        assertTrue(a.verify(domain) && b.verify(domain))
        assertTrue(a.hasOtherPaths && b.hasOtherPaths)

        val keyed = ScriptNote(checksig(key), internal = other.xOnlyPublicKey()).spend(domain, key)
        assertTrue(keyed.verify(domain))
        assertTrue(keyed.hasOtherPaths)
    }

    @Test
    fun `a key that is neither empty nor 32 bytes is never signed for`() {
        // BIP-342 counts any signature for such a key as valid, a soft-fork upgrade; LUD-25 has the wallet refuse it.
        val note = ScriptNote(leaf(OP_PUSHDATA(key.publicKey().value), OP_CHECKSIG))
        assertFalse(note.spendWith(listOf(ByteVector("01"))).verify(domain))
    }

    @Test
    fun `an OP_SUCCESS leaf does not verify`() {
        assertFalse(ScriptNote(ByteVector("50")).spendWith(listOf(ByteVector("01"))).verify(domain), "OP_SUCCESS80")
    }

    @Test
    fun `a leaf of an unknown version does not verify`() {
        assertFalse(ScriptNote(ByteVector("51"), leafVersion = 0xc2).spendWith(listOf(ByteVector("01"))).verify(domain))
    }

    @Test
    fun `lnurlcash-conformance's script spends keep their verdicts`() {
        val v = Vectors.conformance("spends")
        for (leaf in v.obj("tree").objects("leaves")) {
            val spend = assertIs<Spend.Script>(Spend.parse(leaf.str("cw1")))
            assertEquals(leaf.str("verdict") == "accept", spend.verify(domain), "${leaf.str("verdict")}: ${leaf.strOrNull("reason")}")
        }
        val checksig = v.obj("checksig")
        for (spend in checksig.objects("spends")) {
            assertTrue(assertIs<Spend.Script>(Spend.parse(spend.str("cw1"))).verify(checksig.str("domain")), spend.str("cw1"))
        }
    }

    @Test
    fun `a plain bearer note is still read as one, not as a script note`() {
        assertIs<Spend.Bearer>(Spend.parse(BearerNote.cw1(ByteVector32(ByteArray(32) { 9 })).encode()))
    }

    private fun leaf(vararg ops: ScriptElt): ByteVector = Script.write(ops.toList()).byteVector()

    private fun checksig(k: PrivateKey) = leaf(OP_PUSHDATA(k.xOnlyPublicKey()), OP_CHECKSIG)

    private fun cltv(t: Long, k: PrivateKey) =
        leaf(OP_PUSHDATA(Script.encodeNumber(t)), OP_CHECKLOCKTIMEVERIFY, OP_DROP, OP_PUSHDATA(k.xOnlyPublicKey()), OP_CHECKSIG)

    private fun cltvReordered(t: Long, k: PrivateKey) =
        leaf(OP_PUSHDATA(k.xOnlyPublicKey()), OP_CHECKSIGVERIFY, OP_PUSHDATA(Script.encodeNumber(t)), OP_CHECKLOCKTIMEVERIFY)

    private fun csv(s: Long, k: PrivateKey) =
        leaf(OP_PUSHDATA(Script.encodeNumber(s)), OP_CHECKSEQUENCEVERIFY, OP_DROP, OP_PUSHDATA(k.xOnlyPublicKey()), OP_CHECKSIG)

    /** A note of one or two leaves of [leafVersion] under [internal] (NUMS by default), and spends of its leaves. */
    private class ScriptNote(
        vararg val leaves: ByteVector,
        val internal: XonlyPublicKey = XonlyPublicKey(BearerNote.NUMS),
        val leafVersion: Int = BearerNote.LEAF_VERSION,
    ) {
        private val hashes = leaves.map { BearerNote.tapleafHash(it, leafVersion) }
        private val output = internal.outputKey(if (hashes.size == 1) hashes[0] else branch(hashes[0], hashes[1]))
        val q: ByteVector32 = output.first.value

        /** A spend of leaf [leaf] signed by [signer], claiming [lockTime] and [sequence] (below final, so a lock is checked). */
        fun spend(
            domain: String,
            signer: PrivateKey,
            leaf: Int = 0,
            lockTime: Long = 0,
            sequence: Long = CanonicalSpend.DEFAULT_SEQUENCE - 1,
        ): Spend.Script {
            val sig =
                CanonicalSpend.transaction(domain, lockTime, sequence)
                    .signInputTaprootScriptPath(signer, 0, listOf(CanonicalSpend.spentOutput(q)), SigHash.SIGHASH_DEFAULT, hashes[leaf])
            return spendWith(listOf(sig), leaf, lockTime, sequence)
        }

        /** A spend of leaf [leaf] whose witness is [witness], as given. */
        fun spendWith(
            witness: List<ByteVector>,
            leaf: Int = 0,
            lockTime: Long = 0,
            sequence: Long = CanonicalSpend.DEFAULT_SEQUENCE - 1,
        ): Spend.Script {
            val siblings = hashes.filterIndexed { i, _ -> i != leaf }.flatMap { it.toByteArray().toList() }.toByteArray()
            val control =
                (byteArrayOf((leafVersion or if (output.second) 1 else 0).toByte()) + internal.value.toByteArray() + siblings).byteVector()
            val spend = assertIs<Spend.Script>(Spend.parse(Cw1(lockTime, sequence, leaves[leaf], control, witness).encode()))
            assertEquals(q, spend.q)
            return spend
        }

        private fun branch(a: ByteVector32, b: ByteVector32): ByteVector32 {
            val (first, second) = if (a.toHex() < b.toHex()) a to b else b to a
            return Crypto.taggedHash(first.toByteArray() + second.toByteArray(), "TapBranch")
        }
    }
}
