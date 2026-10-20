package com.geoffchan.glucosewidget.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.concurrent.TimeUnit

/** How long the face keeps the ✓ after a save. */
const val FACE_TICK_MS = 10 * 60_000L

/** What the face shows under the glucose: a ✓ for a recent save, a note while entries wait on the watch, else nothing. */
fun faceStatus(savedText: String?, savedAtMs: Long, queued: Int, nowMs: Long): String? = when {
    queued > 0 -> "Held on watch"
    savedText != null && nowMs - savedAtMs in 0 until FACE_TICK_MS -> savedText
    else -> null
}

/** Short enough for the line under the glucose ("✓ Logged 2"). */
fun faceSaved(s: Saved): String =
    if (s.saved + s.confirmed > 0) "✓ Logged ${s.saved + s.confirmed}" else "✓ Already logged"

/**
 * The last thing a voice log did, for the Big Glucose face: LogActivity closes
 * itself 5 s after a save, so the ✓ stays on the face for [FACE_TICK_MS].
 * Read by [LogStatusComplicationService].
 */
object LogStatus {
    private const val PREFS = "face-status"

    fun saved(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("text", text).putLong("at", System.currentTimeMillis()).apply()
        refresh(context)
        // the ✓'s valid time range should hide it on its own; this makes sure
        WorkManager.getInstance(context).enqueueUniqueWork(
            "face-status-expire",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FaceStatusExpireWorker>().setInitialDelay(FACE_TICK_MS + 5_000, TimeUnit.MILLISECONDS).build(),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        refresh(context)
    }

    fun refresh(context: Context) {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, LogStatusComplicationService::class.java))
            .requestUpdateAll()
    }

    /** (text, from, until) for the face right now, or null for nothing. */
    fun current(context: Context, nowMs: Long = System.currentTimeMillis()): Triple<String, Long, Long>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0)
        val queued = WatchQueue.all(context).size
        val text = faceStatus(p.getString("text", null), at, queued, nowMs) ?: return null
        return if (queued > 0) Triple(text, nowMs, Long.MAX_VALUE) else Triple(text, at, at + FACE_TICK_MS)
    }
}

class FaceStatusExpireWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LogStatus.refresh(applicationContext)
        return Result.success()
    }
}

/** SHORT_TEXT complication the Big Glucose face shows under the reading (slot 1). */
class LogStatusComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return NoDataComplicationData()
        val (text, from, until) = LogStatus.current(this) ?: return NoDataComplicationData()
        val range = if (until == Long.MAX_VALUE) TimeRange.after(Instant.ofEpochMilli(from))
        else TimeRange.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(until))
        return shortText(text).setValidTimeRange(range).build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("✓ Logged 2").build() else null

    private fun shortText(text: String) = ShortTextComplicationData.Builder(
        PlainComplicationText.Builder(text).build(),
        PlainComplicationText.Builder(text.removePrefix("✓ ")).build(),
    )
}
