package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletEvent
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus

/**
 * The [WalletEvent]s brands react to, read from what the wallet records anyway (the active account's history, its
 * accounts and its backup), so no money path has to report them. What is already there when collecting starts, or
 * when the user switches accounts, is no event.
 */
class WalletEvents(private val wallet: Wallet, private val clock: () -> Long = System::currentTimeMillis) {
    val events: Flow<WalletEvent> = flow {
        var previous: Snapshot? = null
        combine(wallet.activeAccount, wallet.activity, wallet.accounts, wallet.backupVerified) { active, activity, accounts, backedUp ->
            val id = active?.account?.id
            // An account's history loads after the switch to it: only rows recorded since then are new.
            val since = previous?.takeIf { it.accountId == id }?.since ?: clock()
            Snapshot(id, since, activity.filter { it.accountId == id }.associateBy { it.id }, accounts.size, backedUp)
        }.collect { now ->
            previous?.let { before -> changes(before, now).forEach { emit(it) } }
            previous = now
        }
    }

    internal data class Snapshot(val accountId: String?, val since: Long, val activity: Map<String, Activity>, val accounts: Int, val backedUp: Boolean)

    internal companion object {
        fun changes(before: Snapshot, now: Snapshot): List<WalletEvent> = buildList {
            if (now.accounts > before.accounts) add(WalletEvent.AccountCreated)
            // Another account's history is no news.
            if (now.accountId != before.accountId) return@buildList
            if (now.backedUp && !before.backedUp) add(WalletEvent.BackupDone)
            now.activity.values.forEach { row ->
                val old = before.activity[row.id]
                if (old != null || row.createdAtMillis >= now.since) event(old, row)?.let(::add)
            }
            // A strategy's tidying is often several rows at once: one event for them.
            if (count { it == WalletEvent.NotesTidied } > 1) {
                val first = indexOf(WalletEvent.NotesTidied)
                removeAll { it == WalletEvent.NotesTidied }
                add(first, WalletEvent.NotesTidied)
            }
        }

        /** What changing [old] (null: a new row) to [new] means to the user, if anything. */
        fun event(old: Activity?, new: Activity): WalletEvent? {
            if (old?.status == new.status) return null
            return when (new.kind) {
                ActivityKind.Received, ActivityKind.Claimed ->
                    WalletEvent.PaymentReceived(new.amountMsat).takeIf { new.status == ActivityStatus.Done }
                ActivityKind.Sent -> when (new.status) {
                    ActivityStatus.Done -> WalletEvent.PaymentSent(new.amountMsat)
                    ActivityStatus.Failed -> WalletEvent.Problem(ProblemKind.Other)
                    else -> null
                }
                // A note handed out has left the wallet's hands once it is shared, whether redeemed yet or not.
                ActivityKind.SentNote -> when {
                    old == null -> WalletEvent.PaymentSent(new.amountMsat)
                    new.status == ActivityStatus.Done -> WalletEvent.NoteRedeemed(new.amountMsat)
                    new.status == ActivityStatus.Cancelled -> WalletEvent.NoteTakenBack(new.amountMsat)
                    else -> null
                }
                // What the user does to their notes they see on screen; only what the wallet did by itself is news.
                ActivityKind.Combined, ActivityKind.Split, ActivityKind.Rotated ->
                    WalletEvent.NotesTidied.takeIf { new.automatic && new.status == ActivityStatus.Done }
                else -> null
            }
        }
    }
}
