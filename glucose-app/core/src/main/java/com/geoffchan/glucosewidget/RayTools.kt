package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tools Ray has only in the chat, on top of [AssistantTools]:
 * - [QUERY]: one read-only SQL SELECT over the app's own database;
 * - [ANALYZE]: JavaScript he writes, run in Android's isolated JS sandbox
 *   over her readings and log (nothing leaves the phone but the result);
 * - [REPORT]: save an HTML report to the Reports screen.
 * This file is the pure half (definitions, checks, the data handed to the
 * script); [RayEngine] runs them.
 */
object RayTools {
    const val QUERY = "query_data"
    const val ANALYZE = "run_analysis"
    const val REPORT = "save_report"

    const val MAX_QUERY_ROWS = 200
    const val MAX_ANALYSIS_DAYS = 180
    const val MAX_RESULT_CHARS = 20_000
    const val MAX_REPORT_BYTES = 400_000

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun fn(name: String, desc: String, vararg props: Pair<String, JSONObject>) = JSONObject()
        .put("type", "function").put("name", name).put("description", desc).put("strict", true)
        .put(
            "parameters",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(props.map { it.first }))
                .put("additionalProperties", false),
        )

    /** Stable text and order: part of the chat's cached prefix. */
    val definitions: JSONArray = JSONArray(listOf(
        fn(
            QUERY,
            "Run one read-only SQLite SELECT (or WITH … SELECT) on the app's database and get up to $MAX_QUERY_ROWS rows back. " +
                "Tables: readings(timestampMs INTEGER epoch ms UTC, mgdl INTEGER, trend TEXT) — one every 5 minutes, mmol/L = mgdl / 18.0182, " +
                "local time = datetime(timestampMs/1000, 'unixepoch', 'localtime'); " +
                "journal(id INTEGER, day TEXT 'YYYY-MM-DD' local, text TEXT, scope TEXT 'day' or 'week', createdAtMs, updatedAtMs, uid) — " +
                "text is 'dose: short-acting 4u @ 10:30', 'dose: long-acting 19u @ 10:30' or 'event: <food or activity> @ HH:mm', " +
                "ending ' (guess)' when inferred, not confirmed; use scope = 'day'. " +
                "chat_messages(thread, createdAtMs, author 'francine'|'geoff'|'ray', text) — this chat. " +
                "Aggregate in SQL (COUNT, AVG, MIN, MAX, GROUP BY, strftime) rather than pulling raw rows.",
            "sql" to str("A single SELECT statement"),
        ),
        fn(
            ANALYZE,
            "Run JavaScript you write over her data for analysis the other tools can't do (patterns, comparisons, custom metrics). " +
                "It runs in an isolated sandbox on the phone with no network. Data for from_day..to_day (at most $MAX_ANALYSIS_DAYS days) is preloaded: " +
                "days (array of 'YYYY-MM-DD'); readings = {t: epoch ms[], v: mmol/L[], d: index into days[], m: local minute of day 0-1439[]} (parallel arrays, time order); " +
                "journal = [{id, day, time 'HH:mm', minute, kind 'dose'|'event', type, units, name, guess}] in time order; " +
                "LOW = 3.9, HIGH = 10.0; hhmm(minute) formats a minute of day; log(...) adds a line to the output. " +
                "Your code is a function body: return a JSON-serialisable value (keep it small: summaries, not raw arrays). Plain ES2020, no imports.",
            "code" to str("JavaScript function body that returns the result"),
            "from_day" to str("First local day, YYYY-MM-DD"),
            "to_day" to str("Last local day, YYYY-MM-DD (inclusive)"),
        ),
        fn(
            REPORT,
            "Save a report to the Reports screen in the app (for something they want to keep, print or show her endocrinologist). " +
                "Write complete, self-contained HTML: inline CSS, inline SVG for any charts, no scripts, no external files, readable on a phone in dark mode. " +
                "Every number must come from tool results in this conversation. Returns the saved name; mention in your reply that it's in Reports.",
            "title" to str("Short title, e.g. 'Lows in September'"),
            "html" to str("The full HTML document"),
        ),
    ))

    // ------------------------------------------------------------ query

    private val FORBIDDEN = Regex(
        """\b(insert|update|delete|drop|create|alter|attach|detach|pragma|vacuum|reindex|begin|commit|rollback|savepoint)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why [sql] can't run, or null if it may. The connection is opened
     * read-only as well; this just gives the model a clear message.
     */
    fun checkQuery(sql: String): String? {
        val s = sql.trim().trimEnd(';').trim()
        if (s.isEmpty()) return "empty query"
        if (s.contains(';')) return "only one statement"
        val first = s.split(Regex("""\s+"""), limit = 2)[0].lowercase()
        if (first != "select" && first != "with") return "only SELECT (or WITH … SELECT) is allowed"
        // keywords inside string literals are fine: strip those before looking
        // (replace() stays allowed for parsing text; the connection itself is read-only)
        val bare = s.replace(Regex("""'(?:[^']|'')*'"""), "''")
        FORBIDDEN.find(bare)?.let { return "read-only: '${it.value}' isn't allowed" }
        return null
    }

    /** Rows as the model sees them: column names once, then value arrays; capped. */
    fun queryResult(columns: List<String>, rows: List<List<Any?>>, truncated: Boolean): String {
        val out = JSONObject()
            .put("columns", JSONArray(columns))
            .put("rows", JSONArray(rows.map { r -> JSONArray(r.map { it ?: JSONObject.NULL }) }))
            .put("row_count", rows.size)
        if (truncated) out.put("note", "stopped at $MAX_QUERY_ROWS rows; aggregate or narrow the query")
        return cut(out.toString(), MAX_RESULT_CHARS)
    }

    // ------------------------------------------------------------ analysis

    data class AnalysisRange(val from: LocalDate, val to: LocalDate)

    fun analysisRange(fromDay: String, toDay: String): AnalysisRange {
        val from = LocalDate.parse(fromDay.trim())
        val to = LocalDate.parse(toDay.trim())
        require(!to.isBefore(from)) { "to_day is before from_day" }
        require(to.toEpochDay() - from.toEpochDay() < MAX_ANALYSIS_DAYS) { "at most $MAX_ANALYSIS_DAYS days per run" }
        return AnalysisRange(from, to)
    }

    /**
     * The script: preloaded data, helpers, then [code] as a function body.
     * Always evaluates to a JSON string {result, log} or {error, log}.
     */
    fun analysisScript(
        code: String,
        range: AnalysisRange,
        readings: List<ReadingEntity>,
        journal: List<JournalEntity>,
        zone: ZoneId,
    ): String {
        val days = generateSequence(range.from) { it.plusDays(1) }.takeWhile { !it.isAfter(range.to) }.toList()
        val dayIndex = days.withIndex().associate { (i, d) -> d to i }
        val t = StringBuilder(); val v = StringBuilder(); val d = StringBuilder(); val m = StringBuilder()
        for (r in readings) {
            val local = Instant.ofEpochMilli(r.timestampMs).atZone(zone)
            val di = dayIndex[local.toLocalDate()] ?: continue
            if (t.isNotEmpty()) { t.append(','); v.append(','); d.append(','); m.append(',') }
            t.append(r.timestampMs)
            v.append(Math.round(r.mgdl / 18.0182 * 10) / 10.0)
            d.append(di)
            m.append(local.hour * 60 + local.minute)
        }
        val entries = journal.filter { it.scope == SCOPE_DAY }
            .sortedBy { it.day + "%04d".format(entryMinute(it.text) ?: 0) }
            .mapNotNull { e -> AssistantTools.entryJson(e)?.put("minute", entryMinute(e.text)) }
        return buildString {
            append("const days = ").append(JSONArray(days.map { it.toString() })).append(";\n")
            append("const readings = {t:[").append(t).append("],v:[").append(v).append("],d:[").append(d).append("],m:[").append(m).append("]};\n")
            append("const journal = ").append(JSONArray(entries)).append(";\n")
            append(
                """
                const LOW = 3.9, HIGH = 10.0;
                const __out = [];
                function log(...a) { __out.push(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')); }
                function hhmm(minute) { const h = Math.floor(minute / 60), mm = minute % 60; return String(h).padStart(2, '0') + ':' + String(mm).padStart(2, '0'); }
                (function () {
                  try {
                    const __r = (function () {
                """.trimIndent(),
            )
            append('\n').append(code).append('\n')
            append(
                """
                    })();
                    return JSON.stringify({ result: __r === undefined ? null : __r, log: __out });
                  } catch (e) {
                    return JSON.stringify({ error: String((e && e.stack) || e), log: __out });
                  }
                })();
                """.trimIndent(),
            )
        }
    }

    // ------------------------------------------------------------ reports

    fun reportSlug(title: String): String =
        title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(50).trim('-').ifEmpty { "report" }

    /** "ray-2026-10-10-1432-lows-in-september.html": sorts by time among the weekly reports. */
    fun reportFileName(title: String, now: java.time.LocalDateTime): String =
        "ray-%s-%02d%02d-%s.html".format(now.toLocalDate(), now.hour, now.minute, reportSlug(title))

    private val RAY_NAME = Regex("""^ray-(\d{4}-\d{2}-\d{2})-\d{4}-(.+)\.html$""")

    /** "Lows in september · Oct 10", or null if [fileName] isn't one of Ray's. */
    fun reportTitleOf(fileName: String): String? {
        val m = RAY_NAME.find(fileName) ?: return null
        val (day, slug) = m.destructured
        val words = slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val date = runCatching { LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.CANADA)) }.getOrDefault(day)
        return "$words · $date"
    }

    fun isRayReport(fileName: String) = RAY_NAME.matches(fileName)

    /** Ray's HTML with a dark default style if it brought none; size-checked. */
    fun reportHtml(title: String, html: String): String {
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "report is over ${MAX_REPORT_BYTES / 1000} KB" }
        if (html.contains("<html", ignoreCase = true)) return html
        val esc = title.replace("&", "&amp;").replace("<", "&lt;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>$esc</title><style>body{background:#121518;color:#e6e6e6;font-family:sans-serif;margin:16px;line-height:1.45}table{border-collapse:collapse}td,th{padding:4px 8px;border-bottom:1px solid #333}</style></head>
<body>$html</body></html>"""
    }
}
