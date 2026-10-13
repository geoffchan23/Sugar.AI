package com.geoffchan.glucosewidget

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "glucose")

data class Settings(val lowMmol: Double, val highMmol: Double)

/** Cached state + settings. Credentials live in EncryptedSharedPreferences. */
object Store {
    private val KEY_MGDL = intPreferencesKey("mgdl")
    private val KEY_TS = longPreferencesKey("timestampMs")
    private val KEY_TREND = stringPreferencesKey("trend")
    private val KEY_LOW = doublePreferencesKey("lowMmol")
    private val KEY_HIGH = doublePreferencesKey("highMmol")

    const val DEFAULT_LOW = 3.9
    const val DEFAULT_HIGH = 10.0

    suspend fun saveReading(context: Context, r: Reading) {
        context.dataStore.edit {
            it[KEY_MGDL] = r.mgdl
            it[KEY_TS] = r.timestampMs
            it[KEY_TREND] = r.trend
        }
    }

    suspend fun reading(context: Context): Reading? {
        val p = context.dataStore.data.first()
        val mgdl = p[KEY_MGDL] ?: return null
        return Reading(mgdl, p[KEY_TS] ?: 0L, p[KEY_TREND] ?: "None")
    }

    suspend fun saveSettings(context: Context, s: Settings) {
        context.dataStore.edit {
            it[KEY_LOW] = s.lowMmol
            it[KEY_HIGH] = s.highMmol
        }
    }

    suspend fun settings(context: Context): Settings {
        val p = context.dataStore.data.first()
        return Settings(p[KEY_LOW] ?: DEFAULT_LOW, p[KEY_HIGH] ?: DEFAULT_HIGH)
    }

    private val KEY_LAST_MED = stringPreferencesKey("lastMed")

    suspend fun lastMedication(context: Context): String =
        context.dataStore.data.first()[KEY_LAST_MED] ?: "short-acting"

    suspend fun saveLastMedication(context: Context, name: String) {
        context.dataStore.edit { it[KEY_LAST_MED] = name }
    }

    private val KEY_ROUTINE_DAY = stringPreferencesKey("routineLoggedDay")

    /** Local date ("YYYY-MM-DD") the morning routine was last auto-logged for. */
    suspend fun routineLoggedDay(context: Context): String? =
        context.dataStore.data.first()[KEY_ROUTINE_DAY]

    suspend fun saveRoutineLoggedDay(context: Context, day: String) {
        context.dataStore.edit { it[KEY_ROUTINE_DAY] = day }
    }

    private val KEY_IS_MAIN = androidx.datastore.preferences.core.booleanPreferencesKey("isMainPhone")

    /**
     * The main phone (hers, from 2026-10) runs the auto-routines and takes
     * watch entries; the other phone (Geoff's) syncs but never auto-logs,
     * so routines aren't inserted twice. Defaults to main.
     */
    suspend fun isMainPhone(context: Context): Boolean =
        context.dataStore.data.first()[KEY_IS_MAIN] ?: true

    suspend fun saveIsMainPhone(context: Context, main: Boolean) {
        context.dataStore.edit { it[KEY_IS_MAIN] = main }
    }

    private val KEY_CHAT_AUTHOR = stringPreferencesKey("chatAuthor")

    /** Who writes in the chat from this phone: set in Settings, else hers if main, Geoff's otherwise. */
    suspend fun chatAuthor(context: Context): String =
        context.dataStore.data.first()[KEY_CHAT_AUTHOR] ?: if (isMainPhone(context)) AUTHOR_FRANCINE else AUTHOR_GEOFF

    suspend fun saveChatAuthor(context: Context, author: String) {
        context.dataStore.edit { it[KEY_CHAT_AUTHOR] = author }
    }

    // --- two-phone sync state (key + topic are in securePrefs below) ---

