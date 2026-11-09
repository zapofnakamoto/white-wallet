package whitewallet.core.wallet

import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Mint
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo

/** The one place a spend goes out sends nothing unless the request is journaled, the key pinned, and the callback the mint's. */
class SecretGateTest {
    private val server =
        MockWebServer().apply {
            repeat(3) { enqueue(MockResponse(body = """{"status":"OK"}""")) }
            start()
        }
    private val host = "${server.hostName}:${server.port}"
    private val withdrawUrl = "http://$host/w"
    private val callback = "http://$host/cb"
    private val key = PublicKey(Hex.decode("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"))
    private val otherKey = PublicKey(Hex.decode("02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"))
    private val store = InMemoryWalletStore()
    private val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())

    @AfterTest fun stop() = server.close()

    private fun entry(requestUrl: String = "$callback?k1=a&p1=cp1x") =
        JournalEntry(
            "j1",
            "acc",
            OperationType.Rotate,
            host,
            withdrawUrl,
            listOf("q"),
            listOf("a"),
            emptyList(),
            requestUrl,
            JournalStatus.Prepared,
            null,
            1,
            1,
        )

    private fun release(entry: JournalEntry, info: NoteInfo = NoteInfo(callback, 1_000, key, null)) = runBlocking {
        SecretGate(wallet).release(entry, info, MutationType.Rotate)
    }

    private fun transfer(entry: JournalEntry, to: String) = runBlocking {
        SecretGate(wallet).release(entry, NoteInfo(callback, 1_000, key, null), MutationType.Transfer, to)
    }

    private suspend fun pin(key: PublicKey?) {
        if (store.account("acc") == null) store.addAccount("acc", "Main", null, host)
        store.saveMint(Mint("acc", host, withdrawUrl, key?.toHex(), 1_000, 1_000_000, null, null))
    }

    @Test
    fun `a journaled request to the mint's callback goes out once, unchanged`() =
        runBlocking<Unit> {
            pin(key)
            store.putJournal(entry())
            assertIs<SecretGate.Released.Sent>(release(entry()))
            assertEquals(1, server.requestCount)
            assertEquals("/cb?k1=a&p1=cp1x", server.takeRequest().target)
        }

    @Test
    fun `a transfer goes out to the payee's pay callback on the mint's own origin`() =
        runBlocking<Unit> {
            pin(key)
            val payee = server.url("/p/bob").toString()
            val entry = entry(requestUrl = "$payee?amount=1000&k1=a")
            store.putJournal(entry)
            assertIs<SecretGate.Released.Sent>(transfer(entry, payee))
            assertEquals("/p/bob?amount=1000&k1=a", server.takeRequest().target)
        }

    @Test
    fun `no transfer goes out to a pay callback on any other origin`() =
        runBlocking<Unit> {
            pin(key)
            val elsewhere = "https://payee.example/p/bob"
            val entry = entry(requestUrl = "$elsewhere?amount=1000&k1=a")
            store.putJournal(entry)
            assertIs<SecretGate.Released.NotSent>(transfer(entry, elsewhere))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a mint the wallet doesn't know yet is trusted on first use`() =
        runBlocking<Unit> {
            store.putJournal(entry())
            assertIs<SecretGate.Released.Sent>(release(entry()))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `nothing goes out that isn't journaled as it is sent`() =
        runBlocking<Unit> {
            pin(key)
            assertIs<SecretGate.Released.NotSent>(release(entry()), "not journaled")
            store.putJournal(entry())
            assertIs<SecretGate.Released.NotSent>(release(entry("$callback?k1=b&p1=cp1x")), "another request than the journaled one")
            store.putJournal(entry().copy(status = JournalStatus.Unknown))
            assertIs<SecretGate.Released.NotSent>(release(entry()), "sent already")
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `nothing goes out to a mint presenting another key than the pinned one`() =
        runBlocking<Unit> {
            pin(key)
            store.putJournal(entry())
            assertIs<SecretGate.Released.NotSent>(release(entry(), NoteInfo(callback, 1_000, otherKey, null)))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `nothing goes out to a callback that isn't on the mint's own host, port and scheme`() =
        runBlocking<Unit> {
            pin(key)
            for (elsewhere in listOf("http://other.example/cb", "http://${server.hostName}:${server.port + 1}/cb", "https://$host/cb")) {
                val e = entry("$elsewhere?k1=a&p1=cp1x")
                store.putJournal(e)
                assertIs<SecretGate.Released.NotSent>(release(e, NoteInfo(elsewhere, 1_000, key, null)), elsewhere)
            }
            store.putJournal(entry())
            assertIs<SecretGate.Released.NotSent>(
                release(entry(), NoteInfo("http://$host/other", 1_000, key, null)),
                "a request for another path than the callback",
            )
            assertEquals(0, server.requestCount)
        }
}
