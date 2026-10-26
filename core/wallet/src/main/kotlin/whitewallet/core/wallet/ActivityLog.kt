package whitewallet.core.wallet

import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note

/**
 * Writes an account's history ([Activity]) at the moment something happens, so History never has to infer it from
 * notes. Ids are stable (see [Activity.id]): writing the same event again replaces its row.
 */
internal class ActivityLog(private val wallet: Wallet) {
    suspend fun record(
        id: String,
        accountId: String,
        kind: ActivityKind,
        amountMsat: Long,
        host: String,
        status: ActivityStatus = ActivityStatus.Done,
        feeMsat: Long? = null,
        counterparty: String? = null,
        certified: Boolean? = null,
        noteCount: Int? = null,
        automatic: Boolean = false,
        journalId: String? = null,
    ) = wallet.store.putActivity(
        Activity(id, accountId, kind, amountMsat, feeMsat, host, counterparty, certified, noteCount, automatic, status, journalId, wallet.now()),
    )

    /** A move between two accounts: a row on each side. */
    suspend fun move(fromId: String, toId: String, host: String, amountMsat: Long, feeMsat: Long?, status: ActivityStatus, journalId: String) {
        val fromName = wallet.store.account(fromId)?.name
        val toName = wallet.store.account(toId)?.name
        record("move:$journalId:out", fromId, ActivityKind.MovedOut, amountMsat, host, status, feeMsat, toName, journalId = journalId)
        record("move:$journalId:in", toId, ActivityKind.MovedIn, amountMsat, host, status, counterparty = fromName, journalId = journalId)
    }

    /** Settles rows waiting for a journal entry once reconciliation knows its outcome. */
    suspend fun settle(journalId: String, status: JournalStatus) {
        when (status) {
            JournalStatus.Confirmed -> wallet.store.settleActivities(journalId, ActivityStatus.Done)
            JournalStatus.Rejected -> wallet.store.settleActivities(journalId, ActivityStatus.Failed)
            else -> Unit
        }
    }

    companion object {
        /**
         * The net fee implied by notes worth [inputsMsat] becoming [outputs] (see [Activity.feeMsat]), when every
         * output's amount is known: what the mint kept, or, negative, what it paid back. A refund counts only when the
         * outputs' certificates prove their amounts; without them an amount may just be assumed from the inputs.
         */
        fun fee(inputsMsat: Long, outputs: List<Note>): Long? {
            if (outputs.isEmpty() || outputs.any { it.amountMsat <= 0 }) return null
            return (inputsMsat - outputs.sumOf { it.amountMsat }).takeIf { it > 0 || it < 0 && outputs.all { o -> o.certificate != null } }
        }
    }
}
