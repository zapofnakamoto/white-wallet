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
import whitewallet.core.model.Account
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.NoteState
import whitewallet.core.model.OutputRef
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe

/**
 * An update keeps what a released app stored: a database of an older version, built from its exported schema (core/data/schemas), opens
 * with the app's migrations and still holds its accounts, notes, mints and history.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Creates [name] as Room's schema [version] would have, then runs [fill] on it. */
    private fun oldDatabase(name: String, version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = JSONObject(File("schemas/${WalletDatabase::class.qualifiedName}/$version.json").readText()).getJSONObject("database")
        val file =
            context.getDatabasePath(name).apply {
                parentFile?.mkdirs()
                delete()
            }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.fill()
            db.version = version
        }
    }

    @Test
    fun `a v0_3_0 wallet keeps its accounts, notes, mints, journal and history`() =
        runBlocking<Unit> {
            oldDatabase("v2.db", 2) {
                execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
                execSQL("INSERT INTO notes VALUES ('n1', 'acc', 'mint.example', 'KeyPath', 1, 0, 21000, NULL, 'Outstanding', 2)")
                execSQL("INSERT INTO services VALUES ('mint.example', 'https://mint.example/w', 'pk', 1000, 1000000, 0, 0)")
                execSQL(
                    "INSERT INTO journal VALUES ('m1', 'acc', 'Mint', 'other.example', 'https://other.example/w', '', '', '', 'lnbc1', 'Prepared', NULL, 3, 3)"
                )
                execSQL(
                    "INSERT INTO activity VALUES ('service:m1', 'acc', 'MovedService', 20000, 1000, 'mint.example', 'other.example', NULL, NULL, 0, 'Pending', 'm1', 3)"
                )
                execSQL(
                    "INSERT INTO activity VALUES ('claim:c1', 'acc', 'Claimed', 5000, NULL, 'mint.example', NULL, 1, NULL, 0, 'Done', NULL, 4)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v2.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
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
    fun `a wallet of version 3 keeps its notes and history, and can keep received notes`() =
        runBlocking<Unit> {
            oldDatabase("v3.db", 3) {
                execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
                execSQL("INSERT INTO notes VALUES ('n1', 'acc', 'mint.example', 'KeyPath', 0, 0, 21000, NULL, 'Outstanding', 2)")
                execSQL(
                    "INSERT INTO activity VALUES ('claim:c1', 'acc', 'Claimed', 5000, NULL, 'mint.example', NULL, 1, NULL, 0, 'Done', NULL, 4)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v3.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                assertEquals(listOf(21000L), store.notes("acc").first().map { it.amountMsat })
                assertEquals(listOf("claim:c1"), store.activities("acc").first().map { it.id })
                assertEquals(emptyList(), store.receivedNotes("acc").first())
                // Never checked yet; a check is remembered from now on.
                val n1 = store.notes("acc").first().single()
                assertEquals(null, n1.checkedAtMillis)
                store.upsertNotes(listOf(n1.copy(checkedAtMillis = 7)))
                assertEquals(7L, store.note("n1")?.checkedAtMillis)
                val note =
                    ReceivedNote(
                        "q".repeat(64),
                        "acc",
                        "mint.example",
                        "https://mint.example/w",
                        "cw1example",
                        null,
                        5000,
                        null,
                        1_800_000_000_000,
                        null,
                        ReceivedState.Locked,
                        null,
                        5,
                        5,
                    )
                store.putReceivedNote(note)
                assertEquals(listOf(note), store.receivedNotes(setOf(ReceivedState.Locked)))
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 4 becomes one wallet per account, with its keys, checks and outputs as legacy 2f17578`() =
        runBlocking<Unit> {
            val legacyOutput = "acc:0:3:${"a".repeat(64)}"
            oldDatabase("v4.db", 4) {
                execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
                execSQL("INSERT INTO accounts VALUES ('acc2', 'Savings', 2, 'mint.example')")
                execSQL("INSERT INTO notes VALUES ('n1', 'acc', 'mint.example', 'KeyPath', 0, 2, 21000, NULL, 'Outstanding', 2, 5)")
                execSQL("INSERT INTO counters VALUES ('acc', 'mint.example', 0, 3)")
                execSQL("INSERT INTO address_scan VALUES ('acc', 'mint.example', 4)")
                execSQL("INSERT INTO key_checks VALUES ('acc', 'mint.example', 2, 7, 'Outstanding', 1000, 1, 0, NULL, 9)")
                execSQL("INSERT INTO addresses VALUES ('acc', 'mint.example', 'alice', 3)")
                execSQL(
                    "INSERT INTO journal VALUES ('j1', 'acc', 'Rotate', 'mint.example', 'https://mint.example/w', 'n0', 'k', " +
                        "'$legacyOutput', 'https://mint.example/w?k1=k', 'Prepared', NULL, 3, 3)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v4.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                // Each account is its own wallet, under its own id (where its recovery phrase already is), at account index 0.
                assertEquals(Account("acc", "Main", 1, "mint.example", walletId = "acc", index = 0), store.account("acc"))
                assertEquals("acc2", store.account("acc2")?.walletId)
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })

                val n1 = store.note("n1")!!
                assertEquals(KeyScheme.Legacy, n1.scheme)
                assertEquals(0 to 2L, n1.purpose to n1.index)
                assertNull(n1.share)
                assertEquals(3, store.currentIndex("acc", "mint.example", 0))
                assertEquals(4, store.addressScanFrom("acc", "mint.example"))
                assertEquals(
                    mapOf(KeyId("acc", "mint.example", 2, 7, KeyScheme.Legacy, null) to KeyProbe.Outstanding(1000, true, false, 9)),
                    store.keyChecks("acc").first(),
                )
                assertEquals(listOf(RegisteredAddress("alice", "mint.example", 3, share = null)), store.addresses("acc").first())
                assertEquals(
                    listOf(OutputRef("acc", 0, 3, "a".repeat(64), KeyScheme.Legacy, null)),
                    store.journal(setOf(JournalStatus.Prepared)).single().outputs,
                )

                // The new counters start empty, per mint domain.
                assertNull(store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                assertEquals(0, store.reserveKeyIndex("acc", "mint.example", KeyCounter.Wallet))
                assertEquals(1, store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                store.setShareScanFrom("acc", "mint.example", share = 1, index = 5)
                assertEquals(5, store.shareScanFrom("acc", "mint.example", 1))
                assertEquals(0, store.shareScanFrom("acc", "mint.example", 0))

                // New rows keep their scheme and share.
                val shared = n1.copy(id = "n2", scheme = KeyScheme.Bip32, purpose = 2, index = 0, share = 1)
                store.upsertNotes(listOf(shared))
                assertEquals(shared, store.note("n2"))
                val check = KeyId("acc", "mint.example", 2, 0, KeyScheme.Bip32, 1)
                store.putKeyCheck(check, KeyProbe.Spent(10))
                assertEquals(KeyProbe.Spent(10), store.keyChecks("acc").first()[check])
                val entry = store.journal(setOf(JournalStatus.Prepared)).single()
                val output = OutputRef("acc", 2, 0, "b".repeat(64), KeyScheme.Bip32, 1)
                store.putJournal(entry.copy(id = "j2", outputs = listOf(output, OutputRef("acc", 0, 4, "c".repeat(64), KeyScheme.Bip32))))
                assertEquals(
                    listOf(output, OutputRef("acc", 0, 4, "c".repeat(64), KeyScheme.Bip32)),
                    store.journal(setOf(JournalStatus.Prepared)).single { it.id == "j2" }.outputs,
                )

                // A second account in the first wallet; the wallet goes with its last account.
                store.saveAccount(Account("acc3", "Second", 4, "mint.example", walletId = "acc", index = 1))
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })
                store.deleteAccount("acc")
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })
                assertNull(store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                store.deleteAccount("acc3")
                assertEquals(listOf("acc2"), db.dao().wallets().first().map { it.id })
            } finally {
                db.close()
            }
        }

    @Test
    fun `the address limit set under v0_3_0 is kept under its new key`() =
        runBlocking<Unit> {
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
