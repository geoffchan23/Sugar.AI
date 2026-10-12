package com.geoffchan.ray

import com.geoffchan.glucosewidget.AssistantLogEntity
import com.geoffchan.glucosewidget.ChatMessageEntity
import com.geoffchan.glucosewidget.JournalEntity
import com.geoffchan.glucosewidget.JournalTombstone
import com.geoffchan.glucosewidget.Reading
import com.geoffchan.glucosewidget.SyncStore
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The server's copy of everything: the same tables as the phones' Room
 * database (v5, CREATEs copied from app/schemas/…/5.json), so a database
 * pulled off a phone (tools/pull-db.sh) can seed it as is, plus `server_meta`
 * for the server's own state. One connection, used under its lock.
 */
class ServerDb(path: String) : SyncStore {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            SCHEMA.forEach { st.execute(it) }
        }
    }

    fun <T> tx(block: ServerDb.() -> T): T = synchronized(this) {
        conn.autoCommit = false
        try {
            block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(this) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun exec(sql: String, vararg args: Any?): Int = synchronized(this) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
    }

    // --- readings ---

    /** Readings are immutable facts: duplicates are ignored. Returns how many were new. */
    fun insertReadings(rs: List<Reading>): Int = tx {
        rs.sumOf { exec("INSERT OR IGNORE INTO readings (timestampMs, mgdl, trend) VALUES (?, ?, ?)", it.timestampMs, it.mgdl, it.trend) }
    }

    fun latestReading(): Reading? =
        query("SELECT * FROM readings ORDER BY timestampMs DESC LIMIT 1") { Reading(it.getInt("mgdl"), it.getLong("timestampMs"), it.getString("trend")) }
            .firstOrNull()

    // --- server state ---

    fun meta(key: String): String? = query("SELECT value FROM server_meta WHERE key = ?", key) { it.getString(1) }.firstOrNull()

    fun setMeta(key: String, value: String) {
        exec("INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)
    }

    fun counts(): Map<String, Int> = listOf("readings", "journal", "journal_tombstones", "assistant_log", "chat_messages")
        .associateWith { t -> query("SELECT COUNT(*) FROM $t") { it.getInt(1) }.first() }

    // --- SyncStore (the shared merge, SyncMerge.kt) ---

    private fun journal(rs: ResultSet) = JournalEntity(
        id = rs.getLong("id"), day = rs.getString("day"), text = rs.getString("text"), createdAtMs = rs.getLong("createdAtMs"),
        updatedAtMs = rs.getLong("updatedAtMs"), scope = rs.getString("scope"), uid = rs.getString("uid"),
    )

    override suspend fun journalByUid(uid: String) = query("SELECT * FROM journal WHERE uid = ?", uid, map = ::journal).firstOrNull()

    override suspend fun insertJournal(entry: JournalEntity): Long {
        exec(
            "INSERT INTO journal (day, text, createdAtMs, updatedAtMs, scope, uid) VALUES (?, ?, ?, ?, ?, ?)",
            entry.day, entry.text, entry.createdAtMs, entry.updatedAtMs, entry.scope, entry.uid,
        )
        return query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
    }

    override suspend fun updateJournal(entry: JournalEntity) {
        exec(
            "UPDATE journal SET day = ?, text = ?, createdAtMs = ?, updatedAtMs = ?, scope = ? WHERE uid = ?",
            entry.day, entry.text, entry.createdAtMs, entry.updatedAtMs, entry.scope, entry.uid,
        )
    }

    override suspend fun deleteJournal(entry: JournalEntity) { exec("DELETE FROM journal WHERE uid = ?", entry.uid) }

    override suspend fun tombstone(uid: String) =
        query("SELECT * FROM journal_tombstones WHERE uid = ?", uid) { JournalTombstone(it.getString("uid"), it.getLong("deletedAtMs")) }.firstOrNull()

    override suspend fun insertTombstone(t: JournalTombstone) {
        exec("INSERT OR REPLACE INTO journal_tombstones (uid, deletedAtMs) VALUES (?, ?)", t.uid, t.deletedAtMs)
    }

    override suspend fun clearTombstone(uid: String) { exec("DELETE FROM journal_tombstones WHERE uid = ?", uid) }

    override suspend fun interaction(uid: String) = query("SELECT * FROM assistant_log WHERE uid = ?", uid) {
        AssistantLogEntity(
            uid = it.getString("uid"), createdAtMs = it.getLong("createdAtMs"), updatedAtMs = it.getLong("updatedAtMs"),
            source = it.getString("source"), input = it.getString("input"), parser = it.getString("parser"),
            full = it.getInt("full") != 0, data = it.getString("data"), outcome = it.getString("outcome"),
        )
    }.firstOrNull()

    override suspend fun insertInteraction(e: AssistantLogEntity) {
        exec(
            "INSERT OR IGNORE INTO assistant_log (uid, createdAtMs, updatedAtMs, source, input, parser, full, data, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.uid, e.createdAtMs, e.updatedAtMs, e.source, e.input, e.parser, if (e.full) 1 else 0, e.data, e.outcome,
        )
    }

    override suspend fun updateInteraction(e: AssistantLogEntity) {
        exec(
            "UPDATE assistant_log SET createdAtMs = ?, updatedAtMs = ?, source = ?, input = ?, parser = ?, full = ?, data = ?, outcome = ? WHERE uid = ?",
            e.createdAtMs, e.updatedAtMs, e.source, e.input, e.parser, if (e.full) 1 else 0, e.data, e.outcome, e.uid,
        )
    }

    override suspend fun chatMessage(uid: String) = query("SELECT * FROM chat_messages WHERE uid = ?", uid) {
        ChatMessageEntity(
            uid = it.getString("uid"), thread = it.getString("thread"), createdAtMs = it.getLong("createdAtMs"),
            updatedAtMs = it.getLong("updatedAtMs"), author = it.getString("author"), text = it.getString("text"),
            card = it.getString("card"), meta = it.getString("meta"),
        )
    }.firstOrNull()

    override suspend fun upsertChat(m: ChatMessageEntity) {
        exec(
            "INSERT OR REPLACE INTO chat_messages (uid, thread, createdAtMs, updatedAtMs, author, text, card, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            m.uid, m.thread, m.createdAtMs, m.updatedAtMs, m.author, m.text, m.card, m.meta,
        )
    }

    fun close() = conn.close()

    companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `readings` (`timestampMs` INTEGER NOT NULL, `mgdl` INTEGER NOT NULL, `trend` TEXT NOT NULL, PRIMARY KEY(`timestampMs`))",
            "CREATE TABLE IF NOT EXISTS `journal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `scope` TEXT NOT NULL DEFAULT 'day', `uid` TEXT NOT NULL DEFAULT '')",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_journal_uid` ON `journal` (`uid`)",
            "CREATE TABLE IF NOT EXISTS `journal_tombstones` (`uid` TEXT NOT NULL, `deletedAtMs` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
            "CREATE TABLE IF NOT EXISTS `assistant_log` (`uid` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, `input` TEXT NOT NULL, `parser` TEXT NOT NULL, `full` INTEGER NOT NULL, `data` TEXT NOT NULL, " +
                "`outcome` TEXT, PRIMARY KEY(`uid`))",
            "CREATE TABLE IF NOT EXISTS `chat_messages` (`uid` TEXT NOT NULL, `thread` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, `author` TEXT NOT NULL, `text` TEXT NOT NULL, `card` TEXT, `meta` TEXT, PRIMARY KEY(`uid`))",
            "CREATE INDEX IF NOT EXISTS `index_chat_messages_thread_createdAtMs` ON `chat_messages` (`thread`, `createdAtMs`)",
            "CREATE TABLE IF NOT EXISTS `server_meta` (`key` TEXT NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)",
        )
    }
}
