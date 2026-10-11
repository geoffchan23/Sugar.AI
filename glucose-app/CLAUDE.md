# Sugar.AI (formerly Glucose Widget) — notes for Claude Code

Visible name **Sugar.AI** (phone app, watch app, tile) since 2026-10-10; the
package id stays `com.geoffchan.glucosewidget` (changing it would make a new
app and lose data, Dexcom login, watch pairing and sync). The GitHub repo is
`geoffchan23/Sugar.AI` (was `cgm-tools`; the local folder kept its name). The
assistant is called **Ray**.

Personal Android app on Geoff's phone (reports as "Pixel 9 Pro"). Widget
shows the current Dexcom G7 reading; the app accumulates history and a
day-level journal. Sideloaded debug build only — never publish, never
add accounts or telemetry. Wearer's Dexcom Share login is a PHONE NUMBER
(+1416…), not an email.

## Look and layout (from 2026-10-10)

Branding comes from the app icon (a sugar cube with Ray's sparkle and a
chibi cat-mouth face): indigo `#4F46E5` (was orange `#FF7A45` until Geoff
switched it the same day), sparkle cream `#FFF1B8`, the face's cocoa
`#3A2318`, cool indigo-tinted neutrals; Nunito (variable, `res/font/nunito.ttf`); light/dark follow
the phone. All in `SugarTheme.kt` (`Brand`, `LocalSugar` chart/status
colours, `RayAvatar`, `SheetDialog` = AlertDialog's slots as a bottom
sheet). Glucose status colours stay separate from the brand (low red,
high amber, green target band). One activity with three tabs (MainActivity):
**Today** (`TodayScreen.kt`: Now card with the latest reading — red when
low, amber when high — last dose/meal, the chart card with Day/Week and
time in range, swipe the chart to change day; the log as a timeline: tap
to edit, swipe left to reveal Delete (tap it; Undo after); the Ask Ray bar with a mic and
+ Dose / + Food chips), **Ray** (chat, threads as chips) and **Reports**.
Settings is the gear on Today. `ChatActivity` / `ReportsActivity` are
trampolines kept for notifications. Widget (deep indigo, no logo — no room) and watch use the same palette.

## Exploring the data (no export feature — by design)

```bash
adb connect <phone-ip:port>   # port from Wireless debugging screen; rotates
tools/pull-db.sh              # → ./data/glucose.db (full snapshot = backup)
sqlite3 data/glucose.db
```

Schema:
- `readings(timestampMs INTEGER PK, mgdl INTEGER, trend TEXT)` — one row
  per CGM reading (~5 min cadence), epoch ms UTC, deduped, immutable.
  mmol/L = mgdl / 18.0182. Trend strings: Flat, FortyFiveUp/Down,
  SingleUp/Down, DoubleUp/Down, None, NotComputable, RateOutOfRange.
- `journal(id INTEGER PK, day TEXT 'YYYY-MM-DD', text TEXT,
  createdAtMs INTEGER, updatedAtMs INTEGER, scope TEXT 'day'|'week')` —
  free-text notes, many per key, no intra-day time (LLM infers times
  from text). For scope='day', `day` is the local date; for
  scope='week', `day` is the MONDAY of the ISO week (weeks run Mon–Sun).
  All local dates are the phone's timezone (America/Toronto).

Day-scope entries are structured logs (since 2026-09-05); week-scope
entries are free-text summaries. Conventions inside `journal.text`:
- **Doses**: `dose: <type> <units>u @ HH:mm` where type is
  `short-acting` or `long-acting` and units is 1–100. Quick-entry
  dialog; logged to the day being viewed (today in week mode), e.g.
  `dose: short-acting 4u @ 13:05`.
  (Entries logged before 2026-09-02 may use the older
  `dose: <name> <amount> @ HH:mm` free-name form.)
- **Food/exercise logs**: `event: <text> @ HH:mm`, e.g.
  `event: lunch @ 12:40`, `event: 30 min walk @ 18:00`. Logged from the
  "Log food/exercise" dialog to the day being viewed. Plotted as teal
  diamonds on the chart; shown in the notes list as "12:40 PM · lunch".
