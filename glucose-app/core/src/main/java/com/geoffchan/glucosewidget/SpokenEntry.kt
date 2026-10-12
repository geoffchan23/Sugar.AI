package com.geoffchan.glucosewidget

import java.time.LocalTime

/**
 * Deterministic parser for what she says into her watch ("just took 6 units
 * and having a chicken burger"). Gemini Nano can't run while the phone is in
 * a pocket (AICore blocks background use), so this is the watch path's
 * reliable parser; Nano is only tried first.
 *
 * Watch logs are mostly said in the moment, so anything without a time is
 * stamped [now]. Understands: digits and number words; "4 units short",
 * "19 long", "19 and 4" (19/20 → long, the smaller → short), "took 6";
 * insulin brand/slang words; "at 5:30", "at 5 pm", "at noon", "20 minutes
 * ago", "half an hour ago", "this morning"; exercise ("walked for 20
 * minutes" → "20 min walk"). Everything else becomes food/activity names,
 * with fillers ("I just had a…") stripped.
 */
fun parseSpoken(text: String, now: LocalTime): List<ProposedEntry> {
    val toks = tokenize(text)
    if (toks.isEmpty()) return emptyList()
    val kind = arrayOfNulls<Any>(toks.size) // TimeHit / SpokenDose marker per token, null = plain word

    // 1) times
    val times = findTimes(toks, now)
    for (t in times) for (i in t.from..t.to) kind[i] = t

    // 2) doses
    val doses = findDoses(toks, kind)
    for (d in doses) for (i in d.from..d.to) kind[i] = d
    var haveLong = doses.any { it.type == LONG }
    for (d in doses) if (d.type == null) {
        d.type = if (!haveLong && d.units in 15..30) LONG.also { haveLong = true } else SHORT
    }

    // 3) food / activity chunks from what's left
    val foods = findFoods(toks, kind)
    var mealWord: String? = null
    val events = mutableListOf<Triple<Int, String, Boolean>>() // token index → name, is a food (mergeable)
    for (c in foods) {
        val r = cleanChunk(c.words)
        if (r.meal != null && mealWord == null) mealWord = r.meal
        if (r.name != null) events += Triple(c.at, r.name, !r.exercise && r.name !in STANDALONE_FOODS)
    }
    // "had dinner and took 6": the meal word is the only food there is
    if (events.isEmpty() && mealWord != null) events += Triple(foods.firstOrNull()?.at ?: 0, mealWord!!, false)

    // 4) assemble in spoken order, each with its time
    fun timeFor(at: Int): LocalTime {
        if (times.size == 1) return times[0].time
        times.firstOrNull { it.from > at }?.let { return it.time }
        times.lastOrNull { it.to < at }?.let { return it.time }
        return now.withSecond(0).withNano(0)
    }
    val out = mutableListOf<Pair<Int, ProposedEntry>>()
    for (d in doses) out += d.from to ProposedEntry(isDose = true, insulinType = d.type, units = d.units, time = timeFor(d.from))
    // Foods eaten at the same time are one meal, as she logs them by hand:
    // "sourdough bread with butter and jam and 15 g of cheddar cheese".
    val merged = mutableListOf<Pair<Int, String>>()
    var open: Triple<Int, String, Boolean>? = null
    for (e in events) {
        val o = open
        if (o != null && o.third && e.third && timeFor(o.first) == timeFor(e.first)) {
            open = Triple(o.first, "${o.second} and ${e.second}", true)
        } else {
            o?.let { merged += it.first to it.second }
            open = e
        }
    }
    open?.let { merged += it.first to it.second }
    for ((at, name) in merged) out += at to ProposedEntry(isDose = false, name = name, time = timeFor(at))
    return out.sortedBy { it.first }.map { it.second }
}

// ---------------------------------------------------------------- tokens

private data class Tok(val word: String, val num: Int?)

private val ONES = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
).withIndex().associate { (i, w) -> w to i }
private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

