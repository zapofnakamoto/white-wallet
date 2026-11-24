package whitewallet.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteColorIndexTest {
    private val hosts = listOf("home.example", "b.example", "a.example")

    @Test
    fun `the home mint takes the first colour, the others theirs in turn by name`() {
        assertEquals(0, noteColorIndex("home.example", "home.example", hosts))
        assertEquals(1, noteColorIndex("a.example", "home.example", hosts))
        assertEquals(2, noteColorIndex("b.example", "home.example", hosts))
    }

    @Test
    fun `a note from a mint not shown yet takes the colour it gets there once added`() {
        assertEquals(2, noteColorIndex("aa.example", "home.example", hosts + "aa.example"))
        assertEquals(3, noteColorIndex("c.example", "home.example", hosts + "c.example"))
    }
}
