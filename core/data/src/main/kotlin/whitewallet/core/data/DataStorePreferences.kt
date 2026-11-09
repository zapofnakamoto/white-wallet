package whitewallet.core.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.MintKey
import whitewallet.core.wallet.MintTrustStore
import whitewallet.core.wallet.NotePolicies
import whitewallet.lnurlcash.client.NetworkConditions

/**
 * App-wide preferences and per-account note policies, in the `settings` DataStore. Developer mode starts as [developerModeByDefault] (on in
 * debug builds) and the app lock as [appLockByDefault] (brand `security.appLock`) until the user sets them.
 */
internal class DataStorePreferences(
    private val prefs: DataStore<Preferences>,
    private val developerModeByDefault: Boolean = false,
    private val appLockByDefault: Boolean = false,
) : AppPreferences, NotePolicies, MintTrustStore {
    override val developerMode: Flow<Boolean> = prefs.data.map { it[DEVELOPER_MODE] ?: developerModeByDefault }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        prefs.edit { it[DEVELOPER_MODE] = enabled }
    }

    override val developerOptionsUnlocked: Flow<Boolean> = prefs.data.map { it[DEVELOPER_UNLOCKED] ?: false }

    override suspend fun setDeveloperOptionsUnlocked() {
        prefs.edit { it[DEVELOPER_UNLOCKED] = true }
    }

    override val maxAddressesPerMint: Flow<Int?> = prefs.data.map { it[MAX_ADDRESSES] }

    override suspend fun setMaxAddressesPerMint(max: Int) {
        prefs.edit { it[MAX_ADDRESSES] = max.coerceAtLeast(1) }
    }

    override val networkProfile: Flow<NetworkConditions.Profile> =
        prefs.data.map { p ->
            NetworkConditions.Profile.entries.firstOrNull { it.name == p[NETWORK_PROFILE] } ?: NetworkConditions.Profile.Normal
        }

    override suspend fun setNetworkProfile(profile: NetworkConditions.Profile) {
        prefs.edit { it[NETWORK_PROFILE] = profile.name }
    }

    override val amountUnit: Flow<AmountUnit?> =
        prefs.data.map { p -> p[AMOUNT_UNIT]?.let { name -> AmountUnit.entries.firstOrNull { it.name == name } } }

    override suspend fun setAmountUnit(unit: AmountUnit) {
        prefs.edit { it[AMOUNT_UNIT] = unit.name }
    }

    override val balanceHidden: Flow<Boolean> = prefs.data.map { it[BALANCE_HIDDEN] ?: false }

    override suspend fun setBalanceHidden(hidden: Boolean) {
        prefs.edit { it[BALANCE_HIDDEN] = hidden }
    }

    override fun trusted(walletId: String): Flow<Set<MintKey>> =
        prefs.data.map { p -> p[trustedKey(walletId)].orEmpty().mapNotNull(::mintKey).toSet() }

    override fun untrusted(walletId: String): Flow<Set<MintKey>> =
        prefs.data.map { p -> p[untrustedKey(walletId)].orEmpty().mapNotNull(::mintKey).toSet() }

    override suspend fun setTrusted(walletId: String, mint: MintKey, trusted: Boolean) {
        val entry = "${mint.host} ${mint.pubkey}"
        prefs.edit {
            val (add, remove) =
                if (trusted) trustedKey(walletId) to untrustedKey(walletId) else untrustedKey(walletId) to trustedKey(walletId)
            it[add] = it[add].orEmpty() + entry
            it[remove] = it[remove].orEmpty() - entry
        }
    }

    override suspend fun forget(walletId: String, mint: MintKey) {
        val entry = "${mint.host} ${mint.pubkey}"
        prefs.edit {
            it[trustedKey(walletId)] = it[trustedKey(walletId)].orEmpty() - entry
            it[untrustedKey(walletId)] = it[untrustedKey(walletId)].orEmpty() - entry
        }
    }

    override suspend fun forgetWallet(walletId: String) {
        prefs.edit {
            it.remove(trustedKey(walletId))
            it.remove(untrustedKey(walletId))
        }
    }

    /** Stored as "host pubkey" (a host never contains a space). */
    private fun mintKey(entry: String): MintKey? = entry.split(' ').takeIf { it.size == 2 }?.let { MintKey(it[0], it[1]) }

    override val notesSort: Flow<String?> = prefs.data.map { it[NOTES_SORT] }

    override suspend fun setNotesSort(sort: String) {
        prefs.edit { it[NOTES_SORT] = sort }
    }

    override val seenReleaseNotes: Flow<Set<String>?> = prefs.data.map { it[SEEN_RELEASE_NOTES] }

    override suspend fun setSeenReleaseNotes(ids: Set<String>) {
        prefs.edit { it[SEEN_RELEASE_NOTES] = ids }
    }

    override val appLock: Flow<Boolean> = prefs.data.map { it[APP_LOCK] ?: appLockByDefault }

    override suspend fun setAppLock(enabled: Boolean) {
        prefs.edit { it[APP_LOCK] = enabled }
    }

    override fun autoRotate(accountId: String): Flow<Boolean> = prefs.data.map { it[autoRotateKey(accountId)] ?: false }

    override suspend fun setAutoRotate(accountId: String, enabled: Boolean) {
        prefs.edit { it[autoRotateKey(accountId)] = enabled }
    }

    override fun strategy(accountId: String): Flow<String?> = prefs.data.map { it[strategyKey(accountId)] }

    override suspend fun setStrategy(accountId: String, strategyId: String) {
        prefs.edit { it[strategyKey(accountId)] = strategyId }
    }

    override fun collect(walletId: String): Flow<Boolean?> = prefs.data.map { it[collectKey(walletId)] }

    override suspend fun setCollect(walletId: String, enabled: Boolean) {
        prefs.edit { it[collectKey(walletId)] = enabled }
    }

    override fun collectFeeCap(walletId: String): Flow<Int?> = prefs.data.map { it[feeCapKey(walletId)] }

    override suspend fun setCollectFeeCap(walletId: String, percent: Int) {
        prefs.edit { it[feeCapKey(walletId)] = percent }
    }

    override fun payFromOtherMintsFirst(walletId: String): Flow<Boolean> = prefs.data.map { it[otherMintsFirstKey(walletId)] ?: false }

    override suspend fun setPayFromOtherMintsFirst(walletId: String, enabled: Boolean) {
        prefs.edit { it[otherMintsFirstKey(walletId)] = enabled }
    }

    override fun maintenanceNotices(accountId: String): Flow<Boolean?> = prefs.data.map { it[noticesKey(accountId)] }

    override suspend fun setMaintenanceNotices(accountId: String, enabled: Boolean) {
        prefs.edit { it[noticesKey(accountId)] = enabled }
    }

    override suspend fun clear(accountId: String) {
        prefs.edit {
            it.remove(autoRotateKey(accountId))
            it.remove(strategyKey(accountId))
            it.remove(noticesKey(accountId))
        }
    }

    override suspend fun clearWallet(walletId: String) {
        prefs.edit {
            it.remove(collectKey(walletId))
            it.remove(feeCapKey(walletId))
            it.remove(otherMintsFirstKey(walletId))
        }
    }

    internal companion object {
        private val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val DEVELOPER_UNLOCKED = booleanPreferencesKey("developer_options_unlocked")
        private val MAX_ADDRESSES = intPreferencesKey("max_addresses_per_mint")
        private val NETWORK_PROFILE = stringPreferencesKey("network_profile")
        private val AMOUNT_UNIT = stringPreferencesKey("amount_unit")
        private val BALANCE_HIDDEN = booleanPreferencesKey("balance_hidden")
        private val APP_LOCK = booleanPreferencesKey("app_lock")
        private val NOTES_SORT = stringPreferencesKey("notes_sort")
        private val SEEN_RELEASE_NOTES = stringSetPreferencesKey("seen_release_notes")

        private fun autoRotateKey(accountId: String) = booleanPreferencesKey("auto_rotate_$accountId")

        private fun strategyKey(accountId: String) = stringPreferencesKey("note_strategy_$accountId")

        internal fun collectKey(walletId: String) = booleanPreferencesKey("wallet_collect_at_home_$walletId")

        internal fun feeCapKey(walletId: String) = intPreferencesKey("wallet_collect_fee_cap_$walletId")

        private fun otherMintsFirstKey(walletId: String) = booleanPreferencesKey("wallet_pay_from_other_mints_first_$walletId")

        private fun noticesKey(accountId: String) = booleanPreferencesKey("maintenance_notices_$accountId")

        internal fun trustedKey(walletId: String) = stringSetPreferencesKey("trusted_mints_$walletId")

        internal fun untrustedKey(walletId: String) = stringSetPreferencesKey("untrusted_mints_$walletId")

        /**
         * Keys changed since v0.3.0: an installed app keeps the value under the new name. Trust choices were the device's until each wallet
         * ([wallets], every one on the phone) had its own; each wallet starts with them. Collecting and the home mint were each account's
         * until they were its wallet's (see [WalletHomes]); [homesMigrated] runs once that is done.
         */
        fun migrations(
            wallets: suspend () -> List<MigratingWallet>,
            homesMigrated: suspend () -> Unit = {},
        ): List<DataMigration<Preferences>> =
            listOf(
                RenamedKey(intPreferencesKey("max_addresses_per_service"), MAX_ADDRESSES),
                PerWallet(stringSetPreferencesKey("trusted_mints"), { wallets().map { it.id } }, ::trustedKey),
                PerWallet(stringSetPreferencesKey("untrusted_mints"), { wallets().map { it.id } }, ::untrustedKey),
                WalletHomes(wallets, homesMigrated),
            )
    }
}

