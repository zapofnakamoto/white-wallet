package whitewallet.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import whitewallet.core.model.Account
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.Profile
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe

@Entity(tableName = "accounts")
internal data class AccountEntity(@PrimaryKey val id: String, val name: String, val createdAtMillis: Long, val host: String) {
    fun toModel() = Account(id, name, createdAtMillis, host)
}

/** Single row holding app-wide state. */
@Entity(tableName = "app_state")
internal data class AppStateEntity(@PrimaryKey val id: Int = 0, val activeAccountId: String?)

/** One row of the account switcher: an account with its address and outstanding balance. */
internal data class AccountSummaryRow(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    /** The account's home mint. */
    val host: String,
    val username: String?,
    val addressHost: String?,
    val balanceMsat: Long,
)

@Entity(tableName = "notes", indices = [Index("accountId")])
internal data class NoteEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val host: String,
    val kind: String,
    val purpose: Int?,
    val noteIndex: Long?,
    val amountMsat: Long,
    val certificate: String?,
    val state: String,
    val updatedAtMillis: Long,
) {
    fun toModel() = Note(id, accountId, host, NoteKind.valueOf(kind), purpose, noteIndex, amountMsat, certificate, NoteState.valueOf(state), updatedAtMillis)

    companion object {
        fun from(n: Note) = NoteEntity(n.id, n.accountId, n.host, n.kind.name, n.purpose, n.index, n.amountMsat, n.certificate, n.state.name, n.updatedAtMillis)
    }
}

/** Shared by all accounts: what a host is and the key pinned for it. */
@Entity(tableName = "mints")
internal data class MintEntity(
    @PrimaryKey val host: String,
    val withdrawUrl: String,
    val mintPubkey: String?,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val feeBaseMsat: Long?,
    val feePpm: Long?,
) {
    fun toModel() = Mint(host, withdrawUrl, mintPubkey, minSendableMsat, maxSendableMsat, feeBaseMsat, feePpm)

    companion object {
        fun from(s: Mint) = MintEntity(s.host, s.withdrawUrl, s.mintPubkey, s.minSendableMsat, s.maxSendableMsat, s.feeBaseMsat, s.feePpm)
    }
}

@Entity(tableName = "profile")
internal data class ProfileEntity(@PrimaryKey val accountId: String, val username: String, val host: String) {
    fun toModel() = Profile(username, host)
}

/** Every Lightning address an account registered; the `profile` row names the primary one. */
@Entity(tableName = "addresses", primaryKeys = ["accountId", "host", "username"])
internal data class AddressEntity(val accountId: String, val host: String, val username: String, val addedAtMillis: Long) {
    fun toModel() = RegisteredAddress(username, host, addedAtMillis)
}

@Entity(tableName = "counters", primaryKeys = ["accountId", "host", "purpose"])
internal data class CounterEntity(val accountId: String, val host: String, val purpose: Int, val next: Long)

/** First purpose-2 index the next Lightning address scan starts from, per account and host. */
@Entity(tableName = "address_scan", primaryKeys = ["accountId", "host"])
internal data class AddressScanEntity(val accountId: String, val host: String, val next: Long)

/**
 * The last answer the mint gave for one derived key (public lookup by `p`, never by spend). Failed lookups are
 * not saved. [result] is the [KeyProbe] subtype; [amountMsat], [certified] and [imported] belong to `Outstanding`,
 * [reason] to `Refused`.
 */
@Entity(tableName = "key_checks", primaryKeys = ["accountId", "host", "purpose", "keyIndex"])
internal data class KeyCheckEntity(
    val accountId: String,
    val host: String,
    val purpose: Int,
    val keyIndex: Long,
    val result: String,
    val amountMsat: Long?,
    val certified: Boolean,
    val imported: Boolean,
    val reason: String?,
    val checkedAtMillis: Long,
) {
    fun id() = KeyId(accountId, host, purpose, keyIndex)

    fun toModel(): KeyProbe? = when (result) {
        UNUSED -> KeyProbe.Unused(checkedAtMillis)
        OUTSTANDING -> KeyProbe.Outstanding(amountMsat ?: 0, certified, imported, checkedAtMillis)
        SPENT -> KeyProbe.Spent(checkedAtMillis)
        REFUSED -> KeyProbe.Refused(reason, checkedAtMillis)
        else -> null
    }

    companion object {
        private const val UNUSED = "Unused"
        private const val OUTSTANDING = "Outstanding"
        private const val SPENT = "Spent"
        private const val REFUSED = "Refused"

        /** Null for a failed lookup, which is not saved. */
        fun from(id: KeyId, p: KeyProbe): KeyCheckEntity? {
            val (result, outstanding) = when (p) {
                is KeyProbe.Unused -> UNUSED to null
                is KeyProbe.Outstanding -> OUTSTANDING to p
                is KeyProbe.Spent -> SPENT to null
                is KeyProbe.Refused -> REFUSED to null
                is KeyProbe.Failed -> return null
            }
            return KeyCheckEntity(
                id.accountId, id.host, id.purpose, id.index, result, outstanding?.amountMsat, outstanding?.certified ?: false,
                outstanding?.imported ?: false, (p as? KeyProbe.Refused)?.reason, p.checkedAtMillis,
            )
        }
    }
}

@Entity(tableName = "journal", indices = [Index("accountId")])
internal data class JournalEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: String,
    val host: String,
    val withdrawUrl: String,
    /** Comma-separated; ids, spends and outputs never contain commas. */
    val inputIds: String,
    val inputK1s: String,
    /** `accountId:purpose:index:q`, comma-separated. */
    val outputs: String,
    val requestUrl: String,
    val status: String,
    val detail: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toModel() = JournalEntry(
        id, accountId, OperationType.valueOf(type), host, withdrawUrl, inputIds.split(), inputK1s.split(),
        outputs.split().map { it.split(':').let { (a, p, i, q) -> OutputRef(a, p.toInt(), i.toLong(), q) } },
        requestUrl, JournalStatus.valueOf(status), detail, createdAtMillis, updatedAtMillis,
    )

    companion object {
        fun from(e: JournalEntry) = JournalEntity(
            e.id, e.accountId, e.type.name, e.host, e.withdrawUrl, e.inputIds.joinToString(","), e.inputK1s.joinToString(","),
            e.outputs.joinToString(",") { "${it.accountId}:${it.purpose}:${it.index}:${it.q}" }, e.requestUrl, e.status.name, e.detail,
            e.createdAtMillis, e.updatedAtMillis,
        )

        private fun String.split(): List<String> = if (isEmpty()) emptyList() else split(',')
    }
}

/** One row of an account's history (see [Activity]). */
@Entity(tableName = "activity", indices = [Index("accountId", "createdAtMillis"), Index("journalId")])
internal data class ActivityEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val kind: String,
    val amountMsat: Long,
    val feeMsat: Long?,
    val host: String,
    val counterparty: String?,
    val certified: Boolean?,
    val noteCount: Int?,
    val automatic: Boolean,
    val status: String,
    val journalId: String?,
    val createdAtMillis: Long,
) {
    fun toModel() = Activity(id, accountId, ActivityKind.valueOf(kind), amountMsat, feeMsat, host, counterparty, certified, noteCount, automatic,
        ActivityStatus.valueOf(status), journalId, createdAtMillis)

    companion object {
        fun from(a: Activity) = ActivityEntity(a.id, a.accountId, a.kind.name, a.amountMsat, a.feeMsat, a.host, a.counterparty, a.certified,
            a.noteCount, a.automatic, a.status.name, a.journalId, a.createdAtMillis)
    }
}