- **Guesses** (Claude's inferences from the curve): the normal dose or
  event text plus the literal suffix ` (guess)`, e.g.
  `dose: short-acting 6u @ 17:30 (guess)`, `event: pizza @ 18:00 (guess)`.
  Both parsers accept it (`isGuess`); the chart draws them dashed and
  dimmer with a trailing `?` ("6u?", "pizza?"), the notes list labels
  them "guess" with a **Keep** button that strips the suffix, and editing
  one in the dose/log dialog saves it confirmed. The widget ignores
  guesses; routines treat them as covering (no double-logging). Insert
  them with the receiver like any row.
- **Ask / log by text** (the "Ask Ray or log…" bar on Today, or its mic; was "Describe in words"): a question or
  paragraph goes to the OpenAI assistant when it's set up and online (see
  "Assistant" below), else to on-device Gemini Nano (ML Kit GenAI Prompt
  API, AICore). Nano answers JSON; `TextEntry.kt` validates strictly
  (`parseBreakdown`) and the confirm list shows each row with editable
  time/units/name. Rows matching an entry already logged (same dose type
  and units, or event name, within ±60 min, `matchExisting`) start
  unchecked as "already logged"; a row matching a guess or an untouched
  auto-logged routine row (any units) starts checked and saving updates
  that row to the stated values (so "23 long" after the routine's 19u
  replaces it instead of being dropped — happened 2026-10-10). Saves to the day being
  viewed (today in week mode). Inference only runs with the app in the
  foreground (AICore error 30 otherwise); the model downloads on first
  use. Debug: `--es op describe-test --es text "'…'"` logs status, raw
  output and parsed rows to logcat tag `Describe` (read-only).
- **Legacy day tags**: an entry whose whole text is `#sick` etc. Hidden
  from the list; treat as boolean day flags. Chips UI removed 2026-09-02.
- Times are stored 24-hour; the UI shows 12-hour with AM/PM.

There are no free-text day notes left: the 2026-09-01 … 09-05 notes were
parsed into `event:` rows and then deleted (originals preserved in
`analysis/parse-ledger.json`). No further parsing is expected. To insert
or delete a single row, use the ADB receiver — NOT the SQLite file (WAL):

```bash
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op insert --es day 2026-09-01 --es text "'event: coffee @ 10:30'"
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op delete --es day x --el id 42                # one row, by journal.id
# op=refresh also exists (manual data refresh + widget redraw)
# op=report-ready --es name <file> posts the "report ready" notification
#   (tools/push-report.sh sends it; needs POST_NOTIFICATIONS, granted via
#   `pm grant` on 2026-09-06 and requested on launch as a fallback)
```

There is deliberately no bulk-delete op: `event:` rows are user data now.
Gotchas: add `</dev/null` when broadcasting inside a shell loop (adb
eats stdin); the receiver is DUMP-guarded because Android skips shell
broadcasts to non-exported receivers. With several ADB devices attached
(phone, watch), pass the serial: `tools/pull-db.sh <ip:port>`.

## Auto-logged routines

Every day at 10:30 (Sundays 14:00, since 2026-10-07) the app logs
`event: coffee @ 10:30`. Until 2026-10-10 it also logged
`dose: short-acting 4u @ 10:30` and `dose: long-acting 19u @ 10:30`;
Geoff dropped those (she logs her own doses — the auto 19u hid her real
23u that day). `routineRows(day)` / `gaps.py` still recognise those dose
rows as routine up to that date. `MorningRoutine.ensure()` runs inside the
5-minute refresh worker: first run after 10:30 local inserts whatever is
missing and stamps `routineLoggedDay` in DataStore. A hand-logged dose of
the same type or an event named coffee that day suppresses its routine
twin (any time/units), so a 10:25 hand entry doesn't get a 10:30 double.
Wrong-day entries are just deleted in the app like any other row.

Only entries before `ROUTINE_SPLIT_MINUTE` (15:00) can suppress it, so an
afternoon snack dose doesn't. A Mon-Thu **evening routine** (17:30
`short-acting 6u` + `chicken burger`) ran 2026-09-17 … 10-08 and was
dropped by Geoff: dinner is logged by hand/voice. `gaps.py` still treats
those exact rows in that range as routine.

