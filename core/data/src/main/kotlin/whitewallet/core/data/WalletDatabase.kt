package whitewallet.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
internal interface WalletDao {
    @Query(
        """
        SELECT a.id, a.name, a.createdAtMillis, a.walletId, a.accountIndex,
               COALESCE((SELECT SUM(n.amountMsat) FROM notes n WHERE n.accountId = a.id AND n.state = 'Outstanding'), 0) AS balanceMsat
        FROM accounts a
        ORDER BY a.createdAtMillis
        """
    )
    fun accountSummaries(): Flow<List<AccountSummaryRow>>

    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?

    // Not @Upsert: it turns any unique conflict into an update by id, which would drop an account taking a used (wallet, index) unnoticed.
    @Insert suspend fun insertAccount(account: AccountEntity)

    @Update suspend fun updateAccount(account: AccountEntity): Int

    @Query("SELECT * FROM wallets WHERE id = :id") suspend fun wallet(id: String): WalletEntity?

    @Query("SELECT * FROM wallets ORDER BY createdAtMillis") fun wallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM accounts ORDER BY walletId, accountIndex") suspend fun accountsByIndex(): List<AccountEntity>

    @Upsert suspend fun saveWallet(wallet: WalletEntity)

    @Query("DELETE FROM wallets WHERE id = :id AND NOT EXISTS (SELECT 1 FROM accounts WHERE walletId = :id)")
    suspend fun deleteWalletIfEmpty(id: String)

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0") fun activeAccountId(): Flow<String?>

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0") suspend fun currentAccountId(): String?

    @Upsert suspend fun saveAppState(state: AppStateEntity)

    @Query("DELETE FROM accounts WHERE id = :id") suspend fun deleteAccountRow(id: String)

    @Query("DELETE FROM notes WHERE accountId = :id") suspend fun deleteNotes(id: String)

    @Query("SELECT * FROM addresses WHERE accountId = :accountId ORDER BY addedAtMillis")
    fun addresses(accountId: String): Flow<List<AddressEntity>>

    @Upsert suspend fun saveAddress(address: AddressEntity)

