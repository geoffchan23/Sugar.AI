package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phones ↔ Ray server API (cloud Ray). Every call carries a device token
 * (`Authorization: Bearer …`). See server/README.md.
 *
 *   POST /v1/sync     {"device","cursor"?, rows, tombs, inter, chat}  (SyncCore payload fields)
 *                     → {"cursor", "more", rows, tombs, inter, chat}: what changed since cursor, from other devices
 *   POST /v1/ray/run  [RunRequest] → [encodeRunResult]: one Ray exchange on the server's data
 *   POST /v1/voice    {"text"} → {"speech"}: a spoken question or log (Google shortcuts; Alexa uses /v1/alexa)
 */
const val MODE_LOG = "log" // watch / Ask: watch-sized answer + rows ([ASSISTANT_INSTRUCTIONS])
const val MODE_CHAT = "chat" // the chat screen, full toolset ([CHAT_INSTRUCTIONS])

data class RunRequest(
    val mode: String,
    val userTurn: String,
    val effort: String,
    val prior: List<JSONObject>,
    val today: String,
    val zone: String,
    val maxRounds: Int,
    val maxOutputTokens: Int,
)

fun encodeRunRequest(r: RunRequest): String = JSONObject()
    .put("mode", r.mode).put("userTurn", r.userTurn).put("effort", r.effort)
    .put("prior", JSONArray(r.prior)).put("today", r.today).put("zone", r.zone)
    .put("maxRounds", r.maxRounds).put("maxOutputTokens", r.maxOutputTokens)
    .toString()

fun decodeRunRequest(json: String): RunRequest? = runCatching {
    val o = JSONObject(json)
    val prior = o.optJSONArray("prior") ?: JSONArray()
    RunRequest(
        mode = o.getString("mode").takeIf { it == MODE_LOG || it == MODE_CHAT }!!,
        userTurn = o.getString("userTurn"),
        effort = o.optString("effort", "low").takeIf { it in setOf("minimal", "low", "medium", "high") } ?: "low",
        prior = (0 until prior.length()).map { prior.getJSONObject(it) },
        today = o.getString("today"),
        zone = o.optString("zone", "America/Toronto"),
        maxRounds = o.optInt("maxRounds", RayLoop.MAX_ROUNDS).coerceIn(1, 16),
        maxOutputTokens = o.optInt("maxOutputTokens", 8_000).coerceIn(500, 32_000),
    )
}.getOrNull()

/** A report Ray saved during a chat exchange (save_report); the asking phone stores it. */
data class RayReport(val name: String, val html: String)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun encodeProposed(p: ProposedEntry): JSONObject = JSONObject()
    .put("isDose", p.isDose)
    .put("insulinType", p.insulinType ?: JSONObject.NULL)
    .put("units", p.units ?: JSONObject.NULL)
    .put("name", p.name ?: JSONObject.NULL)
    .put("time", p.time?.format(HHMM) ?: JSONObject.NULL)

fun decodeProposed(o: JSONObject): ProposedEntry = ProposedEntry(
    isDose = o.getBoolean("isDose"),
    insulinType = o.optNullableString("insulinType"),
    units = if (o.isNull("units")) null else o.optInt("units"),
    name = o.optNullableString("name"),
    time = o.optNullableString("time")?.let { LocalTime.parse(it, HHMM) },
)

fun encodeRunResult(r: AssistantResult, reports: List<RayReport> = emptyList()): String = JSONObject()
    .put("answer", r.answer).put("detail", r.detail ?: JSONObject.NULL)
    .put("entries", JSONArray(r.entries.map { encodeProposed(it) })).put("rejected", r.rejected)
    .put("model", r.model).put("inputTokens", r.inputTokens).put("cachedTokens", r.cachedTokens)
    .put("outputTokens", r.outputTokens).put("ms", r.ms).put("reasoningTokens", r.reasoningTokens)
    .put("effort", r.effort ?: JSONObject.NULL).put("userTurn", r.userTurn ?: JSONObject.NULL)
    .put("trace", r.trace).put("changes", encodeChanges(r.changes)).put("changeRejects", JSONArray(r.changeRejects))
    .put("awaitingAnswer", r.awaitingAnswer)
    .put("reports", JSONArray(reports.map { JSONObject().put("name", it.name).put("html", it.html) }))
    .toString()

