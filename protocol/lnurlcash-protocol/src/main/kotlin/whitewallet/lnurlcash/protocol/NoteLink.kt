package whitewallet.lnurlcash.protocol

import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.Spend

/** A note as handed between people: `lnurlw://<service>/w?k1=<spend>[&amount=<msat>][&c=<cs1>]`. */
public data class NoteLink(
    /** The fetchable withdraw URL, without note parameters. */
    val withdrawUrl: String,
    val spend: Spend,
    /** Display only; the service's `maxWithdrawable` is authoritative. */
    val declaredAmountMsat: Long?,
    val certificate: String?,
) {
    /** The host the note lives on, exactly as written (port included). */
    val host: String get() = checkNotNull(Lnurl.host(withdrawUrl))

    public fun encode(): String {
        val params = buildList {
            add("k1" to spend.k1)
            declaredAmountMsat?.let { add("amount" to it.toString()) }
            certificate?.let { add("c" to it) }
        }
        return Lnurl.withQuery(withdrawUrl.replaceFirst(Regex("^https?://"), "lnurlw://"), params)
    }

    public companion object {
        private val NOTE_PARAMS = setOf("k1", "amount", "c", "sig")

        /** Parses pasted or scanned text into a note, or null if it is not one this wallet understands. */
        public fun parse(input: String): NoteLink? {
            val url = Lnurl.resolve(input) ?: return null
            val params = Lnurl.queryParams(url)
            val k1 = params.singleOrNull { it.first == "k1" }?.second ?: return null
            val spend = Spend.parse(k1) ?: return null
            val others = params.filter { it.first !in NOTE_PARAMS }
            val base = Lnurl.withQuery(url.substringBefore('?'), others)
            return NoteLink(
                withdrawUrl = base,
                spend = spend,
                declaredAmountMsat = params.firstOrNull { it.first == "amount" }?.second?.toLongOrNull(),
                certificate = params.firstOrNull { it.first == "c" || it.first == "sig" }?.second,
            )
        }
    }
}
