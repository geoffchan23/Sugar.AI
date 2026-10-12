package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Broad edits from the assistant: "yesterday I didn't have coffee", "the
 * past three dinners were rice and chicken stir fry". The model proposes
 * [ChangeOp]s (via the propose_changes tool) against entries it read with
 * get_journal; she ticks which to apply. This file is the pure half —
 * validation, the stored form, labels; [Journal.applyChanges] writes.
 *
 * Changes reach back [MAX_CHANGE_DAYS] days (today included) and never into
 * the future. Existing rows are named by their local Room id in the tool
 * call and carried by uid from then on (the id only means something on the
 * phone that ran the tools).
 */
const val MAX_CHANGE_DAYS = 14

sealed class ChangeOp {
    abstract val day: String

    data class Add(override val day: String, val text: String) : ChangeOp()

    /** [before] is the row's text when proposed; a row changed since is skipped, not overwritten. */
    data class Edit(override val day: String, val uid: String, val before: String, val text: String) : ChangeOp()

    data class Delete(override val day: String, val uid: String, val before: String) : ChangeOp()
}

/** Valid changes plus why each rejected one was dropped (told back to the model). */
data class ChangeSet(val ops: List<ChangeOp>, val rejected: List<String>)

/**
 * Validates a propose_changes call. [rowById] resolves the ids get_journal
 * showed; [today] bounds the window. Entries go through the same strict
 * row rules as propose_entries ([parseEntryRow]); an edit without a time
 * keeps the row's time, an add must have one.
 */
suspend fun parseChanges(argsJson: String, today: LocalDate, rowById: suspend (Long) -> JournalEntity?): ChangeSet {
    val arr = runCatching { JSONObject(argsJson).getJSONArray("changes") }.getOrNull()
        ?: return ChangeSet(emptyList(), listOf("no changes array"))
    val first = today.minusDays(MAX_CHANGE_DAYS - 1L)
    fun inWindow(day: String) = runCatching { LocalDate.parse(day) }.getOrNull()?.let { !it.isBefore(first) && !it.isAfter(today) } ?: false
    val ops = mutableListOf<ChangeOp>()
    val rejected = mutableListOf<String>()
    val touched = mutableSetOf<String>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i)
        fun reject(why: String) { rejected += "change ${i + 1}: $why" }
        if (o == null) { reject("not an object"); continue }
        val action = o.optString("action").trim().lowercase()
        val row = if (action == "edit" || action == "delete") {
            val id = if (o.isNull("id")) null else o.optLong("id", -1L).takeIf { it > 0 }
            val r = id?.let { rowById(it) }
            if (r == null || r.scope != SCOPE_DAY) { reject("no entry with id ${o.opt("id")}"); continue }
            if (!inWindow(r.day)) { reject("entry ${r.id} is on ${r.day}, outside the last $MAX_CHANGE_DAYS days"); continue }
            if (!touched.add(r.uid)) { reject("entry ${r.id} is already changed above"); continue }
            r
        } else null
        when (action) {
            "delete" -> ops += ChangeOp.Delete(row!!.day, row.uid, row.text)
            "edit" -> {
                val keepTime = entryMinute(row!!.text)?.let { "%02d:%02d".format(it / 60, it % 60) }
                val entry = parseEntryRow(if (o.isNull("time") && keepTime != null) JSONObject(o.toString()).put("time", keepTime) else o)
                val text = entry?.noteText()
                when {
                    entry == null -> reject("not a valid dose or event")
                    text == null -> reject("needs a time")
                    text == row.text -> reject("same as what's logged")
                    else -> ops += ChangeOp.Edit(row.day, row.uid, row.text, text)
                }
            }
            "add" -> {
                val day = o.optString("day").trim()
                val text = parseEntryRow(o)?.noteText()
                when {
                    !inWindow(day) -> reject("day '$day' must be within the last $MAX_CHANGE_DAYS days, not in the future")
                    text == null -> reject("not a valid dose or event with a time")
                    ops.any { it is ChangeOp.Add && it.day == day && it.text == text } -> reject("duplicate add")
                    else -> ops += ChangeOp.Add(day, text)
                }
            }
            else -> reject("action must be add, edit or delete")
        }
    }
    return ChangeSet(ops.sortedWith(compareBy({ it.day }, { entryMinute(it.newOrOldText()) ?: 0 })), rejected)
}

