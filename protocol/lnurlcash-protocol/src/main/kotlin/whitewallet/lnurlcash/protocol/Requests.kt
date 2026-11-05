package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import whitewallet.lnurl.Lnurl
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.RevealsNoteSecret

/** Builds every LNURLcash request URL. Pure: no I/O. Outputs are always sent as `cp1`, so a certificate is owed. */
public object Requests {
    /** The pay callback that mints a note at [output] (`comment = cp1<Q>`). */
    public fun mintInvoice(callback: String, amountMsat: Long, output: ByteVector32): String =
        Lnurl.withQuery(callback, listOf("amount" to amountMsat.toString(), "comment" to Cp1.encode(output)))

    /** A lookup by output key, which keeps the spend off the wire. Never valid at the callback. */
    public fun lookup(withdrawUrl: String, q: ByteVector32): String = Lnurl.withQuery(withdrawUrl, listOf("p" to Cp1.encode(q)))

    @RevealsNoteSecret
    public fun rotate(callback: String, k1: String, p1: ByteVector32): String =
        Lnurl.withQuery(callback, listOf("k1" to k1, "p1" to Cp1.encode(p1)))

    @RevealsNoteSecret
    public fun split(callback: String, k1s: List<String>, amountMsat: Long, p1: ByteVector32, p2: ByteVector32): String {
        require(k1s.isNotEmpty()) { "nothing to burn" }
        return Lnurl.withQuery(
            callback,
            k1s.map { "k1" to it } + listOf("amount" to amountMsat.toString(), "p1" to Cp1.encode(p1), "p2" to Cp1.encode(p2)),
        )
    }

    @RevealsNoteSecret
    public fun merge(callback: String, k1s: List<String>, p1: ByteVector32): String {
        require(k1s.size >= 2) { "merge needs at least two notes" }
        return Lnurl.withQuery(callback, k1s.map { "k1" to it } + ("p1" to Cp1.encode(p1)))
    }

    /** Melt: pay [invoice] from one note. Never retried. */
    @RevealsNoteSecret
    public fun melt(callback: String, k1: String, invoice: String): String = Lnurl.withQuery(callback, listOf("k1" to k1, "pr" to invoice))

    /**
     * Internal transfer (LUD-26): pays a Lightning address on the same mint with a whole note instead of an invoice, at its payRequest
     * [callback]. [amountMsat] is the note's full value.
     */
    @RevealsNoteSecret
    public fun transfer(callback: String, amountMsat: Long, k1: String): String =
        Lnurl.withQuery(callback, listOf("amount" to amountMsat.toString(), "k1" to k1))

    /**
     * The discovery URLs for a mint's own identity, `_@<host>`. LUD-26 names `mint@<domain>` for it, which lnurl-mint answers too, but the
     * legacy 2f17578 mock mint only knows `_`; move to [MINT_USERNAME] with the mock mint's pin.
     */
    public fun servicePayUrl(host: String): String = addressUrl(host, "_")

    public fun serviceWithdrawDiscoveryUrl(host: String): String = checkNotNull(Lnurl.withdrawUrlForPayUrl(servicePayUrl(host)))

    /** `<scheme>://<host>/.well-known/lnurlp/<username>`: a Lightning address, and where LUD-26 registers it. */
    public fun addressUrl(host: String, username: String): String =
        checkNotNull(Lnurl.lightningAddressUrl("$username@$host")) { "invalid address $username@$host" }

    /** The username every mint keeps for its own payRequest; never registrable. */
    public const val MINT_USERNAME: String = "mint"
}

/** A request whose parameters go in an `application/x-www-form-urlencoded` body (LUD-26 registration). */
public data class FormRequest(val method: String, val url: String, val fields: List<Pair<String, String>>)

/** LUD-26 registration at the Lightning address it claims: POST registers, PUT moves it to another share, DELETE frees it. */
public object Registration {
    public fun register(host: String, username: String, cx1: String, sig: String): FormRequest =
        FormRequest("POST", Requests.addressUrl(host, username.lowercase()), listOf("cx1" to cx1, "sig" to sig))

    public fun update(host: String, username: String, cx1: String, sig: String, newsig: String): FormRequest =
        FormRequest("PUT", Requests.addressUrl(host, username.lowercase()), listOf("cx1" to cx1, "sig" to sig, "newsig" to newsig))

    public fun unregister(host: String, username: String, sig: String): FormRequest =
        FormRequest("DELETE", Requests.addressUrl(host, username.lowercase()), listOf("sig" to sig))

    /**
     * A request that changes nothing on any mint but tells the two protocols apart: a mint on LUD-26 answers a PUT on its own reserved name
     * with an LNURL error, one on the legacy draft 2f17578 has no PUT there at all (HTTP 405).
     */
    public fun dialectProbe(host: String): FormRequest = FormRequest("PUT", Requests.addressUrl(host, Requests.MINT_USERNAME), emptyList())
}
