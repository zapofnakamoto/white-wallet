package whitewallet.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState

class ReceivedWaitTest {
    private fun note(state: ReceivedState = ReceivedState.Waiting) =
        ReceivedNote("r".padEnd(64, '0'), "a", "mint.example", "w", "k1", null, null, 1_000, null, null, state, null, 1, 1)

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
    fun onlyAChangedKeyLightningOnlyAndSpentElsewhereNeedTheUser() {
        val needing = ReceivedState.entries.filter { note(it).needsUser }.toSet()
        assertEquals(setOf(ReceivedState.KeyChanged, ReceivedState.LightningOnly, ReceivedState.Gone), needing)
    }
}
