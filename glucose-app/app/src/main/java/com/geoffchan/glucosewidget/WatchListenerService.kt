package com.geoffchan.glucosewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Her watch's voice log, phone side (see [WatchProtocol]). The watch sends
 * what she said; [WatchParse] breaks it into rows and this replies with a
 * proposal; from 2026-10-11 it saves the rows to today via [Journal] straight
 * away (Undo on the watch removes them; older watches still confirm). Entries
 * the watch held while the phone was out of reach arrive on /log/queued and
 * are saved as guesses.
 *
 * Only the main phone answers; Geoff's phone isn't paired to her watch anyway.
 */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        // Already on a background binder thread; blocking keeps the service alive until we reply.
        runBlocking {
            val body = String(event.data, Charsets.UTF_8)
            try {
                when (event.path) {
                    WatchProtocol.PATH_PARSE -> reply(event, WatchProtocol.PATH_PROPOSAL, WatchParse.propose(this@WatchListenerService, body))
                    WatchProtocol.PATH_SAVE -> reply(event, WatchProtocol.PATH_SAVED, WatchParse.save(this@WatchListenerService, body))
                    WatchProtocol.PATH_QUEUED -> reply(event, WatchProtocol.PATH_QUEUED_ACK, WatchParse.saveQueued(this@WatchListenerService, body))
                    WatchProtocol.PATH_OUTCOME -> WatchParse.outcome(this@WatchListenerService, body) // no reply
                    WatchProtocol.PATH_UNDO -> reply(event, WatchProtocol.PATH_UNDONE, WatchParse.undo(this@WatchListenerService, body))
                }
            } catch (e: Exception) {
                Log.w(WatchParse.TAG, "watch ${event.path} failed", e)
                if (event.path == WatchProtocol.PATH_OUTCOME) return@runBlocking
                val (path, err) = when (event.path) {
                    WatchProtocol.PATH_SAVE -> WatchProtocol.PATH_SAVED to encodeSaved(0, 0, 0, WatchParse.SAVE_FAILED)
                    WatchProtocol.PATH_UNDO -> WatchProtocol.PATH_UNDONE to encodeUndone(0, WatchParse.SAVE_FAILED)
                    WatchProtocol.PATH_QUEUED -> WatchProtocol.PATH_QUEUED_ACK to
                        encodeQueuedAck(decodeQueued(body)?.id.orEmpty(), 0, false, WatchParse.SAVE_FAILED)
                    else -> WatchProtocol.PATH_PROPOSAL to encodeProposal(emptyList(), "none", WatchParse.READ_FAILED)
                }
                runCatching { reply(event, path, err) }
            }
        }
    }

    private suspend fun reply(event: MessageEvent, path: String, body: String) {
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, path, body.toByteArray(Charsets.UTF_8)).await()
    }
}

/** Parsing + saving for watch entries; shared by the listener and the ADB test op. */
object WatchParse {
    const val TAG = "WatchLog"
    const val NOT_MAIN = "Your phone isn't set up for watch logging yet."
    const val READ_FAILED = "Your phone couldn't read that — try again."
    const val SAVE_FAILED = "Your phone couldn't save that — try again."

    private const val ASSISTANT_TIMEOUT_MS = 24_000L // the watch waits ~30 s for the reply
    private const val DIRECT_TIMEOUT_MS = 4_000L // in-process Nano: works only if the app is on screen
    private const val SCREEN_TIMEOUT_MS = 12_000L // full-screen-intent Nano; the watch waits ~25 s in all
    private const val CHANNEL = "watch-voice-log"
    private const val NOTIFICATION_ID = 4711

    private suspend fun todayEntries(context: Context, day: String): List<JournalEntity> =
        GlucoseDb.get(context).dao().dayJournalSince(day).filter { it.day == day && it.scope == SCOPE_DAY }

