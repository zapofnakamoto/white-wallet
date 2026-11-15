package whitewallet.core.wallet

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import whitewallet.core.model.KeyCounter
import whitewallet.core.wallet.LookupMint.Companion.error
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.spendDomain

/**
 * A refresh looks for payments to the account's addresses on every share it handed out, with the walker restore uses: several lookups at
 * once, answers in key order, a quiet stop where the mint refuses or doesn't answer, and a mint whose signing key changed named in the
 * result.
 */
class RefreshSharesTest {
    private val clock = 1_800_000_000_000L

    /** Counts the lookups on their way to the mint at once. */
    private class InFlight {
        private val now = AtomicInteger()
        val most = AtomicInteger()
        val lookedUp: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val interceptor = Interceptor { chain ->
            val p = chain.request().url.queryParameter("p") ?: return@Interceptor chain.proceed(chain.request())
            lookedUp += p
            most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
            try {
                // Slow enough that lookups sent together are on their way together.
                Thread.sleep(20)
                chain.proceed(chain.request())
            } finally {
                now.decrementAndGet()
            }
        }
    }

    private class Phone(val wallet: Wallet, val store: InMemoryWalletStore, val accountId: String, val host: String) {
        suspend fun key(set: KeySet, index: Long): String = wallet.keys(accountId, host).pubkey(set, index).toHex()
    }

    /** An account on [mint] that handed out [shares] shares there, as for addresses the phone doesn't know the names of. */
    private suspend fun phone(mint: LookupMint, shares: Long = 1, intercept: Interceptor? = null): Phone {
        val store = InMemoryWalletStore()
        val client =
            if (intercept == null) LnurlcashClient()
            else LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(intercept).build())
        val wallet = Wallet(store, InMemorySeedStore(), client, now = { clock })
        val accountId = assertIs<CreateAccountResult.Created>(wallet.createAccount(mint.host, "Main")).accountId
        store.setKeyIndex(accountId, spendDomain(mint.host), KeyCounter.Shares, shares)
        return Phone(wallet, store, accountId, mint.host)
    }

    @Test
    fun `a refresh asks for a share's keys several at once`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val watch = InFlight()
                val phone = phone(mint, intercept = watch.interceptor)
                phone.wallet.refresh()
                assertTrue(watch.most.get() > 1, "the share's keys went out several at once, not ${watch.most.get()}")
            }
        }

    @Test
    fun `a payment found on a share is received, and the scan of that share goes on from past it`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                mint.lookups[phone.key(KeySet.Share(0), 3)] = note(4_000)
                assertEquals(RefreshResult(1, 4_000), phone.wallet.refresh())
                assertEquals(4, phone.store.indexOf(phone.accountId, mint.host, KeySet.Share(0)))
                assertEquals(RefreshResult(0, 0), phone.wallet.refresh(), "found once")
            }
        }

    @Test
    fun `a refusal ends the share's scan quietly, keeping what it found before it`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                mint.lookups[phone.key(KeySet.Share(0), 1)] = note(3_000)
                mint.lookups[phone.key(KeySet.Share(0), 3)] = error("not today")
                mint.lookups[phone.key(KeySet.Share(0), 5)] = note(2_000)
                assertEquals(RefreshResult(1, 3_000), phone.wallet.refresh())
                assertNull(phone.store.note(phone.key(KeySet.Share(0), 5)), "nothing past the refused key")
                assertEquals(
                    2,
                    phone.store.indexOf(phone.accountId, mint.host, KeySet.Share(0)),
                    "the next refresh goes on past what it found",
                )
            }
        }

    @Test
    fun `a mint whose signing key changed is named, and nothing it says comes in`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val phone = phone(mint)
                mint.lookups[phone.key(KeySet.Share(0), 0)] = note(3_000)
                val pinned = assertNotNull(phone.store.mint(mint.host))
                phone.store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
                assertEquals(RefreshResult(0, 0, setOf(mint.host)), phone.wallet.refresh())
                assertNull(phone.store.note(phone.key(KeySet.Share(0), 0)))
            }
        }
}
