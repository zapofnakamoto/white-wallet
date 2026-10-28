package whitewallet.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import whitewallet.core.wallet.RecoveryPhrase
import whitewallet.core.wallet.SeedStore

/** Stores each account's BIP-39 words encrypted with a non-exportable AES-GCM key in the Android Keystore. */
internal class KeystoreSeedStore(private val prefs: DataStore<Preferences>) : SeedStore {
    override suspend fun load(accountId: String): List<String>? {
        val stored = prefs.data.first()[seedKey(accountId)] ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        val plain = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        return try { RecoveryPhrase.fromBytes(plain) } finally { plain.fill(0) }
    }

    override suspend fun save(accountId: String, words: List<String>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = RecoveryPhrase.toBytes(words)
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        prefs.edit { it[seedKey(accountId)] = Base64.encodeToString(encrypted, Base64.NO_WRAP) }
    }

    override fun backupVerified(accountId: String): Flow<Boolean> = prefs.data.map { it[backupKey(accountId)] == true }

    override suspend fun setBackupVerified(accountId: String) {
        prefs.edit { it[backupKey(accountId)] = true }
    }

    override suspend fun clear(accountId: String) {
        prefs.edit {
            it.remove(seedKey(accountId))
            it.remove(backupKey(accountId))
        }
        // The Keystore key is shared by all accounts; drop it once no account needs it.
        if (prefs.data.first().asMap().keys.none { it.name.startsWith(SEED_PREFIX) }) {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wallet-seed"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val SEED_PREFIX = "seed_"

        fun seedKey(accountId: String) = stringPreferencesKey("$SEED_PREFIX$accountId")

        fun backupKey(accountId: String) = booleanPreferencesKey("backup_verified_$accountId")
    }
}
