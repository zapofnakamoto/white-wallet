package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.secp256k1.Hex

/** A spend: the bearer secret that goes wherever LUD-03 puts a `k1`. */
public sealed interface Spend {
    /** The note's output key `Q`, its id at the service. */
    public val q: ByteVector32

    /** The exact string to send as `k1`. */
    @RevealsNoteSecret public val k1: String

    /** A `ck1`: BIP-340 signature by `Q` over the canonical spend sighash for one domain. */
    @OptIn(RevealsNoteSecret::class)
    public data class KeyPath(override val q: ByteVector32, @property:RevealsNoteSecret val signature: ByteVector64) : Spend {
        @RevealsNoteSecret
        override val k1: String
            get() = Ck1.encode(q, signature)

        public fun verify(domain: String): Boolean =
            Crypto.verifySignatureSchnorr(CanonicalSpend.keyPathSighash(domain, q), signature, XonlyPublicKey(q))

        /** Debug text without the secret. */
        override fun toString(): String = "KeyPath(q=$q, k1=${Redaction.mask(k1)})"
    }

    /** A plain bearer note, sent in its `k1` short form (hex preimage) or as the equivalent `cw1`. */
    @OptIn(RevealsNoteSecret::class)
    public data class Bearer(@property:RevealsNoteSecret val preimage: ByteVector32, val asCw1: Boolean = false) : Spend {
        val h: ByteVector32
            get() = BearerNote.hash(preimage)

        override val q: ByteVector32
            get() = BearerNote.q(h)

        @RevealsNoteSecret
        override val k1: String
            get() = if (asCw1) BearerNote.cw1(preimage).encode() else preimage.toHex()

        /** Debug text without the secret. */
        override fun toString(): String = "Bearer(q=$q, h=$h, k1=${Redaction.mask(k1)})"
    }

    /**
     * Any other script-path spend. What its script means is open-ended, so nothing here reads it: the spend is checked as a whole
     * ([verify]), and the time it claims is read from the spend, which a leaf signature commits to.
     */
    @OptIn(RevealsNoteSecret::class)
    public data class Script(
        override val q: ByteVector32,
        @property:RevealsNoteSecret val cw1: Cw1,
        @property:RevealsNoteSecret override val k1: String,
    ) : Spend {
        /** The Unix time the spend claims (`nLockTime`); the mint refuses it until then. Null if it claims none. */
        val lockedUntilSeconds: Long?
            get() = cw1.lockTime.takeIf { it != 0L }

        /**
         * The wait the spend claims (`nSequence`, BIP-68), counted from when the mint last credited the note (by mint, rotate, split or
         * merge), which only the mint knows. Null if it claims none.
         */
        val lockedForSeconds: Long?
            get() = Timelock.relativeSeconds(cw1.sequence)

        /** False for a time the mint must refuse to honour: a block height, or a wait counted in blocks. */
        val isValidLockClaim: Boolean
            get() = Timelock.isValid(cw1.lockTime, cw1.sequence)

        /** The note may be spent another way too: it has other leaves, or an internal key that isn't the NUMS point. */
        val hasOtherPaths: Boolean
            get() = ScriptPath.hasOtherPaths(cw1)

        /**
         * Whether the spend satisfies its note's script at [domain], as the mint checks it (BIP-341/342 on the canonical spend
         * transaction). Independent of the clock: the claimed time is checked against the script, not against now.
         */
        public fun verify(domain: String): Boolean = ScriptPath.verify(cw1, q, domain)

        /** Debug text without the secret. */
        override fun toString(): String = "Script(q=$q, lockTime=${cw1.lockTime}, sequence=${cw1.sequence}, k1=${Redaction.mask(k1)})"
    }

    @OptIn(RevealsNoteSecret::class)
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
            Ck1.decode(k1)?.let { (q, sig) ->
                return KeyPath(q, sig)
            }
            val cw1 = Cw1.decode(k1) ?: return null
            val q = ScriptPath.outputKey(cw1) ?: return null
            val h = BearerNote.hashOf(cw1.script)
            val preimage = cw1.witness.singleOrNull()
            val isPlainBearer =
                h != null &&
                    preimage != null &&
                    preimage.size() == 32 &&
                    cw1.lockTime == 0L &&
                    cw1.sequence == CanonicalSpend.DEFAULT_SEQUENCE &&
                    BearerNote.hash(preimage.toByteArray().byteVector32()) == h &&
                    BearerNote.q(h) == q
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

    fun hasOtherPaths(cw1: Cw1): Boolean {
        val control = cw1.controlBlock.toByteArray()
        return control.size > 33 || control.copyOfRange(1, 33).byteVector32() != BearerNote.NUMS
    }

    /** Every consensus flag LUD-25 names for a spend. */
    private const val FLAGS =
        ScriptFlags.SCRIPT_VERIFY_P2SH or
            ScriptFlags.SCRIPT_VERIFY_WITNESS or
            ScriptFlags.SCRIPT_VERIFY_TAPROOT or
            ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY or
            ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY or
            ScriptFlags.SCRIPT_VERIFY_DERSIG or
            ScriptFlags.SCRIPT_VERIFY_NULLDUMMY

    @OptIn(RevealsNoteSecret::class)
    fun verify(cw1: Cw1, q: ByteVector32, domain: String): Boolean {
        val tx =
            CanonicalSpend.transaction(domain, cw1.lockTime, cw1.sequence)
                .updateWitness(0, ScriptWitness(cw1.witness + cw1.script + cw1.controlBlock))
        return try {
            Transaction.correctlySpends(tx, mapOf(tx.txIn[0].outPoint to CanonicalSpend.spentOutput(q)), FLAGS)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return 0
    }
}

/** The time a script-path spend claims (LUD-25 "Timelocks"): a Unix time, and a BIP-68 wait counted in seconds. */
internal object Timelock {
    /** Below this, `nLockTime` is a block height. */
    private const val LOCKTIME_THRESHOLD = 500_000_000L
    private const val SEQUENCE_DISABLE = 1L shl 31
    private const val SEQUENCE_TYPE_TIME = 1L shl 22
    private const val SEQUENCE_MASK = 0xffffL
    private const val SEQUENCE_GRANULARITY_SECONDS = 512L

    fun relativeSeconds(sequence: Long): Long? {
        if (sequence and SEQUENCE_DISABLE != 0L || sequence and SEQUENCE_TYPE_TIME == 0L) return null
        return ((sequence and SEQUENCE_MASK) * SEQUENCE_GRANULARITY_SECONDS).takeIf { it > 0 }
    }

    fun isValid(lockTime: Long, sequence: Long): Boolean =
        (lockTime == 0L || lockTime >= LOCKTIME_THRESHOLD) && (sequence and SEQUENCE_DISABLE != 0L || sequence and SEQUENCE_TYPE_TIME != 0L)
}
