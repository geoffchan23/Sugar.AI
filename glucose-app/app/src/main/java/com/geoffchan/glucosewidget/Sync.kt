package com.geoffchan.glucosewidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Two-phone journal sync: her phone (main) and Geoff's (peer) both edit;
 * every change travels end-to-end encrypted through an ntfy.sh topic.
 *
 * - Local writes ([Journal]) add the row's uid to an outbox and schedule
 *   [SyncWorker]; the worker publishes each uid's *current* state (row or
 *   tombstone), so offline edits go out later and nothing is lost.
 * - Every run also polls the topic and merges the other phone's messages
 *   (rules in SyncCore: last write wins on updatedAtMs).
 * - ntfy.sh keeps messages ~12 h, so once a day each phone re-publishes
 *   everything changed in the last 7 days (Ray's records and chat: 2). A phone offline longer than that
 *   needs "Resend last 30 days" from the other one (Settings).
 *
 * Assistant interaction records ([InteractionLog]) ride along: outbox
 * entries "i:<uid>", sent trimmed to fit a message ([trimForSync]); the full
 * trace stays on the phone where it happened. Chat messages with Ray
 * ([ChatStore]) are "c:<uid>", trimmed the same way ([trimChatForSync]).
 *
 * Not set up (no key/topic) = no-op; the app behaves as a single phone.
 */
object Sync {
    private const val BASE = "https://ntfy.sh"
    private const val DIGEST_EVERY_MS = 24 * 3600_000L
    private const val DIGEST_WINDOW_MS = 7 * 24 * 3600_000L
    // Ray's records and chat are most of the volume (~1 message each); both phones share home Wi-Fi
    // and so ntfy.sh's 250 messages/day per IP, which a 7-day digest of them used up (2026-10-12)
    private const val DIGEST_LOG_WINDOW_MS = 2 * 24 * 3600_000L
    const val RESEND_WINDOW_MS = 30 * 24 * 3600_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Called after every local journal write. */
    suspend fun localChanged(context: Context, rows: List<JournalEntity>, deleted: List<JournalTombstone>) {
        if (Store.syncConfig(context) == null) return
        Store.addToOutbox(context, rows.map { it.uid } + deleted.map { it.uid })
        enqueue(context, delaySeconds = 5) // coalesce a burst (e.g. a describe-in-words save)
    }

    /** Called after an interaction record is created or its outcome changes. */
    suspend fun interactionChanged(context: Context, uid: String) {
        if (Store.syncConfig(context) == null) return
        Store.addToOutbox(context, listOf(INTERACTION_PREFIX + uid))
        enqueue(context, delaySeconds = 5)
    }

    private const val INTERACTION_PREFIX = "i:"
    private const val CHAT_PREFIX = "c:"

    /** Called after a chat message is written (sent, or a card saved). Sends right away. */
    suspend fun chatChanged(context: Context, uid: String) {
        if (Store.syncConfig(context) == null) return
        Store.addToOutbox(context, listOf(CHAT_PREFIX + uid))
        enqueue(context, delaySeconds = 1)
    }

    /** Run a sync soon, when there's a network; retried with backoff on failure. */
    fun enqueue(context: Context, delaySeconds: Long = 0) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * Queue every row/tombstone changed within [windowMs] for publishing, and
     * interaction records and chat changed within [logWindowMs].
     */
    suspend fun queueRecent(context: Context, windowMs: Long, logWindowMs: Long = windowMs) {
        val dao = GlucoseDb.get(context).dao()
        val now = System.currentTimeMillis()
        val since = now - windowMs
        val logSince = now - logWindowMs
        Store.addToOutbox(
            context,
            dao.journalUpdatedSince(since).map { it.uid } + dao.tombstonesSince(since).map { it.uid } +
                dao.interactionUidsUpdatedSince(logSince).map { INTERACTION_PREFIX + it } +
                dao.chatUidsUpdatedSince(logSince).map { CHAT_PREFIX + it },
        )
    }

    /** Configure from ADB (`op sync-setup`). Returns false for a bad key. */
    suspend fun setup(context: Context, keyBase64: String, topic: String, main: Boolean?): Boolean {
        if (SyncCrypto.keyFromBase64(keyBase64) == null || topic.length < 16) return false
        Store.resetSyncState(context)
        Store.saveSyncConfig(context, keyBase64.trim(), topic.trim())
        main?.let { Store.saveIsMainPhone(context, it) }
        enqueue(context)
        return true
    }

    suspend fun reset(context: Context) {
        Store.clearSyncConfig(context)
        Store.resetSyncState(context)
        WorkManager.getInstance(context).cancelUniqueWork("sync")
    }

    /**
     * One full cycle: publish the outbox, pull the other phone's changes,
     * then the daily digest if due. Throws on network failure (callers retry).
     */
    suspend fun runOnce(context: Context) {
        val cfg = Store.syncConfig(context) ?: return
        try {
            val status = Store.syncStatus(context)
            // a refused publish (ntfy.sh 429 once its daily message quota is used up) mustn't
            // stop this phone receiving: poll anyway, then report the publish failure
            val published = runCatching {
                if (System.currentTimeMillis() - (status.lastDigestAt ?: 0L) > DIGEST_EVERY_MS) {
                    queueRecent(context, DIGEST_WINDOW_MS, DIGEST_LOG_WINDOW_MS)
                    publishOutbox(context, cfg)
                    Store.markDigest(context)
                } else {
                    publishOutbox(context, cfg)
                }
            }
            poll(context, cfg)
            published.getOrThrow()
            Store.saveSyncError(context, null)
        } catch (e: Exception) {
            Store.saveSyncError(context, "${e.javaClass.simpleName}: ${e.message}".take(200))
            throw e
        }
    }

    private suspend fun publishOutbox(context: Context, cfg: Store.SyncConfig) {
        val all = Store.syncOutbox(context)
        if (all.isEmpty()) return
        val (chatUids, rest) = all.partition { it.startsWith(CHAT_PREFIX) }
        val (interUids, uids) = rest.partition { it.startsWith(INTERACTION_PREFIX) }
        val dao = GlucoseDb.get(context).dao()
        val rows = mutableListOf<SyncRow>()
        val tombs = mutableListOf<SyncTomb>()
        for (uid in uids) {
            val row = dao.journalByUid(uid)
            if (row != null) rows += row.toSyncRow()
            else dao.tombstone(uid)?.let { tombs += SyncTomb(it.uid, it.deletedAtMs) }
        }
        val inter = interUids.mapNotNull { dao.interaction(it.removePrefix(INTERACTION_PREFIX))?.let { e -> trimForSync(e) } }
        val chat = chatUids.mapNotNull { dao.chatMessage(it.removePrefix(CHAT_PREFIX))?.let { m -> trimChatForSync(m) } }
        val device = Store.deviceId(context)
        for (payload in chunkPayloads(device, rows, tombs, inter, chat = chat)) {
            post(cfg, SyncCrypto.encrypt(cfg.key, encodePayload(payload)))
            Store.removeFromOutbox(
                context,
                payload.rows.map { it.uid } + payload.tombs.map { it.uid } + payload.inter.map { INTERACTION_PREFIX + it.uid } +
                    payload.chat.map { CHAT_PREFIX + it.uid },
            )
        }
        // entries with nothing behind them have nothing to send
        val sent = (rows.map { it.uid } + tombs.map { it.uid } + inter.map { INTERACTION_PREFIX + it.uid } + chat.map { CHAT_PREFIX + it.uid }).toSet()
        Store.removeFromOutbox(context, all - sent)
        Store.markPublished(context)
    }

    private suspend fun post(cfg: Store.SyncConfig, body: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/${cfg.topic}")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        http.newCall(req).execute().use { if (!it.isSuccessful) error("publish HTTP ${it.code}") }
    }

    private suspend fun poll(context: Context, cfg: Store.SyncConfig) {
        val since = Store.syncSince(context) ?: "all"
        val lines = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$BASE/${cfg.topic}/json?poll=1&since=$since").get().build()
            http.newCall(req).execute().use {
                if (!it.isSuccessful) error("poll HTTP ${it.code}")
                it.body?.string().orEmpty().lines().filter { l -> l.isNotBlank() }
            }
        }
        val me = Store.deviceId(context)
        var lastId: String? = null
        var changed = false
        val incoming = mutableListOf<ChatMessageEntity>()
        for (line in lines) {
            val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (msg.optString("event") != "message") continue
            lastId = msg.optString("id").ifEmpty { lastId }
            val body = msg.optString("message")
            val payload = SyncCrypto.decrypt(cfg.key, body)?.let { decodePayload(it) } ?: continue
            if (payload.device == me) continue
            if (apply(context, payload, incoming)) changed = true
        }
        lastId?.let { Store.saveSyncSince(context, it) }
        Store.markPolled(context)
        if (changed) GlucoseWidget().updateAll(context)
        if (incoming.isNotEmpty()) ChatNotification.newMessages(context, incoming)
    }

    /** Merge one payload straight into Room — never through [Journal], so it isn't re-published. */
    private suspend fun apply(context: Context, p: SyncPayload, incoming: MutableList<ChatMessageEntity>): Boolean {
        val db = GlucoseDb.get(context)
        return db.withTransaction { applyPayload(db.dao().asSyncStore(), p, incoming) }
    }
}

