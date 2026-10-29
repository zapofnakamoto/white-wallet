package whitewallet.core.wallet

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.WalletTask

/** What the wallet says it works on ([Wallet.task]) against the real reference mint (dev/regtest, LNURLCASH_REGTEST=1). */
class RegtestWalletTaskTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    /** Every task [wallet] reports while [block] runs, in order, repeats dropped. */
    private suspend fun tasks(wallet: Wallet, block: suspend () -> Unit): List<WalletTask?> {
        val seen = Collections.synchronizedList(mutableListOf<WalletTask?>())
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { wallet.task.collect { if (seen.lastOrNull() != it || seen.isEmpty()) seen += it } }
        try {
            block()
        } finally {
            watcher.cancel()
        }
        return seen.toList()
    }

    @Test
    fun `a refresh says it updates, and which maintenance step runs`() = runBlocking<Unit> {
        val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
        val wallet = alice.wallet
        Regtest.pay(alice.address, 1000)
        Regtest.pay(alice.address, 1000)

        val combining = WalletTask.Maintaining(MaintenanceStep.Combining, SingleNoteStrategy.ID)
        val seen = tasks(wallet) { wallet.refresh() }
        assertEquals(listOf(null, WalletTask.Updating, combining, WalletTask.Updating, null), seen)

        // Nothing to maintain: only updating.
        assertEquals(listOf(null, WalletTask.Updating, null), tasks(wallet) { wallet.refresh() })

        // Checking every note counts them off, after the refresh before it, with no pause in between.
        val checking = tasks(wallet) { wallet.recheckNotes() }
        assertEquals(listOf(null, WalletTask.Updating, WalletTask.CheckingNotes(0, 1), WalletTask.Updating, null), checking)
    }

    @Test
    fun `auto-renew is maintenance of no strategy`() = runBlocking<Unit> {
        val bob = Regtest.phone(strategy = ManualStrategy.ID)
        val wallet = bob.wallet
        wallet.setAutoRotate(true)
        Regtest.pay(bob.address, 1500)

        val seen = tasks(wallet) { wallet.refresh() }
        assertTrue(WalletTask.Maintaining(MaintenanceStep.Renewing) in seen, "renewing: $seen")
        assertNull(wallet.task.value)
    }
}
