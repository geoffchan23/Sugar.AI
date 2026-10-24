package com.geoffchan.glucosewidget

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("America/Toronto")
private val DAY: LocalDate = LocalDate.of(2026, 10, 7)

private fun at(h: Int, m: Int, day: LocalDate = DAY) = day.atTime(h, m).atZone(ZONE).toInstant().toEpochMilli()
private fun mgdl(mmol: Double) = Math.round(mmol * 18.0182).toInt()

/** A day of readings every 5 min from [values] (mmol/L) starting at 00:00. */
private fun day(values: (Int) -> Double, day: LocalDate = DAY) = (0 until 288).map { i ->
    ReadingEntity(at(0, 0, day) + i * 300_000L, mgdl(values(i)), "Flat")
}

private class FakeData(val rs: List<ReadingEntity>, val js: List<JournalEntity>) : AssistantData {
    override suspend fun readings(startMs: Long, endMs: Long) = rs.filter { it.timestampMs in startMs until endMs }
    override suspend fun journal(firstDay: String, lastDay: String) = js.filter { it.day in firstDay..lastDay }
    override suspend fun journalById(id: Long) = js.firstOrNull { it.id == id }
}

private fun j(day: LocalDate, text: String) = JournalEntity(day = day.toString(), text = text, createdAtMs = 0, updatedAtMs = 0)

class AssistantToolsTest {
    @Test fun `readings are downsampled to at most 150 points with summary`() = runBlocking {
        val data = FakeData(day({ 5.0 + (it % 10) * 0.1 }), emptyList())
        val out = AssistantTools.readings(data, ZONE, DAY.atStartOfDay(), DAY.atTime(23, 59))
        assertEquals(288, out.getInt("count"))
        assertTrue(out.getJSONArray("points").length() <= 150)
        assertEquals(5.0, out.getDouble("min"), 0.05)
        assertEquals(5.9, out.getDouble("max"), 0.05)
        assertEquals("2026-10-07 00:05", out.getJSONArray("points").getJSONArray(0).getString(0)) // middle of the first bucket
    }

    @Test fun `readings tool parses local times and reports an empty range`() = runBlocking {
        val r = JSONObject(AssistantTools.run(AssistantTools.READINGS, """{"from":"2026-01-01 00:00","to":"2026-01-01 06:00"}""", FakeData(emptyList(), emptyList()), ZONE))
        assertEquals(0, r.getInt("count"))
        assertEquals("no readings in this range", r.getString("note"))
    }

    @Test fun `stats match report py definitions`() {
        // 288 readings: a quarter low (3.0), half in range (7.0), a quarter high (12.0)
        val values = List(72) { 3.0 } + List(144) { 7.0 } + List(72) { 12.0 }
        val m = AssistantTools.metrics(values, days = 1)
        assertEquals(25.0, m.getDouble("below_3_9_pct"), 0.01)
        assertEquals(0.0, m.getDouble("below_3_0_pct"), 0.01) // 3.0 is not below 3.0
        assertEquals(50.0, m.getDouble("in_range_pct"), 0.01)
        assertEquals(25.0, m.getDouble("above_10_pct"), 0.01)
        assertEquals(7.25, m.getDouble("mean"), 0.06)
        assertEquals(100.0, m.getDouble("coverage_pct"), 0.01)
        assertEquals(3.31 + 0.02392 * 7.25 * 18.0182, m.getDouble("gmi_pct"), 0.06)
        assertTrue(m.getDouble("cv_pct") > 0)
    }

    @Test fun `journal flags guesses and drops free text`() = runBlocking {
        val data = FakeData(emptyList(), listOf(
            j(DAY, "event: chicken burger @ 17:30 (guess)"),
            j(DAY, "dose: short-acting 6u @ 17:30"),
            j(DAY, "#sick"),
            j(DAY, "event: coffee @ 10:30"),
        ))
        val out = JSONObject(AssistantTools.run(AssistantTools.JOURNAL, """{"from_day":"2026-10-07","to_day":"2026-10-07"}""", data, ZONE))
        val e = out.getJSONArray("entries")
        assertEquals(3, e.length())
        assertEquals("coffee", e.getJSONObject(0).getString("name")) // time order
        assertTrue(e.getJSONObject(1).getBoolean("guess") || e.getJSONObject(2).getBoolean("guess"))
        val dose = (0 until 3).map { e.getJSONObject(it) }.first { it.getString("kind") == "dose" }
        assertEquals("short-acting", dose.getString("type")); assertEquals(6, dose.getInt("units")); assertFalse(dose.getBoolean("guess"))
    }