    @Query("DELETE FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun deleteAddress(accountId: String, host: String, username: String)

    @Query("SELECT * FROM key_checks WHERE accountId = :accountId") fun keyChecks(accountId: String): Flow<List<KeyCheckEntity>>

    @Upsert suspend fun putKeyCheck(check: KeyCheckEntity)

    @Query("DELETE FROM journal WHERE accountId = :id") suspend fun deleteJournal(id: String)

    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun notes(accountId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): NoteEntity?

    @Upsert suspend fun upsertNotes(notes: List<NoteEntity>)

    @Query("SELECT * FROM addresses WHERE accountId = :accountId AND isPrimary = 1")
    fun primaryAddress(accountId: String): Flow<AddressEntity?>

    @Query("SELECT * FROM addresses WHERE accountId = :accountId AND isPrimary = 1")
    suspend fun currentPrimaryAddress(accountId: String): AddressEntity?

    @Query("SELECT * FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun address(accountId: String, host: String, username: String): AddressEntity?

    @Query("UPDATE addresses SET isPrimary = NULL WHERE accountId = :accountId") suspend fun clearPrimary(accountId: String)

    @Query("UPDATE addresses SET isPrimary = 1 WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun markPrimary(accountId: String, host: String, username: String): Int

    @Query("SELECT * FROM mints WHERE walletId = :walletId AND host = :host") suspend fun mint(walletId: String, host: String): MintEntity?

    @Query("SELECT * FROM mints WHERE walletId = :walletId ORDER BY host") fun mints(walletId: String): Flow<List<MintEntity>>

    @Upsert suspend fun saveMint(mint: MintEntity)

    @Query("DELETE FROM mints WHERE walletId = :walletId AND host = :host") suspend fun removeMint(walletId: String, host: String)

    @Query("DELETE FROM mints WHERE walletId = :walletId") suspend fun removeMints(walletId: String)

    @Query("SELECT next FROM address_scan WHERE accountId = :accountId AND host = :host")
    suspend fun addressScanFrom(accountId: String, host: String): Long?

    @Upsert suspend fun saveAddressScan(scan: AddressScanEntity)

    @Query("SELECT DISTINCT host FROM counters WHERE accountId = :accountId") suspend fun counterHosts(accountId: String): List<String>

    @Query("SELECT * FROM counters WHERE accountId = :accountId AND host = :host")
    fun countersFlow(accountId: String, host: String): Flow<List<CounterEntity>>

    @Query("SELECT next FROM address_scan WHERE accountId = :accountId AND host = :host")
    fun addressScanFlow(accountId: String, host: String): Flow<Long?>

    @Query("SELECT next FROM counters WHERE accountId = :accountId AND host = :host AND purpose = :purpose")
    suspend fun counter(accountId: String, host: String, purpose: Int): Long?

    @Upsert suspend fun saveCounter(counter: CounterEntity)

    @Query("SELECT next FROM key_counters WHERE accountId = :accountId AND domain = :domain AND counter = :counter")
    suspend fun keyCounter(accountId: String, domain: String, counter: String): Long?

    @Query("SELECT * FROM key_counters WHERE accountId = :accountId AND domain = :domain")
    fun keyCountersFlow(accountId: String, domain: String): Flow<List<KeyCounterEntity>>

    @Upsert suspend fun saveKeyCounter(counter: KeyCounterEntity)

    @Query("SELECT next FROM share_scan WHERE accountId = :accountId AND host = :host AND share = :share")
    suspend fun shareScanFrom(accountId: String, host: String, share: Long): Long?

    @Upsert suspend fun saveShareScan(scan: ShareScanEntity)

    @Query("SELECT * FROM share_scan WHERE accountId = :accountId AND host = :host")
    fun shareScansFlow(accountId: String, host: String): Flow<List<ShareScanEntity>>

    @Upsert suspend fun putJournal(entry: JournalEntity)

    @Query("SELECT * FROM journal WHERE status IN (:statuses) ORDER BY createdAtMillis")
    suspend fun journal(statuses: List<String>): List<JournalEntity>

    // Newest first; rowid orders rows written in the same millisecond (@Upsert keeps a row's rowid).
    @Query("SELECT * FROM activity WHERE accountId = :accountId ORDER BY createdAtMillis DESC, rowid DESC")
    fun activities(accountId: String): Flow<List<ActivityEntity>>

    @Upsert suspend fun putActivity(activity: ActivityEntity)

    @Query("UPDATE activity SET status = :status WHERE journalId = :journalId AND status = 'Pending'")
    suspend fun settleActivities(journalId: String, status: String)

    @Query("SELECT * FROM received_notes WHERE accountId = :accountId ORDER BY receivedAtMillis DESC")
    fun receivedNotes(accountId: String): Flow<List<ReceivedNoteEntity>>

    @Query("SELECT * FROM received_notes WHERE id = :id") suspend fun receivedNote(id: String): ReceivedNoteEntity?

    @Query("SELECT * FROM received_notes WHERE state IN (:states) ORDER BY receivedAtMillis")
    suspend fun receivedNotes(states: List<String>): List<ReceivedNoteEntity>

    @Upsert suspend fun putReceivedNote(note: ReceivedNoteEntity)

    @Query("DELETE FROM received_notes WHERE id = :id") suspend fun deleteReceivedNote(id: String)

    @Query("DELETE FROM received_notes WHERE accountId = :id") suspend fun deleteReceivedNotes(id: String)
}

@Database(
    entities =
        [
            AccountEntity::class,
            WalletEntity::class,
            AppStateEntity::class,
            NoteEntity::class,
            MintEntity::class,
            CounterEntity::class,
            AddressScanEntity::class,
            JournalEntity::class,
            KeyCheckEntity::class,
            AddressEntity::class,
            ActivityEntity::class,
            ReceivedNoteEntity::class,
            KeyCounterEntity::class,
            ShareScanEntity::class,
        ],
    version = 7,
    exportSchema = true,
)
internal abstract class WalletDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        /** Every schema change from v0.3.0 (version 2) on; installed wallets keep their data. Schemas: core/data/schemas. */
        val MIGRATIONS = arrayOf(Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7)
    }
}

/** A service is a mint: the table, the history kind of a move between mints and the id of its history row. */
internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE services RENAME TO mints")
        db.execSQL("UPDATE activity SET kind = 'MovedMint' WHERE kind = 'MovedService'")
        db.execSQL("UPDATE activity SET id = 'mint:' || substr(id, 9) WHERE id LIKE 'service:%'")
    }
}

