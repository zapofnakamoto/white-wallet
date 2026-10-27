package whitewallet.core.wallet

import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.protocol.NoteLink

/**
 * What text from outside the app (a deep link, shared text, an NFC tag, a scanned code) is for, decided offline:
 * a note to claim, or something to pay. The screen it opens checks it properly.
 */
sealed interface IncomingLink {
    val text: String

    /** A note link (`lnurlw://…?k1=…`, also bech32 or behind `lightning:`). */
    data class Claim(override val text: String) : IncomingLink

    /** An invoice, a Lightning address, a pay link, or a `bitcoin:` URI with a `lightning=` invoice. */
    data class Pay(override val text: String) : IncomingLink

    companion object {
        fun classify(raw: String?): IncomingLink? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val unwrapped = Payer.unwrapBip21(text)
            val bare = unwrapped.replaceFirst(Regex("^lightning:", RegexOption.IGNORE_CASE), "").trim()
            return when {
                NoteLink.parse(bare) != null -> Claim(bare)
                Bolt11.decode(bare) != null -> Pay(bare)
                Lnurl.lightningAddressUrl(bare) != null -> Pay(bare)
                bare.startsWith("lnurlp://", ignoreCase = true) -> Pay(bare)
                // A bech32 LNURL without a note in it: most likely a pay link; Send checks what it really is.
                Lnurl.decodeBech32(bare) != null -> Pay(bare)
                else -> null
            }
        }
    }
}
