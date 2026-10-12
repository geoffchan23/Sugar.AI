package com.geoffchan.ray

import com.geoffchan.glucosewidget.RayLoop
import com.geoffchan.glucosewidget.SyncCrypto
import com.geoffchan.glucosewidget.decodeRunRequest
import com.geoffchan.glucosewidget.encodeRunResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Config comes from the environment (systemd's EnvironmentFile, /etc/ray/ray.env), never logged:
 * RAY_DB, RAY_PORT, DEXCOM_USERNAME, DEXCOM_PASSWORD, OPENAI_API_KEY, RAY_MODEL,
 * DEVICE_TOKENS (name:sha256,…), ALEXA_SKILL_ID, ALEXA_USER_IDS, DENO,
 * and SYNC_KEY + SYNC_TOPIC only while the server still follows the old ntfy relay.
 */
data class Config(
    val dbPath: String,
    val port: Int,
    val dexcomUser: String?,
    val dexcomPassword: String?,
    val syncKey: ByteArray?,
    val syncTopic: String?,
    val openAiKey: String?,
    val model: String,
    val deviceTokens: String?,
    val alexaSkillId: String?,
    val alexaUserIds: Set<String>,
    val deno: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            dbPath = env["RAY_DB"] ?: "/var/lib/ray/ray.db",
            port = env["RAY_PORT"]?.toIntOrNull() ?: 8080,
            dexcomUser = env["DEXCOM_USERNAME"]?.takeIf { it.isNotBlank() },
            dexcomPassword = env["DEXCOM_PASSWORD"]?.takeIf { it.isNotBlank() },
            syncKey = env["SYNC_KEY"]?.let { SyncCrypto.keyFromBase64(it) },
            syncTopic = env["SYNC_TOPIC"]?.takeIf { it.length >= 16 },
            openAiKey = env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() },
            model = env["RAY_MODEL"]?.takeIf { it.isNotBlank() } ?: RayService.DEFAULT_MODEL,
            deviceTokens = env["DEVICE_TOKENS"],
            alexaSkillId = env["ALEXA_SKILL_ID"]?.takeIf { it.isNotBlank() },
            alexaUserIds = env["ALEXA_USER_IDS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            deno = env["DENO"] ?: "/usr/local/bin/deno",
        )
    }
}

const val DEXCOM_EVERY_S = 300L
const val RELAY_EVERY_S = 60L
const val MAX_BODY_BYTES = 16 * 1024 * 1024

/**
 * Healthy = a reading in the last 30 min and, while it's followed, the relay
 * polled in the last 10 (only the parts that are configured count).
 */
fun healthy(db: ServerDb, cfg: Config, now: Long = System.currentTimeMillis()): Boolean {
    val readingOk = cfg.dexcomUser == null || (db.latestReading()?.let { now - it.timestampMs < 30 * 60_000 } ?: false)
    val relayOk = cfg.syncKey == null || cfg.syncTopic == null ||
        (db.meta(Relay.META_POLLED_AT)?.toLongOrNull()?.let { now - it < 10 * 60_000 } ?: false)
    return readingOk && relayOk
}