/** The DAO as the shared merge's store (SyncMerge.kt). */
fun GlucoseDao.asSyncStore(): SyncStore {
    val dao = this
    return object : SyncStore {
        override suspend fun journalByUid(uid: String) = dao.journalByUid(uid)
        override suspend fun insertJournal(entry: JournalEntity) = dao.insertJournal(entry)
        override suspend fun updateJournal(entry: JournalEntity) = dao.updateJournal(entry)
        override suspend fun deleteJournal(entry: JournalEntity) = dao.deleteJournal(entry)
        override suspend fun tombstone(uid: String) = dao.tombstone(uid)
        override suspend fun insertTombstone(t: JournalTombstone) = dao.insertTombstone(t)
        override suspend fun clearTombstone(uid: String) = dao.clearTombstone(uid)
        override suspend fun interaction(uid: String) = dao.interaction(uid)
        override suspend fun insertInteraction(e: AssistantLogEntity) = dao.insertInteraction(e)
        override suspend fun updateInteraction(e: AssistantLogEntity) = dao.updateInteraction(e)
        override suspend fun chatMessage(uid: String) = dao.chatMessage(uid)
        override suspend fun upsertChat(m: ChatMessageEntity) = dao.upsertChat(m)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Sync.runOnce(applicationContext)
        Result.success()
    } catch (e: Exception) {
        android.util.Log.w("GlucoseWidget", "sync failed", e)
        if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}
