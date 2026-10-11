package com.geoffchan.glucosewidget.wear

import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch side of the phone's WatchProtocol (app/…/WatchProtocol.kt) — same
 * paths and field names; change both together.
 */
object Protocol {
    const val PATH_PARSE = "/log/parse"
    const val PATH_PROPOSAL = "/log/proposal"
    const val PATH_SAVE = "/log/save"
    const val PATH_SAVED = "/log/saved"
    const val PATH_QUEUED = "/log/queued"
    const val PATH_QUEUED_ACK = "/log/queued-ack"
    const val PATH_OUTCOME = "/log/outcome"
    const val PATH_UNDO = "/log/undo"
    const val PATH_UNDONE = "/log/undone"

    // outcomes for the phone's interaction log (match InteractionCore.kt)
    const val OUTCOME_CANCELLED = "cancelled"
    const val OUTCOME_ASK_AGAIN = "ask-again"
    const val OUTCOME_ANSWERED = "answered"
    const val STATUS_ALREADY = "already"
    const val STATUS_CONFIRM = "confirm"
    const val STATUS_REPLACE = "replace"
}

data class Row(val text: String, val label: String, val time: String, val status: String)

/** [answer]: the assistant's reply for the watch screen ("" when it only logged). */
/** [id]: the phone's interaction-log record (our parse id echoed back; null from older phones). */
/** [changes]: edits to other days waiting on the phone for review; [awaiting]: the answer is a question. */
/** [saved]: the phone already saved the rows (no confirm screen, from 2026-10-11); null from older phones. */
data class Proposal(
    val ok: Boolean, val error: String?, val parser: String, val rows: List<Row>, val answer: String = "", val id: String? = null,
    val changes: Int = 0, val awaiting: Boolean = false, val saved: Saved? = null,
)

data class Saved(val ok: Boolean, val error: String?, val saved: Int, val confirmed: Int, val already: Int)

private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

fun decodeProposal(json: String): Proposal {
    val o = JSONObject(json)
    val arr = o.optJSONArray("rows") ?: JSONArray()
    val rows = (0 until arr.length()).map {
        val r = arr.getJSONObject(it)
        Row(r.getString("text"), r.getString("label"), r.getString("time"), r.getString("status"))
    }
    return Proposal(
        o.optBoolean("ok"), o.optNullableString("error"), o.optString("parser"), rows, o.optString("answer", ""),
        if (o.has("id")) o.optNullableString("id") else null,
        o.optInt("changes"), o.optBoolean("awaiting"),
        if (o.optBoolean("autoSaved")) Saved(true, null, o.optInt("saved"), o.optInt("confirmed"), o.optInt("already")) else null,
    )
}

/**
 * /log/parse body: the id lets every later message (save, outcome, a queued
 * resend) find the record; [replyTo] is the record whose question she's answering.
 */
fun encodeParse(id: String, text: String, replyTo: String? = null): String =
    JSONObject().put("id", id).put("text", text).put("autoSave", true).apply { replyTo?.let { put("replyTo", it) } }.toString()

fun encodeUndo(id: String): String = JSONObject().put("id", id).toString()

/** /log/undone: how many rows the phone removed. */
fun decodeUndone(json: String): Pair<Boolean, Int> = JSONObject(json).let { it.optBoolean("ok") to it.optInt("removed") }

/** "3 changes to review on your phone", or "" for none. */
fun changesNote(n: Int): String = when (n) {
    0 -> ""
    1 -> "1 change to review on your phone."
    else -> "$n changes to review on your phone."
}

fun encodeSave(texts: List<String>, id: String? = null, unticked: List<String> = emptyList()): String = JSONObject()
    .put("texts", JSONArray(texts)).put("id", id ?: JSONObject.NULL).put("unticked", JSONArray(unticked)).toString()

fun encodeOutcome(id: String, kind: String): String = JSONObject().put("id", id).put("kind", kind).toString()

fun decodeSaved(json: String): Saved {
    val o = JSONObject(json)
    return Saved(o.optBoolean("ok"), o.optNullableString("error"), o.optInt("saved"), o.optInt("confirmed"), o.optInt("already"))
}

/** What the ✓ screen says, e.g. "Logged 2 · 1 already logged". */
fun savedSummary(s: Saved): String {
    val parts = mutableListOf<String>()
    if (s.saved + s.confirmed > 0) parts += "Logged ${s.saved + s.confirmed}"
    if (s.already > 0) parts += "${s.already} already logged"
    return parts.joinToString(" · ").ifEmpty { "Already logged" }
}

/** Something she said while the phone was out of reach, held until it's back. */
/** [parseId]: the live /log/parse that gave up waiting, so the phone can link the two records. */
data class QueuedItem(val id: String, val text: String, val spokenAtMs: Long, val parseId: String? = null)

data class QueuedAck(val id: String, val ok: Boolean, val duplicate: Boolean)

private fun QueuedItem.toJson(): JSONObject =
    JSONObject().put("id", id).put("text", text).put("spokenAtMs", spokenAtMs).apply { parseId?.let { put("parseId", it) } }

fun encodeQueued(q: QueuedItem): String = q.toJson().toString()

fun decodeQueuedAck(json: String): QueuedAck {
    val o = JSONObject(json)
    return QueuedAck(o.optString("id"), o.optBoolean("ok"), o.optBoolean("duplicate"))
}

/** The ack settles the item (saved now, or saved by an earlier send). */
fun ackSettles(item: QueuedItem, ack: QueuedAck): Boolean = ack.id == item.id && (ack.ok || ack.duplicate)

fun encodeQueue(items: List<QueuedItem>): String =
    JSONArray(items.map { it.toJson() }).toString()

fun decodeQueue(json: String?): List<QueuedItem> = runCatching {
    val arr = JSONArray(json ?: return emptyList())
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        QueuedItem(o.getString("id"), o.getString("text"), o.getLong("spokenAtMs"), o.optNullableString("parseId")?.takeIf { it.isNotBlank() })
    }
}.getOrDefault(emptyList())
