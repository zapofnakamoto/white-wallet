package whitewallet.core.wallet

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * The local regtest stack (dev/regtest): two real reference mints on regtest Lightning, and the user's LND node that
 * pays and invoices from outside. Tests using it call [requireStack] first; they are skipped unless
 * LNURLCASH_REGTEST=1.
 */
internal object Regtest {
    const val HOST = "localhost:8111"

    /** The second mint (2 sat base fee), funded by user-lnd. */
    const val HOST2 = "localhost:8112"

    /** The third mint (1 sat base fee), whose signing key [rotateMint3] changes. */
    const val HOST3 = "localhost:8113"

    private val script = File("../../dev/regtest/regtest.sh").canonicalFile
    private val random = SecureRandom()

    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    /** Runs regtest.sh with [args]; its result is the last line of the output (log lines are merged in). */
    fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "regtest.sh ${args.joinToString(" ")} failed:\n$output" }
        return output.trim().lines().last()
    }

    /** A username nobody has taken yet. */
    fun name(prefix: String = "rt") = prefix + (1..8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    /** Pays [target] (an address, invoice or pay link) from user-lnd. */
    fun pay(target: String, sats: Long) {
        run("pay", target, sats.toString())
    }

    /** A bearer note minted on the mint (the mint's fee comes off), as its link. */
    fun note(sats: Long, second: Boolean = false): String = if (second) run("note", "$sats", "--mint2") else run("note", "$sats")

    /** A bearer note minted on the third mint, as its link. */
    fun note3(sats: Long): String = run("note", "$sats", "--mint3")

    /**
     * Gives the third mint a new signing key, as a mint does that changes its key: it moves to the other Lightning
     * node, keeping its notes. Returns the new key.
     */
    fun rotateMint3(): String = run("rotate-mint3")

    /** The signing key [host] presents now. */
    suspend fun mintKey(host: String): String = checkNotNull(LnurlcashClient().withdrawDiscovery(host).mintPubkey).toHex()

    /** An invoice from user-lnd. */
    fun invoice(sats: Long): String = run("invoice", "$sats", "regtest test")

    /** An invoice from user-lnd committing to [descriptionHashHex], or with a plain memo when it is null. */
    fun invoice(amountMsat: Long, descriptionHashHex: String?): String {
        val description = if (descriptionHashHex != null) arrayOf("--description_hash", descriptionHashHex) else arrayOf("--memo", "regtest test")
        val out = lncliUser("addinvoice", "--amt_msat", "$amountMsat", *description)
        return checkNotNull(Regex("\"payment_request\":\\s*\"([^\"]+)\"").find(out)) { out }.groupValues[1]
    }

    /** Whether user-lnd has been paid [invoice]. */
    fun settled(invoice: String): Boolean {
        val hash = checkNotNull(whitewallet.lnurl.Bolt11.decode(invoice)).paymentHashHex
        return "\"SETTLED\"" in lncliUser("lookupinvoice", hash)
    }

    /**
     * An invoice on user-lnd whose payment stays in flight until [HoldInvoice.settle] or [HoldInvoice.cancel]: how a
     * test makes a Lightning payment pending, or fail.
     */
    fun holdInvoice(sats: Long): HoldInvoice {
        val preimage = ByteArray(32).also(random::nextBytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(preimage).toHex()
        val out = lncliUser("addholdinvoice", hash, "$sats")
        val invoice = checkNotNull(Regex("\"payment_request\":\\s*\"([^\"]+)\"").find(out)) { out }.groupValues[1]
        return HoldInvoice(invoice, preimage.toHex(), hash)
    }

    class HoldInvoice(val invoice: String, private val preimage: String, private val hash: String) {
        fun settle() {
            lncliUser("settleinvoice", preimage)
        }

        fun cancel() {
            lncliUser("cancelinvoice", hash)
        }

        /** user-lnd's view of it: OPEN, ACCEPTED (a payment is held), SETTLED or CANCELED. */
        fun state(): String = Regex("\"state\":\\s*\"([A-Z]+)\"").find(lncliUser("lookupinvoice", hash))!!.groupValues[1]
    }

    fun lncliUser(vararg args: String): String {
        val process = ProcessBuilder(listOf(script.path, "lncli-user") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "lncli ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    /** A wallet with its own store, onboarded with a fresh address on [host]. */
    suspend fun phone(
        strategy: String = ManualStrategy.ID,
        client: LnurlcashClient = LnurlcashClient(),
        host: String = HOST,
        collectDefaults: CollectDefaults = CollectDefaults(),
    ): Phone {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), client, policies = NotePolicies.InMemory(strategy), collectDefaults = collectDefaults)
        val name = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, host))
        return Phone(store, wallet, "$name@$host")
    }

    class Phone(val store: InMemoryWalletStore, val wallet: Wallet, val address: String) {
        /** Pays the address [sats] for each amount and refreshes: one note each, the mint's fee off. */
        suspend fun fund(vararg sats: Long) {
            sats.forEach { pay(address, it) }
            wallet.refresh()
        }

        suspend fun notes(host: String = HOST) =
            store.notes(store.currentAccountId()!!).first().filter { it.host == host && it.state == NoteState.Outstanding }

        suspend fun balance() = wallet.balanceMsat.first()

        /**
         * Refreshes until [settled] holds, for at most [timeoutMillis]: a mint learns a Lightning payment's outcome on
         * its own schedule, so the refresh right after it may still see it in flight.
         */
        suspend fun refreshUntil(timeoutMillis: Long = 30_000, settled: suspend () -> Boolean) {
            val until = System.currentTimeMillis() + timeoutMillis
            while (true) {
                wallet.refresh()
                if (settled() || System.currentTimeMillis() > until) return
                delay(1_000)
            }
        }
    }

    /**
     * A client whose requests to the mints go through [intercept]: to drop a request before it reaches the mint, lose
     * an answer after the mint acted on it, or tamper with a response.
     */
    fun client(intercept: Interceptor): LnurlcashClient =
        LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(intercept).build())

    /** A connection dropped on the way out: the mint never sees the request. */
    fun dropped(): Nothing = throw IOException("connection dropped before the request was sent")

    /** The mint acted on the request, but its answer never arrives. */
    fun lost(): Nothing = throw IOException("connection dropped before the answer arrived")
}

