package com.geoffchan.glucosewidget

import org.json.JSONArray
import kotlin.math.max
import kotlin.math.roundToInt

/** One Share reading. Value is mg/dL as the API returns it. */
data class Reading(val mgdl: Int, val timestampMs: Long, val trend: String)

enum class GlucoseState { IN_RANGE, LOW, HIGH, STALE }

const val STALE_AFTER_MS: Long = 12 * 60_000

/** mg/dL -> mmol/L with one decimal, the value Dexcom displays in Canada. */
fun mmolValue(mgdl: Int): Double = (mgdl / 18.0182 * 10).roundToInt() / 10.0

fun mmolText(mgdl: Int): String = String.format("%.1f", mmolValue(mgdl))

private val LEGACY_TRENDS = listOf(
    "None", "DoubleUp", "SingleUp", "FortyFiveUp", "Flat",
    "FortyFiveDown", "SingleDown", "DoubleDown", "NotComputable", "RateOutOfRange",
)

/**
 * Parse the ReadPublisherLatestGlucoseValues response. Returns null for
 * anything unexpected: empty array, error object, missing fields.
 */
fun parseLatestReading(body: String): Reading? = runCatching {
    val arr = JSONArray(body)
    if (arr.length() == 0) return null
    val o = arr.getJSONObject(0)
    val wt = o.getString("WT") // "Date(1693526400000)"
    val ms = Regex("""Date\((\d+)""").find(wt)!!.groupValues[1].toLong()
    // Trend is a string on current servers, an index 0..9 historically.
    val trend = o.get("Trend").let { t ->
        if (t is Number) LEGACY_TRENDS.getOrElse(t.toInt()) { "None" } else t.toString()
    }
    Reading(mgdl = o.getInt("Value"), timestampMs = ms, trend = trend)
}.getOrNull()

fun trendArrow(trend: String): String = when (trend) {
    "DoubleUp" -> "↑↑"
    "SingleUp" -> "↑"
    "FortyFiveUp" -> "↗"
    "Flat" -> "→"
    "FortyFiveDown" -> "↘"
    "SingleDown" -> "↓"
    "DoubleDown" -> "↓↓"
    else -> "–"
}

/** Stale beats range: an old number must not pretend to be current. */
fun displayState(reading: Reading, nowMs: Long, lowMmol: Double, highMmol: Double): GlucoseState {
    if (nowMs - reading.timestampMs > STALE_AFTER_MS) return GlucoseState.STALE
    val mmol = mmolValue(reading.mgdl)
    return when {
        mmol < lowMmol -> GlucoseState.LOW
        mmol > highMmol -> GlucoseState.HIGH
        else -> GlucoseState.IN_RANGE
    }
}

fun ageText(readingMs: Long, nowMs: Long): String {
    val minutes = max(0L, (nowMs - readingMs) / 60_000)
    return "${minutes}m"
}
