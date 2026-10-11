package com.geoffchan.glucosewidget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The chat screen: Ray talks with Francine and Geoff, with his full toolset. */
val CHAT_INSTRUCTIONS = RAY_CORE + "\n\n" + """
The chat (the Sugar.AI app's chat screen):
- You are chatting with Francine and with Geoff, her husband, who built this app and helps look after her diabetes. Each message starts with who wrote it ("Francine:" or "Geoff:"); both see the whole conversation on their phones. Talk to whoever wrote the latest message; to Geoff, talk about Francine by name. Either of them can log or change her entries through you: propose them exactly as above, and whoever is on the phone confirms.
- You have more tools here. query_data runs a read-only SQL query on the app's database (her readings, journal and this chat). run_analysis runs JavaScript you write over her readings and journal, on her phone. save_report saves an HTML report to the app's Reports screen. Use get_stats, get_lows, get_journal and get_readings for the standard things; reach for query_data and run_analysis when a question needs counting, filtering, comparing periods or a custom calculation. Check your numbers make sense before you use them; if a query or script fails, fix it and run it again.
- Say briefly how you got a number when it isn't obvious ("across the 30 days to Oct 9…").
- Make a report only when they ask for one or for something to keep or show her endocrinologist.

Replying in the chat:
- Finish by calling reply exactly once. Put your whole message in answer and set detail to null. Write like a knowledgeable friend: plain words, short paragraphs; simple "- " lists are fine; no tables or headings. Keep it under about 1,200 characters unless they ask for more.
- If someone only logged something and there's nothing worth adding, answer with a short confirmation of what you proposed.
""".trim()

private val CHAT_CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)

/** The new message as the model sees it: the context that changes, then who said what. */
fun chatUserTurn(author: String, text: String, now: ZonedDateTime, current: String?): String = buildString {
    appendLine("Now: ${CHAT_CLOCK.format(now)} (America/Toronto).")
    appendLine("Francine's current glucose: ${current ?: "unknown"}.")
    appendLine("New entries go on: ${now.toLocalDate()} (today).")
    appendLine("${authorName(author)} is writing in the chat on ${if (author == AUTHOR_FRANCINE) "her" else "his"} phone.")
    appendLine()
    append(authorName(author)).append(": ").append(text.trim())
}

/** Ray's proposed rows as journal texts for [day]; an entry with no time happened [now]. */
fun chatCardEntries(entries: List<ProposedEntry>, now: LocalTime): List<String> =
    entries.mapNotNull { (if (it.time == null) it.copy(time = now.withSecond(0).withNano(0)) else it).noteText() }.distinct()

/** What Ray did, for the message's "how I got this" footer: tool names and their main argument. */
fun toolsUsed(trace: JSONArray): JSONArray {
    val out = JSONArray()
    for (r in 0 until trace.length()) {
        val calls = trace.getJSONObject(r).optJSONArray("calls") ?: continue
        for (c in 0 until calls.length()) {
            val call = calls.getJSONObject(c)
            val name = call.optString("name")
            if (name == AssistantTools.REPLY) continue
            val args = call.opt("args")
            val o = JSONObject().put("name", name)
            when (args) {
                is JSONObject -> {
                    args.optString("sql").takeIf { it.isNotBlank() }?.let { o.put("sql", it) }
                    args.optString("code").takeIf { it.isNotBlank() }?.let { o.put("code", it) }
                    args.optString("title").takeIf { it.isNotBlank() }?.let { o.put("title", it) }
                    listOf("from_day", "to_day", "from", "to").forEach { k -> args.optString(k).takeIf { it.isNotBlank() }?.let { o.put(k, it) } }
                }
            }
            val result = call.optString("result")
            if (result.contains("\"error\"")) o.put("error", runCatching { JSONObject(result).optString("error") }.getOrDefault(result.take(200)))
            out.put(o)
        }
    }
    return out
}

/**
 * Sends a message and gets Ray's reply, off the UI's lifecycle (leaving the
 * screen doesn't drop it). [busy] holds the threads Ray is answering on
 * this phone, for the "Ray is thinking" line.
 */
