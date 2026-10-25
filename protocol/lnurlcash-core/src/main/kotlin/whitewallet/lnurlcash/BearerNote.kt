package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32
import fr.acinq.secp256k1.Hex

/** A plain bearer note: a hash-lock leaf `OP_SHA256 <h> OP_EQUAL` under BIP-341's NUMS internal key. */
public object BearerNote {
    public const val LEAF_VERSION: Int = 0xc0

    /** BIP-341's nothing-up-my-sleeve point, so a bearer note has no key path. */
    public val NUMS: ByteVector32 =
        ByteVector32("50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0")

    public fun leaf(h: ByteVector32): ByteVector = (byteArrayOf(0xa8.toByte(), 0x20) + h.toByteArray() + 0x87.toByte()).byteVector()

    /** Returns `h` if [script] is exactly a bearer hash-lock leaf. */
    public fun hashOf(script: ByteVector): ByteVector32? {
        val b = script.toByteArray()
        if (b.size != 35 || b[0] != 0xa8.toByte() || b[1] != 0x20.toByte() || b[34] != 0x87.toByte()) return null
        return b.copyOfRange(2, 34).byteVector32()
    }

    public fun tapleafHash(script: ByteVector, leafVersion: Int = LEAF_VERSION): ByteVector32 {
        val len = script.size()
        require(len < 0xfd) { "leaf too large" }
        return Crypto.taggedHash(byteArrayOf(leafVersion.toByte(), len.toByte()) + script.toByteArray(), "TapLeaf")
    }

    /** Output key `Q` and parity for the note locked to `h`. */
    public fun outputKey(h: ByteVector32): Pair<ByteVector32, Boolean> {
        val (q, odd) = XonlyPublicKey(NUMS).outputKey(tapleafHash(leaf(h)))
        return q.value to odd
    }

    public fun q(h: ByteVector32): ByteVector32 = outputKey(h).first

    public fun hash(preimage: ByteVector32): ByteVector32 = Crypto.sha256(preimage.toByteArray()).byteVector32()

    public fun controlBlock(h: ByteVector32): ByteVector {
        val odd = outputKey(h).second
        return (byteArrayOf((LEAF_VERSION or if (odd) 1 else 0).toByte()) + NUMS.toByteArray()).byteVector()
    }

    /** The full `cw1` spend for [preimage]: the leaf, its control block, witness `[preimage]`, no time claim. */
    public fun cw1(preimage: ByteVector32): Cw1 {
        val h = hash(preimage)
        return Cw1(0, CanonicalSpend.DEFAULT_SEQUENCE, leaf(h), controlBlock(h), listOf(preimage))
    }

    /** Resolves a `cp1` or the `cp1` short form (hex `h`) to `Q`. */
    public fun parseOutput(value: String): ByteVector32? =
        if (isShortForm(value)) q(ByteVector32(Hex.decode(value))) else Cp1.decode(value)
}
