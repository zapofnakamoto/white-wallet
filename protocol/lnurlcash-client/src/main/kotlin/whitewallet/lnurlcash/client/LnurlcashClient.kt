package whitewallet.lnurlcash.client

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.FormRequest
import whitewallet.lnurlcash.protocol.HttpResult
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.MutationClassifier
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.Registration
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.protocol.WithdrawDiscovery

/**
 * The only place LNURLcash requests touch the network.
 *
 * Every mutation is an HTTP GET that burns its input, so transport-level retries are forbidden: a resend after a dropped connection could
 * be reported as "already spent" while the first attempt minted the outputs. The only retry is a bounded, byte-identical replay of a
 * rotate, split, merge or transfer, which LUD-25 and LUD-26 require a service to answer with its original success. A melt is never
 * replayed, nor any request carrying an invoice (`pr`), whatever type it is sent as: it may pay twice. Only a replay's success replaces an
 * unanswered attempt: a refused replay leaves the outcome ambiguous, since the first attempt may have landed.
 *
 * Servers close idle keep-alive connections (uvicorn after ~5 s), and OkHttp would otherwise reuse such a dead pooled connection after any
 * pause. So read-only requests retry once on a connection failure (they change nothing), on a fresh connection after dropping the idle
 * ones, which idled as long (a scan leaves several); mutations and registrations always go out on a fresh connection, never an idle pooled
 * one.
 *
 * A note's spend (`k1`) leaves only with [mutate] (and [withdrawLink], for a link the user agreed to show its own service): whoever gets it
 * can claim the note, so a note is checked by its key ([lookup]). [SpendGuard], the last step before the wire, refuses any other request
 * carrying a spend, also one an interceptor of [http] rewrote.
 */
