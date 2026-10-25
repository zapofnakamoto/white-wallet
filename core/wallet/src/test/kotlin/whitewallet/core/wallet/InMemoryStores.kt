package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.Service

class InMemoryWalletStore : WalletStore {
    private val accountMap = MutableStateFlow<Map<String, Account>>(emptyMap())
    private val noteMap = MutableStateFlow<Map<String, Note>>(emptyMap())
    private val profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val addressMap = MutableStateFlow<Map<String, List<RegisteredAddress>>>(emptyMap())
    private val active = MutableStateFlow<String?>(null)
    val serviceMap = mutableMapOf<String, Service>()
    val counters = mutableMapOf<Triple<String, String, Int>, Long>()
    val scanFrom = mutableMapOf<Pair<String, String>, Long>()
    val journal = linkedMapOf<String, JournalEntry>()
    private val checks = MutableStateFlow<Map<KeyId, KeyProbe>>(emptyMap())
    /** Insertion order breaks ties between rows written in the same millisecond, like Room's rowid. */
    private val activityMap = MutableStateFlow<Map<String, Pair<Long, Activity>>>(emptyMap())
    private var activitySeq = 0L
    /** Bumped on every counter or scan-start write, so [indexesFlow] can be observed like Room's. */
    private val indexVersion = MutableStateFlow(0L)

