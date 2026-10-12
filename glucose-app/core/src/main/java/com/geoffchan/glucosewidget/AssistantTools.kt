package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * The assistant's tools (see [Assistant]): read-only views of her readings and
 * journal, plus [PROPOSE] and [PROPOSE_CHANGES] which only collect rows and
 * changes for her to confirm — the model never writes anything. Handlers are pure over [AssistantData], so the
 * phone uses Room and tests use lists.
 */
interface AssistantData {
    suspend fun readings(startMs: Long, endMs: Long): List<ReadingEntity>
    suspend fun journal(firstDay: String, lastDay: String): List<JournalEntity>
    suspend fun journalById(id: Long): JournalEntity?
}

const val LOW_MMOL = 3.9
const val HIGH_MMOL = 10.0
private const val VERY_LOW_MMOL = 3.0
private const val VERY_HIGH_MMOL = 13.9
private const val MGDL_PER_MMOL = 18.0182
private const val MAX_POINTS = 150
private const val MAX_DAYS = 31

private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

object AssistantTools {
    const val READINGS = "get_readings"
    const val STATS = "get_stats"
    const val JOURNAL = "get_journal"
    const val LOWS = "get_lows"
    const val PROPOSE = "propose_entries"
    const val PROPOSE_CHANGES = "propose_changes"
    const val REPLY = "reply"

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = props.map { it.first }) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun nullable(type: String, desc: String) = JSONObject().put("type", JSONArray(listOf(type, "null"))).put("description", desc)

    private fun fn(name: String, desc: String, params: JSONObject) = JSONObject()
        .put("type", "function").put("name", name).put("description", desc)
        .put("parameters", params).put("strict", true)

    private val DAY_RANGE = obj(
        "from_day" to str("First local day, YYYY-MM-DD"),
        "to_day" to str("Last local day, YYYY-MM-DD (inclusive)"),
    )

    /** A dose or event; for propose_changes the new values (delete: all null). */
    private val ENTRY_FIELDS = arrayOf(
        "kind" to JSONObject().put("type", JSONArray(listOf("string", "null"))).put("enum", JSONArray(listOf("dose", "event", JSONObject.NULL))),
        "type" to JSONObject().put("type", JSONArray(listOf("string", "null"))).put("enum", JSONArray(listOf("short-acting", "long-acting", JSONObject.NULL)))
            .put("description", "Insulin type for doses; null for events"),
        "units" to nullable("integer", "Whole insulin units 1-100 for doses; null for events"),
        "name" to nullable("string", "Food/drink/activity for events, in her words with every amount kept; null for doses"),
        "time" to nullable("string", "24-hour HH:mm, or null if it can't be told"),
    )

    /** Responses API `tools` array. Stable order and text: part of the cached prefix. */
    val definitions: JSONArray = JSONArray(listOf(
        fn(READINGS, "CGM readings (mmol/L, local time) between two local times, downsampled to at most $MAX_POINTS points, with min/max/mean.",
            obj("from" to str("Start, local \"YYYY-MM-DD HH:mm\""), "to" to str("End, local \"YYYY-MM-DD HH:mm\""))),
        fn(STATS, "Standard CGM metrics for a range of days: time in range 3.9-10, below 3.9 / 3.0, above 10 / 13.9, mean, CV, GMI, coverage.", DAY_RANGE),
        fn(JOURNAL, "Logged doses and food/activity for a range of days, in time order, each with its id (for propose_changes). Entries marked guess=true were inferred from the glucose curve and not confirmed.", DAY_RANGE),
        fn(LOWS, "Every low (below 3.9) in a range of days with its nadir and duration, what was logged in the 3 hours before it, and the glucose 3h/2h/1h before.", DAY_RANGE),
        fn(PROPOSE, "Propose new journal entries for the day entries go on, for her to confirm on screen. Call once with every entry from the message. Nothing is saved unless she confirms.",
            obj("entries" to JSONObject().put("type", "array").put("items", obj(*ENTRY_FIELDS)))),
        fn(PROPOSE_CHANGES, "Propose changes to her log for her to confirm on screen: edit or delete existing entries (by id from get_journal), or add entries on any of the last $MAX_CHANGE_DAYS days. Call once with every change. Nothing is saved unless she confirms.",
            obj("changes" to JSONObject().put("type", "array").put("items", obj(
                "action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "edit", "delete"))),
                "id" to nullable("integer", "The entry's id from get_journal for edit/delete; null for add"),
                "day" to nullable("string", "YYYY-MM-DD for add; null for edit/delete"),
                *ENTRY_FIELDS,
            )))),
        fn(REPLY, "Your final reply. Call exactly once, last.",
            obj("answer" to str("One or two short sentences for a watch screen. Empty string if she only logged something and there's nothing to add."),
                "detail" to nullable("string", "Optional fuller explanation for the phone; null if none"),
                "awaiting_answer" to JSONObject().put("type", "boolean")
                    .put("description", "true only if answer asks her a question you need answered before you can log or change anything"))),
    ))

    /** Runs one tool call; returns the JSON string handed back to the model. */
    suspend fun run(name: String, argsJson: String, data: AssistantData, zone: ZoneId): String = try {
        val a = JSONObject(argsJson)
        when (name) {
            READINGS -> readings(data, zone, parseLocal(a.getString("from")), parseLocal(a.getString("to")))
            STATS -> stats(data, zone, days(a))
            JOURNAL -> journal(data, days(a))
            LOWS -> lows(data, zone, days(a))
            else -> JSONObject().put("error", "unknown tool $name")
        }.toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
    }

    private fun parseLocal(s: String): LocalDateTime = LocalDateTime.parse(s.trim().replace('T', ' '), LOCAL)

    private fun days(a: JSONObject): Pair<LocalDate, LocalDate> {
        val from = LocalDate.parse(a.getString("from_day"))
        val to = LocalDate.parse(a.getString("to_day"))
        require(!to.isBefore(from)) { "to_day is before from_day" }
        return from to (if (to.isAfter(from.plusDays(MAX_DAYS - 1L))) from.plusDays(MAX_DAYS - 1L) else to)
    }

    private fun mmol(r: ReadingEntity) = r.mgdl / MGDL_PER_MMOL
    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun local(ms: Long, zone: ZoneId) = LOCAL.format(Instant.ofEpochMilli(ms).atZone(zone))
    private fun ms(t: LocalDateTime, zone: ZoneId) = t.atZone(zone).toInstant().toEpochMilli()

    suspend fun readings(data: AssistantData, zone: ZoneId, from: LocalDateTime, to: LocalDateTime): JSONObject {
        val rs = data.readings(ms(from, zone), ms(to, zone))
        val out = JSONObject().put("from", LOCAL.format(from)).put("to", LOCAL.format(to)).put("count", rs.size)
        if (rs.isEmpty()) return out.put("note", "no readings in this range")
        val v = rs.map { mmol(it) }
        out.put("min", r1(v.min())).put("max", r1(v.max())).put("mean", r1(v.average()))
        // bucket-average down to MAX_POINTS so a day fits; keeps the curve's shape
        val per = (rs.size + MAX_POINTS - 1) / MAX_POINTS
        val pts = rs.chunked(per).map { c ->
            JSONArray(listOf(local(c[c.size / 2].timestampMs, zone), r1(c.map { mmol(it) }.average())))
        }
        return out.put("points_every_minutes", per * 5).put("points", JSONArray(pts))
    }

    /** Same metrics as analysis/report.py. */
    fun metrics(values: List<Double>, days: Int): JSONObject {
        val n = values.size
        if (n == 0) return JSONObject().put("count", 0)
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / n)
        fun pct(p: (Double) -> Boolean) = Math.round(values.count(p) * 1000.0 / n) / 10.0
        return JSONObject()
            .put("count", n)
            .put("coverage_pct", Math.round(n * 1000.0 / (days * 288)) / 10.0)
            .put("mean", r1(mean))
            .put("cv_pct", if (mean > 0) Math.round(sd / mean * 1000) / 10.0 else 0.0)
            .put("gmi_pct", r1(3.31 + 0.02392 * mean * MGDL_PER_MMOL))
            .put("in_range_pct", pct { it in LOW_MMOL..HIGH_MMOL })
            .put("below_3_9_pct", pct { it < LOW_MMOL })
            .put("below_3_0_pct", pct { it < VERY_LOW_MMOL })
            .put("above_10_pct", pct { it > HIGH_MMOL })
            .put("above_13_9_pct", pct { it > VERY_HIGH_MMOL })
            .put("min", r1(values.min())).put("max", r1(values.max()))
    }

    suspend fun stats(data: AssistantData, zone: ZoneId, range: Pair<LocalDate, LocalDate>): JSONObject {
        val (from, to) = range
        val rs = data.readings(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val days = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        return metrics(rs.map { mmol(it) }, days).put("from_day", from.toString()).put("to_day", to.toString())
    }

    /** One journal row as the model sees it. */
    fun entryJson(e: JournalEntity): JSONObject? {
        val guess = isGuessEntry(e.text)
        parseDoseNote(e.text)?.let { d ->
            return JSONObject().put("id", e.id).put("day", e.day).put("time", "%02d:%02d".format(d.time.hour, d.time.minute))
                .put("kind", "dose").put("type", d.insulinType).put("units", d.units).put("guess", guess)
        }
        parseEventNote(e.text)?.let { ev ->
            return JSONObject().put("id", e.id).put("day", e.day).put("time", "%02d:%02d".format(ev.time.hour, ev.time.minute))
                .put("kind", "event").put("name", ev.name).put("guess", guess)
        }
        return null
    }

    private fun sortKey(e: JournalEntity) = e.day + "%04d".format(entryMinute(e.text) ?: 0)

    suspend fun journal(data: AssistantData, range: Pair<LocalDate, LocalDate>): JSONObject {
        val rows = data.journal(range.first.toString(), range.second.toString())
            .sortedBy { sortKey(it) }.mapNotNull { entryJson(it) }
        return JSONObject().put("from_day", range.first.toString()).put("to_day", range.second.toString())
            .put("entries", JSONArray(rows))
    }

    suspend fun lows(data: AssistantData, zone: ZoneId, range: Pair<LocalDate, LocalDate>): JSONObject {
        val (from, to) = range
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 3 h of context before the first day too
        val rs = data.readings(start - 3 * 3600_000L, end)
        val journal = data.journal(from.minusDays(1).toString(), to.toString())
        val episodes = mutableListOf<JSONObject>()
        var i = rs.indexOfFirst { it.timestampMs >= start }.let { if (it < 0) rs.size else it }
        while (i < rs.size) {
            if (mmol(rs[i]) >= LOW_MMOL) { i++; continue }
            var j = i
            while (j + 1 < rs.size && mmol(rs[j + 1]) < LOW_MMOL && rs[j + 1].timestampMs - rs[j].timestampMs <= 20 * 60_000L) j++
            val ep = rs.subList(i, j + 1)
            val nadir = ep.minBy { it.mgdl }
            val t0 = ep.first().timestampMs
            fun before(h: Int): Any = rs.lastOrNull { it.timestampMs <= t0 - h * 3600_000L && it.timestampMs > t0 - h * 3600_000L - 15 * 60_000L }
                ?.let { r1(mmol(it)) } ?: JSONObject.NULL
            val logged = journal.filter { e ->
                val m = entryMinute(e.text) ?: return@filter false
                val at = LocalDate.parse(e.day).atStartOfDay(zone).plusMinutes(m.toLong()).toInstant().toEpochMilli()
                at in (t0 - 3 * 3600_000L)..t0
            }.sortedBy { sortKey(it) }.mapNotNull { entryJson(it) }
            episodes += JSONObject()
                .put("start", local(t0, zone)).put("end", local(ep.last().timestampMs, zone))
                .put("minutes", ((ep.last().timestampMs - t0) / 60_000L + 5).toInt())
                .put("nadir", r1(mmol(nadir))).put("nadir_at", local(nadir.timestampMs, zone))
                .put("glucose_3h_before", before(3)).put("glucose_2h_before", before(2)).put("glucose_1h_before", before(1))
                .put("logged_in_3h_before", JSONArray(logged))
            i = j + 1
        }
        return JSONObject().put("from_day", from.toString()).put("to_day", to.toString())
            .put("count", episodes.size).put("lows", JSONArray(episodes))
    }

    /** Rows from a [PROPOSE] call, validated like the Nano path's [parseBreakdown]. */
    fun proposals(argsJson: String): Breakdown = parseBreakdown(argsJson)
}