## Two phones: sync (from 2026-10)

Her Pixel 9 Pro is the **main** phone (runs the morning routine, takes
watch entries); Geoff's is a **second** phone (`isMainPhone=false`, never
auto-logs). Both read Dexcom Share themselves, and both can add/edit/delete
journal rows: changes sync both ways, end-to-end encrypted, through an
ntfy.sh topic (no account). The relay only sees base64(nonce ‖ AES-256-GCM).

- Every journal write goes through `Journal` (dialogs, routines, receiver),
  which queues the row's `uid` in an outbox; `SyncWorker` publishes each
  uid's current row or tombstone (chunked under ntfy's 4 KB limit) and
  polls the topic. Polls also run on every 5-min refresh and app open.
- Merge (`SyncCore.kt`, unit-tested): by `uid`, last write wins on
  `updatedAtMs`, ties keep local; a delete beats any edit older than it.
  Remote rows are written straight to Room so they aren't re-published.
- ntfy.sh keeps messages ~12 h, so once a day each phone re-sends what
  changed in the last **7 days**. A phone offline longer than that needs
  Settings → "Resend 30 days" (or `op sync-resend`) on the other phone.
- `journal.id` is per phone; use ids from a pull of the phone you target.
  Because edits sync, `pull-db.sh`, the IngestReceiver and the fill-gaps
  push can target either phone — Geoff's is usually the one on ADB.
- Settings shows sync status (role, last check/send, waiting, last error).

Configure both phones with the same key and topic (keep them out of git):

```bash
KEY=$(python3 -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")
TOPIC=cgm-$(python3 -c "import secrets;print(secrets.token_hex(16))")
R="shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver"
adb -s <her-phone>   $R --es op sync-setup --es key "$KEY" --es topic "$TOPIC" --ez main true
adb -s <geoff-phone> $R --es op sync-setup --es key "$KEY" --es topic "$TOPIC" --ez main false
# also: --es op sync-now | sync-resend | sync-reset   (logcat tag "Sync")
```

## Widget

Three rows: reading + trend arrow with its age on the right; last dose
(▲ short / △ long, units) with its clock time ("10:30 AM"); last food/exercise log
likewise. Dose/log rows come straight from Room (last 3 days) at render
time; the app calls `GlucoseWidget().updateAll()` after every journal
save/delete, and the 5-min refresh keeps the reading age current.

## Assistant — Ray (OpenAI, from 2026-10-08)

Francine's CGM assistant, **Ray** (`Assistant.kt`, `AssistantTools.kt`): OpenAI's
Responses API with function tools over her own data. It logs what she says
(as rows she confirms — the model never writes) and answers questions
("why did I go low last night?", "this week vs last?"). Model is a setting,
default **`gpt-6-luna`** ($0.10 in / $0.01 cached / $0.50 out per 1M
tokens; `gpt-6.1-sol` at $2/$0.10/$10 is the step up). Effort: `low` for
logging, `medium` for questions (`defaultEffort`), or a fixed override.
The old "no cloud / never an LLM API" rule is gone (Geoff, 2026-10-08).

- Raw HTTPS to `/v1/responses` with okhttp + org.json, not openai-java:
  the SDK brings Jackson and a newer Kotlin stdlib, and the project is
  pinned to Kotlin 2.1.21 for ML Kit genai-prompt.
- `store: false`; every output item (encrypted reasoning included) is
  replayed each round; ≤ 6 rounds; the final answer comes from the `reply`
  tool (`answer` = watch-sized, `detail` = phone), or plain text if the
  model skips it.
- Tools: `get_readings` (≤150 points), `get_stats` (report.py metrics),
  `get_journal` (ids, guesses flagged), `get_lows` (each low + 3 h before),
  `propose_entries`, `propose_changes`, `reply`. Instructions (`ASSISTANT_INSTRUCTIONS`) are a
  stable, cacheable prefix built on the shared `LOGGING_RULES`; now, the
  current reading and the target day go in the user turn.
