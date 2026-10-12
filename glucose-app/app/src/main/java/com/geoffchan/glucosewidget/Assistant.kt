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
    private const val URL = "https://api.openai.com/v1/responses"
    private const val MAX_ROUNDS = 6
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // a chat round that writes analysis code can take a while
        .build()

    fun configured(context: Context): Boolean = Store.aiConfig(context) != null

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
        val cfg = Store.aiConfig(context) ?: throw AssistantException("assistant not configured")
        val now = ZonedDateTime.now(zone)
        val lessons = correctionLessons(InteractionLog.correctionExamples(context))
        val turn = assistantUserTurn(message, now, targetDay, fromWatch, InteractionLog.currentReading(context), lessons)
        // a reply to a question, or anything touching other days, deserves more thought than a plain log
        val effort = cfg.effort ?: if (history.isNotEmpty()) "medium" else defaultEffort(message)
        run(cfg, turn, effort, roomData(context), zone, trace = trace, history = history, today = now.toLocalDate())
    }

    /**
     * The tool loop. Every output item (reasoning included) is replayed:
     * store=false. [transport] is the HTTP call (a fake in tests).
     *
     * The watch and Ask dialog use the defaults. The chat ([RayChat]) passes
     * its own [instructions], a longer [tools] list, the thread as [prior]
     * and [extraTool] for the tools only it has (queries, analysis, reports);
     * [extraTool] returns null for names it doesn't handle.
     */
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
        maxRounds: Int = MAX_ROUNDS,
        prior: List<JSONObject> = historyInput(history),
        maxOutputTokens: Int = 8_000,
        extraTool: (suspend (name: String, args: String) -> String?)? = null,
        transport: suspend (key: String, body: JSONObject) -> JSONObject = ::post,
    ): AssistantResult {
        val t0 = System.currentTimeMillis()
        val input = JSONArray(prior).put(JSONObject().put("role", "user").put("content", userTurn))
        val proposed = mutableListOf<ProposedEntry>()
        val changes = mutableListOf<ChangeOp>()
        val changeRejects = mutableListOf<String>()
        var awaiting = false
        var rejected = 0
        var answer: String? = null
        var detail: String? = null
        var inTok = 0; var cachedTok = 0; var outTok = 0; var reasoningTok = 0
        var lastText: String? = null

        for (round in 1..maxRounds) {
            val body = JSONObject()
                .put("model", cfg.model)
                .put("instructions", instructions)
                .put("input", input)
                .put("tools", tools)
                .put("reasoning", JSONObject().put("effort", effort))
                .put("store", false)
                .put("max_output_tokens", maxOutputTokens)
            val res = transport(cfg.key, body)
            val roundLog = JSONObject().put("round", round)
            val roundCalls = JSONArray()
            res.optJSONObject("usage")?.let { u ->
                inTok += u.optInt("input_tokens"); outTok += u.optInt("output_tokens")
                cachedTok += u.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0
                reasoningTok += u.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens") ?: 0
                roundLog.put(
                    "usage",
                    JSONObject().put("input", u.optInt("input_tokens")).put("output", u.optInt("output_tokens"))
                        .put("cached", u.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0)
                        .put("reasoning", u.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens") ?: 0),
                )
            }
            roundLog.put("calls", roundCalls)
            trace.put(roundLog)
            val output = res.optJSONArray("output") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                input.put(item)
                when (item.optString("type")) {
                    "function_call" -> calls += item
                    "message" -> lastText = messageText(item)?.also { roundLog.put("text", it) } ?: lastText
                }
            }
            if (calls.isEmpty()) break // spoke without calling reply: use its text
            var replied = false
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optString("arguments", "{}")
                val result = when (name) {
                    AssistantTools.PROPOSE -> {
                        val b = AssistantTools.proposals(args)
                        proposed += b.entries; rejected += b.rejected
                        JSONObject().put("shown_to_her", b.entries.size).put("rejected", b.rejected).toString()
                    }
                    AssistantTools.PROPOSE_CHANGES -> {
                        val cs = parseChanges(args, today) { data.journalById(it) }
                        changes += cs.ops; changeRejects += cs.rejected
                        JSONObject().put("shown_to_her", cs.ops.size).put("rejected", JSONArray(cs.rejected)).toString()
                    }
                    AssistantTools.REPLY -> {
                        val a = runCatching { JSONObject(args) }.getOrNull()
                        answer = a?.optString("answer").orEmpty()
                        detail = a?.takeIf { !it.isNull("detail") }?.optString("detail")?.takeIf { it.isNotBlank() }
                        awaiting = a?.optBoolean("awaiting_answer") ?: false
                        replied = true
                        "{\"ok\":true}"
                    }
                    else -> extraTool?.invoke(name, args) ?: AssistantTools.run(name, args, data, zone)
                }
                input.put(JSONObject().put("type", "function_call_output").put("call_id", c.optString("call_id")).put("output", result))
                roundCalls.put(
                    JSONObject().put("name", name)
                        .put("args", runCatching { JSONObject(args) }.getOrElse { cut(args, MAX_TOOL_RESULT_CHARS) })
                        .put("result", cut(result, MAX_TOOL_RESULT_CHARS)),
                )
            }
            if (replied) break
            if (round == maxRounds) log("stopped after $maxRounds rounds")
        }

        val finalAnswer = answer ?: lastText.orEmpty().trim()
        val result = AssistantResult(
            answer = finalAnswer, detail = detail, entries = proposed, rejected = rejected,
            model = cfg.model, inputTokens = inTok, cachedTokens = cachedTok, outputTokens = outTok,
            ms = System.currentTimeMillis() - t0,
            reasoningTokens = reasoningTok, effort = effort, userTurn = userTurn, trace = trace,
            changes = changes, changeRejects = changeRejects, awaitingAnswer = awaiting && proposed.isEmpty() && changes.isEmpty(),
        )
        log(
            "model=${cfg.model} effort=$effort ms=${result.ms} tokens in=$inTok (cached $cachedTok) out=$outTok " +
                "cost≈$${"%.5f".format(result.estimatedCostUsd)} rows=${proposed.size} rejected=$rejected " +
                "changes=${changes.size} changeRejects=${changeRejects.size} awaiting=${result.awaitingAnswer} history=${history.size}",
        )
        return result
    }

    // android.util.Log isn't available in JVM unit tests
    private fun log(msg: String) { runCatching { Log.i(TAG, msg) } }

    private fun messageText(item: JSONObject): String? {
        val content = item.optJSONArray("content") ?: return null
        return (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "output_text" }
            .joinToString("") { it.optString("text") }
            .takeIf { it.isNotBlank() }
    }

    suspend fun post(key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw AssistantException("OpenAI ${resp.code}: ${msg ?: text.take(200)}")
            }
            JSONObject(text)
        }
    }
}
