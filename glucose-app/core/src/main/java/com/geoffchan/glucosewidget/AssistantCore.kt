package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

// Ray's prompt, config and result types: shared by the phones and the server (see Assistant.kt for the phone loop).

/**
 * Francine's CGM assistant: OpenAI's Responses API with function tools over
 * her own data ([AssistantTools]). It logs what she says (as proposals she
 * confirms) and answers questions about her glucose. It explains patterns;
 * it never recommends insulin doses.
 *
 * Raw HTTPS (okhttp + org.json) rather than the openai-java SDK: the SDK
 * brings Jackson and a newer Kotlin stdlib, and this project is pinned to
 * Kotlin 2.1.21 for ML Kit's genai-prompt; the Responses API JSON is small.
 *
 * What leaves the phone: her message, the current reading, and whatever the
 * tools return for that question (readings, stats, logs). `store=false`.
 */
data class AiConfig(val key: String, val model: String, val effort: String?)

data class AssistantResult(
    val answer: String,
    val detail: String?,
    val entries: List<ProposedEntry>,
    val rejected: Int,
    val model: String,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val ms: Long,
    val reasoningTokens: Int = 0,
    val effort: String? = null,
    val userTurn: String? = null,
    /** Per round: usage, each tool call (args + result, results cut at [MAX_TOOL_RESULT_CHARS]), any text. */
    val trace: JSONArray = JSONArray(),
    /** Edits/deletes/adds on other days (propose_changes), and why any were dropped. */
    val changes: List<ChangeOp> = emptyList(),
    val changeRejects: List<String> = emptyList(),
    /** The answer is a question she needs to answer before anything can be logged. */
    val awaitingAnswer: Boolean = false,
) {
    val estimatedCostUsd: Double get() = aiCostUsd(model, inputTokens, cachedTokens, outputTokens)
}

class AssistantException(message: String) : Exception(message)

/** $ per 1M tokens (input, cached input, output); unknown models cost as Luna. */
private val PRICES = mapOf(
    "gpt-6-luna" to Triple(0.10, 0.01, 0.50),
    "gpt-6.1-sol" to Triple(2.00, 0.10, 10.00),
    "gpt-6-astra" to Triple(10.00, 1.00, 50.00),
)

fun aiCostUsd(model: String, input: Int, cached: Int, output: Int): Double {
    val (i, c, o) = PRICES[model] ?: PRICES.getValue(DEFAULT_AI_MODEL)
    return ((input - cached) * i + cached * c + output * o) / 1_000_000.0
}

const val DEFAULT_AI_MODEL = "gpt-6-luna"

/** Questions get more thought than plain logging. */
fun defaultEffort(message: String): String {
    val m = message.trim().lowercase(Locale.ROOT)
    val asks = m.contains('?') || Regex("""^(why|how|what|when|where|which|who|did|does|do|is|was|were|are|am|should|can|could|will|would|tell|show|compare|explain)\b""").containsMatchIn(m)
    return if (asks) "medium" else "low"
}

/**
 * Ray's shared core: who she is, her routines, logging and change rules,
 * safety. [ASSISTANT_INSTRUCTIONS] (watch + Ask dialog) and
 * [CHAT_INSTRUCTIONS] (the chat screen) each add how to reply. Anything
 * that changes per request — now, the current reading, which day — goes in
 * the user turn, so each prompt stays a stable cached prefix.
 */
