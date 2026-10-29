package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.Profile
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.Mint
import whitewallet.lnurlcash.client.NetworkConditions

/**
 * Persistent wallet state for every account. Implemented by core:data on Android and in memory for tests.
 * Mints (a host's withdraw URL and pinned key) are shared; everything else is per account.
 */
interface WalletStore {
    /** Oldest first: an account's place here is its mark's colour, so the order must stay stable. */
    val accounts: Flow<List<AccountSummary>>
    val activeAccountId: Flow<String?>
    suspend fun currentAccountId(): String?
    suspend fun setActiveAccount(id: String?)
    suspend fun account(id: String): Account?
    suspend fun saveAccount(account: Account)

    /** Removes the account and all of its notes, addresses, counters, key checks, journal entries and history. */
    suspend fun deleteAccount(id: String)

    fun notes(accountId: String): Flow<List<Note>>
    /** The account's primary address. */
    fun profile(accountId: String): Flow<Profile?>
    suspend fun currentProfile(accountId: String): Profile?

    /** Makes [profile] the account's primary address, adding it to [addresses] if it is not there yet. */
    suspend fun saveProfile(accountId: String, profile: Profile)

    /** Every Lightning address registered to the account, oldest first; the primary one is always among them. */
    fun addresses(accountId: String): Flow<List<RegisteredAddress>>
    suspend fun saveAddress(accountId: String, address: RegisteredAddress)
    suspend fun deleteAddress(accountId: String, username: String, host: String)

    suspend fun mint(host: String): Mint?
    suspend fun saveMint(mint: Mint)

    /** Forgets the mint at [host] and its pinned key (see [Wallet.removeMint]); notes and history stay. */
    suspend fun removeMint(host: String)

    /** Every mint the wallet has connected to (all accounts share them), by host. */
    val mints: Flow<List<Mint>>

    /** A note by `Q`, whichever account holds it. */
    suspend fun note(id: String): Note?
    suspend fun upsertNotes(notes: List<Note>)

    /** Returns the next unused index for ([accountId], [host], [purpose]) and durably advances the counter, atomically. */
    suspend fun reserveIndex(accountId: String, host: String, purpose: Int): Long

    /** The next index [reserveIndex] would return, without reserving it. */
    suspend fun currentIndex(accountId: String, host: String, purpose: Int): Long = currentIndexOrNull(accountId, host, purpose) ?: 0

    /** Like [currentIndex], but null if the counter was never set (the account has not used this mint and purpose yet). */
    suspend fun currentIndexOrNull(accountId: String, host: String, purpose: Int): Long?

    /** Every host the account has a wallet or change counter on (set by its first use, or by a restore). */
    suspend fun counterHosts(accountId: String): List<String>

    /** Sets the counter directly (developer tools and recovery). */
    suspend fun setIndex(accountId: String, host: String, purpose: Int, next: Long)

    /** The account's indexes on [host] (purpose 0/1 counters and the purpose-2 scan start), updated on every change. */
    fun indexesFlow(accountId: String, host: String): Flow<KeyIndexes>

    /** First purpose-2 index the next Lightning address scan starts from. */
    suspend fun addressScanFrom(accountId: String, host: String): Long
    suspend fun setAddressScanFrom(accountId: String, host: String, index: Long)

    /** The last answer the mint gave for each of the account's derived keys that was looked up, kept across restarts. */
    fun keyChecks(accountId: String): Flow<Map<KeyId, KeyProbe>>

    /** Saves [probe] as the last answer for [id], replacing the previous one. */
    suspend fun putKeyCheck(id: KeyId, probe: KeyProbe)

    suspend fun putJournal(entry: JournalEntry)

    /** The account's history, newest first. */
    fun activities(accountId: String): Flow<List<Activity>>

    /** Adds [activity], or replaces the row with the same id. */
    suspend fun putActivity(activity: Activity)

    /** Settles every pending row waiting for [journalId]. */
    suspend fun settleActivities(journalId: String, status: ActivityStatus)

    /** Journal entries of every account with one of [statuses]. */
    suspend fun journal(statuses: Set<JournalStatus>): List<JournalEntry>
}

/** Holds each account's BIP-39 words. Implemented with Android Keystore encryption on device. */
interface SeedStore {
    suspend fun load(accountId: String): List<String>?
    suspend fun save(accountId: String, words: List<String>)

    /** Whether the user has written this account's words down and passed the verification step. */
    fun backupVerified(accountId: String): Flow<Boolean>
    suspend fun setBackupVerified(accountId: String)

    /** Deletes the account's words and backup flag. */
    suspend fun clear(accountId: String)
}

