package com.geoffchan.glucosewidget

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private fun msg(author: String, text: String, at: Long, thread: String = "t1", card: ChatCard? = null, meta: String? = null) =
    ChatMessageEntity(uid = "u$at", thread = thread, createdAtMs = at, updatedAtMs = at, author = author, text = text, card = card?.let { encodeCard(it) }, meta = meta)

class ChatCoreTest {
    private val card = ChatCard(
        "2026-10-10",
        listOf("dose: short-acting 4u @ 10:33", "event: coffee @ 10:30"),
        listOf(ChangeOp.Delete("2026-10-09", "j1", "event: coffee @ 10:30")),
    )

    @Test fun `card round trip and summary for the model`() {
        assertEquals(card, decodeCard(encodeCard(card)))
        assertNull(decodeCard("not json"))
        assertTrue(cardSummary(card).contains("Not saved yet"))
        val saved = card.copy(state = CARD_SAVED, by = AUTHOR_GEOFF, result = "Saved 2")
        assertEquals(saved, decodeCard(encodeCard(saved)))
        assertTrue(cardSummary(saved).endsWith("Geoff saved it (Saved 2).]"))
    }

    @Test fun `thread replays people as user turns and Ray with his card outcome`() {
        val prior = chatPrior(listOf(
            msg(AUTHOR_RAY, "orphan reply", 1),
            msg(AUTHOR_FRANCINE, "took 4 short", 2),
            msg(AUTHOR_RAY, "Here's what I'd log:", 3, card = card.copy(state = CARD_DISMISSED, by = AUTHOR_FRANCINE)),
            msg(AUTHOR_GEOFF, "how was her week?", 4),
        ))
        assertEquals(listOf("user", "assistant", "user"), prior.map { it.getString("role") })
        assertEquals("Francine: took 4 short", prior[0].getString("content"))
        assertTrue(prior[1].getString("content").contains("Francine dismissed it"))
        assertEquals("Geoff: how was her week?", prior[2].getString("content"))
    }

    @Test fun `thread replay keeps the most recent messages within the cap`() {
        val long = (1..10).map { msg(if (it % 2 == 1) AUTHOR_GEOFF else AUTHOR_RAY, "x".repeat(1000), it.toLong()) }
        val prior = chatPrior(long, maxChars = 3500)
        assertEquals("user", prior.first().getString("role"))
        assertTrue(prior.size in 2..3)
        assertTrue(prior.last().getString("content").startsWith("x")) // Ray's last reply, kept
    }

    @Test fun `threads are listed newest first with a title from the first person`() {
        val ts = chatThreads(listOf(
            msg(AUTHOR_GEOFF, "How was   last week?", 1, "a"),
            msg(AUTHOR_RAY, "Pretty good.", 2, "a"),
            msg(AUTHOR_FRANCINE, "took 3", 5, "b"),
        ))
        assertEquals(listOf("b", "a"), ts.map { it.thread })
        assertEquals("How was last week?", ts[1].title)
        assertEquals("Ray: Pretty good.", ts[1].lastLine)
        assertEquals(2, ts[1].count)
    }