    @Test fun `lows carry the 3 hours before them`() = runBlocking {
        // low 3.2 from 19:00 to 19:30, otherwise 8.0
        val rs = day({ i -> if (i in 228..234) 3.2 else 8.0 })
        val data = FakeData(rs, listOf(
            j(DAY, "dose: short-acting 6u @ 17:30"),
            j(DAY, "event: chicken burger @ 17:30"),
            j(DAY, "event: coffee @ 10:30"), // outside the 3 h window
        ))
        val out = JSONObject(AssistantTools.run(AssistantTools.LOWS, """{"from_day":"2026-10-07","to_day":"2026-10-07"}""", data, ZONE))
        assertEquals(1, out.getInt("count"))
        val low = out.getJSONArray("lows").getJSONObject(0)
        assertEquals("2026-10-07 19:00", low.getString("start"))
        assertEquals(3.2, low.getDouble("nadir"), 0.05)
        assertEquals(35, low.getInt("minutes"))
        assertEquals(8.0, low.getDouble("glucose_1h_before"), 0.05)
        assertEquals(2, low.getJSONArray("logged_in_3h_before").length())
    }

    @Test fun `bad arguments come back as an error the model can read`() = runBlocking {
        val r = JSONObject(AssistantTools.run(AssistantTools.STATS, """{"from_day":"2026-10-07","to_day":"2026-10-01"}""", FakeData(emptyList(), emptyList()), ZONE))
        assertTrue(r.has("error"))
    }

    @Test fun `tool schemas are strict - every property required, no extras`() {
        fun check(schema: JSONObject) {
            if (schema.optString("type") == "object") {
                assertFalse(schema.getBoolean("additionalProperties"))
                val props = schema.getJSONObject("properties")
                val required = schema.getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                assertEquals(props.keys().asSequence().toSet(), required)
                props.keys().forEach { check(props.getJSONObject(it)) }
            }
            schema.optJSONObject("items")?.let { check(it) }
        }
        val defs = AssistantTools.definitions
        val names = (0 until defs.length()).map { defs.getJSONObject(it) }.onEach {
            assertTrue(it.getBoolean("strict")); check(it.getJSONObject("parameters"))
        }.map { it.getString("name") }
        assertEquals(listOf("get_readings", "get_stats", "get_journal", "get_lows", "get_strategies", "propose_entries", "propose_changes", "reply"), names)
    }

    @Test fun `proposals go through the same strict validation as the on-device path`() {
        val b = AssistantTools.proposals(
            """{"entries":[{"kind":"event","type":null,"units":null,"name":"sourdough bread with butter and jam and 15 g cheddar cheese","time":"14:33"},
               {"kind":"dose","type":"short-acting","units":3,"name":null,"time":"14:33"},
               {"kind":"dose","type":"fast","units":3,"name":null,"time":"14:33"}]}""",
        )
        assertEquals(listOf("event: sourdough bread with butter and jam and 15 g cheddar cheese @ 14:33", "dose: short-acting 3u @ 14:33"), b.entries.map { it.noteText() })
        assertEquals(1, b.rejected)
    }
}

class AssistantPromptTest {
    @Test fun `instructions forbid dose advice and keep the standing recipes`() {
        val p = ASSISTANT_INSTRUCTIONS
        assertTrue(p.contains("Never give an insulin dose, an amount to change a dose by, a ratio or a correction factor"))
        assertTrue(p.contains("never insulin units"))
        assertTrue(p.contains("endocrinologist"))
        assertTrue(p.contains("treat the low first"))
        assertTrue(p.contains("\"coffee\"") && p.contains("\"chicken burger\""))
        assertTrue(p.contains(LOGGING_RULES))
        assertTrue(p.contains("ONE event")) // the meal rule from the shared logging rules
    }

