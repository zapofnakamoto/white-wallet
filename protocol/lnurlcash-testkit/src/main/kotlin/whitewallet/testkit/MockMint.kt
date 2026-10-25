package whitewallet.testkit

import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The lnurlcash-conformance adversarial mock mint, run as a Node process.
 *
 * Needs `node` and a checkout of lnurlcash-conformance (with `npm ci` run) at `$LNURLCASH_CONFORMANCE`.
 * Tests call [available] and skip when it is missing.
 */
public class MockMint private constructor(private val process: Process, public val url: String) : AutoCloseable {
    /** `127.0.0.1:<port>`, the host as a wallet stores it. */
    public val host: String get() = URI(url).rawAuthority

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
        private val dir: File? get() = System.getenv("LNURLCASH_CONFORMANCE")?.let(::File)?.takeIf { File(it, "mock-mint/index.mjs").exists() }

        public val available: Boolean get() = dir != null

        public fun start(vararg flags: String): MockMint {
            val root = checkNotNull(dir) { "LNURLCASH_CONFORMANCE is not set" }
            val process = ProcessBuilder(listOf("node", "mock-mint/index.mjs", "--testHooks") + flags)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            val line = reader.readLine() ?: error("mock mint exited without announcing itself")
            val url = Regex("listening on (\\S+)").find(line)?.groupValues?.get(1) ?: error("unexpected mock mint output: $line")
            Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
            return MockMint(process, url)
        }
    }
}
