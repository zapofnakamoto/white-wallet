package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_CHECKLOCKTIMEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.OperationType
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.CanonicalSpend
import whitewallet.lnurlcash.Cw1
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Notes locked until a time (LUD-25 "Timelocks"), on the real mint: a leaf `<t> OP_CHECKLOCKTIMEVERIFY OP_DROP <key>
 * OP_CHECKSIG` under the NUMS key. The wallet makes none; a note of its own is renewed to one, and spent from it.
 */
class RegtestTimelockTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `a note whose timelock is in the past is spent, and comes back to the wallet`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val locked = TimelockedNote(System.currentTimeMillis() / 1000 - 3600)
        alice.lockTo(locked)
        assertEquals(0, alice.balance())

        val result = alice.takeBack(locked)
        assertIs<MutationResult.Done>(result, "the lock has passed")
        assertEquals(1_999_000, alice.balance(), "renewing is free")
        assertEquals(NoteLookupResult.Spent, alice.lookup(locked))
    }

    @Test
    fun `a note whose timelock is in the future is refused, and stays locked`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val locked = TimelockedNote(System.currentTimeMillis() / 1000 + 86_400)
        alice.lockTo(locked)

        val info = assertFailsWith<NoteLookupError> { alice.wallet.client.noteInfo(alice.withdrawUrl(), locked.spend(spendDomain(HOST)).k1) }
        assertTrue("in the future" in info.message.orEmpty(), "the mint names the unmet lock: ${info.message}")

        // Sent to the callback anyway, the spend is refused, and nothing is burned.
        val refused = assertIs<MutationResult.Refused>(alice.takeBack(locked), "the lock has not passed")
        assertTrue("in the future" in refused.reason.orEmpty(), "the mint names the unmet lock: ${refused.reason}")
        assertEquals(emptyList(), refused.setAside)
        assertEquals(0, alice.balance())
        assertEquals(NoteLookupResult.Outstanding(1_999_000), alice.lookup(locked))
    }

    /** A note spendable by [key] once the mint's clock reaches [lockTime] (Unix seconds). */
    private class TimelockedNote(val lockTime: Long, val key: PrivateKey = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes))) {
        val leaf: ByteVector = Script.write(
            listOf(OP_PUSHDATA(Script.encodeNumber(lockTime)), OP_CHECKLOCKTIMEVERIFY, OP_DROP, OP_PUSHDATA(key.xOnlyPublicKey()), OP_CHECKSIG),
        ).byteVector()
        private val output = XonlyPublicKey(BearerNote.NUMS).outputKey(BearerNote.tapleafHash(leaf))
        val q: ByteVector32 = output.first.value
        private val control = (byteArrayOf((BearerNote.LEAF_VERSION or if (output.second) 1 else 0).toByte()) + BearerNote.NUMS.toByteArray()).byteVector()

        /** The `cw1` claiming [lockTime] itself; a sequence below final, so the lock is checked at all. */
        fun spend(domain: String): Spend.Script {
            val sequence = CanonicalSpend.DEFAULT_SEQUENCE - 1
            val sig = CanonicalSpend.transaction(domain, lockTime, sequence)
                .signInputTaprootScriptPath(key, 0, listOf(CanonicalSpend.spentOutput(q)), SigHash.SIGHASH_DEFAULT, BearerNote.tapleafHash(leaf))
            val cw1 = Cw1(lockTime, sequence, leaf, control, listOf(sig))
            return assertIs<Spend.Script>(Spend.parse(cw1.encode())).also {
                assertEquals(q, it.q)
                assertTrue(it.verify(domain), "checks out locally, as at the mint")
            }
        }
    }

    /** Renews the phone's one note to [note]'s key. */
    private suspend fun Regtest.Phone.lockTo(note: TimelockedNote) {
        val input = notes().single()
        val result = NoteMutator(wallet).mutate(wallet.activeId(), HOST, listOf(input), OperationType.Rotate, listOf(OutputSpec.External { note.q })) { callback, k1s, outs ->
            Requests.rotate(callback, k1s.single(), outs.single().key)
        }
        assertIs<MutationResult.Done>(result)
        assertEquals(NoteLookupResult.Outstanding(input.amountMsat), lookup(note))
    }

    private suspend fun Regtest.Phone.withdrawUrl(): String = assertNotNull(store.mint(HOST)).withdrawUrl

    /**
     * Spends [note] into a fresh wallet key of the phone. Its value and the callback come from a lookup by key, so the
     * spend itself reaches only the callback.
     */
    private suspend fun Regtest.Phone.takeBack(note: TimelockedNote): MutationResult {
        val info = wallet.client.lookup(withdrawUrl(), note.q)
        return NoteMutator(wallet).rotateIn(wallet.activeId(), HOST, withdrawUrl(), info, note.spend(spendDomain(HOST)), info.maxWithdrawableMsat, sent = null)
    }

    private sealed interface NoteLookupResult {
        data class Outstanding(val amountMsat: Long) : NoteLookupResult
        data object Spent : NoteLookupResult
    }

    /** What the mint says of [note], asked by its key alone. */
    private suspend fun Regtest.Phone.lookup(note: TimelockedNote): NoteLookupResult {
        val info = try {
            wallet.client.lookup(withdrawUrl(), note.q)
        } catch (e: NoteLookupError) {
            assertEquals(RejectionKind.Spent, e.kind, e.reason)
            return NoteLookupResult.Spent
        }
        return NoteLookupResult.Outstanding(info.maxWithdrawableMsat)
    }
}
