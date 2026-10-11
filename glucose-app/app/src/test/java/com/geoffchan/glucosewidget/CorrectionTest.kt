package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTest {
    private val c1 = Correction("u1", CORRECTION_EDIT, "22:59 · 2u short-acting", "22:59 · 3u short-acting", "geoff", 100)
    private val c2 = Correction("u2", CORRECTION_DELETE, "23:00 · apple fritter", "", "francine", 200)

    @Test fun ops() {
        assertNull(correctionOp("a", "a"))
        assertEquals(CORRECTION_DELETE, correctionOp("a", null))
        assertEquals(CORRECTION_EDIT, correctionOp("a", "b"))
        assertEquals(CORRECTION_CONFIRM, correctionOp("10:30 · coffee$GUESS_SUFFIX", "10:30 · coffee"))
    }

    @Test fun roundTripAndMergeKeepBothSides() {
        val base = Outcome(OUTCOME_SAVED, 50, listOf(SavedItem("x", "x", "u1", "insert")))
        val mine = encodeOutcome(base.copy(corrections = listOf(c1)))
        val theirs = encodeOutcome(base.copy(corrections = listOf(c2)))
        assertEquals(listOf(c1), decodeOutcome(mine)!!.corrections)
        val merged = decodeOutcome(mergeOutcome(mine, theirs))!!
        assertEquals(listOf(c1, c2), merged.corrections)
        assertEquals(OUTCOME_SAVED, merged.kind)
        // a later cancel never drops the save or its corrections
        val cancel = encodeOutcome(Outcome(OUTCOME_CANCELLED, 300))
        assertEquals(listOf(c1), decodeOutcome(mergeOutcome(mine, cancel))!!.corrections)
    }

    @Test fun lessons() {
        val text = correctionLessons(listOf(CorrectionExample("log two short", c1), CorrectionExample("had a fritter", c2)))
        assertTrue(text.startsWith("Past corrections"))
        val lines = text.lines().drop(1)
        assertEquals("- \"had a fritter\" → you logged \"23:00 · apple fritter\"; Francine deleted it.", lines[0])
        assertTrue(lines[1].contains("changed it to \"22:59 · 3u short-acting\""))
        assertEquals("", correctionLessons(emptyList()))
    }

    @Test fun parseRequestAutoSave() {
        assertTrue(decodeParseRequest("""{"id":"a","text":"hi","autoSave":true}""").autoSave)
        assertEquals(false, decodeParseRequest("""{"id":"a","text":"hi"}""").autoSave)
    }
}
