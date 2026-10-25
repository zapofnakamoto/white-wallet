package whitewallet.lnurlcash.client

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.protocol.HttpResult
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.MutationClassifier
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.protocol.WithdrawDiscovery

/**
 * The only place LNURLcash requests touch the network.
 *
 * Every mutation is an HTTP GET that burns its input, so transport-level retries are forbidden: a resend after a
 * dropped connection could be reported as "already spent" while the first attempt minted the outputs. The only
 * retry is a bounded, byte-identical replay of rotate/split/merge, which LUD-25 requires a service to answer with
 * its original success. A melt is never replayed.
 *
 * Servers close idle keep-alive connections (uvicorn after ~5 s), and OkHttp would otherwise reuse such a dead
 * pooled connection after any pause. So read-only requests retry once on a connection failure (they change
 * nothing), and mutations and registrations always go out on a fresh connection, never an idle pooled one.
 */
public class LnurlcashClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val mutationReplays: Int = 1,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(!http.retryOnConnectionFailure) { "LNURLcash requests must not be retried by the HTTP client" }
        require(mutationReplays >= 0)
    }

    /** Same settings and interceptors, but keeps no idle connections, so every request opens a fresh one. */
    private val freshConnectionHttp: OkHttpClient = http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()

    /**
     * GETs [url] and parses the LNURL response. Only for read-only requests: retried once on a connection failure.
     * Throws [IOException] if both attempts fail.
     */
    public suspend fun get(url: String): LnurlResponse {
        val request = Request.Builder().url(url).get().build()
        val result = execute(request).let { if (it is HttpResult.TransportFailure) execute(request) else it }
        return when (result) {
            is HttpResult.TransportFailure -> throw IOException(result.message)
            is HttpResult.Response -> LnurlResponse.parse(result.body)
                ?: throw ProtocolViolation("not an LNURL response (HTTP ${result.status})")
        }
    }

    /** The service's minting payRequest for its own identity (`_@host`). */
    public suspend fun servicePayRequest(host: String): MintingPayRequest = MintingPayRequest.from(get(Requests.servicePayUrl(host)))

    /** The service's withdraw discovery document, which carries `mintPubkey` on the reference mints. */
    public suspend fun withdrawDiscovery(host: String): WithdrawDiscovery =
        WithdrawDiscovery.from(get(Requests.serviceWithdrawDiscoveryUrl(host)))

    /** The informational GET for a spend. Verifies the echoed k1; never burns. */
    public suspend fun noteInfo(withdrawUrl: String, k1: String): NoteInfo = NoteInfo.from(get(Requests.info(withdrawUrl, k1)), k1)

    /** Looks a note up by its output key without disclosing the spend. Throws NoteLookupError if unknown or spent. */
    public suspend fun lookup(withdrawUrl: String, q: ByteVector32): NoteInfo = NoteInfo.from(get(Requests.lookup(withdrawUrl, q)), null)

    /** Sends a mutation URL built by [Requests] and classifies the outcome. */
    public suspend fun mutate(type: MutationType, url: String): MutationOutcome {
        val replays = if (type == MutationType.Melt) 0 else mutationReplays
        val request = Request.Builder().url(url).get().build()
        var outcome = MutationClassifier.classify(type, execute(request, freshConnectionHttp))
        repeat(replays) {
            if (outcome !is MutationOutcome.Ambiguous) return outcome
            outcome = MutationClassifier.classify(type, execute(request, freshConnectionHttp))
        }
        return outcome
    }

    /** Registers [username] against a `cx1` (see Requests.register). */
    public suspend fun register(host: String, username: String, cx1: String, signatureHex: String): RegistrationOutcome =
        registration(Request.Builder().url(Requests.register(host, username, cx1, signatureHex)).post(ByteArray(0).toRequestBody()).build())

    /** Frees [username] again (see Requests.unregister). [RegistrationOutcome.Registered] means the service accepted it. */
    public suspend fun unregister(host: String, username: String, signatureHex: String): RegistrationOutcome =
        registration(Request.Builder().url(Requests.unregister(host, username, signatureHex)).delete().build())

    /** Registration changes always go out on a fresh connection and are never retried. */
    private suspend fun registration(request: Request): RegistrationOutcome {
        return when (val result = execute(request, freshConnectionHttp)) {
            is HttpResult.TransportFailure -> RegistrationOutcome.Ambiguous(result.message)
            is HttpResult.Response -> when (val r = LnurlResponse.parse(result.body)) {
                is LnurlResponse.Error -> RegistrationOutcome.Rejected(RejectionKind.fromReason(r.reason), r.reason)
                is LnurlResponse.Ok -> RegistrationOutcome.Registered
                else -> if (result.status in 200..299) RegistrationOutcome.Registered else RegistrationOutcome.Rejected(RejectionKind.Other, "HTTP ${result.status}")
            }
        }
    }

    private suspend fun execute(request: Request, client: OkHttpClient = http): HttpResult = withContext(io) {
        try {
            client.newCall(request).execute().use { response -> HttpResult.Response(response.code, response.body.string()) }
        } catch (e: IOException) {
            HttpResult.TransportFailure(e.message ?: e.javaClass.simpleName)
        }
    }

    public companion object {
        public fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

public sealed interface RegistrationOutcome {
    public data object Registered : RegistrationOutcome

    /** Refused, e.g. because the username is taken. */
    public data class Rejected(val kind: RejectionKind, val reason: String?) : RegistrationOutcome

    public data class Ambiguous(val cause: String) : RegistrationOutcome
}
