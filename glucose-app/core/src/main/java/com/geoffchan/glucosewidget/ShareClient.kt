package com.geoffchan.glucosewidget

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the unofficial Dexcom Share API — the same endpoints
 * xDrip/Nightscout/pydexcom use. shareous1 is the outside-US server
 * (Canada). Unsupported by Dexcom but stable for ~a decade.
 */
object ShareClient {
    private const val BASE = "https://shareous1.dexcom.com/ShareWebServices/Services"
    private const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    class ShareException(message: String) : Exception(message)

    private fun post(path: String, body: String): String {
        val req = Request.Builder()
            .url("$BASE/$path")
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("User-Agent", "Dexcom Share/3.0.2.11 CFNetwork/711.2.23 Darwin/14.0.0")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ShareException("HTTP ${resp.code}: ${text.take(200)}")
            return text
        }
    }

    /** Returns a session id for the wearer's account. */
    fun login(username: String, password: String): String {
        val creds = JSONObject()
            .put("accountName", username)
            .put("password", password)
            .put("applicationId", APPLICATION_ID)
            .toString()
        val accountId = post("General/AuthenticatePublisherAccount", creds).trim('"')
        val byId = JSONObject()
            .put("accountId", accountId)
            .put("password", password)
            .put("applicationId", APPLICATION_ID)
            .toString()
        return post("General/LoginPublisherAccountById", byId).trim('"')
    }

    private fun latestBody(sessionId: String): String =
        post("Publisher/ReadPublisherLatestGlucoseValues?sessionId=$sessionId&minutes=1440&maxCount=1", "")

    private fun historyBody(sessionId: String): String =
        post("Publisher/ReadPublisherLatestGlucoseValues?sessionId=$sessionId&minutes=1440&maxCount=288", "")

    /**
     * Fetch the trailing 24 h of readings, newest first. Same session
     * handling as [fetchLatest]. Backfills gaps left by phone downtime.
     */
    fun fetchHistory(username: String, password: String, cachedSession: String?): Pair<List<Reading>, String> {
        if (!cachedSession.isNullOrEmpty()) {
            runCatching {
                val rs = parseReadings(historyBody(cachedSession))
                if (rs.isNotEmpty()) return rs to cachedSession
            }
        }
        val session = login(username, password)
        val rs = parseReadings(historyBody(session))
        if (rs.isEmpty()) throw ShareException("No readings returned — is Share sharing active?")
        return rs to session
    }

    /**
     * Fetch the latest reading, reusing [cachedSession] when possible and
     * re-authenticating once when it has expired. Returns the reading and
     * the session id that worked.
     */
    fun fetchLatest(username: String, password: String, cachedSession: String?): Pair<Reading, String> {
        if (!cachedSession.isNullOrEmpty()) {
            runCatching {
                val r = parseLatestReading(latestBody(cachedSession))
                if (r != null) return r to cachedSession
            }
        }
        val session = login(username, password)
        val reading = parseLatestReading(latestBody(session))
            ?: throw ShareException("No readings returned — is Share sharing active?")
        return reading to session
    }
}
