package whitewallet.lnurlcash.client.legacy

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.protocol.legacy.LegacyRequests

/*
 * Legacy: LUD-25/26 draft 2f17578. Removable once every mint the wallet uses runs LUD-26 at 92e430f or later (tag: mint); see
 * protocol/SPEC.md, "Removing legacy 2f17578".
 */

/** Legacy 2f17578: registers [username] against a branch `cx1` (see LegacyRequests.register). */
public suspend fun LnurlcashClient.legacyRegister(host: String, username: String, cx1: String, signatureHex: String): RegistrationOutcome =
    registration(
        Request.Builder().url(LegacyRequests.register(host, username, cx1, signatureHex)).post(ByteArray(0).toRequestBody()).build()
    )

/** Legacy 2f17578: frees [username] again (see LegacyRequests.unregister). */
public suspend fun LnurlcashClient.legacyUnregister(host: String, username: String, signatureHex: String): RegistrationOutcome =
    registration(Request.Builder().url(LegacyRequests.unregister(host, username, signatureHex)).delete().build())
