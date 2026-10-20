# CLAUDE.md — handoff notes

State of the work as of 2026-08-31. The face is **built, installed, and live
on Geoff's wrist**, visually matching the stock face within a couple of pixels.

## The goal, in one sentence

Geoff wears a **Pixel Watch 2** (Wear OS 6, 384x384) and wants his stock
Pixel digital face kept exactly as-is, except the Dexcom glucose reading
rendered much bigger, with `mmol/L` and the timestamp dropped.

## What was learned on-device (supersedes earlier guesses)

- **The complication provider is NOT Dexcom.** It is the "Glucose Watch" app
  by Sagitta Software: `com.sagittasoftware.glucosewatch/…wear.GlucoseComplicationService`.
  The Dexcom watch app (`com.dexcom.g6.region7.mmol`) provides no complication
  at all. Verified by pulling the APK and reading its manifest.
- **SHORT_TEXT exists and includes the trend arrow**: renders as `10.9 →`.
  Units and timestamp gone, exactly as designed. Supported types:
  SHORT_TEXT, LONG_TEXT, RANGED_VALUE, SMALL_IMAGE, MONOCHROMATIC_IMAGE;
  updates every 300 s.
- **The photo-sampled blue was wrong.** Real colors from an ADB screenshot of
  the stock face: weekday `#aeb4ff`, clock `#a0a7ff`, month/day and glucose
  plain white. The date is two-tone (weekday periwinkle, rest white) and
  UPPERCASE (`MON AUG 31`) — both now replicated (uppercase via Condition
  lookup tables over [DAY_OF_WEEK] 1=Sun..7=Sat and [MONTH]).
- Screenshots capture at current screen brightness — a uniformly ~85%-dimmed
  capture means the screen was dimming, not a color bug. Tap a blank corner
  (`input tap 30 192`) right before `screencap`.

## Build/dev loop (all working)

- Toolchain: Homebrew OpenJDK 17 + android-commandlinetools (SDK 35) +
  platform-tools. No Android Studio. `local.properties` points at
  `/opt/homebrew/share/android-commandlinetools`.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`
- **Gradle cannot validate the WFF XML** (res/raw is opaque to aapt2). Always
  run Google's validator after editing:
  `java -jar wff-validator.jar 2 watchface/src/main/res/raw/watchface.xml`
  (jar from https://github.com/google/watchface/releases, tag `latest`; on
  this Mac: `~/.local/share/wff/wff-validator.jar`).
- Watch connects over Wi-Fi ADB: `adb pair` once, then
  `adb mdns services` to find the connect port, `adb connect IP:port`.
  The watch drops off Wi-Fi when it sleeps off-charger; re-pair is not
  needed, just re-connect.
- Install: `adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk`
- **Activate from the shell** (no wrist taps needed):
  `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId com.geoffchan.bigglucose`
- Iterate: screenshot with `adb shell screencap -p /sdcard/w.png && adb pull …`
  and compare against `scratch stock3.png`-style captures pixel-wise.

## Hard-won WFF gotchas (validator caught all of these)

- `--` is illegal inside XML comments.
- `<Font>` requires `family`; `SYNC_TO_DEVICE` uses the device font.
- `<ComplicationSlot>` requires a `<BoundingShape>` child (e.g. BoundingBox).
- `<Template>` requires at least one `<Parameter>`; static text goes directly
  inside `<Font>` (mixed content).
- The manifest must NOT declare a service (official sample has none), and
  **`res/xml/watch_face_info.xml` with a `<Preview>` is mandatory** — without
  it the face installs but WearServices logs
  "could not be parsed - Resource ID #0x0" and it never appears anywhere.

## Design decisions

- Watch Face Format v2, `hasCode="false"`, 450x450 canvas — unchanged.
- Slot renders SHORT_TEXT first (that's what strips units/timestamp);
  LONG_TEXT fallback retained.
- `DefaultProviderPolicy` pre-wires the Glucose Watch provider, so the face
  arrives with glucose already attached — no manual slot assignment.
- Date split: weekday right-aligned ending x=196, month/day left-aligned
  from x=211 (450-space), approximating the stock centered two-tone line.
- **Glucose number centering** (Geoff approved 2026-08-31): the arrow is baked
  into COMPLICATION.TEXT ("10.8 →"; TITLE is empty — probed on-device), so the
  slot is shifted right by half the constant arrow-tail width to center the
  number itself: slot x=106 y=255, with x=-61 compensation on the LONG_TEXT
  PartText so the fallback stays truly centered. If the provider ever sends
  arrowless text ("--" during warm-up), it sits ~50 right of center — known,
  accepted. Verified pixel-exact: number center 191.5/384, arrow fully clear
  of the round edge.
- **Tap the time → voice log** (2026-10-07): the clock `PartText` carries
  `<Launch target="com.geoffchan.glucosewidget/com.geoffchan.glucosewidget.wear.LogActivity"/>`,
  which opens Glucose Log (glucose-app's `:wear` module) straight into the
  mic. Nothing visual changed; the glucose complication keeps its own tap
  (Glucose Watch). If Glucose Log isn't installed, Wear offers the Play
  Store — install `wear-debug.apk` first. `Launch` is valid in any `Part*`
  (validator v1.7.0, format 2).

- **Last voice log under the glucose** (2026-10-20): slot 1 (x=75 y=370,
  300x42, periwinkle size 28) defaults to Sugar.AI's
  `com.geoffchan.glucosewidget/.wear.LogStatusComplicationService`: "✓ Logged 2"
  for 10 min after she logs by voice, "Held on watch" while the phone is away,
  empty otherwise. Install the wear APK first; if the slot comes up empty after
  updating the face, long-press → Customize → pick "Last voice log" for it.

## Remaining deltas / possible next steps

- **Font.** SYNC_TO_DEVICE renders Roboto-ish; the stock clock uses Google's
  rounded flex font. Matching exactly would need embedding a font in res/font.
  Geoff has not yet said whether he cares.
- **Ambient (AOD) rendering has not been checked.** No Variant/AMBIENT
  elements exist; worth screenshotting the AOD state.
- Sizes/positions were tuned pixel-wise against the stock screenshot
  (clock size=134, date y=41); further taste tweaks are one sed + rebuild away.
- Nothing is committed since the fixes; branch `claude/custom-wearos-watch-face-5a4az6`.

## Things not to do

- No Play Store publishing, no Dexcom API/network code, no PRs unless asked,
  no scope widening. Sideload via ADB is the distribution plan.