private fun tokenize(text: String): List<Tok> {
    var s = " " + text.lowercase() + " "
    s = s.replace("a.m.", " am ").replace("p.m.", " pm ").replace("o'clock", " ")
        .replace("&", " and ").replace("+", " plus ")
    s = s.replace(Regex("""(\d)(am|pm)\b"""), "$1 $2")
    s = s.replace(Regex("""(\d)(u|units?)\b"""), "$1 $2")
    s = s.replace(",", " , ").replace(Regex("""(?<!\d)\.|\.(?!\d)"""), " ")
    s = s.replace(Regex("""[!?;:"()](?!\d)"""), " ").replace("'", "").replace("-", " ")
    val words = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < words.size) {
        val w = words[i]
        val tens = TENS[w]
        when {
            tens != null -> {
                val next = words.getOrNull(i + 1)?.let { ONES[it] }
                if (next != null && next in 1..9) { out += Tok("$w ${words[i + 1]}", tens + next); i += 2; continue }
                out += Tok(w, tens)
            }
            ONES[w] != null -> out += Tok(w, ONES[w])
            w.all(Char::isDigit) && w.length <= 3 -> out += Tok(w, w.toInt())
            else -> out += Tok(w, null)
        }
        i++
    }
    return out
}

// ---------------------------------------------------------------- times

private class TimeHit(val from: Int, val to: Int, val time: LocalTime)

private val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")
private val MINUTE_WORDS = setOf("minute", "minutes", "min", "mins")
private val HOUR_WORDS = setOf("hour", "hours", "hr", "hrs")

/** An hour said without am/pm: the most recent matching clock time (≤ now + 30 min). */
private fun resolveHour(h: Int, m: Int, ampm: String?, now: LocalTime): LocalTime? {
    if (m !in 0..59) return null
    if (ampm != null) {
        if (h !in 1..12) return null
        val h24 = (h % 12) + if (ampm == "pm") 12 else 0
        return LocalTime.of(h24, m)
    }
    if (h !in 0..23) return null
    if (h == 0 || h > 12) return LocalTime.of(h, m)
    val cands = listOf(LocalTime.of(h % 12, m), LocalTime.of(h % 12 + 12, m))
    val limit = now.plusMinutes(30)
    val past = cands.filter { !it.isAfter(limit) || limit.isBefore(now) /* wrapped past midnight */ }
    return past.maxOrNull() ?: cands.min()
}

