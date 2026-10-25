package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** LUD-26 derivation purposes; each has its own index counter. */
public enum class NotePurpose(public val value: Int) {
    Wallet(0),
    Change(1),
    LightningAddress(2),
}

/**
 * One service's note branch (LUD-26): `m/139'/d1/d2/d3/d4` with `d` from HMAC-SHA256 of the host under the
 * hashing key at `m/139'/0`. Note keys are derived non-hardened, so a `cx1` holder can enumerate but never spend.
 */
public class CashBranch private constructor(
    /** x-only branch key `P`. */
    public val pubkey: ByteVector32,
    public val chainCode: ByteVector32,
    private val secret: PrivateKey?,
) {
    public val isWatchOnly: Boolean get() = secret == null

    public fun cx1(): String = Cx1.encode(pubkey, chainCode)

    public fun tweak(purpose: NotePurpose, index: Long): PrivateKey {
        require(index in 0..0xffffffffL) { "index must be a uint32" }
        val data = pubkey.toByteArray() + chainCode.toByteArray() + ser32(purpose.value.toLong()) + ser32(index)
        val t = BigInteger(1, Crypto.taggedHash(data, "LNURLcash/derive").toByteArray()).mod(N)
        return PrivateKey(t.toByteArray32())
    }

    /** The note's `Q`, computable from the `cx1` alone. */
    public fun notePubkey(purpose: NotePurpose, index: Long): ByteVector32 =
        XonlyPublicKey(XonlyPublicKey(pubkey).publicKey + tweak(purpose, index).publicKey()).value

    public fun noteSecretKey(purpose: NotePurpose, index: Long): PrivateKey {
        val p = checkNotNull(secret) { "watch-only branch" }
        val base = if (p.publicKey().isEven()) p else -p
        return base + tweak(purpose, index)
    }

    /** Signs a key-path spend for the note at ([purpose], [index]) on [domain]. */
    public fun spend(purpose: NotePurpose, index: Long, domain: String): Spend.KeyPath =
        Spend.keyPath(noteSecretKey(purpose, index), domain)

    public companion object {
        private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        private val PURPOSE = DeterministicWallet.hardened(139)

        /** Derives the branch for [host], hashed exactly as stored (port included). */
        public fun fromSeed(seed: ByteArray, host: String): CashBranch {
            val master = DeterministicWallet.generate(seed)
            val hashingKey = master.derivePrivateKey(listOf(PURPOSE, 0L)).secretkeybytes.toByteArray()
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hashingKey, "HmacSHA256")) }
            val material = mac.doFinal(host.encodeToByteArray())
            // Each d is used as a raw uint32 index, hardened only if its top bit is set (as LUD-05 does).
            val d = (0 until 4).map { i -> (0 until 4).fold(0L) { acc, j -> (acc shl 8) or (material[4 * i + j].toLong() and 0xff) } }
            val node = master.derivePrivateKey(listOf(PURPOSE) + d)
            val p = node.privateKey
            return CashBranch(p.xOnlyPublicKey().value, node.chaincode, p)
        }

        public fun fromCx1(cx1: String): CashBranch? =
            Cx1.decode(cx1)?.let { (pubkey, chainCode) -> CashBranch(pubkey, chainCode, null) }

        private fun ser32(v: Long): ByteArray = ByteArray(4) { ((v ushr (24 - 8 * it)) and 0xff).toByte() }

        private fun BigInteger.toByteArray32(): ByteArray {
            val raw = toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
    }
}

/** LUD-26 username registration proofs, signed by the branch's purpose-0 index-0 key. */
public object AddressProof {
    public enum class Action(public val wire: String) { Register("register"), Unregister("unregister") }

    public fun message(action: Action, domain: String, username: String): String =
        "LNURLcash:${action.wire}:$domain:$username"

    /** Returns the hex-encoded 64-byte BIP-340 signature. */
    public fun sign(branch: CashBranch, action: Action, domain: String, username: String): String {
        val digest = Crypto.sha256(message(action, domain, username).encodeToByteArray()).byteVector32()
        val sk = branch.noteSecretKey(NotePurpose.Wallet, 0)
        return Crypto.signSchnorr(digest, sk, null, ByteVector32.Zeroes).toHex()
    }
}
