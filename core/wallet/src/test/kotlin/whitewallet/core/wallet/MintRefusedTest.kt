package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import whitewallet.lnurlcash.client.LnurlcashClient

/** A mint that answers ERROR where it should answer with a request: a refusal in its own words, never "not a mint". */
class MintRefusedTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val host = "refusing.example"

    private fun wallet(body: String): Pair<Wallet, InMemoryWalletStore> {
        val client =
            LnurlcashClient(
                http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(Interceptor { Regtest.answer(it, body) }).build()
            )
        val store = InMemoryWalletStore()
        return Wallet(store, InMemorySeedStore(mapOf("a" to words)), client) to store
    }

    @Test
    fun `its words come with the preview`() =
        runBlocking<Unit> {
            val (wallet, _) = wallet("""{"status":"ERROR","reason":"Closed for maintenance"}""")
            assertEquals(MintPreview.Invalid(host, MintRefusal.Refused, "Closed for maintenance"), wallet.previewMint(host))
        }

    @Test
    fun `its words come with adding it`() =
        runBlocking<Unit> {
            val (wallet, store) = wallet("""{"status":"ERROR","reason":"Closed for maintenance"}""")
            store.addAccount("a", "Main", null, host)
            val failed = assertIs<ConnectResult.Failed>(wallet.connectMint(host))
            assertEquals(OnboardResult.MintInvalid(MintRefusal.Refused, "Closed for maintenance"), failed.result)
        }

    @Test
    fun `a refusal without words is still a refusal`() =
        runBlocking<Unit> {
            val (wallet, _) = wallet("""{"status":"ERROR"}""")
            assertEquals(MintPreview.Invalid(host, MintRefusal.Refused, null), wallet.previewMint(host))
        }

    @Test
    fun `an answer that isn't a mint's is not a mint`() =
        runBlocking<Unit> {
            val (wallet, _) = wallet("""{"tag":"channelRequest","k1":"00","uri":"x@y:1","callback":"https://x.example/c"}""")
            assertEquals(MintPreview.Invalid(host, MintRefusal.NotAMint), wallet.previewMint(host))
        }
}
