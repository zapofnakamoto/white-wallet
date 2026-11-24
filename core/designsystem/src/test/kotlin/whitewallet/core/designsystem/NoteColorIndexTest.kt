package whitewallet.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteColorIndexTest {
    @Test
    fun `the home mint takes the first colour`() {
        assertEquals(0, noteColorIndex("home.example", "home.example"))
    }

    @Test
    fun `another mint's colour comes from its host alone, never the home mint's`() {
        // Pinned: a mint keeps its colour across releases and phones.
        assertEquals(1 + 0x78b12f74, noteColorIndex("a.example", "home.example"))
        assertEquals(noteColorIndex("a.example", "home.example"), noteColorIndex("a.example", null))
        assertEquals(noteColorIndex("a.example", null), noteColorIndex("A.Example", null))
        for (host in listOf("a.example", "b.example", "mint.example:8111", "")) assertNotEquals(0, noteColorIndex(host, "home.example"))
    }
}
