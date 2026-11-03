package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A spend comes from whoever made the link: anything in it may be wrong. Whatever it holds, reading and checking it
 * says "no spend" or "doesn't verify", and never throws.
 */
class MalformedSpendTest {
    private val domain = "mint.example"
    private val key = PrivateKey(ByteArray(32) { 1 })

    @Test
    fun `a leaf of 253 bytes or more is hashed with its length as a compact size, as BIP-341 says`() {
        for (size in listOf(0, 1, 252, 253, 254, 0xffff)) {
            val script = ByteVector(ByteArray(size) { 0x51 })
            assertEquals(ScriptTree.Leaf(script, BearerNote.LEAF_VERSION).hash(), BearerNote.tapleafHash(script), "$size bytes")
        }
    }

    @Test
    fun `a spend of a long leaf is read and checked like any other`() {
        // 300 bytes of data dropped, then a signature check: a leaf well over 252 bytes.
        val leaf = Script.write(listOf(OP_PUSHDATA(ByteArray(300) { 7 }), OP_DROP, OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG)).byteVector()
        val hash = ScriptTree.Leaf(leaf, BearerNote.LEAF_VERSION).hash()
        val (q, odd) = XonlyPublicKey(BearerNote.NUMS).outputKey(hash)
        val sequence = CanonicalSpend.DEFAULT_SEQUENCE - 1
        val sig = CanonicalSpend.transaction(domain, 0, sequence)
            .signInputTaprootScriptPath(key, 0, listOf(CanonicalSpend.spentOutput(q.value)), SigHash.SIGHASH_DEFAULT, hash)
        val control = (byteArrayOf((BearerNote.LEAF_VERSION or if (odd) 1 else 0).toByte()) + BearerNote.NUMS.toByteArray()).byteVector()
        val spend = assertIs<Spend.Script>(Spend.parse(Cw1(0, sequence, leaf, control, listOf(sig)).encode()))
        assertEquals(q.value, spend.q)
        assertTrue(spend.verify(domain))
    }

    @Test
    fun `a broken spend is no spend, or one that doesn't verify, and never throws`() {
        val nums = BearerNote.NUMS.toByteArray()
        val control = byteArrayOf(BearerNote.LEAF_VERSION.toByte()) + nums
        val checksig = Script.write(listOf(OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG))
        val broken = mapOf(
            "too short for its times" to raw(ByteArray(5)),
            "no control block" to raw(times() + item(checksig)),
            "an item cut off" to raw(times() + item(checksig) + byteArrayOf(0, 40, 1, 2)),
            "a control block of 10 bytes" to cw1(checksig, ByteArray(10), ByteArray(64)),
            "a control block between two lengths" to cw1(checksig, control + ByteArray(5), ByteArray(64)),
            "an internal key off the curve" to cw1(checksig, byteArrayOf(BearerNote.LEAF_VERSION.toByte()) + ByteArray(32) { -1 }, ByteArray(64)),
            "an empty script" to cw1(ByteArray(0), control, ByteArray(64)),
            "no witness" to cw1(checksig, control),
            "a witness item of 65535 bytes" to cw1(checksig, control, ByteArray(0xffff)),
            "a thousand witness items" to cw1(checksig, control, *Array(1000) { ByteArray(1) }),
            "a script of 65535 bytes" to cw1(ByteArray(0xffff) { 0x51 }, control, ByteArray(64)),
            "a lock time and wait of all ones" to raw(ByteArray(8) { -1 } + item(checksig) + item(control) + item(ByteArray(64))),
        )
        for ((what, k1) in broken) {
            val spend = runCatching { Spend.parse(k1) }.getOrElse { throw AssertionError("$what: parse threw $it", it) }
            if (spend is Spend.Script) {
                val verifies = runCatching { spend.hasOtherPaths; spend.lockedForSeconds; spend.isValidLockClaim && spend.verify(domain) }
                    .getOrElse { throw AssertionError("$what: checking threw $it", it) }
                assertFalse(verifies, "$what verifies")
            }
        }
    }

    private fun times(lockTime: Long = 0, sequence: Long = CanonicalSpend.DEFAULT_SEQUENCE - 1): ByteArray =
        listOf(lockTime, sequence).flatMap { v -> (0 until 4).map { ((v ushr (24 - 8 * it)) and 0xff).toByte() } }.toByteArray()

    private fun item(b: ByteArray): ByteArray = byteArrayOf((b.size ushr 8).toByte(), b.size.toByte()) + b

    private fun raw(data: ByteArray): String = Bech32m.encode("cw", data)

    private fun cw1(script: ByteArray, control: ByteArray, vararg witness: ByteArray): String =
        raw(times() + item(script) + item(control) + witness.fold(ByteArray(0)) { acc, w -> acc + item(w) })
}
