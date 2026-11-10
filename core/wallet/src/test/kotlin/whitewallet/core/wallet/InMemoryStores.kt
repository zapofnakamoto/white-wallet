package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletRecord
import whitewallet.lnurlcash.spendDomain

/** A test's account's primary address, as [InMemoryWalletStore.addAccount] sets it up. */
data class PrimaryAddress(val username: String, val host: String) {
    val address: String
        get() = "$username@$host"
}

/**
 * [WalletStore] in memory. Like the database's foreign keys, it refuses an account before its wallet, an account's rows (counters, scans,
 * addresses) before the account, and a wallet's mint before the wallet.
 */
class InMemoryWalletStore : WalletStore {
    private val walletMap = MutableStateFlow<Map<String, WalletRecord>>(emptyMap())
    private val accountMap = MutableStateFlow<Map<String, Account>>(emptyMap())
    private val noteMap = MutableStateFlow<Map<String, Note>>(emptyMap())
    /** Per account, its primary address as (username, host). */
    private val primaries = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    private val addressMap = MutableStateFlow<Map<String, List<RegisteredAddress>>>(emptyMap())
    private val active = MutableStateFlow<String?>(null)
    val mintMap = MutableStateFlow(emptyMap<Pair<String, String>, Mint>())
    val counters = mutableMapOf<Triple<String, String, Int>, Long>()
    val keyCounters = mutableMapOf<Triple<String, String, KeyCounter>, Long>()
    val shareScans = mutableMapOf<Triple<String, String, Long>, Long>()
    val scanFrom = mutableMapOf<Pair<String, String>, Long>()
    val journal = linkedMapOf<String, JournalEntry>()
    private val checks = MutableStateFlow<Map<KeyId, KeyProbe>>(emptyMap())
    /** Insertion order breaks ties between rows written in the same millisecond, like Room's rowid. */
    private val activityMap = MutableStateFlow<Map<String, Pair<Long, Activity>>>(emptyMap())
    private var activitySeq = 0L
    /** Bumped on every counter or scan-start write, so [indexesFlow] can be observed like Room's. */
    private val indexVersion = MutableStateFlow(0L)

    override val accounts: Flow<List<AccountSummary>> =
        combine(accountMap, noteMap) { accounts, notes ->
            accounts.values
                .sortedBy { it.createdAtMillis }
                .map { a ->
                    AccountSummary(
                        a,
                        notes.values.filter { it.accountId == a.id && it.state == NoteState.Outstanding }.sumOf { it.amountMsat },
                    )
                }
        }

    override val wallets: Flow<List<WalletRecord>> = walletMap.map { it.values.sortedBy { w -> w.createdAtMillis } }

    override suspend fun wallet(id: String) = walletMap.value[id]

    override suspend fun saveWallet(wallet: WalletRecord) {
        walletMap.update { it + (wallet.id to wallet) }
    }

    override val activeAccountId: Flow<String?> = active

    override suspend fun currentAccountId() = active.value

    override suspend fun setActiveAccount(id: String?) {
        active.value = id
    }

    override suspend fun account(id: String) = accountMap.value[id]

    private fun requireAccount(id: String) = check(id in accountMap.value) { "row of account $id saved before the account" }

    override suspend fun saveAccount(account: Account) {
        check(account.walletId in walletMap.value) { "account ${account.id} saved before its wallet ${account.walletId}" }
        check(accountMap.value.values.none { it.id != account.id && it.walletId == account.walletId && it.index == account.index }) {
            "account ${account.index} of wallet ${account.walletId} exists already"
        }
        accountMap.value = accountMap.value + (account.id to account)
    }

    override suspend fun deleteAccount(id: String) {
        val walletId = accountMap.value[id]?.walletId
        primaries.value = primaries.value - id
        addressMap.value = addressMap.value - id
        noteMap.value = noteMap.value.filterValues { it.accountId != id }
        counters.keys.removeAll { it.first == id }
        keyCounters.keys.removeAll { it.first == id }
        shareScans.keys.removeAll { it.first == id }
        scanFrom.keys.removeAll { it.first == id }
        journal.values.removeAll { it.accountId == id }
        checks.value = checks.value.filterKeys { it.accountId != id }
        activityMap.value = activityMap.value.filterValues { it.second.accountId != id }
        receivedMap.value = receivedMap.value.filterValues { it.accountId != id }
        accountMap.value = accountMap.value - id
        if (walletId != null && accountMap.value.values.none { it.walletId == walletId }) {
            removeMints(walletId)
            walletMap.update { it - walletId }
        }
        if (active.value == id) active.value = null
    }

    override fun notes(accountId: String): Flow<List<Note>> = noteMap.map { it.values.filter { n -> n.accountId == accountId } }

