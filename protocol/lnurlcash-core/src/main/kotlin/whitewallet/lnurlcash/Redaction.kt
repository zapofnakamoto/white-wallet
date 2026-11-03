package whitewallet.lnurlcash

/**
 * Masks bearer secrets: `k1` spends in URLs and JSON, and payment preimages. Also address proofs (`sig`): an unregister
 * proof stays valid for its name, so a leaked one could free the name later. Everything else is public.
 */
public object Redaction {
    private const val KEEP = 6
    private val QUERY_K1 = Regex("([?&](?:k1|sig)=)([^&#]+)")
    private val JSON_SECRET = Regex("(\"(?:k1|preimage)\"\\s*:\\s*\")([^\"]*)(\")")

    public fun url(url: String): String = QUERY_K1.replace(url) { m -> m.groupValues[1] + mask(m.groupValues[2]) }

    public fun body(body: String): String = JSON_SECRET.replace(body) { m -> m.groupValues[1] + mask(m.groupValues[2]) + m.groupValues[3] }

    /** [secret] with all but its first few characters (its kind: `ck1`, `cw1`, …) masked. */
    public fun mask(secret: String): String = if (secret.length <= KEEP) "…" else secret.take(KEEP) + "…(redacted)"
}
