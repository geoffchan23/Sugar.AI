package com.geoffchan.ray

import com.geoffchan.glucosewidget.AssistantData
import com.geoffchan.glucosewidget.AssistantLogEntity
import com.geoffchan.glucosewidget.ChatMessageEntity
import com.geoffchan.glucosewidget.JournalEntity
import com.geoffchan.glucosewidget.JournalTombstone
import com.geoffchan.glucosewidget.Reading
import com.geoffchan.glucosewidget.ReadingEntity
import com.geoffchan.glucosewidget.SyncStore
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The server's copy of everything: the same tables as the phones' Room
 * database (v5, CREATEs copied from app/schemas/…/5.json), so a database
 * pulled off a phone (tools/pull-db.sh) can seed it as is, plus `server_meta`
 * for the server's own state and `changes`, the log the phones sync from:
 * every write through [SyncStore] appends (kind, uid, origin), kind "j"
 * (journal row or its tombstone), "i" (interaction record) or "c" (chat).
 * One connection, used under its lock; [origin] is who the current write is for.
 */
class ServerDb(private val path: String) : SyncStore, AssistantData {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    /** The device whose payload is being merged ("server" for the server's own writes). */
    @Volatile var origin: String = ORIGIN_SERVER

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

    /** [tx] with every change logged as made by [device]. */
    fun <T> txAs(device: String, block: ServerDb.() -> T): T = synchronized(this) {
        origin = device
        try { tx(block) } finally { origin = ORIGIN_SERVER }
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

    private fun journalRow(rs: ResultSet) = JournalEntity(
        id = rs.getLong("id"), day = rs.getString("day"), text = rs.getString("text"), createdAtMs = rs.getLong("createdAtMs"),
        updatedAtMs = rs.getLong("updatedAtMs"), scope = rs.getString("scope"), uid = rs.getString("uid"),
    )

    override suspend fun journalByUid(uid: String) = query("SELECT * FROM journal WHERE uid = ?", uid, map = ::journalRow).firstOrNull()

    override suspend fun insertJournal(entry: JournalEntity): Long {
        changed("j", entry.uid)
        exec(
            "INSERT INTO journal (day, text, createdAtMs, updatedAtMs, scope, uid) VALUES (?, ?, ?, ?, ?, ?)",
            entry.day, entry.text, entry.createdAtMs, entry.updatedAtMs, entry.scope, entry.uid,
        )
        return query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
    }

    override suspend fun updateJournal(entry: JournalEntity) {
        changed("j", entry.uid)
        exec(
            "UPDATE journal SET day = ?, text = ?, createdAtMs = ?, updatedAtMs = ?, scope = ? WHERE uid = ?",
            entry.day, entry.text, entry.createdAtMs, entry.updatedAtMs, entry.scope, entry.uid,
        )
    }

    override suspend fun deleteJournal(entry: JournalEntity) { changed("j", entry.uid); exec("DELETE FROM journal WHERE uid = ?", entry.uid) }

    override suspend fun tombstone(uid: String) =
        query("SELECT * FROM journal_tombstones WHERE uid = ?", uid) { JournalTombstone(it.getString("uid"), it.getLong("deletedAtMs")) }.firstOrNull()

    override suspend fun insertTombstone(t: JournalTombstone) {
        changed("j", t.uid)
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
        changed("i", e.uid)
        exec(
            "INSERT OR IGNORE INTO assistant_log (uid, createdAtMs, updatedAtMs, source, input, parser, full, data, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.uid, e.createdAtMs, e.updatedAtMs, e.source, e.input, e.parser, if (e.full) 1 else 0, e.data, e.outcome,
        )
    }

    override suspend fun updateInteraction(e: AssistantLogEntity) {
        changed("i", e.uid)
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
        changed("c", m.uid)
        exec(
            "INSERT OR REPLACE INTO chat_messages (uid, thread, createdAtMs, updatedAtMs, author, text, card, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            m.uid, m.thread, m.createdAtMs, m.updatedAtMs, m.author, m.text, m.card, m.meta,
        )
    }

    private fun changed(kind: String, uid: String) {
        exec("INSERT INTO changes (kind, uid, origin) VALUES (?, ?, ?)", kind, uid, origin)
    }

    // --- the change log (POST /v1/sync) ---

    data class Change(val seq: Long, val kind: String, val uid: String)

    fun maxSeq(): Long = query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.first()

    /** The latest change per (kind, uid) after [cursor], not made by [device], oldest first, at most [limit]. */
    fun changesAfter(cursor: Long, device: String, limit: Int): List<Change> = query(
        "SELECT MAX(seq) AS s, kind, uid FROM changes WHERE seq > ? AND origin != ? GROUP BY kind, uid ORDER BY s LIMIT ?",
        cursor, device, limit,
    ) { Change(it.getLong("s"), it.getString("kind"), it.getString("uid")) }

    /** Everything updated since [sinceMs], for a device's first sync with the server. */
    fun updatedSince(sinceMs: Long): List<Pair<String, String>> =
        query("SELECT 'j', uid FROM journal WHERE updatedAtMs >= ? UNION ALL SELECT 'j', uid FROM journal_tombstones WHERE deletedAtMs >= ? " +
            "UNION ALL SELECT 'i', uid FROM assistant_log WHERE updatedAtMs >= ? UNION ALL SELECT 'c', uid FROM chat_messages WHERE updatedAtMs >= ?",
            sinceMs, sinceMs, sinceMs, sinceMs) { it.getString(1) to it.getString(2) }

    // --- Ray's data (AssistantData + extras) ---

    override suspend fun readings(startMs: Long, endMs: Long): List<ReadingEntity> =
        query("SELECT * FROM readings WHERE timestampMs >= ? AND timestampMs < ? ORDER BY timestampMs", startMs, endMs) {
            ReadingEntity(it.getLong("timestampMs"), it.getInt("mgdl"), it.getString("trend"))
        }

    override suspend fun journal(firstDay: String, lastDay: String): List<JournalEntity> =
        query("SELECT * FROM journal WHERE scope = 'day' AND day >= ? AND day <= ? ORDER BY day, createdAtMs", firstDay, lastDay, map = ::journalRow)

    override suspend fun journalById(id: Long): JournalEntity? = query("SELECT * FROM journal WHERE id = ?", id, map = ::journalRow).firstOrNull()

    /** Records whose outcome has corrections, newest first (the "Past corrections" lessons). */
    fun correctedInteractions(limit: Int): List<AssistantLogEntity> =
        query("SELECT uid FROM assistant_log WHERE outcome LIKE '%\"corrections\":[%' ORDER BY updatedAtMs DESC LIMIT ?", limit) { it.getString(1) }
            .mapNotNull { kotlinx.coroutines.runBlocking { interaction(it) } }

    /** A separate read-only connection for Ray's query_data tool. */
    fun readOnly(): Connection = DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro", java.util.Properties().apply { put("open_mode", "1") }).also {
        it.createStatement().use { st -> st.execute("PRAGMA query_only = 1") }
    }

    fun logVoice(via: String, intent: String, text: String, path: String, ms: Long, interaction: String?) {
        exec("INSERT INTO voice_log (atMs, via, intent, text, path, ms, interaction) VALUES (?, ?, ?, ?, ?, ?, ?)",
            System.currentTimeMillis(), via, intent, text, path, ms, interaction)
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
            // how people talk to Ray by voice (Alexa, Hey Google): for tuning the skill's phrasings
            "CREATE TABLE IF NOT EXISTS `voice_log` (`atMs` INTEGER NOT NULL, `via` TEXT NOT NULL, `intent` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `path` TEXT NOT NULL, `ms` INTEGER NOT NULL, `interaction` TEXT)",
            "CREATE TABLE IF NOT EXISTS `changes` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT, `kind` TEXT NOT NULL, `uid` TEXT NOT NULL, `origin` TEXT NOT NULL)",
        )
        const val ORIGIN_SERVER = "server"
    }
}