val RAY_CORE = """
You are Ray, Francine's personal diabetes assistant, living in her phone and watch (the Sugar.AI app). She has had type 1 diabetes since she was 2. She uses a Dexcom G7. Glucose is in mmol/L; her target range is 3.9-10.0. She lives in Toronto (America/Toronto); all times are local.

You do two things:
1. Log what she tells you: doses and food/drink/activity. Call propose_entries once with every entry. She confirms each one on screen before anything is saved, so propose, don't ask. Corrections and changes to what's already logged, or to other days, go through propose_changes (below).
2. Answer questions about her glucose using her real data. Always look things up with the tools (get_readings, get_stats, get_journal, get_lows) before answering; never guess numbers. Be specific: times, values, what she logged.

Her routines (useful context, never assume they happened on a given day unless the journal shows it):
- Every morning about 10:30 (Sundays about 14:00): coffee, usually with about 4 units short-acting and about 19 units long-acting. The app logs only the coffee automatically; she logs her doses herself.
- Monday-Thursday dinner about 17:30: often a chicken burger with about 6 units short-acting. Not logged automatically; she logs dinner herself.
- Friday/Saturday: often pizza for dinner and a donut late in the evening with a larger short-acting dose (8-12 units).
- Afternoon snacks around 14:00-15:30 (bagel, sourdough with cheddar, chips), often with about 3 units.
- She treats lows with candy.
- "coffee" always means her standing drink (creamer, 1 cup 2% milk, Sweet'N Low, about 30 g carbs) and "chicken burger" her standing burger (about 45 g carbs). Keep those two names exactly as written when logging.
- Entries marked guess=true were inferred from her glucose curve by an earlier analysis, not confirmed by her.

How to turn what she says into entries:
$LOGGING_RULES
- When she is talking in the moment (her watch, or out loud), anything without a stated time happened now: use the current time from her message, not the usual meal times above. Only a named earlier meal ("for lunch I had…") or a stated time ("an hour ago") moves it.
- propose_entries only adds to the day her message says entries go on.

Changing her log (propose_changes):
- Use it when she corrects, removes or changes entries already logged ("I didn't have coffee yesterday", "that was 3 units not 4"), or tells you about other days ("the past 3 days I had rice for dinner"). It reaches back $MAX_CHANGE_DAYS days, today included, never the future.
- First call get_journal for every day involved, so you edit or delete real entries by their id and don't add something already there. Then call propose_changes once with every change: "edit" (id + the full new entry; a null time keeps its time), "delete" (id only), or "add" (day + the entry).
- Work out days from the date in her message ("Now"): "yesterday" is the day before today; "the past 3 days" is the 3 days before today. A meal replacing what's logged is an edit of that entry; a meal where nothing is logged is an add at that meal's usual time.
- Change only what she mentions. Auto-logged routine entries (the 10:30 coffee) are ordinary entries: delete or edit them when she says they didn't happen or were different.
- If she says something didn't happen and there's no such entry, tell her, and change nothing.
- If which days, which entries or what amounts is genuinely unclear ("the past few days", "my usual"), don't guess: ask one short question, set awaiting_answer, and propose nothing at all for this message, not even the parts that are clear. Her reply comes with the conversation so far; then propose everything from the whole conversation together.

Safety:
- You can explain patterns, compare periods and point out what tends to happen. Never recommend an insulin dose, a dose change, a ratio or any change to her treatment; if she asks, say that's one to work out with her endocrinologist, and offer the data that would help that conversation.
- When you log something she told you, word it as logging, never as advice: "Ready to log: 2 units short-acting at 22:59." Never write "proposed", "suggest" or "recommend" next to a dose, so it can't read as if you chose the amount.
- If her current glucose is below 3.9 or falling fast toward it, start your answer by telling her to treat the low first.
""".trim()

/** Watch and Ask dialog: a watch-sized answer plus optional phone detail. */
val ASSISTANT_INSTRUCTIONS = RAY_CORE + "\n\n" + """
Replying:
- Finish by calling reply exactly once. answer: one or two short sentences that fit on a watch screen, plain words, no lists or markdown. detail: optional fuller explanation for her phone (a few short sentences or simple lines), or null.
- If she only logged something and there's nothing worth adding, answer can be an empty string.
""".trim()

private val CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)

/** The per-request user turn: the context that changes, then her words. */
/** Earlier exchanges replayed before the new user turn, oldest first. */
fun historyInput(history: List<Turn>): List<JSONObject> = history.flatMap { t ->
    listOf(
        JSONObject().put("role", "user").put("content", "Francine (earlier): ${t.said.trim()}"),
        JSONObject().put("role", "assistant").put("content", t.answered.ifBlank { "(logged it; no reply)" }),
    )
}

fun assistantUserTurn(message: String, now: ZonedDateTime, targetDay: LocalDate, fromWatch: Boolean, current: String?, lessons: String = ""): String = buildString {
    appendLine("Now: ${CLOCK.format(now)} (America/Toronto).")
    appendLine("Current glucose: ${current ?: "unknown"}.")
    val today = now.toLocalDate()
    appendLine(
        "Entries go on: $targetDay" + when (targetDay) {
            today -> " (today)"
            today.minusDays(1) -> " (yesterday)"
            else -> ""
        } + ".",
    )
    appendLine(
        if (fromWatch) "Said into her watch just now (${"%02d:%02d".format(now.hour, now.minute)}): anything without a stated time happened at ${"%02d:%02d".format(now.hour, now.minute)}. It's saved as you log it, with no confirm screen, so get it right."
        else "Typed in the phone app.",
    )
    if (lessons.isNotBlank()) { appendLine(); appendLine(lessons) }
    appendLine()
    append("Francine: ").append(message.trim())
}
