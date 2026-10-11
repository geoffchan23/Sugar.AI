#!/usr/bin/env python3
"""Export Francine's assistant interaction log for evals and skills.

Usage (from glucose-app/):
  tools/pull-db.sh [serial]                    # either phone: records sync both ways
  python3 tools/export-assistant-log.py [--db data/glucose.db] [--out data/assistant-log.jsonl] [--include-debug]

Writes one interaction per line (JSONL): what she said or typed, where
(watch / watch-queued / phone / debug), which parser handled it and what was
tried first, model usage and cost, the model trace (full on the phone where
it happened; trimmed on the other), what was proposed, and the OUTCOME — the
eval label — with later corrections resolved against the journal: each saved
row is "kept", "edited" (and to what), "confirmed" (a guess she kept),
"deleted" or "missing" (not synced yet).

Also writes data/watch-evals.jsonl (--evals): one case per saved record,
with what she said, what was logged ("got") and what the log says now
("expected": later edits applied, deletes dropped). Cases someone
corrected are the interesting ones; tools/run-watch-evals.py replays them.
From 2026-10-11 records also carry explicit "corrections" (who changed
what, when) in their outcome.

Prints a short summary. Records from ADB test ops (source "debug") are in
the file but left out of the summary unless --include-debug.

The output is health data: it stays in data/ (gitignored). Apple's
/usr/bin/python3 (3.9) is enough; stdlib only.
"""
import argparse
import json
import sqlite3
import statistics
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUESS = " (guess)"