    @Test fun `a long message is trimmed to fit the relay but keeps its card`() {
        val meta = JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "run_analysis").put("code", "x".repeat(3000)))).toString()
        val m = msg(AUTHOR_RAY, "Long answer. ".repeat(400), 9, card = card, meta = meta)
        val s = trimChatForSync(m)
        assertTrue(encodeSyncChat(s).toString().toByteArray().size <= CHAT_SYNC_BUDGET)
        assertEquals(m.card, s.card)
        assertTrue(s.text.endsWith("(shortened; the full message is on the phone that wrote it)"))
        // a short one goes as-is
        val short = msg(AUTHOR_GEOFF, "hi", 10)
        assertEquals(short.toSyncChat(), trimChatForSync(short))
        // and it all fits in one encrypted relay message
        val payload = chunkPayloads("dev", emptyList(), emptyList(), chat = listOf(s))
        assertEquals(1, payload.size)
        assertTrue(encryptedSize(encodePayload(payload[0]).toByteArray().size) <= MAX_MESSAGE_BYTES)
    }

    @Test fun `merge takes newer cards but keeps this phone's full text`() {
        val local = msg(AUTHOR_RAY, "Long answer. ".repeat(400), 9, card = card)
        val trimmed = trimChatForSync(local)
        val savedThere = trimmed.copy(updatedAtMs = 20, card = encodeCard(card.copy(state = CARD_SAVED, by = AUTHOR_FRANCINE, result = "Saved 3")))
        val merged = mergeChat(savedThere, local)!!
        assertEquals(local.text, merged.text)
        assertEquals(CARD_SAVED, decodeCard(merged.card)!!.state)
        assertEquals(20, merged.updatedAtMs)
        assertNull(mergeChat(trimmed, local)) // same age: keep ours
        assertEquals(savedThere.toEntity(), mergeChat(savedThere, null))
    }

    @Test fun `chat rides in sync payloads and older payloads still decode`() {
        val c = msg(AUTHOR_GEOFF, "how's she doing?", 3).toSyncChat()
        val p = decodePayload(encodePayload(SyncPayload("dev", chat = listOf(c))))!!
        assertEquals(listOf(c), p.chat)
        assertTrue(decodePayload("""{"v":1,"device":"d","rows":[],"tombs":[]}""")!!.chat.isEmpty())
        val many = (1..30).map { msg(AUTHOR_GEOFF, "message number $it ".repeat(20), it.toLong()).toSyncChat() }
        val chunks = chunkPayloads("dev", emptyList(), emptyList(), chat = many)
        assertTrue(chunks.size > 1)
        assertEquals(many, chunks.flatMap { it.chat })
        chunks.forEach { assertTrue(encryptedSize(encodePayload(it).toByteArray().size) <= MAX_MESSAGE_BYTES) }
    }

    @Test fun `card entries without a time happened now`() {
        val es = listOf(
            ProposedEntry(isDose = true, insulinType = "short-acting", units = 3, time = null),
            ProposedEntry(isDose = false, name = "pizza", time = LocalTime.of(17, 30)),
        )
        assertEquals(listOf("dose: short-acting 3u @ 14:05", "event: pizza @ 17:30"), chatCardEntries(es, LocalTime.of(14, 5, 33)))
    }

    @Test fun `chat user turn says who is writing`() {
        val t = chatUserTurn(AUTHOR_GEOFF, " how was her week? ", java.time.ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, ZoneId.of("America/Toronto")), "6.1 mmol/L Flat (2 min ago)")
        assertTrue(t.contains("Geoff is writing in the chat on his phone."))
        assertTrue(t.endsWith("Geoff: how was her week?"))
        assertFalse(CHAT_INSTRUCTIONS.contains("Now:")) // per-request context stays out of the cached prefix
        assertTrue(CHAT_INSTRUCTIONS.startsWith(RAY_CORE))
        assertTrue(ASSISTANT_INSTRUCTIONS.startsWith(RAY_CORE))
    }
}

class RayToolsTest {
    @Test fun `only single read-only selects pass`() {
        assertNull(RayTools.checkQuery("SELECT COUNT(*) FROM readings;"))
        assertNull(RayTools.checkQuery("with x as (select mgdl from readings) select avg(mgdl) from x"))
        assertNull(RayTools.checkQuery("SELECT replace(text, 'event: ', '') FROM journal WHERE updatedAtMs > 0"))
        assertNull(RayTools.checkQuery("SELECT * FROM chat_messages WHERE text LIKE '%delete%'")) // inside a string: fine
        assertEquals("only one statement", RayTools.checkQuery("select 1; drop table journal"))
        assertNotNull(RayTools.checkQuery("DELETE FROM journal"))
        assertNotNull(RayTools.checkQuery("WITH x AS (SELECT 1) DELETE FROM journal"))
        assertNotNull(RayTools.checkQuery("PRAGMA table_info(journal)"))
        assertNotNull(RayTools.checkQuery("select * from journal; attach database 'x' as y"))
        assertNotNull(RayTools.checkQuery("   "))
    }

    @Test fun `query results carry columns, rows and a truncation note`() {
        val r = JSONObject(RayTools.queryResult(listOf("day", "n"), listOf(listOf("2026-10-09", 12L), listOf(null, 1L)), truncated = true))
        assertEquals(JSONArray(listOf("day", "n")).toString(), r.getJSONArray("columns").toString())
        assertTrue(r.getJSONArray("rows").getJSONArray(1).isNull(0))
        assertTrue(r.has("note"))
    }