/** Notes received as links are kept until claimed (see ReceivedNote); a note remembers when its mint last answered for it. */
internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN `checkedAtMillis` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `received_notes` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                "`withdrawUrl` TEXT NOT NULL, `k1` TEXT, `certificate` TEXT, `declaredMsat` INTEGER, `amountMsat` INTEGER, " +
                "`lockedUntilMillis` INTEGER, `lockedForSeconds` INTEGER, `state` TEXT NOT NULL, `journalId` TEXT, " +
                "`receivedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `refusedSinceMillis` INTEGER, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_received_notes_accountId` ON `received_notes` (`accountId`)")
    }
}

/**
 * LUD-26 at luds 92e430f: wallets hold accounts (each account so far becomes its own wallet, with its id, at account index 0); notes, key
 * checks and journal outputs say which key scheme they use (everything so far is legacy 2f17578); new counters per mint domain and share
 * scans; an address says which share it is on (none so far: they are on the legacy branch `cx1`).
 */
internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wallets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO wallets (id, name, createdAtMillis) SELECT id, name, createdAtMillis FROM accounts")
        db.execSQL("ALTER TABLE accounts ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN `accountIndex` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE accounts SET walletId = id")

        db.execSQL("ALTER TABLE notes ADD COLUMN `scheme` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN `share` INTEGER")
        db.execSQL("ALTER TABLE addresses ADD COLUMN `share` INTEGER")
        db.execSQL("ALTER TABLE addresses ADD COLUMN `feeInvoice` TEXT")

        // The primary key gains the scheme and share: a new table, the old rows as legacy keys.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_checks_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `scheme` INTEGER NOT NULL, " +
                "`purpose` INTEGER NOT NULL, `share` INTEGER NOT NULL, `keyIndex` INTEGER NOT NULL, `result` TEXT NOT NULL, " +
                "`amountMsat` INTEGER, `certified` INTEGER NOT NULL, `imported` INTEGER NOT NULL, `reason` TEXT, " +
                "`checkedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`, `scheme`, `purpose`, `share`, `keyIndex`))"
        )
        db.execSQL(
            "INSERT INTO key_checks_new SELECT accountId, host, 0, purpose, -1, keyIndex, result, amountMsat, certified, imported, " +
                "reason, checkedAtMillis FROM key_checks"
        )
        db.execSQL("DROP TABLE key_checks")
        db.execSQL("ALTER TABLE key_checks_new RENAME TO key_checks")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_counters` (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `counter` TEXT NOT NULL, " +
                "`next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `domain`, `counter`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `share_scan` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `share` INTEGER NOT NULL, " +
                "`next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`, `share`))"
        )
        // Journal outputs of four fields are read as legacy keys; nothing to rewrite.
    }
}

/**
 * Each wallet has its own mints, with the key it pinned: every mint so far becomes each wallet's, as all of them shared it. An account may
 * have no home mint (its wallet has none yet); every account so far keeps its own.
 */
internal object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                "`host` TEXT, `walletId` TEXT NOT NULL DEFAULT '', `accountIndex` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO accounts_new SELECT id, name, createdAtMillis, host, walletId, accountIndex FROM accounts")
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_new RENAME TO accounts")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mints_new` (`walletId` TEXT NOT NULL, `host` TEXT NOT NULL, `withdrawUrl` TEXT NOT NULL, " +
                "`mintPubkey` TEXT, `minSendableMsat` INTEGER NOT NULL, `maxSendableMsat` INTEGER NOT NULL, `feeBaseMsat` INTEGER, " +
                "`feePpm` INTEGER, PRIMARY KEY(`walletId`, `host`))"
        )
        db.execSQL(
            "INSERT INTO mints_new SELECT wallets.id, host, withdrawUrl, mintPubkey, minSendableMsat, maxSendableMsat, feeBaseMsat, " +
                "feePpm FROM mints, wallets"
        )
        db.execSQL("DROP TABLE mints")
        db.execSQL("ALTER TABLE mints_new RENAME TO mints")
    }
}