private fun findTimes(t: List<Tok>, now: LocalTime): List<TimeHit> {
    val hits = mutableListOf<TimeHit>()
    fun w(i: Int) = t.getOrNull(i)?.word
    fun ago(from: Int, to: Int, minutes: Int) {
        val at = now.minusMinutes(minutes.toLong()).withSecond(0).withNano(0)
        // past midnight would land on yesterday; the phone saves to today, so clamp
        hits += TimeHit(from, to, if (minutes > now.toSecondOfDay() / 60) LocalTime.MIDNIGHT else at)
    }
    var i = 0
    while (i < t.size) {
        val word = t[i].word
        // "... ago"
        if (word == "ago") {
            var j = i - 1
            when {
                // "half an hour ago"
                w(j) in HOUR_WORDS && w(j - 1) in setOf("an", "a") && w(j - 2) == "half" -> ago(j - 2, i, 30)
                // "an hour and a half ago"
                w(j) == "half" && w(j - 1) == "a" && w(j - 2) == "and" && w(j - 3) in HOUR_WORDS -> {
                    val n = t.getOrNull(j - 4)?.num ?: (if (w(j - 4) in setOf("an", "a")) 1 else null)
                    if (n != null) ago(j - 4, i, n * 60 + 30)
                }
                w(j) in HOUR_WORDS -> {
                    val n = t.getOrNull(j - 1)?.num ?: (if (w(j - 1) in setOf("an", "a")) 1 else null)
                    if (n != null) ago(j - 1, i, n * 60)
                }
                w(j) in MINUTE_WORDS -> {
                    val n = t.getOrNull(j - 1)?.num
                    if (n != null) {
                        ago(j - 1, i, n)
                    } else {
                        // "a few minutes ago" ≈ 5, "a couple (of) minutes ago" ≈ 2
                        var start = j
                        while (w(start - 1) in setOf("a", "few", "couple", "of")) start--
                        val vague = (start until j).map { w(it) }
                        if ("few" in vague) ago(start, i, 5) else if ("couple" in vague) ago(start, i, 2)
                    }
                }
            }
            i++; continue
        }
        if (word == "this" && w(i + 1) == "morning") { hits += TimeHit(i, i + 1, LocalTime.of(10, 30)); i += 2; continue }
        if (word == "this" && w(i + 1) == "afternoon") { hits += TimeHit(i, i + 1, LocalTime.of(15, 0)); i += 2; continue }

        // "at|around|about|by [about] <clock>" ; a bare "H:MM" or "H am/pm" counts without "at"
        val lead = word in setOf("at", "around", "about", "by")
        var j = if (lead) i + 1 else i
        if (lead && w(j) in setOf("about", "around")) j++
        val clock = w(j)?.let { CLOCK.find(it) }
        var parsed: Pair<LocalTime, Int>? = null // time, last token index
        when {
            w(j) == "noon" && lead -> parsed = LocalTime.NOON to j
            w(j) == "midnight" && lead -> parsed = LocalTime.MIDNIGHT to j
            clock != null -> {
                val ampm = w(j + 1)?.takeIf { it == "am" || it == "pm" }
                resolveHour(clock.groupValues[1].toInt(), clock.groupValues[2].toInt(), ampm, now)
                    ?.let { parsed = it to (if (ampm != null) j + 1 else j) }
            }
            t.getOrNull(j)?.num != null -> {
                val h = t[j].num!!
                val ampm = w(j + 1)?.takeIf { it == "am" || it == "pm" }
                // "at 5 30" (speech sometimes drops the colon)
                val mm = t.getOrNull(j + 1)?.num?.takeIf { lead && ampm == null && it in 0..59 && t[j + 1].word.length == 2 }
                val ampm2 = if (mm != null) w(j + 2)?.takeIf { it == "am" || it == "pm" } else ampm
                // "about 5 units" is a dose, not a time
                val dosey = w(j + 1) in UNIT_WORDS || typeOf(w(j + 1)) != null
                if ((lead || ampm != null) && !dosey) {
                    resolveHour(h, mm ?: 0, ampm2, now)?.let {
                        parsed = it to (j + (if (mm != null) 1 else 0) + (if (ampm2 != null) 1 else 0))
                    }
                }
            }
        }
        val p = parsed
        if (p != null) { hits += TimeHit(i, p.second, p.first); i = p.second + 1; continue }
        i++
    }
    return hits.sortedBy { it.from }
}

// ---------------------------------------------------------------- doses

private const val SHORT = "short-acting"
private const val LONG = "long-acting"

private class SpokenDose(val from: Int, val to: Int, val units: Int, var type: String?)

private val SHORT_WORDS = setOf("short", "fast", "rapid", "quick", "bolus", "humalog", "novorapid", "novolog", "fiasp", "admelog", "lyumjev", "apidra")
private val LONG_WORDS = setOf("long", "slow", "basal", "lantus", "basaglar", "tresiba", "toujeo", "levemir", "semglee")
private val UNIT_WORDS = setOf("units", "unit", "u")
private val DOSE_VERBS = setOf("took", "take", "taking", "did", "dosed", "dose", "injected", "inject", "gave", "bolused")
private val HEDGES = setOf("about", "around", "maybe", "like", "roughly")
private val FOOD_VERBS = setOf("ate", "had", "drank", "eating", "having", "eat")
private val STANDALONE_NEXT = setOf("and", "plus", ",", "then", "with", "at", "around", "about", "this", "of", "for")

