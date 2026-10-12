#!/bin/bash
# Write the server's secrets from Geoff's Mac (~/.config/cgm-tools/) straight
# to the VM over SSH — never printed, never in the repo.
#   ray.env:    SYNC_KEY/SYNC_TOPIC (sync.env), DEXCOM_USERNAME/DEXCOM_PASSWORD (dexcom.env)
#   backup.env: BACKUP_PAR_URL (ray-backup-par.env), BACKUP_AGE_RECIPIENT (ray-backup.agepub)
set -euo pipefail
C=$HOME/.config/cgm-tools
HOST=${RAY_HOST:-ubuntu@ray.geoffreychan.com}
SSH="ssh -i $HOME/.ssh/oci_ray_key"
{
  echo "RAY_DB=/var/lib/ray/ray.db"
  echo "RAY_PORT=8080"
  sed -n 's/^KEY=/SYNC_KEY=/p; s/^TOPIC=/SYNC_TOPIC=/p' "$C/sync.env"
  [ -f "$C/dexcom.env" ] && grep -E '^DEXCOM_(USERNAME|PASSWORD)=' "$C/dexcom.env" || echo "# dexcom.env not on the Mac yet" >&2
} | $SSH "$HOST" 'sudo install -d -m 750 -o root -g ray /etc/ray && sudo tee /etc/ray/ray.env >/dev/null && sudo chown root:ray /etc/ray/ray.env && sudo chmod 640 /etc/ray/ray.env'
{
  cat "$C/ray-backup-par.env"
  echo "BACKUP_AGE_RECIPIENT=$(cat "$C/ray-backup.agepub")"
} | $SSH "$HOST" 'sudo tee /etc/ray/backup.env >/dev/null && sudo chown root:ray /etc/ray/backup.env && sudo chmod 640 /etc/ray/backup.env'
echo "secrets written"