/**
 * A wallet as the settings migrations see it: its accounts' ids, lowest index first, and the homes its accounts had besides the one the
 * wallet kept ([otherHomes], host to the key it pinned there, if any).
 */
internal data class MigratingWallet(
    val id: String,
    val accountIds: List<String>,
    val otherHomes: List<Pair<String, String?>> = emptyList(),
)

/**
 * Collecting at the home mint, once each account's, becomes its wallet's: off if any account had it off, or if its accounts had different
 * homes (collecting at one home would move the others' money); the fee cap of its first account. The accounts' keys go. A home an account
 * had besides the wallet's stays trusted with the key it pinned (a home was trusted by default), unless the user untrusted it.
 */
private class WalletHomes(private val wallets: suspend () -> List<MigratingWallet>, private val done: suspend () -> Unit) :
    DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData.asMap().keys.any { it.name.startsWith(OLD_COLLECT) || it.name.startsWith(OLD_FEE_CAP) } ||
            wallets().any { it.otherHomes.isNotEmpty() }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val wallets = wallets()
        return currentData
            .toMutablePreferences()
            .apply {
                for (wallet in wallets) {
                    val collectKey = DataStorePreferences.collectKey(wallet.id)
                    val collect = wallet.accountIds.mapNotNull { this[booleanPreferencesKey(OLD_COLLECT + it)] }
                    when {
                        collectKey in this -> Unit
                        wallet.otherHomes.isNotEmpty() || false in collect -> this[collectKey] = false
                        true in collect -> this[collectKey] = true
                    }
                    val feeCapKey = DataStorePreferences.feeCapKey(wallet.id)
                    val feeCap = wallet.accountIds.firstOrNull()?.let { this[intPreferencesKey(OLD_FEE_CAP + it)] }
                    if (feeCap != null && feeCapKey !in this) this[feeCapKey] = feeCap
                    val trusted = DataStorePreferences.trustedKey(wallet.id)
                    val untrusted = this[DataStorePreferences.untrustedKey(wallet.id)].orEmpty()
                    val keep = wallet.otherHomes.mapNotNull { (host, key) -> key?.let { "$host $it" } }.filter { it !in untrusted }
                    if (keep.isNotEmpty()) this[trusted] = this[trusted].orEmpty() + keep
                }
                asMap().keys.filter { it.name.startsWith(OLD_COLLECT) || it.name.startsWith(OLD_FEE_CAP) }.forEach { remove(it) }
            }
            .toPreferences()
    }

    override suspend fun cleanUp() = done()

    private companion object {
        const val OLD_COLLECT = "collect_at_home_"
        const val OLD_FEE_CAP = "collect_fee_cap_"
    }
}

/** Gives every wallet of [walletIds] the device-wide set under [old] once, joined with its own ([new]). */
private class PerWallet(
    private val old: Preferences.Key<Set<String>>,
    private val walletIds: suspend () -> List<String>,
    private val new: (String) -> Preferences.Key<Set<String>>,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = old in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val wallets = walletIds()
        return currentData
            .toMutablePreferences()
            .apply {
                val value = remove(old).orEmpty()
                wallets.forEach { this[new(it)] = this[new(it)].orEmpty() + value }
            }
            .toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/** Moves a value from [old] to [new] once; a value already under [new] wins. */
private class RenamedKey<T>(private val old: Preferences.Key<T>, private val new: Preferences.Key<T>) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = old in currentData

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData
            .toMutablePreferences()
            .apply {
                val value = remove(old)
                if (new !in this && value != null) this[new] = value
            }
            .toPreferences()

    override suspend fun cleanUp() = Unit
}