    override fun primaryAddress(accountId: String): Flow<RegisteredAddress?> =
        combine(primaries, addressMap) { primaries, addresses -> primaryOf(accountId, primaries, addresses) }

    override suspend fun currentPrimaryAddress(accountId: String) = primaryOf(accountId, primaries.value, addressMap.value)

    private fun primaryOf(
        accountId: String,
        primaries: Map<String, Pair<String, String>>,
        addresses: Map<String, List<RegisteredAddress>>,
    ) = primaries[accountId]?.let { (username, host) -> addresses[accountId]?.firstOrNull { it.username == username && it.host == host } }

    override suspend fun setPrimaryAddress(accountId: String, username: String?, host: String?) {
        if (username == null || host == null) {
            primaries.update { it - accountId }
            return
        }
        check(addressMap.value[accountId].orEmpty().any { it.username == username && it.host == host }) { "not an address of this account" }
        primaries.update { it + (accountId to (username to host)) }
    }

    override fun addresses(accountId: String): Flow<List<RegisteredAddress>> = addressMap.map {
        it[accountId].orEmpty().sortedBy { a -> a.addedAtMillis }
    }

    override suspend fun saveAddress(accountId: String, address: RegisteredAddress) {
        requireAccount(accountId)
        val others = addressMap.value[accountId].orEmpty().filterNot { it.username == address.username && it.host == address.host }
        addressMap.value = addressMap.value + (accountId to others + address)
    }

    override suspend fun deleteAddress(accountId: String, username: String, host: String) {
        if (primaries.value[accountId] == (username to host)) primaries.update { it - accountId }
        addressMap.value =
            addressMap.value + (accountId to addressMap.value[accountId].orEmpty().filterNot { it.username == username && it.host == host })
    }

    override suspend fun mint(walletId: String, host: String) = mintMap.value[walletId to host]

    override suspend fun saveMint(mint: Mint) {
        check(mint.walletId in walletMap.value) { "mint ${mint.host} saved before its wallet ${mint.walletId}" }
        mintMap.update { it + ((mint.walletId to mint.host) to mint) }
    }

    override suspend fun removeMint(walletId: String, host: String) {
        mintMap.update { it - (walletId to host) }
    }

    override suspend fun removeMints(walletId: String) {
        mintMap.update { all -> all.filterKeys { it.first != walletId } }
    }

    /** The mint at [host] of the one wallet that has it, for tests of a phone with a single wallet there. */
    fun mint(host: String): Mint? =
        mintMap.value.values.filter { it.host == host }.also { check(it.size <= 1) { "several wallets have $host" } }.firstOrNull()

    override fun mints(walletId: String): Flow<List<Mint>> = mintMap.map { all ->
        all.values.filter { it.walletId == walletId }.sortedBy { it.host }
    }

    override suspend fun note(id: String) = noteMap.value[id]

    override suspend fun upsertNotes(notes: List<Note>) {
        noteMap.value = noteMap.value + notes.associateBy { it.id }
    }

    override suspend fun reserveKeyIndex(accountId: String, domain: String, counter: KeyCounter): Long {
        requireAccount(accountId)
        val key = Triple(accountId, domain, counter)
        val next = keyCounters[key] ?: 0
        keyCounters[key] = next + 1
        indexVersion.value++
        return next
    }

    override suspend fun keyIndexOrNull(accountId: String, domain: String, counter: KeyCounter) =
        keyCounters[Triple(accountId, domain, counter)]

    override suspend fun setKeyIndex(accountId: String, domain: String, counter: KeyCounter, next: Long) {
        requireAccount(accountId)
        keyCounters[Triple(accountId, domain, counter)] = next
        indexVersion.value++
    }

    override suspend fun shareScanFrom(accountId: String, host: String, share: Long) = shareScans[Triple(accountId, host, share)] ?: 0

    override suspend fun setShareScanFrom(accountId: String, host: String, share: Long, index: Long) {
        requireAccount(accountId)
        shareScans[Triple(accountId, host, share)] = index
        indexVersion.value++
    }

    override suspend fun reserveIndex(accountId: String, host: String, purpose: Int): Long {
        requireAccount(accountId)
        val key = Triple(accountId, host, purpose)
        val next = counters[key] ?: 0
        counters[key] = next + 1
        indexVersion.value++
        return next
    }

    override suspend fun currentIndexOrNull(accountId: String, host: String, purpose: Int) = counters[Triple(accountId, host, purpose)]

    override suspend fun setIndex(accountId: String, host: String, purpose: Int, next: Long) {
        requireAccount(accountId)
        counters[Triple(accountId, host, purpose)] = next
        indexVersion.value++
    }

