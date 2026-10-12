package com.geoffchan.ray

import com.geoffchan.glucosewidget.ChatMessageEntity
import com.geoffchan.glucosewidget.SyncPayload
import com.geoffchan.glucosewidget.SyncTomb
import com.geoffchan.glucosewidget.applyPayload
import com.geoffchan.glucosewidget.decodePayload
import com.geoffchan.glucosewidget.encodePayload
import com.geoffchan.glucosewidget.toSyncChat
import com.geoffchan.glucosewidget.toSyncInteraction
import com.geoffchan.glucosewidget.toSyncRow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Device tokens: `DEVICE_TOKENS=name:sha256hex,name:sha256hex` in ray.env
 * (the tokens themselves live only on the devices and the Mac). Returns the
 * device name for a valid `Authorization: Bearer <token>`.
 */
class Devices(spec: String?) {
    private val byHash: Map<String, String> = spec.orEmpty().split(',').mapNotNull { e ->
        val (name, hash) = e.trim().split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        hash.lowercase() to name
    }.toMap()

    val configured get() = byHash.isNotEmpty()

    fun device(authorization: String?): String? {
        val token = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.length >= 32 } ?: return null
        val h = sha256(token)
        // constant-time compare against every known hash
        var found: String? = null
        for ((k, v) in byHash) if (MessageDigest.isEqual(k.toByteArray(), h.toByteArray())) found = v
        return found
    }

    companion object {
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * POST /v1/sync. The phone sends what it changed (a SyncCore payload) and
 * its cursor; the server merges with the shared rules, then answers with
 * the current state of everything other devices changed after the cursor.
 * No cursor yet (a phone's first sync): everything changed in the last
 * [FIRST_SYNC_DAYS] days, merged idempotently on the phone.
 */
class SyncApi(private val db: ServerDb) {
    data class Reply(val json: String, val merged: Int, val sent: Int)

    fun handle(device: String, body: String): Reply {
        val o = JSONObject(body)
        val cursor = if (o.isNull("cursor") || !o.has("cursor")) null else o.getLong("cursor")
        val payload = o.optJSONObject("payload")?.let { decodePayload(it.toString()) }
        var merged = 0
        if (payload != null) {
            db.txAs(device) { runBlocking { applyPayload(this@txAs, payload, mutableListOf<ChatMessageEntity>()) } }
            merged = payload.rows.size + payload.tombs.size + payload.inter.size + payload.chat.size
        }
        val (items, next, more) = if (cursor == null) {
            val top = db.maxSeq()
            Triple(db.updatedSince(System.currentTimeMillis() - FIRST_SYNC_DAYS * 86_400_000L).distinct(), top, false)
        } else {
            val ch = db.changesAfter(cursor, device, PAGE)
            Triple(ch.map { it.kind to it.uid }, ch.maxOfOrNull { it.seq } ?: cursor, ch.size == PAGE)
        }
        val out = runBlocking { build(items) }
        return Reply(
            JSONObject().put("cursor", next).put("more", more).put("payload", JSONObject(encodePayload(out))).toString(),
            merged, items.size,
        )
    }

    private suspend fun build(items: List<Pair<String, String>>): SyncPayload {
        val rows = mutableListOf<com.geoffchan.glucosewidget.SyncRow>()
        val tombs = mutableListOf<SyncTomb>()
        val inter = mutableListOf<com.geoffchan.glucosewidget.SyncInteraction>()
        val chat = mutableListOf<com.geoffchan.glucosewidget.SyncChat>()
        for ((kind, uid) in items) when (kind) {
            "j" -> db.journalByUid(uid)?.let { rows += it.toSyncRow() } ?: db.tombstone(uid)?.let { tombs += SyncTomb(it.uid, it.deletedAtMs) }
            "i" -> db.interaction(uid)?.let { inter += it.toSyncInteraction() }
            "c" -> db.chatMessage(uid)?.let { chat += it.toSyncChat() }
        }
        return SyncPayload(ServerDb.ORIGIN_SERVER, rows, tombs, inter = inter, chat = chat)
    }

    companion object {
        const val PAGE = 300
        const val FIRST_SYNC_DAYS = 30L
    }
}
