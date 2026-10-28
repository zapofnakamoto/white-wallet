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
        """,
    )
    fun accountSummaries(): Flow<List<AccountSummaryRow>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Upsert
    suspend fun saveAccount(account: AccountEntity)

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0")
    fun activeAccountId(): Flow<String?>

    @Query("SELECT activeAccountId FROM app_state WHERE id = 0")
    suspend fun currentAccountId(): String?

    @Upsert
    suspend fun saveAppState(state: AppStateEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccountRow(id: String)

    @Query("DELETE FROM notes WHERE accountId = :id")
    suspend fun deleteNotes(id: String)

    @Query("DELETE FROM profile WHERE accountId = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM counters WHERE accountId = :id")
    suspend fun deleteCounters(id: String)

    @Query("DELETE FROM address_scan WHERE accountId = :id")
    suspend fun deleteAddressScan(id: String)

    @Query("DELETE FROM addresses WHERE accountId = :id")
    suspend fun deleteAddresses(id: String)

    @Query("SELECT * FROM addresses WHERE accountId = :accountId ORDER BY addedAtMillis")
    fun addresses(accountId: String): Flow<List<AddressEntity>>

    @Query("SELECT COUNT(*) FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun addressCount(accountId: String, host: String, username: String): Int

    @Upsert
    suspend fun saveAddress(address: AddressEntity)

    @Query("DELETE FROM addresses WHERE accountId = :accountId AND host = :host AND username = :username")
    suspend fun deleteAddress(accountId: String, host: String, username: String)

    @Query("DELETE FROM key_checks WHERE accountId = :id")
    suspend fun deleteKeyChecks(id: String)

    @Query("SELECT * FROM key_checks WHERE accountId = :accountId")
    fun keyChecks(accountId: String): Flow<List<KeyCheckEntity>>

    @Upsert
    suspend fun putKeyCheck(check: KeyCheckEntity)

    @Query("DELETE FROM journal WHERE accountId = :id")
    suspend fun deleteJournal(id: String)

    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun notes(accountId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Upsert
    suspend fun upsertNotes(notes: List<NoteEntity>)

    @Query("SELECT * FROM profile WHERE accountId = :accountId")
    fun profile(accountId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE accountId = :accountId")
    suspend fun currentProfile(accountId: String): ProfileEntity?

    @Upsert
    suspend fun saveProfile(profile: ProfileEntity)

    @Query("SELECT * FROM mints WHERE host = :host")
    suspend fun mint(host: String): MintEntity?

    @Query("SELECT * FROM mints ORDER BY host")
    fun mints(): Flow<List<MintEntity>>

    @Upsert
    suspend fun saveMint(mint: MintEntity)

    @Query("DELETE FROM mints WHERE host = :host")
    suspend fun removeMint(host: String)

    @Query("SELECT next FROM address_scan WHERE accountId = :accountId AND host = :host")
    suspend fun addressScanFrom(accountId: String, host: String): Long?

    @Upsert
    suspend fun saveAddressScan(scan: AddressScanEntity)

    @Query("SELECT DISTINCT host FROM counters WHERE accountId = :accountId")
    suspend fun counterHosts(accountId: String): List<String>

    @Query("SELECT * FROM counters WHERE accountId = :accountId AND host = :host")
    fun countersFlow(accountId: String, host: String): Flow<List<CounterEntity>>

    @Query("SELECT next FROM address_scan WHERE accountId = :accountId AND host = :host")
    fun addressScanFlow(accountId: String, host: String): Flow<Long?>

    @Query("SELECT next FROM counters WHERE accountId = :accountId AND host = :host AND purpose = :purpose")
    suspend fun counter(accountId: String, host: String, purpose: Int): Long?

    @Upsert
    suspend fun saveCounter(counter: CounterEntity)

    @Upsert
    suspend fun putJournal(entry: JournalEntity)

    @Query("SELECT * FROM journal WHERE status IN (:statuses) ORDER BY createdAtMillis")
    suspend fun journal(statuses: List<String>): List<JournalEntity>

    // Newest first; rowid orders rows written in the same millisecond (@Upsert keeps a row's rowid).
    @Query("SELECT * FROM activity WHERE accountId = :accountId ORDER BY createdAtMillis DESC, rowid DESC")
    fun activities(accountId: String): Flow<List<ActivityEntity>>

    @Upsert
    suspend fun putActivity(activity: ActivityEntity)

    @Query("UPDATE activity SET status = :status WHERE journalId = :journalId AND status = 'Pending'")
    suspend fun settleActivities(journalId: String, status: String)

    @Query("DELETE FROM activity WHERE accountId = :id")
    suspend fun deleteActivities(id: String)
}

@Database(
    entities = [
        AccountEntity::class, AppStateEntity::class, NoteEntity::class, MintEntity::class, ProfileEntity::class,
        CounterEntity::class, AddressScanEntity::class, JournalEntity::class, KeyCheckEntity::class,
        AddressEntity::class, ActivityEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class WalletDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        /** Every schema change from v0.3.0 (version 2) on; installed wallets keep their data. Schemas: core/data/schemas. */
        val MIGRATIONS = arrayOf(Migration2To3)
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
