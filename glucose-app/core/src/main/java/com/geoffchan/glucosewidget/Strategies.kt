package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ray's playbook: what people with T1D on injections actually do about a
 * pattern — from guidelines (ADA, Diabetes Canada, the CGM and exercise
 * consensus), dietitians and educators, and the community (Reddit, Juicebox,
 * diaTribe). Built from research on 2026-10-24 into
 * core/src/main/resources/ray/strategies.json; [AssistantTools.STRATEGIES]
 * hands Ray the entries that fit what he saw in her data, so his
 * suggestions are concrete and grounded instead of "contact her team".
 */
data class Strategy(
    val id: String,
    val title: String,
    val patterns: List<String>,
    val foods: List<String>,
    val advice: String,
    val how: String,
    val why: String,
    val doseChange: Boolean,
    val evidence: String,
    val caveat: String,
)

object Strategies {
    /** The patterns get_strategies takes; every entry is tagged with some of these. */
    val PATTERNS = listOf(
        "overnight-lows", "morning-highs", "post-meal-spikes", "post-meal-lows", "late-morning-lows",
        "high-variability", "rebound-highs", "treating-lows", "hypo-unawareness", "exercise", "alcohol",
        "high-fat-meals", "pre-bolus", "corrections-stacking", "sick-days", "stress", "menstrual-cycle",
        "injection-sites", "insulin-handling", "cgm-use", "eating-patterns", "carb-counting", "sleep",
        "motivation-burnout", "partner-support", "general",
    )

    /** Guidelines first, the community last: it breaks ties, it doesn't outrank a pattern match. */
    private val EVIDENCE_RANK = mapOf("guideline" to 4, "consensus" to 4, "study" to 3, "educator" to 2, "community" to 1)

    const val MAX_RESULTS = 8

    val all: List<Strategy> by lazy {
        val text = Strategies::class.java.getResourceAsStream("/ray/strategies.json")!!.bufferedReader().use { it.readText() }
        parse(text)
    }

    fun parse(json: String): List<Strategy> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }.orEmpty()
            Strategy(
                o.getString("id"), o.getString("title"), list("patterns"), list("foods"),
                o.getString("advice"), o.optString("how"), o.optString("why"),
                o.optBoolean("involves_dose_change"), o.optString("evidence"), o.optString("caveat"),
            )
        }
    }

    /**
     * Entries for [patterns] (and [foods] she eats), best first: more patterns
     * matched, then a food match, then stronger evidence. Anything matching
     * neither a pattern nor a food is left out.
     */
    fun find(patterns: List<String>, foods: List<String>, from: List<Strategy> = all, limit: Int = MAX_RESULTS): List<Strategy> {
        val ps = patterns.map { it.trim().lowercase() }.toSet()
        val fs = foods.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun foodHit(s: Strategy) = s.foods.any { f -> fs.any { it.contains(f) || f.contains(it) } }
        return from.map { s -> s to (s.patterns.count { it in ps } * 10 + (if (foodHit(s)) 6 else 0)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Strategy, Int>> { it.second }.thenByDescending { EVIDENCE_RANK[it.first.evidence] ?: 0 })
            .take(limit).map { it.first }
    }

    /** What Ray sees: no sources (he doesn't cite them in chat), dose-related ones flagged. */
    fun toJson(s: Strategy): JSONObject = JSONObject()
        .put("title", s.title).put("advice", s.advice).put("how", s.how).put("why", s.why)
        .put("evidence", s.evidence)
        .apply {
            if (s.doseChange) put("dose_change", "yes: name the lever, leave the amount to her diabetes team")
            if (s.caveat.isNotBlank()) put("caveat", s.caveat)
        }
}
