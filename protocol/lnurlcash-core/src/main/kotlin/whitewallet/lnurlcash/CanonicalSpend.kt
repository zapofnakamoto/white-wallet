package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_1
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut

/** The fixed, never-broadcast transaction every note is spent from (LUD-25 "The canonical spend transaction"). */
public object CanonicalSpend {
    public const val DEFAULT_SEQUENCE: Long = 0xffffffffL

    /** `tagged_hash("LNURLcash/mint", domain)`, used as the prevout txid in serialized byte order. */
    public fun prevoutHash(domain: String): ByteVector32 =
        Crypto.taggedHash(domain.lowercase().encodeToByteArray(), "LNURLcash/mint")

    public fun transaction(domain: String, lockTime: Long = 0, sequence: Long = DEFAULT_SEQUENCE): Transaction =
        Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxHash(prevoutHash(domain)), 0), ByteVector.empty, sequence)),
            txOut = listOf(TxOut(Satoshi(0), ByteVector.empty)),
            lockTime = lockTime,
        )

    /** The spent output `(OP_1 <Q>, 0)`. */
    public fun spentOutput(q: ByteVector32): TxOut = TxOut(Satoshi(0), listOf(OP_1, OP_PUSHDATA(q)))

    /** BIP-341 key-path sighash (SIGHASH_DEFAULT) that a `ck1` signs. */
    public fun keyPathSighash(domain: String, q: ByteVector32): ByteVector32 =
        transaction(domain).hashForSigningTaprootKeyPath(0, listOf(spentOutput(q)), SigHash.SIGHASH_DEFAULT)
}

/**
 * The domain a spend is bound to: the host's lowercase hostname, without scheme or port.
 * Derivation (LUD-26) hashes the host exactly as stored instead; see [CashBranch].
 */
public fun spendDomain(host: String): String = host.substringBefore(':').lowercase()
