package com.geoffchan.ray

import com.geoffchan.glucosewidget.JournalEntity
import com.geoffchan.glucosewidget.Reading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

class RayServiceTest {
    private val deno = listOf("/usr/local/bin/deno", "/opt/homebrew/bin/deno", System.getProperty("user.home") + "/.deno/bin/deno").firstOrNull { File(it).canExecute() }

    @Test fun quickVoiceAnswersNeedNoModel() {
        val db = ServerDb(":memory:")
        val now = System.currentTimeMillis()
        db.insertReadings(listOf(Reading(112, now - 3 * 60_000, "Flat")))
        val today = ZonedDateTime.now(RayService.ZONE).toLocalDate().toString()
        runBlocking { db.insertJournal(JournalEntity(day = today, text = "dose: short-acting 4u @ 08:05", createdAtMs = now, updatedAtMs = now)) }
        val ray = RayService(db, null, "m", "deno")
        assertEquals("Francine is 6 point 2 and steady, as of 3 minutes ago.", ray.voice("what's Francine's blood sugar", "Alexa", "alexa", 5000).speech)
        assertEquals("Francine's last logged dose was 4 units of short-acting at 8:05 am today.", ray.voice("when did she last take insulin", "Alexa", "alexa", 5000).speech)
        assertEquals("Ray isn't set up on the server yet.", ray.voice("why was she high last night", "Alexa", "alexa", 5000).speech)
    }

    @Test fun quickAnswersSayHowTheyWereAnswered() {
        val ray = RayService(ServerDb(":memory:"), null, "m", "deno")
        assertEquals("quick:READING", ray.voice("what's her sugar", "Alexa", "alexa", 5000).path)
        assertEquals("off", ray.voice("why was she high", "Alexa", "alexa", 5000).path)
    }

    @Test fun voiceSaveConfirmsGuessesAndSkipsRepeats() {
        val db = ServerDb(":memory:")
        val ray = RayService(db, null, "m", "deno")
        val day = LocalDate.now().toString()
        runBlocking { db.insertJournal(JournalEntity(day = day, text = "dose: short-acting 4u @ 10:30 (guess)", createdAtMs = 1, updatedAtMs = 1)) }
        val items = ray.save(day, listOf("dose: short-acting 4u @ 10:30", "event: coffee @ 10:30", "event: coffee @ 10:30"))
        assertEquals(listOf("confirm", "insert", "already"), items.map { it.op })
        assertEquals(2, runBlocking { db.journal(day, day) }.size)
        assertTrue(db.maxSeq() >= 2)
    }

    @Test fun queryIsReadOnly() {
        val f = File.createTempFile("ray", ".db"); f.deleteOnExit()
        val db = ServerDb(f.path)
        db.insertReadings(listOf(Reading(100, 1, "Flat")))
        val ray = RayService(db, null, "m", "deno")
        assertTrue(ray.query("SELECT COUNT(*) AS n FROM readings").contains("\"n\""))
        assertTrue(ray.query("DELETE FROM readings").contains("error"))
        assertTrue(ray.query("WITH x AS (SELECT 1) DELETE FROM readings").contains("error"))
        assertEquals(1, db.counts()["readings"])
    }

    @Test fun javascriptRunsSandboxed() {
        assumeTrue("deno not installed", deno != null)
        val ray = RayService(ServerDb(":memory:"), null, "m", deno!!)
        assertEquals("42", ray.runJs("(function(){ return String(6*7) })()"))
        assertTrue(ray.runJs("Deno.readTextFileSync('/etc/passwd')").contains("NotCapable") || ray.runJs("Deno.readTextFileSync('/etc/passwd')").contains("PermissionDenied") || ray.runJs("Deno.readTextFileSync('/etc/passwd')").contains("Requires read access"))
    }
}