object RayChat {
    private const val TAG = "RayChat"
    private const val TIMEOUT_MS = 150_000L
    private const val MAX_ROUNDS = 14
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    fun send(context: Context, thread: String, author: String, text: String) {
        val app = context.applicationContext
        scope.launch { runCatching { reply(app, thread, author, text) }.onFailure { Log.w(TAG, "reply failed", it) } }
    }

    /** Posts [text] by [author], then Ray's answer; returns Ray's message. */
    suspend fun reply(context: Context, thread: String, author: String, text: String, zone: ZoneId = ZoneId.systemDefault()): ChatMessageEntity {
        val dao = GlucoseDb.get(context).dao()
        val prior = chatPrior(dao.chatThreadNow(thread))
        ChatStore.post(context, thread, author, text.trim())
        _busy.update { it + thread }
        try {
            return answer(context, thread, author, text, prior, zone)
        } finally {
            _busy.update { it - thread }
        }
    }

    private suspend fun answer(context: Context, thread: String, author: String, text: String, prior: List<JSONObject>, zone: ZoneId): ChatMessageEntity {
        val cfg = Store.aiConfig(context)
            ?: return ChatStore.post(context, thread, AUTHOR_RAY, "I'm not set up on this phone yet (no OpenAI key), so I can't answer here.", meta = JSONObject().put("error", "not configured"))
        if (!Assistant.online(context)) {
            return ChatStore.post(context, thread, AUTHOR_RAY, "I can't reach the internet from this phone right now. Try again in a moment.", meta = JSONObject().put("error", "offline"))
        }
        val now = ZonedDateTime.now(zone)
        val current = InteractionLog.currentReading(context)
        val turn = chatUserTurn(author, text, now, current)
        val trace = JSONArray()
        var report: String? = null
        val interactionId = newUid()
        return try {
            val r = withTimeout(TIMEOUT_MS) {
                Assistant.run(
                    cfg, turn, cfg.effort ?: "medium", Assistant.roomData(context), zone,
                    trace = trace, today = now.toLocalDate(),
                    instructions = CHAT_INSTRUCTIONS,
                    tools = JSONArray().apply {
                        for (i in 0 until AssistantTools.definitions.length()) put(AssistantTools.definitions.get(i))
                        for (i in 0 until RayTools.definitions.length()) put(RayTools.definitions.get(i))
                    },
                    maxRounds = MAX_ROUNDS,
                    prior = prior,
                    maxOutputTokens = 16_000,
                    extraTool = { name, args -> RayEngine.handle(context, name, args, zone) { report = it } },
                )
            }
            val entries = chatCardEntries(r.entries, now.toLocalTime())
            val card = if (entries.isNotEmpty() || r.changes.isNotEmpty()) ChatCard(now.toLocalDate().toString(), entries, r.changes) else null
            val body = loggingWording(listOfNotNull(r.answer.takeIf { it.isNotBlank() }, r.detail).joinToString("\n\n").ifBlank { if (card != null) "Ready to log:" else "(no reply)" })
            val meta = JSONObject()
                .put("tools", toolsUsed(trace))
                .put("model", r.model).put("ms", r.ms).put("costUsd", r.estimatedCostUsd)
                .put("interaction", interactionId)
                .apply { report?.let { put("report", it) } }
            InteractionLog.record(
                context, interactionId, SOURCE_CHAT, text, "openai",
                interactionData(
                    InteractionLog.context(context, now.toLocalDate(), now, turn), listOf(ParseAttempt("openai", true, null, r.ms)),
                    entries.map { ProposalLog(it, WatchProtocol.STATUS_NEW) }, body, null, r.rejected, r, trace,
                ).put("chat", JSONObject().put("thread", thread).put("author", author)),
                outcome = if (card == null) Outcome(OUTCOME_ANSWERED, System.currentTimeMillis()) else null,
            )
            ChatStore.post(context, thread, AUTHOR_RAY, body, card = card, meta = meta)
        } catch (e: Exception) {
            val why = if (e is kotlinx.coroutines.TimeoutCancellationException) "took too long" else e.message ?: e.javaClass.simpleName
            Log.w(TAG, "ask failed: $why")
            InteractionLog.record(
                context, interactionId, SOURCE_CHAT, text, "none",
                interactionData(InteractionLog.context(context, now.toLocalDate(), now, turn), listOf(ParseAttempt("openai", false, why)), emptyList(), trace = trace, error = why)
                    .put("chat", JSONObject().put("thread", thread).put("author", author)),
            )
            ChatStore.post(
                context, thread, AUTHOR_RAY, "Sorry, I couldn't finish that ($why). Try asking again.",
                meta = JSONObject().put("error", why).put("tools", toolsUsed(trace)).apply { report?.let { put("report", it) } },
            )
        }
    }
}

