package whitewallet.core.wallet

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.spendDomain

/**
 * A refresh asks its mints at once, one lane per mint domain: a lane goes through the accounts that use that mint, in turn, and what goes
 * wrong in one lane stays there. Two fake mints, one reached as `localhost` and one as `127.0.0.1`, stand for two domains.
 */
class ParallelRefreshTest {
    private val clock = 1_800_000_000_000L

    /** Every request, by host; [onLookup] runs as a lookup by `p` goes out, with its host and `p`. */
    private class Requests {
        val byHost = ConcurrentHashMap<String, MutableList<String>>()
        @Volatile var onLookup: (host: String, p: String) -> Unit = { _, _ -> }

        val interceptor = Interceptor { chain ->
            val url = chain.request().url
            val host = "${url.host}:${url.port}"
            byHost.computeIfAbsent(host) { java.util.Collections.synchronizedList(mutableListOf()) } += url.encodedPath
            url.queryParameter("p")?.let { onLookup(host, it) }
            chain.proceed(chain.request())
        }

        fun lookups(host: String): Int = byHost[host].orEmpty().count { it == "/w" }
    }

    private class Phone(val wallet: Wallet, val store: InMemoryWalletStore, val accountId: String) {
        /** Hands out [count] shares of [accountId] on [host]'s domain, as for addresses the phone doesn't know the names of. */
        suspend fun shares(host: String, count: Long = 1, accountId: String = this.accountId) =
            store.setKeyIndex(accountId, spendDomain(host), KeyCounter.Shares, count)

        suspend fun key(host: String, set: KeySet, index: Long, accountId: String = this.accountId): String =
            wallet.keys(accountId, host).pubkey(set, index).toHex()
    }

    private suspend fun phone(home: LookupMint, requests: Requests, vararg others: LookupMint): Phone {
        val store = InMemoryWalletStore()
        val client = LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(requests.interceptor).build())
        val wallet = Wallet(store, InMemorySeedStore(), client, now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(home.host, "Main")).accountId
        for (other in others) assertIs<ConnectResult.Connected>(wallet.connectMint(other.host))
        return Phone(wallet, store, accountId)
    }

    @Test
    fun `a lookup to one mint goes out while another mint's is still on its way`() =
        runBlocking<Unit> {
            LookupMint().use { slow ->
                LookupMint("127.0.0.1").use { other ->
                    val requests = Requests()
                    val phone = phone(slow, requests, other)
                    phone.shares(slow.host)
                    phone.shares(other.host)
                    val otherAsked = CountDownLatch(1)
                    var waitedFor = true
                    requests.onLookup = { host, _ ->
                        if (host == slow.host && otherAsked.count > 0) waitedFor = otherAsked.await(5, TimeUnit.SECONDS) && waitedFor
                        if (host == other.host) otherAsked.countDown()
                    }
                    phone.wallet.refresh()
                    assertTrue(waitedFor, "${other.host} was asked while ${slow.host}'s lookups were held")
                }
            }
        }

    @Test
    fun `within a mint, the accounts are looked through in turn`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val requests = Requests()
                val phone = phone(mint, requests)
                // An account is added only after a used one.
                val q = phone.key(mint.host, KeySet.Wallet, 0)
                phone.store.upsertNotes(
                    listOf(Note(q, phone.accountId, mint.host, NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, clock))
                )
                mint.lookups[q] = note(1_000)
                val second = assertIs<CreateAccountResult.Created>(phone.wallet.createAccount("Second")).accountId
                phone.shares(mint.host)
                phone.shares(mint.host, accountId = second)
                val owners =
                    listOf(phone.accountId, second)
                        .flatMap { id ->
                            (0L until 20).map { Cp1.encode(phone.wallet.keys(id, mint.host).pubkey(KeySet.Share(0), it)) to id }
                        }
                        .toMap()
                val order = java.util.Collections.synchronizedList(mutableListOf<String>())
                requests.onLookup = { _, p -> owners[p]?.let { order += it } }
                phone.wallet.refresh()
                assertEquals(setOf(phone.accountId, second), order.toSet(), "both accounts were looked through")
                assertEquals(1, order.zipWithNext().count { (a, b) -> a != b }, "one account's lookups, then the other's")
            }
        }

    @Test
    fun `a mint no account uses now hears nothing from a refresh`() =
        runBlocking<Unit> {
            LookupMint().use { used ->
                LookupMint("127.0.0.1").use { kept ->
                    val requests = Requests()
                    val phone = phone(used, requests, kept)
                    phone.shares(used.host)
                    requests.byHost.clear()
                    phone.wallet.refresh()
                    assertTrue(requests.lookups(used.host) > 0)
                    assertEquals(null, requests.byHost[kept.host], "a mint only added to the wallet gets no request")
                }
            }
        }

    @Test
    fun `a mint that doesn't answer ends its own lane, and the others' findings arrive`() =
        runBlocking<Unit> {
            LookupMint().use { up ->
                LookupMint("127.0.0.1").use { down ->
                    val requests = Requests()
                    val phone = phone(up, requests, down)
                    phone.shares(up.host)
                    phone.shares(down.host)
                    up.lookups[phone.key(up.host, KeySet.Share(0), 2)] = note(4_000)
                    down.close()
                    assertEquals(RefreshResult(1, 4_000), phone.wallet.refresh())
                    assertEquals(4_000, phone.wallet.balanceMsat.first())
                }
            }
        }

    @Test
    fun `a mint whose signing key changed gets no lookups, and is named`() =
        runBlocking<Unit> {
            LookupMint().use { changed ->
                LookupMint("127.0.0.1").use { other ->
                    val requests = Requests()
                    val phone = phone(changed, requests, other)
                    phone.shares(changed.host)
                    phone.shares(other.host)
                    val pinned = assertNotNull(phone.store.mint(changed.host))
                    phone.store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
                    requests.byHost.clear()

                    assertEquals(setOf(changed.host), phone.wallet.refresh().keyChanged)
                    assertEquals(0, requests.lookups(changed.host), "none of the account's keys went there")
                    assertTrue(requests.lookups(other.host) > 0, "the other mint's lane went on")
                    assertEquals(setOf(changed.host), phone.wallet.mintsWithChangedKey.first())
                    assertFalse(changed.host in phone.wallet.mintsNotAnswering.first())
                }
            }
        }

    @Test
    fun `a mint whose signing key changed is asked for it on each refresh, and used again once it matches`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val requests = Requests()
                val phone = phone(mint, requests)
                phone.shares(mint.host)
                val pinned = assertNotNull(phone.store.mint(mint.host))
                phone.store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
                assertEquals(setOf(mint.host), phone.wallet.refresh().keyChanged)

                // The key it presents matches again, as after a proxy in front of it that dropped it.
                phone.store.saveMint(pinned)
                requests.byHost.clear()
                assertEquals(RefreshResult(0, 0), phone.wallet.refresh())
                assertEquals(emptySet(), phone.wallet.mintsWithChangedKey.first())
                assertTrue(requests.lookups(mint.host) > 0, "its lookups go out again")
            }
        }
}