def load(db):
    c = sqlite3.connect(db)
    c.row_factory = sqlite3.Row
    tables = {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "assistant_log" not in tables:
        return [], {}, {}
    journal = {r["uid"]: dict(r) for r in c.execute("SELECT uid, day, text, updatedAtMs FROM journal")}
    tombs = {r["uid"]: r["deletedAtMs"] for r in c.execute("SELECT uid, deletedAtMs FROM journal_tombstones")}
    rows = [dict(r) for r in c.execute("SELECT * FROM assistant_log ORDER BY createdAtMs")]
    return rows, journal, tombs


def resolve(item, journal, tombs):
    """What became of one saved row since: kept / edited / confirmed / deleted / missing."""
    uid = item.get("journalUid")
    out = dict(item)
    if not uid:
        out["now"] = "n/a"  # "already logged": nothing was written
        return out
    row = journal.get(uid)
    if row is None:
        if uid in tombs:
            out["now"], out["deletedAtMs"] = "deleted", tombs[uid]
        else:
            out["now"] = "missing"
        return out
    saved = item.get("saved", "")
    if row["text"] == saved:
        out["now"] = "kept"
    elif saved.endswith(GUESS) and row["text"] == saved[: -len(GUESS)]:
        out["now"] = "confirmed"
    else:
        out["now"], out["nowText"] = "edited", row["text"]
    return out


def record(r, journal, tombs):
    try:
        data = json.loads(r["data"] or "{}")
    except ValueError:
        data = {"unparsed": r["data"]}
    outcome = None
    if r["outcome"]:
        try:
            outcome = json.loads(r["outcome"])
            outcome["saved"] = [resolve(s, journal, tombs) for s in outcome.get("saved", [])]
        except ValueError:
            outcome = {"unparsed": r["outcome"]}
    return {
        "id": r["uid"],
        "createdAtMs": r["createdAtMs"],
        "updatedAtMs": r["updatedAtMs"],
        "source": r["source"],
        "input": r["input"],
        "parser": r["parser"],
        "fullTrace": bool(r["full"]),
        **data,
        "outcome": outcome,
    }


def eval_case(x):
    """input → expected rows, from a saved record and what became of its rows."""
    o = x.get("outcome") or {}
    if o.get("kind") not in ("saved", "saved-as-guesses"):
        return None
    got, expected = [], []
    for s in o.get("saved", []):
        text = s.get("saved", "")
        text = text[: -len(GUESS)] if text.endswith(GUESS) else text
        if s.get("op") == "already":
            continue
        got.append(text)
        now = s.get("now")
        if now == "edited":
            expected.append(s.get("nowText", ""))
        elif now in ("kept", "confirmed", "missing"):
            expected.append(text)
    corrections = o.get("corrections", [])
    return {
        "id": x["id"], "source": x["source"], "input": x["input"],
        "now": (x.get("context") or {}).get("now"), "parser": x["parser"],
        "got": got, "expected": expected, "corrected": got != expected or bool(corrections),
        "corrections": corrections,
    }


def summary(recs):
    n = len(recs)
    print("interactions: %d" % n)
    if not n:
        return
    print("by source: " + ", ".join("%s %d" % kv for kv in Counter(x["source"] for x in recs).most_common()))
    print("by parser: " + ", ".join("%s %d" % kv for kv in Counter(x["parser"] for x in recs).most_common()))
    kinds = Counter((x["outcome"] or {}).get("kind", "none (abandoned / not yet)") for x in recs)
    print("outcomes:  " + ", ".join("%s %d" % kv for kv in kinds.most_common()))
    fell_back = sum(1 for x in recs if any(not a.get("ok") for a in x.get("attempts", [])))
    print("fell back to another parser: %d" % fell_back)

    # keep rate: of the rows offered as new (or confirming a guess) on records she acted on,
    # how many were saved and are still as saved
    offered = kept = edits = deleted = 0
    for x in recs:
        o = x["outcome"]
        if not o or o.get("kind") not in ("saved", "saved-as-guesses"):
            continue
        offered += sum(1 for p in x.get("proposals", []) if p.get("status") in ("new", "confirm"))
        for s in o.get("saved", []):
            if s.get("op") == "already":
                continue
            saved = s.get("saved", "")
            saved = saved[: -len(GUESS)] if saved.endswith(GUESS) else saved
            if s.get("proposed") and s.get("proposed") != saved:
                edits += 1  # edited on the phone before saving
            elif s.get("now") in ("kept", "confirmed"):
                kept += 1
            elif s.get("now") == "edited":
                edits += 1  # fixed later
            elif s.get("now") == "deleted":
                deleted += 1
    if offered:
        print("keep rate: %d/%d proposed rows saved and unchanged (%.0f%%); %d edited (before or after saving), %d deleted later"
              % (kept, offered, 100.0 * kept / offered, edits, deleted))

    cs = [c for x in recs for c in ((x.get("outcome") or {}).get("corrections") or [])]
    if cs:
        print("corrections: %d (%s; by %s)" % (
            len(cs), ", ".join("%s %d" % kv for kv in Counter(c["op"] for c in cs).most_common()),
            ", ".join("%s %d" % kv for kv in Counter(c["by"] for c in cs).most_common())))

    ms = [x["ms"] for x in recs if isinstance(x.get("ms"), (int, float))]
    if ms:
        print("assistant latency: median %.1f s, max %.1f s (n=%d)" % (statistics.median(ms) / 1000, max(ms) / 1000, len(ms)))
    cost = [x["costUsd"] for x in recs if isinstance(x.get("costUsd"), (int, float))]
    if cost:
        print("assistant cost: total $%.4f, mean $%.5f per call (n=%d)" % (sum(cost), sum(cost) / len(cost), len(cost)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=str(ROOT / "data" / "glucose.db"))
    ap.add_argument("--out", default=str(ROOT / "data" / "assistant-log.jsonl"))
    ap.add_argument("--evals", default=str(ROOT / "data" / "watch-evals.jsonl"))
    ap.add_argument("--include-debug", action="store_true", help="count ADB test-op records in the summary")
    a = ap.parse_args()
    if not Path(a.db).exists():
        sys.exit("no database at %s — run tools/pull-db.sh first" % a.db)
    rows, journal, tombs = load(a.db)
    recs = [record(r, journal, tombs) for r in rows]
    with open(a.out, "w") as f:
        for x in recs:
            f.write(json.dumps(x, ensure_ascii=False) + "\n")
    print("wrote %d interactions to %s" % (len(recs), a.out))
    cases = [c for c in (eval_case(x) for x in recs if x["source"] != "debug") if c]
    with open(a.evals, "w") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print("wrote %d eval cases (%d corrected) to %s" % (len(cases), sum(c["corrected"] for c in cases), a.evals))
    if not rows and journal == {}:
        print("(this database predates the interaction log — install the current app and pull again)")
    summary([x for x in recs if a.include_debug or x["source"] != "debug"])


if __name__ == "__main__":
    main()
