package whitewallet.lnurlcash

import fr.acinq.bitcoin.Bech32

/** BIP-350 bech32m without BIP-173's 90-character limit, as LUD-25 requires for every value. */
internal object Bech32m {
    fun encode(hrp: String, data: ByteArray): String = Bech32.encodeBytes(hrp, data, Bech32.Encoding.Bech32m)

    /** Returns the lowercased human-readable part and payload, or null for anything that is not valid bech32m. */
    fun decode(value: String): Pair<String, ByteArray>? = runCatching {
        val (hrp, data, encoding) = Bech32.decodeBytes(value)
        if (encoding != Bech32.Encoding.Bech32m) null else hrp to data
    }.getOrNull()
}
