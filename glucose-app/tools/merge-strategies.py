#!/usr/bin/env python3
"""Merge research JSON files into Ray's playbook (core/src/main/resources/ray/strategies.json).

Usage: tools/merge-strategies.py research/clinical.json research/community.json research/nutrition.json
Each input is a JSON array of entries (id, title, patterns, foods?, advice, how, why,
involves_dose_change, evidence, caveat?, sources). Unknown patterns are dropped, ids
de-duplicated (first wins), and the result is sorted by id so diffs stay readable.
"""
import json, os, re, sys

PATTERNS = re.findall(r'"([a-z-]+)"', re.search(r'val PATTERNS = listOf\((.*?)\)', open(os.path.join(os.path.dirname(__file__), '../core/src/main/java/com/geoffchan/glucosewidget/Strategies.kt')).read(), re.S).group(1))
OUT = os.path.join(os.path.dirname(__file__), '../core/src/main/resources/ray/strategies.json')

merged, seen = [], set()
for path in sys.argv[1:]:
    origin = os.path.splitext(os.path.basename(path))[0]
    for e in json.load(open(path)):
        if e['id'] in seen:
            continue
        pats = [p for p in e.get('patterns', []) if p in PATTERNS]
        if not pats:
            print(f"skip {e['id']}: no known pattern {e.get('patterns')}", file=sys.stderr)
            continue
        seen.add(e['id'])
        merged.append({
            'id': e['id'], 'title': e['title'], 'patterns': pats,
            'foods': [f.lower() for f in e.get('foods', [])],
            'advice': e['advice'], 'how': e.get('how', ''), 'why': e.get('why', ''),
            'involves_dose_change': bool(e.get('involves_dose_change')),
            'evidence': e.get('evidence', 'educator'), 'caveat': e.get('caveat', ''),
            'research': origin, 'sources': e.get('sources', []),
        })
# Corrections after review (kept here so a re-merge doesn't lose them).
OVERRIDES = {
    'food-order': {'caveat': "Studied mostly in type 2; in type 1 the slower rise can leave rapid insulin peaking early, so watch for a dip in the first hour."},
}
for e in merged:
    e.update(OVERRIDES.get(e['id'], {}))
merged.sort(key=lambda e: e['id'])
json.dump(merged, open(OUT, 'w'), indent=1, ensure_ascii=False)
open(OUT, 'a').write('\n')
from collections import Counter
print(len(merged), 'entries →', os.path.relpath(OUT))
print(Counter(p for e in merged for p in e['patterns']).most_common())