private fun typeOf(word: String?): String? = when (word) {
    in SHORT_WORDS -> SHORT
    in LONG_WORDS -> LONG
    else -> null
}

private fun findDoses(t: List<Tok>, kind: Array<Any?>): List<SpokenDose> {
    val out = mutableListOf<SpokenDose>()
    fun free(i: Int) = i in t.indices && kind[i] == null
    fun w(i: Int) = if (free(i)) t[i].word else null
    for (i in t.indices) {
        val n = t[i].num ?: continue
        if (!free(i)) continue
        // after the number: [units] [of] [type [acting|lasting]] [insulin]
        var j = i + 1
        var hasUnits = false
        if (w(j) in UNIT_WORDS) { hasUnits = true; j++ }
        var typeAfter: String? = null
        val ofAt = j
        if (w(j) == "of") j++
        typeOf(w(j))?.let { typeAfter = it; j++; if (w(j) in setOf("acting", "lasting")) j++ }
        if (typeAfter == null) j = ofAt // "of" without a type belongs to the food ("4 units of… " is rare)
        if (w(j) == "insulin") j++
        val end = j - 1
        // before the number: [type [acting|lasting]] or a dose verb
        var start = i
        var typeBefore: String? = null
        var k = i - 1
        if (w(k) in setOf("acting", "lasting")) k--
        typeOf(w(k))?.let { tb ->
            // not a type word that already belongs to the previous dose ("19 long 4 short")
            if (out.none { k in it.from..it.to }) { typeBefore = tb; start = k }
        }
        // "took about 5": hedges between the verb and the number belong to the dose
        if (w(start - 1) in HEDGES && (w(start - 2) in DOSE_VERBS || hasUnits || typeAfter != null)) start--
        val prev = w(start - 1)
        val verb = prev in DOSE_VERBS
        if (verb) start--
        val afterPrevDose = (prev == "and" || prev == "plus" || prev == ",") && out.any { it.to == start - 2 }
        val next = t.getOrNull(end + 1)?.word
        val standalone = !hasUnits && typeAfter == null && typeBefore == null &&
            (next == null || next in STANDALONE_NEXT || t.getOrNull(end + 1)?.num != null || !free(end + 1)) &&
            prev !in FOOD_VERBS && prev != "half"
        val isDose = hasUnits || typeAfter != null || typeBefore != null || verb || afterPrevDose || standalone
        if (!isDose || n !in 1..100) continue
        // a trailing "and"/"plus" between two doses is part of neither food nor dose: leave it as a separator
        out += SpokenDose(start, end, n, typeAfter ?: typeBefore)
    }
    return out
}

// ---------------------------------------------------------------- food / activity

private class Chunk(val at: Int, val words: List<Tok>)

/** "x and y" pairs that are one food, not two. */
private val AND_COMPOUNDS = setOf(
    "mac" to "cheese", "fish" to "chips", "salt" to "vinegar", "cream" to "onion", "butter" to "jelly",
    "pb" to "j", "cookies" to "cream", "bread" to "butter", "rice" to "beans", "franks" to "beans", "sweet" to "sour",
)
private val SEPARATORS = setOf(",", "then", "plus", "also")

private fun findFoods(t: List<Tok>, kind: Array<Any?>): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    var cur = mutableListOf<Tok>()
    var start = -1
    fun flush() { if (cur.isNotEmpty()) chunks += Chunk(start, cur); cur = mutableListOf(); start = -1 }
    for (i in t.indices) {
        val k = kind[i]
        if (k is SpokenDose) { flush(); continue }
        if (k is TimeHit) continue // a time sits inside a chunk without splitting it
        val word = t[i].word
        val isSep = when (word) {
            in SEPARATORS -> true
            "and" -> (t.getOrNull(i - 1)?.word to t.getOrNull(i + 1)?.word) !in AND_COMPOUNDS
            // "with" splits only next to a dose ("4 units with my coffee"), not "bagel with cream cheese"
            "with" -> kind.getOrNull(i - 1) is SpokenDose || kind.getOrNull(i + 1) is SpokenDose || cur.isEmpty()
            else -> false
        }
        if (isSep) { flush(); continue }
        if (start < 0) start = i
        cur += t[i]
    }
    flush()
    return chunks
}