public class LnurlcashClient(
    http: OkHttpClient = defaultHttpClient(),
    private val mutationReplays: Int = 1,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(!http.retryOnConnectionFailure) { "LNURLcash requests must not be retried by the HTTP client" }
        require(mutationReplays >= 0)
    }

    private val http: OkHttpClient = http.newBuilder().addNetworkInterceptor(SpendGuard).build()

    /** Same settings and interceptors, but keeps no idle connections, so every request opens a fresh one. */
    private val freshConnectionHttp: OkHttpClient = this.http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()

    /**
     * GETs [url] and parses the LNURL response. Only for read-only requests: retried once on a connection failure. Throws [IOException] if
     * both attempts fail.
     */
    public suspend fun get(url: String): LnurlResponse = read(Request.Builder().url(url).get().build())

    /**
     * GETs a withdraw link as it was given, `k1` included (LUD-03's first request), and parses the answer like [get]. Only for a link whose
     * `k1` is no note's spend, or one the user agreed to show to the link's own service: a note's spend otherwise only goes out with
     * [mutate]. Read-only, so retried once like [get].
     */
    @RevealsNoteSecret
    public suspend fun withdrawLink(url: String): LnurlResponse =
        read(Request.Builder().url(url).get().tag(SpendGuard.Mutation::class.java, SpendGuard.Mutation).build())

    /** A read-only [request] (see [readOnce]), its answer parsed as an LNURL response. */
    private suspend fun read(request: Request): LnurlResponse =
        when (val result = readOnce(request)) {
            is HttpResult.TransportFailure -> throw IOException(result.message)
            is HttpResult.Response ->
                LnurlResponse.parse(result.body) ?: throw ProtocolViolation("not an LNURL response (HTTP ${result.status})")
        }

    /** The service's minting payRequest for its own identity (`_@host`). */
    public suspend fun servicePayRequest(host: String): MintingPayRequest = MintingPayRequest.from(get(Requests.servicePayUrl(host)))

    /** The service's withdraw discovery document, which carries `mintPubkey` on the reference mints. */
    public suspend fun withdrawDiscovery(host: String): WithdrawDiscovery =
        WithdrawDiscovery.from(get(Requests.serviceWithdrawDiscoveryUrl(host)))

    /** Looks a note up by its output key without disclosing the spend. Throws NoteLookupError if unknown or spent. */
    public suspend fun lookup(withdrawUrl: String, q: ByteVector32): NoteInfo = NoteInfo.from(get(Requests.lookup(withdrawUrl, q)), null)

    /**
     * Sends a mutation URL built by [Requests] and classifies the outcome. The only request that carries a spend. Replayed when its answer
     * is lost, unless it is a melt: [type] says so, or the URL carrying an invoice (`pr`) does.
     */
    @RevealsNoteSecret
    public suspend fun mutate(type: MutationType, url: String): MutationOutcome {
        val request = Request.Builder().url(url).get().tag(SpendGuard.Mutation::class.java, SpendGuard.Mutation).build()
        val pays = type == MutationType.Melt || request.url.queryParameterNames.any { it.equals("pr", ignoreCase = true) }
        val replays = if (pays) 0 else mutationReplays
        var outcome = MutationClassifier.classify(type, execute(request, freshConnectionHttp))
        repeat(replays) {
            if (outcome !is MutationOutcome.Ambiguous) return outcome
            // A refused replay says nothing about the attempt before it, which may have landed: only a success settles that.
            outcome =
                when (val replay = MutationClassifier.classify(type, execute(request, freshConnectionHttp))) {
                    is MutationOutcome.Rejected -> MutationOutcome.Ambiguous("replay refused: ${replay.reason ?: replay.kind}")
                    else -> replay
                }
        }
        return outcome
    }

    /** Registers [username] for a share (LUD-26 POST). The mint may hold it for an address fee ([RegistrationOutcome.AwaitingFee]). */
    public suspend fun register(host: String, username: String, cx1: String, sig: String): RegistrationOutcome =
        registration(Registration.register(host, username, cx1, sig))

    /** Moves [username] to another share without freeing it (LUD-26 PUT): `sig` by the key on file, `newsig` by the new share. */
    public suspend fun updateRegistration(host: String, username: String, cx1: String, sig: String, newsig: String): RegistrationOutcome =
        registration(Registration.update(host, username, cx1, sig, newsig))

    /** Frees [username] again (LUD-26 DELETE). [RegistrationOutcome.Registered] means the service accepted it. */
    public suspend fun unregister(host: String, username: String, sig: String): RegistrationOutcome =
        registration(Registration.unregister(host, username, sig))

    /** A read-only [request], retried once after a connection failure (see the class). */
    private suspend fun readOnce(request: Request): HttpResult =
        execute(request).let {
            if (it !is HttpResult.TransportFailure) return it
            // Closing a pooled TLS connection writes to it: never on the caller's thread, which may be Android's main thread.
            withContext(io) { http.connectionPool.evictAll() }
            execute(request, freshConnectionHttp)
        }

    private suspend fun registration(form: FormRequest): RegistrationOutcome =
        registration(Request.Builder().url(form.url).method(form.method, formBody(form.fields)).build())

    /** Registration changes always go out on a fresh connection and are never retried. */
    internal suspend fun registration(request: Request): RegistrationOutcome {
        return when (val result = execute(request, freshConnectionHttp)) {
            is HttpResult.TransportFailure -> RegistrationOutcome.Ambiguous(result.message)
            is HttpResult.Response ->
                when (val r = LnurlResponse.parse(result.body)) {
                    is LnurlResponse.Error -> RegistrationOutcome.Rejected(RegistrationRejection.fromReason(r.reason), r.reason)
                    is LnurlResponse.Ok -> r.raw.string("pr")?.let { RegistrationOutcome.AwaitingFee(it) } ?: RegistrationOutcome.Registered
                    else ->
                        if (result.status in 200..299) RegistrationOutcome.Registered
                        else RegistrationOutcome.Rejected(RegistrationRejection.Other, "HTTP ${result.status}")
                }
        }
    }

    private fun formBody(fields: List<Pair<String, String>>): RequestBody =
        FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()

    private suspend fun execute(request: Request, client: OkHttpClient = http): HttpResult =
        withContext(io) {
            try {
                client.newCall(request).execute().use { response -> HttpResult.Response(response.code, response.body.string()) }
            } catch (e: IOException) {
                HttpResult.TransportFailure(e.message ?: e.javaClass.simpleName)
            }
        }

    public companion object {
        public fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

public sealed interface RegistrationOutcome {
    public data object Registered : RegistrationOutcome

    /**
     * Accepted, but held until the address fee is paid (LUD-26): [invoice] is a BOLT-11 invoice for it. Until it settles, the mint
     * publishes nothing for the name; if it expires unpaid, the name is free again.
     */
    public data class AwaitingFee(val invoice: String) : RegistrationOutcome

    /** Refused, e.g. because the username is taken. */
    public data class Rejected(val kind: RegistrationRejection, val reason: String?) : RegistrationOutcome

    public data class Ambiguous(val cause: String) : RegistrationOutcome
}

/** Why a mint refused a registration, update or unregistration. */
public enum class RegistrationRejection {
    /** The name is registered, or held for an unpaid address fee (a POST never overwrites). */
    AlreadyRegistered,

    /** This share's key was accepted before, for any name: each share registers once. */
    Cx1AlreadyUsed,

    /** No such registration (a PUT or DELETE for a free name). */
    UnknownUser,

    /** A proof didn't verify: the name is not held by the key that signed. Not a reason the spec defines; lnurl-mint's wording. */
    InvalidSignature,
    RateLimited,
    Other;

    public companion object {
        public fun fromReason(reason: String?): RegistrationRejection {
            val r = reason?.trim()?.lowercase() ?: return Other
            return when {
                "already registered" in r -> AlreadyRegistered
                "cx1 already used" in r -> Cx1AlreadyUsed
                "unknown user" in r -> UnknownUser
                "signature" in r -> InvalidSignature
                "rate limited" in r -> RateLimited
                else -> Other
            }
        }
    }
}
