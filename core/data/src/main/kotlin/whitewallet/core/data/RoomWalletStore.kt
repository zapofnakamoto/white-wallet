package whitewallet.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.WalletStore
import whitewallet.lnurlcash.spendDomain

internal class RoomWalletStore(private val db: WalletDatabase) : WalletStore {
    private val dao = db.dao()

    override val accounts: Flow<List<AccountSummary>> =
        dao.accountSummaries().map { rows ->
            rows.map { r -> AccountSummary(Account(r.id, r.name, r.createdAtMillis, r.walletId, r.accountIndex), r.balanceMsat) }
        }
    override val activeAccountId: Flow<String?> = dao.activeAccountId()

    override suspend fun currentAccountId() = dao.currentAccountId()

    override suspend fun setActiveAccount(id: String?) = dao.saveAppState(AppStateEntity(activeAccountId = id))

    override val wallets: Flow<List<WalletRecord>> = dao.wallets().map { list -> list.map { it.toModel() } }

    override suspend fun wallet(id: String) = dao.wallet(id)?.toModel()

    override suspend fun saveWallet(wallet: WalletRecord) = dao.saveWallet(WalletEntity.from(wallet))

    override suspend fun account(id: String) = dao.account(id)?.toModel()

    override suspend fun saveAccount(account: Account) {
        val row = AccountEntity.from(account)
        if (dao.updateAccount(row) == 0) dao.insertAccount(row)
    }

    override suspend fun deleteAccount(id: String) = db.withTransaction {
        // Money and operations are deleted by name (their foreign keys restrict); everything else of the account cascades.
        dao.deleteNotes(id)
        dao.deleteJournal(id)
        dao.deleteReceivedNotes(id)
        val walletId = dao.account(id)?.walletId
        dao.deleteAccountRow(id)
        // The wallet's mints cascade with it.
        walletId?.let { dao.deleteWalletIfEmpty(it) }
        if (dao.currentAccountId() == id) dao.saveAppState(AppStateEntity(activeAccountId = null))
    }

    override fun notes(accountId: String): Flow<List<Note>> = dao.notes(accountId).map { list -> list.map { it.toModel() } }

    override fun primaryAddress(accountId: String): Flow<RegisteredAddress?> = dao.primaryAddress(accountId).map { it?.toModel() }

    override suspend fun currentPrimaryAddress(accountId: String) = dao.currentPrimaryAddress(accountId)?.toModel()

    override suspend fun setPrimaryAddress(accountId: String, username: String?, host: String?) = db.withTransaction {
        dao.clearPrimary(accountId)
        if (username != null && host != null) check(dao.markPrimary(accountId, host, username) == 1) { "not an address of this account" }
    }

    override fun addresses(accountId: String): Flow<List<RegisteredAddress>> =
        dao.addresses(accountId).map { list -> list.map { it.toModel() } }

    override suspend fun saveAddress(accountId: String, address: RegisteredAddress) = db.withTransaction {
        val primary = dao.address(accountId, address.host, address.username)?.isPrimary
        dao.saveAddress(
            AddressEntity(accountId, address.host, address.username, address.addedAtMillis, address.share, address.feeInvoice, primary)
        )
    }

    override suspend fun deleteAddress(accountId: String, username: String, host: String) = dao.deleteAddress(accountId, host, username)

    override suspend fun mint(walletId: String, host: String) = dao.mint(walletId, host)?.toModel()

    override suspend fun saveMint(mint: Mint) = dao.saveMint(MintEntity.from(mint))

    override suspend fun removeMint(walletId: String, host: String) = dao.removeMint(walletId, host)

    override suspend fun removeMints(walletId: String) = dao.removeMints(walletId)

    override fun mints(walletId: String): Flow<List<Mint>> = dao.mints(walletId).map { list -> list.map { it.toModel() } }

    override suspend fun note(id: String) = dao.note(id)?.toModel()

    override suspend fun upsertNotes(notes: List<Note>) = dao.upsertNotes(notes.map(NoteEntity::from))

    override suspend fun reserveKeyIndex(accountId: String, domain: String, counter: KeyCounter): Long = db.withTransaction {
        val next = dao.keyCounter(accountId, domain, counter.name) ?: 0
        dao.saveKeyCounter(KeyCounterEntity(accountId, domain, counter.name, next + 1))
        next
    }

