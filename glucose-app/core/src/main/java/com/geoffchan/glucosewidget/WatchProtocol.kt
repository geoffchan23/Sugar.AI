package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Messages between her watch and the main phone (Wearable MessageClient).
 * The watch module keeps a mirror of these paths and field names in
 * wear/…/Protocol.kt — change both together.
 *
 *   watch → phone  /log/parse     {"id","text"} (older watches: the bare UTF-8 transcript)
 *   watch → phone  /log/parse     {"id","text","replyTo"?,"autoSave"?}  replyTo: the record whose question she's answering;
 *                                 autoSave (watches from 2026-10-11): save the rows now, no confirm screen
 *   phone → watch  /log/proposal  … plus "autoSaved" (the rows are already in the journal) and the
 *                                 saved/confirmed/already counts; the watch shows ✓ with Undo
 *   watch → phone  /log/undo      {"id"}  remove what record id saved (logged as corrections)
 *   phone → watch  /log/undone    {"ok","error","removed"}
 *   phone → watch  /log/proposal  {"ok","error","parser","answer","id","rows":[{text,label,time,status}],"changes","awaiting"}
 *                  answer: the assistant's watch-sized reply ("" when none; then rows only)
 *                  id: the interaction log record (the watch's id echoed, or a new one)
 *                  changes: how many edits to past entries/other days await review on the phone
 *                  awaiting: the answer is a question; the watch offers Reply
 *   watch → phone  /log/save      {"texts":[...],"id","unticked":[...]}  (canonical texts, as proposed;
 *                                 id + unticked are optional and only feed the interaction log)
 *   phone → watch  /log/saved     {"ok","error","saved","confirmed","already"}
 *   watch → phone  /log/outcome   {"id","kind"}  no reply; cancelled / ask-again / answered, for the log
 *
 * Said while the phone was out of reach (no confirm screen was possible):
 *   watch → phone  /log/queued     {"id","text","spokenAtMs","parseId"?}  (parseId: the /log/parse that timed out)
 *   phone → watch  /log/queued-ack {"id","ok","saved","duplicate"}
 * The phone saves those rows as guesses; a resend of the same id is acked
 * without saving again.
 */
object WatchProtocol {
    const val PATH_PARSE = "/log/parse"
    const val PATH_PROPOSAL = "/log/proposal"
    const val PATH_SAVE = "/log/save"
    const val PATH_SAVED = "/log/saved"
    const val PATH_QUEUED = "/log/queued"
    const val PATH_QUEUED_ACK = "/log/queued-ack"
    const val PATH_OUTCOME = "/log/outcome"
    const val PATH_UNDO = "/log/undo"
    const val PATH_UNDONE = "/log/undone"

    const val STATUS_NEW = "new"
    const val STATUS_ALREADY = "already" // the same entry is already logged; saving it does nothing
    const val STATUS_CONFIRM = "confirm" // saving confirms one of Claude's guesses
    const val STATUS_REPLACE = "replace" // saving replaces an auto-logged routine row with her values
}

/** How saving [text] relates to its [match] in the day's log (see [matchExisting]). */
fun matchStatus(text: String?, match: JournalEntity?): String = when {
    match == null -> WatchProtocol.STATUS_NEW
    isGuessEntry(match.text) -> WatchProtocol.STATUS_CONFIRM
    match.text != text && isRoutineEntry(match) -> WatchProtocol.STATUS_REPLACE
    else -> WatchProtocol.STATUS_ALREADY
}

data class WatchRow(val text: String, val label: String, val time: String, val status: String)

/** Watch-sized label: "4u short", "19u long", "chicken burger". */
fun watchLabel(p: ProposedEntry): String =
    if (p.isDose) "${p.units}u ${if (p.insulinType == "long-acting") "long" else "short"}" else p.name.orEmpty()

/** The proposal row for [p] against today's [existing] journal (see [matchExisting]). */
fun watchRow(p: ProposedEntry, existing: List<JournalEntity>): WatchRow? {
    val text = p.noteText() ?: return null
    val status = matchStatus(text, matchExisting(p, existing))
    return WatchRow(text, watchLabel(p), time12(p.time!!), status)
}

fun encodeProposal(
    rows: List<WatchRow>, parser: String, error: String? = null, answer: String = "", id: String? = null,
    changes: Int = 0, awaiting: Boolean = false, saved: SaveCounts? = null,
): String = JSONObject()
    .put("ok", error == null)
    .put("error", error ?: JSONObject.NULL)
    .put("parser", parser)
    .put("answer", answer)
    .put("id", id ?: JSONObject.NULL)
    .put("rows", JSONArray(rows.map { JSONObject().put("text", it.text).put("label", it.label).put("time", it.time).put("status", it.status) }))
    .put("changes", changes)
    .put("awaiting", awaiting)
    .apply {
        if (saved != null) put("autoSaved", true).put("saved", saved.saved).put("confirmed", saved.confirmed).put("already", saved.already)
    }
    .toString()

data class SaveCounts(val saved: Int, val confirmed: Int, val already: Int)

fun encodeUndone(removed: Int, error: String? = null): String = JSONObject()
    .put("ok", error == null).put("error", error ?: JSONObject.NULL).put("removed", removed).toString()

fun encodeSave(texts: List<String>, id: String? = null, unticked: List<String> = emptyList()): String = JSONObject()
    .put("texts", JSONArray(texts)).put("id", id ?: JSONObject.NULL).put("unticked", JSONArray(unticked)).toString()

/** The interaction-log half of a /log/save: which record, and the rows she unticked. */
data class SaveMeta(val id: String?, val unticked: List<String>)

fun decodeSaveMeta(json: String): SaveMeta = runCatching {
    val o = JSONObject(json)
    val u = o.optJSONArray("unticked") ?: JSONArray()
    SaveMeta(
        if (o.isNull("id")) null else o.optString("id").takeIf { it.isNotBlank() },
        (0 until u.length()).map { u.getString(it) },
    )
}.getOrDefault(SaveMeta(null, emptyList()))

/** Texts to save; anything that isn't a canonical dose/event line is dropped. */
fun decodeSave(json: String): List<String> = runCatching {
    val arr = JSONObject(json).getJSONArray("texts")
    (0 until arr.length()).map { arr.getString(it) }
}.getOrDefault(emptyList()).filter { proposedFromText(it) != null }

fun encodeSaved(saved: Int, confirmed: Int, already: Int, error: String? = null): String = JSONObject()
    .put("ok", error == null)
    .put("error", error ?: JSONObject.NULL)
    .put("saved", saved).put("confirmed", confirmed).put("already", already)
    .toString()

/** Back from canonical text to a proposal, for matching at save time. Guesses aren't accepted. */
fun proposedFromText(text: String): ProposedEntry? {
    if (isGuessEntry(text)) return null
    parseDoseNote(text)?.let { return ProposedEntry(isDose = true, insulinType = it.insulinType, units = it.units, time = it.time) }
    parseEventNote(text)?.let { return ProposedEntry(isDose = false, name = it.name, time = it.time) }
    return null
}

/** What saving [texts] does to today's [existing] journal. */
sealed class WatchSaveOp {
    data class Insert(val text: String) : WatchSaveOp()
    data class Confirm(val guess: JournalEntity, val text: String) : WatchSaveOp()
    data class Already(val text: String) : WatchSaveOp()
}

fun planWatchSave(texts: List<String>, existing: List<JournalEntity>): List<WatchSaveOp> {
    val seen = existing.toMutableList()
    return texts.mapNotNull { text ->
        val p = proposedFromText(text) ?: return@mapNotNull null
        val match = matchExisting(p, seen)
        when (matchStatus(text, match)) {
            WatchProtocol.STATUS_NEW -> WatchSaveOp.Insert(text).also {
                // two identical rows in one utterance shouldn't both insert
                seen += JournalEntity(day = "", text = text, createdAtMs = 0, updatedAtMs = 0)
            }
            WatchProtocol.STATUS_CONFIRM, WatchProtocol.STATUS_REPLACE -> WatchSaveOp.Confirm(match!!, text).also {
                // the updated row is hers now: a second match adds, not replaces again
                seen.remove(match); seen += match.copy(day = "", text = text)
            }
            else -> WatchSaveOp.Already(text)
        }
    }
}

/** One entry the watch held while the phone was unreachable. */
data class QueuedEntry(val id: String, val text: String, val spokenAtMs: Long, val parseId: String? = null)

fun decodeQueued(json: String): QueuedEntry? = runCatching {
    val o = JSONObject(json)
    val parseId = if (o.isNull("parseId")) null else o.optString("parseId").takeIf { it.isNotBlank() }
    QueuedEntry(o.getString("id"), o.getString("text"), o.getLong("spokenAtMs"), parseId).takeIf { it.id.isNotBlank() }
}.getOrNull()

fun encodeQueuedAck(id: String, saved: Int, duplicate: Boolean, error: String? = null): String = JSONObject()
    .put("id", id).put("ok", error == null).put("error", error ?: JSONObject.NULL)
    .put("saved", saved).put("duplicate", duplicate)
    .toString()

/**
 * Idempotency for queued entries: the new remembered-id list if [id] is
 * new (oldest dropped past [keep]), or null if it was already saved.
 */
fun rememberQueuedId(recent: List<String>, id: String, keep: Int = 50): List<String>? =
    if (id in recent) null else (recent + id).takeLast(keep)

/**
 * Journal texts for a queued entry: each row as a guess (no one confirmed
 * it on the watch), minus anything [existing] already covers — a confirmed
 * entry or an earlier guess of the same thing.
 */
fun planQueuedSave(rows: List<ProposedEntry>, existing: List<JournalEntity>): List<String> {
    val seen = existing.toMutableList()
    return rows.mapNotNull { p ->
        val text = p.noteText() ?: return@mapNotNull null
        if (matchExisting(p, seen) != null) return@mapNotNull null
        seen += JournalEntity(day = "", text = text, createdAtMs = 0, updatedAtMs = 0)
        text + GUESS_SUFFIX
    }
}
