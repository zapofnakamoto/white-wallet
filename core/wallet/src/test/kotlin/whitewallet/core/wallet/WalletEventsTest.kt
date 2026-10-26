package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletEvent
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.WalletEvents.Snapshot

class WalletEventsTest {
    private fun row(id: String, kind: ActivityKind, status: ActivityStatus = ActivityStatus.Done, at: Long = 100, account: String = "a") =
        Activity(id, account, kind, 1_000, null, "mint", null, null, null, false, status, null, at)

    private fun snapshot(vararg rows: Activity, account: String? = "a", since: Long = 50, accounts: Int = 1, backedUp: Boolean = false) =
        Snapshot(account, since, rows.associateBy { it.id }, accounts, backedUp)

    @Test
    fun `a payment that arrives is an event, one already there is not`() {
        val before = snapshot(row("old", ActivityKind.Received))
        val now = snapshot(row("old", ActivityKind.Received), row("new", ActivityKind.Received))
        assertEquals(listOf<WalletEvent>(WalletEvent.PaymentReceived(1_000)), WalletEvents.changes(before, now))
    }

    @Test
    fun `a payment that settles is sent, one that fails is a problem`() {
        val before = snapshot(row("p1", ActivityKind.Sent, ActivityStatus.Pending), row("p2", ActivityKind.Sent, ActivityStatus.Pending))
        val now = snapshot(row("p1", ActivityKind.Sent), row("p2", ActivityKind.Sent, ActivityStatus.Failed))
        assertEquals(listOf(WalletEvent.PaymentSent(1_000), WalletEvent.Problem(ProblemKind.Other)), WalletEvents.changes(before, now))
    }

    @Test
    fun `a pending claim is no event until it is done`() {
        val pending = snapshot(row("c", ActivityKind.Claimed, ActivityStatus.Pending))
        assertEquals(emptyList(), WalletEvents.changes(snapshot(), pending))
        assertEquals(listOf<WalletEvent>(WalletEvent.PaymentReceived(1_000)), WalletEvents.changes(pending, snapshot(row("c", ActivityKind.Claimed))))
    }

    @Test
    fun `a shared note is sent once, when it is handed out`() {
        val shared = snapshot(row("n", ActivityKind.SentNote, ActivityStatus.Pending))
        assertEquals(listOf<WalletEvent>(WalletEvent.PaymentSent(1_000)), WalletEvents.changes(snapshot(), shared))
        assertEquals(emptyList(), WalletEvents.changes(shared, snapshot(row("n", ActivityKind.SentNote))))
    }

    @Test
    fun `maintenance is no event`() {
        assertEquals(emptyList(), WalletEvents.changes(snapshot(), snapshot(row("m", ActivityKind.Combined))))
    }

    @Test
    fun `history that loads after switching accounts is no event`() {
        val switched = snapshot(account = "b", since = 500)
        assertEquals(emptyList(), WalletEvents.changes(snapshot(row("x", ActivityKind.Received)), switched))
        val loaded = snapshot(row("y", ActivityKind.Received, at = 100, account = "b"), account = "b", since = 500)
        assertEquals(emptyList(), WalletEvents.changes(switched, loaded))
    }

    @Test
    fun `a new account and a verified backup are events`() {
        assertEquals(listOf<WalletEvent>(WalletEvent.AccountCreated), WalletEvents.changes(snapshot(), snapshot(accounts = 2)))
        assertEquals(listOf<WalletEvent>(WalletEvent.BackupDone), WalletEvents.changes(snapshot(), snapshot(backedUp = true)))
    }
}
