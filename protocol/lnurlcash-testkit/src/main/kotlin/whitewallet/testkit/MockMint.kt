package whitewallet.testkit

import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The lnurlcash-conformance adversarial mock mint, run as a Node process.
 *
 * Needs `node` and the conformance suite that dev/conformance.sh prepares; the build passes its path as `LNURLCASH_CONFORMANCE`. Tests call
 * [available] and skip when it is missing.
 */
public class MockMint private constructor(private val process: Process, public val url: String) : AutoCloseable {
    /** `127.0.0.1:<port>`, the host as a wallet stores it. */
    public val host: String
        get() = URI(url).rawAuthority

    /** Credits a note by output (`cp1` or hex `h`) using the test hook. */
    public fun credit(cp1: String, amountMsat: Long) {
        val body = URI("$url/_test/credit?p=$cp1&amount=$amountMsat").toURL().readText()
        check("\"OK\"" in body) { "credit failed: $body" }
    }

    override fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    public companion object {
        private const val START_SECONDS = 30L

        private val dir: File?
            get() =
                System.getenv("LNURLCASH_CONFORMANCE")?.let(::File)?.takeIf {
                    File(it, "mock-mint/index.mjs").exists() && File(it, "node_modules/.package-lock.prepared").exists()
                }

        public val available: Boolean
            get() = dir != null

        public fun start(vararg flags: String): MockMint {
            val root = checkNotNull(dir) { "no conformance suite; run dev/conformance.sh" }
            val process =
                ProcessBuilder(listOf("node", "mock-mint/index.mjs", "--testHooks") + flags)
                    .directory(root)
                    .redirectErrorStream(true)
                    .start()
            val reader = process.inputStream.bufferedReader()
            // A mock mint that never announces itself fails the test instead of holding it for good.
            val announced = CompletableFuture.supplyAsync { reader.readLine() }
            val line =
                try {
                    announced.get(START_SECONDS, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    process.destroyForcibly()
                    error("mock mint did not announce itself within $START_SECONDS s")
                } ?: error("mock mint exited without announcing itself")
            val url = Regex("listening on (\\S+)").find(line)?.groupValues?.get(1) ?: error("unexpected mock mint output: $line")
            Thread { reader.forEachLine {} }.apply { isDaemon = true }.start()
            return MockMint(process, url)
        }
    }
}
