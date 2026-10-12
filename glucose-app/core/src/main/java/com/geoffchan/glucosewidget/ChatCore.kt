package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of the chat with Ray: who's talking, the card Ray attaches
 * when he proposes entries or changes, the thread as model input, and the
 * sync form. [ChatStore] and [RayChat] do the I/O; ChatActivity is the UI.
 *
 * Both phones share every thread: messages travel through the same
 * encrypted relay as the journal ("chat" in a sync payload) and merge by
 * uid, last write wins (a card's saved state is the only thing that
 * changes after a message is sent).
 */
const val AUTHOR_FRANCINE = "francine"
const val AUTHOR_GEOFF = "geoff"
const val AUTHOR_RAY = "ray"
/**
 * Thread events, not messages: a rename (meta {"rename": title}) or a
 * delete (meta {"deleted": true}). They ride the chat sync like any
 * message, so both phones agree; never shown, never sent to the model.
 */
const val AUTHOR_SYSTEM = "system"

fun threadEventMeta(rename: String? = null, deleted: Boolean = false): JSONObject =
    JSONObject().apply { rename?.let { put("rename", it) }; if (deleted) put("deleted", true) }

/** A real message (not a [AUTHOR_SYSTEM] thread event). */
val ChatMessageEntity.isMessage: Boolean get() = author != AUTHOR_SYSTEM

fun authorName(author: String): String = when (author) {
    AUTHOR_FRANCINE -> "Francine"
    AUTHOR_GEOFF -> "Geoff"
    AUTHOR_RAY -> "Ray"
    else -> author.replaceFirstChar { it.uppercase() }
}

/** Card states: proposals waiting, saved (some ticked rows written), or dismissed. */
const val CARD_OPEN = "open"
const val CARD_SAVED = "saved"
const val CARD_DISMISSED = "dismissed"

/**
 * What Ray proposed in one reply: new [entries] (journal note texts) for
 * [day], and [changes] on any of the last 14 days. Once someone acts on it,
 * [state], [by] (author) and [result] ("Saved 3") say so on both phones.
 */
data class ChatCard(
    val day: String,
    val entries: List<String>,
    val changes: List<ChangeOp>,
    val state: String = CARD_OPEN,
    val by: String? = null,
    val result: String? = null,
)

fun encodeCard(c: ChatCard): String = JSONObject()
    .put("day", c.day)
    .put("entries", JSONArray(c.entries))
    .put("changes", encodeChanges(c.changes))
    .put("state", c.state)
    .put("by", c.by ?: JSONObject.NULL)
    .put("result", c.result ?: JSONObject.NULL)
    .toString()

fun decodeCard(json: String?): ChatCard? = json?.let {
    runCatching {
        val o = JSONObject(it)
        val e = o.optJSONArray("entries") ?: JSONArray()
        ChatCard(
            day = o.getString("day"),
            entries = (0 until e.length()).map { i -> e.getString(i) },
            changes = decodeChanges(o.optJSONArray("changes")),
            state = o.optString("state", CARD_OPEN),
            by = o.optString("by").takeIf { s -> !o.isNull("by") && s.isNotBlank() },
            result = o.optString("result").takeIf { s -> !o.isNull("result") && s.isNotBlank() },
        )
    }.getOrNull()
}

/** One line for the model: what the card offered and what became of it. */
fun cardSummary(c: ChatCard): String = buildString {
    val items = c.entries.map { entryLabel(it) } + c.changes.map { changeLabel(it) }
    append("[Proposed for ${c.day}: ${items.joinToString("; ")}. ")
    append(
        when (c.state) {
            CARD_SAVED -> "${authorName(c.by ?: "")} saved it${c.result?.let { " ($it)" } ?: ""}."
            CARD_DISMISSED -> "${authorName(c.by ?: "")} dismissed it; nothing was saved."
            else -> "Not saved yet."
        },
    )
    append("]")
}

/** Cap on the thread replayed to the model: the most recent messages within this many characters. */
const val CHAT_PRIOR_CHARS = 24_000

/**
 * The thread before the new message, as Responses API input items:
 * people's messages as user turns ("Geoff: …"), Ray's as assistant turns
 * with any card's outcome appended. Oldest dropped first past [maxChars].
 */
fun chatPrior(messages: List<ChatMessageEntity>, maxChars: Int = CHAT_PRIOR_CHARS): List<JSONObject> {
    val items = messages.filter { it.isMessage }.map { m ->
        if (m.author == AUTHOR_RAY) {
            val card = decodeCard(m.card)?.let { "\n" + cardSummary(it) }.orEmpty()
            JSONObject().put("role", "assistant").put("content", (m.text.ifBlank { "(no reply)" } + card))
        } else {
            JSONObject().put("role", "user").put("content", "${authorName(m.author)}: ${m.text.trim()}")
        }
    }
    val kept = ArrayDeque<JSONObject>()
    var total = 0
    for (item in items.asReversed()) {
        val n = item.getString("content").length
        if (kept.isNotEmpty() && total + n > maxChars) break
        kept.addFirst(item); total += n
    }
    // a replay must not start with Ray talking to nobody
    while (kept.isNotEmpty() && kept.first().getString("role") == "assistant") kept.removeFirst()
    return kept.toList()
}

/** A thread for the list: its id, a title from the first message, the latest activity. */
data class ChatThreadSummary(val thread: String, val title: String, val lastAtMs: Long, val lastLine: String, val count: Int)