    override val accounts: Flow<List<AccountSummary>> = combine(accountMap, profiles, noteMap) { accounts, profiles, notes ->
        accounts.values.sortedBy { it.createdAtMillis }.map { a ->
            AccountSummary(a, profiles[a.id], notes.values.filter { it.accountId == a.id && it.state == NoteState.Outstanding }.sumOf { it.amountMsat })
        }
    }
    override val activeAccountId: Flow<String?> = active
    override suspend fun currentAccountId() = active.value
    override suspend fun setActiveAccount(id: String?) { active.value = id }
    override suspend fun account(id: String) = accountMap.value[id]
    override suspend fun saveAccount(account: Account) { accountMap.value = accountMap.value + (account.id to account) }
    override suspend fun deleteAccount(id: String) {
        accountMap.value = accountMap.value - id
        profiles.value = profiles.value - id
        addressMap.value = addressMap.value - id
        noteMap.value = noteMap.value.filterValues { it.accountId != id }
        counters.keys.removeAll { it.first == id }
        scanFrom.keys.removeAll { it.first == id }
        journal.values.removeAll { it.accountId == id }
        checks.value = checks.value.filterKeys { it.accountId != id }
        activityMap.value = activityMap.value.filterValues { it.second.accountId != id }
        if (active.value == id) active.value = null
    }
    override fun notes(accountId: String): Flow<List<Note>> = noteMap.map { it.values.filter { n -> n.accountId == accountId } }
    override fun profile(accountId: String): Flow<Profile?> = profiles.map { it[accountId] }
    override suspend fun currentProfile(accountId: String) = profiles.value[accountId]
    override suspend fun saveProfile(accountId: String, profile: Profile) {
        profiles.value = profiles.value + (accountId to profile)
        val list = addressMap.value[accountId].orEmpty()
        if (list.none { it.username == profile.username && it.host == profile.host }) {
            saveAddress(accountId, RegisteredAddress(profile.username, profile.host, System.currentTimeMillis()))
        }
    }
    override fun addresses(accountId: String): Flow<List<RegisteredAddress>> = addressMap.map { it[accountId].orEmpty().sortedBy { a -> a.addedAtMillis } }
    override suspend fun saveAddress(accountId: String, address: RegisteredAddress) {
        val others = addressMap.value[accountId].orEmpty().filterNot { it.username == address.username && it.host == address.host }
        addressMap.value = addressMap.value + (accountId to others + address)
    }
    override suspend fun deleteAddress(accountId: String, username: String, host: String) {
        addressMap.value = addressMap.value + (accountId to addressMap.value[accountId].orEmpty().filterNot { it.username == username && it.host == host })
    }
    override suspend fun service(host: String) = serviceMap[host]
    override suspend fun saveService(service: Service) {
        serviceMap[service.host] = service
        servicesFlow.value = serviceMap.values.sortedBy { it.host }
    }
    private val servicesFlow = MutableStateFlow<List<Service>>(emptyList())
    override val services get() = servicesFlow
    override suspend fun note(id: String) = noteMap.value[id]
    override suspend fun upsertNotes(notes: List<Note>) { noteMap.value = noteMap.value + notes.associateBy { it.id } }
    override suspend fun reserveIndex(accountId: String, host: String, purpose: Int): Long {
        val key = Triple(accountId, host, purpose)
        val next = counters[key] ?: 0
        counters[key] = next + 1
        indexVersion.value++
        return next
    }
    override suspend fun currentIndexOrNull(accountId: String, host: String, purpose: Int) = counters[Triple(accountId, host, purpose)]
    override suspend fun setIndex(accountId: String, host: String, purpose: Int, next: Long) {
        counters[Triple(accountId, host, purpose)] = next
        indexVersion.value++
    }
    override fun indexesFlow(accountId: String, host: String): Flow<KeyIndexes> = indexVersion.map {
        KeyIndexes(counters[Triple(accountId, host, 0)] ?: 0, counters[Triple(accountId, host, 1)] ?: 0, scanFrom[accountId to host] ?: 0)
    }
    override suspend fun addressScanFrom(accountId: String, host: String) = scanFrom[accountId to host] ?: 0
    override suspend fun setAddressScanFrom(accountId: String, host: String, index: Long) {
        scanFrom[accountId to host] = index
        indexVersion.value++
    }
    override fun keyChecks(accountId: String): Flow<Map<KeyId, KeyProbe>> = checks.map { it.filterKeys { id -> id.accountId == accountId } }
    override suspend fun putKeyCheck(id: KeyId, probe: KeyProbe) { checks.value = checks.value + (id to probe) }
    override suspend fun putJournal(entry: JournalEntry) { journal[entry.id] = entry }
    override suspend fun counterHosts(accountId: String) = counters.keys.filter { it.first == accountId }.map { it.second }.distinct()
    override suspend fun journal(statuses: Set<JournalStatus>) = journal.values.filter { it.status in statuses }
    override fun activities(accountId: String): Flow<List<Activity>> = activityMap.map { rows ->
        rows.values.filter { it.second.accountId == accountId }
            .sortedWith(compareByDescending<Pair<Long, Activity>> { it.second.createdAtMillis }.thenByDescending { it.first })
            .map { it.second }
    }
    override suspend fun putActivity(activity: Activity) {
        activityMap.value = activityMap.value + (activity.id to ((activityMap.value[activity.id]?.first ?: activitySeq++) to activity))
    }
    override suspend fun settleActivities(journalId: String, status: ActivityStatus) {
        activityMap.value = activityMap.value.mapValues { (_, row) ->
            if (row.second.journalId == journalId && row.second.status == ActivityStatus.Pending) row.first to row.second.copy(status = status) else row
        }
    }

    fun allNotes() = noteMap.value.values.toList()

    /** Creates an account with [profile] (on [host], its home mint) and makes it active, as a successful onboarding would. */
    suspend fun addAccount(id: String, name: String, profile: Profile?, host: String = profile?.host ?: "mint.example") {
        saveAccount(Account(id, name, accountMap.value.size.toLong(), host))
        profile?.let { saveProfile(id, it) }
        setActiveAccount(id)
    }
}

class InMemorySeedStore(initial: Map<String, List<String>> = emptyMap()) : SeedStore {
    private val words = initial.toMutableMap()
    private val verified = MutableStateFlow<Set<String>>(emptySet())
    override suspend fun load(accountId: String) = words[accountId]
    override suspend fun save(accountId: String, words: List<String>) { this.words[accountId] = words }
    override fun backupVerified(accountId: String): Flow<Boolean> = verified.map { accountId in it }
    override suspend fun setBackupVerified(accountId: String) { verified.value = verified.value + accountId }
    override suspend fun clear(accountId: String) {
        words.remove(accountId)
        verified.value = verified.value - accountId
    }
    fun accountIds() = words.keys.toSet()
}
