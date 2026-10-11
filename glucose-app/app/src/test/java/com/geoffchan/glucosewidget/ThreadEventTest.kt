package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ThreadEventTest {
    private fun m(thread: String, at: Long, author: String, text: String = "hi", meta: String? = null) =
        ChatMessageEntity(thread = thread, createdAtMs = at, updatedAtMs = at, author = author, text = text, meta = meta)

    @Test fun renameDeleteAndHiddenEvents() {
        val all = listOf(
            m("a", 1, AUTHOR_GEOFF, "How was this past week?"), m("a", 2, AUTHOR_RAY, "Fine"),
            m("a", 3, AUTHOR_SYSTEM, "", threadEventMeta(rename = "Weekly check").toString()),
            m("b", 4, AUTHOR_FRANCINE, "log coffee"),
            m("b", 5, AUTHOR_SYSTEM, "", threadEventMeta(deleted = true).toString()),
            m("c", 6, AUTHOR_SYSTEM, "", threadEventMeta(rename = "empty").toString()),
        )
        val t = chatThreads(all)
        assertEquals(listOf("a"), t.map { it.thread })
        assertEquals("Weekly check", t[0].title)
        assertEquals("Ray: Fine", t[0].lastLine)
        assertTrue(chatPrior(all.filter { it.thread == "a" }).none { it.getString("content").isEmpty() })
        assertEquals(2, chatPrior(all.filter { it.thread == "a" }).size)
    }

    @Test fun groups() {
        val z = ZoneId.of("America/Toronto")
        val today = LocalDate.of(2026, 10, 11)
        fun at(d: LocalDate) = d.atTime(12, 0).atZone(z).toInstant().toEpochMilli()
        assertEquals("Today", threadGroup(at(today), today, z))
        assertEquals("Yesterday", threadGroup(at(today.minusDays(1)), today, z))
        assertEquals("Previous 7 days", threadGroup(at(today.minusDays(6)), today, z))
        assertEquals("September", threadGroup(at(LocalDate.of(2026, 9, 1)), today, z))
        assertEquals("December 2025", threadGroup(at(LocalDate.of(2025, 12, 1)), today, z))
    }
}
