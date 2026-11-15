package whitewallet.lnurl

import fr.acinq.bitcoin.Bech32
import java.net.URI
import java.net.URLEncoder

/** LUD-01 bech32 LNURLs, LUD-17 schemes and LUD-16 Lightning Addresses, resolved to a fetchable URL. */
public object Lnurl {
    private val LUD17_SCHEMES = setOf("lnurlw", "lnurlp", "lnurlc", "keyauth")
    private val ADDRESS = Regex("^([a-z0-9\\-_.+]+)@([a-z0-9\\-.]+(:[0-9]{1,5})?)$", RegexOption.IGNORE_CASE)
    private val WELL_KNOWN_LNURLP = Regex("^(https?://[^/]+)/\\.well-known/lnurlp/([^/?#]+)$")

    /** A canonical dotted-decimal IPv4 address in 127.0.0.0/8: four octets 0–255, no leading zeros. */
    private val LOOPBACK_IPV4 = Regex("^127(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$")

    /** Decodes a bech32 `lnurl1...` string to its URL, or null. Case-insensitive, with no length limit. */
    public fun decodeBech32(value: String): String? = runCatching {
        val (hrp, data, encoding) = Bech32.decodeBytes(value)
        if (hrp != "lnurl" || encoding != Bech32.Encoding.Bech32 || data.isEmpty()) null else data.decodeToString()
    }
        .getOrNull()

    /** Encodes [url] as an uppercase bech32 LNURL (uppercase keeps QR codes compact). */
    public fun encodeBech32(url: String): String = Bech32.encodeBytes("lnurl", url.encodeToByteArray(), Bech32.Encoding.Bech32).uppercase()

    /**
     * Resolves user input (an https URL, a LUD-17 scheme, a bech32 LNURL, a Lightning Address, optionally behind `lightning:`) to the URL
     * to GET. Cleartext http is only allowed for onion and loopback hosts.
     */
    public fun resolve(input: String): String? {
        var value = input.trim()
        if (value.startsWith("lightning:", ignoreCase = true)) value = value.substring("lightning:".length)
        if (value.isEmpty()) return null
        lightningAddressUrl(value)?.let {
            return it
        }
        if (value.startsWith("lnurl1", ignoreCase = true)) return decodeBech32(value)?.let(::resolveUrl)
        return resolveUrl(value)
    }

    /** `user@domain` to `https://domain/.well-known/lnurlp/user` (LUD-16). */
    public fun lightningAddressUrl(address: String): String? {
        val match = ADDRESS.matchEntire(address.trim()) ?: return null
        val (user, host) = match.destructured
        return "${schemeFor(host)}://${host.lowercase()}/.well-known/lnurlp/${user.lowercase()}"
    }

    /** The conventional withdraw URL next to a Lightning Address pay URL (`/.well-known/lnurlw/<name>`), or null. */
    public fun withdrawUrlForPayUrl(payUrl: String): String? =
        WELL_KNOWN_LNURLP.matchEntire(payUrl)?.destructured?.let { (base, name) -> "$base/.well-known/lnurlw/$name" }

    /** The Lightning Address username a pay URL is published under, or null. */
    public fun lightningAddressUsername(payUrl: String): String? = WELL_KNOWN_LNURLP.matchEntire(payUrl)?.groupValues?.get(2)

    /** Appends query parameters to [url], keeping any it already carries. Repeated keys are kept in order. */
    public fun withQuery(url: String, params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return url
        val query = params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val (base, fragment) = url.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
        val sep = if ('?' in base) (if (base.endsWith('?') || base.endsWith('&')) "" else "&") else "?"
        return base + sep + query + (fragment?.let { "#$it" } ?: "")
    }

    /** Parses a query string into ordered key/value pairs, decoding percent-escapes. */
    public fun queryParams(url: String): List<Pair<String, String>> {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyList()
        return query
            .split('&')
            .filter { it.isNotEmpty() }
            .map {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                decode(k) to decode(v)
            }
    }

    public fun host(url: String): String? = runCatching { URI(url).rawAuthority }.getOrNull()

    private fun resolveUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val rest = value.substring(value.indexOf("://") + 3)
        return when (scheme) {
            in LUD17_SCHEMES -> "${schemeFor(host)}://$rest"
            "https" -> "https://$rest"
            "http" -> if (isOnionOrLoopback(host)) "http://$rest" else null
            else -> null
        }
    }

    private fun schemeFor(hostWithPort: String): String {
        // An IPv6 host is bracketed and holds colons itself: its port comes after the closing bracket.
        val host = if (hostWithPort.startsWith('[')) hostWithPort.substringBefore(']') + "]" else hostWithPort.substringBefore(':')
        return if (isOnionOrLoopback(host.lowercase())) "http" else "https"
    }

    /** `.onion`, or loopback: `localhost`, a canonical IPv4 address in 127.0.0.0/8, `[::1]` or `0.0.0.0` (url-admission.json). */
    private fun isOnionOrLoopback(host: String): Boolean =
        host.endsWith(".onion") ||
            host == "localhost" ||
            LOOPBACK_IPV4.matches(host) ||
            host == "[::1]" ||
            host == "::1" ||
            host == "0.0.0.0"

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun decode(s: String) = java.net.URLDecoder.decode(s, Charsets.UTF_8)
}
