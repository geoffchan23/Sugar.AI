package com.geoffchan.glucosewidget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Records every parse/ask — whatever handled it (openai, nano, rules) and
 * from wherever (watch, queued watch entry, phone dialog, ADB test ops) —
 * plus what she did with it, for building evals and skills later. Writes
 * go to Room ([AssistantLogEntity]) and on to the other phone via [Sync].
 *
 * Logging must never break logging-a-dose: every call here swallows its
 * own errors.
 */
object InteractionLog {
    private const val TAG = "InteractionLog"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CLOCK = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")

    /** The current reading as the assistant sees it, e.g. "6.4 mmol/L Flat (3 min ago)". */
    suspend fun currentReading(context: Context): String? {
        val r = Store.reading(context) ?: return null
        val ageMin = (System.currentTimeMillis() - r.timestampMs) / 60_000
        return "${mmolText(r.mgdl)} mmol/L ${r.trend} (${ageMin} min ago)"
    }

    suspend fun context(context: Context, targetDay: LocalDate, now: ZonedDateTime = ZonedDateTime.now(), userTurn: String? = null) =
        InteractionContext(CLOCK.format(now), targetDay.toString(), runCatching { currentReading(context) }.getOrNull(), userTurn)

    suspend fun record(
        context: Context,
        uid: String,
        source: String,
        input: String,
        parser: String,
        data: org.json.JSONObject,
        outcome: Outcome? = null,
    ) {
        try {
            val now = System.currentTimeMillis()
            GlucoseDb.get(context).dao().insertInteraction(
                AssistantLogEntity(
                    uid = uid, createdAtMs = now, updatedAtMs = now, source = source, input = input,
                    parser = parser, full = true, data = data.toString(), outcome = outcome?.let { encodeOutcome(it) },
                ),
            )
            Sync.interactionChanged(context, uid)
        } catch (e: Exception) {
            Log.w(TAG, "record failed", e)
        }
    }

    /** Attach (merge) her outcome; a later save always beats a cancel (see [mergeOutcome]). */
    suspend fun setOutcome(context: Context, uid: String, outcome: Outcome) {
        try {
            val dao = GlucoseDb.get(context).dao()
            val cur = dao.interaction(uid) ?: run { Log.i(TAG, "no interaction $uid for outcome ${outcome.kind}"); return }
            val merged = mergeOutcome(cur.outcome, encodeOutcome(outcome))
            if (merged == cur.outcome) return
            dao.updateInteraction(cur.copy(outcome = merged, updatedAtMs = maxOf(System.currentTimeMillis(), cur.updatedAtMs + 1)))
            Sync.interactionChanged(context, uid)
        } catch (e: Exception) {
            Log.w(TAG, "outcome failed", e)
        }
    }

    /**
     * Row [uid] was edited ([after]) or deleted (null) after a record saved
     * it: note that on the record, for the evals and the prompt's lessons.
     * Rows no record saved (typed in by hand) are ignored.
     */
    suspend fun correction(context: Context, uid: String, before: String, after: String?) {
        try {
            val op = correctionOp(before, after) ?: return
            val dao = GlucoseDb.get(context).dao()
            val rec = dao.interactionsMentioning(uid).firstOrNull { r ->
                decodeOutcome(r.outcome)?.saved?.any { it.journalUid == uid && it.op != "already" } == true
            } ?: return
            val cur = decodeOutcome(rec.outcome) ?: return
            val c = Correction(uid, op, before, after.orEmpty(), Store.chatAuthor(context), System.currentTimeMillis())
            dao.updateInteraction(rec.copy(outcome = encodeOutcome(cur.copy(corrections = cur.corrections + c)), updatedAtMs = maxOf(c.atMs, rec.updatedAtMs + 1)))
            Sync.interactionChanged(context, rec.uid)
            Log.i(TAG, "correction on ${rec.uid}: $op \"$before\" → \"${after.orEmpty()}\"")
        } catch (e: Exception) {
            Log.w(TAG, "correction failed", e)
        }
    }

    /** Recent corrections with what she said, newest first. */
    suspend fun correctionExamples(context: Context, limit: Int = 40): List<CorrectionExample> = try {
        GlucoseDb.get(context).dao().correctedInteractions(limit).flatMap { r ->
            decodeOutcome(r.outcome)?.corrections.orEmpty().map { CorrectionExample(r.input, it) }
        }.sortedByDescending { it.correction.atMs }
    } catch (e: Exception) {
        Log.w(TAG, "corrections failed", e); emptyList()
    }

    /** The conversation so far when she replies to record [replyTo] (empty if none or unknown). */
    suspend fun history(context: Context, replyTo: String?): List<Turn> {
        replyTo ?: return emptyList()
        return try {
            GlucoseDb.get(context).dao().interaction(replyTo)?.let { historyAfter(it.input, it.data) }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "history failed", e); emptyList()
        }
    }

    /** The other-day changes record [uid] proposed, for the phone's review screen. */
    suspend fun proposedChanges(context: Context, uid: String): List<ChangeOp> = try {
        GlucoseDb.get(context).dao().interaction(uid)
            ?.let { decodeChanges(org.json.JSONObject(it.data).optJSONArray("changes")) }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "changes failed", e); emptyList()
    }

    /** For callers about to leave (a dialog closing): don't tie the write to their scope. */
    fun setOutcomeLater(context: Context, uid: String?, outcome: Outcome) {
        uid ?: return
        val app = context.applicationContext
        scope.launch { setOutcome(app, uid, outcome) }
    }

    fun recordLater(context: Context, block: suspend (Context) -> Unit) {
        val app = context.applicationContext
        scope.launch { runCatching { block(app) }.onFailure { Log.w(TAG, "record failed", it) } }
    }
}
