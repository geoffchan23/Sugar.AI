#!/usr/bin/env python3
"""Replay the watch eval cases through the phone's real parser and score them.

Usage (from glucose-app/, after export-assistant-log.py):
  python3 tools/run-watch-evals.py --serial <adb serial> [--evals data/watch-evals.jsonl] [--all] [--limit N]

Each case's input goes through the ADB op watch-parse-test (the same code
path as the watch, read-only: nothing is saved, recorded as source "debug").
Rows are compared without times — what was logged, not when, since the
replay runs at a different time of day. Default: only the corrected cases
(the ones the parser got wrong before); --all runs every case as a
regression check. Costs a fraction of a cent per case with gpt-6-luna.

Prints each miss and a score. Output stays local (her words); stdlib only.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
P = "com.geoffchan.glucosewidget"


def timeless(text):
    """'dose: short-acting 2u @ 22:59' → 'dose: short-acting 2u'."""
    return re.sub(r"\s*@\s*\d{1,2}:\d{2}.*$", "", text.replace(" (guess)", "")).strip().lower()


def parse(serial, text):
    adb = ["adb", "-s", serial]
    subprocess.run(adb + ["logcat", "-c"], check=True)
    quoted = "'" + text.replace("'", "'\\''") + "'"
    subprocess.run(adb + ["shell", "am", "broadcast", "-n", P + "/.IngestReceiver", "--es", "op", "watch-parse-test",
                          "--ez", "any", "true", "--es", "text", '"%s"' % quoted.replace('"', '\\"')],
                   check=True, capture_output=True)
    for _ in range(45):
        time.sleep(1)
        log = subprocess.run(adb + ["logcat", "-d", "-s", "WatchLog:I"], capture_output=True, text=True).stdout
        m = re.search(r"watch-parse-test → (\{.*\})", log)
        if m:
            return [r["text"] for r in json.loads(m.group(1)).get("rows", [])]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--evals", default=str(ROOT / "data" / "watch-evals.jsonl"))
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    cases = [json.loads(l) for l in open(a.evals)]
    if not a.all:
        cases = [c for c in cases if c["corrected"]]
    if a.limit:
        cases = cases[-a.limit:]
    if not cases:
        sys.exit("no cases (run export-assistant-log.py; --all for uncorrected ones too)")
    passed = 0
    for c in cases:
        got = parse(a.serial, c["input"])
        want = sorted(timeless(t) for t in c["expected"])
        have = sorted(timeless(t) for t in got) if got is not None else None
        ok = have == want
        passed += ok
        if not ok:
            print("MISS %s\n  said:     %s\n  expected: %s\n  now got:  %s\n  first got:%s"
                  % (c["id"], c["input"], want, have, sorted(timeless(t) for t in c["got"])))
    print("score: %d/%d" % (passed, len(cases)))


if __name__ == "__main__":
    main()
