package com.geoffchan.ray

import com.geoffchan.glucosewidget.AUTHOR_FRANCINE
import com.geoffchan.glucosewidget.AUTHOR_GEOFF
import com.geoffchan.glucosewidget.MODE_CHAT
import com.geoffchan.glucosewidget.MODE_LOG
import com.geoffchan.glucosewidget.RunRequest
import com.geoffchan.glucosewidget.assistantUserTurn
import com.geoffchan.glucosewidget.chatUserTurn
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * How Ray sounds, on a copy of the server's data — not a unit test: skipped
 * unless RAY_EVAL_DB (a copy of ray.db, kept in gitignored data/) and
 * OPENAI_API_KEY are set. Runs tools/ray-voice-questions.txt (one per line:
 * `chat geoff|francine <text>` or `watch <text>`) and writes the answers
 * as JSONL to RAY_EVAL_OUT. tools/ray-voice-eval.sh wraps it.
 */
class RayVoiceEval {
    @Test fun answers() {
        val dbPath = System.getenv("RAY_EVAL_DB")
        val key = System.getenv("OPENAI_API_KEY")
        assumeTrue("RAY_EVAL_DB / OPENAI_API_KEY not set", !dbPath.isNullOrBlank() && !key.isNullOrBlank())
        val questions = File(System.getenv("RAY_EVAL_QUESTIONS") ?: "../tools/ray-voice-questions.txt").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val out = File(System.getenv("RAY_EVAL_OUT") ?: "../data/ray-voice-eval.jsonl")
        out.writeText("")
        val db = ServerDb(dbPath!!)
        val ray = RayService(db, key, System.getenv("RAY_MODEL") ?: RayService.DEFAULT_MODEL, "deno")
        val latest = db.latestReading()
        val now = latest?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.timestampMs), RayService.ZONE) }
            ?: ZonedDateTime.now(RayService.ZONE)
        val current = latest?.let { com.geoffchan.glucosewidget.readingText(it, it.timestampMs + 60_000) }
        for (q in questions) {
            val parts = q.split(" ", limit = 3)
            val req = if (parts[0] == "watch") {
                val text = q.removePrefix("watch ").trim()
                RunRequest(MODE_LOG, assistantUserTurn(text, now, now.toLocalDate(), true, current), "medium", emptyList(), now.toLocalDate().toString(), RayService.ZONE.id, 6, 4_000)
            } else {
                val author = if (parts[1] == "geoff") AUTHOR_GEOFF else AUTHOR_FRANCINE
                RunRequest(MODE_CHAT, chatUserTurn(author, parts[2], now, current), "medium", emptyList(), now.toLocalDate().toString(), RayService.ZONE.id, 14, 16_000)
            }
            val (r, _) = ray.run(req)
            val line = JSONObject().put("q", q).put("answer", r.answer).put("detail", r.detail ?: JSONObject.NULL)
                .put("tools", com.geoffchan.glucosewidget.toolsUsed(r.trace).let { t -> (0 until t.length()).map { t.getJSONObject(it).getString("name") } })
                .put("ms", r.ms).put("costUsd", r.estimatedCostUsd)
            out.appendText(line.toString() + "\n")
            println("\n=== $q\n${r.answer}${r.detail?.let { "\n-- detail: $it" } ?: ""}\ntools: ${line.opt("tools")}\n(${r.ms} ms, $${"%.4f".format(r.estimatedCostUsd)})")
        }
    }
}