    /**
     * /log/parse → /log/proposal body. The OpenAI assistant when it's set up
     * and the phone is online (it can also answer questions); otherwise Nano
     * when it can run, else [parseSpoken]. Every call is recorded in the
     * interaction log under the watch's id (echoed back in the proposal).
     * [requireMain] is false and [source] is debug only for the ADB test op.
     */
    suspend fun propose(context: Context, body: String, requireMain: Boolean = true, source: String = SOURCE_WATCH): String {
        val req = decodeParseRequest(body)
        val id = req.id ?: newUid()
        val transcript = req.text
        if (requireMain && !Store.isMainPhone(context)) return encodeProposal(emptyList(), "none", NOT_MAIN, id = id)
        val zone = ZoneId.systemDefault()
        val nowZ = java.time.ZonedDateTime.now(zone)
        val now = nowZ.toLocalTime().withSecond(0).withNano(0)
        val t0 = System.currentTimeMillis()
        val today = nowZ.toLocalDate()
        val attempts = mutableListOf<ParseAttempt>()
        val trace = org.json.JSONArray()
        var aiError: String? = null
        val history = InteractionLog.history(context, req.replyTo)
        req.replyTo?.let { InteractionLog.setOutcome(context, it, Outcome(OUTCOME_REPLIED, System.currentTimeMillis(), note = "reply $id")) }
        val configured = Assistant.configured(context)
        val online = configured && Assistant.online(context)
        if (configured && !online) attempts += ParseAttempt("openai", false, "offline")
        val ai = if (online) {
            val ta = System.currentTimeMillis()
            try {
                Assistant.ask(context, transcript, today, fromWatch = true, timeoutMs = ASSISTANT_TIMEOUT_MS, zone = zone, trace = trace, history = history)
                    .also { attempts += ParseAttempt("openai", true, null, System.currentTimeMillis() - ta) }
            } catch (e: Exception) {
                val reason = if (e is kotlinx.coroutines.TimeoutCancellationException) "timeout after ${ASSISTANT_TIMEOUT_MS} ms" else e.message ?: e.javaClass.simpleName
                aiError = reason
                attempts += ParseAttempt("openai", false, reason, System.currentTimeMillis() - ta)
                Log.i(TAG, "assistant failed ($reason); falling back to rules")
                null
            }
        } else null
        val (entries, parser) = when {
            ai != null -> ai.entries to "openai"
            // the assistant used up the time budget: no room for Nano's ~12 s route
            online -> parseSpoken(transcript, now) to "rules"
            else -> nanoRows(context, transcript, now, attempts) ?: (parseSpoken(transcript, now) to "rules")
        }
        if (parser == "rules") attempts += ParseAttempt("rules", true)
        val answer = ai?.answer.orEmpty()
        val existing = todayEntries(context, today.toString())
        val rows = entries.mapNotNull { watchRow(if (it.time == null) it.copy(time = now) else it, existing) }
        Log.i(TAG, "parse via $parser in ${System.currentTimeMillis() - t0} ms: \"$transcript\" → ${rows.map { it.text }} answer=\"$answer\"")
        InteractionLog.record(
            context, id, source, transcript, parser,
            interactionData(
                InteractionLog.context(context, today, nowZ, ai?.userTurn), attempts,
                rows.map { ProposalLog(it.text, it.status) }, answer, ai?.detail, ai?.rejected ?: 0, ai,
                trace.takeIf { it.length() > 0 }, if (ai == null) aiError else null, history,
            ).apply { req.replyTo?.let { put("replyTo", it) } },
        )
        val changes = ai?.changes.orEmpty()
        // other days' edits are reviewed on the phone, not on the watch's small screen
        if (changes.isNotEmpty() && source != SOURCE_DEBUG) notifyChanges(context, id, changes.size)
        // the watch no longer confirms (Geoff, 2026-10-11): save now; she and Geoff fix it afterwards,
        // and each fix is logged as a correction against this record
        val counts = if (req.autoSave && rows.isNotEmpty() && source != SOURCE_DEBUG) {
            val (counts, items) = saveTexts(context, rows.map { it.text })
            InteractionLog.setOutcome(context, id, Outcome(OUTCOME_SAVED, System.currentTimeMillis(), items, note = "auto-saved from the watch"))
            counts
        } else null
        return encodeProposal(rows, parser, answer = answer, id = id, changes = changes.size, awaiting = ai?.awaitingAnswer == true, saved = counts)
    }

    private const val CHANGES_CHANNEL = "watch-changes"

