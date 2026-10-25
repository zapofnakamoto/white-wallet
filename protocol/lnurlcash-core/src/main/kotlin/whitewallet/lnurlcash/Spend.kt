package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.secp256k1.Hex

/** A spend: the bearer secret that goes wherever LUD-03 puts a `k1`. */
public sealed interface Spend {
    /** The note's output key `Q`, its id at the service. */
    public val q: ByteVector32

    /** The exact string to send as `k1`. */
    public val k1: String

    /** A `ck1`: BIP-340 signature by `Q` over the canonical spend sighash for one domain. */
    public data class KeyPath(override val q: ByteVector32, val signature: ByteVector64) : Spend {
        override val k1: String get() = Ck1.encode(q, signature)

        public fun verify(domain: String): Boolean =
            Crypto.verifySignatureSchnorr(CanonicalSpend.keyPathSighash(domain, q), signature, XonlyPublicKey(q))
    }

    /** A plain bearer note, sent in its `k1` short form (hex preimage) or as the equivalent `cw1`. */
    public data class Bearer(val preimage: ByteVector32, val asCw1: Boolean = false) : Spend {
        val h: ByteVector32 get() = BearerNote.hash(preimage)
        override val q: ByteVector32 get() = BearerNote.q(h)
        override val k1: String get() = if (asCw1) BearerNote.cw1(preimage).encode() else preimage.toHex()
    }

    /** Any other script-path spend. This wallet can identify its note but not verify it. */
    public data class Script(override val q: ByteVector32, val cw1: Cw1, override val k1: String) : Spend

    public companion object {
        /** Signs a key-path spend of the note owned by [secretKey] at [domain] (all-zero aux_rand, so it is reproducible). */
        public fun keyPath(secretKey: PrivateKey, domain: String): KeyPath {
            val q = secretKey.xOnlyPublicKey().value
            val sig = Crypto.signSchnorr(CanonicalSpend.keyPathSighash(domain, q), secretKey, null, ByteVector32.Zeroes)
            return KeyPath(q, sig)
        }

        /** Parses a `k1`: a `ck1`, a `cw1`, or a 64-hex bearer preimage. Returns null for anything else. */
        public fun parse(k1: String): Spend? {
            if (isShortForm(k1)) return Bearer(ByteVector32(Hex.decode(k1)))
            Ck1.decode(k1)?.let { (q, sig) -> return KeyPath(q, sig) }
            val cw1 = Cw1.decode(k1) ?: return null
            val q = ScriptPath.outputKey(cw1) ?: return null
            val h = BearerNote.hashOf(cw1.script)
            val preimage = cw1.witness.singleOrNull()
            val isPlainBearer = h != null && preimage != null && preimage.size() == 32 &&
                cw1.lockTime == 0L && cw1.sequence == CanonicalSpend.DEFAULT_SEQUENCE &&
                BearerNote.hash(preimage.toByteArray().byteVector32()) == h && BearerNote.q(h) == q
            return if (isPlainBearer) {
                Bearer(preimage.toByteArray().byteVector32(), asCw1 = true)
            } else {
                Script(q, cw1, k1)
            }
        }
    }
}

/** Recomputes a script-path spend's output key from its leaf and control block (BIP-341). */
internal object ScriptPath {
    fun outputKey(cw1: Cw1): ByteVector32? {
        val control = cw1.controlBlock.toByteArray()
        if (control.size < 33 || (control.size - 33) % 32 != 0) return null
        val leafVersion = control[0].toInt() and 0xfe
        val odd = (control[0].toInt() and 1) == 1
        val internal = control.copyOfRange(1, 33).byteVector32()
        if (!isValidXOnly(internal)) return null
        var node = BearerNote.tapleafHash(cw1.script, leafVersion)
        for (i in 33 until control.size step 32) {
            val sibling = control.copyOfRange(i, i + 32)
            val a = node.toByteArray()
            val ordered = if (compare(a, sibling) < 0) a + sibling else sibling + a
            node = Crypto.taggedHash(ordered, "TapBranch")
        }
        val (q, parity) = XonlyPublicKey(internal).outputKey(node)
        return if (parity == odd) q.value else null
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return 0
    }
}
