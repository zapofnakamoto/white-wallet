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
import whitewallet.core.model.Profile
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.WalletStore
import whitewallet.lnurlcash.spendDomain

internal class RoomWalletStore(private val db: WalletDatabase) : WalletStore {
    private val dao = db.dao()

    override val accounts: Flow<List<AccountSummary>> =
        dao.accountSummaries().map { rows ->
            rows.map { r ->
                val profile = if (r.username != null && r.addressHost != null) Profile(r.username, r.addressHost) else null
                AccountSummary(Account(r.id, r.name, r.createdAtMillis, r.host), profile, r.balanceMsat)
            }
        }
    override val activeAccountId: Flow<String?> = dao.activeAccountId()

    override suspend fun currentAccountId() = dao.currentAccountId()

    override suspend fun setActiveAccount(id: String?) = dao.saveAppState(AppStateEntity(activeAccountId = id))

    override suspend fun account(id: String) = dao.account(id)?.toModel()

    override suspend fun saveAccount(account: Account) = db.withTransaction {
        if (dao.wallet(account.walletId) == null) dao.saveWallet(WalletEntity(account.walletId, account.name, account.createdAtMillis))
        dao.saveAccount(AccountEntity.from(account))
    }

    override suspend fun deleteAccount(id: String) = db.withTransaction {
        dao.deleteNotes(id)
        dao.deleteProfile(id)
        dao.deleteCounters(id)
        dao.deleteAddressScan(id)
        dao.deleteJournal(id)
        dao.deleteKeyChecks(id)
        dao.deleteAddresses(id)
        dao.deleteActivities(id)
        dao.deleteReceivedNotes(id)
        dao.deleteKeyCounters(id)
        dao.deleteShareScans(id)
        val walletId = dao.account(id)?.walletId
        dao.deleteAccountRow(id)
        walletId?.let { dao.deleteWalletIfEmpty(it) }
        if (dao.currentAccountId() == id) dao.saveAppState(AppStateEntity(activeAccountId = null))
    }

    override fun notes(accountId: String): Flow<List<Note>> = dao.notes(accountId).map { list -> list.map { it.toModel() } }

    override fun profile(accountId: String): Flow<Profile?> = dao.profile(accountId).map { it?.toModel() }

    override suspend fun currentProfile(accountId: String) = dao.currentProfile(accountId)?.toModel()

    override suspend fun saveProfile(accountId: String, profile: Profile) = db.withTransaction {
        dao.saveProfile(ProfileEntity(accountId, profile.username, profile.host))
        if (dao.addressCount(accountId, profile.host, profile.username) == 0) {
            dao.saveAddress(AddressEntity(accountId, profile.host, profile.username, System.currentTimeMillis()))
        }
    }

    override fun addresses(accountId: String): Flow<List<RegisteredAddress>> =
        dao.addresses(accountId).map { list -> list.map { it.toModel() } }

    override suspend fun saveAddress(accountId: String, address: RegisteredAddress) =
        dao.saveAddress(AddressEntity(accountId, address.host, address.username, address.addedAtMillis, address.share, address.feeInvoice))

    override suspend fun deleteAddress(accountId: String, username: String, host: String) = dao.deleteAddress(accountId, host, username)

    override suspend fun mint(host: String) = dao.mint(host)?.toModel()

    override suspend fun saveMint(mint: Mint) = dao.saveMint(MintEntity.from(mint))

    override suspend fun removeMint(host: String) = dao.removeMint(host)

    override val mints: Flow<List<Mint>> = dao.mints().map { list -> list.map { it.toModel() } }

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
