package com.geoffchan.glucosewidget

/**
 * Where a sync payload is merged: Room on the phones ([GlucoseDao] through
 * an adapter), SQLite on the Ray server. Same names as the DAO's.
 */
interface SyncStore {
    suspend fun journalByUid(uid: String): JournalEntity?
    suspend fun insertJournal(entry: JournalEntity): Long
    suspend fun updateJournal(entry: JournalEntity)
    suspend fun deleteJournal(entry: JournalEntity)
    suspend fun tombstone(uid: String): JournalTombstone?
    suspend fun insertTombstone(t: JournalTombstone)
    suspend fun clearTombstone(uid: String)
    suspend fun interaction(uid: String): AssistantLogEntity?
    suspend fun insertInteraction(e: AssistantLogEntity)
    suspend fun updateInteraction(e: AssistantLogEntity)
    suspend fun chatMessage(uid: String): ChatMessageEntity?
    suspend fun upsertChat(m: ChatMessageEntity)
}

/**
 * Merge one payload from another device into [store] (the caller wraps it
 * in a transaction). New chat messages are added to [incoming]. Returns
 * whether the journal changed.
 */
suspend fun applyPayload(store: SyncStore, p: SyncPayload, incoming: MutableList<ChatMessageEntity>): Boolean {
    var changed = false
    for (r in p.rows) {
        val local = store.journalByUid(r.uid)
        when (decideRow(r, local, store.tombstone(r.uid)?.deletedAtMs)) {
            RowAction.INSERT -> {
                store.insertJournal(JournalEntity(day = r.day, text = r.text, createdAtMs = r.createdAtMs, updatedAtMs = r.updatedAtMs, scope = r.scope, uid = r.uid))
                store.clearTombstone(r.uid)
                changed = true
            }
            RowAction.UPDATE -> {
                store.updateJournal(local!!.copy(day = r.day, text = r.text, scope = r.scope, createdAtMs = r.createdAtMs, updatedAtMs = r.updatedAtMs))
                changed = true
            }
            RowAction.IGNORE -> Unit
        }
    }
    for (t in p.tombs) {
        val local = store.journalByUid(t.uid)
        if (tombDeletesLocal(t, local)) {
            store.deleteJournal(local!!)
            changed = true
        }
        val known = store.tombstone(t.uid)
        if (known == null || known.deletedAtMs < t.deletedAtMs) store.insertTombstone(JournalTombstone(t.uid, t.deletedAtMs))
    }
    for (i in p.inter) {
        val local = store.interaction(i.uid)
        applyRemoteInteraction(i, local)?.let { if (local == null) store.insertInteraction(it) else store.updateInteraction(it) }
    }
    for (c in p.chat) {
        val local = store.chatMessage(c.uid)
        mergeChat(c, local)?.let {
            store.upsertChat(it)
            if (local == null) incoming += it
        }
    }
    return changed
}
