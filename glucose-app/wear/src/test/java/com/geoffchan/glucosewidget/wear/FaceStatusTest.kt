package com.geoffchan.glucosewidget.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceStatusTest {
    @Test fun tickShowsForTenMinutes() {
        assertEquals("✓ Logged 2", faceStatus("✓ Logged 2", 1_000, 0, 1_000))
        assertEquals("✓ Logged 2", faceStatus("✓ Logged 2", 1_000, 0, 1_000 + FACE_TICK_MS - 1))
        assertNull(faceStatus("✓ Logged 2", 1_000, 0, 1_000 + FACE_TICK_MS))
    }

    @Test fun nothingLoggedShowsNothing() = assertNull(faceStatus(null, 0, 0, 5_000))

    @Test fun heldEntriesWinUntilSent() {
        assertEquals("Held on watch", faceStatus("✓ Logged 1", 1_000, 1, 2_000))
        assertEquals("Held on watch", faceStatus(null, 0, 2, 2_000))
    }

    @Test fun savedText() {
        assertEquals("✓ Logged 3", faceSaved(Saved(true, null, saved = 2, confirmed = 1, already = 1)))
        assertEquals("✓ Already logged", faceSaved(Saved(true, null, saved = 0, confirmed = 0, already = 2)))
    }
}
