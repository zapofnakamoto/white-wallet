package whitewallet.lnurlcash.protocol.legacy

import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.protocol.Requests

/*
 * Legacy: LUD-25/26 draft 2f17578. Removable once every mint the wallet uses runs LUD-26 at 92e430f or later (tag: mint); see
 * protocol/SPEC.md, "Removing legacy 2f17578".
 */

/** Legacy 2f17578 registration: `POST <mint>/p/<username>?cx1=&sig=` and `DELETE` with `?sig=`, as the reference mints implemented it. */
public object LegacyRequests {
    public fun register(host: String, username: String, cx1: String, signatureHex: String): String =
        Lnurl.withQuery("${base(host)}/p/$username", listOf("cx1" to cx1, "sig" to signatureHex))

    /** `DELETE` target that frees [username] again, proven by an unregister signature (see LegacyAddressProof). */
    public fun unregister(host: String, username: String, signatureHex: String): String =
        Lnurl.withQuery("${base(host)}/p/$username", listOf("sig" to signatureHex))

    private fun base(host: String): String = Requests.servicePayUrl(host).substringBefore("/.well-known/")
}

/** Legacy 2f17578 `text/cpub` metadata: the payee's `cx1` and the mint's best-known next index of its purpose-2 counter. */
public object LegacyCpub {
    public fun from(pay: LnurlResponse.PayRequest): Pair<String, Long>? =
        pay.metadataEntries
            .firstOrNull { it.first == "text/cpub" }
            ?.second
            ?.let { value -> value.substringAfterLast(':', "").toLongOrNull()?.let { value.substringBeforeLast(':') to it } }

    /** The published `cx1` alone, whatever follows it. */
    public fun cx1(pay: LnurlResponse.PayRequest): String? =
        pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second?.substringBeforeLast(':')
}
