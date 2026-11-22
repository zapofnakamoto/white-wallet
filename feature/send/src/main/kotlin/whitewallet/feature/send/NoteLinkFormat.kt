package whitewallet.feature.send

import androidx.annotation.StringRes

/** How a note's link is handed over: as it is (`lnurlw://`), or as bech32 `LNURL1…` for wallets that read only that. */
enum class NoteLinkFormat(@StringRes val label: Int) {
    Lnurlw(R.string.note_format_lnurlw),
    Lnurl(R.string.note_format_lnurl);

    /** What the QR code holds: the bech32 form all uppercase, so the code is compact. */
    fun qr(link: String): String =
        when (this) {
            Lnurlw -> link
            Lnurl -> bech32(link)?.let { "LIGHTNING:$it" } ?: link
        }

    /** What Share and Copy hand over: behind `lightning:`, so messengers make it tappable. */
    fun text(link: String): String =
        when (this) {
            Lnurlw -> link
            Lnurl -> bech32(link)?.let { "lightning:$it" } ?: link
        }

    // Qualified: the entry [Lnurl] hides the object of the same name.
    private fun bech32(link: String): String? = whitewallet.lnurl.Lnurl.bech32Link(link)
}
