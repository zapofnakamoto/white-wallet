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
    private fun row(id: String, kind: ActivityKind, status: ActivityStatus = ActivityStatus.Done, at: Long = 100, account: String = "a", automatic: Boolean = false) =
        Activity(id, account, kind, 1_000, null, "mint", null, null, null, automatic, status, null, at)

    private fun snapshot(vararg rows: Activity, account: String? = "a", since: Long = 50, accounts: Int = 1, backedUp: Boolean = false, run: PolicyRun? = null) =
        Snapshot(account, since, rows.associateBy { it.id }, accounts, backedUp, run)

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
        // Redeemed later, it is no second payment.
        assertEquals(listOf<WalletEvent>(WalletEvent.NoteRedeemed(1_000)), WalletEvents.changes(shared, snapshot(row("n", ActivityKind.SentNote))))
    }

    @Test
    fun `a handed-out note is redeemed or taken back later`() {
        val shared = snapshot(row("r", ActivityKind.SentNote, ActivityStatus.Pending), row("t", ActivityKind.SentNote, ActivityStatus.Pending))
        val now = snapshot(row("r", ActivityKind.SentNote), row("t", ActivityKind.SentNote, ActivityStatus.Cancelled))
        assertEquals(listOf(WalletEvent.NoteRedeemed(1_000), WalletEvent.NoteTakenBack(1_000)), WalletEvents.changes(shared, now))
    }

    @Test
    fun `note maintenance is one event per run of the note policies, with what it did`() {
        // Maintenance rows, by the user or by the wallet, are no events of their own.
        val rows = snapshot(row("c", ActivityKind.Combined, automatic = true), row("m", ActivityKind.Combined), row("p", ActivityKind.Received))
        assertEquals(listOf<WalletEvent>(WalletEvent.PaymentReceived(1_000)), WalletEvents.changes(snapshot(), rows))

        val run = PolicyRun(rotated = 1, merged = 11, failed = 0, stoppedEarly = null, atMillis = 200, split = 0,
            collected = listOf(Collected("other", 5_000, 100), Collected("third", 7_000, 0, pending = true)),
            notesReplaced = 12, notesMade = 1, notesMadeMsat = 40_000, balanceChangeMsat = 1_000)
        val ran = snapshot(run = run)
        assertEquals(listOf<WalletEvent>(WalletEvent.NoteMaintenance(combined = 11, split = 0, renewed = 1, movedHomeMsat = 5_000, notesReplaced = 12,
            notesMade = 1, balanceChangeMsat = 1_000)), WalletEvents.changes(snapshot(), ran))
        assertEquals(emptyList(), WalletEvents.changes(ran, ran), "the same run again")
        val tried = PolicyRun(rotated = 0, merged = 0, failed = 1, stoppedEarly = "no answer", atMillis = 300)
        assertEquals(emptyList(), WalletEvents.changes(ran, snapshot(run = tried)), "a run that changed nothing")
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
