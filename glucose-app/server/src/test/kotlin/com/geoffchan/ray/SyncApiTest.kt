package com.geoffchan.ray

import com.geoffchan.glucosewidget.SyncChat
import com.geoffchan.glucosewidget.SyncPayload
import com.geoffchan.glucosewidget.SyncRow
import com.geoffchan.glucosewidget.SyncTomb
import com.geoffchan.glucosewidget.decodePayload
import com.geoffchan.glucosewidget.encodePayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiTest {
    private val now = System.currentTimeMillis()

    private fun body(cursor: Long?, p: SyncPayload?) = JSONObject()
        .put("cursor", cursor ?: JSONObject.NULL)
        .apply { if (p != null) put("payload", JSONObject(encodePayload(p))) }
        .toString()

    private fun reply(json: String): Triple<Long, Boolean, SyncPayload> {
        val o = JSONObject(json)
        return Triple(o.getLong("cursor"), o.getBoolean("more"), decodePayload(o.getJSONObject("payload").toString())!!)
    }

    @Test fun changesFlowBetweenPhonesButNotBack() {
        val db = ServerDb(":memory:")
        val api = SyncApi(db)
        // both phones start: first sync returns recent state and a cursor
        val (c1, _, _) = reply(api.handle("her", body(null, null)).json)
        val (c2, _, _) = reply(api.handle("geoff", body(null, null)).json)

        val row = SyncRow("u1", "2026-10-12", "dose: short-acting 4u @ 10:30", "day", now, now)
        val (c1b, _, back) = reply(api.handle("her", body(c1, SyncPayload("her", rows = listOf(row)))).json)
        assertTrue("her own change isn't echoed", back.rows.isEmpty())

        val (c2b, _, got) = reply(api.handle("geoff", body(c2, null)).json)
        assertEquals(listOf("u1"), got.rows.map { it.uid })

        // geoff deletes it; her phone gets the tombstone
        api.handle("geoff", body(c2b, SyncPayload("geoff", tombs = listOf(SyncTomb("u1", now + 10)))))
        val (_, _, del) = reply(api.handle("her", body(c1b, null)).json)
        assertEquals(listOf("u1"), del.tombs.map { it.uid })
        assertTrue(del.rows.isEmpty())
    }

    @Test fun firstSyncSendsRecentStateAndChat() {
        val db = ServerDb(":memory:")
        val api = SyncApi(db)
        api.handle("her", body(0, SyncPayload("her",
            rows = listOf(SyncRow("old", "2026-01-01", "event: x @ 10:00", "day", 1, 1), SyncRow("new", "2026-10-12", "event: y @ 10:00", "day", now, now)),
            chat = listOf(SyncChat("c1", "t", now, now, "geoff", "hi", null, null)))))
        val (cursor, more, p) = reply(api.handle("geoff", body(null, null)).json)
        assertEquals(listOf("new"), p.rows.map { it.uid })
        assertEquals(listOf("c1"), p.chat.map { it.uid })
        assertFalse(more)
        assertEquals(db.maxSeq(), cursor)
    }

    @Test fun pagesLongBacklogs() {
        val db = ServerDb(":memory:")
        val api = SyncApi(db)
        val rows = (1..(SyncApi.PAGE + 5)).map { SyncRow("u$it", "2026-10-12", "event: e$it @ 10:00", "day", now, now) }
        api.handle("her", body(0, SyncPayload("her", rows = rows)))
        val (c, more, p) = reply(api.handle("geoff", body(0, null)).json)
        assertTrue(more); assertEquals(SyncApi.PAGE, p.rows.size)
        val (_, more2, p2) = reply(api.handle("geoff", body(c, null)).json)
        assertFalse(more2); assertEquals(5, p2.rows.size)
    }

    @Test fun deviceTokens() {
        val token = "a".repeat(40)
        val d = Devices("her:${Devices.sha256(token)}, geoff:${Devices.sha256("b".repeat(40))}")
        assertEquals("her", d.device("Bearer $token"))
        assertNull(d.device("Bearer " + "c".repeat(40)))
        assertNull(d.device(null))
        assertNull(d.device("Bearer short"))
    }
}
