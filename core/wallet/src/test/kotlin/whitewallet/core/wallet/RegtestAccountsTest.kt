package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.wallet.Regtest.HOST

/** Moving money between two accounts of one wallet, on the real mint. */
class RegtestAccountsTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** Main (active, funded with [sats]) and an empty Savings account, both with an address on the mint. */
    private suspend fun accounts(vararg sats: Long): Triple<Regtest.Phone, String, String> {
        val phone = Regtest.phone()
        val main = assertNotNull(phone.store.currentAccountId())
        assertIs<OnboardResult.Ready>(phone.wallet.onboard(Regtest.name(), HOST, newAccountName = "Savings"))
        val savings = assertNotNull(phone.store.currentAccountId())
        phone.wallet.switchAccount(main)
        phone.fund(*sats)
        return Triple(phone, main, savings)
    }

    private suspend fun Regtest.Phone.balances() = wallet.accounts.first().associate { it.account.id to it.balanceMsat }

    @Test
    fun `moving everything rotates a single note into the other account, at no fee`() = runBlocking<Unit> {
        val (phone, main, savings) = accounts(2000)
        assertEquals(MoveResult.Moved(1_999_000), phone.wallet.moveFunds(savings, null))
        assertEquals(mapOf(main to 0L, savings to 1_999_000L), phone.balances())
        val out = phone.wallet.activity.first().single { it.kind == ActivityKind.MovedOut }
        assertEquals("Savings", out.counterparty)
    }

    @Test
    fun `moving everything merges several notes into one note of the other account`() = runBlocking<Unit> {
        val (phone, main, savings) = accounts(1000, 2000)
        val moved = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(savings, null))
        assertTrue(moved.amountMsat >= 2_998_000, "a merge never loses value")
        assertEquals(mapOf(main to 0L, savings to moved.amountMsat), phone.balances())
        phone.wallet.switchAccount(savings)
        assertEquals(1, phone.store.notes(savings).first().size)
    }

    @Test
    fun `moving more than any single note merges first, then splits`() = runBlocking<Unit> {
        val (phone, main, savings) = accounts(1000, 2000)
        assertEquals(MoveResult.Moved(2_500_000), phone.wallet.moveFunds(savings, 2_500_000))
        val balances = phone.balances()
        assertEquals(2_500_000, balances[savings])
        assertTrue(balances[main]!! >= 2_998_000 - 2_500_000 - 1_000, "the change, less at most the split fee: $balances")
    }

    @Test
    fun `moving more than the balance is refused without touching anything`() = runBlocking<Unit> {
        val (phone, main, savings) = accounts(2000)
        val before = phone.notes().map { it.id }
        assertEquals(MoveFailure.InsufficientFunds, assertIs<MoveResult.Failed>(phone.wallet.moveFunds(savings, 5_000_000)).reason)
        assertEquals(before, phone.notes().map { it.id })
        assertEquals(mapOf(main to 1_999_000L, savings to 0L), phone.balances())
    }
}
