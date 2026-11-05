package whitewallet.lnurlcash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoteKeysTest {
    private val seed = ByteArray(32) { 7 }

    /** The flip side of `legacy 2f17578 - a branch's cx1 derives its wallet and change keys`: a share's cx1 reaches its own notes only. */
    @Test
    fun `a share's cx1 derives its own notes and nothing else`() {
        val account = ServiceRoot.fromSeed(seed, "mint.example").account(0)
        val watcher = assertNotNull(Share.fromCx1(account.share(0).cx1()))
        val visible = (0L..19L).map { watcher.notePubkey(it) }.toSet()
        for (i in 0L..19L) {
            assertEquals(account.share(0).noteSecretKey(i).xOnlyPublicKey().value, watcher.notePubkey(i))
            assert(account.walletKey(i).xOnlyPublicKey().value !in visible) { "wallet #$i" }
            assert(account.changeKey(i).xOnlyPublicKey().value !in visible) { "change #$i" }
            assert(account.share(1).notePubkey(i) !in visible) { "share 1 #$i" }
        }
        assertFailsWith<IllegalStateException> { watcher.noteSecretKey(0) }
        assertFailsWith<IllegalStateException> { watcher.proofKey() }
    }

    @Test
    fun `accounts are independent`() {
        val root = ServiceRoot.fromSeed(seed, "mint.example")
        assertNotEquals(root.account(0).walletKey(0), root.account(1).walletKey(0))
        assertNotEquals(root.account(0).share(0).cx1(), root.account(1).share(0).cx1())
    }

    /** A domain hosts one mint: the root hashes the domain, never the port, so mints on one hostname share keys and counters. */
    @Test
    fun `the root ignores the port, the name seed keeps it`() {
        val a = ServiceRoot.fromSeed(seed, "localhost:8111").account(0).walletKey(0)
        assertEquals(a, ServiceRoot.fromSeed(seed, "localhost:8112").account(0).walletKey(0))
        assertEquals(a, ServiceRoot.fromSeed(seed, "LOCALHOST").account(0).walletKey(0))
        assertNotEquals(NameSeed.of(seed, "localhost:8111"), NameSeed.of(seed, "localhost:8112"))
    }

    @Test
    fun `cx1 rejects the legacy 64-byte form and invalid keys`() {
        val legacy = NameSeed.of(seed, "mint.example")
        assertNull(Cx1.decode(legacy), "64 bytes is the legacy cx1")
        assertNull(Share.fromCx1(legacy))
        val cx1 = ServiceRoot.fromSeed(seed, "mint.example").account(0).share(0).cx1()
        assertEquals(113, cx1.length)
        val (chainCode, publicKey) = assertNotNull(Cx1.decode(cx1))
        val badPrefix = publicKey.toByteArray().also { it[0] = 4 }
        assertNull(Cx1.decode(Bech32m.encode("cx", chainCode.toByteArray() + badPrefix)))
    }
}
