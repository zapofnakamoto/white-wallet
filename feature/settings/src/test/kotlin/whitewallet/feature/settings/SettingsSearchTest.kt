package whitewallet.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val notes = SearchDestination.Page(SettingsTarget.Notes)
    private val backup = SearchDestination.Page(SettingsTarget.Backup)
    private val items = listOf(
        SearchItem("Recovery phrase", "Recovery phrase", "seed, words, mnemonic", backup),
        SearchItem("Note strategy", "Notes", "combine, merge, rotate", notes),
        SearchItem("Auto-rotate incoming notes", "Notes", "privacy, fresh key", notes),
        SearchItem("Derived keys", "Developer options", "Note keys, indexes, rotate", SearchDestination.Route("developer/keys"), developerOnly = true),
        SearchItem("Clé de récupération", "Sécurité", "", backup),
    )

    private fun titles(query: String, developer: Boolean = false) = SettingsSearch.search(items, query, developer).map { it.title }

    @Test
    fun `matches titles, keywords and the page a setting lives on`() {
        assertEquals(listOf("Recovery phrase"), titles("seed"))
        // "notes" is in one title; the other matches through its page ("Notes").
        assertEquals(listOf("Auto-rotate incoming notes", "Note strategy"), titles("notes"))
    }

    @Test
    fun `title matches come first`() {
        assertEquals(listOf("Auto-rotate incoming notes", "Note strategy"), titles("rotate"))
    }

    @Test
    fun `ignores case, accents and punctuation, and needs every word`() {
        assertEquals(listOf("Auto-rotate incoming notes"), titles("AUTO ROTATE"))
        assertEquals(listOf("Clé de récupération"), titles("cle recuperation"))
        assertEquals(listOf("Auto-rotate incoming notes"), titles("rotate privacy"))
    }

    @Test
    fun `developer-only settings are found only in developer mode`() {
        assertTrue("Derived keys" !in titles("keys"))
        assertEquals(listOf("Derived keys"), titles("keys", developer = true))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(titles("").isEmpty())
        assertTrue(titles("  - ").isEmpty())
    }
}
