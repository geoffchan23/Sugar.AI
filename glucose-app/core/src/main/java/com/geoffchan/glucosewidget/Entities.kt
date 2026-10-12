package com.geoffchan.glucosewidget

import androidx.room.Entity
import androidx.room.PrimaryKey

// The stored rows, shared by the phones (Room, see Db.kt) and the server.
// Only Room's annotations live here (room-common is plain JVM).

@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey val timestampMs: Long,
    val mgdl: Int,
    val trend: String,
)

@Entity(tableName = "journal", indices = [androidx.room.Index(value = ["uid"], unique = true)])
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // For scope "day": the local date "YYYY-MM-DD".
    // For scope "week": the Monday of the ISO week, same format.
    val day: String,
    val text: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @androidx.room.ColumnInfo(defaultValue = "day") val scope: String = SCOPE_DAY,
    // Same on every phone: how her phone and Geoff's match a row when syncing.
    // [id] is local only (autoincrement differs per device).
    @androidx.room.ColumnInfo(defaultValue = "") val uid: String = newUid(),
)

fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")

/** A deleted row, remembered so the delete can sync to the other phone. */
@Entity(tableName = "journal_tombstones")
data class JournalTombstone(
    @PrimaryKey val uid: String,
    val deletedAtMs: Long,
)

/**
 * One thing Francine said or typed to the assistant and what came of it
 * (see InteractionCore.kt). [data] is JSON: context, parsers tried, model
 * usage + trace, proposals. [outcome] is JSON, set when she acts (the eval
 * label). [full] is false for a copy synced from the other phone, whose
 * trace may be trimmed.
 */
@Entity(tableName = "assistant_log")
data class AssistantLogEntity(
    @PrimaryKey val uid: String = newUid(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val source: String,
    val input: String,
    val parser: String,
    val full: Boolean = true,
    val data: String,
    val outcome: String? = null,
)

/**
 * One message in a chat with Ray (see Chat.kt), shared by both phones.
 * [author] is "francine", "geoff" or "ray". [card] (Ray's messages) is JSON:
 * the entries/changes he proposed and whether someone saved them. [meta] is
 * JSON: tools he used (with any code), cost, report file, error. Rows are
 * merged by [uid], last write wins on [updatedAtMs] (a card being saved).
 */
@Entity(tableName = "chat_messages", indices = [androidx.room.Index(value = ["thread", "createdAtMs"])])
data class ChatMessageEntity(
    @PrimaryKey val uid: String = newUid(),
    val thread: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val author: String,
    val text: String,
    val card: String? = null,
    val meta: String? = null,
)

const val SCOPE_DAY = "day"
const val SCOPE_WEEK = "week"
