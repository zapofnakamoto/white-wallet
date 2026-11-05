package whitewallet.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import whitewallet.core.model.Account
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.Profile
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.lnurlcash.RevealsNoteSecret

@Entity(tableName = "accounts")
internal data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMillis: Long,
    val host: String,
    /** The wallet (recovery phrase) the account's keys derive from; an account from before wallets is its own, with the same id. */
    @ColumnInfo(defaultValue = "''") val walletId: String,
    /** Its LUD-26 account index `a` in that wallet. */
    @ColumnInfo(defaultValue = "0") val accountIndex: Long,
) {
    fun toModel() = Account(id, name, createdAtMillis, host, walletId, accountIndex)

    companion object {
        fun from(a: Account) = AccountEntity(a.id, a.name, a.createdAtMillis, a.host, a.walletId, a.index)
    }
}

/** A recovery phrase and the accounts below it (`accounts.walletId`); the phrase itself is in the seed store, under this id. */
@Entity(tableName = "wallets") internal data class WalletEntity(@PrimaryKey val id: String, val name: String, val createdAtMillis: Long)

/** Single row holding app-wide state. */
@Entity(tableName = "app_state") internal data class AppStateEntity(@PrimaryKey val id: Int = 0, val activeAccountId: String?)

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
    val checkedAtMillis: Long? = null,
    /** [KeyScheme.value]: rows from before LUD-26 at 92e430f are legacy. */
    @ColumnInfo(defaultValue = "0") val scheme: Int = KeyScheme.Legacy.value,
    val share: Long? = null,
) {
    fun toModel() =
        Note(
            id,
            accountId,
            host,
            kind = NoteKind.valueOf(kind),
            purpose,
            index = noteIndex,
            amountMsat,
            certificate,
            state = NoteState.valueOf(state),
            updatedAtMillis,
            checkedAtMillis,
            KeyScheme.of(scheme),
            share,
        )

    companion object {
        fun from(n: Note) =
            NoteEntity(
                n.id,
                n.accountId,
                n.host,
                n.kind.name,
                n.purpose,
                noteIndex = n.index,
                n.amountMsat,
                n.certificate,
                n.state.name,
                n.updatedAtMillis,
                n.checkedAtMillis,
                n.scheme.value,
                n.share,
            )
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
internal data class AddressEntity(
    val accountId: String,
    val host: String,
    val username: String,
    val addedAtMillis: Long,
    /** Its LUD-26 share, or null while it is registered for the legacy 2f17578 branch `cx1`. */
    val share: Long? = null,
    /** The address fee's invoice while the mint holds the name for it. */
    val feeInvoice: String? = null,
) {
    fun toModel() = RegisteredAddress(username, host, addedAtMillis, share, feeInvoice)
}

/** Legacy: LUD-26 draft 2f17578. The next index per account, host and legacy purpose (tag: keys). */
@Entity(tableName = "counters", primaryKeys = ["accountId", "host", "purpose"])
internal data class CounterEntity(val accountId: String, val host: String, val purpose: Int, val next: Long)

/**
 * The next unused index of one of an account's LUD-26 counters ([KeyCounter]) on a mint domain: keys derive from the domain, never the
 * port, so mints sharing a hostname share these.
 */
@Entity(tableName = "key_counters", primaryKeys = ["accountId", "domain", "counter"])
internal data class KeyCounterEntity(val accountId: String, val domain: String, val counter: String, val next: Long)

/** Legacy: LUD-26 draft 2f17578. First purpose-2 index the next Lightning address scan starts from, per account and host (tag: keys). */
@Entity(tableName = "address_scan", primaryKeys = ["accountId", "host"])
internal data class AddressScanEntity(val accountId: String, val host: String, val next: Long)

/** First note index of share [share] the next scan of it at [host] starts from. */
@Entity(tableName = "share_scan", primaryKeys = ["accountId", "host", "share"])
internal data class ShareScanEntity(val accountId: String, val host: String, val share: Long, val next: Long)

/**
 * The last answer the mint gave for one derived key (public lookup by `p`, never by spend). Failed lookups are not saved. [result] is the
 * [KeyProbe] subtype; [amountMsat], [certified] and [imported] belong to `Outstanding`, [reason] to `Refused`.
 */
@Entity(tableName = "key_checks", primaryKeys = ["accountId", "host", "scheme", "purpose", "share", "keyIndex"])
internal data class KeyCheckEntity(
    val accountId: String,
    val host: String,
    /** [KeyScheme.value]. */
    val scheme: Int,
    val purpose: Int,
    /** The share of a chain-2 key, or -1. */
    val share: Long,
    val keyIndex: Long,
    val result: String,
    val amountMsat: Long?,
    val certified: Boolean,
    val imported: Boolean,
    val reason: String?,
    val checkedAtMillis: Long,
) {
    fun id() = KeyId(accountId, host, purpose, keyIndex, KeyScheme.of(scheme), share.takeIf { it >= 0 })

    fun toModel(): KeyProbe? =
        when (result) {
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
            val (result, outstanding) =
                when (p) {
                    is KeyProbe.Unused -> UNUSED to null
                    is KeyProbe.Outstanding -> OUTSTANDING to p
                    is KeyProbe.Spent -> SPENT to null
                    is KeyProbe.Refused -> REFUSED to null
                    is KeyProbe.Failed -> return null
                }
            return KeyCheckEntity(
                id.accountId,
                id.host,
                id.scheme.value,
                id.purpose,
                id.share ?: -1,
                keyIndex = id.index,
                result,
                outstanding?.amountMsat,
                certified = outstanding?.certified ?: false,
                imported = outstanding?.imported ?: false,
                reason = (p as? KeyProbe.Refused)?.reason,
                p.checkedAtMillis,
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
    /** `accountId:scheme:purpose:share:index:q` (share empty for none), comma-separated; legacy rows `accountId:purpose:index:q`. */
    val outputs: String,
    val requestUrl: String,
    val status: String,
    val detail: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toModel() =
        JournalEntry(
            id,
            accountId,
            type = OperationType.valueOf(type),
            host,
            withdrawUrl,
            inputIds = inputIds.split(),
            inputK1s = inputK1s.split(),
            outputs = outputs.split().map(::output),
            requestUrl,
            status = JournalStatus.valueOf(status),
            detail,
            createdAtMillis,
            updatedAtMillis,
        )

    companion object {
        @OptIn(RevealsNoteSecret::class) // stored in the encrypted database
        fun from(e: JournalEntry) =
            JournalEntity(
                e.id,
                e.accountId,
                e.type.name,
                e.host,
                e.withdrawUrl,
                inputIds = e.inputIds.joinToString(","),
                inputK1s = e.inputK1s.joinToString(","),
                outputs =
                    e.outputs.joinToString(",") {
                        "${it.accountId}:${it.scheme.value}:${it.purpose}:${it.share ?: ""}:${it.index}:${it.q}"
                    },
                e.requestUrl,
                e.status.name,
                e.detail,
                e.createdAtMillis,
                e.updatedAtMillis,
            )

        private fun String.split(): List<String> = if (isEmpty()) emptyList() else split(',')

        private fun output(text: String): OutputRef {
            val parts = text.split(':')
            return if (parts.size == 4) {
                // Written before LUD-26 at 92e430f: a legacy key.
                val (a, p, i, q) = parts
                OutputRef(a, p.toInt(), i.toLong(), q, KeyScheme.Legacy)
            } else {
                val (a, scheme, p, share) = parts
                OutputRef(a, p.toInt(), parts[4].toLong(), parts[5], KeyScheme.of(scheme.toInt()), share.toLongOrNull())
            }
        }
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
    fun toModel() =
        Activity(
            id,
            accountId,
            kind = ActivityKind.valueOf(kind),
            amountMsat,
            feeMsat,
            host,
            counterparty,
            certified,
            noteCount,
            automatic,
            status = ActivityStatus.valueOf(status),
            journalId,
            createdAtMillis,
        )

    companion object {
        fun from(a: Activity) =
            ActivityEntity(
                a.id,
                a.accountId,
                a.kind.name,
                a.amountMsat,
                a.feeMsat,
                a.host,
                a.counterparty,
                a.certified,
                a.noteCount,
                a.automatic,
                a.status.name,
                a.journalId,
                a.createdAtMillis,
            )
    }
}

/** A note received as a link (see [ReceivedNote]). */
@Entity(tableName = "received_notes", indices = [Index("accountId")])
internal data class ReceivedNoteEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val host: String,
    val withdrawUrl: String,
    val k1: String?,
    val certificate: String?,
    val declaredMsat: Long?,
    val amountMsat: Long?,
    val lockedUntilMillis: Long?,
    val lockedForSeconds: Long?,
    val state: String,
    val journalId: String?,
    val receivedAtMillis: Long,
    val updatedAtMillis: Long,
    val refusedSinceMillis: Long?,
) {
    fun toModel() =
        ReceivedNote(
            id,
            accountId,
            host,
            withdrawUrl,
            k1,
            certificate,
            declaredMsat,
            amountMsat,
            lockedUntilMillis,
            lockedForSeconds,
            state = ReceivedState.valueOf(state),
            journalId,
            receivedAtMillis,
            updatedAtMillis,
            refusedSinceMillis,
        )

    companion object {
        @OptIn(RevealsNoteSecret::class) // stored in the encrypted database: the seed can't derive it again
        fun from(n: ReceivedNote) =
            ReceivedNoteEntity(
                n.id,
                n.accountId,
                n.host,
                n.withdrawUrl,
                n.k1,
                n.certificate,
                n.declaredMsat,
                n.amountMsat,
                n.lockedUntilMillis,
                n.lockedForSeconds,
                n.state.name,
                n.journalId,
                n.receivedAtMillis,
                n.updatedAtMillis,
                n.refusedSinceMillis,
            )
    }
}
