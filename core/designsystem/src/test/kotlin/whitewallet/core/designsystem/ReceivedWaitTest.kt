package whitewallet.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState

class ReceivedWaitTest {
    private fun note(state: ReceivedState = ReceivedState.Waiting, lockedUntilMillis: Long? = null) =
        ReceivedNote("r".padEnd(64, '0'), "a", "mint.example", "w", "k1", null, null, 1_000, lockedUntilMillis, null, state, null, 1, 1)

    @Test
    fun offlineWinsOverASilentMint() {
        assertEquals(ReceivedWait.Offline, note().waitsFor(offline = true, notAnswering = setOf("mint.example")))
    }

    @Test
    fun aSilentMintIsWhatItWaitsFor() {
        assertEquals(ReceivedWait.MintNotAnswering, note().waitsFor(offline = false, notAnswering = setOf("mint.example")))
    }

    @Test
    fun otherwiseItWaitsForTheNextRefresh() {
        assertEquals(ReceivedWait.NextRefresh, note().waitsFor(offline = false, notAnswering = setOf("other.example")))
    }

    @Test
    fun aChangedKeyLightningOnlySpentElsewhereAndALockWithoutADateNeedTheUser() {
        val needing = ReceivedState.entries.filter { note(it).needsUser(nowMillis = 0) }.toSet()
        assertEquals(setOf(ReceivedState.KeyChanged, ReceivedState.LightningOnly, ReceivedState.Gone, ReceivedState.Locked), needing)
    }

    @Test
    fun aLockNeedsTheUserOnlyOnceItsDatePassed() {
        assertEquals(false, note(ReceivedState.Locked, lockedUntilMillis = 2_000).needsUser(nowMillis = 1_999))
        assertEquals(true, note(ReceivedState.Locked, lockedUntilMillis = 2_000).needsUser(nowMillis = 2_000))
    }
}