    override fun indexesFlow(accountId: String, host: String): Flow<Map<KeySet, Long>> = indexVersion.map {
        buildMap {
            val domain = spendDomain(host)
            keyCounters.forEach { (k, next) ->
                if (k.first == accountId && k.second == domain) {
                    when (k.third) {
                        KeyCounter.Wallet -> put(KeySet.Wallet, next)
                        KeyCounter.Change -> put(KeySet.Change, next)
                        KeyCounter.Shares -> Unit
                    }
                }
            }
            shareScans.forEach { (k, next) -> if (k.first == accountId && k.second == host) put(KeySet.share(k.third), next) }
            counters.forEach { (k, next) ->
                if (k.first == accountId && k.second == host && k.third != 2) put(KeySet(KeyScheme.Legacy, k.third), next)
            }
            scanFrom[accountId to host]?.let { put(KeySet(KeyScheme.Legacy, 2), it) }
        }
    }

    override suspend fun addressScanFrom(accountId: String, host: String) = scanFrom[accountId to host] ?: 0

    override suspend fun setAddressScanFrom(accountId: String, host: String, index: Long) {
        scanFrom[accountId to host] = index
        indexVersion.value++
    }

    override fun keyChecks(accountId: String): Flow<Map<KeyId, KeyProbe>> = checks.map { it.filterKeys { id -> id.accountId == accountId } }

    override suspend fun putKeyCheck(id: KeyId, probe: KeyProbe) {
        checks.value = checks.value + (id to probe)
    }

    override suspend fun putJournal(entry: JournalEntry) {
        journal[entry.id] = entry
    }

    override suspend fun counterHosts(accountId: String) = counters.keys.filter { it.first == accountId }.map { it.second }.distinct()

    override suspend fun journal(statuses: Set<JournalStatus>) = journal.values.filter { it.status in statuses }

    override fun activities(accountId: String): Flow<List<Activity>> = activityMap.map { rows ->
        rows.values
            .filter { it.second.accountId == accountId }
            .sortedWith(compareByDescending<Pair<Long, Activity>> { it.second.createdAtMillis }.thenByDescending { it.first })
            .map { it.second }
    }

    override suspend fun putActivity(activity: Activity) {
        activityMap.value = activityMap.value + (activity.id to ((activityMap.value[activity.id]?.first ?: activitySeq++) to activity))
    }

    override suspend fun settleActivities(journalId: String, status: ActivityStatus) {
        activityMap.value =
            activityMap.value.mapValues { (_, row) ->
                if (row.second.journalId == journalId && row.second.status == ActivityStatus.Pending)
                    row.first to row.second.copy(status = status)
                else row
            }
    }

    private val receivedMap = MutableStateFlow<Map<String, ReceivedNote>>(emptyMap())

    override fun receivedNotes(accountId: String): Flow<List<ReceivedNote>> = receivedMap.map {
        it.values.filter { n -> n.accountId == accountId }.sortedByDescending { n -> n.receivedAtMillis }
    }

    override suspend fun receivedNote(id: String) = receivedMap.value[id]

    override suspend fun receivedNotes(states: Set<ReceivedState>) =
        receivedMap.value.values.filter { it.state in states }.sortedBy { it.receivedAtMillis }

    override suspend fun putReceivedNote(note: ReceivedNote) {
        receivedMap.value = receivedMap.value + (note.id to note)
    }

    override suspend fun deleteReceivedNote(id: String) {
        receivedMap.value = receivedMap.value - id
    }

    fun allNotes() = noteMap.value.values.toList()

    /** [accountId]'s home mint. */
    suspend fun homeOf(accountId: String): String? = account(accountId)?.homeHost

    /**
     * Creates an account in a wallet of its own (home mint [host]) with [primary] as its primary address, and makes it active, as a
     * successful onboarding would.
     */
    suspend fun addAccount(id: String, name: String, primary: PrimaryAddress?, host: String = primary?.host ?: "mint.example") {
        if (id !in walletMap.value) saveWallet(WalletRecord(id, name, accountMap.value.size.toLong()))
        saveAccount(Account(id, name, accountMap.value.size.toLong(), homeHost = host))
        primary?.let {
            saveAddress(id, RegisteredAddress(it.username, it.host, 0, share = 0))
            setPrimaryAddress(id, it.username, it.host)
        }
        setActiveAccount(id)
    }
}

class InMemorySeedStore(initial: Map<String, List<String>> = emptyMap()) : SeedStore {
    private val words = initial.toMutableMap()
    private val verified = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun load(walletId: String) = words[walletId]

    override suspend fun save(walletId: String, words: List<String>) {
        this.words[walletId] = words
    }

    override fun backupVerified(walletId: String): Flow<Boolean> = verified.map { walletId in it }

    override suspend fun setBackupVerified(walletId: String) {
        verified.value = verified.value + walletId
    }

    override suspend fun clear(walletId: String) {
        words.remove(walletId)
        verified.value = verified.value - walletId
    }

    fun walletIds() = words.keys.toSet()
}
