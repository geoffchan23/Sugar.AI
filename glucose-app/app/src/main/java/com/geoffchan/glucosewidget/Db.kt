package com.geoffchan.glucosewidget

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {
    // Readings are immutable facts: ignore duplicates on re-backfill.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadings(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs")
    fun readingsBetween(startMs: Long, endMs: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings ORDER BY timestampMs DESC LIMIT 1")
    fun latestReading(): Flow<ReadingEntity?>

    @Query("SELECT * FROM journal WHERE day = :key AND scope = :scope ORDER BY createdAtMs")
    fun journalFor(scope: String, key: String): Flow<List<JournalEntity>>

    // Day-scoped notes inside a date range; ISO dates compare correctly as strings.
    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :firstDay AND day <= :lastDay ORDER BY day, createdAtMs")
    fun dayJournalInRange(firstDay: String, lastDay: String): Flow<List<JournalEntity>>

    /** Recent day-scope rows for the widget's last-dose / last-log lines. */
    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :sinceDay")
    suspend fun dayJournalSince(sinceDay: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun journalById(id: Long): JournalEntity?

    // One-shot reads for the assistant's tools (no Flow).
    @Query("SELECT * FROM readings WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs")
    suspend fun readingsIn(startMs: Long, endMs: Long): List<ReadingEntity>

    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :firstDay AND day <= :lastDay ORDER BY day, createdAtMs")
    suspend fun dayJournalBetween(firstDay: String, lastDay: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE uid = :uid")
    suspend fun journalByUid(uid: String): JournalEntity?

    // Journal writes go through [Journal] (which keeps sync informed), not these directly.
    @Insert suspend fun insertJournal(entry: JournalEntity): Long
    @Update suspend fun updateJournal(entry: JournalEntity)
    @Delete suspend fun deleteJournal(entry: JournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(t: JournalTombstone)

    @Query("SELECT * FROM journal_tombstones WHERE uid = :uid")
    suspend fun tombstone(uid: String): JournalTombstone?

    @Query("DELETE FROM journal_tombstones WHERE uid = :uid")
    suspend fun clearTombstone(uid: String)

    @Query("SELECT * FROM journal WHERE updatedAtMs >= :sinceMs")
    suspend fun journalUpdatedSince(sinceMs: Long): List<JournalEntity>

    @Query("SELECT * FROM journal_tombstones WHERE deletedAtMs >= :sinceMs")
    suspend fun tombstonesSince(sinceMs: Long): List<JournalTombstone>

    // Assistant interaction log; writes go through [InteractionLog] (sync), not these directly.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInteraction(e: AssistantLogEntity)

    @Update suspend fun updateInteraction(e: AssistantLogEntity)

    @Query("SELECT * FROM assistant_log WHERE uid = :uid")
    suspend fun interaction(uid: String): AssistantLogEntity?

    @Query("SELECT uid FROM assistant_log WHERE updatedAtMs >= :sinceMs")
    suspend fun interactionUidsUpdatedSince(sinceMs: Long): List<String>

    /** Records whose outcome mentions journal row [uid] (the one that saved it, normally just one). */
    @Query("SELECT * FROM assistant_log WHERE outcome LIKE '%' || :uid || '%' ORDER BY createdAtMs DESC")
    suspend fun interactionsMentioning(uid: String): List<AssistantLogEntity>

    @Query("SELECT * FROM assistant_log WHERE outcome LIKE '%\"corrections\":[%' ORDER BY updatedAtMs DESC LIMIT :limit")
    suspend fun correctedInteractions(limit: Int): List<AssistantLogEntity>

    @Query("SELECT COUNT(*) FROM assistant_log")
    suspend fun interactionCount(): Int

    // Chat with Ray; writes go through [ChatStore] (sync), not these directly.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(m: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE uid = :uid")
    suspend fun chatMessage(uid: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE thread = :thread ORDER BY createdAtMs")
    fun chatThread(thread: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE thread = :thread ORDER BY createdAtMs")
    suspend fun chatThreadNow(thread: String): List<ChatMessageEntity>

    /** Every message, oldest first; threads are grouped in [chatThreads]. */
    @Query("SELECT * FROM chat_messages ORDER BY createdAtMs")
    fun allChat(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY createdAtMs")
    suspend fun allChatNow(): List<ChatMessageEntity>

    @Query("SELECT uid FROM chat_messages WHERE updatedAtMs >= :sinceMs")
    suspend fun chatUidsUpdatedSince(sinceMs: Long): List<String>
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journal ADD COLUMN scope TEXT NOT NULL DEFAULT 'day'")
    }
}

/** v3: per-row [JournalEntity.uid] (backfilled) + tombstones, for two-phone sync. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journal ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE journal SET uid = lower(hex(randomblob(16))) WHERE uid = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_uid ON journal (uid)")
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_tombstones (uid TEXT NOT NULL, deletedAtMs INTEGER NOT NULL, PRIMARY KEY(uid))")
    }
}

/** v4: the assistant interaction log (CREATE matches Room's own; checked against schemas/…/4.json in tests). */
val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `assistant_log` (`uid` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
        "`source` TEXT NOT NULL, `input` TEXT NOT NULL, `parser` TEXT NOT NULL, `full` INTEGER NOT NULL, `data` TEXT NOT NULL, " +
        "`outcome` TEXT, PRIMARY KEY(`uid`))",
)

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
    }
}

/** v5: chat with Ray (CREATE matches Room's own; checked against schemas/…/5.json in tests). */
val MIGRATION_4_5_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `chat_messages` (`uid` TEXT NOT NULL, `thread` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
        "`updatedAtMs` INTEGER NOT NULL, `author` TEXT NOT NULL, `text` TEXT NOT NULL, `card` TEXT, `meta` TEXT, PRIMARY KEY(`uid`))",
    "CREATE INDEX IF NOT EXISTS `index_chat_messages_thread_createdAtMs` ON `chat_messages` (`thread`, `createdAtMs`)",
)

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MIGRATION_4_5_SQL.forEach { db.execSQL(it) }
    }
}

@Database(
    entities = [ReadingEntity::class, JournalEntity::class, JournalTombstone::class, AssistantLogEntity::class, ChatMessageEntity::class],
    version = 5, exportSchema = true,
)
abstract class GlucoseDb : RoomDatabase() {
    abstract fun dao(): GlucoseDao

    companion object {
        @Volatile private var instance: GlucoseDb? = null

        fun get(context: Context): GlucoseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GlucoseDb::class.java, "glucose.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}
