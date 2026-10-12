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
        val server = Store.rayServer(context)
        val cfg = Store.aiConfig(context)
        if (server == null && cfg == null) {
            return ChatStore.post(context, thread, AUTHOR_RAY, "I'm not set up on this phone yet (no Ray server or OpenAI key), so I can't answer here.", meta = JSONObject().put("error", "not configured"))
        }
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
                if (server != null) {
                    try {
                        val (r, reports) = Assistant.onServer(
                            context, server, MODE_CHAT, turn, cfg?.effort ?: "medium", prior, now.toLocalDate(), zone, MAX_ROUNDS, 16_000, trace,
                        )
                        // reports Ray saved on the server live on the phone that asked, like before
                        for (rep in reports) java.io.File(reportsDir(context), rep.name).writeText(rep.html)
                        reports.lastOrNull()?.let { report = it.name }
                        return@withTimeout r
                    } catch (e: RayServer.RayServerException) {
                        if (cfg == null || !e.fallBack) throw e
                        Log.w(TAG, "server failed (${e.message}); answering on the phone")
                        trace.put(JSONObject().put("serverFailed", e.message))
                    }
                }
                Assistant.run(
                    cfg!!, turn, cfg.effort ?: "medium", Assistant.roomData(context), zone,
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
    /** Rename or delete a thread on both phones (a [AUTHOR_SYSTEM] event; see [chatThreads]). */
    suspend fun threadEvent(context: Context, thread: String, rename: String? = null, deleted: Boolean = false) {
        post(context, thread, AUTHOR_SYSTEM, "", meta = threadEventMeta(rename, deleted))
    }

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
