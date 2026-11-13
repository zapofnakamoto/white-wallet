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
            it.remove(withoutSigningKeyKey(walletId))
        }
    }

    override fun allowsMintsWithoutSigningKey(walletId: String): Flow<Boolean> =
        prefs.data.map { it[withoutSigningKeyKey(walletId)] ?: false }

    override suspend fun setAllowsMintsWithoutSigningKey(walletId: String, allowed: Boolean) {
        prefs.edit { it[withoutSigningKeyKey(walletId)] = allowed }
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

    override fun collect(accountId: String): Flow<Boolean?> = prefs.data.map { it[collectKey(accountId)] }

    override suspend fun setCollect(accountId: String, enabled: Boolean) {
        prefs.edit { it[collectKey(accountId)] = enabled }
    }

    override fun collectFeeCap(accountId: String): Flow<Int?> = prefs.data.map { it[feeCapKey(accountId)] }

    override suspend fun setCollectFeeCap(accountId: String, percent: Int) {
        prefs.edit { it[feeCapKey(accountId)] = percent }
    }

    override fun payFromOtherMintsFirst(accountId: String): Flow<Boolean> = prefs.data.map { it[otherMintsFirstKey(accountId)] ?: false }

    override suspend fun setPayFromOtherMintsFirst(accountId: String, enabled: Boolean) {
        prefs.edit { it[otherMintsFirstKey(accountId)] = enabled }
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
            it.remove(collectKey(accountId))
            it.remove(feeCapKey(accountId))
            it.remove(otherMintsFirstKey(accountId))
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

        private fun collectKey(accountId: String) = booleanPreferencesKey("collect_at_home_$accountId")

        private fun feeCapKey(accountId: String) = intPreferencesKey("collect_fee_cap_$accountId")

        private fun otherMintsFirstKey(accountId: String) = booleanPreferencesKey("pay_from_other_mints_first_$accountId")

        private fun noticesKey(accountId: String) = booleanPreferencesKey("maintenance_notices_$accountId")

        internal fun trustedKey(walletId: String) = stringSetPreferencesKey("trusted_mints_$walletId")

        internal fun untrustedKey(walletId: String) = stringSetPreferencesKey("untrusted_mints_$walletId")

        private fun withoutSigningKeyKey(walletId: String) = booleanPreferencesKey("mints_without_signing_key_$walletId")

        /**
         * Keys changed since v0.3.0: an installed app keeps the value under the new name. Trust choices were the device's until each wallet
         * ([walletIds], every one on the phone) had its own; each wallet starts with them.
         */
        fun migrations(walletIds: suspend () -> List<String>): List<DataMigration<Preferences>> =
            listOf(
                RenamedKey(intPreferencesKey("max_addresses_per_service"), MAX_ADDRESSES),
                PerWallet(stringSetPreferencesKey("trusted_mints"), walletIds, ::trustedKey),
                PerWallet(stringSetPreferencesKey("untrusted_mints"), walletIds, ::untrustedKey),
            )
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
