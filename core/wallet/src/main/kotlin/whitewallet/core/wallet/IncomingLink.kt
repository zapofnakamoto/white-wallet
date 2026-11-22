package whitewallet.core.wallet

import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.protocol.NoteLink

/**
 * What text from outside the app (a deep link, shared text, an NFC tag, a scanned code) is for, decided offline: a note to claim, or
 * something to pay. The screen it opens checks it properly.
 */
sealed interface IncomingLink {
    val text: String

    /**
     * A note link (`lnurlw://…?k1=…`, also bech32 or behind `lightning:`), any other withdraw link (`lnurlw://`, also inside bech32), or a
     * bech32 LNURL of a plain https URL that may be either a withdraw or a pay link ([mayBePay]): Claim asks its service, and hands a pay
     * link on to Send.
     */
    data class Claim(override val text: String, val mayBePay: Boolean = false) : IncomingLink

    /** An invoice, a Lightning address, a pay link, or a `bitcoin:` URI with a `lightning=` invoice. */
    data class Pay(override val text: String) : IncomingLink

    companion object {
        fun classify(raw: String?): IncomingLink? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val unwrapped = Payer.unwrapBip21(text)
            val bare = unwrapped.replaceFirst(Regex("^lightning:", RegexOption.IGNORE_CASE), "").trim()
            return when {
                NoteLink.parse(bare) != null -> Claim(bare)
                bare.startsWith("lnurlw://", ignoreCase = true) -> Claim(bare)
                Bolt11.decode(bare) != null -> Pay(bare)
                Lnurl.lightningAddressUrl(bare) != null -> Pay(bare)
                bare.startsWith("lnurlp://", ignoreCase = true) -> Pay(bare)
                else -> Lnurl.decodeBech32(bare)?.let { inner -> bech32(bare, inner) }
            }
        }

        /**
         * A bech32 LNURL without a note in it. A LUD-17 scheme inside (against LUD-01, but other wallets make them) or a Lightning
         * address's pay URL says which it is, also offline; a plain https URL only its service can say. Either way Send hands a withdraw
         * answer to Claim, and Claim a pay answer to Send.
         */
        private fun bech32(text: String, inner: String): IncomingLink =
            when {
                inner.startsWith("lnurlp://", ignoreCase = true) || Lnurl.lightningAddressUsername(inner) != null -> Pay(text)
                inner.startsWith("lnurlw://", ignoreCase = true) -> Claim(text)
                else -> Claim(text, mayBePay = true)
            }
    }
}
