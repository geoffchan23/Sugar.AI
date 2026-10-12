#!/bin/bash
# Nightly: consistent snapshot of the Ray database → gzip → age-encrypted to
# the backup public key (the private key is only on Geoff's Mac) → upload to
# the private ray-backups bucket through a write-only link. The server can
# add backups but can't read or delete them; the bucket keeps 60 days.
set -euo pipefail
source /etc/ray/backup.env   # BACKUP_PAR_URL, BACKUP_AGE_RECIPIENT
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
NAME="ray-$(date +%Y-%m-%d-%H%M).db.gz.age"
sqlite3 /var/lib/ray/ray.db ".backup '$TMP/ray.db'"
sqlite3 "$TMP/ray.db" "PRAGMA integrity_check" | grep -qx ok
gzip -9 "$TMP/ray.db"
age -r "$BACKUP_AGE_RECIPIENT" -o "$TMP/$NAME" "$TMP/ray.db.gz"
curl -fsS --retry 3 -X PUT --data-binary "@$TMP/$NAME" "${BACKUP_PAR_URL}${NAME}" >/dev/null
echo "backed up $NAME ($(stat -c %s "$TMP/$NAME") bytes)"
