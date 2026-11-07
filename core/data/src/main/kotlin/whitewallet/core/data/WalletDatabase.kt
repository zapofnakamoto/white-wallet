package whitewallet.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
internal interface WalletDao {
    @Query(
        """
        SELECT a.id, a.name, a.createdAtMillis, a.host, p.username, p.host AS addressHost,
               COALESCE((SELECT SUM(n.amountMsat) FROM notes n WHERE n.accountId = a.id AND n.state = 'Outstanding'), 0) AS balanceMsat
        FROM accounts a LEFT JOIN profile p ON p.accountId = a.id
        ORDER BY a.createdAtMillis
        """
    )
    fun accountSummaries(): Flow<List<AccountSummaryRow>>

    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?

    @Upsert suspend fun saveAccount(account: AccountEntity)

    @Query("SELECT * FROM wallets WHERE id = :id") suspend fun wallet(id: String): WalletEntity?

    @Query("SELECT * FROM wallets ORDER BY createdAtMillis") fun wallets(): Flow<List<WalletEntity>>

    @Upsert suspend fun saveWallet(wallet: WalletEntity)

    @Query("DELETE FROM wallets WHERE id = :id AND NOT EXISTS (SELECT 1 FROM accounts WHERE walletId = :id)")
    suspend fun deleteWalletIfEmpty(id: String)

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0") fun activeAccountId(): Flow<String?>

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0") suspend fun currentAccountId(): String?

    @Upsert suspend fun saveAppState(state: AppStateEntity)

    @Query("DELETE FROM accounts WHERE id = :id") suspend fun deleteAccountRow(id: String)

    @Query("DELETE FROM notes WHERE accountId = :id") suspend fun deleteNotes(id: String)

    @Query("DELETE FROM profile WHERE accountId = :id") suspend fun deleteProfile(id: String)

    @Query("DELETE FROM counters WHERE accountId = :id") suspend fun deleteCounters(id: String)

    @Query("DELETE FROM address_scan WHERE accountId = :id") suspend fun deleteAddressScan(id: String)

    @Query("DELETE FROM key_counters WHERE accountId = :id") suspend fun deleteKeyCounters(id: String)

    @Query("DELETE FROM share_scan WHERE accountId = :id") suspend fun deleteShareScans(id: String)

    @Query("DELETE FROM addresses WHERE accountId = :id") suspend fun deleteAddresses(id: String)

    @Query("SELECT * FROM addresses WHERE accountId = :accountId ORDER BY addedAtMillis")
    fun addresses(accountId: String): Flow<List<AddressEntity>>

    @Query("SELECT COUNT(*) FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun addressCount(accountId: String, host: String, username: String): Int

    @Upsert suspend fun saveAddress(address: AddressEntity)

    @Query("DELETE FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun deleteAddress(accountId: String, host: String, username: String)

    @Query("DELETE FROM key_checks WHERE accountId = :id") suspend fun deleteKeyChecks(id: String)

    @Query("SELECT * FROM key_checks WHERE accountId = :accountId") fun keyChecks(accountId: String): Flow<List<KeyCheckEntity>>

    @Upsert suspend fun putKeyCheck(check: KeyCheckEntity)

    @Query("DELETE FROM journal WHERE accountId = :id") suspend fun deleteJournal(id: String)

    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun notes(accountId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): NoteEntity?

    @Upsert suspend fun upsertNotes(notes: List<NoteEntity>)

    @Query("SELECT * FROM profile WHERE accountId = :accountId") fun profile(accountId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE accountId = :accountId") suspend fun currentProfile(accountId: String): ProfileEntity?

    @Upsert suspend fun saveProfile(profile: ProfileEntity)

    @Query("SELECT * FROM mints WHERE walletId = :walletId AND host = :host") suspend fun mint(walletId: String, host: String): MintEntity?

    @Query("SELECT * FROM mints WHERE walletId = :walletId ORDER BY host") fun mints(walletId: String): Flow<List<MintEntity>>

    @Upsert suspend fun saveMint(mint: MintEntity)

    @Query("DELETE FROM mints WHERE walletId = :walletId AND host = :host") suspend fun removeMint(walletId: String, host: String)

    @Query("DELETE FROM mints WHERE walletId = :walletId") suspend fun removeMints(walletId: String)

    @Query("DELETE FROM mints WHERE walletId = :walletId AND NOT EXISTS (SELECT 1 FROM accounts WHERE walletId = :walletId)")
    suspend fun deleteMintsIfNoAccount(walletId: String)

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

    @Query("DELETE FROM activity WHERE accountId = :id") suspend fun deleteActivities(id: String)

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
            ProfileEntity::class,
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
    version = 6,
    exportSchema = true,
)
internal abstract class WalletDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        /** Every schema change from v0.3.0 (version 2) on; installed wallets keep their data. Schemas: core/data/schemas. */
        val MIGRATIONS = arrayOf(Migration2To3, Migration3To4, Migration4To5, Migration5To6)
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

/** Each wallet has its own mints, with the key it pinned: every mint so far becomes each wallet's, as all of them shared it. */
internal object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
