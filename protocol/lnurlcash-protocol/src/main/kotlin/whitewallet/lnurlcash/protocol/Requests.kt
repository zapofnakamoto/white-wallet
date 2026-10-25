package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.Cp1

/** Builds every LNURLcash request URL. Pure: no I/O. Outputs are always sent as `cp1`, so a certificate is owed. */
public object Requests {
    /** The pay callback that mints a note at [output] (`comment = cp1<Q>`). */
    public fun mintInvoice(callback: String, amountMsat: Long, output: ByteVector32): String =
        Lnurl.withQuery(callback, listOf("amount" to amountMsat.toString(), "comment" to Cp1.encode(output)))

    /** The informational GET for a spend. Never burns. */
    public fun info(withdrawUrl: String, k1: String): String = Lnurl.withQuery(withdrawUrl, listOf("k1" to k1))

    /** A lookup by output key, which keeps the spend off the wire. Never valid at the callback. */
    public fun lookup(withdrawUrl: String, q: ByteVector32): String = Lnurl.withQuery(withdrawUrl, listOf("p" to Cp1.encode(q)))

    public fun rotate(callback: String, k1: String, p1: ByteVector32): String =
        Lnurl.withQuery(callback, listOf("k1" to k1, "p1" to Cp1.encode(p1)))

    public fun split(callback: String, k1s: List<String>, amountMsat: Long, p1: ByteVector32, p2: ByteVector32): String {
        require(k1s.isNotEmpty()) { "nothing to burn" }
        return Lnurl.withQuery(callback, k1s.map { "k1" to it } + listOf("amount" to amountMsat.toString(), "p1" to Cp1.encode(p1), "p2" to Cp1.encode(p2)))
    }

    public fun merge(callback: String, k1s: List<String>, p1: ByteVector32): String {
        require(k1s.size >= 2) { "merge needs at least two notes" }
        return Lnurl.withQuery(callback, k1s.map { "k1" to it } + ("p1" to Cp1.encode(p1)))
    }

    /** Melt: pay [invoice] from one note. Never retried. */
    public fun melt(callback: String, k1: String, invoice: String): String =
        Lnurl.withQuery(callback, listOf("k1" to k1, "pr" to invoice))

    /** The discovery URLs for a service's own identity (`_`). */
    public fun servicePayUrl(host: String): String = checkNotNull(Lnurl.lightningAddressUrl("_@$host")) { "invalid host $host" }

    public fun serviceWithdrawDiscoveryUrl(host: String): String = checkNotNull(Lnurl.withdrawUrlForPayUrl(servicePayUrl(host)))

    /**
     * Username registration: `POST <service>/p/<username>?cx1=&sig=`.
     * Not specified by LUD-26; this is the endpoint both reference mints implement (see protocol/SPEC.md).
     */
    public fun register(host: String, username: String, cx1: String, signatureHex: String): String {
        val base = servicePayUrl(host).substringBefore("/.well-known/")
        return Lnurl.withQuery("$base/p/$username", listOf("cx1" to cx1, "sig" to signatureHex))
    }

    /** `DELETE` target that frees [username] again, proven by an unregister signature (see AddressProof). */
    public fun unregister(host: String, username: String, signatureHex: String): String {
        val base = servicePayUrl(host).substringBefore("/.well-known/")
        return Lnurl.withQuery("$base/p/$username", listOf("sig" to signatureHex))
    }
}
