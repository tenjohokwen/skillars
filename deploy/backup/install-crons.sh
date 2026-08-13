#!/usr/bin/env bash
# Installs backup cron entries for pg-backup.sh and volume-backup.sh.
# Safe to re-run — only adds each entry if not already present. Also purges the stale
# volume-snapshot.sh entry left behind on an already-provisioned node — that script no longer
# exists on disk, but a prior crontab install of it is not removed just by deleting the file.
set -euo pipefail

LOG="/var/log/skillars-backup.log"
BACKUP_DIR="/opt/skillars/deploy/backup"

PG_CRON="0 */6 * * * ${BACKUP_DIR}/pg-backup.sh >> ${LOG} 2>&1"
VOLUME_CRON="0 2 * * * ${BACKUP_DIR}/volume-backup.sh >> ${LOG} 2>&1"
# 03:30 UTC — clear of both producers above (0 */6 and 0 2), so retention never races an upload.
PRUNE_CRON="30 3 * * * ${BACKUP_DIR}/prune-backups.sh >> ${LOG} 2>&1"

if ! crontab -l 2>/dev/null | grep -qF "pg-backup.sh"; then
  (crontab -l 2>/dev/null; echo "$PG_CRON") | crontab -
  echo "[install-crons] pg-backup cron installed."
else
  echo "[install-crons] pg-backup cron already present — skipping."
fi

if crontab -l 2>/dev/null | grep -qF "volume-snapshot.sh"; then
  crontab -l 2>/dev/null | grep -vF "volume-snapshot.sh" | crontab -
  echo "[install-crons] removed stale volume-snapshot.sh cron entry (script no longer exists)."
fi

if ! crontab -l 2>/dev/null | grep -qF "volume-backup.sh"; then
  (crontab -l 2>/dev/null; echo "$VOLUME_CRON") | crontab -
  echo "[install-crons] volume-backup cron installed."
else
  echo "[install-crons] volume-backup cron already present — skipping."
fi

if ! crontab -l 2>/dev/null | grep -qF "prune-backups.sh"; then
  (crontab -l 2>/dev/null; echo "$PRUNE_CRON") | crontab -
  echo "[install-crons] prune-backups cron installed."
else
  echo "[install-crons] prune-backups cron already present — skipping."
fi
