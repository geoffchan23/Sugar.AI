#!/bin/bash
# Write the server's secrets from Geoff's Mac (~/.config/cgm-tools/) straight
# to the VM over SSH — never printed, never in the repo.
#   ray.env:    DEXCOM_USERNAME/DEXCOM_PASSWORD (dexcom.env), OPENAI_API_KEY (openai.env),
#               DEVICE_TOKENS = name:sha256 of each token in ray-devices.env (HER_PHONE_TOKEN → her-phone),
#               ALEXA_SKILL_ID (alexa.env, once the skill exists),
#               SYNC_KEY/SYNC_TOPIC (sync.env) only while RAY_FOLLOW_RELAY=1
#   backup.env: BACKUP_PAR_URL (ray-backup-par.env), BACKUP_AGE_RECIPIENT (ray-backup.agepub)
set -euo pipefail
C=$HOME/.config/cgm-tools
HOST=${RAY_HOST:-ubuntu@ray.geoffreychan.com}
SSH="ssh -i $HOME/.ssh/oci_ray_key"
{
  echo "RAY_DB=/var/lib/ray/ray.db"
  echo "RAY_PORT=8080"
  [ "${RAY_FOLLOW_RELAY:-0}" = 1 ] && sed -n 's/^KEY=/SYNC_KEY=/p; s/^TOPIC=/SYNC_TOPIC=/p' "$C/sync.env"
  [ -f "$C/dexcom.env" ] && grep -E '^DEXCOM_(USERNAME|PASSWORD)=' "$C/dexcom.env" || echo "# dexcom.env not on the Mac yet" >&2
  grep -E '^OPENAI_API_KEY=' "$C/openai.env"
  echo "DEVICE_TOKENS=$(python3 - "$C/ray-devices.env" <<'PY'
import sys,hashlib
out=[]
for line in open(sys.argv[1]):
    k,_,v=line.strip().partition('=')
    if k.endswith('_TOKEN') and v: out.append(k[:-6].lower().replace('_','-')+':'+hashlib.sha256(v.encode()).hexdigest())
print(','.join(out))
PY
)"
  [ -f "$C/alexa.env" ] && grep -E '^ALEXA_(SKILL_ID|USER_IDS)=' "$C/alexa.env" || true
} | $SSH "$HOST" 'sudo install -d -m 750 -o root -g ray /etc/ray && sudo tee /etc/ray/ray.env >/dev/null && sudo chown root:ray /etc/ray/ray.env && sudo chmod 640 /etc/ray/ray.env'
{
  cat "$C/ray-backup-par.env"
  echo "BACKUP_AGE_RECIPIENT=$(cat "$C/ray-backup.agepub")"
} | $SSH "$HOST" 'sudo tee /etc/ray/backup.env >/dev/null && sudo chown root:ray /etc/ray/backup.env && sudo chmod 640 /etc/ray/backup.env'
echo "secrets written"
