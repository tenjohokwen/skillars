#!/usr/bin/env bash
# Installs backup cron entries for pg-backup.sh and volume-snapshot.sh.
# Safe to re-run — only adds each entry if not already present.
set -euo pipefail

LOG="/var/log/skillars-backup.log"
BACKUP_DIR="/opt/skillars/deploy/backup"

PG_CRON="0 */6 * * * ${BACKUP_DIR}/pg-backup.sh >> ${LOG} 2>&1"
SNAP_CRON="0 2 * * * ${BACKUP_DIR}/volume-snapshot.sh >> ${LOG} 2>&1"

if ! crontab -l 2>/dev/null | grep -qF "pg-backup.sh"; then
  (crontab -l 2>/dev/null; echo "$PG_CRON") | crontab -
  echo "[install-crons] pg-backup cron installed."
else
  echo "[install-crons] pg-backup cron already present — skipping."
fi

if ! crontab -l 2>/dev/null | grep -qF "volume-snapshot.sh"; then
  (crontab -l 2>/dev/null; echo "$SNAP_CRON") | crontab -
  echo "[install-crons] volume-snapshot cron installed."
else
  echo "[install-crons] volume-snapshot cron already present — skipping."
fi
