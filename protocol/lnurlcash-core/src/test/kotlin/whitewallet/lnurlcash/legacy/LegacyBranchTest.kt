package whitewallet.lnurlcash.legacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LegacyBranchTest {
    /**
     * The purpose only enters the non-hardened per-note tweak, so the one cx1 an address registration hands the mint (and the mint
     * publishes as text/cpub) derives every wallet and change key of the branch too. Flip this when the purpose gets a hardened step of its
     * own.
     */
    @Test
    fun `legacy 2f17578 - a branch's cx1 derives its wallet and change keys, but can't spend them`() {
        val own = LegacyBranch.fromSeed(ByteArray(32) { 7 }, "mint.example")
        val watcher = assertNotNull(LegacyBranch.fromCx1(own.cx1()))
        for (purpose in LegacyPurpose.entries) {
            for (i in 0L..4L) {
                assertEquals(own.noteSecretKey(purpose, i).xOnlyPublicKey().value, watcher.notePubkey(purpose, i), "$purpose #$i")
            }
        }
        assertFailsWith<IllegalStateException> { watcher.noteSecretKey(LegacyPurpose.Wallet, 0) }
    }
}
