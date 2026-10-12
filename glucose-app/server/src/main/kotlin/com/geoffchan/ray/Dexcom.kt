package com.geoffchan.ray

import com.geoffchan.glucosewidget.ShareClient

/**
 * Reads Dexcom Share itself (the same client as the phones), so the
 * server's readings never depend on a phone being awake. Each poll fetches
 * the trailing 24 h, which also fills any gap after downtime.
 */
class Dexcom(private val db: ServerDb, private val username: String, private val password: String) {
    private var session: String? = null

    /** Returns how many readings were new. */
    fun pollOnce(): Int {
        val (readings, s) = ShareClient.fetchHistory(username, password, session)
        session = s
        val added = db.insertReadings(readings)
        db.setMeta(META_POLLED_AT, System.currentTimeMillis().toString())
        return added
    }

    companion object {
        const val META_POLLED_AT = "dexcom_polled_at"
    }
}
