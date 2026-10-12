package com.geoffchan.glucosewidget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object Assistant {
    private const val TAG = "Assistant"

    /** Ray can answer: through the Ray server, or on this phone with its own OpenAI key. */
    fun configured(context: Context): Boolean = Store.rayServer(context) != null || Store.aiConfig(context) != null

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun roomData(context: Context) = object : AssistantData {
        private val dao = GlucoseDb.get(context).dao()
        override suspend fun readings(startMs: Long, endMs: Long) = dao.readingsIn(startMs, endMs)
        override suspend fun journal(firstDay: String, lastDay: String) = dao.dayJournalBetween(firstDay, lastDay)
        override suspend fun journalById(id: Long) = dao.journalById(id)
    }

    /**
     * One exchange: her [message] → answer + proposed rows. Throws
     * [AssistantException] (or times out) so callers can fall back. [trace]
     * collects the rounds as they happen, so a caller logging a failure
     * still has what got that far. [history]: the conversation so far when
     * she's replying to an earlier answer.
     */
    suspend fun ask(
        context: Context,
        message: String,
        targetDay: LocalDate,
        fromWatch: Boolean,
        timeoutMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        trace: JSONArray = JSONArray(),
        history: List<Turn> = emptyList(),
    ): AssistantResult = withTimeout(timeoutMs) {
        val server = Store.rayServer(context)
        val cfg = Store.aiConfig(context)
        if (server == null && cfg == null) throw AssistantException("assistant not configured")
        val now = ZonedDateTime.now(zone)
        val lessons = correctionLessons(InteractionLog.correctionExamples(context))
        val turn = assistantUserTurn(message, now, targetDay, fromWatch, InteractionLog.currentReading(context), lessons)
        // a reply to a question, or anything touching other days, deserves more thought than a plain log
        val effort = cfg?.effort ?: if (history.isNotEmpty()) "medium" else defaultEffort(message)
        if (server != null) {
            try {
                return@withTimeout onServer(context, server, MODE_LOG, turn, effort, historyInput(history), now.toLocalDate(), zone, RayLoop.MAX_ROUNDS, 8_000, trace).first
            } catch (e: RayServer.RayServerException) {
                if (cfg == null || !e.fallBack) throw AssistantException(e.message ?: "Ray server failed")
                log("server failed (${e.message}); answering on the phone")
            }
        }
        run(cfg!!, turn, effort, roomData(context), zone, trace = trace, history = history, today = now.toLocalDate())
    }

    /**
     * One exchange on the Ray server (cloud Ray), after sending this phone's
     * pending changes so the server's copy is current. The server's trace is
     * appended to [trace], marked {"via":"server"}.
     */
    suspend fun onServer(
        context: Context,
        server: Store.RayServerConfig,
        mode: String,
        turn: String,
        effort: String,
        prior: List<JSONObject>,
        today: LocalDate,
        zone: ZoneId,
        maxRounds: Int,
        maxOutputTokens: Int,
        trace: JSONArray,
    ): Pair<AssistantResult, List<RayReport>> {
        Sync.pushBeforeAsk(context, if (mode == MODE_CHAT) 5_000 else 2_500)
        val (r, reports) = RayServer.run(server, RunRequest(mode, turn, effort, prior, today.toString(), zone.id, maxRounds, maxOutputTokens))
        trace.put(JSONObject().put("via", "server"))
        for (i in 0 until r.trace.length()) trace.put(r.trace.get(i))
        log("server: model=${r.model} effort=$effort ms=${r.ms} rows=${r.entries.size} changes=${r.changes.size}")
        return r to reports
    }

    /** The tool loop ([RayLoop.run]); see there for the parameters. */
    suspend fun run(
        cfg: AiConfig,
        userTurn: String,
        effort: String,
        data: AssistantData,
        zone: ZoneId,
        trace: JSONArray = JSONArray(),
        history: List<Turn> = emptyList(),
        today: LocalDate = LocalDate.now(zone),
        instructions: String = ASSISTANT_INSTRUCTIONS,
        tools: JSONArray = AssistantTools.definitions,
        maxRounds: Int = RayLoop.MAX_ROUNDS,
        prior: List<JSONObject> = historyInput(history),
        maxOutputTokens: Int = 8_000,
        extraTool: (suspend (name: String, args: String) -> String?)? = null,
        transport: suspend (key: String, body: JSONObject) -> JSONObject = RayLoop::post,
    ): AssistantResult {
        RayLoop.log = ::log
        return RayLoop.run(cfg, userTurn, effort, data, zone, trace, history, today, instructions, tools, maxRounds, prior, maxOutputTokens, extraTool, transport)
    }

    // android.util.Log isn't available in JVM unit tests
    private fun log(msg: String) { runCatching { Log.i(TAG, msg) } }

}
