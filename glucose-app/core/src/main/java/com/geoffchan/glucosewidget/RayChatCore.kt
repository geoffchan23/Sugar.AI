package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The chat screen: Ray talks with Francine and Geoff, with his full toolset. */
val CHAT_INSTRUCTIONS = RAY_CORE + "\n\n" + """
The chat (the Sugar.AI app's chat screen):
- You are chatting with Francine and with Geoff, her husband, who built this app and helps look after her diabetes. Each message starts with who wrote it ("Francine:" or "Geoff:"); both see the whole conversation on their phones. Talk to whoever wrote the latest message; to Geoff, talk about Francine by name. Either of them can log or change her entries through you: propose them exactly as above, and whoever is on the phone confirms.
- You have more tools here. query_data runs a read-only SQL query on the app's database (her readings, journal and this chat). run_analysis runs JavaScript you write over her readings and journal, on her phone. save_report saves an HTML report to the app's Reports screen. Use get_stats, get_lows, get_journal and get_readings for the standard things; reach for query_data and run_analysis when a question needs counting, filtering, comparing periods or a custom calculation. Check your numbers make sense before you use them; if a query or script fails, fix it and run it again.
- Say which days you looked at ("over the last two weeks"); if they ask how you got a number, say exactly.
- Make a report only when they ask for one or for something to keep or show her endocrinologist.

Replying in the chat:
- Finish by calling reply exactly once. Put your whole message in answer and set detail to null. Write like her diabetes educator talking with them: plain words, two or three short paragraphs; a simple "- " list only for things to try; no tables or headings. Keep it under about 1,000 characters unless they ask for more.
- If someone only logged something and there's nothing worth adding, answer with a short confirmation of what you proposed.
""".trim()

private val CHAT_CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)

/** The new message as the model sees it: the context that changes, then who said what. */
fun chatUserTurn(author: String, text: String, now: ZonedDateTime, current: String?): String = buildString {
    appendLine("Now: ${CHAT_CLOCK.format(now)} (America/Toronto).")
    appendLine("Francine's current glucose: ${current ?: "unknown"}.")
    appendLine("New entries go on: ${now.toLocalDate()} (today).")
    appendLine("${authorName(author)} is writing in the chat on ${if (author == AUTHOR_FRANCINE) "her" else "his"} phone.")
    appendLine()
    append(authorName(author)).append(": ").append(text.trim())
}

/** Ray's proposed rows as journal texts for [day]; an entry with no time happened [now]. */
fun chatCardEntries(entries: List<ProposedEntry>, now: LocalTime): List<String> =
    entries.mapNotNull { (if (it.time == null) it.copy(time = now.withSecond(0).withNano(0)) else it).noteText() }.distinct()

/** What Ray did, for the message's "how I got this" footer: tool names and their main argument. */
fun toolsUsed(trace: JSONArray): JSONArray {
    val out = JSONArray()
    for (r in 0 until trace.length()) {
        val calls = trace.getJSONObject(r).optJSONArray("calls") ?: continue
        for (c in 0 until calls.length()) {
            val call = calls.getJSONObject(c)
            val name = call.optString("name")
            if (name == AssistantTools.REPLY) continue
            val args = call.opt("args")
            val o = JSONObject().put("name", name)
            when (args) {
                is JSONObject -> {
                    args.optString("sql").takeIf { it.isNotBlank() }?.let { o.put("sql", it) }
                    args.optString("code").takeIf { it.isNotBlank() }?.let { o.put("code", it) }
                    args.optString("title").takeIf { it.isNotBlank() }?.let { o.put("title", it) }
                    listOf("from_day", "to_day", "from", "to").forEach { k -> args.optString(k).takeIf { it.isNotBlank() }?.let { o.put(k, it) } }
                }
            }
            val result = call.optString("result")
            if (result.contains("\"error\"")) o.put("error", runCatching { JSONObject(result).optString("error") }.getOrDefault(result.take(200)))
            out.put(o)
        }
    }
    return out
}
