package com.geoffchan.glucosewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only ADB entry point for Claude Code backfills. Inserting through
 * Room (instead of editing the SQLite file externally) keeps WAL
 * consistency while the app runs.
 *
 *   adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
 *     --es op insert --es day 2026-09-01 --es text "event: coffee @ 10:30"
 *
 *   adb shell am broadcast -n .../.IngestReceiver --es op delete --es day 2026-09-01 --el id 42
 *   adb shell am broadcast -n .../.IngestReceiver --es op report-ready --es day x --es name report-...html
 *
 *   adb shell am broadcast -n .../.IngestReceiver --es op describe-test --es day x --es text "'coffee and 19+4'"
 *     → logcat tag "Describe": Gemini Nano status, raw output and the validated
 *       rows. Read-only; nothing is saved. (AICore may refuse while the app
 *       is in the background — the log says so.)
 *
 *   adb shell am broadcast -n .../.IngestReceiver --es op watch-parse-test --es text "'took 6 and a chicken burger'"
 *     → logcat tag "WatchLog": the exact proposal her watch would get (same
 *       code path: Nano in-process, then over the lock screen, else rules).
 *       Read-only. Needs the main phone.
 *
 * Two-phone sync (see [Sync]); the same key + topic go to both phones:
 *   … --es op sync-setup --es key <base64 32 bytes> --es topic <secret> --ez main true|false
 *   … --es op sync-now      (publish outbox + poll)
 *   … --es op sync-resend   (re-publish everything changed in the last 30 days)
 *   … --es op sync-reset    (forget key, topic and sync state)
 * Inserts/deletes here go through [Journal], so they sync like app edits.
 *
 * Ray server (cloud Ray, see [RayServer]); the device token travels as a file:
 *   adb exec-in run-as <pkg> sh -c 'cat > files/ray-handoff' < token-file
 *   … --es op ray-setup [--es url https://…] [--ez main true|false]   (sync + Ray go through the server)
 *   … --es op ray-clear
 *   … --es op voice-test --es text '…'   (logcat tag "Voice"; a spoken log is saved like Alexa's)
 *
 * Assistant (OpenAI, see [Assistant]); the key travels as a file, never an extra:
 *   adb exec-in run-as <pkg> sh -c 'cat > files/ai-handoff' < key-file
 *   … --es op ai-setup [--es model gpt-6-luna] [--es effort low|medium|auto]
 *   … --es op ai-clear
 *   … --es op ask-test --es text '…' [--ez watch true] [--es replyTo <id>]
 *     (runs it, saves nothing, logs to tag Assistant with the record id; replyTo continues that conversation)
 *
 * Delete is by single row id only; there is deliberately no bulk delete,
 * since `event:` rows are user-logged data.
 */
class IngestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val day = intent.getStringExtra("day") ?: "" // only insert/delete use it
        val dao = GlucoseDb.get(context).dao()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (op) {
                    "insert" -> {
                        if (day.isEmpty()) return@launch
                        val text = intent.getStringExtra("text") ?: return@launch
                        val now = System.currentTimeMillis()
                        Journal.insert(
                            context,
                            JournalEntity(day = day, text = text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY),
                        )
                        GlucoseWidget().updateAll(context)
                    }
                    "delete" -> {
                        val id = intent.getLongExtra("id", -1L)
                        dao.journalById(id)?.let { Journal.delete(context, it) }
                        GlucoseWidget().updateAll(context)
                    }
                    "report-ready" -> intent.getStringExtra("name")?.let { ReportNotification.show(context, it) }
                    // Moving phones: hand the Dexcom login from one phone to the other
                    // through app-private files (adb exec-out | exec-in), never on screen.
                    "creds-export" -> Store.credentials(context)?.let { (u, pw) ->
                        java.io.File(context.filesDir, CREDS_HANDOFF).writeText("$u\n$pw")
                    }
                    "creds-import" -> java.io.File(context.filesDir, CREDS_HANDOFF).let { f ->
                        val lines = f.takeIf { it.exists() }?.readLines().orEmpty()
                        if (lines.size >= 2) {
                            Store.saveCredentials(context, lines[0], lines[1])
                            Refresh.enqueue(context)
                        }
                        f.delete()
                    }
                    "creds-clear" -> java.io.File(context.filesDir, CREDS_HANDOFF).delete()
                    "refresh" -> Refresh.enqueue(context)
                    "sync-setup" -> {
                        val ok = Sync.setup(
                            context,
                            intent.getStringExtra("key").orEmpty(),
                            intent.getStringExtra("topic").orEmpty(),
                            if (intent.hasExtra("main")) intent.getBooleanExtra("main", true) else null,
                        )
                        android.util.Log.i("Sync", if (ok) "configured" else "sync-setup rejected: need a base64 32-byte key and a 16+ char topic")
                    }
                    "sync-now" -> Sync.enqueue(context, now = true)
                    "sync-resend" -> { Sync.queueRecent(context, Sync.RESEND_WINDOW_MS); Sync.enqueue(context) }
                    "sync-reset" -> { Sync.reset(context); android.util.Log.i("Sync", "reset") }
                    // Assistant key: pushed into files/ai-handoff with adb exec-in (never an
                    // intent extra, so it never shows in logs or `ps`), imported, deleted.
                    "ai-setup" -> {
                        val f = java.io.File(context.filesDir, AI_HANDOFF)
                        val key = f.takeIf { it.exists() }?.readText()?.lineSequence()
                            ?.map { it.trim().removePrefix("OPENAI_API_KEY=").trim() }?.firstOrNull { it.isNotEmpty() }
                        f.delete()
                        Store.saveAiConfig(context, key, intent.getStringExtra("model"), intent.getStringExtra("effort"))
                        val cfg = Store.aiConfig(context)
                        android.util.Log.i("Assistant", if (cfg != null) "configured: model=${cfg.model} effort=${cfg.effort ?: "auto"}" else "ai-setup: no key (push files/$AI_HANDOFF first)")
                    }
                    "ai-clear" -> { Store.clearAiConfig(context); android.util.Log.i("Assistant", "cleared") }
                    // Ray server (cloud Ray): this phone's device token goes in files/ray-handoff the same way
                    "ray-setup" -> {
                        val f = java.io.File(context.filesDir, RAY_HANDOFF)
                        val token = f.takeIf { it.exists() }?.readText()?.lineSequence()
                            ?.map { it.trim().substringAfter('=').trim() }?.firstOrNull { it.length >= 32 }
                        f.delete()
                        if (token == null) android.util.Log.i("Sync", "ray-setup: no token (push files/$RAY_HANDOFF first)")
                        else {
                            Sync.setupServer(context, intent.getStringExtra("url") ?: DEFAULT_RAY_URL, token,
                                if (intent.hasExtra("main")) intent.getBooleanExtra("main", true) else null)
                            android.util.Log.i("Sync", "ray server configured")
                        }
                    }
                    "ray-clear" -> { Store.clearRayServer(context); Store.saveRayCursor(context, null); android.util.Log.i("Sync", "ray server cleared") }
                    // a spoken question/log through the server, as the voice shortcuts send it (logs the speech; may save entries)
                    "voice-test" -> Store.rayServer(context)?.let { cfg ->
                        android.util.Log.i("Voice", "voice-test → " + runCatching { RayServer.voice(cfg, intent.getStringExtra("text").orEmpty()) }.getOrElse { "failed: ${it.message}" })
                    }
                    // Runs the assistant on --es text without saving anything; logs the result.
                    // chat as someone, e.g. --es text "how was last week?" --es author geoff [--es thread <id>]; logs Ray's reply
                    "chat-test" -> chatTest(context, intent.getStringExtra("text").orEmpty(), intent.getStringExtra("author") ?: AUTHOR_GEOFF, intent.getStringExtra("thread"))
                    // remove a test thread from both phones (a delete event, like the drawer's Delete)
                    "chat-thread-delete" -> intent.getStringExtra("thread")?.let { ChatStore.threadEvent(context, it, deleted = true) }
                    "chat-author" -> intent.getStringExtra("author")?.takeIf { it == AUTHOR_FRANCINE || it == AUTHOR_GEOFF }?.let { Store.saveChatAuthor(context, it) }
                    "ask-test" -> askTest(context, intent.getStringExtra("text").orEmpty(), intent.getBooleanExtra("watch", false), intent.getStringExtra("replyTo"))
                    "describe-test" -> describeTest(context, intent.getStringExtra("text").orEmpty())
                    "watch-parse-test" -> android.util.Log.i(
                        WatchParse.TAG,
                        "watch-parse-test → " + WatchParse.propose(
                            context, intent.getStringExtra("text").orEmpty(),
                            requireMain = !intent.getBooleanExtra("any", false), // --ez any true: test on the peer phone
                            source = SOURCE_DEBUG,
                        ),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Runs the assistant without saving; recorded in the interaction log as source=debug. */
    /** A real chat turn (it's saved and syncs like any message); logcat tag "ChatTest" shows the reply. */
    private suspend fun chatTest(context: Context, text: String, author: String, thread: String?) {
        if (text.isBlank()) return
        val t = thread ?: newUid()
        val m = RayChat.reply(context, t, author, text)
        val log = { msg: String -> android.util.Log.i("ChatTest", msg) }
        log("thread: $t")
        m.text.lines().forEach { log("ray: $it") }
        decodeCard(m.card)?.let { c -> (c.entries + c.changes.map { changeLogText(it) }).forEach { log("card: $it") } }
        m.meta?.let { log("meta: ${it.take(3000)}") }
    }

    private suspend fun askTest(context: Context, text: String, fromWatch: Boolean, replyTo: String?) {
        val today = java.time.LocalDate.now()
        val trace = org.json.JSONArray()
        val t0 = System.currentTimeMillis()
        val history = InteractionLog.history(context, replyTo)
        val id = newUid()
        try {
            val r = Assistant.ask(context, text, today, fromWatch, timeoutMs = 50_000, trace = trace, history = history)
            android.util.Log.i("Assistant", "ask-test id: $id (history ${history.size})")
            android.util.Log.i("Assistant", "ask-test answer: ${r.answer}" + if (r.awaitingAnswer) " [awaiting answer]" else "")
            r.detail?.let { android.util.Log.i("Assistant", "ask-test detail: $it") }
            android.util.Log.i("Assistant", "ask-test rows: ${r.entries.map { it.noteText() ?: "$it (no time)" }} rejected=${r.rejected}")
            r.changes.forEach { android.util.Log.i("Assistant", "ask-test change: ${changeLogText(it)}") }
            r.changeRejects.forEach { android.util.Log.i("Assistant", "ask-test change rejected: $it") }
            InteractionLog.record(
                context, id, SOURCE_DEBUG, text, "openai",
                interactionData(
                    InteractionLog.context(context, today, userTurn = r.userTurn), listOf(ParseAttempt("openai", true, null, r.ms)),
                    r.entries.map { ProposalLog(it.noteText() ?: "${it.name ?: it.insulinType} (no time)", "new") },
                    r.answer, r.detail, r.rejected, r, trace, history = history,
                ).put("debugOp", "ask-test"),
            )
        } catch (e: Exception) {
            android.util.Log.w("Assistant", "ask-test failed: $e")
            InteractionLog.record(
                context, newUid(), SOURCE_DEBUG, text, "none",
                interactionData(
                    InteractionLog.context(context, today), listOf(ParseAttempt("openai", false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)),
                    emptyList(), trace = trace, error = e.message ?: e.javaClass.simpleName,
                ).put("debugOp", "ask-test"),
            )
        }
    }

    private suspend fun describeTest(context: Context, text: String) {
        val model = com.google.mlkit.genai.prompt.Generation.getClient()
        try {
            val status = model.checkStatus()
            android.util.Log.i("Describe", "status=$status (0=unavailable 1=downloadable 2=downloading 3=available)")
            if (status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE ||
                status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING
            ) {
                // a broadcast may only run ~10 s: kick the download off, log its start, return
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    model.download().collect { android.util.Log.i("Describe", "download: $it") }
                }
                return
            }
            val raw = model.generateContent(
                com.google.mlkit.genai.prompt.generateContentRequest(com.google.mlkit.genai.prompt.TextPart(describePrompt(text))) {
                    temperature = 0f; topK = 1; maxOutputTokens = 256 // API maximum; five rows of JSON need ~150
                },
            ).candidates.firstOrNull()?.text.orEmpty()
            android.util.Log.i("Describe", "raw=$raw")
            val b = parseBreakdown(raw)
            android.util.Log.i("Describe", "rows=${b.entries.map { it.noteText() ?: "$it (no time)" }} rejected=${b.rejected}")
            InteractionLog.record(
                context, newUid(), SOURCE_DEBUG, text, "nano",
                interactionData(
                    InteractionLog.context(context, java.time.LocalDate.now()), listOf(ParseAttempt("nano", b.entries.isNotEmpty())),
                    b.entries.map { ProposalLog(it.noteText() ?: "${it.name ?: it.insulinType} (no time)", "new") }, rejected = b.rejected,
                ).put("debugOp", "describe-test").put("raw", raw),
            )
        } catch (e: Exception) {
            android.util.Log.w("Describe", "failed: $e")
        } finally {
            model.close()
        }
    }
}

private const val CREDS_HANDOFF = "creds-handoff"
private const val AI_HANDOFF = "ai-handoff"
private const val RAY_HANDOFF = "ray-handoff"