    /** "Review 3 changes from your watch" → the app's review dialog for record [id]. */
    private fun notifyChanges(context: Context, id: String, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANGES_CHANNEL, "Changes to review", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Edits to past entries asked for from your watch, to confirm on the phone"
            },
        )
        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_REVIEW_CHANGES, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            id.hashCode(),
            Notification.Builder(context, CHANGES_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(if (count == 1) "1 change to review" else "$count changes to review")
                .setContentText("From your watch — tap to check and save")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Rows from Gemini Nano with the parser name, or null to fall back to rules; each try lands in [attempts]. */
    private suspend fun nanoRows(context: Context, transcript: String, now: LocalTime, attempts: MutableList<ParseAttempt>): Pair<List<ProposedEntry>, String>? {
        val t0 = System.currentTimeMillis()
        val model = Generation.getClient()
        val available = try {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) { model.checkStatus() } == FeatureStatus.AVAILABLE
        } catch (e: Exception) {
            false
        }
        if (!available) {
            model.close()
            attempts += ParseAttempt("nano", false, "model not available", System.currentTimeMillis() - t0)
            return null
        }
        val prompt = describePrompt(watchParagraph(transcript, now))

        // 1) The app may already be on screen (she has it open): just ask.
        var why: String? = null
        val direct = try {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                model.generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0f; topK = 1; maxOutputTokens = 256 })
                    .candidates.firstOrNull()?.text
            }.also { if (it == null) why = "timeout" }
        } catch (e: Exception) {
            Log.i(TAG, "Nano in-process refused (${e.message}); trying the lock-screen route")
            why = e.message ?: e.javaClass.simpleName
            null
        } finally {
            model.close()
        }
        rowsOf(direct)?.let {
            attempts += ParseAttempt("nano", true, null, System.currentTimeMillis() - t0)
            return it to "nano"
        }
        attempts += ParseAttempt("nano", false, why ?: "no rows", System.currentTimeMillis() - t0)

        // 2) Bring an invisible activity up over the lock screen and ask from there.
        val t1 = System.currentTimeMillis()
        val viaScreen = rowsOf(viaFullScreen(context, prompt))
        attempts += ParseAttempt("nano-screen", viaScreen != null, if (viaScreen == null) "no answer within ${SCREEN_TIMEOUT_MS} ms" else null, System.currentTimeMillis() - t1)
        return viaScreen?.let { it to "nano-screen" }
    }

    private fun rowsOf(raw: String?): List<ProposedEntry>? =
        raw?.let { parseBreakdown(it).entries }?.takeIf { it.isNotEmpty() }

    private suspend fun viaFullScreen(context: Context, prompt: String): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent()) {
            Log.i(TAG, "full-screen intents not allowed (appops USE_FULL_SCREEN_INTENT); using rules")
            return null
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch voice log", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Briefly shown while the phone reads an entry spoken into the watch"
            },
        )
        val id = java.util.UUID.randomUUID().toString()
        val answer = NanoBridge.register(id)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, NanoParseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(NanoParseActivity.EXTRA_ID, id)
                .putExtra(NanoParseActivity.EXTRA_PROMPT, prompt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Reading your watch entry…")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(SCREEN_TIMEOUT_MS + 3_000)
                .build(),
        )
        return try {
            withTimeoutOrNull(SCREEN_TIMEOUT_MS) { answer.await() }
        } catch (e: Exception) {
            null
        } finally {
            NanoBridge.cancel(id)
            nm.cancel(NOTIFICATION_ID)
        }
    }

    /** /log/save → /log/saved body (watches from before 2026-10-11, which still confirm). */
    suspend fun save(context: Context, json: String): String {
        if (!Store.isMainPhone(context)) return encodeSaved(0, 0, 0, NOT_MAIN)
        val (c, items) = saveTexts(context, decodeSave(json))
        val meta = decodeSaveMeta(json)
        meta.id?.let { InteractionLog.setOutcome(context, it, Outcome(OUTCOME_SAVED, System.currentTimeMillis(), items, meta.unticked)) }
        return encodeSaved(c.saved, c.confirmed, c.already)
    }

    /** Writes canonical [texts] to today (see [planWatchSave]). */
    private suspend fun saveTexts(context: Context, texts: List<String>): Pair<SaveCounts, List<SavedItem>> {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val existing = todayEntries(context, today)
        val now = System.currentTimeMillis()
        var saved = 0; var confirmed = 0; var already = 0
        val items = mutableListOf<SavedItem>()
        for (op in planWatchSave(texts, existing)) when (op) {
            is WatchSaveOp.Insert -> {
                val row = JournalEntity(day = today, text = op.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY)
                Journal.insert(context, row)
                items += SavedItem(op.text, op.text, row.uid, "insert")
                saved++
            }
            is WatchSaveOp.Confirm -> {
                Journal.update(context, op.guess.copy(text = op.text, updatedAtMs = now))
                items += SavedItem(op.text, op.text, op.guess.uid, "confirm")
                confirmed++
            }
            is WatchSaveOp.Already -> { items += SavedItem(op.text, op.text, null, "already"); already++ }
        }
        if (saved + confirmed > 0) GlucoseWidget().updateAll(context)
        return SaveCounts(saved, confirmed, already) to items
    }

    /**
     * /log/undo → /log/undone: she tapped Undo on the ✓ screen. Removes the
     * rows record [id] inserted (a guess or auto-logged row it confirmed was
     * there before, so it stays); each delete goes through [Journal], so
     * it's logged as a correction.
     */
    suspend fun undo(context: Context, json: String): String {
        if (!Store.isMainPhone(context)) return encodeUndone(0, NOT_MAIN)
        val id = runCatching { org.json.JSONObject(json).getString("id") }.getOrNull() ?: return encodeUndone(0, SAVE_FAILED)
        val dao = GlucoseDb.get(context).dao()
        val rec = dao.interaction(id) ?: return encodeUndone(0, "Couldn't find that entry on your phone.")
        var removed = 0
        for (item in decodeOutcome(rec.outcome)?.saved.orEmpty()) {
            if (item.op != "insert") continue
            val row = item.journalUid?.let { dao.journalByUid(it) } ?: continue
            Journal.delete(context, row); removed++
        }
        if (removed > 0) GlucoseWidget().updateAll(context)
        Log.i(TAG, "undo $id: removed $removed")
        return encodeUndone(removed)
    }

    /** /log/outcome: she cancelled, asked again or read an answer — logged against the record. */
    suspend fun outcome(context: Context, json: String) {
        val o = decodeWatchOutcome(json) ?: return
        InteractionLog.setOutcome(context, o.id, Outcome(o.kind, System.currentTimeMillis()))
    }

    /**
     * /log/queued → /log/queued-ack body. Rule parser only (no one is looking
     * at the phone and nothing can be confirmed), times relative to when she
     * spoke, saved as guesses to the day she spoke. Idempotent by id.
     */
    suspend fun saveQueued(context: Context, json: String): String {
        val q = decodeQueued(json) ?: return encodeQueuedAck("", 0, false, SAVE_FAILED)
        if (!Store.isMainPhone(context)) return encodeQueuedAck(q.id, 0, false, NOT_MAIN)
        val remembered = rememberQueuedId(Store.watchQueuedIds(context), q.id)
            ?: return encodeQueuedAck(q.id, 0, duplicate = true)
        val spokenAt = Instant.ofEpochMilli(q.spokenAtMs).atZone(ZoneId.systemDefault())
        val day = spokenAt.toLocalDate().toString()
        val rows = parseSpoken(q.text, spokenAt.toLocalTime().withSecond(0).withNano(0))
        val now = System.currentTimeMillis()
        val texts = planQueuedSave(rows, todayEntries(context, day))
        val items = mutableListOf<SavedItem>()
        for (text in texts) {
            val row = JournalEntity(day = day, text = text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY)
            Journal.insert(context, row)
            items += SavedItem(text.removeSuffix(GUESS_SUFFIX), text, row.uid, "guess")
        }
        Store.saveWatchQueuedIds(context, remembered)
        if (texts.isNotEmpty()) GlucoseWidget().updateAll(context)
        // the queued entry is its own record (uid = its id, so a resend can't log it twice) …
        InteractionLog.record(
            context, q.id, SOURCE_WATCH_QUEUED, q.text, "rules",
            interactionData(
                InteractionLog.context(context, spokenAt.toLocalDate(), spokenAt).copy(currentReading = null),
                listOf(ParseAttempt("rules", true)),
                rows.mapNotNull { it.noteText() }.map { ProposalLog(it, if (it + GUESS_SUFFIX in texts) "new" else WatchProtocol.STATUS_ALREADY) },
            ).put("spokenAtMs", q.spokenAtMs).put("parseId", q.parseId ?: org.json.JSONObject.NULL),
            Outcome(OUTCOME_GUESSES, now, items, note = "no confirm screen: saved as guesses"),
        )
        // … and the live attempt it replaces (if the watch gave up waiting) gets its outcome
        q.parseId?.let { InteractionLog.setOutcome(context, it, Outcome(OUTCOME_TIMEOUT, now, note = "queued as ${q.id}")) }
        Log.i(TAG, "queued ${q.id} from $spokenAt: \"${q.text}\" → $texts")
        return encodeQueuedAck(q.id, texts.size, duplicate = false)
    }
}

/**
 * Said into the watch in the moment, so untimed items happened now — except
 * items from an earlier meal she mentions ("coffee this morning"), which get
 * that meal's usual time.
 */
fun watchParagraph(transcript: String, now: LocalTime): String {
    val hhmm = "%02d:%02d".format(now.hour, now.minute)
    return "$transcript (Said at $hhmm. Items with no stated time happened at $hhmm, " +
        "except items from a meal that was clearly earlier than $hhmm, which get that meal's usual time.)"
}
