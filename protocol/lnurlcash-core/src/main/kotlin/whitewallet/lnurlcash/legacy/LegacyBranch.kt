package whitewallet.lnurlcash.legacy

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import java.math.BigInteger
import whitewallet.lnurlcash.ServiceRoot
import whitewallet.lnurlcash.Spend

/*
 * Legacy: LUD-25/26 draft 2f17578. Removable once no wallet holds notes or addresses made under it (tag: keys); see protocol/SPEC.md,
 * "Removing legacy 2f17578".
 */

/** Legacy 2f17578 derivation purposes; each has its own index counter. */
public enum class LegacyPurpose(public val value: Int) {
    Wallet(0),
    Change(1),
    LightningAddress(2),
}

/**
 * Legacy 2f17578: one service's note branch, the node at `m/139'/d1/d2/d3/d4` for the host as stored (port included), with note keys
 * tweaked from it non-hardened, so a `cx1` holder can enumerate but never spend. Superseded by BIP-32 accounts and shares ([ServiceRoot],
 * which hashes the domain without its port).
 */
public class LegacyBranch
private constructor(
    /** x-only branch key `P`. */
    public val pubkey: ByteVector32,
    public val chainCode: ByteVector32,
    private val secret: PrivateKey?,
) {
    public val isWatchOnly: Boolean
        get() = secret == null

    public fun cx1(): String = LegacyCx1.encode(pubkey, chainCode)

    public fun tweak(purpose: LegacyPurpose, index: Long): PrivateKey {
        require(index in 0..0xffffffffL) { "index must be a uint32" }
        val data = pubkey.toByteArray() + chainCode.toByteArray() + ser32(purpose.value.toLong()) + ser32(index)
        val t = BigInteger(1, Crypto.taggedHash(data, "LNURLcash/derive").toByteArray()).mod(N)
        return PrivateKey(t.toByteArray32())
    }

    /** The note's `Q`, computable from the `cx1` alone. */
    public fun notePubkey(purpose: LegacyPurpose, index: Long): ByteVector32 =
        XonlyPublicKey(XonlyPublicKey(pubkey).publicKey + tweak(purpose, index).publicKey()).value

    public fun noteSecretKey(purpose: LegacyPurpose, index: Long): PrivateKey {
        val p = checkNotNull(secret) { "watch-only branch" }
        val base = if (p.publicKey().isEven()) p else -p
        return base + tweak(purpose, index)
    }

    /**
     * The branch key `p`: the key a mint on LUD-26 holds on file for an address registered under this draft, so it signs the `update` that
     * moves the address to a share.
     */
    public fun branchKey(): PrivateKey = checkNotNull(secret) { "watch-only branch" }

    /** Signs a key-path spend for the note at ([purpose], [index]) on [domain]. */
    public fun spend(purpose: LegacyPurpose, index: Long, domain: String): Spend.KeyPath =
        Spend.keyPath(noteSecretKey(purpose, index), domain)

    public companion object {
        private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

        /** Derives the branch for [host], hashed exactly as stored (port included). */
        public fun fromSeed(seed: ByteArray, host: String): LegacyBranch {
            val node = ServiceRoot.node(seed, host)
            return LegacyBranch(node.privateKey.xOnlyPublicKey().value, node.chaincode, node.privateKey)
        }

        public fun fromCx1(cx1: String): LegacyBranch? =
            LegacyCx1.decode(cx1)?.let { (pubkey, chainCode) -> LegacyBranch(pubkey, chainCode, null) }

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

/** Legacy 2f17578 username registration proofs, signed by the branch's purpose-0 index-0 key, without the `cx1`. */
public object LegacyAddressProof {
    public enum class Action(public val wire: String) {
        Register("register"),
        Unregister("unregister"),
    }

    public fun message(action: Action, domain: String, username: String): String = "LNURLcash:${action.wire}:$domain:$username"

    /** Returns the hex-encoded 64-byte BIP-340 signature. */
    public fun sign(branch: LegacyBranch, action: Action, domain: String, username: String): String {
        val digest = Crypto.sha256(message(action, domain, username).encodeToByteArray()).byteVector32()
        val sk = branch.noteSecretKey(LegacyPurpose.Wallet, 0)
        return Crypto.signSchnorr(digest, sk, null, ByteVector32.Zeroes).toHex()
    }
}
