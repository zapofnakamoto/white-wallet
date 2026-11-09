package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletRecord
import whitewallet.lnurlcash.client.NetworkConditions

/**
 * Persistent wallet state for every wallet and account. Implemented by core:data on Android and in memory for tests. A wallet's rows come
 * before its accounts', and an account's before anything of it: mints (a host's withdraw URL and pinned key) and the home mint are per
 * wallet; everything else is per account.
 */
interface WalletStore {
    /** Oldest first: an account's place here is its mark's colour, so the order must stay stable. */
    val accounts: Flow<List<AccountSummary>>
    val activeAccountId: Flow<String?>

    suspend fun currentAccountId(): String?

    suspend fun setActiveAccount(id: String?)

    /** Every wallet, oldest first. */
    val wallets: Flow<List<WalletRecord>>

    suspend fun wallet(id: String): WalletRecord?

    /** Saves [wallet], or updates it in place: its accounts and everything of theirs stay. */
    suspend fun saveWallet(wallet: WalletRecord)

    suspend fun account(id: String): Account?

    /** Saves [account] in its wallet ([Account.walletId]), which must be saved already; an update keeps everything of the account. */
    suspend fun saveAccount(account: Account)

    /**
     * Removes the account with all of its notes, received notes, addresses, counters, key checks, journal entries and history, and its
     * wallet with the wallet's mints once no account is left in it.
     */
    suspend fun deleteAccount(id: String)

    fun notes(accountId: String): Flow<List<Note>>

    /** The address the account's user made primary (Receive offers it first); null for none. */
    fun primaryAddress(accountId: String): Flow<RegisteredAddress?>

    suspend fun currentPrimaryAddress(accountId: String): RegisteredAddress?

    /** Makes the account's address [username]@[host], which must be saved already, its primary one; null: none is. */
    suspend fun setPrimaryAddress(accountId: String, username: String?, host: String?)

    /** Every Lightning address registered to the account, oldest first. */
    fun addresses(accountId: String): Flow<List<RegisteredAddress>>

    /** Saves [address], or updates it: an update keeps whether it is primary. */
    suspend fun saveAddress(accountId: String, address: RegisteredAddress)

    suspend fun deleteAddress(accountId: String, username: String, host: String)

    suspend fun mint(walletId: String, host: String): Mint?

    /** Saves [mint] for its wallet ([Mint.walletId]). */
    suspend fun saveMint(mint: Mint)

    /** Forgets the wallet's mint at [host] and its pinned key (see [Wallet.removeMint]); notes and history stay. */
    suspend fun removeMint(walletId: String, host: String)

    /** Forgets every mint of the wallet: its recovery phrase is gone from the phone. */
    suspend fun removeMints(walletId: String)

    /** Every mint the wallet has connected to (all its accounts share them), by host. */
    fun mints(walletId: String): Flow<List<Mint>>

    /** A note by `Q`, whichever account holds it. */
    suspend fun note(id: String): Note?

    suspend fun upsertNotes(notes: List<Note>)

    /**
     * Returns the next unused index of the account's LUD-26 [counter] on [domain] (a mint's spend domain: mints sharing a hostname share
     * keys) and durably advances it, atomically.
     */
    suspend fun reserveKeyIndex(accountId: String, domain: String, counter: KeyCounter): Long

    /** The next index [reserveKeyIndex] would return, or null if the counter was never set. */
    suspend fun keyIndexOrNull(accountId: String, domain: String, counter: KeyCounter): Long?

    /** Sets a LUD-26 counter directly (restore, developer tools). */
    suspend fun setKeyIndex(accountId: String, domain: String, counter: KeyCounter, next: Long)

    /** First note index of [share] the next scan of it at [host] starts from. */
    suspend fun shareScanFrom(accountId: String, host: String, share: Long): Long

    suspend fun setShareScanFrom(accountId: String, host: String, share: Long, index: Long)

    /** Legacy 2f17578 (tag: keys): returns the next unused index for ([accountId], [host], [purpose]) and durably advances it. */
    suspend fun reserveIndex(accountId: String, host: String, purpose: Int): Long

    /** The next index [reserveIndex] would return, without reserving it. */
    suspend fun currentIndex(accountId: String, host: String, purpose: Int): Long = currentIndexOrNull(accountId, host, purpose) ?: 0

    /** Like [currentIndex], but null if the counter was never set (the account has not used this mint and purpose yet). */
    suspend fun currentIndexOrNull(accountId: String, host: String, purpose: Int): Long?

    /** Every host the account has a wallet or change counter on (set by its first use, or by a restore). */
    suspend fun counterHosts(accountId: String): List<String>

    /** Sets the counter directly (developer tools and recovery). */
    suspend fun setIndex(accountId: String, host: String, purpose: Int, next: Long)

    /**
     * The account's indexes on [host], updated on every change: each one that is set (the wallet and change counters of [host]'s domain,
     * share scan starts, and the legacy counters and address scan start), by the keys it counts.
     */
    fun indexesFlow(accountId: String, host: String): Flow<Map<KeySet, Long>>

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

    /** The account's received notes (see [ReceivedNote]), newest first. */
    fun receivedNotes(accountId: String): Flow<List<ReceivedNote>>

