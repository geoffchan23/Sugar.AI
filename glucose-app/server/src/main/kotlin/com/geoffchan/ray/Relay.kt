package com.geoffchan.ray

import com.geoffchan.glucosewidget.ChatMessageEntity
import com.geoffchan.glucosewidget.SyncCrypto
import com.geoffchan.glucosewidget.applyPayload
import com.geoffchan.glucosewidget.decodePayload
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Follows the phones' encrypted ntfy relay and merges every change into the
 * server's copy — read-only: the server never publishes there (from phase 4
 * the phones sync with the server directly and the relay goes away).
 * ntfy keeps ~12 h of messages and each phone re-sends its last 7 days once
 * a day, so a short outage here heals by itself.
 */
class Relay(private val db: ServerDb, private val key: ByteArray, private val topic: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class PollResult(val messages: Int, val merged: Int, val journalChanged: Boolean)

    fun pollOnce(): PollResult {
        val since = db.meta(META_SINCE) ?: "all"
        val lines = http.newCall(Request.Builder().url("$BASE/$topic/json?poll=1&since=$since").get().build()).execute().use {
            if (!it.isSuccessful) error("poll HTTP ${it.code}")
            it.body?.string().orEmpty().lines().filter { l -> l.isNotBlank() }
        }
        var lastId: String? = null
        var merged = 0
        var changed = false
        val incoming = mutableListOf<ChatMessageEntity>()
        for (line in lines) {
            val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (msg.optString("event") != "message") continue
            lastId = msg.optString("id").ifEmpty { lastId }
            val payload = SyncCrypto.decrypt(key, msg.optString("message"))?.let { decodePayload(it) } ?: continue
            if (db.txAs(ORIGIN) { runBlocking { applyPayload(this@txAs, payload, incoming) } }) changed = true
            merged++
        }
        lastId?.let { db.setMeta(META_SINCE, it) }
        db.setMeta(META_POLLED_AT, System.currentTimeMillis().toString())
        return PollResult(lines.size, merged, changed)
    }

    companion object {
        private const val BASE = "https://ntfy.sh"
        const val META_SINCE = "relay_since"
        const val META_POLLED_AT = "relay_polled_at"
        const val ORIGIN = "relay"
    }
}
