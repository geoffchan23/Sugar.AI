package com.geoffchan.ray

import com.geoffchan.glucosewidget.SyncCrypto
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Config comes from the environment (systemd's EnvironmentFile, /etc/ray/ray.env):
 * RAY_DB, RAY_PORT, DEXCOM_USERNAME, DEXCOM_PASSWORD, SYNC_KEY, SYNC_TOPIC.
 * Never logged.
 */
data class Config(
    val dbPath: String,
    val port: Int,
    val dexcomUser: String?,
    val dexcomPassword: String?,
    val syncKey: ByteArray?,
    val syncTopic: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            dbPath = env["RAY_DB"] ?: "/var/lib/ray/ray.db",
            port = env["RAY_PORT"]?.toIntOrNull() ?: 8080,
            dexcomUser = env["DEXCOM_USERNAME"]?.takeIf { it.isNotBlank() },
            dexcomPassword = env["DEXCOM_PASSWORD"]?.takeIf { it.isNotBlank() },
            syncKey = env["SYNC_KEY"]?.let { SyncCrypto.keyFromBase64(it) },
            syncTopic = env["SYNC_TOPIC"]?.takeIf { it.length >= 16 },
        )
    }
}

const val DEXCOM_EVERY_S = 300L
const val RELAY_EVERY_S = 60L

/**
 * Healthy = a reading in the last 30 min and the relay polled in the last
 * 10 (only the parts that are configured count).
 */
fun healthy(db: ServerDb, cfg: Config, now: Long = System.currentTimeMillis()): Boolean {
    val readingOk = cfg.dexcomUser == null || (db.latestReading()?.let { now - it.timestampMs < 30 * 60_000 } ?: false)
    val relayOk = cfg.syncKey == null || (db.meta(Relay.META_POLLED_AT)?.toLongOrNull()?.let { now - it < 10 * 60_000 } ?: false)
    return readingOk && relayOk
}

fun main() {
    val cfg = Config.fromEnv()
    val db = ServerDb(cfg.dbPath)
    val jobs = Executors.newScheduledThreadPool(2)

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
    } else log("relay: not configured")

    // Loopback only; Caddy forwards just /health to the outside.
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
            .put("readingAgeMin", r?.let { (now - it.timestampMs) / 60_000 } ?: JSONObject.NULL)
            .put("dexcomPolledAgoS", db.meta(Dexcom.META_POLLED_AT)?.toLongOrNull()?.let { (now - it) / 1000 } ?: JSONObject.NULL)
            .put("relayPolledAgoS", db.meta(Relay.META_POLLED_AT)?.toLongOrNull()?.let { (now - it) / 1000 } ?: JSONObject.NULL))
    }
    http.executor = Executors.newFixedThreadPool(4)
    http.start()
    log("listening on 127.0.0.1:${cfg.port}")

    Runtime.getRuntime().addShutdownHook(Thread {
        http.stop(1); jobs.shutdownNow(); db.close()
    })
}

private fun HttpExchange.json(code: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun log(msg: String) = println(msg) // journald adds the time