private val MEALS = setOf("breakfast", "lunch", "dinner", "supper", "snack", "dessert")
private val LEAD_FILLERS = setOf(
    "i", "ive", "im", "id", "she", "shes", "just", "had", "have", "has", "having", "ate", "eat", "eaten", "eating",
    "drank", "drink", "drinking", "did", "took", "take", "a", "an", "some", "my", "the", "also", "and", "so", "ok", "okay",
    "um", "uh", "like", "got", "grabbed", "went", "go", "for", "then", "now", "been", "was", "is",
)
private val TRAIL_FILLERS = setOf("now", "today", "earlier", "too", "also", "please", "just", "right", "as", "well")
private val EXERCISE = mapOf(
    "walk" to "walk", "walked" to "walk", "walking" to "walk", "walks" to "walk",
    "run" to "run", "ran" to "run", "running" to "run", "jog" to "jog", "jogged" to "jog", "jogging" to "jog",
    "bike" to "bike ride", "biked" to "bike ride", "biking" to "bike ride", "cycled" to "bike ride", "cycling" to "bike ride",
    "swim" to "swim", "swam" to "swim", "swimming" to "swim", "yoga" to "yoga", "gym" to "workout", "workout" to "workout",
    "exercise" to "workout", "exercised" to "workout", "hike" to "hike", "hiked" to "hike", "hiking" to "hike",
    "raking" to "raking", "raked" to "raking", "dancing" to "dancing", "danced" to "dancing",
)

private class Cleaned(val name: String?, val meal: String?, val exercise: Boolean = false)

/** Logged on their own, never folded into a meal: standing recipes and low treatment. */
private val STANDALONE_FOODS = setOf("coffee", "candy")

private fun cleanChunk(words: List<Tok>): Cleaned {
    val w = words.map { it.word }.toMutableList()
    val nums = words.map { it.num }.toMutableList()
    var meal: String? = null
    // "for breakfast", "for dinner": remembered, removed
    var i = 0
    while (i < w.size) {
        if (w[i] == "for" && w.getOrNull(i + 1) in MEALS) { meal = w[i + 1]; w.removeAt(i); w.removeAt(i); nums.removeAt(i); nums.removeAt(i); continue }
        i++
    }
    // exercise: "walked for 20 minutes", "30 minute walk", "an hour of yoga"
    val ex = w.firstNotNullOfOrNull { EXERCISE[it] }
    if (ex != null) {
        var minutes: Int? = null
        for (k in w.indices) {
            if (w[k] in MINUTE_WORDS) nums.getOrNull(k - 1)?.let { minutes = it }
            if (w[k] in HOUR_WORDS) {
                val n = nums.getOrNull(k - 1) ?: (if (w.getOrNull(k - 1) in setOf("an", "a")) 1 else null)
                if (n != null) minutes = n * 60 + (if (w.getOrNull(k + 1) == "and" && w.getOrNull(k + 3) == "half") 30 else 0)
                if (w.getOrNull(k - 1) in setOf("an", "a") && w.getOrNull(k - 2) == "half") minutes = 30
            }
        }
        return Cleaned(if (minutes != null) "$minutes min $ex" else ex, meal, exercise = true)
    }
    while (w.isNotEmpty() && w.first() in LEAD_FILLERS) w.removeAt(0)
    while (w.isNotEmpty() && w.last() in TRAIL_FILLERS) w.removeAt(w.size - 1)
    if (w.isEmpty()) return Cleaned(null, meal)
    if (w.size == 1 && w[0] in MEALS) return Cleaned(null, meal ?: w[0])
    val name = w.joinToString(" ").take(MAX_EVENT_NAME)
    return Cleaned(name, meal)
}