    @Test fun `instructions are a stable prefix - nothing per-request`() {
        assertFalse(Regex("""20\d\d-\d\d-\d\d""").containsMatchIn(ASSISTANT_INSTRUCTIONS))
        assertFalse(ASSISTANT_INSTRUCTIONS.contains("Now:"))
    }

    @Test fun `user turn carries now, the current reading and the target day`() {
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 8, 14, 33), ZONE)
        val t = assistantUserTurn("took 3", now, LocalDate.of(2026, 10, 8), fromWatch = true, current = "9.3 mmol/L Flat (2 min ago)")
        assertTrue(t.contains("Thursday 2026-10-08 14:33"))
        assertTrue(t.contains("9.3 mmol/L"))
        assertTrue(t.contains("2026-10-08 (today)"))
        assertTrue(t.contains("watch"))
        assertTrue(t.endsWith("Francine: took 3"))
    }

    @Test fun `questions get more thought than logging`() {
        assertEquals("medium", defaultEffort("why did I go low last night?"))
        assertEquals("medium", defaultEffort("How was this week"))
        assertEquals("low", defaultEffort("took 6 units and a chicken burger"))
        assertEquals("low", defaultEffort("coffee"))
    }

    @Test fun `cost estimate uses cached pricing`() {
        assertEquals((9000 * 0.10 + 1000 * 0.01 + 500 * 0.50) / 1e6, aiCostUsd("gpt-6-luna", 10_000, 1_000, 500), 1e-12)
        assertEquals(aiCostUsd("gpt-6-luna", 1000, 0, 100), aiCostUsd("some-new-model", 1000, 0, 100), 1e-12)
    }
}

class AssistantLoopTest {
    private val cfg = AiConfig("sk-test", "gpt-6-luna", null)

    private fun response(vararg items: JSONObject) = JSONObject()
        .put("output", JSONArray(items.toList()))
        .put("usage", JSONObject().put("input_tokens", 1000).put("output_tokens", 100)
            .put("input_tokens_details", JSONObject().put("cached_tokens", 400)))

    private fun call(id: String, name: String, args: String) =
        JSONObject().put("type", "function_call").put("call_id", id).put("name", name).put("arguments", args)

    @Test fun `tools are run, everything is replayed, and reply ends the loop`() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val replies = ArrayDeque(listOf(
            response(JSONObject().put("type", "reasoning").put("encrypted_content", "x"), call("c1", "get_stats", """{"from_day":"2026-10-07","to_day":"2026-10-07"}""")),
            response(
                call("c2", "propose_entries", """{"entries":[{"kind":"dose","type":"short-acting","units":3,"name":null,"time":"14:33"}]}"""),
                call("c3", "reply", """{"answer":"Logged 3 units.","detail":null}"""),
            ),
        ))
        val r = Assistant.run(cfg, "Francine: took 3", "low", FakeData(day({ 7.0 }), emptyList()), ZONE) { key, body ->
            assertEquals("sk-test", key)
            sent += JSONObject(body.toString()) // snapshot: the loop keeps appending to its input
            replies.removeFirst()
        }
        assertEquals("Logged 3 units.", r.answer)
        assertNull(r.detail)
        assertEquals(listOf("dose: short-acting 3u @ 14:33"), r.entries.map { it.noteText() })
        assertEquals(2000, r.inputTokens); assertEquals(800, r.cachedTokens); assertEquals(200, r.outputTokens)

        val first = sent[0]
        assertEquals("gpt-6-luna", first.getString("model"))
        assertFalse(first.getBoolean("store"))
        assertEquals("low", first.getJSONObject("reasoning").getString("effort"))
        assertEquals(ASSISTANT_INSTRUCTIONS, first.getString("instructions"))
        // second request replays the reasoning item and the call, then our output for it
        val second = sent[1].getJSONArray("input")
        val types = (0 until second.length()).map { second.getJSONObject(it).optString("type", "user") }
        assertEquals(listOf("user", "reasoning", "function_call", "function_call_output"), types)
        val stats = JSONObject(second.getJSONObject(3).getString("output"))
        assertEquals(100.0, stats.getDouble("in_range_pct"), 0.01)

