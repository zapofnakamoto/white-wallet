package whitewallet.core.wallet

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.core.wallet.Regtest.HOST3

/**
 * A refresh for one wallet of three accounts, each with an address on a mint of its own, on the real mints: payments to all three arrive,
 * and how long a refresh takes is printed. The three mints of the stack share the domain `localhost`, so they are one lane: this times the
 * lookups at once, not the lanes beside each other.
 *
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestParallelRefreshTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `a refresh finds the payments to three accounts on three mints`() =
        runBlocking<Unit> {
            val phone = Regtest.phone()
            val main = assertNotNull(phone.store.currentAccountId())
            // An account is added only after a used one.
            phone.fund(1_000)
            val addresses = mutableMapOf(main to phone.address)
            for ((host, name) in listOf(HOST2 to "Second", HOST3 to "Third")) {
                val username = Regtest.name()
                Regtest.registered(phone.wallet, phone.wallet.onboard(username, host, newAccountName = name, inActiveWallet = true))
                val id = assertNotNull(phone.store.currentAccountId())
                addresses[id] = "$username@$host"
                Regtest.pay(addresses.getValue(id), 1_000)
                phone.refreshUntil { balances(phone).getValue(id) > 0 }
            }
            phone.wallet.switchAccount(main)
            val before = balances(phone)
            assertTrue(before.values.all { it > 0 }, "every account is used: $before")

            // A mint learns of a payment on its own schedule: refreshes until all three arrived, in any of them.
            addresses.values.forEach { Regtest.pay(it, 2_000) }
            phone.refreshUntil { balances(phone).all { (id, msat) -> msat > before.getValue(id) } }
            val after = balances(phone)
            for (id in addresses.keys) assertTrue(
                after.getValue(id) > before.getValue(id),
                "the payment to ${addresses[id]} arrived: $after",
            )
            assertEquals(addresses.keys, after.keys)

            // What every refresh costs: each account's shares looked through on the three mints, up to their gaps.
            val millis = (1..3).map { measureTimeMillis { phone.wallet.refresh() } }
            println("A refresh of 3 accounts on 3 mints ($HOST, $HOST2, $HOST3): $millis ms")
        }

    private suspend fun balances(phone: Regtest.Phone): Map<String, Long> =
        phone.wallet.accounts.first().associate { it.account.id to it.balanceMsat }
}
