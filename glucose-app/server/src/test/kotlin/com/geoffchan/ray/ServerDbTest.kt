package com.geoffchan.ray

import com.geoffchan.glucosewidget.ChatMessageEntity
import com.geoffchan.glucosewidget.Reading
import com.geoffchan.glucosewidget.SyncChat
import com.geoffchan.glucosewidget.SyncPayload
import com.geoffchan.glucosewidget.SyncRow
import com.geoffchan.glucosewidget.SyncTomb
import com.geoffchan.glucosewidget.applyPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDbTest {
    private fun db() = ServerDb(":memory:")

    private fun apply(db: ServerDb, p: SyncPayload, incoming: MutableList<ChatMessageEntity> = mutableListOf()) =
        db.tx { runBlocking { applyPayload(this@tx, p, incoming) } }

    @Test fun readingsIgnoreDuplicates() {
        val db = db()
        assertEquals(2, db.insertReadings(listOf(Reading(100, 1_000, "Flat"), Reading(110, 2_000, "Flat"))))
        assertEquals(1, db.insertReadings(listOf(Reading(110, 2_000, "Flat"), Reading(120, 3_000, "SingleUp"))))
        assertEquals(120, db.latestReading()!!.mgdl)
    }

    @Test fun mergesRowsEditsAndDeletes() = runBlocking {
        val db = db()
        val row = SyncRow("u1", "2026-10-12", "4u short-acting at 08:00", "day", 10, 10)
        assertTrue(apply(db, SyncPayload("phone", rows = listOf(row))))
        assertEquals("4u short-acting at 08:00", db.journalByUid("u1")!!.text)

        // an older copy loses; a newer edit wins
        assertFalse(apply(db, SyncPayload("phone", rows = listOf(row.copy(text = "old", updatedAtMs = 5)))))
        assertTrue(apply(db, SyncPayload("phone", rows = listOf(row.copy(text = "5u short-acting at 08:00", updatedAtMs = 20)))))
        assertEquals("5u short-acting at 08:00", db.journalByUid("u1")!!.text)

        assertTrue(apply(db, SyncPayload("phone", tombs = listOf(SyncTomb("u1", 30)))))
        assertNull(db.journalByUid("u1"))
        // a stale resend of the row doesn't bring it back
        assertFalse(apply(db, SyncPayload("phone", rows = listOf(row.copy(updatedAtMs = 20)))))
        assertEquals(30L, db.tombstone("u1")!!.deletedAtMs)
    }

    @Test fun newChatMessagesAreReported() = runBlocking {
        val db = db()
        val incoming = mutableListOf<ChatMessageEntity>()
        val m = SyncChat("c1", "t1", 1, 1, "francine", "how was last night?", null, null)
        apply(db, SyncPayload("phone", chat = listOf(m)), incoming)
        assertEquals(listOf("c1"), incoming.map { it.uid })
        assertEquals("how was last night?", db.chatMessage("c1")!!.text)
    }

    @Test fun metaAndCounts() {
        val db = db()
        db.setMeta("k", "1"); db.setMeta("k", "2")
        assertEquals("2", db.meta("k"))
        assertEquals(0, db.counts()["journal"])
    }
}