    @Test fun `analysis script preloads local days, readings and journal`() {
        val zone = ZoneId.of("America/Toronto")
        val range = RayTools.analysisRange("2026-10-08", "2026-10-09")
        val t0 = LocalDate.of(2026, 10, 9).atTime(10, 35).atZone(zone).toInstant().toEpochMilli()
        val script = RayTools.analysisScript(
            "return readings.v.length;", range,
            listOf(ReadingEntity(t0, 108, "Flat"), ReadingEntity(t0 + 86_400_000L * 5, 100, "Flat")), // the second is outside the range
            listOf(JournalEntity(id = 5, day = "2026-10-09", text = "dose: short-acting 4u @ 10:30", createdAtMs = 0, updatedAtMs = 0)),
            zone,
        )
        assertTrue(script.startsWith("const days = [\"2026-10-08\",\"2026-10-09\"];"))
        assertTrue(script.contains("const readings = {t:[$t0],v:[6.0],d:[1],m:[635]};"))
        assertTrue(script.contains("\"minute\":630"))
        assertTrue(script.contains("\nreturn readings.v.length;\n"))
        assertTrue(script.trimEnd().endsWith("})();"))
    }

    @Test fun `analysis range is bounded`() {
        assertEquals(LocalDate.of(2026, 10, 1), RayTools.analysisRange("2026-10-01", "2026-10-09").from)
        assertTrue(runCatching { RayTools.analysisRange("2026-01-01", "2026-10-09") }.isFailure)
        assertTrue(runCatching { RayTools.analysisRange("2026-10-09", "2026-10-01") }.isFailure)
    }

    @Test fun `reports get a sortable name, a readable title and a default page`() {
        val name = RayTools.reportFileName("Lows in September!", LocalDateTime.of(2026, 10, 10, 14, 32))
        assertEquals("ray-2026-10-10-1432-lows-in-september.html", name)
        assertTrue(RayTools.isRayReport(name))
        assertTrue(RayTools.reportTitleOf(name)!!.startsWith("Lows in september · Oct")) // "Oct" or "Oct." by locale data
        assertEquals(RayTools.reportTitleOf(name), reportTitle(name))
        assertFalse(RayTools.isRayReport("report-2026-09-01_2026-09-06.html"))
        assertTrue(RayTools.reportHtml("A <b>", "<h1>Hi</h1>").contains("<title>A &lt;b></title>"))
        assertEquals("<html>x</html>", RayTools.reportHtml("t", "<html>x</html>"))
        assertTrue(runCatching { RayTools.reportHtml("t", "x".repeat(RayTools.MAX_REPORT_BYTES + 1)) }.isFailure)
    }

    @Test fun `tools used lists what Ray ran, without the reply`() {
        val trace = JSONArray().put(
            JSONObject().put("calls", JSONArray()
                .put(JSONObject().put("name", RayTools.QUERY).put("args", JSONObject().put("sql", "select 1")).put("result", "{\"error\":\"no such table\"}"))
                .put(JSONObject().put("name", RayTools.ANALYZE).put("args", JSONObject().put("code", "return 1").put("from_day", "2026-10-01").put("to_day", "2026-10-09")).put("result", "{\"result\":1}"))
                .put(JSONObject().put("name", AssistantTools.REPLY).put("args", JSONObject()).put("result", "{}"))),
        )
        val used = toolsUsed(trace)
        assertEquals(2, used.length())
        assertEquals("no such table", used.getJSONObject(0).getString("error"))
        assertEquals("return 1", used.getJSONObject(1).getString("code"))
        assertEquals("2026-10-01", used.getJSONObject(1).getString("from_day"))
    }
}

