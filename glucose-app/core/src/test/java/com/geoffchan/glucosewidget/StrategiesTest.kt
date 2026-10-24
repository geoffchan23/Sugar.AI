package com.geoffchan.glucosewidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategiesTest {
    private fun s(id: String, patterns: List<String>, foods: List<String> = emptyList(), evidence: String = "educator") =
        Strategy(id, id, patterns, foods, "a", "h", "w", false, evidence, "")

    @Test fun morePatternsMatchedComeFirstThenFoodThenEvidence() {
        val pool = listOf(
            s("one", listOf("overnight-lows"), evidence = "community"),
            s("two", listOf("overnight-lows", "alcohol")),
            s("guideline", listOf("overnight-lows"), evidence = "guideline"),
            s("pizza", listOf("high-fat-meals"), foods = listOf("pizza")),
            s("unrelated", listOf("sick-days")),
        )
        assertEquals(listOf("two", "guideline", "one"), Strategies.find(listOf("overnight-lows", "alcohol"), emptyList(), pool).map { it.id })
        // a food alone gets in, below any pattern match
        assertEquals(listOf("guideline", "two", "one", "pizza"), Strategies.find(listOf("overnight-lows"), listOf("Pizza"), pool).map { it.id })
        assertTrue(Strategies.find(listOf("menstrual-cycle"), emptyList(), pool).isEmpty())
    }

    @Test fun playbookShipsAndEveryEntryIsTaggedWithAKnownPattern() {
        val all = Strategies.all
        assertTrue("playbook has ${all.size} entries", all.size >= 100)
        all.forEach { e -> assertTrue("${e.id}: ${e.patterns}", e.patterns.isNotEmpty() && e.patterns.all { it in Strategies.PATTERNS }) }
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test fun noEntryGivesInsulinUnits() {
        val units = Regex("""\b\d+(\.\d+)?\s*(u|units?)\b""", RegexOption.IGNORE_CASE)
        Strategies.all.forEach { e ->
            listOf(e.advice, e.how).forEach { assertTrue("${e.id} gives units: $it", !units.containsMatchIn(it)) }
        }
    }

    @Test fun toolReturnsMatchesWithoutSources() {
        val r = AssistantTools.strategies(JSONObject("""{"patterns":["overnight-lows"],"foods":[]}"""))
        assertTrue(r.getInt("count") in 1..Strategies.MAX_RESULTS)
        assertTrue(!r.toString().contains("http"))
    }
}
