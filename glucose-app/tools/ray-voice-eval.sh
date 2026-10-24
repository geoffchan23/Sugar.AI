#!/usr/bin/env bash
# How Ray sounds, on a copy of the server's data (never her phone, never the live server).
# Pulls a fresh copy of ray.db into gitignored data/ unless --no-pull, then runs
# tools/ray-voice-questions.txt through the real chat/watch loop. Answers → data/ray-voice-eval.jsonl.
set -euo pipefail
cd "$(dirname "$0")/.."
K=$HOME/.ssh/oci_ray_key; HOST=${RAY_HOST:-ubuntu@ray.geoffreychan.com}
if [[ "${1:-}" != "--no-pull" ]]; then
  ssh -i "$K" "$HOST" 'sudo -u ray sqlite3 /var/lib/ray/ray.db ".backup /tmp/ray-eval.db" && sudo chmod 644 /tmp/ray-eval.db'
  scp -q -i "$K" "$HOST:/tmp/ray-eval.db" data/ray-eval.db
  ssh -i "$K" "$HOST" 'sudo rm -f /tmp/ray-eval.db'
fi
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export OPENAI_API_KEY=$(grep OPENAI_API_KEY ~/.config/cgm-tools/openai.env | cut -d= -f2-)
export RAY_EVAL_DB=$PWD/data/ray-eval.db RAY_EVAL_OUT=${RAY_EVAL_OUT:-$PWD/data/ray-voice-eval.jsonl} RAY_EVAL_QUESTIONS=${RAY_EVAL_QUESTIONS:-$PWD/tools/ray-voice-questions.txt}
./gradlew -q :server:test --tests com.geoffchan.ray.RayVoiceEval --rerun >/dev/null
python3 -c "import json,sys
for l in open(sys.argv[1]):
  o=json.loads(l); print(\"\\n=== \"+o[\"q\"]); print(o[\"answer\"]); o[\"detail\"] and print(\"-- detail: \"+o[\"detail\"]); print(\"tools:\", o.get(\"tools\"))" "$RAY_EVAL_OUT"
