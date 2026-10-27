package whitewallet.core.data

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

/**
 * App-wide preferences and per-account note policies, in the `settings` DataStore. Developer mode starts as
 * [developerModeByDefault] (on in debug builds) until the user sets it.
 */
internal class DataStorePreferences(
    private val prefs: DataStore<Preferences>,
    private val developerModeByDefault: Boolean = false,
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

    override val amountUnit: Flow<AmountUnit?> = prefs.data.map { p -> p[AMOUNT_UNIT]?.let { name -> AmountUnit.entries.firstOrNull { it.name == name } } }

    override suspend fun setAmountUnit(unit: AmountUnit) {
        prefs.edit { it[AMOUNT_UNIT] = unit.name }
    }

    override val balanceHidden: Flow<Boolean> = prefs.data.map { it[BALANCE_HIDDEN] ?: false }

    override suspend fun setBalanceHidden(hidden: Boolean) {
        prefs.edit { it[BALANCE_HIDDEN] = hidden }
    }

    override val trusted: Flow<Set<MintKey>> = prefs.data.map { p -> p[TRUSTED].orEmpty().mapNotNull(::mintKey).toSet() }
    override val untrusted: Flow<Set<MintKey>> = prefs.data.map { p -> p[UNTRUSTED].orEmpty().mapNotNull(::mintKey).toSet() }

    override suspend fun setTrusted(mint: MintKey, trusted: Boolean) {
        val entry = "${mint.host} ${mint.pubkey}"
        prefs.edit {
            val (add, remove) = if (trusted) TRUSTED to UNTRUSTED else UNTRUSTED to TRUSTED
            it[add] = it[add].orEmpty() + entry
            it[remove] = it[remove].orEmpty() - entry
        }
    }

    override suspend fun forget(mint: MintKey) {
        val entry = "${mint.host} ${mint.pubkey}"
        prefs.edit {
            it[TRUSTED] = it[TRUSTED].orEmpty() - entry
            it[UNTRUSTED] = it[UNTRUSTED].orEmpty() - entry
        }
    }

    /** Stored as "host pubkey" (a host never contains a space). */
    private fun mintKey(entry: String): MintKey? = entry.split(' ').takeIf { it.size == 2 }?.let { MintKey(it[0], it[1]) }

    override val notesSort: Flow<String?> = prefs.data.map { it[NOTES_SORT] }

    override suspend fun setNotesSort(sort: String) {
        prefs.edit { it[NOTES_SORT] = sort }
    }

    override val appLock: Flow<Boolean> = prefs.data.map { it[APP_LOCK] ?: false }

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

    override suspend fun clear(accountId: String) {
        prefs.edit {
            it.remove(autoRotateKey(accountId))
            it.remove(strategyKey(accountId))
            it.remove(collectKey(accountId))
            it.remove(feeCapKey(accountId))
        }
    }

    private companion object {
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val DEVELOPER_UNLOCKED = booleanPreferencesKey("developer_options_unlocked")
        val MAX_ADDRESSES = intPreferencesKey("max_addresses_per_mint")
        val AMOUNT_UNIT = stringPreferencesKey("amount_unit")
        val BALANCE_HIDDEN = booleanPreferencesKey("balance_hidden")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val NOTES_SORT = stringPreferencesKey("notes_sort")
        val TRUSTED = stringSetPreferencesKey("trusted_mints")
        val UNTRUSTED = stringSetPreferencesKey("untrusted_mints")
        fun autoRotateKey(accountId: String) = booleanPreferencesKey("auto_rotate_$accountId")
        fun strategyKey(accountId: String) = stringPreferencesKey("note_strategy_$accountId")
        fun collectKey(accountId: String) = booleanPreferencesKey("collect_at_home_$accountId")
        fun feeCapKey(accountId: String) = intPreferencesKey("collect_fee_cap_$accountId")
    }
}
