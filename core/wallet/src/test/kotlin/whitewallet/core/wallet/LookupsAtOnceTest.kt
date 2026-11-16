package whitewallet.core.wallet

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import whitewallet.core.model.KeyCounter
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.spendDomain

/**
 * How many lookups a mint gets at once (decision 5 of the parallel refresh): one that fails a lookup among several is asked one at a time
 * for the rest of that run, on every chain and account, while another mint keeps its lookups at once; the next run tries them at once
 * again.
 */
class LookupsAtOnceTest {
    private val clock = 1_800_000_000_000L

    /**
     * Watches every lookup by `p`: how many were on their way to each mint at once, and fails [failing] the first two times it goes out.
     */
    private class Watch {
        /** The mint and key (`p`) to fail. */
        @Volatile var failing: Pair<String, String>? = null
        val failures = AtomicInteger()
        private val inFlight = ConcurrentHashMap<String, AtomicInteger>()
        /** By mint, the most lookups on their way at once since [failing] last failed. */
        val mostAfterFailure = ConcurrentHashMap<String, Int>()
        val most = ConcurrentHashMap<String, Int>()

        val interceptor = Interceptor { chain ->
            val url = chain.request().url
            val p = url.queryParameter("p") ?: return@Interceptor chain.proceed(chain.request())
            val host = "${url.host}:${url.port}"
            // Sent once the key had failed: lookups sent together with it don't count.
            val afterFailure = failures.get() >= 2
            // Slow enough that lookups sent together are on their way together.
            Thread.sleep(20)
            if (host to p == failing && failures.get() < 2) {
                failures.incrementAndGet()
                throw IOException("connection dropped")
            }
            val now = inFlight.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet()
            try {
                most.merge(host, now, ::maxOf)
                if (afterFailure) mostAfterFailure.merge(host, now, ::maxOf)
                Thread.sleep(20)
                chain.proceed(chain.request())
            } finally {
                inFlight.getValue(host).decrementAndGet()
            }
        }
    }

    @Test
    fun `a mint that fails a lookup among several gets one at a time for the rest of the scan, another keeps them at once`() =
        runBlocking<Unit> {
            LookupMint().use { first ->
                LookupMint().use { second ->
                    val watch = Watch()
                    val settings = WalletSettings(scanStopAfterUnusedKeys = 8, scanStopAfterUnusedShares = 2, scanLookupsAtOnce = 8)
                    val client =
                        LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(watch.interceptor).build())
                    val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client, settings = settings, now = { clock })
                    val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(first.host, "Main")).accountId
                    assertIs<ConnectResult.Connected>(wallet.connectMint(second.host))

                    // Whichever mint the scan looks through first fails key 2 of the change chain, among its first lookups at once.
                    val keys =
                        listOf(first.host, second.host).associateWith { Cp1.encode(wallet.keys(accountId, it).pubkey(KeySet.Change, 2)) }
                    val order = mutableListOf<String>()
                    wallet.scan { host, _ ->
                        if (host !in order) {
                            order += host
                            if (order.size == 1) watch.failing = host to keys.getValue(host)
                        }
                    }
                    val (failed, other) = order
                    assertEquals(2, watch.failures.get(), "the key failed twice: the client's own retry, then the lookup")
                    assertEquals(1, watch.mostAfterFailure[failed], "the rest of the scan asks $failed one at a time, on every chain")
                    assertTrue(watch.mostAfterFailure.getValue(other) > 1, "$other still gets its lookups at once")

                    // The next scan tries them at once again.
                    watch.most.clear()
                    wallet.scan()
                    assertTrue(watch.most.getValue(failed) > 1, "a new scan asks $failed several at once again")
                }
            }
        }

    @Test
    fun `a mint that fails a lookup among several gets one at a time for the rest of the refresh, another keeps them at once`() =
        runBlocking<Unit> {
            LookupMint().use { first ->
                LookupMint().use { second ->
                    val watch = Watch()
                    val settings = WalletSettings(scanStopAfterUnusedKeys = 8, scanLookupsAtOnce = 8)
                    val client =
                        LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(watch.interceptor).build())
                    val store = InMemoryWalletStore()
                    val wallet = Wallet(store, InMemorySeedStore(), client, settings = settings, now = { clock })
                    val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(first.host, "Main")).accountId
                    assertIs<ConnectResult.Connected>(wallet.connectMint(second.host))
                    // Two shares handed out on each mint (one domain, one counter): addresses the phone doesn't know the names of.
                    store.setKeyIndex(accountId, spendDomain(first.host), KeyCounter.Shares, 2)

                    watch.failing = first.host to Cp1.encode(wallet.keys(accountId, first.host).pubkey(KeySet.Share(0), 2))
                    wallet.refresh()
                    assertEquals(2, watch.failures.get(), "the key failed twice: the client's own retry, then the lookup")
                    assertEquals(1, watch.mostAfterFailure[first.host], "the rest of the refresh asks ${first.host} one at a time")
                    assertTrue(watch.most.getValue(second.host) > 1, "${second.host} still gets its lookups at once")

                    watch.most.clear()
                    wallet.refresh()
                    assertTrue(watch.most.getValue(first.host) > 1, "a new refresh asks ${first.host} several at once again")
                }
            }
        }
}
