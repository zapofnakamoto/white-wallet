package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.byteVector64

private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

/** True for the 64-hex-character short forms LUD-25 accepts in place of a `cp1` (`h`) or a spend (preimage). */
public fun isShortForm(value: String): Boolean = HEX64.matches(value)

/** True if [x] is the x coordinate of a point on the curve. */
public fun isValidXOnly(x: ByteVector32): Boolean = Crypto.isPubKeyValid(byteArrayOf(2) + x.toByteArray())

/** `cp1<Q>`: a note's 32-byte x-only output key. */
public object Cp1 {
    private const val HRP = "cp"

    public fun encode(q: ByteVector32): String = Bech32m.encode(HRP, q.toByteArray())

    public fun decode(value: String): ByteVector32? {
        val (hrp, data) = Bech32m.decode(value) ?: return null
        if (hrp != HRP || data.size != 32) return null
        return data.byteVector32().takeIf { isValidXOnly(it) }
    }
}

/** `ck1<Q || sig>`: a key-path spend. */
public object Ck1 {
    private const val HRP = "ck"

    public fun encode(q: ByteVector32, signature: ByteVector64): String = Bech32m.encode(HRP, q.toByteArray() + signature.toByteArray())

    public fun decode(value: String): Pair<ByteVector32, ByteVector64>? {
        val (hrp, data) = Bech32m.decode(value) ?: return null
        if (hrp != HRP || data.size != 96) return null
        val q = data.copyOfRange(0, 32).byteVector32()
        if (!isValidXOnly(q)) return null
        return q to data.copyOfRange(32, 96).byteVector64()
    }
}

/** `cx1<chaincode || P>`: a share's extended public key (LUD-26), `P` compressed, in BIP-32's order; 65 bytes, 113 characters. */
public object Cx1 {
    private const val HRP = "cx"

    public fun encode(chainCode: ByteVector32, publicKey: ByteVector): String {
        require(publicKey.size() == 33) { "P must be a compressed public key" }
        return Bech32m.encode(HRP, chainCode.toByteArray() + publicKey.toByteArray())
    }

    /** Returns `(chaincode, P)`, or null for anything but a 65-byte `cx1` whose `P` is a valid compressed key. */
    public fun decode(value: String): Pair<ByteVector32, ByteVector>? {
        val (hrp, data) = Bech32m.decode(value) ?: return null
        if (hrp != HRP || data.size != 65) return null
        val publicKey = data.copyOfRange(32, 65)
        if (publicKey[0] != 2.toByte() && publicKey[0] != 3.toByte()) return null
        if (!Crypto.isPubKeyValid(publicKey)) return null
        return data.copyOfRange(0, 32).byteVector32() to ByteVector(publicKey)
    }
}

/** A decoded `cs1` certificate: the amount from its prefix and the 65-byte `r || s || recovery-id` signature. */
public data class Cs1(val amountMsat: Long, val signature: ByteVector) {
    init {
        require(amountMsat > 0) { "amount must be positive" }
        require(signature.size() == 65) { "signature must be 65 bytes" }
    }

    public fun encode(): String = Bech32m.encode("cs" + Bolt11Amount.encode(amountMsat), signature.toByteArray())

    public companion object {
        public fun decode(value: String): Cs1? {
            val (hrp, data) = Bech32m.decode(value) ?: return null
            // r || s || recovery-id, and a recovery id is 0 to 3.
            if (!hrp.startsWith("cs") || data.size != 65 || data[64].toInt() !in 0..3) return null
            val amount = Bolt11Amount.decode(hrp.removePrefix("cs")) ?: return null
            return Cs1(amount, ByteVector(data))
        }
    }
}

/**
 * `cw1<...>`: a script-path spend. Payload: `u32 locktime || u32 sequence || (u16 len || item)*` over script, control block, then witness
 * items.
 */
public data class Cw1(
    val lockTime: Long,
    val sequence: Long,
    val script: ByteVector,
    val controlBlock: ByteVector,
    /** What satisfies the script: signatures, preimages. The secret part of a spend. */
    @property:RevealsNoteSecret val witness: List<ByteVector>,
) {
    /** Debug text without the witness. */
    @OptIn(RevealsNoteSecret::class)
    override fun toString(): String =
        "Cw1(lockTime=$lockTime, sequence=$sequence, script=$script, controlBlock=$controlBlock, witness=${witness.size} items, redacted)"

    @RevealsNoteSecret
    public fun encode(): String {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Long) = repeat(4) { out.write((v ushr (24 - 8 * it)).toInt() and 0xff) }
        fun item(b: ByteVector) {
            require(b.size() <= 0xffff) { "witness item too large" }
            out.write(b.size() ushr 8)
            out.write(b.size() and 0xff)
            out.write(b.toByteArray())
        }
        u32(lockTime)
        u32(sequence)
        item(script)
        item(controlBlock)
        witness.forEach(::item)
        return Bech32m.encode(HRP, out.toByteArray())
    }

    public companion object {
        private const val HRP = "cw"

        public fun decode(value: String): Cw1? {
            val (hrp, data) = Bech32m.decode(value) ?: return null
            if (hrp != HRP || data.size < 8) return null
            fun u32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (data[at + i].toLong() and 0xff) }
            val items = mutableListOf<ByteVector>()
            var pos = 8
            while (pos < data.size) {
                if (pos + 2 > data.size) return null
                val len = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
                pos += 2
                if (pos + len > data.size) return null
                items += ByteVector(data.copyOfRange(pos, pos + len))
                pos += len
            }
            if (items.size < 2) return null
            return Cw1(lockTime = u32(0), sequence = u32(4), script = items[0], controlBlock = items[1], witness = items.drop(2))
        }
    }
}
