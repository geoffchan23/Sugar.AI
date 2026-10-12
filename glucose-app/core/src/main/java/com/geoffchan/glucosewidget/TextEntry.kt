package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * "Describe in words": a paragraph goes to on-device Gemini Nano, which
 * answers with JSON; everything here is the deterministic half — the prompt,
 * strict validation of what the model said, and matching against what's
 * already logged. Nothing here trusts the model: a row that doesn't validate
 * is dropped (and counted), and the user confirms every row before saving.
 */

/** One row the breakdown proposes. [time] null = not stated; the user must set it. */
data class ProposedEntry(
    val isDose: Boolean,
    val insulinType: String? = null, // "short-acting" | "long-acting", doses only
    val units: Int? = null, // 1..100, doses only
    val name: String? = null, // events only
    val time: LocalTime? = null,
) {
    /** The canonical journal text, or null until a time is set. */
    fun noteText(): String? {
        val t = time ?: return null
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return if (isDose) doseNoteText(insulinType!!, "${units}u", hhmm) else eventNoteText(name!!, hhmm)
    }

    val minuteOfDay: Int? get() = time?.let { it.hour * 60 + it.minute }
}

/** Validated rows plus how many the model produced that didn't pass. */
data class Breakdown(val entries: List<ProposedEntry>, val rejected: Int)

private val HHMM = Regex("""^(\d{1,2}):(\d{2})$""")

/** "17:30" → 17:30; anything else (including "evening", "25:00") → null. */
fun parseHhmm(s: String?): LocalTime? {
    val m = HHMM.find(s?.trim() ?: return null) ?: return null
    val (h, mm) = m.destructured
    val hour = h.toInt(); val minute = mm.toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

/**
 * Parse the model's answer. Tolerates the wrapping small models add (```json
 * fences, a sentence before the JSON); the rows themselves are strict:
 * kind dose|event, type short-acting|long-acting, whole units 1..100, a
 * non-blank event name. A missing or malformed time keeps the row with a null
 * time so the confirm list asks for it, rather than inventing one.
 */
fun parseBreakdown(raw: String): Breakdown {
    val rows = extractRows(raw) ?: return Breakdown(emptyList(), rejected = 1)
    val ok = mutableListOf<ProposedEntry>()
    var rejected = 0
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i)
        val parsed = row?.let { parseEntryRow(it) }
        if (parsed == null) rejected++ else ok += parsed
    }
    return Breakdown(ok, rejected)
}

private fun extractRows(raw: String): JSONArray? {
    val text = raw.replace("```json", "").replace("```", "").trim()
    val obj = text.indexOf('{').takeIf { it >= 0 }?.let { start ->
        runCatching { JSONObject(text.substring(start, text.lastIndexOf('}') + 1)) }.getOrNull()
    }
    obj?.optJSONArray("entries")?.let { return it }
    val arrStart = text.indexOf('[')
    if (arrStart < 0) return null
    return runCatching { JSONArray(text.substring(arrStart, text.lastIndexOf(']') + 1)) }.getOrNull()
}

/** One dose/event row from a model, strictly validated (shared with propose_changes). */
fun parseEntryRow(o: JSONObject): ProposedEntry? {
    val time = parseHhmm(o.optString("time", ""))
    return when (o.optString("kind").trim().lowercase()) {
        "dose" -> {
            val type = o.optString("type").trim().lowercase()
            if (type != "short-acting" && type != "long-acting") return null
            val units = when (val u = o.opt("units")) {
                is Int -> u
                is Long -> u.toInt()
                is Number -> u.toDouble().takeIf { it == Math.floor(it) }?.toInt()
                is String -> u.trim().takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toInt()
                else -> null
            } ?: return null
            if (units !in 1..100) return null
            ProposedEntry(isDose = true, insulinType = type, units = units, time = time)
        }
        "event" -> {
            val name = o.optString("name").replace(Regex("""\s+"""), " ").trim()
            if (name.isEmpty() || name.length > MAX_EVENT_NAME || name.equals("null", ignoreCase = true)) return null
            ProposedEntry(isDose = false, name = name, time = time)
        }
        else -> null
    }
}

/**
 * The logged entry this proposal duplicates or replaces, if any: a dose of the
 * same type and units, or an event of the same name (case-insensitive),
 * within [windowMinutes]. A guess or an untouched routine row ([isReplaceable])
 * matches a dose of any units, so "23 long" after the routine's 19u replaces
 * it rather than adding a second dose. A plain duplicate wins over a
 * replaceable row, so a real duplicate is reported as such.
 */
