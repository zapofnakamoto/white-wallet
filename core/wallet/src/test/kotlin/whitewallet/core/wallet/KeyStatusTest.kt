package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

class KeyStatusTest {
    private fun note(state: NoteState) = Note("q", "a", "host", NoteKind.KeyPath, 0, 1, 5_000, null, state, 0)

    @Test
    fun `the wallet's own note decides first, whatever the last lookup said`() {
        assertEquals(KeyStatus.Funded(5_000), KeyStatus.of(1, 3, note(NoteState.Outstanding), null))
        assertEquals(KeyStatus.Funded(5_000), KeyStatus.of(1, 3, note(NoteState.Outstanding), KeyProbe.Failed("offline", 0)))
        assertEquals(KeyStatus.Confirming, KeyStatus.of(1, 3, note(NoteState.Ambiguous), null))
        assertEquals(KeyStatus.Spent, KeyStatus.of(1, 3, note(NoteState.Spent), null))
    }

    @Test
    fun `without a note, the lookup and the index tell the rest`() {
        assertEquals(KeyStatus.Spent, KeyStatus.of(1, 3, null, KeyProbe.Spent(0)))
        assertEquals(KeyStatus.Skipped, KeyStatus.of(1, 3, null, KeyProbe.Unused(0)))
        assertEquals(KeyStatus.HandedOut, KeyStatus.of(1, 3, null, null))
        assertEquals(KeyStatus.HandedOut, KeyStatus.of(1, 3, null, KeyProbe.Failed("offline", 0)))
        assertEquals(KeyStatus.Next, KeyStatus.of(3, 3, null, null))
        assertEquals(KeyStatus.Next, KeyStatus.of(3, 3, null, KeyProbe.Unused(0)))
        assertEquals(KeyStatus.Free, KeyStatus.of(4, 3, null, null))
    }
}