class ChatLoopTest {
    @Test fun `chat runs with its own prompt, tools, prior thread and extra handler`() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        fun call(id: String, name: String, args: String) =
            JSONObject().put("type", "function_call").put("call_id", id).put("name", name).put("arguments", args)
        val replies = ArrayDeque(listOf(
            JSONObject().put("output", JSONArray().put(call("c1", RayTools.QUERY, """{"sql":"select count(*) from readings"}"""))),
            JSONObject().put("output", JSONArray().put(call("c2", AssistantTools.REPLY, """{"answer":"She had 288 readings.","detail":null,"awaiting_answer":false}"""))),
        ))
        val handled = mutableListOf<String>()
        val prior = chatPrior(listOf(msg(AUTHOR_FRANCINE, "hi Ray", 1), msg(AUTHOR_RAY, "Hi!", 2)))
        val noData = object : AssistantData {
            override suspend fun readings(startMs: Long, endMs: Long) = emptyList<ReadingEntity>()
            override suspend fun journal(firstDay: String, lastDay: String) = emptyList<JournalEntity>()
            override suspend fun journalById(id: Long): JournalEntity? = null
        }
        val r = Assistant.run(
            AiConfig("k", "gpt-6-luna", null), "Geoff: how many readings?", "medium", noData, ZoneId.of("America/Toronto"),
            transport = { _, body -> sent += JSONObject(body.toString()); replies.removeFirst() },
            instructions = CHAT_INSTRUCTIONS,
            tools = RayTools.definitions,
            prior = prior,
            maxRounds = 14,
            extraTool = { name, _ -> if (name == RayTools.QUERY) { handled += name; """{"columns":["n"],"rows":[[288]]}""" } else null },
        )
        assertEquals("She had 288 readings.", r.answer)
        assertEquals(listOf(RayTools.QUERY), handled)
        assertEquals(CHAT_INSTRUCTIONS, sent[0].getString("instructions"))
        assertEquals(3, sent[0].getJSONArray("tools").length())
        val input = sent[0].getJSONArray("input")
        assertEquals(listOf("Francine: hi Ray", "Hi!", "Geoff: how many readings?"), (0 until input.length()).map { input.getJSONObject(it).getString("content") })
        val out = sent[1].getJSONArray("input").let { it.getJSONObject(it.length() - 1) }
        assertEquals("function_call_output", out.getString("type"))
        assertTrue(out.getString("output").contains("288"))
    }
}

/** v4 → v5 against Room's own schema export and a real SQLite. */
class ChatMigrationTest {
    @Test fun `migration creates exactly what Room expects and keeps the rest`() {
        val entities = JSONObject(File("schemas/com.geoffchan.glucosewidget.GlucoseDb/5.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val e = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "chat_messages" }
        val expected = listOf(e.getString("createSql")) +
            (0 until e.getJSONArray("indices").length()).map { e.getJSONArray("indices").getJSONObject(it).getString("createSql") }
        assertEquals(expected.map { it.replace("\${TABLE_NAME}", "chat_messages") }, MIGRATION_4_5_SQL)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE journal (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day TEXT NOT NULL, text TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, scope TEXT NOT NULL DEFAULT 'day', uid TEXT NOT NULL DEFAULT '')")
                s.execute("INSERT INTO journal (day, text, createdAtMs, updatedAtMs, uid) VALUES ('2026-10-10', 'event: coffee @ 10:30', 1, 1, 'j1')")
                MIGRATION_4_5_SQL.forEach { s.execute(it) }
                s.execute("INSERT INTO chat_messages VALUES ('m1', 't1', 1, 1, 'geoff', 'hi', NULL, NULL)")
                s.executeQuery("SELECT COUNT(*) FROM journal").use { r -> r.next(); assertEquals(1, r.getInt(1)) }
                s.executeQuery("SELECT author FROM chat_messages").use { r -> r.next(); assertEquals("geoff", r.getString(1)) }
            }
        }
        assertNotNull(MIGRATION_4_5)
    }
}

class LoggingWordingTest {
    @org.junit.Test
    fun proposedBecomesReadyToLog() {
        org.junit.Assert.assertEquals(
            "Ready to log: 2 units of short-acting insulin at 22:59. Please confirm on screen.",
            loggingWording("Proposed 2 units of short-acting insulin at 22:59. Please confirm on screen."),
        )
        org.junit.Assert.assertEquals("Ready to log: a quarter of an apple fritter", loggingWording("Proposed: a quarter of an apple fritter"))
        org.junit.Assert.assertEquals("You were in range 66%.", loggingWording("You were in range 66%."))
    }
}