fun main() {
    val cfg = Config.fromEnv()
    val db = ServerDb(cfg.dbPath)
    val jobs = Executors.newScheduledThreadPool(2)
    RayLoop.log = { log("ray: $it") }
    val ray = RayService(db, cfg.openAiKey, cfg.model, cfg.deno)
    val devices = Devices(cfg.deviceTokens)
    val sync = SyncApi(db)
    val alexa = Alexa(cfg.alexaSkillId, cfg.alexaUserIds, ray, db)

    if (cfg.dexcomUser != null && cfg.dexcomPassword != null) {
        val dexcom = Dexcom(db, cfg.dexcomUser, cfg.dexcomPassword)
        jobs.scheduleWithFixedDelay({
            runCatching { dexcom.pollOnce() }
                .onSuccess { if (it > 0) log("dexcom: $it new") }
                .onFailure { log("dexcom failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}") }
        }, 0, DEXCOM_EVERY_S, TimeUnit.SECONDS)
    } else log("dexcom: not configured")

    if (cfg.syncKey != null && cfg.syncTopic != null) {
        val relay = Relay(db, cfg.syncKey, cfg.syncTopic)
        jobs.scheduleWithFixedDelay({
            runCatching { relay.pollOnce() }
                .onSuccess { if (it.merged > 0) log("relay: merged ${it.merged} payloads${if (it.journalChanged) " (journal changed)" else ""}") }
                .onFailure { log("relay failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}") }
        }, 5, RELAY_EVERY_S, TimeUnit.SECONDS)
    } else log("relay: off")

    // Loopback only; Caddy forwards /health and /v1/* to the outside.
    val http = HttpServer.create(InetSocketAddress("127.0.0.1", cfg.port), 0)
    http.createContext("/health") { ex -> ex.json(if (healthy(db, cfg)) 200 else 503, JSONObject().put("ok", healthy(db, cfg))) }
    http.createContext("/status") { ex ->
        // never through Caddy (it always adds X-Forwarded-For): counts and ages are for SSH use
        if (ex.requestHeaders.containsKey("X-Forwarded-For")) return@createContext ex.json(404, JSONObject())
        val now = System.currentTimeMillis()
        val r = db.latestReading()
        ex.json(200, JSONObject()
            .put("ok", healthy(db, cfg))
            .put("counts", JSONObject(db.counts()))
            .put("changes", db.maxSeq())
            .put("readingAgeMin", r?.let { (now - it.timestampMs) / 60_000 } ?: JSONObject.NULL)
            .put("dexcomPolledAgoS", db.meta(Dexcom.META_POLLED_AT)?.toLongOrNull()?.let { (now - it) / 1000 } ?: JSONObject.NULL)
            .put("relayPolledAgoS", db.meta(Relay.META_POLLED_AT)?.toLongOrNull()?.let { (now - it) / 1000 } ?: JSONObject.NULL)
            .put("ray", ray.configured).put("devices", devices.configured).put("alexa", cfg.alexaSkillId != null))
    }

    /** A device endpoint: POST, a known token, a bounded body. */
    fun device(path: String, handler: (device: String, body: String, ex: HttpExchange) -> Unit) = http.createContext(path) { ex ->
        try {
            if (ex.requestMethod != "POST") return@createContext ex.json(405, JSONObject().put("error", "POST only"))
            val dev = devices.device(ex.requestHeaders.getFirst("Authorization"))
                ?: return@createContext ex.json(401, JSONObject().put("error", "unauthorized"))
            val body = ex.body() ?: return@createContext ex.json(413, JSONObject().put("error", "too large"))
            handler(dev, String(body), ex)
        } catch (e: Exception) {
            log("$path failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            ex.json(500, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
        }
    }

    device("/v1/sync") { dev, body, ex ->
        val r = sync.handle(dev, body)
        if (r.merged > 0 || r.sent > 0) log("sync $dev: in ${r.merged}, out ${r.sent}")
        ex.json(200, r.json)
    }
    device("/v1/ray/run") { dev, body, ex ->
        val req = decodeRunRequest(body) ?: return@device ex.json(400, JSONObject().put("error", "bad request"))
        if (!ray.configured) return@device ex.json(503, JSONObject().put("error", "Ray isn't set up on the server"))
        val (r, reports) = ray.run(req)
        log("ray/run $dev ${req.mode}: ${r.ms} ms, ${r.entries.size} rows, ${r.changes.size} changes")
        ex.json(200, encodeRunResult(r, reports))
    }
    device("/v1/voice") { dev, body, ex ->
        val o = JSONObject(body)
        val t0 = System.currentTimeMillis()
        val spoken = ray.voice(o.getString("text"), o.optString("via", "a phone's voice assistant"), RayService.SOURCE_GOOGLE, o.optLong("deadlineMs", 25_000).coerceIn(3_000, 60_000))
        db.logVoice("google:$dev", "shortcut", o.getString("text"), spoken.path, System.currentTimeMillis() - t0, spoken.interaction)
        log("voice $dev${if (spoken.interaction != null) " record ${spoken.interaction}" else " (quick)"}")
        ex.json(200, JSONObject().put("speech", spoken.speech).put("interaction", spoken.interaction ?: JSONObject.NULL))
    }
    http.createContext("/v1/alexa") { ex ->
        try {
            val body = ex.body() ?: return@createContext ex.json(413, JSONObject())
            val r = alexa.handle(body, ex.requestHeaders.getFirst("SignatureCertChainUrl"), ex.requestHeaders.getFirst("Signature-256"))
            ex.json(r.code, r.json)
        } catch (e: Exception) {
            log("alexa failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            ex.json(500, JSONObject())
        }
    }
    http.executor = Executors.newFixedThreadPool(8)
    http.start()
    log("listening on 127.0.0.1:${cfg.port}")

    Runtime.getRuntime().addShutdownHook(Thread {
        http.stop(1); jobs.shutdownNow(); db.close()
    })
}

private fun HttpExchange.body(): ByteArray? = requestBody.use { input ->
    val bytes = input.readNBytes(MAX_BODY_BYTES + 1)
    bytes.takeIf { it.size <= MAX_BODY_BYTES }
}

private fun HttpExchange.json(code: Int, body: JSONObject) = json(code, body.toString())

private fun HttpExchange.json(code: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun log(msg: String) = println(msg) // journald adds the time
