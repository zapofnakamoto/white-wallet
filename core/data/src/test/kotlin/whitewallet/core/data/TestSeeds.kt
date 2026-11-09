package whitewallet.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import whitewallet.core.wallet.SeedStore

/** The phrases, kept across restarts as the Keystore keeps them on a phone. */
internal class TestSeeds : SeedStore {
    private val words = mutableMapOf<String, List<String>>()
    private val verified = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun load(accountId: String) = words[accountId]

    override suspend fun save(accountId: String, words: List<String>) {
        this.words[accountId] = words
    }

    override fun backupVerified(accountId: String): Flow<Boolean> = verified.map { accountId in it }

    override suspend fun setBackupVerified(accountId: String) {
        verified.value = verified.value + accountId
    }

    override suspend fun clear(accountId: String) {
        words.remove(accountId)
    }
}