fun matchExisting(p: ProposedEntry, existing: List<JournalEntity>, windowMinutes: Int = 60): JournalEntity? {
    val minute = p.minuteOfDay ?: return null
    val matches = existing.filter { e ->
        if (p.isDose) {
            parseDoseNote(e.text)?.let { d ->
                d.insulinType == p.insulinType && kotlin.math.abs(d.minuteOfDay - minute) <= windowMinutes &&
                    (d.units == p.units || isReplaceable(e))
            } ?: false
        } else {
            parseEventNote(e.text)?.let { ev ->
                ev.name.equals(p.name, ignoreCase = true) && kotlin.math.abs(ev.minuteOfDay - minute) <= windowMinutes
            } ?: false
        }
    }
    return matches.firstOrNull { !isReplaceable(it) } ?: matches.firstOrNull()
}

/** A morning-routine row exactly as [Routines] auto-logged it on its day ([routineRows]). */
fun isRoutineEntry(e: JournalEntity): Boolean =
    runCatching { java.time.LocalDate.parse(e.day) }.getOrNull()?.let { e.text in routineRows(it) } ?: false

/** Her own account of this row updates it: a guess, or an untouched routine row. */
fun isReplaceable(e: JournalEntity): Boolean = isGuessEntry(e.text) || isRoutineEntry(e)

/**
 * Instructions for Gemini Nano. Small model, so: one schema, explicit rules
 * for her routine, one worked example, JSON only. The default times mirror
 * her 10:30 morning coffee and her usual 17:30 dinner.
 */
/**
 * How a description becomes entries — shared by the on-device prompt
 * ([describePrompt]) and the OpenAI assistant ([ASSISTANT_INSTRUCTIONS]).
 */
val LOGGING_RULES = """
- kind is "dose" for insulin, "event" for food, drink or exercise.
- type is exactly "short-acting" or "long-acting". "19+4" means two doses. If the person says which is which, follow that. Otherwise the 19 or 20 is long-acting and the other is short-acting. A dose without a type is short-acting.
- units is a whole number.
- time is 24-hour "HH:mm". Use a time the person states ("at 7pm" is "19:00"). Otherwise: breakfast or morning coffee "10:30", lunch "13:00", afternoon snack "15:00", dinner "17:30", dessert or evening snack "21:30", bedtime "23:00". A dose taken with a meal gets that meal's time. If you cannot tell the time, use null.
- A stated time belongs only to the item it is attached to. A meal word wins for its own items: in "dinner was a chicken burger with 6, and a cookie around 7", the chicken burger and the 6 units are "17:30" and only the cookie is "19:00".
- name is lowercase, in the person's own words, and KEEPS every amount and size they said: "15 g cheddar cheese", "half a medium pizza hut pizza", "2 slices sourdough", "30 min walk". Never drop a number from a food.
- Foods eaten together at the same time are ONE event with all of them in its name, joined the way the person said them: "sourdough bread with butter and jam and 15 g cheddar cheese" is one event, not four. Only coffee, candy and exercise are always their own events. Keep "coffee" and "chicken burger" exactly as written.
- Do not log food the person says they did not eat. Do not invent anything.
""".trim()

/** Long enough for a whole meal said in one breath. */
const val MAX_EVENT_NAME = 160

fun describePrompt(paragraph: String): String = """
You turn a person's description of their day into diabetes log entries.
The person has type 1 diabetes and takes two insulins:
- long-acting: one dose a day, usually 19 or 20 units, in the morning.
- short-acting: smaller doses (about 1 to 12 units) with meals or to correct highs.

Output ONLY JSON, no other text, exactly this shape:
{"entries":[{"kind":"dose","type":"short-acting","units":4,"time":"10:30"},{"kind":"event","name":"coffee","time":"10:30"}]}

Rules:
$LOGGING_RULES

Example
Text: had a quarter bagel with cream cheese and 20 g of cheddar around 2 and took 3, then half a pizza for dinner with 7 units and walked 20 min after
JSON: {"entries":[{"kind":"event","name":"quarter bagel with cream cheese and 20 g of cheddar","time":"14:00"},{"kind":"dose","type":"short-acting","units":3,"time":"14:00"},{"kind":"event","name":"half a pizza","time":"17:30"},{"kind":"dose","type":"short-acting","units":7,"time":"17:30"},{"kind":"event","name":"20 min walk","time":"18:00"}]}

Text: ${paragraph.trim().replace("\n", " ")}
JSON:
""".trimIndent()
