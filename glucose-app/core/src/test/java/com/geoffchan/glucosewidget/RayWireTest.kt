package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class RayWireTest {
    @Test fun runRequestRoundTrips() {
        val r = RunRequest(MODE_CHAT, "turn", "medium", listOf(JSONObject().put("role", "user").put("content", "hi")), "2026-10-12", "America/Toronto", 14, 16_000)
        val back = decodeRunRequest(encodeRunRequest(r))!!
        assertEquals(r.copy(prior = emptyList()), back.copy(prior = emptyList()))
        assertEquals("hi", back.prior.single().getString("content"))
        assertNull(decodeRunRequest("""{"mode":"other","userTurn":"x","today":"2026-10-12"}"""))
    }

    @Test fun runResultRoundTrips() {
        val r = AssistantResult(
            answer = "Ready to log", detail = null,
            entries = listOf(ProposedEntry(true, "short-acting", 4, null, LocalTime.of(21, 12)), ProposedEntry(false, name = "coffee", time = null)),
            rejected = 1, model = "gpt-6-luna", inputTokens = 10, cachedTokens = 5, outputTokens = 3, ms = 1200,
            trace = JSONArray().put(JSONObject().put("round", 1)),
            changes = listOf(ChangeOp.Delete("2026-10-11", "u1", "event: x @ 10:00")), awaitingAnswer = false,
        )
        val (back, reports) = decodeRunResult(encodeRunResult(r, listOf(RayReport("ray-x.html", "<p>x</p>"))))
        assertEquals(r.entries, back.entries)
        assertEquals(r.changes, back.changes)
        assertEquals(1, back.trace.length())
        assertEquals("ray-x.html", reports.single().name)
    }

    @Test fun quickQuestions() {
        assertEquals(QuickQuestion.READING, quickQuestion("what is Francine's blood sugar"))
        assertEquals(QuickQuestion.READING, quickQuestion("what's her number"))
        assertEquals(QuickQuestion.LAST_DOSE, quickQuestion("when was the last time Francine took medicine"))
        assertEquals(QuickQuestion.LAST_MEAL, quickQuestion("when did she last eat"))
        assertNull(quickQuestion("why was her sugar high last night"))
        assertNull(quickQuestion("she had 4 units of short acting"))
        assertNull(quickQuestion("how was her sugar yesterday"))
    }

    @Test fun spokenAnswers() {
        val now = 1_000_000_000L
        assertEquals("Francine is 3 point 5 and falling, as of just now. That's low.", spokenReading(Reading(63, now, "SingleDown"), now))
        assertEquals("9:12 pm", spokenTime(LocalTime.of(21, 12)))
        assertEquals("12:05 am", spokenTime(LocalTime.of(0, 5)))
        val rows = listOf(
            JournalEntity(day = "2026-10-11", text = "dose: short-acting 6u @ 17:30", createdAtMs = 0, updatedAtMs = 0),
            JournalEntity(day = "2026-10-12", text = "dose: long-acting 19u @ 10:30", createdAtMs = 0, updatedAtMs = 0),
            JournalEntity(day = "2026-10-12", text = "event: coffee @ 10:30", createdAtMs = 0, updatedAtMs = 0),
        )
        assertEquals("Francine's last logged dose was 19 units of long-acting at 10:30 am today.", spokenLast(QuickQuestion.LAST_DOSE, rows, "2026-10-12"))
        assertEquals("The last thing Francine logged eating was coffee at 10:30 am today.", spokenLast(QuickQuestion.LAST_MEAL, rows, "2026-10-12"))
        assertEquals("Francine's last logged dose was 6 units of short-acting at 5:30 pm yesterday.", spokenLast(QuickQuestion.LAST_DOSE, rows.take(1), "2026-10-12"))
    }
}