        // the interaction log's trace: one entry per round, every tool call with args and result
        assertEquals("low", r.effort)
        assertEquals("Francine: took 3", r.userTurn)
        assertEquals(2, r.trace.length())
        val round1 = r.trace.getJSONObject(0)
        assertEquals(1000, round1.getJSONObject("usage").getInt("input"))
        val statsCall = round1.getJSONArray("calls").getJSONObject(0)
        assertEquals("get_stats", statsCall.getString("name"))
        assertEquals("2026-10-07", statsCall.getJSONObject("args").getString("from_day"))
        assertTrue(statsCall.getString("result").contains("in_range_pct"))
        val names = r.trace.getJSONObject(1).getJSONArray("calls").let { c -> (0 until c.length()).map { c.getJSONObject(it).getString("name") } }
        assertEquals(listOf("propose_entries", "reply"), names)
    }

    @Test fun `a plain text answer without reply is still used`() = runBlocking {
        val msg = JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Mostly in range today.")))
        val r = Assistant.run(cfg, "Francine: how am I doing?", "medium", FakeData(emptyList(), emptyList()), ZONE) { _, _ -> response(msg) }
        assertEquals("Mostly in range today.", r.answer)
        assertTrue(r.entries.isEmpty())
    }

    @Test fun `changes are validated, history is replayed first, and a question waits for her answer`() = runBlocking {
        val coffee = JournalEntity(id = 7, day = "2026-10-06", text = "event: coffee @ 10:30", createdAtMs = 0, updatedAtMs = 0)
        val sent = mutableListOf<JSONObject>()
        val replies = ArrayDeque(listOf(
            response(
                call("c1", "propose_changes", """{"changes":[
                    {"action":"delete","id":7,"day":null,"kind":null,"type":null,"units":null,"name":null,"time":null},
                    {"action":"add","id":null,"day":"2026-10-05","kind":"event","type":null,"units":null,"name":"1 cup rice and half cup chicken stir fry","time":"17:30"},
                    {"action":"delete","id":99,"day":null,"kind":null,"type":null,"units":null,"name":null,"time":null}]}"""),
                call("c2", "reply", """{"answer":"Removed yesterday's coffee and added Monday's dinner.","detail":null,"awaiting_answer":false}"""),
            ),
        ))
        val history = listOf(Turn("past few days I had rice and stir fry", "Which days, and was it dinner?"))
        val r = Assistant.run(
            cfg, "Francine: Monday dinner, and no coffee yesterday", "medium", FakeData(emptyList(), listOf(coffee)), ZONE,
            history = history, today = DAY,
        ) { _, body -> sent += JSONObject(body.toString()); replies.removeFirst() }
        assertEquals(
            listOf("add 2026-10-05 event: 1 cup rice and half cup chicken stir fry @ 17:30", "delete 2026-10-06 event: coffee @ 10:30"),
            r.changes.map { changeLogText(it) },
        )
        assertEquals(1, r.changeRejects.size)
        assertFalse(r.awaitingAnswer)
        val input = sent[0].getJSONArray("input")
        assertEquals(listOf("user", "assistant", "user"), (0 until input.length()).map { input.getJSONObject(it).getString("role") })
        assertTrue(input.getJSONObject(0).getString("content").contains("past few days"))
        // the model hears which change was dropped and why
        val out = JSONObject(sent.size.let { r.trace.getJSONObject(0).getJSONArray("calls").getJSONObject(0).getString("result") })
        assertEquals(2, out.getInt("shown_to_her"))
        assertTrue(out.getJSONArray("rejected").getString(0).contains("99"))

        val asking = Assistant.run(cfg, "Francine: past few days rice", "medium", FakeData(emptyList(), emptyList()), ZONE, today = DAY) { _, _ ->
            response(call("c1", "reply", """{"answer":"Which days?","detail":null,"awaiting_answer":true}"""))
        }
        assertTrue(asking.awaitingAnswer)
    }
}