/**
 * A Lightning Address `carol@payee.example` that is no mint: its LUD-16 pay link is answered on the wire (it never
 * reaches a server), and its invoices come from user-lnd, so paying it is a real Lightning payment. [invoiceFor] makes
 * the invoice for an amount; by default it commits to the pay link's metadata.
 */
internal class LightningAddressPayee(private val invoiceFor: (amountMsat: Long, metadata: String) -> String = { amount, metadata ->
    Regtest.invoice(amount, Payer.sha256Hex(metadata))
}) : Interceptor {
    val address = "carol@payee.example"
    val metadata = """[["text/plain","Pay Carol"],["text/identifier","carol@payee.example"]]"""
    var lastComment: String? = null
    var lastInvoice: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "payee.example") return chain.proceed(request)
        val body = when (request.url.encodedPath) {
            "/.well-known/lnurlp/carol" -> """{"tag":"payRequest","callback":"https://payee.example/cb","minSendable":1000,"maxSendable":100000000,""" +
                """"metadata":${JsonPrimitive(metadata)},"commentAllowed":140}"""
            "/cb" -> {
                lastComment = request.url.queryParameter("comment")
                val invoice = invoiceFor(request.url.queryParameter("amount")!!.toLong(), metadata).also { lastInvoice = it }
                """{"pr":"$invoice","routes":[]}"""
            }
            else -> return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("".toResponseBody()).build()
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}

/** A note-changing request (rotate, merge, split, melt) rather than a lookup, info or pay request. */
internal val HttpUrl.isMutation: Boolean get() = queryParameter("k1") != null && (queryParameter("p1") != null || queryParameter("pr") != null)

/** A melt: a note paying an invoice. */
internal val HttpUrl.isMelt: Boolean get() = queryParameter("k1") != null && queryParameter("pr") != null

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