/**
 * The wallet model, cleaned up: the home mint is the wallet's (its first account's, of those that had one), not each account's; an
 * account's primary address is a flag on its address (`profile` goes); a wallet's setup step is kept; every table points at its wallet or
 * account with a foreign key, and one (wallet, index) is one account.
 *
 * Nothing that holds money is dropped. Duplicate accounts at one (wallet, index) (an old scan bug) are merged into the oldest: the others'
 * rows move to it, counters keep the highest index (no key is used twice), scans the lowest (a scan goes back rather than skip). Notes,
 * received notes and journal entries of an account that doesn't exist move to `orphan_*` tables; rows derived from the seed (counters,
 * scans, key checks), history, addresses and mints of a missing account or wallet go. Wallets whose accounts had different homes are noted
 * in [SPLIT_HOMES] for the settings migration (see [DataStorePreferences.migrations]): it keeps those homes trusted and collecting off.
 */
internal object Migration6To7 : Migration(6, 7) {
    /** Each account home other than its wallet's new home, with the key the wallet pinned for it; emptied by the settings migration. */
    const val SPLIT_HOMES = "migration_split_homes"

    /** The other homes per wallet in [SPLIT_HOMES] (host to pinned key); none on a database that never had them. */
    fun splitHomes(db: SupportSQLiteDatabase): Map<String, List<Pair<String, String?>>> {
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$SPLIT_HOMES'").use {
            if (!it.moveToFirst()) return emptyMap()
        }
        val homes = mutableMapOf<String, MutableList<Pair<String, String?>>>()
        db.query("SELECT walletId, host, mintPubkey FROM $SPLIT_HOMES").use { c ->
            while (c.moveToNext()) homes.getOrPut(c.getString(0)) { mutableListOf() } +=
                c.getString(1) to if (c.isNull(2)) null else c.getString(2)
        }
        return homes
    }

    /** Forgets [SPLIT_HOMES] once the settings migration has used it. */
    fun forgetSplitHomes(db: SupportSQLiteDatabase) = db.execSQL("DROP TABLE IF EXISTS $SPLIT_HOMES")

    override fun migrate(db: SupportSQLiteDatabase) {
        // Every account's wallet exists.
        db.execSQL(
            "INSERT OR IGNORE INTO wallets (id, name, createdAtMillis) SELECT walletId, name, MIN(createdAtMillis) FROM accounts GROUP BY walletId"
        )
        mergeDuplicates(db)
        keepOrphans(db)
        noteSplitHomes(db)

        for (table in TABLES) {
            db.execSQL(table.createSql)
            val columns = table.columns.joinToString(", ") { "`$it`" }
            when (table.name) {
                "wallets" ->
                    db.execSQL(
                        "INSERT INTO wallets_new (id, name, createdAtMillis, homeHost) SELECT w.id, w.name, w.createdAtMillis, " +
                            "(SELECT a.host FROM accounts a WHERE a.walletId = w.id AND a.host IS NOT NULL " +
                            "ORDER BY a.accountIndex, a.createdAtMillis LIMIT 1) FROM wallets w"
                    )
                "addresses" -> {
                    db.execSQL(
                        "INSERT INTO addresses_new (accountId, host, username, addedAtMillis, share, feeInvoice) " +
                            "SELECT accountId, host, username, addedAtMillis, share, feeInvoice FROM addresses"
                    )
                    // The primary address was its own row; it was always among the addresses, but keep it even if it wasn't.
                    db.execSQL(
                        "INSERT OR IGNORE INTO addresses_new (accountId, host, username, addedAtMillis) " +
                            "SELECT accountId, host, username, CAST(strftime('%s', 'now') AS INTEGER) * 1000 FROM profile"
                    )
                    db.execSQL(
                        "UPDATE addresses_new SET isPrimary = 1 WHERE EXISTS (SELECT 1 FROM profile p WHERE p.accountId = " +
                            "addresses_new.accountId AND p.host = addresses_new.host AND p.username = addresses_new.username)"
                    )
                    db.execSQL("DROP TABLE profile")
                }
                else -> db.execSQL("INSERT INTO ${table.name}_new ($columns) SELECT $columns FROM ${table.name}")
            }
            db.execSQL("DROP TABLE ${table.name}")
            db.execSQL("ALTER TABLE ${table.name}_new RENAME TO ${table.name}")
            table.indices.forEach(db::execSQL)
        }

        db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "a row points at a wallet or account that doesn't exist" } }
    }

    /** One account per (wallet, index): the oldest stays, the others' rows move to it. */
    private fun mergeDuplicates(db: SupportSQLiteDatabase) {
        val duplicates = mutableListOf<Pair<String, String>>()
        db.query(
                "SELECT a.id, (SELECT b.id FROM accounts b WHERE b.walletId = a.walletId AND b.accountIndex = a.accountIndex " +
                    "ORDER BY b.createdAtMillis, b.id LIMIT 1) FROM accounts a"
            )
            .use { c ->
                while (c.moveToNext()) if (c.getString(0) != c.getString(1)) duplicates += c.getString(0) to c.getString(1)
            }
        for ((dup, keep) in duplicates) {
            val ids = arrayOf<Any>(keep, dup)
            for (table in listOf("notes", "received_notes", "journal", "activity")) {
                db.execSQL("UPDATE $table SET accountId = ? WHERE accountId = ?", ids)
            }
            // Journal outputs name their account first in each comma-separated field.
            db.execSQL(
                "UPDATE journal SET outputs = substr(replace(',' || outputs, ?, ?), 2)",
                arrayOf<Any>(",$dup:", ",$keep:"),
            )
            mergeRows(db, "counters", listOf("host", "purpose"), "MAX", keep, dup)
            mergeRows(db, "key_counters", listOf("domain", "counter"), "MAX", keep, dup)
            mergeRows(db, "address_scan", listOf("host"), "MIN", keep, dup)
            mergeRows(db, "share_scan", listOf("host", "share"), "MIN", keep, dup)
            db.execSQL(
                "INSERT OR IGNORE INTO addresses SELECT ?, host, username, addedAtMillis, share, feeInvoice FROM addresses WHERE accountId = ?",
                ids,
            )
            db.execSQL("INSERT OR IGNORE INTO profile SELECT ?, username, host FROM profile WHERE accountId = ?", ids)
            db.execSQL("UPDATE app_state SET activeAccountId = ? WHERE activeAccountId = ?", ids)
            for (table in listOf("addresses", "profile", "key_checks", "counters", "key_counters", "address_scan", "share_scan")) {
                db.execSQL("DELETE FROM $table WHERE accountId = ?", arrayOf<Any>(dup))
            }
            db.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf<Any>(dup))
        }
    }

    /** Gives [keep] [dup]'s `next` in [table] (keyed by [keys]), [pick] (MAX or MIN) of the two where both have one. */
    private fun mergeRows(db: SupportSQLiteDatabase, table: String, keys: List<String>, pick: String, keep: String, dup: String) {
        val match = keys.joinToString(" AND ") { "k.$it = d.$it" }
        db.execSQL(
            "INSERT OR REPLACE INTO $table (accountId, ${keys.joinToString(", ")}, next) " +
                "SELECT ?, ${keys.joinToString(", ") { "d.$it" }}, " +
                "COALESCE($pick(d.next, (SELECT k.next FROM $table k WHERE k.accountId = ? AND $match)), d.next) " +
                "FROM $table d WHERE d.accountId = ?",
            arrayOf<Any>(keep, keep, dup),
        )
    }

    /** Money and operations of an account that doesn't exist move aside, never dropped; what can be derived again goes. */
    private fun keepOrphans(db: SupportSQLiteDatabase) {
        val missing = "accountId NOT IN (SELECT id FROM accounts)"
        for (table in listOf("notes", "received_notes", "journal")) {
            val orphans =
                db.query("SELECT COUNT(*) FROM $table WHERE $missing").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            if (orphans == 0) continue
            db.execSQL("CREATE TABLE IF NOT EXISTS orphan_$table AS SELECT * FROM $table WHERE 0")
            db.execSQL("INSERT INTO orphan_$table SELECT * FROM $table WHERE $missing")
            db.execSQL("DELETE FROM $table WHERE $missing")
        }
        for (table in listOf("activity", "addresses", "profile", "counters", "key_counters", "address_scan", "share_scan", "key_checks")) {
            db.execSQL("DELETE FROM $table WHERE $missing")
        }
        db.execSQL("DELETE FROM mints WHERE walletId NOT IN (SELECT id FROM wallets)")
        db.execSQL("UPDATE app_state SET activeAccountId = NULL WHERE activeAccountId NOT IN (SELECT id FROM accounts)")
    }

    /** Notes each account home that isn't its wallet's new home, with the key the wallet pinned for it. */
    private fun noteSplitHomes(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $SPLIT_HOMES (`walletId` TEXT NOT NULL, `host` TEXT NOT NULL, `mintPubkey` TEXT, " +
                "PRIMARY KEY(`walletId`, `host`))"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO $SPLIT_HOMES SELECT a.walletId, a.host, " +
                "(SELECT m.mintPubkey FROM mints m WHERE m.walletId = a.walletId AND m.host = a.host) FROM accounts a " +
                "WHERE a.host IS NOT NULL AND a.host IS NOT (SELECT b.host FROM accounts b WHERE b.walletId = a.walletId " +
                "AND b.host IS NOT NULL ORDER BY b.accountIndex, b.createdAtMillis LIMIT 1)"
        )
    }

    private class Table(val name: String, val createSql: String, val columns: List<String>, val indices: List<String>)

    /** Version 7's tables with their foreign keys, in the order they are rebuilt (parents first), as Room creates them. */
    private val TABLES: List<Table> =
        listOf(
            Table(
                "wallets",
                "CREATE TABLE IF NOT EXISTS `wallets_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `homeHost` TEXT, `setupStep` TEXT, `activeBeforeSetup` TEXT, PRIMARY KEY(`id`))",
                listOf("id", "name", "createdAtMillis", "homeHost", "setupStep", "activeBeforeSetup"),
                listOf(),
            ),
            Table(
                "accounts",
                "CREATE TABLE IF NOT EXISTS `accounts_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `walletId` TEXT NOT NULL, `accountIndex` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`walletId`) REFERENCES `wallets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                listOf("id", "name", "createdAtMillis", "walletId", "accountIndex"),
                listOf(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_walletId_accountIndex` ON `accounts` (`walletId`, `accountIndex`)"
                ),
            ),
            Table(
                "mints",
                "CREATE TABLE IF NOT EXISTS `mints_new` (`walletId` TEXT NOT NULL, `host` TEXT NOT NULL, `withdrawUrl` TEXT NOT NULL, `mintPubkey` TEXT, `minSendableMsat` INTEGER NOT NULL, `maxSendableMsat` INTEGER NOT NULL, `feeBaseMsat` INTEGER, `feePpm` INTEGER, PRIMARY KEY(`walletId`, `host`), FOREIGN KEY(`walletId`) REFERENCES `wallets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("walletId", "host", "withdrawUrl", "mintPubkey", "minSendableMsat", "maxSendableMsat", "feeBaseMsat", "feePpm"),
                listOf(),
            ),
            Table(
                "notes",
                "CREATE TABLE IF NOT EXISTS `notes_new` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `kind` TEXT NOT NULL, `purpose` INTEGER, `noteIndex` INTEGER, `amountMsat` INTEGER NOT NULL, `certificate` TEXT, `state` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `checkedAtMillis` INTEGER, `scheme` INTEGER NOT NULL DEFAULT 0, `share` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                listOf(
                    "id",
                    "accountId",
                    "host",
                    "kind",
                    "purpose",
                    "noteIndex",
                    "amountMsat",
                    "certificate",
                    "state",
                    "updatedAtMillis",
                    "checkedAtMillis",
                    "scheme",
                    "share",
                ),
                listOf("CREATE INDEX IF NOT EXISTS `index_notes_accountId` ON `notes` (`accountId`)"),
            ),
            Table(
                "received_notes",
                "CREATE TABLE IF NOT EXISTS `received_notes_new` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `withdrawUrl` TEXT NOT NULL, `k1` TEXT, `certificate` TEXT, `declaredMsat` INTEGER, `amountMsat` INTEGER, `lockedUntilMillis` INTEGER, `lockedForSeconds` INTEGER, `state` TEXT NOT NULL, `journalId` TEXT, `receivedAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `refusedSinceMillis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                listOf(
                    "id",
                    "accountId",
                    "host",
                    "withdrawUrl",
                    "k1",
                    "certificate",
                    "declaredMsat",
                    "amountMsat",
                    "lockedUntilMillis",
                    "lockedForSeconds",
                    "state",
                    "journalId",
                    "receivedAtMillis",
                    "updatedAtMillis",
                    "refusedSinceMillis",
                ),
                listOf("CREATE INDEX IF NOT EXISTS `index_received_notes_accountId` ON `received_notes` (`accountId`)"),
            ),
            Table(
                "journal",
                "CREATE TABLE IF NOT EXISTS `journal_new` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `type` TEXT NOT NULL, `host` TEXT NOT NULL, `withdrawUrl` TEXT NOT NULL, `inputIds` TEXT NOT NULL, `inputK1s` TEXT NOT NULL, `outputs` TEXT NOT NULL, `requestUrl` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT, `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                listOf(
                    "id",
                    "accountId",
                    "type",
                    "host",
                    "withdrawUrl",
                    "inputIds",
                    "inputK1s",
                    "outputs",
                    "requestUrl",
                    "status",
                    "detail",
                    "createdAtMillis",
                    "updatedAtMillis",
                ),
                listOf("CREATE INDEX IF NOT EXISTS `index_journal_accountId` ON `journal` (`accountId`)"),
            ),
            Table(
                "activity",
                "CREATE TABLE IF NOT EXISTS `activity_new` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `kind` TEXT NOT NULL, `amountMsat` INTEGER NOT NULL, `feeMsat` INTEGER, `host` TEXT NOT NULL, `counterparty` TEXT, `certified` INTEGER, `noteCount` INTEGER, `automatic` INTEGER NOT NULL, `status` TEXT NOT NULL, `journalId` TEXT, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf(
                    "id",
                    "accountId",
                    "kind",
                    "amountMsat",
                    "feeMsat",
                    "host",
                    "counterparty",
                    "certified",
                    "noteCount",
                    "automatic",
                    "status",
                    "journalId",
                    "createdAtMillis",
                ),
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_activity_accountId_createdAtMillis` ON `activity` (`accountId`, `createdAtMillis`)",
                    "CREATE INDEX IF NOT EXISTS `index_activity_journalId` ON `activity` (`journalId`)",
                ),
            ),
            Table(
                "addresses",
                "CREATE TABLE IF NOT EXISTS `addresses_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `username` TEXT NOT NULL, `addedAtMillis` INTEGER NOT NULL, `share` INTEGER, `feeInvoice` TEXT, `isPrimary` INTEGER, PRIMARY KEY(`accountId`, `host`, `username`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("accountId", "host", "username", "addedAtMillis", "share", "feeInvoice", "isPrimary"),
                listOf("CREATE UNIQUE INDEX IF NOT EXISTS `index_addresses_accountId_isPrimary` ON `addresses` (`accountId`, `isPrimary`)"),
            ),
            Table(
                "counters",
                "CREATE TABLE IF NOT EXISTS `counters_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `purpose` INTEGER NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`, `purpose`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("accountId", "host", "purpose", "next"),
                listOf(),
            ),
            Table(
                "key_counters",
                "CREATE TABLE IF NOT EXISTS `key_counters_new` (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `counter` TEXT NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `domain`, `counter`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("accountId", "domain", "counter", "next"),
                listOf(),
            ),
            Table(
                "address_scan",
                "CREATE TABLE IF NOT EXISTS `address_scan_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("accountId", "host", "next"),
                listOf(),
            ),
            Table(
                "share_scan",
                "CREATE TABLE IF NOT EXISTS `share_scan_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `share` INTEGER NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`, `share`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf("accountId", "host", "share", "next"),
                listOf(),
            ),
            Table(
                "key_checks",
                "CREATE TABLE IF NOT EXISTS `key_checks_new` (`accountId` TEXT NOT NULL, `host` TEXT NOT NULL, `scheme` INTEGER NOT NULL, `purpose` INTEGER NOT NULL, `share` INTEGER NOT NULL, `keyIndex` INTEGER NOT NULL, `result` TEXT NOT NULL, `amountMsat` INTEGER, `certified` INTEGER NOT NULL, `imported` INTEGER NOT NULL, `reason` TEXT, `checkedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `host`, `scheme`, `purpose`, `share`, `keyIndex`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf(
                    "accountId",
                    "host",
                    "scheme",
                    "purpose",
                    "share",
                    "keyIndex",
                    "result",
                    "amountMsat",
                    "certified",
                    "imported",
                    "reason",
                    "checkedAtMillis",
                ),
                listOf(),
            ),
        )
}