fun chatThreads(all: List<ChatMessageEntity>): List<ChatThreadSummary> =
    all.groupBy { it.thread }.mapNotNull { (thread, ms) ->
        val events = ms.filter { !it.isMessage }.sortedBy { it.createdAtMs }.map { runCatching { JSONObject(it.meta ?: "{}") }.getOrDefault(JSONObject()) }
        if (events.any { it.optBoolean("deleted") }) return@mapNotNull null
        val sorted = ms.filter { it.isMessage }.sortedBy { it.createdAtMs }
        if (sorted.isEmpty()) return@mapNotNull null
        val first = sorted.firstOrNull { it.author != AUTHOR_RAY } ?: sorted.first()
        val last = sorted.last()
        val renamed = events.lastOrNull { it.has("rename") }?.optString("rename")?.takeIf { it.isNotBlank() }
        ChatThreadSummary(
            thread = thread,
            title = renamed ?: first.text.trim().replace(Regex("""\s+"""), " ").let { if (it.length > 60) it.take(57) + "…" else it },
            lastAtMs = last.createdAtMs,
            lastLine = "${authorName(last.author)}: ${last.text.trim().replace(Regex("""\s+"""), " ").take(80)}",
            count = sorted.size,
        )
    }.sortedByDescending { it.lastAtMs }

/** ChatGPT-style buckets for the history list: Today, Yesterday, Previous 7 days, then month names. */
fun threadGroup(lastAtMs: Long, today: java.time.LocalDate, zone: java.time.ZoneId): String {
    val d = java.time.Instant.ofEpochMilli(lastAtMs).atZone(zone).toLocalDate()
    return when {
        d >= today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d > today.minusDays(8) -> "Previous 7 days"
        d.year == today.year -> d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CANADA)
        else -> "${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CANADA)} ${d.year}"
    }
}

// ---------------------------------------------------------------- sync

data class SyncChat(
    val uid: String,
    val thread: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val author: String,
    val text: String,
    val card: String?,
    val meta: String?,
)

fun ChatMessageEntity.toSyncChat() = SyncChat(uid, thread, createdAtMs, updatedAtMs, author, text, card, meta)

fun SyncChat.toEntity() = ChatMessageEntity(uid, thread, createdAtMs, updatedAtMs, author, text, card, meta)

fun encodeSyncChat(c: SyncChat): JSONObject = JSONObject()
    .put("uid", c.uid).put("thread", c.thread).put("createdAtMs", c.createdAtMs).put("updatedAtMs", c.updatedAtMs)
    .put("author", c.author).put("text", c.text)
    .put("card", c.card ?: JSONObject.NULL).put("meta", c.meta ?: JSONObject.NULL)

fun decodeSyncChat(o: JSONObject): SyncChat = SyncChat(
    o.getString("uid"), o.getString("thread"), o.getLong("createdAtMs"), o.getLong("updatedAtMs"),
    o.getString("author"), o.getString("text"),
    o.optString("card").takeIf { !o.isNull("card") && it.isNotEmpty() },
    o.optString("meta").takeIf { !o.isNull("meta") && it.isNotEmpty() },
)

/** A chat message's encoded size must leave room for the payload envelope in one relay message. */
const val CHAT_SYNC_BUDGET = 2_400

private fun chatBytes(c: SyncChat) = encodeSyncChat(c).toString().toByteArray(Charsets.UTF_8).size

/**
 * Fit one message into a relay message: drop the code from its tool list,
 * then its meta, then shorten the text (the full text stays on the phone
 * that wrote it). The card is always kept — the other phone may save it.
 */
fun trimChatForSync(m: ChatMessageEntity, budget: Int = CHAT_SYNC_BUDGET): SyncChat {
    var c = m.toSyncChat()
    if (chatBytes(c) <= budget) return c
    c = c.copy(meta = c.meta?.let { slimMeta(it) })
    if (chatBytes(c) <= budget) return c
    c = c.copy(meta = null)
    if (chatBytes(c) <= budget) return c
    val note = "… (shortened; the full message is on the phone that wrote it)"
    var keep = c.text.length
    while (keep > 0) {
        keep = keep * 3 / 4
        val t = c.copy(text = c.text.take(keep).trimEnd() + note)
        if (chatBytes(t) <= budget) return t
    }
    return c.copy(text = note)
}

/** Meta without code or long arguments: tool names and the headline numbers only. */
private fun slimMeta(meta: String): String? = runCatching {
    val o = JSONObject(meta)
    o.optJSONArray("tools")?.let { tools ->
        o.put("tools", JSONArray((0 until tools.length()).map { i -> JSONObject().put("name", tools.getJSONObject(i).optString("name")) }))
    }
    o.toString()
}.getOrNull()

/** Last write wins on updatedAtMs; a tie keeps the local copy. */
fun chatShouldApply(remote: SyncChat, local: ChatMessageEntity?): Boolean =
    local == null || remote.updatedAtMs > local.updatedAtMs

/**
 * When the remote copy is a trimmed version of a message this phone wrote
 * (same uid, newer only because a card was saved), keep the local text and
 * meta and take the card.
 */
fun mergeChat(remote: SyncChat, local: ChatMessageEntity?): ChatMessageEntity? {
    if (!chatShouldApply(remote, local)) return null
    if (local == null) return remote.toEntity()
    val keepText = remote.text.length < local.text.length && local.text.startsWith(remote.text.substringBefore("… (shortened").trimEnd())
    return local.copy(
        updatedAtMs = remote.updatedAtMs,
        text = if (keepText) local.text else remote.text,
        card = remote.card ?: local.card,
        meta = if (remote.meta == null || (local.meta?.length ?: 0) > remote.meta.length) local.meta else remote.meta,
    )
}

/**
 * "Proposed 2 units…" in a notification reads like Ray chose the dose
 * (Geoff, 2026-10-10). Logging replies say "Ready to log" instead.
 */
fun loggingWording(text: String): String =
    text.replace(Regex("""^\s*(I've |I have )?proposed:?\s*""", RegexOption.IGNORE_CASE), "Ready to log: ")
