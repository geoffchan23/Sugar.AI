package com.geoffchan.glucosewidget.wear

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Entries she spoke while the phone was out of reach. Kept in a small
 * SharedPreferences list and sent on /log/queued when the phone is back —
 * on the next app open and by [QueueFlushWorker] retrying in the background.
 * The phone saves them as guesses (nothing was confirmed) and is idempotent
 * by id, so a resend after a lost ack never double-logs.
 */
object WatchQueue {
    private const val PREFS = "watch-queue"
    private const val KEY = "items"
    private const val TAG = "WatchLog"
    private val lock = Any()

    fun all(context: Context): List<QueuedItem> =
        decodeQueue(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun add(context: Context, text: String, spokenAtMs: Long = System.currentTimeMillis(), parseId: String? = null) {
        synchronized(lock) {
            val items = all(context) + QueuedItem(java.util.UUID.randomUUID().toString(), text, spokenAtMs, parseId)
            write(context, items)
        }
        QueueFlushWorker.schedule(context)
    }

    private fun remove(context: Context, id: String) = synchronized(lock) {
        write(context, all(context).filterNot { it.id == id })
    }

    private fun write(context: Context, items: List<QueuedItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encodeQueue(items)).apply()
    }

    /** Sends everything queued; returns how many are still waiting. */
    suspend fun flush(context: Context): Int {
        val link = PhoneLink(context)
        for (item in all(context)) {
            try {
                val ack = decodeQueuedAck(link.request(Protocol.PATH_QUEUED, encodeQueued(item), Protocol.PATH_QUEUED_ACK))
                if (ackSettles(item, ack)) remove(context, item.id)
            } catch (e: Exception) {
                Log.i(TAG, "queue flush stopped: ${e.message}")
                break // phone still away; try later
            }
        }
        val left = all(context).size
        // the face's "Held on watch" goes once the phone has them
        if (left == 0) LogStatus.refresh(context)
        return left
    }
}

class QueueFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (WatchQueue.flush(applicationContext) == 0) Result.success() else Result.retry()

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "watch-queue-flush",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<QueueFlushWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
