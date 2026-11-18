package whitewallet.core.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.MintScan
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.UnclaimedReason
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.MintKey
import whitewallet.lnurlcash.RevealsNoteSecret

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
    fun `a v0_3_0 wallet keeps its accounts, mints, journal and history, and its notes on the older keys go`() =
        runBlocking<Unit> {
            oldDatabase("v2.db", 2) {
                execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
                execSQL("INSERT INTO accounts VALUES ('acc2', 'Work', 2, 'other.example')")
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
                // Each account of then is a wallet of its own, its home the account's.
                assertEquals("mint.example", store.account("acc")?.homeHost)
                assertEquals("other.example", store.account("acc2")?.homeHost)
                // Its notes were on the older keys, no longer supported (version 9): version 0.9.0 still reaches them.
                assertEquals(emptyList(), store.notes("acc").first())
                assertEquals("pk", store.mint("acc", "mint.example")?.mintPubkey)
                // A receive with no output has no note to wait for (version 9): it is settled as failed, with its row.
                assertEquals(listOf("m1"), store.journal(setOf(JournalStatus.Rejected)).map { it.id })
                assertEquals(
                    listOf(
                        Triple("claim:c1", ActivityKind.Claimed, ActivityStatus.Done),
                        Triple("mint:m1", ActivityKind.MovedMint, ActivityStatus.Failed),
                    ),
                    store.activities("acc").first().map { Triple(it.id, it.kind, it.status) }.sortedBy { it.first },
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 3 keeps its history, and can keep received notes`() =
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
                // Its note was on the older keys (version 9).
                assertEquals(emptyList(), store.notes("acc").first())
                assertEquals(listOf("claim:c1"), store.activities("acc").first().map { it.id })
                assertEquals(emptyList(), store.receivedNotes("acc").first())
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
    fun `a wallet of version 4 becomes one wallet per account, and what it had on the older keys is gone`() =
        runBlocking<Unit> {
            oldDatabase("v4.db", 4) {
                execSQL("INSERT INTO accounts VALUES ('acc', 'Main', 1, 'mint.example')")
                execSQL("INSERT INTO accounts VALUES ('acc2', 'Savings', 2, 'mint.example')")
                execSQL("INSERT INTO notes VALUES ('n1', 'acc', 'mint.example', 'KeyPath', 0, 2, 21000, NULL, 'Outstanding', 2, 5)")
                execSQL("INSERT INTO counters VALUES ('acc', 'mint.example', 0, 3)")
                execSQL("INSERT INTO mints VALUES ('mint.example', 'https://mint.example/w', 'pk', 1000, 1000000, 0, 0)")
                execSQL("INSERT INTO mints VALUES ('other.example', 'https://other.example/w', 'pk2', 1000, 1000000, 0, 0)")
                execSQL("INSERT INTO address_scan VALUES ('acc', 'mint.example', 4)")
                execSQL("INSERT INTO key_checks VALUES ('acc', 'mint.example', 2, 7, 'Outstanding', 1000, 1, 0, NULL, 9)")
                execSQL("INSERT INTO addresses VALUES ('acc', 'mint.example', 'alice', 3)")
                execSQL(
                    "INSERT INTO journal VALUES ('j1', 'acc', 'Rotate', 'mint.example', 'https://mint.example/w', 'n0', 'k', " +
                        "'acc:0:3:${"a".repeat(64)}', 'https://mint.example/w?k1=k', 'Prepared', NULL, 3, 3)"
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
                assertEquals(Account("acc", "Main", 1, walletId = "acc", index = 0, homeHost = "mint.example"), store.account("acc"))
                assertEquals("acc2", store.account("acc2")?.walletId)
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })
                // The mints were the device's: each wallet has them now, with the key pinned for them.
                assertEquals(listOf("pk", "pk2"), store.mints("acc").first().map { it.mintPubkey })
                assertEquals(listOf("pk", "pk2"), store.mints("acc2").first().map { it.mintPubkey })
                // Only the home mint was ever looked through for the wallet's keys; a mint added later never was, so the app offers to.
                assertEquals(
                    listOf(MintScan.Finished, MintScan.Never),
                    store.mints("acc").first().map { it.scan },
                )
                assertEquals(0L to 0L, store.mint("acc", "mint.example")?.let { it.scanStartedAtMillis to it.scanFinishedAtMillis })

                // Everything on the older keys went with their support (version 9): the note, its check, the address and the operation's
                // output. The operation stays, so its inputs still settle.
                assertNull(store.note("n1"))
                assertEquals(emptyMap(), store.keyChecks("acc").first())
                assertEquals(emptyList(), store.addresses("acc").first())
                assertEquals(
                    listOf("j1" to emptyList<OutputRef>()),
                    store.journal(JournalStatus.entries.toSet()).map { it.id to it.outputs },
                )

                // The new counters start empty, per mint domain.
                assertNull(store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                assertEquals(0, store.reserveKeyIndex("acc", "mint.example", KeyCounter.Wallet))
                assertEquals(1, store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                store.setShareScanFrom("acc", "mint.example", share = 1, index = 5)
                assertEquals(5, store.shareScanFrom("acc", "mint.example", 1))
                assertEquals(0, store.shareScanFrom("acc", "mint.example", 0))

                // New rows keep their share.
                val shared = Note("n2", "acc", "mint.example", NoteKind.KeyPath, 2, 0, 1000, null, NoteState.Outstanding, 6, share = 1)
                store.upsertNotes(listOf(shared))
                assertEquals(shared, store.note("n2"))
                val check = KeyId("acc", "mint.example", KeySet.Share(1), 0)
                store.putKeyCheck(check, KeyProbe.Spent(10))
                assertEquals(KeyProbe.Spent(10), store.keyChecks("acc").first()[check])
                val outputs = listOf(OutputRef("acc", 2, 0, "b".repeat(64), 1), OutputRef("acc", 0, 4, "c".repeat(64)))
                store.putJournal(
                    JournalEntry(
                        "j2",
                        "acc",
                        OperationType.Rotate,
                        "mint.example",
                        "https://mint.example/w",
                        listOf("n2"),
                        listOf("k"),
                        outputs,
                        "https://mint.example/w?k1=k",
                        JournalStatus.Prepared,
                        null,
                        7,
                        7,
                    )
                )
                assertEquals(outputs, store.journal(setOf(JournalStatus.Prepared)).single { it.id == "j2" }.outputs)

                // A second account in the first wallet; the wallet goes with its last account.
                store.saveAccount(Account("acc3", "Second", 4, walletId = "acc", index = 1))
                assertEquals("Second", store.accounts.first().single { it.account.id == "acc3" }.account.name)
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })
                store.deleteAccount("acc")
                assertEquals(listOf("acc", "acc2"), db.dao().wallets().first().map { it.id })
                assertNull(store.keyIndexOrNull("acc", "mint.example", KeyCounter.Wallet))
                assertEquals(listOf("mint.example", "other.example"), store.mints("acc").first().map { it.host })
                store.deleteAccount("acc3")
                assertEquals(listOf("acc2"), db.dao().wallets().first().map { it.id })
                // A wallet's mints go with it; another wallet's stay.
                assertEquals(emptyList(), store.mints("acc").first())
                assertEquals(listOf("mint.example", "other.example"), store.mints("acc2").first().map { it.host })
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 6 keeps every note, each account keeps its home, and every row points at its account`() =
        runBlocking<Unit> {
            val q = { c: Char -> c.toString().repeat(64) }
            oldDatabase("v6.db", 6) {
                // Wallet w1: accounts 0 and 1 with different homes, and a duplicate of account 1 (an old scan bug), newer.
                execSQL("INSERT INTO wallets VALUES ('w1', 'Main', 1)")
                execSQL("INSERT INTO accounts VALUES ('a0', 'Main', 1, 'one.example', 'w1', 0)")
                execSQL("INSERT INTO accounts VALUES ('a1', 'Savings', 2, 'two.example', 'w1', 1)")
                execSQL("INSERT INTO accounts VALUES ('dup', 'Savings 2', 3, 'one.example', 'w1', 1)")
                // A wallet from before v5 (its id is its account's) whose wallet row is missing.
                execSQL("INSERT INTO accounts VALUES ('old', 'Old', 4, 'one.example', 'old', 0)")
                execSQL("INSERT INTO mints VALUES ('w1', 'one.example', 'https://one.example/w', 'pk1', 1, 2, 0, 0)")
                execSQL("INSERT INTO mints VALUES ('w1', 'two.example', 'https://two.example/w', 'pk2', 1, 2, 0, 0)")
                execSQL("INSERT INTO mints VALUES ('ghost', 'one.example', 'https://one.example/w', 'pk1', 1, 2, 0, 0)")
                for ((id, account) in listOf('a' to "a0", 'b' to "a1", 'c' to "dup", 'd' to "gone")) {
                    execSQL(
                        "INSERT INTO notes VALUES ('${q(id)}', '$account', 'one.example', 'KeyPath', 0, 1, 1000, NULL, 'Outstanding', 1, NULL, 1, NULL)"
                    )
                }
                execSQL(
                    "INSERT INTO received_notes VALUES ('${q('r')}', 'a1', 'one.example', 'https://one.example/w', 'k1', NULL, 500, NULL, " +
                        "NULL, NULL, 'Waiting', NULL, 1, 1, NULL)"
                )
                execSQL(
                    "INSERT INTO received_notes VALUES ('${q('s')}', 'gone', 'one.example', 'https://one.example/w', 'k1', NULL, 700, NULL, " +
                        "NULL, NULL, 'Waiting', NULL, 1, 1, NULL)"
                )
                // In flight from account 0 to the duplicate and to account 1.
                execSQL(
                    "INSERT INTO journal VALUES ('j1', 'a0', 'Split', 'one.example', 'https://one.example/w', '${q('a')}', 'k', " +
                        "'dup:1:0::5:${q('e')},a1:1:0::6:${q('f')}', 'https://one.example/w?k1=k', 'Unknown', NULL, 3, 3)"
                )
                execSQL("INSERT INTO key_counters VALUES ('a1', 'one.example', 'Wallet', 4)")
                execSQL("INSERT INTO key_counters VALUES ('dup', 'one.example', 'Wallet', 9)")
                execSQL("INSERT INTO key_counters VALUES ('dup', 'one.example', 'Shares', 2)")
                execSQL("INSERT INTO share_scan VALUES ('a1', 'one.example', 0, 8)")
                execSQL("INSERT INTO share_scan VALUES ('dup', 'one.example', 0, 3)")
                execSQL("INSERT INTO key_counters VALUES ('gone', 'one.example', 'Wallet', 1)")
                // Addresses on the older keys (no share) and the primary one as its own row; the old wallet's isn't among its addresses.
                execSQL("INSERT INTO addresses VALUES ('a0', 'one.example', 'alice', 5, NULL, NULL)")
                execSQL("INSERT INTO addresses VALUES ('a0', 'two.example', 'alice', 6, 0, NULL)")
                execSQL("INSERT INTO addresses VALUES ('dup', 'one.example', 'sam', 7, 1, NULL)")
                execSQL("INSERT INTO profile VALUES ('a0', 'alice', 'one.example')")
                execSQL("INSERT INTO profile VALUES ('old', 'bob', 'one.example')")
                execSQL("INSERT INTO app_state VALUES (0, 'dup')")
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v6.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                // A wallet's name was its first account's, never shown: it has none now, and shows its number.
                assertEquals(listOf<String?>(null, null), db.dao().wallets().first().map { it.name })
                // Each account keeps its own home, also where the accounts of one wallet had different ones.
                assertEquals(
                    listOf("a0" to "one.example", "a1" to "two.example", "old" to "one.example"),
                    store.accounts.first().map { it.account.id to it.account.homeHost },
                )

                // The duplicate is merged into the older account 1: its note, its counters (the highest), its scans (the lowest).
                assertEquals(listOf("a0", "a1", "old"), store.accounts.first().map { it.account.id })
                assertEquals(listOf(q('b'), q('c')), store.notes("a1").first().map { it.id }.sorted())
                assertEquals(9, store.keyIndexOrNull("a1", "one.example", KeyCounter.Wallet))
                assertEquals(2, store.keyIndexOrNull("a1", "one.example", KeyCounter.Shares))
                assertEquals(3, store.shareScanFrom("a1", "one.example", 0))
                assertEquals(listOf("sam"), store.addresses("a1").first().map { it.username })
                assertEquals("a1", store.currentAccountId())
                // The operation in flight now names account 1 for both outputs, and settles there.
                assertEquals(listOf("a1", "a1"), store.journal(setOf(JournalStatus.Unknown)).single().outputs.map { it.accountId })

                // The primary address is a flag (version 7). Addresses on no share were on the older keys and went with them (version 9):
                // the account's oldest one left is its primary one.
                assertEquals(RegisteredAddress("alice", "two.example", 6, share = 0), store.currentPrimaryAddress("a0"))
                assertEquals(listOf("alice@two.example"), store.addresses("a0").first().map { it.address })
                assertNull(store.currentPrimaryAddress("old"))

                // What belonged to no account: money and operations kept aside, derived rows and a missing wallet's mints gone.
                assertNull(store.note(q('d')))
                assertNull(store.receivedNote(q('s')))
                val sql = db.openHelper.readableDatabase
                sql.query("SELECT id FROM orphan_notes").use {
                    assertEquals(true, it.moveToFirst())
                    assertEquals(q('d'), it.getString(0))
                }
                sql.query("SELECT id FROM orphan_received_notes").use { assertEquals(true, it.moveToFirst()) }
                assertNull(store.keyIndexOrNull("gone", "one.example", KeyCounter.Wallet))
                assertEquals(emptyList(), store.mints("ghost").first())
                assertEquals(listOf("one.example", "two.example"), store.mints("w1").first().map { it.host })

                // From now on the database keeps it that way: no row of an account that doesn't exist.
                assertFailsWith<SQLiteConstraintException> { store.setKeyIndex("gone", "one.example", KeyCounter.Wallet, 1) }
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 7 keeps its received notes, and those its mint kept refusing are archived as refused`() =
        runBlocking<Unit> {
            val q = { c: Char -> c.toString().repeat(64) }
            oldDatabase("v7.db", 7) {
                execSQL("INSERT INTO wallets VALUES ('w1', NULL, 1, NULL, NULL)")
                execSQL("INSERT INTO accounts VALUES ('a0', 'Main', 1, 'w1', 0, 'one.example')")
                execSQL(
                    "INSERT INTO received_notes VALUES ('${q('r')}', 'a0', 'one.example', 'https://one.example/w', 'k1', NULL, 500, NULL, " +
                        "NULL, NULL, 'Refused', NULL, 1, 2, 1)"
                )
                execSQL(
                    "INSERT INTO received_notes VALUES ('${q('w')}', 'a0', 'one.example', 'https://one.example/w', 'k2', NULL, 700, NULL, " +
                        "NULL, NULL, 'Waiting', NULL, 1, 2, 1)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v7.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                val refused = assertNotNull(store.receivedNote(q('r')))
                assertEquals(ReceivedState.Archived, refused.state)
                assertEquals(UnclaimedReason.Refused, refused.unclaimedReason)
                assertEquals(1, refused.failingSinceMillis)
                @OptIn(RevealsNoteSecret::class) val k1 = refused.k1
                assertEquals("k1", k1, "its spend is kept")
                val waiting = assertNotNull(store.receivedNote(q('w')))
                assertEquals(ReceivedState.Waiting, waiting.state)
                assertNull(waiting.unclaimedReason)
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 8 loses what is on the older keys, and keeps the rest`() =
        runBlocking<Unit> {
            val q = { c: Char -> c.toString().repeat(64) }
            oldDatabase("v8.db", 8) {
                execSQL("INSERT INTO wallets VALUES ('w1', NULL, 1, NULL, NULL)")
                execSQL("INSERT INTO accounts VALUES ('a0', 'Main', 1, 'w1', 0, 'one.example')")
                // Notes: one on the older keys (scheme 0), one on the account's keys (1), and a bearer note, which has no keys.
                execSQL(
                    "INSERT INTO notes VALUES ('${q('o')}', 'a0', 'one.example', 'KeyPath', 2, 4, 1000, NULL, 'Outstanding', 1, NULL, 0, NULL)"
                )
                execSQL(
                    "INSERT INTO notes VALUES ('${q('n')}', 'a0', 'one.example', 'KeyPath', 2, 1, 2000, 'cs', 'Outstanding', 1, 3, 1, 0)"
                )
                execSQL(
                    "INSERT INTO notes VALUES ('${q('b')}', 'a0', 'one.example', 'Bearer', NULL, NULL, 3000, NULL, 'Outstanding', 1, NULL, 0, NULL)"
                )
                execSQL("INSERT INTO key_checks VALUES ('a0', 'one.example', 0, 2, -1, 4, 'Outstanding', 1000, 0, 0, NULL, 5)")
                execSQL("INSERT INTO key_checks VALUES ('a0', 'one.example', 1, 2, 0, 1, 'Outstanding', 2000, 1, 0, NULL, 5)")
                // The primary address on the older keys, two on shares (one held for its fee).
                execSQL("INSERT INTO addresses VALUES ('a0', 'one.example', 'alice', 1, NULL, NULL, 1)")
                execSQL("INSERT INTO addresses VALUES ('a0', 'one.example', 'held', 2, 1, 'lnbc1', NULL)")
                execSQL("INSERT INTO addresses VALUES ('a0', 'one.example', 'bob', 3, 0, NULL, NULL)")
                execSQL("INSERT INTO counters VALUES ('a0', 'one.example', 0, 5)")
                execSQL("INSERT INTO address_scan VALUES ('a0', 'one.example', 5)")
                // Operations: one paying a key of someone else's older keys (scheme 0), one of four fields, and one of the account's own.
                execSQL(
                    "INSERT INTO journal VALUES ('old', 'a0', 'Rotate', 'one.example', 'https://one.example/w', '${q('n')}', 'k', " +
                        "':0:2::7:${q('x')}', 'https://one.example/w?k1=k', 'Unknown', NULL, 1, 1)"
                )
                execSQL(
                    "INSERT INTO journal VALUES ('older', 'a0', 'Rotate', 'one.example', 'https://one.example/w', '${q('o')}', 'k', " +
                        "'a0:0:3:${q('y')}', 'https://one.example/w?k1=k', 'Confirmed', NULL, 1, 1)"
                )
                execSQL(
                    "INSERT INTO journal VALUES ('own', 'a0', 'Split', 'one.example', 'https://one.example/w', '${q('n')}', 'k', " +
                        "'a0:1:0::5:${q('e')},a0:1:2:0:6:${q('f')}', 'https://one.example/w?k1=k', 'Unknown', NULL, 1, 1)"
                )
                // An invoice still waiting, whose note would be on the older keys, and its row in the history.
                execSQL(
                    "INSERT INTO journal VALUES ('receive', 'a0', 'Mint', 'one.example', 'https://one.example/w', '', '', " +
                        "'a0:0:0::3:${q('r')}', 'lnbc1', 'Unknown', NULL, 1, 1)"
                )
                execSQL(
                    "INSERT INTO activity VALUES ('invoice:receive', 'a0', 'Received', 4000, NULL, 'one.example', NULL, NULL, NULL, 0, " +
                        "'Pending', 'receive', 1)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v8.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                assertEquals(listOf(q('b'), q('n')), store.notes("a0").first().map { it.id }.sorted())
                assertEquals(
                    Note(q('n'), "a0", "one.example", NoteKind.KeyPath, 2, 1, 2000, "cs", NoteState.Outstanding, 1, 3, share = 0),
                    store.note(q('n')),
                )
                assertEquals(
                    mapOf(KeyId("a0", "one.example", KeySet.Share(0), 1) to KeyProbe.Outstanding(2000, true, false, 5)),
                    store.keyChecks("a0").first(),
                )
                assertEquals(listOf("bob", "held"), store.addresses("a0").first().map { it.username }.sorted())
                // The primary address went: the oldest one left that isn't held for its fee is primary now.
                assertEquals(RegisteredAddress("bob", "one.example", 3, share = 0), store.currentPrimaryAddress("a0"))
                // Every operation stays, without its outputs on the older keys: one still open settles its inputs (see Reconciler). A
                // receive has none: with its note gone, it is settled as failed, and so is its row in the history.
                assertEquals(
                    listOf(
                        Triple("old", JournalStatus.Unknown, emptyList()),
                        Triple("older", JournalStatus.Confirmed, emptyList()),
                        Triple(
                            "own",
                            JournalStatus.Unknown,
                            listOf(OutputRef("a0", 0, 5, q('e')), OutputRef("a0", 2, 6, q('f'), share = 0)),
                        ),
                        Triple("receive", JournalStatus.Rejected, emptyList()),
                    ),
                    store.journal(JournalStatus.entries.toSet()).map { Triple(it.id, it.status, it.outputs) }.sortedBy { it.first },
                )
                assertEquals(ActivityStatus.Failed, store.activity("invoice:receive")?.status)
                val sql = db.openHelper.readableDatabase
                for (table in listOf("counters", "address_scan")) {
                    sql.query("SELECT name FROM sqlite_master WHERE name = '$table'").use { assertEquals(false, it.moveToFirst(), table) }
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 9 keeps its notes, none of them archived, and can archive one its mint doesn't know`() =
        runBlocking<Unit> {
            val q = "u".repeat(64)
            oldDatabase("v9.db", 9) {
                execSQL("INSERT INTO wallets VALUES ('w1', NULL, 1, NULL, NULL)")
                execSQL("INSERT INTO accounts VALUES ('a0', 'Main', 1, 'w1', 0, 'one.example')")
                execSQL("INSERT INTO notes VALUES ('$q', 'a0', 'one.example', 'KeyPath', 0, 3, 2000, NULL, 'Unknown', 5, NULL, NULL)")
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v9.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val store = RoomWalletStore(db)
                val note = assertNotNull(store.note(q))
                assertEquals(NoteState.Unknown, note.state)
                assertNull(note.archivedAtMillis)
                store.upsertNotes(listOf(note.copy(archivedAtMillis = 7)))
                assertEquals(true, store.note(q)?.isArchived)
                store.deleteNote(q)
                assertNull(store.note(q))
            } finally {
                db.close()
            }
        }

    @Test
    fun `a wallet of version 10 keeps its journal, and an open melt has no verify url`() =
        runBlocking<Unit> {
            val q = "u".repeat(64)
            oldDatabase("v10.db", 10) {
                execSQL("INSERT INTO wallets VALUES ('w1', NULL, 1, NULL, NULL)")
                execSQL("INSERT INTO accounts VALUES ('a0', 'Main', 1, 'w1', 0, 'one.example')")
                execSQL(
                    "INSERT INTO journal VALUES ('j1', 'a0', 'Melt', 'one.example', 'https://one.example/w', '$q', 'ck1x', '', " +
                        "'https://one.example/w/cb?k1=ck1x&pr=lnbc1', 'Unknown', 'in flight', 5, 6)"
                )
            }
            val db =
                Room.databaseBuilder(context, WalletDatabase::class.java, "v10.db")
                    .addMigrations(*WalletDatabase.MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
            try {
                val melt = RoomWalletStore(db).journal(setOf(JournalStatus.Unknown)).single()
                assertEquals(listOf(q), melt.inputIds)
                assertEquals("in flight", melt.detail)
                assertNull(melt.verifyUrl)
            } finally {
                db.close()
            }
        }

    @Test
    fun `collecting and its fee cap set under v0_3_0 stay each account's`() =
        runBlocking<Unit> {
            val file = File(context.filesDir, "collect.preferences_pb").apply { delete() }
            val before = SupervisorJob()
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + before)) { file }
                .edit {
                    it[booleanPreferencesKey("collect_at_home_a0")] = true
                    it[booleanPreferencesKey("collect_at_home_a1")] = false
                    it[intPreferencesKey("collect_fee_cap_a0")] = 3
                }
            before.cancelAndJoin()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val store =
                    PreferenceDataStoreFactory.create(migrations = DataStorePreferences.migrations { listOf("a") }, scope = scope) { file }
                val prefs = DataStorePreferences(store)
                assertEquals(true, prefs.collect("a0").first())
                assertEquals(3, prefs.collectFeeCap("a0").first())
                assertEquals(false, prefs.collect("a1").first(), "another account of the same wallet keeps its own choice")
                assertNull(prefs.collectFeeCap("a1").first())
                assertNull(prefs.collect("a2").first(), "an account that never chose follows the brand")
                // Deleting an account forgets its choices only.
                prefs.clear("a0")
                assertNull(prefs.collect("a0").first())
                assertNull(prefs.collectFeeCap("a0").first())
                assertEquals(false, prefs.collect("a1").first())
            } finally {
                scope.cancel()
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
                val store =
                    PreferenceDataStoreFactory.create(migrations = DataStorePreferences.migrations { emptyList() }, scope = scope) { file }
                assertEquals(3, DataStorePreferences(store).maxAddressesPerMint.first())
                assertNull(store.data.first()[old])
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `trust chosen for the device becomes each wallet's own`() =
        runBlocking<Unit> {
            val file = File(context.filesDir, "trust.preferences_pb").apply { delete() }
            val before = SupervisorJob()
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + before)) { file }
                .edit {
                    it[stringSetPreferencesKey("trusted_mints")] = setOf("mint.example 02aa")
                    it[stringSetPreferencesKey("untrusted_mints")] = setOf("other.example 02bb")
                }
            before.cancelAndJoin()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        migrations = DataStorePreferences.migrations { listOf("w1", "w2") },
                        scope = scope,
                    ) {
                        file
                    }
                val prefs = DataStorePreferences(store)
                for (wallet in listOf("w1", "w2")) {
                    assertEquals(setOf(MintKey("mint.example", "02aa")), prefs.trusted(wallet).first())
                    assertEquals(setOf(MintKey("other.example", "02bb")), prefs.untrusted(wallet).first())
                }
                assertNull(store.data.first()[stringSetPreferencesKey("trusted_mints")])

                // From then on, one wallet's choice is its own.
                prefs.setTrusted("w1", MintKey("other.example", "02bb"), true)
                assertEquals(setOf(MintKey("other.example", "02bb")), prefs.untrusted("w2").first())
                prefs.forgetWallet("w1")
                assertEquals(emptySet(), prefs.trusted("w1").first())
                assertEquals(setOf(MintKey("mint.example", "02aa")), prefs.trusted("w2").first())
            } finally {
                scope.cancel()
            }
        }
}
