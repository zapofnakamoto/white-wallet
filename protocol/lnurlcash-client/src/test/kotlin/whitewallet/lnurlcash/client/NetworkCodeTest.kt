package whitewallet.lnurlcash.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only [LnurlcashClient] talks to the network, and only the wallet's SecretGate sends a spend through it. Anything else would be a way out
 * for a note's spend that SpendGuard and SecretGate never see, so this scans every app source (`src/main`) of the repository and fails on
 * network code outside [ALLOWED].
 *
 * Adding to [ALLOWED] is a deliberate change: say why next to the entry.
 */
class NetworkCodeTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(): List<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .toList()

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    @Test
    fun `no network code outside the client`() {
        val sources = sources()
        assertTrue(sources.any { relative(it).endsWith("LnurlcashClient.kt") }, "the scan found the sources")
        val found =
            sources
                .filterNot { file -> ALLOWED.keys.any { relative(file).startsWith(it) } }
                .flatMap { file -> networkCode(file.readText()).map { "${relative(file)}: $it" } }
        assertEquals(emptyList(), found, "network code outside LnurlcashClient (see NetworkCodeTest.ALLOWED)")
    }

    @Test
    fun `only SecretGate sends a mutation`() {
        val callers = sources().filter { MUTATE.containsMatchIn(it.readText()) }.map(::relative)
        assertEquals(listOf("core/wallet/src/main/kotlin/whitewallet/core/wallet/SecretGate.kt"), callers)
    }

    @Test
    fun `the scan finds network code`() {
        assertEquals(1, networkCode("import java.net.URL\n").size)
        assertEquals(1, networkCode("val c = java.net.URL(u).openConnection()\n").size)
        assertEquals(1, networkCode("import okhttp3.OkHttpClient\n").size)
        assertEquals(1, networkCode("import android.webkit.WebView\n").size)
        assertEquals(emptyList(), networkCode("import java.net.URI\nval s = java.net.URLEncoder.encode(x, UTF_8)\n"))
    }

    private companion object {
        /** Paths (from the repository root) allowed to hold network code, and why. */
        val ALLOWED =
            mapOf(
                "protocol/lnurlcash-client/src/main/" to "the client: every request to a mint",
                "core/wallet/src/main/kotlin/whitewallet/core/wallet/RegtestFaucet.kt" to
                    "regtest only: pays and makes invoices at the local faucet, never handles a note",
            )

        val NETWORK =
            listOf(
                Regex("""^import (okhttp3|io\.ktor|retrofit2|android\.webkit|javax\.net|org\.apache\.http|com\.android\.volley)\."""),
                Regex("""^import android\.app\.DownloadManager\b"""),
                Regex("""java\.net\.(URL|HttpURLConnection|Socket|ServerSocket|DatagramSocket)\b"""),
                Regex("""java\.net\.http\."""),
                Regex("""\.(openConnection|openStream)\("""),
            )

        /** A call of LnurlcashClient.mutate, the one request that carries a spend (it also needs @OptIn(RevealsNoteSecret)). */
        val MUTATE = Regex("""\bclient\.mutate\(|\.mutate\(\s*MutationType\.""")

        fun networkCode(text: String): List<String> = text.lines().filter { line -> NETWORK.any { it.containsMatchIn(line.trim()) } }
    }
}
