package whitewallet.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.NoteState

/**
 * An update keeps what a released app stored: a database of an older version, built from its exported schema
 * (core/data/schemas), opens with the app's migrations and still holds its accounts, notes, mints and history.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Creates [name] as Room's schema [version] would have, then runs [fill] on it. */
    private fun oldDatabase(name: String, version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = JSONObject(File("schemas/${WalletDatabase::class.qualifiedName}/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.fill()
            db.version = version
        }
    }

    @Test
    fun `a v0_3_0 wallet keeps its accounts, notes, mints, journal and history`() = runBlocking<Unit> {
        oldDatabase("v2.db", 2) {
            execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
            execSQL("INSERT INTO notes VALUES ('n1', 'acc', 'mint.example', 'KeyPath', 1, 0, 21000, NULL, 'Outstanding', 2)")
            execSQL("INSERT INTO services VALUES ('mint.example', 'https://mint.example/w', 'pk', 1000, 1000000, 0, 0)")
            execSQL("INSERT INTO journal VALUES ('m1', 'acc', 'Mint', 'other.example', 'https://other.example/w', '', '', '', 'lnbc1', 'Prepared', NULL, 3, 3)")
            execSQL("INSERT INTO activity VALUES ('service:m1', 'acc', 'MovedService', 20000, 1000, 'mint.example', 'other.example', NULL, NULL, 0, 'Pending', 'm1', 3)")
            execSQL("INSERT INTO activity VALUES ('claim:c1', 'acc', 'Claimed', 5000, NULL, 'mint.example', NULL, 1, NULL, 0, 'Done', NULL, 4)")
        }
        val db = Room.databaseBuilder(context, WalletDatabase::class.java, "v2.db").addMigrations(*WalletDatabase.MIGRATIONS)
            .allowMainThreadQueries().build()
        try {
            val store = RoomWalletStore(db)
            assertEquals("mint.example", store.account("acc")?.host)
            assertEquals(listOf(21000L to NoteState.Outstanding), store.notes("acc").first().map { it.amountMsat to it.state })
            assertEquals("pk", store.mint("mint.example")?.mintPubkey)
            assertEquals(listOf("m1"), store.journal(setOf(JournalStatus.Prepared)).map { it.id })
            assertEquals(
                listOf("claim:c1" to ActivityKind.Claimed, "mint:m1" to ActivityKind.MovedMint),
                store.activities("acc").first().map { it.id to it.kind }.sortedBy { it.first },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the address limit set under v0_3_0 is kept under its new key`() = runBlocking<Unit> {
        val file = File(context.filesDir, "migrate.preferences_pb").apply { delete() }
        val old = intPreferencesKey("max_addresses_per_service")
        val before = SupervisorJob()
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + before)) { file }.edit { it[old] = 3 }
        before.cancelAndJoin()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(migrations = DataStorePreferences.MIGRATIONS, scope = scope) { file }
            assertEquals(3, DataStorePreferences(store).maxAddressesPerMint.first())
            assertNull(store.data.first()[old])
        } finally {
            scope.cancel()
        }
    }
}