    private val KEY_DEVICE_ID = stringPreferencesKey("syncDeviceId")
    private val KEY_SYNC_SINCE = stringPreferencesKey("syncSinceId")
    private val KEY_SYNC_OUTBOX = androidx.datastore.preferences.core.stringSetPreferencesKey("syncOutbox")
    private val KEY_SYNC_POLL_AT = longPreferencesKey("syncLastPollAt")
    private val KEY_SYNC_PUBLISH_AT = longPreferencesKey("syncLastPublishAt")
    private val KEY_SYNC_DIGEST_AT = longPreferencesKey("syncLastDigestAt")
    private val KEY_SYNC_ERROR = stringPreferencesKey("syncLastError")

    /** Random per install; tells this phone's own relay messages apart. */
    suspend fun deviceId(context: Context): String {
        context.dataStore.data.first()[KEY_DEVICE_ID]?.let { return it }
        val id = newUid()
        context.dataStore.edit { if (it[KEY_DEVICE_ID] == null) it[KEY_DEVICE_ID] = id }
        return context.dataStore.data.first()[KEY_DEVICE_ID]!!
    }

    /** Last relay message id applied; null = never polled (ask for everything cached). */
    suspend fun syncSince(context: Context): String? = context.dataStore.data.first()[KEY_SYNC_SINCE]
    suspend fun saveSyncSince(context: Context, id: String) { context.dataStore.edit { it[KEY_SYNC_SINCE] = id } }

    /** uids whose current state (row or tombstone) still has to be published. */
    suspend fun syncOutbox(context: Context): Set<String> = context.dataStore.data.first()[KEY_SYNC_OUTBOX] ?: emptySet()
    fun syncOutboxFlow(context: Context): Flow<Set<String>> = context.dataStore.data.map { it[KEY_SYNC_OUTBOX] ?: emptySet() }
    suspend fun addToOutbox(context: Context, uids: Collection<String>) {
        if (uids.isEmpty()) return
        context.dataStore.edit { it[KEY_SYNC_OUTBOX] = (it[KEY_SYNC_OUTBOX] ?: emptySet()) + uids }
    }
    suspend fun removeFromOutbox(context: Context, uids: Collection<String>) {
        context.dataStore.edit { it[KEY_SYNC_OUTBOX] = (it[KEY_SYNC_OUTBOX] ?: emptySet()) - uids.toSet() }
    }

    data class SyncStatus(val lastPollAt: Long?, val lastPublishAt: Long?, val lastDigestAt: Long?, val lastError: String?, val outbox: Int)

    suspend fun syncStatus(context: Context): SyncStatus {
        val p = context.dataStore.data.first()
        return SyncStatus(p[KEY_SYNC_POLL_AT], p[KEY_SYNC_PUBLISH_AT], p[KEY_SYNC_DIGEST_AT], p[KEY_SYNC_ERROR], (p[KEY_SYNC_OUTBOX] ?: emptySet()).size)
    }
    suspend fun markPolled(context: Context) { context.dataStore.edit { it[KEY_SYNC_POLL_AT] = System.currentTimeMillis() } }
    suspend fun markPublished(context: Context) { context.dataStore.edit { it[KEY_SYNC_PUBLISH_AT] = System.currentTimeMillis() } }
    suspend fun markDigest(context: Context) { context.dataStore.edit { it[KEY_SYNC_DIGEST_AT] = System.currentTimeMillis() } }
    suspend fun saveSyncError(context: Context, error: String?) {
        context.dataStore.edit { if (error == null) it.remove(KEY_SYNC_ERROR) else it[KEY_SYNC_ERROR] = error }
    }

    /** Forget all sync state (config is cleared separately). */
    suspend fun resetSyncState(context: Context) {
        context.dataStore.edit {
            it.remove(KEY_SYNC_SINCE); it.remove(KEY_SYNC_OUTBOX); it.remove(KEY_SYNC_POLL_AT)
            it.remove(KEY_SYNC_PUBLISH_AT); it.remove(KEY_SYNC_DIGEST_AT); it.remove(KEY_SYNC_ERROR)
        }
    }

    data class SyncConfig(val key: ByteArray, val topic: String)

    /** Null = sync not set up on this phone; everything then stays local. */
    fun syncConfig(context: Context): SyncConfig? {
        val p = securePrefs(context)
        val key = p.getString("syncKey", null)?.let { SyncCrypto.keyFromBase64(it) } ?: return null
        val topic = p.getString("syncTopic", null) ?: return null
        return SyncConfig(key, topic)
    }