/** App-wide preferences, independent of accounts (they survive deleting every account). */
interface AppPreferences {
    /** Unlocks developer-only pages. Off by default. */
    val developerMode: Flow<Boolean>
    suspend fun setDeveloperMode(enabled: Boolean)

    /** Whether the user unlocked Developer options in About (brand `developer.options=unlock`). Off by default. */
    val developerOptionsUnlocked: Flow<Boolean>
    suspend fun setDeveloperOptionsUnlocked()

    /**
     * How many Lightning addresses an account may register per mint while developer mode is on; null until set,
     * then the brand's limit applies (see [AppDefaults.addressLimit]).
     */
    val maxAddressesPerMint: Flow<Int?>
    suspend fun setMaxAddressesPerMint(max: Int)

    /** How requests to mints behave while developer mode is on, to try the app on a bad connection. Normal until set. */
    val networkProfile: Flow<NetworkConditions.Profile>
    suspend fun setNetworkProfile(profile: NetworkConditions.Profile)

    /** The unit the user chose for amounts, or null to follow the brand's default ([AppDefaults.defaultAmountUnit]). */
    val amountUnit: Flow<AmountUnit?>
    suspend fun setAmountUnit(unit: AmountUnit)

    /** Whether the balance on Home is hidden (tap it to toggle). Off by default. */
    val balanceHidden: Flow<Boolean>
    suspend fun setBalanceHidden(hidden: Boolean)

    /** Whether opening the app asks for the phone's own lock (fingerprint, face, PIN). Off by default. */
    val appLock: Flow<Boolean>
    suspend fun setAppLock(enabled: Boolean)

    /** How the Notes tab orders notes (a name the screen defines), or null for its default. */
    val notesSort: Flow<String?>
    suspend fun setNotesSort(sort: String)
}

/**
 * Per-account note policies: auto-rotate (off by default) and the id of the chosen note strategy (null until the user
 * picks one; the brand's default applies, see [NoteStrategies]).
 */
interface NotePolicies {
    fun autoRotate(accountId: String): Flow<Boolean>
    suspend fun setAutoRotate(accountId: String, enabled: Boolean)
    fun strategy(accountId: String): Flow<String?>
    suspend fun setStrategy(accountId: String, strategyId: String)

    /** Whether notes on other mints are moved to the home mint; null until the user chooses (the brand's default). */
    fun collect(accountId: String): Flow<Boolean?>
    suspend fun setCollect(accountId: String, enabled: Boolean)

    /** The most an automatic move may cost, in percent of what it moves; null until chosen (the brand's default). */
    fun collectFeeCap(accountId: String): Flow<Int?>
    suspend fun setCollectFeeCap(accountId: String, percent: Int)

    /** Whether the user is told what the note policies did by themselves; null until chosen (the brand's default). */
    fun maintenanceNotices(accountId: String): Flow<Boolean?>
    suspend fun setMaintenanceNotices(accountId: String, enabled: Boolean)
    suspend fun clear(accountId: String)

    /** Keeps policies in memory only; the default when no persistent store is given. */
    class InMemory(private val defaultStrategy: String? = null) : NotePolicies {
        private val autoRotate = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val strategies = MutableStateFlow<Map<String, String>>(emptyMap())
        private val collect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val feeCaps = MutableStateFlow<Map<String, Int>>(emptyMap())
        private val notices = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        override fun autoRotate(accountId: String): Flow<Boolean> = autoRotate.map { it[accountId] ?: false }
        override suspend fun setAutoRotate(accountId: String, enabled: Boolean) = autoRotate.update { it + (accountId to enabled) }
        override fun strategy(accountId: String): Flow<String?> = strategies.map { it[accountId] ?: defaultStrategy }
        override suspend fun setStrategy(accountId: String, strategyId: String) = strategies.update { it + (accountId to strategyId) }
        override fun collect(accountId: String): Flow<Boolean?> = collect.map { it[accountId] }
        override suspend fun setCollect(accountId: String, enabled: Boolean) = collect.update { it + (accountId to enabled) }
        override fun collectFeeCap(accountId: String): Flow<Int?> = feeCaps.map { it[accountId] }
        override suspend fun setCollectFeeCap(accountId: String, percent: Int) = feeCaps.update { it + (accountId to percent) }
        override fun maintenanceNotices(accountId: String): Flow<Boolean?> = notices.map { it[accountId] }
        override suspend fun setMaintenanceNotices(accountId: String, enabled: Boolean) = notices.update { it + (accountId to enabled) }
        override suspend fun clear(accountId: String) {
            autoRotate.update { it - accountId }
            strategies.update { it - accountId }
            collect.update { it - accountId }
            feeCaps.update { it - accountId }
            notices.update { it - accountId }
        }
    }
}