fun decodeRunResult(json: String): Pair<AssistantResult, List<RayReport>> {
    val o = JSONObject(json)
    val entries = o.optJSONArray("entries") ?: JSONArray()
    val rejects = o.optJSONArray("changeRejects") ?: JSONArray()
    val reports = o.optJSONArray("reports") ?: JSONArray()
    val r = AssistantResult(
        answer = o.optString("answer"), detail = o.optNullableString("detail"),
        entries = (0 until entries.length()).map { decodeProposed(entries.getJSONObject(it)) }, rejected = o.optInt("rejected"),
        model = o.optString("model"), inputTokens = o.optInt("inputTokens"), cachedTokens = o.optInt("cachedTokens"),
        outputTokens = o.optInt("outputTokens"), ms = o.optLong("ms"), reasoningTokens = o.optInt("reasoningTokens"),
        effort = o.optNullableString("effort"), userTurn = o.optNullableString("userTurn"),
        trace = o.optJSONArray("trace") ?: JSONArray(),
        changes = decodeChanges(o.optJSONArray("changes")),
        changeRejects = (0 until rejects.length()).map { rejects.getString(it) },
        awaitingAnswer = o.optBoolean("awaitingAnswer"),
    )
    return r to (0 until reports.length()).map { reports.getJSONObject(it).let { x -> RayReport(x.getString("name"), x.getString("html")) } }
}

/** Her reading as the prompts show it: "6.2 mmol/L Flat (3 min ago)". */
fun readingText(r: Reading, nowMs: Long): String = "${mmolText(r.mgdl)} mmol/L ${r.trend} (${(nowMs - r.timestampMs) / 60_000} min ago)"

// ---------------------------------------------------------------- voice (Alexa, Google shortcuts)

/** Ray out loud: someone at home asks a smart speaker or a phone's voice assistant. */
val VOICE_INSTRUCTIONS = RAY_CORE + "\n\n" + """
Out loud (voice):
- Geoff or Francine asked out loud, through Alexa at home or a "Hey Google" shortcut on a phone. Your answer is read aloud by a speaker, maybe with others in the room. You can't tell who is speaking: when it isn't clear, talk about Francine by name.
- If they're telling you something to log, call propose_entries. Spoken entries are saved straight away with no confirm screen, so get them right, and if anything is unclear (amount, insulin type) ask instead and propose nothing. Changes to what's already logged (propose_changes) aren't possible by voice: say to use the Ray chat for that.
- Finish by calling reply exactly once. answer: what the speaker says, one to three short spoken sentences. No lists, symbols, emoji, markdown or units abbreviations: say "mmol" and "units"; say times like "9:12 pm". detail: null.
- When you logged something, answer with exactly what you logged, as logging, never as advice: "Logged 4 units of short-acting at 9:12 pm."
""".trim()

private val VOICE_CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)

fun voiceUserTurn(text: String, now: ZonedDateTime, current: String?, via: String, lessons: String = ""): String = buildString {
    appendLine("Now: ${VOICE_CLOCK.format(now)} (America/Toronto).")
    appendLine("Francine's current glucose: ${current ?: "unknown"}.")
    appendLine("Entries go on: ${now.toLocalDate()} (today).")
    appendLine("Said out loud through $via just now: anything without a stated time happened at ${"%02d:%02d".format(now.hour, now.minute)}.")
    if (lessons.isNotBlank()) { appendLine(); appendLine(lessons) }
    appendLine()
    append("Spoken: ").append(text.trim())
}

/** 21:12 → "9:12 pm" (speech). */
fun spokenTime(t: LocalTime): String {
    val h = t.hour % 12
    return "${if (h == 0) 12 else h}:${"%02d".format(t.minute)} ${if (t.hour < 12) "am" else "pm"}"
}

/** "6.2" → "6 point 2" so every speaker reads it the same. */
fun spokenMmol(mgdl: Int): String = mmolText(mgdl).replace(".", " point ")

