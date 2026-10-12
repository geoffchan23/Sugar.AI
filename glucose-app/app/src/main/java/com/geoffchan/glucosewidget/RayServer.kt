package com.geoffchan.glucosewidget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val DEFAULT_RAY_URL = "https://ray.geoffreychan.com"

/**
 * The phone's client for the Ray server (cloud Ray; API in core RayWire.kt).
 * Throws [RayServerException] on anything but a 2xx, so callers can fall
 * back to the phone (local OpenAI, Nano, rules).
 */
object RayServer {
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // a chat exchange with analysis can take a while
        .build()

    class RayServerException(message: String, val code: Int = 0) : Exception(message) {
        /** Worth answering on the phone instead: the server is unreachable or broken, not refusing us. */
        val fallBack: Boolean get() = code == 0 || code >= 500
    }

    data class SyncReply(val cursor: Long, val more: Boolean, val payload: SyncPayload)

    private suspend fun post(cfg: Store.RayServerConfig, path: String, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(cfg.url + path)
            .header("Authorization", "Bearer ${cfg.token}")
            .post(body.toRequestBody(JSON))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    throw RayServerException("Ray server ${resp.code}${msg?.let { ": $it" }.orEmpty()}", resp.code)
                }
                text
            }
        } catch (e: java.io.IOException) {
            throw RayServerException("Ray server unreachable: ${e.javaClass.simpleName}")
        }
    }

    suspend fun sync(cfg: Store.RayServerConfig, cursor: Long?, payload: SyncPayload?): SyncReply {
        val body = JSONObject()
            .put("cursor", cursor ?: JSONObject.NULL)
            .apply { if (payload != null) put("payload", JSONObject(encodePayload(payload))) }
        val o = JSONObject(post(cfg, "/v1/sync", body.toString()))
        val p = decodePayload(o.getJSONObject("payload").toString()) ?: throw RayServerException("bad sync reply")
        return SyncReply(o.getLong("cursor"), o.optBoolean("more"), p)
    }

    suspend fun run(cfg: Store.RayServerConfig, req: RunRequest): Pair<AssistantResult, List<RayReport>> =
        decodeRunResult(post(cfg, "/v1/ray/run", encodeRunRequest(req)))

    /** A spoken question or log (the voice shortcuts); returns what to say. */
    suspend fun voice(cfg: Store.RayServerConfig, text: String): String =
        JSONObject(post(cfg, "/v1/voice", JSONObject().put("text", text).put("via", "a Hey Google shortcut on a phone").toString())).getString("speech")
}
