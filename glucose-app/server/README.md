# Ray server (cloud Ray)

One Ray for everything. The server holds the master copy of Francine's data (the same tables as the phones' Room
database) and answers for the phones, the watch (through her phone), Alexa and the "Hey Google" shortcuts. It runs on
its own Oracle Always Free VM at `https://ray.geoffreychan.com`. CLAUDE.md ("Ray server") has the operations notes.

## API

Everything is under `/v1`. Only `/health` and `/v1/*` are public; `/status` is loopback only.

| Endpoint | Who | What |
|---|---|---|
| `POST /v1/sync` | phones (device token) | Send the outbox; get what other devices changed since the phone's cursor (paged, 300 at a time). The first sync, with no cursor, returns the last 30 days. |
| `POST /v1/ray/run` | phones | One Ray exchange on the server's data. `mode` is `log` (watch/Ask) or `chat` (full toolset). The phone builds the turn, then records the result and posts the chat message as before. |
| `POST /v1/voice` | phones (the shortcuts) | `{"text"}` → `{"speech"}`. Fixed questions come straight from the data; anything else goes to Ray in voice mode. Spoken logs are saved at once and read back. |
| `POST /v1/alexa` | Alexa | The private skill. It checks Amazon's signature (certificate chain, SHA-256, timestamp, skill id). |

**Devices.** A device token is `Authorization: Bearer <token>`. The server only stores SHA-256 hashes, in
`DEVICE_TOKENS`. The tokens are in `~/.config/cgm-tools/ray-devices.env` on the Mac and reach a phone with
`op ray-setup` (see IngestReceiver).

**The change log.** `changes(seq, kind, uid, origin)` gets a row for every write the server applies. A phone's cursor is
a `seq`, and it never gets its own changes back. Merging uses the same rules as before (`applyPayload`, core
`SyncMerge.kt`), so syncing twice is harmless.

**Ray's chat tools on the server:**
- `query_data` runs on a read-only SQLite connection.
- `run_analysis` runs in Deno with no `--allow` flags (no files, network or environment), with 256 MB and 20 s limits.
- `save_report` hands the HTML back to the phone that asked, which keeps it under Reports.

## Alexa skill (one-time, in Geoff's Amazon developer account)

A skill in development only works on its developer's own Amazon account, so it stays private.

1. Go to https://developer.amazon.com/alexa/console/ask and choose **Create Skill**.
   - Name: Ray. Locale: English (CA), plus English (US) if your Echo uses it.
   - Type: **Other → Custom → Provision your own**. Template: **Start from scratch**.
2. **Interaction model → JSON Editor**: paste `alexa/skill-package/interactionModels/custom/en-CA.json` (and en-US for
   that locale). Click **Save**, then **Build skill**.
   - If the build rejects the one-word invocation name "ray", use "sugar ray" in both files and here.
3. **Endpoint**:
   - Choose **HTTPS**. Default region: `https://ray.geoffreychan.com/v1/alexa`.
   - Certificate: *"My development endpoint has a certificate from a trusted certificate authority"*. Save.
4. Copy the **Skill ID** (`amzn1.ask.skill.…`) into `~/.config/cgm-tools/alexa.env` as `ALEXA_SKILL_ID=…`, then run:
   `server/deploy/put-secrets.sh && server/deploy/deploy.sh`
5. **Test** tab: set "Skill testing is enabled in" to **Development**, then type "open ray".
6. In the Alexa app, add routines so no "ask Ray" is needed:
   - When you say "what's Francine's blood sugar", the action is **Customized**: "open Ray".
   - When you say "when did Francine last take insulin", the action is **Customized**: "ask Ray when she last took
     insulin".

**Saying it:**
- "Alexa, open Ray" gives her number.
- "Alexa, ask Ray when she last took insulin".
- "Alexa, ask Ray how was her night".
- "Alexa, tell Ray she had 4 units of short acting" logs it and reads back exactly what was saved.

Alexa waits 8 seconds. Fixed questions answer in under a second. Others get about 6.5 s of Ray, then
"try the Ray chat".

## Ops

- `server/deploy/deploy.sh` builds, tests and ships to `/opt/ray`, installs Deno and the Caddyfile, and restarts.
- `server/deploy/put-secrets.sh` writes `/etc/ray/*.env` from the Mac. Set `RAY_FOLLOW_RELAY=1` only to follow the old
  ntfy relay again.
- Logs: `journalctl -u ray`. State: `curl -s localhost:8080/status` on the VM.
- Backups: nightly, encrypted with age, kept 60 days in the `ray-backups` bucket. CLAUDE.md has the restore command.
