package com.geoffchan.ray

import com.geoffchan.glucosewidget.ASSISTANT_INSTRUCTIONS
import com.geoffchan.glucosewidget.AiConfig
import com.geoffchan.glucosewidget.AssistantLogEntity
import com.geoffchan.glucosewidget.AssistantResult
import com.geoffchan.glucosewidget.AssistantTools
import com.geoffchan.glucosewidget.CHAT_INSTRUCTIONS
import com.geoffchan.glucosewidget.CorrectionExample
import com.geoffchan.glucosewidget.DEFAULT_AI_MODEL
import com.geoffchan.glucosewidget.InteractionContext
import com.geoffchan.glucosewidget.JournalEntity
import com.geoffchan.glucosewidget.MODE_CHAT
import com.geoffchan.glucosewidget.OUTCOME_ANSWERED
import com.geoffchan.glucosewidget.OUTCOME_SAVED
import com.geoffchan.glucosewidget.Outcome
import com.geoffchan.glucosewidget.ParseAttempt
import com.geoffchan.glucosewidget.ProposalLog
import com.geoffchan.glucosewidget.QuickQuestion
import com.geoffchan.glucosewidget.RayLoop
import com.geoffchan.glucosewidget.RayReport
import com.geoffchan.glucosewidget.RayTools
import com.geoffchan.glucosewidget.RunRequest
import com.geoffchan.glucosewidget.SCOPE_DAY
import com.geoffchan.glucosewidget.SavedItem
import com.geoffchan.glucosewidget.VOICE_INSTRUCTIONS
import com.geoffchan.glucosewidget.WatchProtocol
import com.geoffchan.glucosewidget.WatchSaveOp
import com.geoffchan.glucosewidget.correctionLessons
import com.geoffchan.glucosewidget.decodeOutcome
import com.geoffchan.glucosewidget.encodeOutcome
import com.geoffchan.glucosewidget.interactionData
import com.geoffchan.glucosewidget.planWatchSave
import com.geoffchan.glucosewidget.quickQuestion
import com.geoffchan.glucosewidget.readingText
import com.geoffchan.glucosewidget.spokenLast
import com.geoffchan.glucosewidget.spokenReading
import com.geoffchan.glucosewidget.spokenTime
import com.geoffchan.glucosewidget.voiceUserTurn
import com.geoffchan.glucosewidget.watchLabel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Ray on the server: the same loop, prompts and tools as the phones
 * (RayLoop, AssistantTools, RayTools in :core), over the server's copy of
 * her data. The chat-only tools run here: SQL on a read-only connection,
 * JavaScript in Deno with no permissions (no files, network or env), and
 * reports handed back to the asking phone.
 */
class RayService(private val db: ServerDb, private val key: String?, private val model: String, private val deno: String) {
    val configured get() = key != null

    private fun cfg() = AiConfig(key ?: throw IllegalStateException("OPENAI_API_KEY not set"), model, null)

    /** POST /v1/ray/run: one exchange for a phone (the phone records it and posts any chat message). */
    fun run(req: RunRequest): Pair<AssistantResult, List<RayReport>> = runBlocking {
        val zone = runCatching { ZoneId.of(req.zone) }.getOrDefault(ZONE)
        val reports = mutableListOf<RayReport>()
        val chat = req.mode == MODE_CHAT
        val r = RayLoop.run(
            cfg(), req.userTurn, req.effort, db, zone,
            today = LocalDate.parse(req.today),
            instructions = if (chat) CHAT_INSTRUCTIONS else ASSISTANT_INSTRUCTIONS,
            tools = if (chat) chatTools else AssistantTools.definitions,
            maxRounds = req.maxRounds,
            prior = req.prior,
            maxOutputTokens = req.maxOutputTokens,
            extraTool = if (chat) { name, args -> chatTool(name, args, zone) { reports += it } } else null,
        )
        r to reports
    }

    private val chatTools = JSONArray().apply {
        for (i in 0 until AssistantTools.definitions.length()) put(AssistantTools.definitions.get(i))
        for (i in 0 until RayTools.definitions.length()) put(RayTools.definitions.get(i))
    }

    // ---------------------------------------------------------------- chat-only tools

