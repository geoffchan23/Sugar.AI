package com.geoffchan.glucosewidget

import android.content.Context

/**
 * The one write path for journal rows. Every insert/edit/delete — the app's
 * dialogs, the auto-routines, the ADB receiver, the watch — goes through
 * here so the change is also handed to [Sync] for the other phone, and an
 * edit or delete of a row the assistant saved is logged as a correction
 * ([InteractionLog.correction]).
 */
object Journal {
    suspend fun insert(context: Context, entry: JournalEntity): Long {
        val dao = GlucoseDb.get(context).dao()
        val id = dao.insertJournal(entry)
        dao.clearTombstone(entry.uid)
        Sync.localChanged(context, listOf(entry.copy(id = id)), emptyList())
        return id
    }

    /** Saves [entry] as given; callers set updatedAtMs (it decides sync conflicts). */
    suspend fun update(context: Context, entry: JournalEntity) {
        val dao = GlucoseDb.get(context).dao()
        val before = dao.journalByUid(entry.uid)?.text
        dao.updateJournal(entry)
        if (before != null && before != entry.text) InteractionLog.correction(context, entry.uid, before, entry.text)
        Sync.localChanged(context, listOf(entry), emptyList())
    }

    suspend fun delete(context: Context, entry: JournalEntity) {
        val dao = GlucoseDb.get(context).dao()
        val before = dao.journalByUid(entry.uid)?.text ?: entry.text
        dao.deleteJournal(entry)
        InteractionLog.correction(context, entry.uid, before, null)
        val t = JournalTombstone(entry.uid, System.currentTimeMillis())
        dao.insertTombstone(t)
        Sync.localChanged(context, emptyList(), listOf(t))
    }

    /**
     * Applies the changes she ticked (see [planChange]: rows edited or
     * deleted since are skipped). Returns what happened to each, for the
     * interaction log's outcome.
     */
    suspend fun applyChanges(context: Context, ops: List<ChangeOp>): List<SavedItem> {
        val dao = GlucoseDb.get(context).dao()
        val now = System.currentTimeMillis()
        return ops.map { op ->
            val current = when (op) {
                is ChangeOp.Edit -> dao.journalByUid(op.uid)
                is ChangeOp.Delete -> dao.journalByUid(op.uid)
                is ChangeOp.Add -> null
            }
            val dayTexts = if (op is ChangeOp.Add) dao.dayJournalBetween(op.day, op.day).map { it.text } else emptyList()
            when (val step = planChange(op, current, dayTexts)) {
                is ChangeStep.Insert -> {
                    val row = JournalEntity(day = step.day, text = step.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY)
                    insert(context, row)
                    SavedItem(changeLogText(op), step.text, row.uid, "add")
                }
                is ChangeStep.Update -> {
                    update(context, step.row.copy(text = step.text, updatedAtMs = now))
                    SavedItem(changeLogText(op), step.text, step.row.uid, "edit")
                }
                is ChangeStep.Remove -> {
                    delete(context, step.row)
                    SavedItem(changeLogText(op), "", step.row.uid, "delete")
                }
                is ChangeStep.Skip -> SavedItem(changeLogText(op), "", null, "skipped: ${step.why}")
            }
        }
    }
}
