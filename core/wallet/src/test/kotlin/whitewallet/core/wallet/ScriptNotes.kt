@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_CHECKLOCKTIMEVERIFY
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.CanonicalSpend
import whitewallet.lnurlcash.Cw1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.spendDomain

/**
 * A note locked by time (LUD-25 "Timelocks"): one leaf under the NUMS key, spendable by [key] once its mint's clock allows. The spend
 * claims the lock itself, with a sequence below final so the lock is checked at all.
 */
internal class LockedNote
private constructor(private val leaf: ByteVector, private val lockTime: Long, private val sequence: Long, private val key: PrivateKey) {
    private val output = XonlyPublicKey(BearerNote.NUMS).outputKey(BearerNote.tapleafHash(leaf))
    val q: ByteVector32 = output.first.value
    private val control =
        (byteArrayOf((BearerNote.LEAF_VERSION or if (output.second) 1 else 0).toByte()) + BearerNote.NUMS.toByteArray()).byteVector()

    fun spend(domain: String): Spend.Script {
        val sig =
            CanonicalSpend.transaction(domain, lockTime, sequence)
                .signInputTaprootScriptPath(
                    key,
                    0,
                    listOf(CanonicalSpend.spentOutput(q)),
                    SigHash.SIGHASH_DEFAULT,
                    BearerNote.tapleafHash(leaf),
                )
        return assertIs<Spend.Script>(Spend.parse(Cw1(lockTime, sequence, leaf, control, listOf(sig)).encode())).also {
            assertEquals(q, it.q)
            assertTrue(it.verify(domain), "checks out locally, as at the mint")
        }
    }

    /** The note's link on [host]'s mint, as someone would hand it over. */
    fun link(host: String, withdrawUrl: String, declaredMsat: Long? = null): String =
        NoteLink(withdrawUrl, spend(spendDomain(host)), declaredMsat, null).encode()

    companion object {
        private fun key() = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes))

        /**
         * Locked until [lockTime] (Unix seconds): `<t> OP_CHECKLOCKTIMEVERIFY OP_DROP <key> OP_CHECKSIG`, or [reordered] `<key>
         * OP_CHECKSIGVERIFY <t> OP_CHECKLOCKTIMEVERIFY`.
         */
        fun until(lockTime: Long, reordered: Boolean = false): LockedNote {
            val key = key()
            val t = OP_PUSHDATA(Script.encodeNumber(lockTime))
            val k = OP_PUSHDATA(key.xOnlyPublicKey())
            val ops =
                if (reordered) listOf(k, OP_CHECKSIGVERIFY, t, OP_CHECKLOCKTIMEVERIFY)
                else listOf(t, OP_CHECKLOCKTIMEVERIFY, OP_DROP, k, OP_CHECKSIG)
            return LockedNote(Script.write(ops).byteVector(), lockTime, CanonicalSpend.DEFAULT_SEQUENCE - 1, key)
        }

        /** Not locked, one plain leaf: `<key> OP_CHECKSIG`. */
        fun checksig(): LockedNote {
            val key = key()
            return LockedNote(
                Script.write(listOf(OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG)).byteVector(),
                0,
                CanonicalSpend.DEFAULT_SEQUENCE - 1,
                key,
            )
        }

        /** Not locked, with a leaf over 252 bytes (whose length BIP-341 writes in three bytes): `<300 bytes> OP_DROP <key> OP_CHECKSIG`. */
        fun longLeaf(): LockedNote {
            val key = key()
            val ops = listOf(OP_PUSHDATA(ByteArray(300) { 7 }), OP_DROP, OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG)
            return LockedNote(Script.write(ops).byteVector(), 0, CanonicalSpend.DEFAULT_SEQUENCE - 1, key)
        }

        /** Locked for [units] × 512 s after its mint last credited it: `<s> OP_CHECKSEQUENCEVERIFY OP_DROP <key> OP_CHECKSIG`. */
        fun after(units: Int): LockedNote {
            val key = key()
            val sequence = (1L shl 22) or units.toLong()
            val ops =
                listOf(
                    OP_PUSHDATA(Script.encodeNumber(sequence)),
                    OP_CHECKSEQUENCEVERIFY,
                    OP_DROP,
                    OP_PUSHDATA(key.xOnlyPublicKey()),
                    OP_CHECKSIG,
                )
            return LockedNote(Script.write(ops).byteVector(), 0, sequence, key)
        }
    }
}

/** Renews the phone's one note on [host] to [q], someone else's key: the money leaves the balance. */
internal suspend fun Regtest.Phone.lockTo(q: ByteVector32, host: String = Regtest.HOST) {
    val result =
        NoteMutator(wallet)
            .mutate(wallet.activeId(), host, listOf(notes(host).single()), OperationType.Rotate, listOf(OutputSpec.External { q }))
    assertIs<MutationResult.Done>(result)
}
