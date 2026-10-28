package whitewallet.lnurlcash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CashBranchTest {
    /**
     * The purpose only enters the non-hardened per-note tweak, so the one cx1 an address registration hands the mint
     * (and the mint publishes as text/cpub) derives every wallet and change key of the branch too. Flip this when the
     * purpose gets a hardened step of its own.
     */
    @Test
    fun `a branch's cx1 derives its wallet and change keys, but can't spend them`() {
        val own = CashBranch.fromSeed(ByteArray(32) { 7 }, "mint.example")
        val watcher = assertNotNull(CashBranch.fromCx1(own.cx1()))
        for (purpose in NotePurpose.entries) {
            for (i in 0L..4L) {
                assertEquals(own.noteSecretKey(purpose, i).xOnlyPublicKey().value, watcher.notePubkey(purpose, i), "$purpose #$i")
            }
        }
        assertFailsWith<IllegalStateException> { watcher.noteSecretKey(NotePurpose.Wallet, 0) }
    }
}