- **Broad edits** (`Changes.kt`, from 2026-10-08): `propose_changes` edits
  or deletes existing rows (by the Room id get_journal showed) and adds rows
  on any of the last 14 days (`MAX_CHANGE_DAYS`, never the future). Strictly
  validated (`parseChanges`; rejects go back to the model with reasons),
  carried by uid, and applied by `Journal.applyChanges`, which skips a row
  edited/deleted since it was proposed (`planChange`). Phone: the change
  list sits under the new rows in the Ask dialog, grouped by day, all ticked.
  Watch: today's rows are confirmed on the watch as before; other changes
  post a "N changes to review" notification that opens `ChangesDialog`.
- **Replies**: when something's unclear the model asks one question
  (`reply.awaiting_answer`, proposing nothing). Reply (phone button / watch
  chip; `/log/parse` `replyTo`) sends her answer with up to 4 earlier
  exchanges rebuilt from the interaction log (`historyAfter`); the asking
  record's outcome becomes `replied`. ADB: `ask-test … --es replyTo <id>`
  (the id is logged). For real conversations use the chat (below).
- Safety: explains patterns, never recommends doses or treatment changes
  (refers to her endo); tells her to treat a low first.
- **What goes to OpenAI:** her message, the current reading, and whatever
  the tools return for that question (readings, stats, logs). Nothing else.
- Logcat tag `Assistant`: model, effort, latency, tokens, estimated cost.
- Setup over ADB — the key travels as a file, never an intent extra:
  ```bash
  P=com.geoffchan.glucosewidget
  grep OPENAI_API_KEY ~/.config/cgm-tools/openai.env | adb -s <serial> exec-in run-as $P sh -c 'cat > files/ai-handoff'
  adb -s <serial> shell am broadcast -n $P/.IngestReceiver --es op ai-setup [--es model gpt-6-luna] [--es effort low|medium|auto]
  adb -s <serial> shell am broadcast -n $P/.IngestReceiver --es op ask-test --es text "'how was this week?'"   # saves nothing
  adb -s <serial> shell am broadcast -n $P/.IngestReceiver --es op ai-clear
  ```
  Settings shows the model (never the key). Unset or offline → Nano/rules.

### Chat with Ray (from 2026-10-10)

The **Ray** tab (`RayScreen` in ChatActivity.kt; `ChatActivity` itself now only forwards notifications to it): threads shared
by both phones, Francine and Geoff both writing, Ray answering. Settings
says which person this phone is (`Store.chatAuthor`; default Francine on
the main phone, Geoff on the other; ADB `op chat-author --es author geoff`).

- Same loop (`Assistant.run`) with `CHAT_INSTRUCTIONS` (= `RAY_CORE` + chat
  rules; `ASSISTANT_INSTRUCTIONS` is `RAY_CORE` + watch-sized replies), the
  thread replayed as input (`chatPrior`: people as "Geoff: …" user turns,
  Ray's turns with what became of his cards; newest 24k chars), ≤ 14
  rounds, 150 s, effort medium, plus three chat-only tools (`RayTools.kt`,
  run by `RayEngine.kt`):
  - `query_data` — one SELECT on a **read-only** connection to glucose.db
    (`checkQuery` rejects anything else first; ≤ 200 rows).
  - `run_analysis` — JavaScript Ray writes, run in androidx.javascriptengine's
    isolated sandbox (separate process, no network/files), ≤ 180 days of
    data preloaded (`analysisScript`: `days`, `readings{t,v,d,m}`,
    `journal`), 20 s, 256 MB. Errors go back so he fixes and re-runs.
  - `save_report` — self-contained HTML into files/reports/
    (`ray-<date>-<HHmm>-<slug>.html`), listed in Reports, opened with
    JavaScript off. Reports stay on the phone that made them.
- Proposals become a **card** on Ray's message (`ChatCard`: entries for
  today + changes), all ticked; Save writes through `planWatchSave` /
  `Journal.applyChanges` (`ChatStore.saveCard`), Dismiss drops it; the
  card's state syncs so both phones see who saved it.
- Messages (`chat_messages`, Room v5) sync through the same relay ("chat"
  in the payload, outbox `c:<uid>`), trimmed to fit one relay message
  (`trimChatForSync`: tool code, then meta, then text — the full text stays
  on the phone that wrote it). Incoming messages from the other person or
  their Ray post a notification (`ChatNotification`) unless that thread is open.