    override suspend fun keyIndexOrNull(accountId: String, domain: String, counter: KeyCounter) =
        dao.keyCounter(accountId, domain, counter.name)

    override suspend fun setKeyIndex(accountId: String, domain: String, counter: KeyCounter, next: Long) =
        dao.saveKeyCounter(KeyCounterEntity(accountId, domain, counter.name, next))

    override suspend fun shareScanFrom(accountId: String, host: String, share: Long) = dao.shareScanFrom(accountId, host, share) ?: 0

    override suspend fun setShareScanFrom(accountId: String, host: String, share: Long, index: Long) =
        dao.saveShareScan(ShareScanEntity(accountId, host, share, index))

    override suspend fun reserveIndex(accountId: String, host: String, purpose: Int): Long = db.withTransaction {
        val next = dao.counter(accountId, host, purpose) ?: 0
        dao.saveCounter(CounterEntity(accountId, host, purpose, next + 1))
        next
    }

    override suspend fun currentIndexOrNull(accountId: String, host: String, purpose: Int) = dao.counter(accountId, host, purpose)

    override suspend fun setIndex(accountId: String, host: String, purpose: Int, next: Long) =
        dao.saveCounter(CounterEntity(accountId, host, purpose, next))

    override fun indexesFlow(accountId: String, host: String): Flow<Map<KeySet, Long>> =
        combine(
            dao.keyCountersFlow(accountId, spendDomain(host)),
            dao.shareScansFlow(accountId, host),
            dao.countersFlow(accountId, host),
            dao.addressScanFlow(accountId, host),
        ) { keyCounters, shareScans, counters, scan ->
            buildMap {
                keyCounters.forEach { row ->
                    when (row.counter) {
                        KeyCounter.Wallet.name -> put(KeySet.Wallet, row.next)
                        KeyCounter.Change.name -> put(KeySet.Change, row.next)
                    }
                }
                shareScans.forEach { put(KeySet.share(it.share), it.next) }
                counters.filter { it.purpose != 2 }.forEach { put(KeySet(KeyScheme.Legacy, it.purpose), it.next) }
                scan?.let { put(KeySet(KeyScheme.Legacy, 2), it) }
            }
        }

    override suspend fun addressScanFrom(accountId: String, host: String) = dao.addressScanFrom(accountId, host) ?: 0

    override suspend fun setAddressScanFrom(accountId: String, host: String, index: Long) =
        dao.saveAddressScan(AddressScanEntity(accountId, host, index))

    override fun keyChecks(accountId: String): Flow<Map<KeyId, KeyProbe>> =
        dao.keyChecks(accountId).map { rows -> rows.mapNotNull { row -> row.toModel()?.let { row.id() to it } }.toMap() }

    override suspend fun putKeyCheck(id: KeyId, probe: KeyProbe) {
        KeyCheckEntity.from(id, probe)?.let { dao.putKeyCheck(it) }
    }

    override suspend fun putJournal(entry: JournalEntry) = dao.putJournal(JournalEntity.from(entry))

    override suspend fun journal(statuses: Set<JournalStatus>) = dao.journal(statuses.map { it.name }).map { it.toModel() }

    override fun activities(accountId: String): Flow<List<Activity>> = dao.activities(accountId).map { rows -> rows.map { it.toModel() } }

    override suspend fun putActivity(activity: Activity) = dao.putActivity(ActivityEntity.from(activity))

    override suspend fun settleActivities(journalId: String, status: ActivityStatus) = dao.settleActivities(journalId, status.name)

    override suspend fun counterHosts(accountId: String) = dao.counterHosts(accountId)

    override fun receivedNotes(accountId: String): Flow<List<ReceivedNote>> =
        dao.receivedNotes(accountId).map { rows -> rows.map { it.toModel() } }

    override suspend fun receivedNote(id: String) = dao.receivedNote(id)?.toModel()

    override suspend fun receivedNotes(states: Set<ReceivedState>) = dao.receivedNotes(states.map { it.name }).map { it.toModel() }

    override suspend fun putReceivedNote(note: ReceivedNote) = dao.putReceivedNote(ReceivedNoteEntity.from(note))

    override suspend fun deleteReceivedNote(id: String) = dao.deleteReceivedNote(id)
}