    fun saveSyncConfig(context: Context, keyBase64: String, topic: String) {
        securePrefs(context).edit().putString("syncKey", keyBase64).putString("syncTopic", topic).commit()
    }

    fun clearSyncConfig(context: Context) {
        securePrefs(context).edit().remove("syncKey").remove("syncTopic").commit()
    }

    // --- the Ray server (cloud Ray): URL + this phone's device token, in encrypted prefs ---

    data class RayServerConfig(val url: String, val token: String)

    /** Null = no Ray server: sync goes through the ntfy relay (if set up) and Ray runs on the phone. */
    fun rayServer(context: Context): RayServerConfig? {
        val p = securePrefs(context)
        val token = p.getString("rayToken", null)?.takeIf { it.isNotBlank() } ?: return null
        return RayServerConfig(p.getString("rayUrl", null) ?: DEFAULT_RAY_URL, token)
    }

    fun saveRayServer(context: Context, url: String, token: String) {
        securePrefs(context).edit().putString("rayUrl", url.trimEnd('/')).putString("rayToken", token).commit()
    }

    fun clearRayServer(context: Context) {
        securePrefs(context).edit().remove("rayUrl").remove("rayToken").commit()
    }

    private val KEY_RAY_CURSOR = longPreferencesKey("rayCursor")

    /** The server's change-log position this phone has applied; null = never synced with it. */
    suspend fun rayCursor(context: Context): Long? = context.dataStore.data.first()[KEY_RAY_CURSOR]
    suspend fun saveRayCursor(context: Context, cursor: Long?) {
        context.dataStore.edit { if (cursor == null) it.remove(KEY_RAY_CURSOR) else it[KEY_RAY_CURSOR] = cursor }
    }

    // --- assistant (OpenAI): key, model and effort, all in encrypted prefs ---

    /** Null = assistant not set up; Nano and the rule parser handle everything. */
    fun aiConfig(context: Context): AiConfig? {
        val p = securePrefs(context)
        val key = p.getString("aiKey", null)?.takeIf { it.isNotBlank() } ?: return null
        return AiConfig(key, p.getString("aiModel", null) ?: DEFAULT_AI_MODEL, p.getString("aiEffort", null))
    }

    /** [key] null keeps the stored one (to change only the model or effort). */
    fun saveAiConfig(context: Context, key: String?, model: String?, effort: String?) {
        securePrefs(context).edit().apply {
            key?.let { putString("aiKey", it) }
            model?.let { if (it.isBlank()) remove("aiModel") else putString("aiModel", it) }
            effort?.let { if (it.isBlank() || it == "auto") remove("aiEffort") else putString("aiEffort", it) }
        }.commit()
    }

    fun clearAiConfig(context: Context) {
        securePrefs(context).edit().remove("aiKey").remove("aiModel").remove("aiEffort").commit()
    }

    private val KEY_WATCH_QUEUED_IDS = stringPreferencesKey("watchQueuedIds")

    /** Ids of watch entries queued while offline that were already saved (newest last). */
    suspend fun watchQueuedIds(context: Context): List<String> =
        context.dataStore.data.first()[KEY_WATCH_QUEUED_IDS]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    suspend fun saveWatchQueuedIds(context: Context, ids: List<String>) {
        context.dataStore.edit { it[KEY_WATCH_QUEUED_IDS] = ids.joinToString(",") }
    }

    // --- credentials + session ---

    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveCredentials(context: Context, username: String, password: String) {
        securePrefs(context).edit().putString("u", username).putString("p", password).remove("s").apply()
    }

    fun credentials(context: Context): Pair<String, String>? {
        val p = securePrefs(context)
        val u = p.getString("u", null) ?: return null
        val pw = p.getString("p", null) ?: return null
        return u to pw
    }

    fun saveSession(context: Context, session: String) {
        securePrefs(context).edit().putString("s", session).apply()
    }

    fun session(context: Context): String? = securePrefs(context).getString("s", null)
}