- Each chat turn is also an interaction-log record (source `chat`, with
  `data.chat.{thread,author}`), outcome from the card.
- A message's footer lists what Ray looked at; tap to see the SQL/code.
- ADB: `--es op chat-test --es author geoff --es text "…" [--es thread <id>]`
  posts a real message (it syncs) and logs Ray's reply under tag `ChatTest`.
- Live check 2026-10-10 (thread `raytest1`, as Geoff): SQL count +
  30-day comparison ($0.0005), a JS analysis he debugged himself ($0.0013),
  and a lows report for her endo ($0.0019).
- Both phones have the key and the chat (2026-10-10); sync verified both
  ways. A phone upgraded after messages were sent misses them (the old app
  skipped "chat"): run `op sync-resend` on the phone that has them.
- Not built yet (Geoff chose to wait): Ray asking Claude Code for work on
  the Mac.

## Interaction log (for evals and skills, from 2026-10-08)

Every parse/ask is recorded in Room table `assistant_log` (v4), whatever
handled it and wherever it came from — the point is to build skills and
evals for the assistant from what Francine actually says and does.
`source`: `watch`, `watch-queued` (said while the phone was away, saved as
guesses), `phone` (Ask / log dialog), `debug` (ADB `ask-test`,
`watch-parse-test`, `describe-test`). Pure logic in `InteractionCore.kt`,
I/O in `InteractionLog.kt`; logging never blocks or breaks an entry.

- `input`: exactly what she said or typed. `parser`: what produced the rows
  (openai / nano / nano-screen / rules / none).
- `data` (JSON): `context` (local time, the day entries went on, current
  reading, the full user turn sent to OpenAI), `attempts` (each parser
  tried, ok, reason — e.g. `openai timeout after 24000 ms` → rules, ms),
  `model`/`effort`/`ms`/`tokens` (input, cached, output, reasoning)/`costUsd`,
  `trace` (per round: usage, every tool call with args and result — results
  over 20 KB cut with a marker — and any text), `answer`/`detail`,
  `proposals` (text + status new/already/confirm), `rejected`, `error`.