fun spokenTrend(trend: String): String = when (trend) {
    "DoubleUp" -> "rising fast"
    "SingleUp" -> "rising"
    "FortyFiveUp" -> "rising slowly"
    "Flat" -> "steady"
    "FortyFiveDown" -> "falling slowly"
    "SingleDown" -> "falling"
    "DoubleDown" -> "falling fast"
    else -> ""
}

/** "Francine is 6 point 2 and steady, as of 3 minutes ago." — no model needed. */
fun spokenReading(r: Reading?, nowMs: Long): String {
    if (r == null) return "I don't have a reading for Francine right now."
    val ageMin = (nowMs - r.timestampMs) / 60_000
    if (ageMin > 30) return "Francine's last reading was ${spokenMmol(r.mgdl)}, but that was $ageMin minutes ago, so it may be out of date."
    val trend = spokenTrend(r.trend).takeIf { it.isNotEmpty() }?.let { " and $it" }.orEmpty()
    val age = when (ageMin) { 0L -> "just now"; 1L -> "a minute ago"; else -> "$ageMin minutes ago" }
    val low = if (mmolValue(r.mgdl) < LOW_MMOL) " That's low." else ""
    return "Francine is ${spokenMmol(r.mgdl)}$trend, as of $age.$low"
}

/** Kinds of fixed question answered from the data without the model (fast, for Alexa's 8 s). */
enum class QuickQuestion { READING, LAST_DOSE, LAST_MEAL }

fun quickQuestion(text: String): QuickQuestion? {
    val t = text.lowercase(Locale.ROOT).replace(Regex("[^a-z' ]"), " ")
    val sugar = Regex("""\b(sugar|glucose|blood sugar|number|level|reading|bg)\b""").containsMatchIn(t)
    val time = Regex("""\b(when|last|latest|recent)\b""").containsMatchIn(t)
    val dose = Regex("""\b(insulin|medicine|medication|dose|shot|units|bolus)\b""").containsMatchIn(t)
    val meal = Regex("""\b(eat|ate|eaten|meal|food|snack|lunch|dinner|breakfast)\b""").containsMatchIn(t)
    val compare = Regex("""\b(why|how come|yesterday|week|average|trend|pattern|night|overnight|compare|today's)\b""").containsMatchIn(t)
    return when {
        compare -> null
        dose && time -> QuickQuestion.LAST_DOSE
        meal && time -> QuickQuestion.LAST_MEAL
        sugar && !dose && !meal -> QuickQuestion.READING
        else -> null
    }
}

/** "Francine's last dose was 4 units of short-acting at 9:12 pm today." from recent journal rows (newest days last). */
fun spokenLast(kind: QuickQuestion, rows: List<JournalEntity>, today: String): String {
    data class Hit(val day: String, val time: LocalTime, val say: String)
    val hits = rows.mapNotNull { e ->
        if (e.scope != SCOPE_DAY) return@mapNotNull null
        val p = proposedFromText(e.text.removeSuffix(GUESS_SUFFIX)) ?: return@mapNotNull null
        val t = p.time ?: return@mapNotNull null
        when {
            kind == QuickQuestion.LAST_DOSE && p.isDose ->
                Hit(e.day, t, "${p.units} units of ${p.insulinType}")
            kind == QuickQuestion.LAST_MEAL && !p.isDose -> Hit(e.day, t, p.name.orEmpty())
            else -> null
        }
    }
    val last = hits.maxWithOrNull(compareBy({ it.day }, { it.time })) ?: return when (kind) {
        QuickQuestion.LAST_DOSE -> "I don't see a dose logged in the last few days."
        else -> "I don't see anything she ate logged in the last few days."
    }
    val whenDay = if (last.day == today) "today" else if (last.day == java.time.LocalDate.parse(today).minusDays(1).toString()) "yesterday" else "on ${last.day}"
    return when (kind) {
        QuickQuestion.LAST_DOSE -> "Francine's last logged dose was ${last.say} at ${spokenTime(last.time)} $whenDay."
        else -> "The last thing Francine logged eating was ${last.say} at ${spokenTime(last.time)} $whenDay."
    }
}