    /** A received note by `Q`, whichever account kept it. */
    suspend fun receivedNote(id: String): ReceivedNote?

    /** Received notes of every account in one of [states]. */
    suspend fun receivedNotes(states: Set<ReceivedState>): List<ReceivedNote>

    /** Adds [note], or replaces the one with the same `Q`. */
    suspend fun putReceivedNote(note: ReceivedNote)

    suspend fun deleteReceivedNote(id: String)
}

/** Holds each wallet's BIP-39 words, by wallet id. Implemented with Android Keystore encryption on device. */
interface SeedStore {
    suspend fun load(walletId: String): List<String>?

    suspend fun save(walletId: String, words: List<String>)

    /** Whether the user has written this wallet's words down and passed the verification step. */
    fun backupVerified(walletId: String): Flow<Boolean>

    suspend fun setBackupVerified(walletId: String)

    /** Deletes the wallet's words and backup flag. */
    suspend fun clear(walletId: String)
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
     * How many Lightning addresses an account may register per mint while developer mode is on; null until set, then the brand's limit
     * applies (see [AppDefaults.addressLimit]).
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

    /** Ids of the release notes the user has seen ([ReleaseNoteTracker]), or null until the first are recorded. */
    val seenReleaseNotes: Flow<Set<String>?>

    suspend fun setSeenReleaseNotes(ids: Set<String>)
}

/**
 * Note policies. Per account: auto-rotate of notes on Lightning address keys (off by default), the id of the chosen note strategy (null
 * until the user picks one; the brand's default applies, see [NoteStrategies]) and maintenance notices. Per wallet: collecting at its home
 * mint, the fee cap for that, and which mint payments come from first.
 */
interface NotePolicies {
    fun autoRotate(accountId: String): Flow<Boolean>

    suspend fun setAutoRotate(accountId: String, enabled: Boolean)

    fun strategy(accountId: String): Flow<String?>

    suspend fun setStrategy(accountId: String, strategyId: String)

    /** Whether the wallet's notes on other mints are moved to its home mint; null until the user chooses (the brand's default). */
    fun collect(walletId: String): Flow<Boolean?>

    suspend fun setCollect(walletId: String, enabled: Boolean)

    /** The most an automatic move may cost, in percent of what it moves; null until chosen (the brand's default). */
    fun collectFeeCap(walletId: String): Flow<Int?>

    suspend fun setCollectFeeCap(walletId: String, percent: Int)

    /** Whether payments use notes on other mints before the home mint's; off (home mint first) until chosen. */
    fun payFromOtherMintsFirst(walletId: String): Flow<Boolean>

    suspend fun setPayFromOtherMintsFirst(walletId: String, enabled: Boolean)

    /** Whether the user is told what the note policies did by themselves; null until chosen (the brand's default). */
    fun maintenanceNotices(accountId: String): Flow<Boolean?>

    suspend fun setMaintenanceNotices(accountId: String, enabled: Boolean)

    /** Forgets the account's policies. */
    suspend fun clear(accountId: String)

    /** Forgets the wallet's policies. */
    suspend fun clearWallet(walletId: String)

    /** Keeps policies in memory only; the default when no persistent store is given. */
    class InMemory(private val defaultStrategy: String? = null) : NotePolicies {
        private val autoRotate = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val strategies = MutableStateFlow<Map<String, String>>(emptyMap())
        private val collect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val feeCaps = MutableStateFlow<Map<String, Int>>(emptyMap())
        private val otherMintsFirst = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val notices = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        override fun autoRotate(accountId: String): Flow<Boolean> = autoRotate.map { it[accountId] ?: false }

        override suspend fun setAutoRotate(accountId: String, enabled: Boolean) = autoRotate.update { it + (accountId to enabled) }

        override fun strategy(accountId: String): Flow<String?> = strategies.map { it[accountId] ?: defaultStrategy }

        override suspend fun setStrategy(accountId: String, strategyId: String) = strategies.update { it + (accountId to strategyId) }

        override fun collect(walletId: String): Flow<Boolean?> = collect.map { it[walletId] }

        override suspend fun setCollect(walletId: String, enabled: Boolean) = collect.update { it + (walletId to enabled) }

        override fun collectFeeCap(walletId: String): Flow<Int?> = feeCaps.map { it[walletId] }

        override suspend fun setCollectFeeCap(walletId: String, percent: Int) = feeCaps.update { it + (walletId to percent) }

        override fun payFromOtherMintsFirst(walletId: String): Flow<Boolean> = otherMintsFirst.map { it[walletId] ?: false }

        override suspend fun setPayFromOtherMintsFirst(walletId: String, enabled: Boolean) = otherMintsFirst.update {
            it + (walletId to enabled)
        }

        override fun maintenanceNotices(accountId: String): Flow<Boolean?> = notices.map { it[accountId] }

        override suspend fun setMaintenanceNotices(accountId: String, enabled: Boolean) = notices.update { it + (accountId to enabled) }

        override suspend fun clear(accountId: String) {
            autoRotate.update { it - accountId }
            strategies.update { it - accountId }
            notices.update { it - accountId }
        }

        override suspend fun clearWallet(walletId: String) {
            collect.update { it - walletId }
            feeCaps.update { it - walletId }
            otherMintsFirst.update { it - walletId }
        }
    }
}
