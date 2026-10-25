package whitewallet.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import whitewallet.core.model.Account

/** What only a device can check: the Keystore-encrypted seed and database passphrase, and SQLCipher itself. */
@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun seedsRoundTripThroughTheKeystore() = runBlocking<Unit> {
        val file = File(context.filesDir, "test-secrets-${System.nanoTime()}.preferences_pb")
        val store = KeystoreSeedStore(PreferenceDataStoreFactory.create { file })
        val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        store.save("a", words)
        assertEquals(words, store.load("a"))
        assertFalse("never stored in clear", file.readText(Charsets.ISO_8859_1).contains("sausage"))
        store.clear("a")
        assertNull(store.load("a"))
        file.delete()
    }

    @Test
    fun theDatabaseIsEncryptedWithAStablePassphrase() = runBlocking<Unit> {
        val name = "test-wallet-${System.nanoTime()}.db"
        val passphrase = DatabaseKey.passphrase(context, name)
        assertArrayEquals(passphrase, DatabaseKey.passphrase(context, name))

        System.loadLibrary("sqlcipher")
        val db = Room.databaseBuilder(context, WalletDatabase::class.java, name).openHelperFactory(SupportOpenHelperFactory(passphrase)).build()
        RoomWalletStore(db).saveAccount(Account("a", "Main", 1, "mint.example"))
        db.close()

        val bytes = context.getDatabasePath(name).readBytes()
        assertFalse("no SQLite header", String(bytes, 0, 16, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse("no clear text", String(bytes, Charsets.ISO_8859_1).contains("Main"))
        context.deleteDatabase(name)
    }
}
