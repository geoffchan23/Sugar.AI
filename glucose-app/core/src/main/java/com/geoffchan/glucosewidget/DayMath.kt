package com.geoffchan.glucosewidget

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Parse every valid reading in a Share response; malformed items are skipped. */
fun parseReadings(body: String): List<Reading> = runCatching {
    val arr = JSONArray(body)
    (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val one = JSONArray().put(arr.getJSONObject(i)).toString()
            parseLatestReading(one)
        }.getOrNull()
    }
}.getOrDefault(emptyList())

/** [start, end) of a local calendar day in epoch ms. DST-correct: not always 24 h. */
fun dayBoundsMs(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

/** "YYYY-MM-DD" of the local day a timestamp falls in. */
fun dayKey(timestampMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toString()

/** Minutes since local midnight of [day] — the chart's x coordinate. */
fun minuteOfDay(timestampMs: Long, day: LocalDate, zone: ZoneId): Float {
    val (start, _) = dayBoundsMs(day, zone)
    return (timestampMs - start) / 60_000f
}

/** The Monday of the ISO week containing [date]. Week keys use this. */
fun weekStartOf(date: LocalDate): LocalDate =
    date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

/** Legacy one-tap day tags (chips UI removed 2026-09-02); still hidden from lists. */
fun isTagEntry(text: String): Boolean = text.startsWith("#") && !text.contains(" ")

/**
 * Geoff's dosing routine: short-acting is 4u; long-acting is 19u every day
 * (the 25u Friday-to-Sunday variant was dropped 2026-09-12). Prefills the
 * dose dialog. [dayOfWeek] is kept so a day-dependent rule can return.
 */
@Suppress("UNUSED_PARAMETER")
fun defaultUnits(type: String, dayOfWeek: java.time.DayOfWeek): Int =
    if (type == "long-acting") 19 else 4

/** Her morning coffee, auto-logged daily (see [Routines]). */
const val ROUTINE_TIME = "10:30"

/** Sundays the morning routine happens around 2 pm (Geoff, 2026-10-07). */
const val SUNDAY_ROUTINE_TIME = "14:00"

fun morningRoutineTime(dayOfWeek: java.time.DayOfWeek): String =
    if (dayOfWeek == java.time.DayOfWeek.SUNDAY) SUNDAY_ROUTINE_TIME else ROUTINE_TIME

/**
 * End of the morning routine's window: only entries before 15:00 can stand in
 * for it, so an afternoon snack dose doesn't suppress the morning's.
 */
const val ROUTINE_SPLIT_MINUTE = 15 * 60

/** What the routine auto-logs: just the coffee. She logs her own doses. */
fun morningRoutine(dayOfWeek: java.time.DayOfWeek): List<String> =
    listOf(eventNoteText("coffee", morningRoutineTime(dayOfWeek)))

/**
 * The routine also auto-logged 4u short + 19u long until this day; from
 * 2026-10-11 she logs her doses herself (Geoff, 2026-10-10).
 */
val ROUTINE_DOSES_UNTIL: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 10)

/** Every row the routine may have auto-logged on [day], for recognising them later. */
fun routineRows(day: java.time.LocalDate): List<String> {
    val t = morningRoutineTime(day.dayOfWeek)
    val doses = if (day.isAfter(ROUTINE_DOSES_UNTIL)) emptyList() else listOf(
        doseNoteText("short-acting", "${defaultUnits("short-acting", day.dayOfWeek)}u", t),
        doseNoteText("long-acting", "${defaultUnits("long-acting", day.dayOfWeek)}u", t),
    )
    return doses + morningRoutine(day.dayOfWeek)
}

/**
 * Routine entries not yet covered by what's already logged in [fromMinute]
 * until [toMinute]. A dose of the same type counts as covered regardless of
 * its units, so a hand-logged 10:25 dose doesn't get a 10:30 twin. Food is
 * matched by name: coffee is near-certain, so it's added even if toast was
 * logged.
 */
fun routineMissing(
    routine: List<String>,
    todayTexts: List<String>,
    fromMinute: Int = 0,
    toMinute: Int = 24 * 60,
): List<String> {
    fun inWindow(minute: Int) = minute >= fromMinute && minute < toMinute
    val doseTypes = todayTexts.mapNotNull { parseDoseNote(it) }
        .filter { inWindow(it.minuteOfDay) }.map { it.isShort }.toSet()
    val events = todayTexts.mapNotNull { parseEventNote(it) }
        .filter { inWindow(it.minuteOfDay) }.map { it.name.lowercase() }
    return routine.filter { r ->
        parseDoseNote(r)?.let { it.isShort !in doseTypes }
            ?: parseEventNote(r)?.let { it.name.lowercase() !in events }
            ?: true
    }
}

/** Canonical dose-note format; the analysis parser relies on it. */
fun doseNoteText(name: String, amount: String, time: String): String =
    "dose: ${name.trim()} ${amount.trim()} @ $time"

/**
 * Suffix Claude Code appends to entries it inferred from the glucose curve
 * (`dose: short-acting 6u @ 17:30 (guess)`). Drawn hollow on the chart until
 * "Keep" in the notes list strips it; editing one also saves it confirmed.
 */
const val GUESS_SUFFIX = " (guess)"

fun isGuessEntry(text: String): Boolean = text.endsWith(GUESS_SUFFIX)

/** The confirmed form of a guess: same entry, suffix removed. */
fun confirmGuess(text: String): String = text.removeSuffix(GUESS_SUFFIX)

data class DoseNote(val isShort: Boolean, val units: Int, val minuteOfDay: Int, val isGuess: Boolean = false) {
    /** Canonical type name, as the dose dialog's chips and [doseNoteText] use it. */
    val insulinType: String get() = if (isShort) "short-acting" else "long-acting"
    val time: java.time.LocalTime get() = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

data class EventNote(val name: String, val minuteOfDay: Int, val isGuess: Boolean = false) {
    val time: java.time.LocalTime get() = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

/** Canonical food/exercise log format; same shape the chart plots as diamonds. */
fun eventNoteText(name: String, time: String): String = "event: ${name.trim()} @ $time"

private val TIME_12H = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)

/** "12:21 PM" — what the user sees; storage stays 24-hour HH:mm. */
fun time12(t: java.time.LocalTime): String = t.format(TIME_12H)

/** Notes-list rendering: timed entries read "12:40 PM · lunch"; anything else is shown raw. */
fun entryLabel(text: String): String {
    parseDoseNote(text)?.let { return "${time12(it.time)} · ${it.units}u ${it.insulinType}" + if (it.isGuess) " · guess" else "" }
    parseEventNote(text)?.let { return "${time12(it.time)} · ${it.name}" + if (it.isGuess) " · guess" else "" }
    return text
}

/** Minute-of-day for timed entries (doses, logs); null for free text. */
fun entryMinute(text: String): Int? =
    parseDoseNote(text)?.minuteOfDay ?: parseEventNote(text)?.minuteOfDay

/** Notes-list order: by day, then timed entries by time, then untimed by creation. */
fun sortForList(entries: List<JournalEntity>): List<JournalEntity> =
    entries.sortedWith(
        compareBy<JournalEntity> { it.day }
            .thenBy { entryMinute(it.text) == null }
            .thenBy { entryMinute(it.text) ?: 0 }
            .thenBy { it.createdAtMs },
    )

/** A marker for the widget: short label plus the instant it happened. */
data class LastLog(val label: String, val atMs: Long)

private fun instantOf(day: String, minuteOfDay: Int, zone: ZoneId): Long =
    LocalDate.parse(day).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()

fun latestDose(entries: List<JournalEntity>, zone: ZoneId): LastLog? =
    entries.asSequence()
        .filter { it.scope == SCOPE_DAY && !isGuessEntry(it.text) } // the widget shows what's known
        .mapNotNull { e -> parseDoseNote(e.text)?.let { d -> LastLog("${if (d.isShort) "▲" else "△"} ${d.units}u", instantOf(e.day, d.minuteOfDay, zone)) } }
        .maxByOrNull { it.atMs }

fun latestEvent(entries: List<JournalEntity>, zone: ZoneId): LastLog? =
    entries.asSequence()
        .filter { it.scope == SCOPE_DAY && !isGuessEntry(it.text) }
        .mapNotNull { e -> parseEventNote(e.text)?.let { ev -> LastLog("◆ ${ev.name}", instantOf(e.day, ev.minuteOfDay, zone)) } }
        .maxByOrNull { it.atMs }

/** Widget time for a dose/log: clock time only, e.g. "10:30 AM". */
fun whenText(atMs: Long, zone: ZoneId): String =
    time12(Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime())

/** "now", "45m ago", "2h ago", "3d ago"; future instants clamp to "now". */
fun relativeAge(atMs: Long, nowMs: Long): String {
    val m = (nowMs - atMs) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m ago"
        m < 24 * 60 -> "${m / 60}h ago"
        else -> "${m / (24 * 60)}d ago"
    }
}

private val EVENT_RE = Regex("""^event: (.+) @ (\d{1,2}):(\d{2})( \(guess\))?$""")

/**
 * Parse a food/exercise log (`event: coffee @ 10:30`). Logged from the app's
 * "Log food/exercise" dialog (older ones were written by Claude Code from
 * free-text notes); plotted as diamonds on the chart.
 */
fun parseEventNote(text: String): EventNote? {
    val m = EVENT_RE.find(text) ?: return null
    val (name, hh, mm, guess) = m.destructured
    return EventNote(name.trim(), hh.toInt() * 60 + mm.toInt(), isGuess = guess.isNotEmpty())
}

/** Entries the notes list hides: legacy tag chips only. Logs and doses are shown. */
fun isDerivedEntry(text: String): Boolean = isTagEntry(text)

/** Chart marker inputs (minute-of-range keyed) from day-scope journal entries. */
fun markerData(
    entries: List<JournalEntity>,
    firstDay: LocalDate,
): Pair<List<Pair<Float, DoseNote>>, List<Pair<Float, EventNote>>> {
    val doses = mutableListOf<Pair<Float, DoseNote>>()
    val events = mutableListOf<Pair<Float, EventNote>>()
    for (e in entries) {
        if (e.scope != SCOPE_DAY) continue
        val dayOffset = java.time.temporal.ChronoUnit.DAYS.between(firstDay, LocalDate.parse(e.day)).toInt()
        parseDoseNote(e.text)?.let { doses += (dayOffset * 1440f + it.minuteOfDay) to it }
        parseEventNote(e.text)?.let { events += (dayOffset * 1440f + it.minuteOfDay) to it }
    }
    return doses to events
}

private val DOSE_RE = Regex("""^dose: (.+) (\d+)\S* @ (\d{1,2}):(\d{2})( \(guess\))?$""")

/** Parse a dose journal entry; legacy free-name notes count as short-acting. */
fun parseDoseNote(text: String): DoseNote? {
    val m = DOSE_RE.find(text) ?: return null
    val (name, units, hh, mm, guess) = m.destructured
    return DoseNote(
        isShort = !name.contains("long", ignoreCase = true),
        units = units.toInt(),
        minuteOfDay = hh.toInt() * 60 + mm.toInt(),
        isGuess = guess.isNotEmpty(),
    )
}

/** [start, end) in epoch ms of [days] consecutive local days from [firstDay]. */
fun rangeBoundsMs(firstDay: LocalDate, days: Int, zone: ZoneId): Pair<Long, Long> {
    val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = firstDay.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}
