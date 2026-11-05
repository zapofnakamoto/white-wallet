package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A wallet's root `S = m/139'/d1/d2/d3/d4` for one mint domain (LUD-26), `d` from HMAC-SHA256 of the domain under the hashing key at
 * `m/139'/0`, as LUD-05 hashes a domain. A domain hosts one mint, so mints on one hostname (ports apart) share a root and its counters.
 * Accounts ([account]) are independent sets of funds below it.
 */
public class ServiceRoot internal constructor(internal val node: ExtendedPrivateKey) {
    public fun account(a: Long): NoteAccount {
        require(a in 0 until HARDENED) { "account must be below 2^31" }
        return NoteAccount(a, node.derivePrivateKey(DeterministicWallet.hardened(a)))
    }

    public companion object {
        internal const val HARDENED = 0x80000000L
        private val PURPOSE = DeterministicWallet.hardened(139)

        /** The hashing key at `m/139'/0`, [seed]'s own for every mint. */
        public fun hashingKey(seed: ByteArray): ByteVector32 =
            DeterministicWallet.generate(seed).derivePrivateKey(listOf(PURPOSE, 0L)).privateKey.value

        /** The four indices `d1..d4` for [hashed]: raw uint32s, hardened only if their top bit is set (as LUD-05 does). */
        public fun domainIndices(seed: ByteArray, hashed: String): List<Long> {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hashingKey(seed).toByteArray(), "HmacSHA256")) }
            val material = mac.doFinal(hashed.encodeToByteArray())
            return (0 until 4).map { i -> (0 until 4).fold(0L) { acc, j -> (acc shl 8) or (material[4 * i + j].toLong() and 0xff) } }
        }

        /** The root for the mint at [host]: its [spendDomain] is hashed, so the port never matters. */
        public fun fromSeed(seed: ByteArray, host: String): ServiceRoot = ServiceRoot(node(seed, spendDomain(host)))

        /** The node `m/139'/d1/d2/d3/d4` for [hashed] taken as is. */
        internal fun node(seed: ByteArray, hashed: String): ExtendedPrivateKey =
            DeterministicWallet.generate(seed).derivePrivateKey(listOf(PURPOSE) + domainIndices(seed, hashed))
    }
}

/** Account `S/a'` (LUD-26): wallet notes `S/a'/0/i`, change `S/a'/1/i` and shares `S/a'/2/n'`. */
public class NoteAccount internal constructor(public val index: Long, private val node: ExtendedPrivateKey) {
    /** The secret key of wallet note [i]: every note the wallet mints, rotates or merges into, and a split's `p1`. */
    public fun walletKey(i: Long): PrivateKey = node.derivePrivateKey(listOf(WALLET, index(i))).privateKey

    /** The secret key of change note [i]: a split's `p2`. */
    public fun changeKey(i: Long): PrivateKey = node.derivePrivateKey(listOf(CHANGE, index(i))).privateKey

    /** Share [n], one per party a `cx1` is handed to (a Lightning address). */
    public fun share(n: Long): Share {
        require(n in 0 until ServiceRoot.HARDENED) { "share must be below 2^31" }
        return Share(node.derivePrivateKey(listOf(SHARES, DeterministicWallet.hardened(n))))
    }

    private companion object {
        const val WALLET = 0L
        const val CHANGE = 1L
        const val SHARES = 2L
    }
}

/**
 * A share `S/a'/2/n'` (LUD-26): its notes `S/a'/2/n'/i` derive from its `cx1` by plain BIP-32 public derivation, so a mint or watcher
 * holding the `cx1` enumerates them without being able to spend them.
 */
public class Share private constructor(private val xpub: ExtendedPublicKey, private val xpriv: ExtendedPrivateKey?) {
    internal constructor(xpriv: ExtendedPrivateKey) : this(xpriv.extendedPublicKey, xpriv)

    public val isWatchOnly: Boolean
        get() = xpriv == null

    public fun cx1(): String = Cx1.encode(xpub.chaincode, xpub.publickeybytes)

    /** `x(P)`: what the mint verifies this share's proofs against. */
    public val xOnly: ByteVector32
        get() = xpub.publicKey.xOnly().value

    /** The note's `Q`, computable from the `cx1` alone. */
    public fun notePubkey(i: Long): ByteVector32 = xpub.derivePublicKey(index(i)).publicKey.xOnly().value

    public fun noteSecretKey(i: Long): PrivateKey = checkNotNull(xpriv) { "watch-only share" }.derivePrivateKey(index(i)).privateKey

    /** The share's own key `p`, which signs its address proofs. */
    public fun proofKey(): PrivateKey = checkNotNull(xpriv) { "watch-only share" }.privateKey

    public companion object {
        public fun fromCx1(cx1: String): Share? {
            val (chainCode, publicKey) = Cx1.decode(cx1) ?: return null
            return Share(ExtendedPublicKey(publicKey, chainCode, depth = 0, path = KeyPath.empty, parent = 0), null)
        }
    }
}

/**
 * The string the seed-derived default address names are hashed from: what draft 2f17578 called the branch's `cx1`, bech32m of `x(P) ||
 * chaincode` of the node for the host as stored (port included). Not protocol: kept as is so a restore keeps finding the names wallets
 * already registered.
 */
public object NameSeed {
    public fun of(seed: ByteArray, host: String): String {
        val node = ServiceRoot.node(seed, host)
        return Bech32m.encode("cx", node.privateKey.xOnlyPublicKey().value.toByteArray() + node.chaincode.toByteArray())
    }
}

/**
 * LUD-26 username registration proofs: BIP-340 signatures (all-zero `aux_rand`) by a share's own key, bound to the domain. The username is
 * case-insensitive and signed in lowercase, the `cx1` in its canonical lowercase spelling.
 */
public object AddressProof {
    public fun registerMessage(domain: String, username: String, cx1: String): String =
        "LNURLcash:register:$domain:${username.lowercase()}:${cx1.lowercase()}"

    public fun updateMessage(domain: String, username: String, cx1: String): String =
        "LNURLcash:update:$domain:${username.lowercase()}:${cx1.lowercase()}"

    public fun unregisterMessage(domain: String, username: String): String = "LNURLcash:unregister:$domain:${username.lowercase()}"

    /** Proves [share] may hold [username] at [domain]. */
    public fun register(share: Share, domain: String, username: String): String =
        sign(registerMessage(domain, username, share.cx1()), share.proofKey())

    /** Moves [username] to [newShare]: `sig` by the key on file ([onFile]), `newsig` by the new share. */
    public fun update(onFile: PrivateKey, newShare: Share, domain: String, username: String): Pair<String, String> =
        sign(updateMessage(domain, username, newShare.cx1()), onFile) to register(newShare, domain, username)

    /** Frees [username], proven by the share on file. */
    public fun unregister(share: Share, domain: String, username: String): String =
        sign(unregisterMessage(domain, username), share.proofKey())

    /** Returns the hex-encoded 64-byte BIP-340 signature of `sha256(message)`. */
    public fun sign(message: String, key: PrivateKey): String =
        Crypto.signSchnorr(Crypto.sha256(message.encodeToByteArray()).byteVector32(), key, null, ByteVector32.Zeroes).toHex()
}

private fun index(i: Long): Long {
    require(i in 0 until ServiceRoot.HARDENED) { "index must be below 2^31" }
    return i
}