    private suspend fun chatTool(name: String, args: String, zone: ZoneId, onReport: (RayReport) -> Unit): String? {
        if (name != RayTools.QUERY && name != RayTools.ANALYZE && name != RayTools.REPORT) return null
        return try {
            val a = JSONObject(args)
            when (name) {
                RayTools.QUERY -> query(a.getString("sql"))
                RayTools.ANALYZE -> analyze(a.getString("code"), a.getString("from_day"), a.getString("to_day"), zone)
                else -> {
                    val file = RayTools.reportFileName(a.getString("title"), LocalDateTime.now(zone))
                    val html = RayTools.reportHtml(a.getString("title"), a.getString("html"))
                    if (html.length > RayTools.MAX_REPORT_BYTES) return JSONObject().put("error", "report too large").toString()
                    onReport(RayReport(file, html))
                    JSONObject().put("saved", file).put("shown_as", RayTools.reportTitleOf(file)).toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    fun query(sql: String): String {
        RayTools.checkQuery(sql)?.let { return JSONObject().put("error", it).toString() }
        db.readOnly().use { c ->
            c.createStatement().use { st ->
                st.queryTimeout = 10
                st.executeQuery(sql.trim().trimEnd(';')).use { rs ->
                    val md = rs.metaData
                    val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
                    val rows = mutableListOf<List<Any?>>()
                    var truncated = false
                    while (rs.next()) {
                        if (rows.size >= RayTools.MAX_QUERY_ROWS) { truncated = true; break }
                        rows += (1..md.columnCount).map { i ->
                            when (val v = rs.getObject(i)) {
                                is Double -> Math.round(v * 1000) / 1000.0
                                is Float -> Math.round(v * 1000) / 1000.0
                                is ByteArray -> "(blob)"
                                else -> v
                            }
                        }
                    }
                    return RayTools.queryResult(cols, rows, truncated)
                }
            }
        }
    }

    suspend fun analyze(code: String, fromDay: String, toDay: String, zone: ZoneId): String {
        val range = RayTools.analysisRange(fromDay, toDay)
        val start = range.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val script = RayTools.analysisScript(code, range, db.readings(start, end), db.journal(range.from.toString(), range.to.toString()), zone)
        return runJs(script)
    }

    /** Deno with no --allow flags: the script can compute, nothing else. Last expression → stdout. */
    fun runJs(script: String): String {
        val runner = File.createTempFile("ray-js", ".js").apply {
            writeText("const s = new TextDecoder().decode(await new Response(Deno.stdin.readable).arrayBuffer());\nconsole.log(String((0, eval)(s)));\n")
        }
        try {
            val p = ProcessBuilder(deno, "run", "--no-prompt", "--no-config", "--no-lock", "--quiet",
                "--v8-flags=--max-old-space-size=256", runner.path)
                .redirectErrorStream(true)
                .apply { environment().clear(); environment()["DENO_DIR"] = "/tmp/ray-deno"; environment()["NO_COLOR"] = "1" }
                .start()
            p.outputStream.use { it.write(script.toByteArray()) }
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            if (!p.waitFor(ANALYSIS_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly(); reader.join(1000)
                return JSONObject().put("error", "stopped after $ANALYSIS_TIMEOUT_S s; simplify the code").toString()
            }
            reader.join(2000)
            val text = out.toString().trim()
            if (p.exitValue() != 0) return JSONObject().put("error", text.take(2000)).toString()
            return com.geoffchan.glucosewidget.cut(text, RayTools.MAX_RESULT_CHARS)
        } finally {
            runner.delete()
        }
    }

    // ---------------------------------------------------------------- voice (Alexa, Google shortcuts)

    /** [path]: how it was answered: "quick:READING" etc., "ray", "ray-timeout", "ray-error", "off". */
    data class Spoken(val speech: String, val interaction: String?, val path: String = "ray")

    /**
     * A spoken question or log. Fixed questions (her number, last dose, last
     * meal) come straight from the data; anything else goes to Ray within
     * [deadlineMs]. Entries are saved at once and read back exactly as saved.
     */
    fun voice(text: String, via: String, source: String, deadlineMs: Long): Spoken {
        val now = ZonedDateTime.now(ZONE)
        val nowMs = now.toInstant().toEpochMilli()
        quickQuestion(text)?.let { q ->
            val speech = when (q) {
                QuickQuestion.READING -> spokenReading(db.latestReading(), nowMs)
                else -> spokenLast(q, runBlocking { db.journal(now.toLocalDate().minusDays(3).toString(), now.toLocalDate().toString()) }, now.toLocalDate().toString())
            }
            return Spoken(speech, null, "quick:$q")
        }
        if (key == null) return Spoken("Ray isn't set up on the server yet.", null, "off")
        val current = db.latestReading()?.let { readingText(it, nowMs) }
        val lessons = correctionLessons(correctionExamples())
        val turn = voiceUserTurn(text, now, current, via, lessons)
        val trace = JSONArray()
        val uid = com.geoffchan.glucosewidget.newUid()
        val ctx = InteractionContext(CLOCK.format(now), now.toLocalDate().toString(), current, turn)
        val r = try {
            runBlocking {
                withTimeout(deadlineMs) {
                    RayLoop.run(cfg(), turn, "low", db, ZONE, trace = trace, today = now.toLocalDate(), instructions = VOICE_INSTRUCTIONS, tools = AssistantTools.definitions)
                }
            }
        } catch (e: Exception) {
            val why = if (e is TimeoutCancellationException) "took longer than ${deadlineMs / 1000} s" else e.message ?: e.javaClass.simpleName
            record(uid, source, text, "none", interactionData(ctx, listOf(ParseAttempt("openai", false, why)), emptyList(), trace = trace, error = why), null)
            return Spoken(
                if (e is TimeoutCancellationException) "Sorry, that's taking me too long to answer out loud. Try asking in the Ray chat." else "Sorry, I couldn't reach Ray just now.",
                uid, if (e is TimeoutCancellationException) "ray-timeout" else "ray-error",
            )
        }
        val entries = r.entries.mapNotNull { p -> (if (p.time == null) p.copy(time = now.toLocalTime().withSecond(0).withNano(0)) else p).noteText()?.let { p to it } }
        val proposals = entries.map { ProposalLog(it.second, WatchProtocol.STATUS_NEW) }
        val data = interactionData(ctx, listOf(ParseAttempt("openai", true, null, r.ms)), proposals, r.answer, r.detail, r.rejected, r, trace)
        if (entries.isEmpty()) {
            record(uid, source, text, "openai", data, Outcome(OUTCOME_ANSWERED, System.currentTimeMillis()))
            return Spoken(r.answer.ifBlank { "Sorry, I don't have an answer for that." }, uid)
        }
        val items = save(now.toLocalDate().toString(), entries.map { it.second })
        record(uid, source, text, "openai", data, Outcome(OUTCOME_SAVED, System.currentTimeMillis(), items, note = "spoken via $via"))
        return Spoken(readBack(items, entries.map { it.first }), uid, "ray-log")
    }

    /** "Logged 4 units of short-acting at 9:12 pm and coffee at 10:30 am." — from what was saved, not the model's words. */
    private fun readBack(items: List<SavedItem>, proposed: List<com.geoffchan.glucosewidget.ProposedEntry>): String {
        val said = proposed.zip(items).map { (p, item) ->
            val what = if (p.isDose) "${p.units} units of ${p.insulinType}" else watchLabel(p)
            val at = p.time?.let { " at ${spokenTime(it)}" }.orEmpty()
            (if (item.op == "already") "$what$at was already logged" else "$what$at")
        }
        val (already, logged) = said.partition { it.endsWith("was already logged") }
        return listOfNotNull(
            logged.takeIf { it.isNotEmpty() }?.let { "Logged " + joinSpoken(it) + "." },
            already.takeIf { it.isNotEmpty() }?.let { joinSpoken(it).replaceFirstChar(Char::uppercase) + "." },
        ).joinToString(" ")
    }

    private fun joinSpoken(parts: List<String>) = if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

    /** Saves [texts] on [day] like the watch does (guesses/routine rows confirmed, exact repeats skipped). */
    fun save(day: String, texts: List<String>): List<SavedItem> = db.txAs(ServerDb.ORIGIN_SERVER) {
        runBlocking {
            val now = System.currentTimeMillis()
            val items = mutableListOf<SavedItem>()
            for (op in planWatchSave(texts, journal(day, day))) when (op) {
                is WatchSaveOp.Insert -> {
                    val row = JournalEntity(day = day, text = op.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY)
                    insertJournal(row)
                    items += SavedItem(op.text, op.text, row.uid, "insert")
                }
                is WatchSaveOp.Confirm -> {
                    updateJournal(op.guess.copy(text = op.text, updatedAtMs = now))
                    items += SavedItem(op.text, op.text, op.guess.uid, "confirm")
                }
                is WatchSaveOp.Already -> items += SavedItem(op.text, op.text, null, "already")
            }
            items
        }
    }

    private fun record(uid: String, source: String, input: String, parser: String, data: JSONObject, outcome: Outcome?) {
        val now = System.currentTimeMillis()
        db.txAs(ServerDb.ORIGIN_SERVER) {
            runBlocking {
                insertInteraction(
                    AssistantLogEntity(uid = uid, createdAtMs = now, updatedAtMs = now, source = source, input = input, parser = parser,
                        full = true, data = data.toString(), outcome = outcome?.let { encodeOutcome(it) }),
                )
            }
        }
    }

    private fun correctionExamples(): List<CorrectionExample> = db.correctedInteractions(40).flatMap { r ->
        decodeOutcome(r.outcome)?.corrections.orEmpty().map { CorrectionExample(r.input, it) }
    }.sortedByDescending { it.correction.atMs }

    companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        private val CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)
        const val ANALYSIS_TIMEOUT_S = 20L
        const val SOURCE_ALEXA = "alexa"
        const val SOURCE_GOOGLE = "google"
        val DEFAULT_MODEL = DEFAULT_AI_MODEL
    }
}