- `outcome` (JSON, the eval label): `saved` (each row: as proposed, as
  saved — differs if she edited it on the phone — journal uid, op
  insert/confirm/already/guess), `unticked`, or `cancelled` / `ask-again` /
  `answered` / `timeout` (the watch gave up; its queued resend is linked by
  `parseId`) / `saved-as-guesses`. A save is never overwritten by a later
  cancel. No outcome = she swiped away (or hasn't acted yet).
- The watch generates the id and sends it with /log/parse; proposal, save
  and /log/outcome carry it back (older watches/phones without ids still work).
- **Corrections** (from 2026-10-11, the label now that the watch doesn't
  confirm): every edit or delete through `Journal` of a row some record
  saved appends `{journalUid, op edit/delete/confirm, before, after, by,
  atMs}` to that record's `outcome.corrections` (`InteractionLog.correction`;
  `by` = `Store.chatAuthor`, so Francine on hers, Geoff on his). Undo on the
  watch is a delete too. Merged from both phones (`mergeOutcome` keeps both
  sides'). Adds that fill a gap (something she said but the parse missed)
  aren't linked yet.
- **Memory:** the most recent 15 edit/delete corrections go into every
  logging prompt's user turn as "Past corrections …" (`correctionLessons`),
  so the model sees what it got wrong before.
- **Evals:** the export also writes `data/watch-evals.jsonl` (input, got,
  expected = the log as it is now, corrected flag); `tools/run-watch-evals.py
  --serial <phone>` replays the corrected cases (`--all` for every case)
  through `watch-parse-test` and scores rows ignoring times. Use it after
  changing the prompt or model. Turning recurring corrections into rules
  in `LOGGING_RULES` / `RAY_CORE` is the "skill" step — do it in a session
  from the export.
- The export also resolves each saved row against the journal (kept /
  edited / confirmed guess / deleted) for records from before corrections.

Sync: records ride the encrypted relay (outbox entries `i:<uid>`, payload
field `inter`). The full trace stays on the phone where it happened; the
other phone gets a copy trimmed to fit one relay message (tool results,
then the trace, then long texts are dropped; input, proposals and outcome
kept) stored with `full=0`, and only outcome changes move after that. So
pulling from Geoff's phone gives every interaction with outcomes; pull from
her phone for full traces of what happened there.

```bash
tools/pull-db.sh <serial>
python3 tools/export-assistant-log.py          # → data/assistant-log.jsonl + summary
python3 tools/export-assistant-log.py --include-debug
```
Privacy: this is her words and health data. It stays on the two phones, in
the encrypted relay, and in gitignored `data/` — never commit an export or
paste one into an issue. Settings shows a count only.

## Watch (voice log from her Pixel Watch 2)

`wear/` is a Wear OS module: launcher app **Glucose Log** plus a tile
with one big mic button — and, the way she normally gets in, **tapping
the time on her Big Glucose watch face** (a WFF `<Launch>` on the clock;
see watch-face/CLAUDE.md). She speaks ("took 6 units and a chicken
burger"); the watch sends the transcript to her phone (the main phone)
over the Wearable Data Layer; **the phone saves the rows to today via
`Journal` straight away** and replies; the watch buzzes and shows ✓ with
what went in, plus **Undo** (`/log/undo`: removes the rows that record
inserted) — no confirm screen since 2026-10-11 (Geoff: "assume it gets it
right, we correct after the fact"). A plain log closes itself after 5 s;
an answer waits for Done. The watch asks for this with `autoSave` on
/log/parse; an older watch without it still gets the tick-and-Save screen. Paths and JSON are in
`WatchProtocol.kt` (phone) and `wear/…/Protocol.kt` (watch) — change
both together. The wear APK must keep applicationId
`com.geoffchan.glucosewidget` and the same debug key as the phone app,
or the phone never hears it. Only a phone with `isMainPhone` answers.

Parsing (`WatchParse` in `WatchListenerService.kt`), in order:
0. **The OpenAI assistant** when set up and online (24 s budget): rows
   plus an `answer`; the watch shows the answer above the rows, or alone
   (Done / Ask again) for a pure question. If it fails, straight to rules
   (no time left for Nano's route).
1. **Gemini Nano in-process** (4 s) — works only if the app happens to be
   on screen; otherwise AICore answers error 30 (BACKGROUND_USE_BLOCKED:
   inference is for the top foreground app only, foreground services
   included).
2. **Nano over the lock screen**: a full-screen-intent notification
   (silent channel "Watch voice log", CATEGORY_CALL) opens the invisible
   `NanoParseActivity` (showWhenLocked + turnScreenOn), which runs the
   prompt and hands the answer back through `NanoBridge`. Her screen
   lights for a few seconds and **stays locked**. Proven on a Pixel 9 Pro
   (Android 17): locked → ~4–7 s. A plain background `startActivity` is
   BAL-blocked even with SYSTEM_ALERT_WINDOW, hence the notification.
   If she's using the phone, the FSI shows as a heads-up instead; after
   12 s it falls back to rules (tapping the heads-up runs Nano).
   **Her phone needs, once:**
   `adb shell appops set com.geoffchan.glucosewidget USE_FULL_SCREEN_INTENT allow`
   (Android 14+ doesn't grant it to non-call apps) and the AICore model
   downloaded (send something from the Ask bar once, or `op describe-test`).
3. **`parseSpoken`** (`SpokenEntry.kt`, deterministic): number words,
   "19 and 4" (19/20 → long), insulin brand/slang words, "at 5:30" /
   "an hour ago" / "this morning", "walked 20 minutes" → `20 min walk`.
   Good for short in-the-moment phrases; weak on paragraphs.
Anything without a time is stamped now. Status per row: new,
"already logged" (saving is a no-op), "confirms guess" or "replaces
auto-log" (both update the matched row).
Logcat tag `WatchLog` shows which parser ran (`openai`, `nano`,
`nano-screen`, `rules`), the latency and the rows. The watch waits up to
30 s ("Thinking…"). `watch-parse-test --ez any true` runs it on the peer.

Test without a watch (same code path, read-only):
`adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver --es op watch-parse-test --es text "'took 6 and a chicken burger'"`

**Phone out of reach:** the watch keeps the transcript (`WatchQueue`,
SharedPreferences) and says "Saved on your watch — I'll send it when your
phone is back." It's sent on the next app open and by a WorkManager job
retrying every few minutes (`QueueFlushWorker`), on `/log/queued`. The
phone parses it with rules (no one can confirm it), times relative to
when she spoke, and saves the rows as **guesses** to that day — she or
Geoff confirms with Keep. Idempotent by id (`Store.watchQueuedIds`, last
50), so a resend after a lost ack never double-logs.

```bash
adb -s <watch-ip:port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

The tile is optional: swipe to the end of the tiles → **+ Add tile** →
**Glucose log**. (Watch ADB: Settings → Developer options → Wireless
debugging → pair once; it drops when the watch sleeps off-charger.)

## Nutrition & activity estimates (analysis/nutrition/)

Per-day JSON (`analysis/nutrition/<day>.json`) with each logged event
broken into items (grams, kcal, carb, protein, fat, fibre, sugar) and
activities (MET, minutes, kcal). Claude in a session does the parsing
via the `nutrition` skill;
`analysis/nutrition.py` does search/lookup/arithmetic. Sources, in order:
`nutrition/cnf.json` (Canadian Nutrient File, offline, primary),
Open Food Facts (`search --off`, brands; flaky 503s), USDA FoodData
Central (`search --usda`, DEMO_KEY ≈10 req/h). Non-CNF hits are cached in
`nutrition/food-cache.json`. Activities use `nutrition/met.json` (2024
Compendium) × `config.json` weightKg (65.8 kg ≈ 145 lb). Standing recipes
in `nutrition/recipes.json` — `coffee` is always creamer + 1 cup 2% milk +
3 Sweet'N Low + Pike Place (~30 g carbs), never black; `chicken burger` is
always GV bun + Janes patty + 2 tbsp Chick-fil-A sauce + 20 g cheddar. All committed;
they are part of the dataset. `report.py` shows carbs on meal markers, a
per-day line and weekly averages, labelled as estimates; unparsed days say
so. `weekly-report.sh` prints which days are unparsed; the weekly-report
skill fills them in.

## Weekly report

`analysis/report.py` turns `data/glucose.db` into a self-contained HTML
report (consensus metrics, 24 h overlay, day strips, every low with its
preceding 3 h, observations for the endo). `tools/weekly-report.sh` runs
the whole cycle: find phone via mDNS → pull DB → generate → push into the
app's **Reports** screen (`files/reports/report-<first>_<last>.html`,
the Reports tab, WebView; a notification is posted when a new
report arrives and opens it directly). The `weekly-report` skill (repo
`.claude/skills/`) wraps that plus republishing the artifact
(`https://claude.ai/code/artifact/42522e03-0809-4e84-b7cb-7ff7eebf974a`).
A launchd job (`tools/install-weekly-job.sh`, Sundays 22:00) runs the
shell pipeline only — not Claude: under launchd `claude` (and Homebrew
python3) hang on macOS Desktop-folder protection (TCC) while the repo
lives under `~/Desktop`; Apple's `/usr/bin/python3`, bash, adb, sqlite3
are exempt (verified 2026-09-06; a repo outside Desktop avoids all of
it). Log:
`data/weekly-report.log`. The generated report is gitignored (health data).

## Build & deploy

Kotlin 2.1.21 + KSP1 (`ksp.useKSP2=false`: Room 2.6.1 breaks under KSP2).
ML Kit `genai-prompt` is pinned at 1.0.0-beta2 — beta3+ pulls the Kotlin
2.3 stdlib; beta2's 2.2 metadata needs Kotlin ≥ 2.1. `maxOutputTokens` is
capped at 256 by the API.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Data source: unofficial Dexcom Share API, `shareous1.dexcom.com` (Canada).
Each 5-min poll (exact alarm → worker) fetches the trailing 24 h and
upserts, so gaps under 24 h self-heal; longer gaps are lost (accepted —
official API deliberately not used). Widget reads a DataStore cache;
history lives in Room. `docs/superpowers/specs/` has the full design
history including decisions and their reversals.
