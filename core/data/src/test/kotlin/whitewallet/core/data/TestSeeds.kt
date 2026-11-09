package whitewallet.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import whitewallet.core.wallet.SeedStore

/** The phrases, kept across restarts as the Keystore keeps them on a phone. */
internal class TestSeeds : SeedStore {
    private val words = mutableMapOf<String, List<String>>()
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
    }
}
