package whitewallet.feature.settings

import androidx.annotation.StringRes
import java.text.Normalizer

/** A settings page the hub can open. The app maps each to its route. */
enum class SettingsTarget { Account, Backup, Addresses, AccountMints, Notes, NoteList, Mints, Delete, Display, Security, About, Developer }

/** Where a search result leads: a settings page, or a developer page by route. */
sealed interface SearchDestination {
    data class Page(val target: SettingsTarget) : SearchDestination
    data class Route(val route: String) : SearchDestination
}

/** One searchable setting. [keywords] is a localized list of synonyms (or a page description). */
data class SearchEntry(
    @StringRes val title: Int,
    @StringRes val keywords: Int?,
    val destination: SearchDestination,
    val developerOnly: Boolean = false,
)

/** A [SearchEntry] with its strings resolved; [location] names the page it opens. */
data class SearchItem(
    val title: String,
    val location: String,
    val keywords: String,
    val destination: SearchDestination,
    val developerOnly: Boolean = false,
)

/**
 * The searchable settings and the matching. To make a new setting findable, add an entry here (title, keywords and
 * the page it lives on).
 */
object SettingsSearch {
    private fun page(target: SettingsTarget) = SearchDestination.Page(target)

    val entries: List<SearchEntry> = listOf(
        SearchEntry(R.string.settings_account, R.string.search_kw_account, page(SettingsTarget.Account)),
        SearchEntry(R.string.settings_recovery_phrase, R.string.search_kw_backup, page(SettingsTarget.Backup)),
        SearchEntry(R.string.addresses_title, R.string.search_kw_addresses, page(SettingsTarget.Addresses)),
        SearchEntry(R.string.settings_home_mint, R.string.search_kw_mint, page(SettingsTarget.AccountMints)),
        SearchEntry(R.string.mint_watch_only, R.string.search_kw_watch_only, page(SettingsTarget.AccountMints)),
        SearchEntry(R.string.addresses_add, R.string.search_kw_add_address, page(SettingsTarget.Addresses)),
        SearchEntry(R.string.addresses_make_primary, R.string.search_kw_primary, page(SettingsTarget.Addresses)),
        SearchEntry(R.string.addresses_change_primary, R.string.search_kw_change_primary, page(SettingsTarget.Addresses)),
        SearchEntry(R.string.addresses_unregister, R.string.search_kw_unregister, page(SettingsTarget.Addresses)),
        SearchEntry(R.string.settings_note_strategy, R.string.search_kw_notes, page(SettingsTarget.Notes)),
        SearchEntry(R.string.settings_strategy_single, R.string.search_kw_single_note, page(SettingsTarget.Notes)),
        SearchEntry(R.string.settings_strategy_manual, null, page(SettingsTarget.Notes)),
        SearchEntry(R.string.notes_collect_search, R.string.search_kw_collect, page(SettingsTarget.Notes)),
        SearchEntry(R.string.settings_mints, R.string.search_kw_mints, page(SettingsTarget.Mints)),
        SearchEntry(R.string.settings_auto_renew, R.string.search_kw_auto_renew, page(SettingsTarget.Notes)),
        SearchEntry(R.string.settings_delete, R.string.search_kw_delete, page(SettingsTarget.Delete)),
        SearchEntry(R.string.settings_amounts, R.string.search_kw_amounts, page(SettingsTarget.Display)),
        SearchEntry(R.string.security_app_lock, R.string.search_kw_security, page(SettingsTarget.Security)),
        SearchEntry(R.string.about_title, R.string.search_kw_about, page(SettingsTarget.About)),
        SearchEntry(R.string.developer_mode, R.string.search_kw_developer, page(SettingsTarget.Developer)),
        SearchEntry(R.string.developer_max_addresses, R.string.search_kw_max_addresses, page(SettingsTarget.Developer), developerOnly = true),
    )

    /** The developer pages, searchable by title and description while developer mode is on. */
    fun developerEntries(pages: List<DeveloperPage>): List<SearchEntry> =
        pages.map { SearchEntry(it.title, it.description, SearchDestination.Route(it.route), developerOnly = true) }

    /** The brand's own pages, searchable by title. */
    fun brandEntries(pages: List<BrandPage>): List<SearchEntry> = pages.map { SearchEntry(it.title, null, SearchDestination.Route(it.route)) }

    /**
     * Items containing every word of [query] in their title, location or keywords, ignoring case, accents and
     * punctuation. Title matches come first; otherwise the registry order is kept. A blank query matches nothing.
     */
    fun search(items: List<SearchItem>, query: String, developerMode: Boolean): List<SearchItem> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items
            .filter { developerMode || !it.developerOnly }
            .mapNotNull { item ->
                val title = normalize(item.title)
                val all = "$title ${normalize(item.location)} ${normalize(item.keywords)}"
                when {
                    words.all { it in title } -> 0 to item
                    words.all { it in all } -> 1 to item
                    else -> null
                }
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace(NON_WORD, " ")
            .trim()

    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