/** Chat message writes: Room, then the other phone. */
object ChatStore {
    suspend fun post(
        context: Context,
        thread: String,
        author: String,
        text: String,
        card: ChatCard? = null,
        meta: JSONObject? = null,
    ): ChatMessageEntity {
        val now = System.currentTimeMillis()
        val m = ChatMessageEntity(
            thread = thread, createdAtMs = now, updatedAtMs = now, author = author, text = text,
            card = card?.let { encodeCard(it) }, meta = meta?.toString(),
        )
        GlucoseDb.get(context).dao().upsertChat(m)
        Sync.chatChanged(context, m.uid)
        return m
    }

    /**
     * Saves the ticked parts of a card: new entries on the card's day (a
     * guess or auto-logged routine row is updated, an exact duplicate
     * skipped — [planWatchSave]) and changes on other days. The card then
     * says who saved what, on both phones.
     */
    suspend fun saveCard(context: Context, uid: String, entries: List<String>, changes: List<ChangeOp>, by: String): String {
        val dao = GlucoseDb.get(context).dao()
        val m = dao.chatMessage(uid) ?: return "That message is gone."
        val card = decodeCard(m.card) ?: return "Nothing to save."
        if (card.state != CARD_OPEN) return card.result ?: "Already done."
        val now = System.currentTimeMillis()
        val items = mutableListOf<SavedItem>()
        val existing = dao.dayJournalBetween(card.day, card.day)
        for (op in planWatchSave(entries, existing)) when (op) {
            is WatchSaveOp.Insert -> {
                val row = JournalEntity(day = card.day, text = op.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY)
                Journal.insert(context, row)
                items += SavedItem(op.text, op.text, row.uid, "insert")
            }
            is WatchSaveOp.Confirm -> {
                Journal.update(context, op.guess.copy(text = op.text, updatedAtMs = now))
                items += SavedItem(op.text, op.text, op.guess.uid, "confirm")
            }
            is WatchSaveOp.Already -> items += SavedItem(op.text, op.text, null, "already")
        }
        items += Journal.applyChanges(context, changes)
        GlucoseWidget().updateAll(context)
        val written = items.count { it.op != "already" && !it.op.startsWith("skipped") }
        val result = if (written == 0) "Nothing new to save" else "Saved $written"
        dao.upsertChat(m.copy(card = encodeCard(card.copy(state = CARD_SAVED, by = by, result = result)), updatedAtMs = maxOf(now, m.updatedAtMs + 1)))
        Sync.chatChanged(context, m.uid)
        val unticked = card.entries.filter { it !in entries } + card.changes.filter { it !in changes }.map { changeLogText(it) }
        metaInteraction(m)?.let { InteractionLog.setOutcome(context, it, Outcome(OUTCOME_SAVED, now, items, unticked, note = "chat by $by")) }
        return result
    }

    suspend fun dismissCard(context: Context, uid: String, by: String) {
        val dao = GlucoseDb.get(context).dao()
        val m = dao.chatMessage(uid) ?: return
        val card = decodeCard(m.card)?.takeIf { it.state == CARD_OPEN } ?: return
        val now = System.currentTimeMillis()
        dao.upsertChat(m.copy(card = encodeCard(card.copy(state = CARD_DISMISSED, by = by)), updatedAtMs = maxOf(now, m.updatedAtMs + 1)))
        Sync.chatChanged(context, m.uid)
        metaInteraction(m)?.let { InteractionLog.setOutcome(context, it, Outcome(OUTCOME_CANCELLED, now, note = "chat by $by")) }
    }

    private fun metaInteraction(m: ChatMessageEntity): String? =
        m.meta?.let { runCatching { JSONObject(it).optString("interaction").takeIf { s -> s.isNotBlank() } }.getOrNull() }
}