private fun ChangeOp.newOrOldText(): String = when (this) {
    is ChangeOp.Add -> text
    is ChangeOp.Edit -> text
    is ChangeOp.Delete -> before
}

/** One line for the review list, e.g. "Remove 10:30 AM · coffee" (labels as in the notes list). */
fun changeLabel(op: ChangeOp): String = when (op) {
    is ChangeOp.Add -> "Add ${entryLabel(op.text)}"
    is ChangeOp.Delete -> "Remove ${entryLabel(op.before)}"
    is ChangeOp.Edit -> "Change ${entryLabel(op.before)} → ${entryLabel(op.text)}"
}

/** For the log and the outcome: the op as one canonical line. */
fun changeLogText(op: ChangeOp): String = when (op) {
    is ChangeOp.Add -> "add ${op.day} ${op.text}"
    is ChangeOp.Delete -> "delete ${op.day} ${op.before}"
    is ChangeOp.Edit -> "edit ${op.day} ${op.before} -> ${op.text}"
}

fun encodeChanges(ops: List<ChangeOp>): JSONArray = JSONArray(ops.map { op ->
    JSONObject().put("day", op.day).apply {
        when (op) {
            is ChangeOp.Add -> put("action", "add").put("text", op.text)
            is ChangeOp.Edit -> put("action", "edit").put("uid", op.uid).put("before", op.before).put("text", op.text)
            is ChangeOp.Delete -> put("action", "delete").put("uid", op.uid).put("before", op.before)
        }
    }
})

fun decodeChanges(arr: JSONArray?): List<ChangeOp> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
    val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
    runCatching {
        when (o.getString("action")) {
            "add" -> ChangeOp.Add(o.getString("day"), o.getString("text"))
            "edit" -> ChangeOp.Edit(o.getString("day"), o.getString("uid"), o.getString("before"), o.getString("text"))
            "delete" -> ChangeOp.Delete(o.getString("day"), o.getString("uid"), o.getString("before"))
            else -> null
        }
    }.getOrNull()
}

/** What applying [op] does given the row as it is now ([current], null if gone). */
sealed class ChangeStep {
    data class Insert(val day: String, val text: String) : ChangeStep()
    data class Update(val row: JournalEntity, val text: String) : ChangeStep()
    data class Remove(val row: JournalEntity) : ChangeStep()
    data class Skip(val why: String) : ChangeStep()
}

/**
 * Edits and deletes only touch a row still as she saw it: if it was deleted
 * or edited since (on either phone), the change is skipped rather than
 * clobbering the newer version. Adds already on that day are skipped too.
 */
fun planChange(op: ChangeOp, current: JournalEntity?, dayTexts: List<String>): ChangeStep = when (op) {
    is ChangeOp.Add -> if (op.text in dayTexts) ChangeStep.Skip("already logged") else ChangeStep.Insert(op.day, op.text)
    is ChangeOp.Edit -> when {
        current == null -> ChangeStep.Skip("entry was deleted")
        current.text != op.before -> ChangeStep.Skip("entry changed since")
        else -> ChangeStep.Update(current, op.text)
    }
    is ChangeOp.Delete -> when {
        current == null -> ChangeStep.Skip("already deleted")
        current.text != op.before -> ChangeStep.Skip("entry changed since")
        else -> ChangeStep.Remove(current)
    }
}

// ---------------------------------------------------------------- conversation

/** One earlier exchange: what she said, what the assistant answered. */
data class Turn(val said: String, val answered: String)

const val MAX_HISTORY_TURNS = 4

/**
 * The conversation so far when she replies to the record [data]/[input]:
 * that record's own history plus its exchange, newest last, capped.
 */
fun historyAfter(input: String, data: String): List<Turn> {
    val d = runCatching { JSONObject(data) }.getOrDefault(JSONObject())
    val earlier = d.optJSONArray("history")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Turn(it.optString("said"), it.optString("answered")) }
    }.orEmpty()
    return (earlier + Turn(input, d.optString("answer"))).takeLast(MAX_HISTORY_TURNS)
}

fun encodeHistory(turns: List<Turn>): JSONArray =
    JSONArray(turns.map { JSONObject().put("said", it.said).put("answered", it.answered) })
