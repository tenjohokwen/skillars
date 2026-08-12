#!/usr/bin/env bash
# Creates a Hetzner Volume snapshot via the Hetzner Cloud API.
# Cron runs this daily at 02:00 UTC via install-crons.sh.
#
# !!! BROKEN — DOES NOT WORK AND CANNOT BE MADE TO WORK AS WRITTEN !!!
# Verified against the Hetzner Cloud API on 2026-08-11 (UAT.3 AC6): there is NO volume snapshot in
# that API. Volumes support attach / detach / resize / change_protection and nothing else; the only
# image-creating action is POST /v1/servers/{id}/actions/create_image, and a Hetzner server snapshot
# explicitly EXCLUDES attached volumes. The POST below therefore hits an endpoint that does not
# exist, fails, and has failed on every cron run since deploy-3-1 — consistent with drill-log.md
# never having recorded a restore drill.
#
# CONSEQUENCE: the Hetzner Volume mounted at /opt/skillars/data has NO working backup. That volume
# holds postgres data, redis, prometheus, loki and acme.json. The Postgres dumps in Object Storage
# (pg-backup.sh) are unaffected and remain the only working backup of the database.
#
# Deliberately NOT rewritten here: choosing the replacement (file-level backup of the volume to
# Object Storage, a Storage Box, or accepting dumps-only) is an operational decision, and
# restore-from-snapshot.sh rests on the same non-existent snapshots. Tracked in deferred-work.md
# under skillars-uat-3 and in docs/deployment/runbook.md.
set -euo pipefail

# shellcheck source=/dev/null
. /opt/skillars/.env

DATE=$(date -u +%Y-%m-%d)
DESCRIPTION="daily-${DATE}"

RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"description\": \"${DESCRIPTION}\"}" \
  "https://api.hetzner.cloud/v1/volumes/${HETZNER_VOLUME_ID}/actions/create_snapshot") \
  || { echo "[volume-snapshot][error] curl failed (exit $?) — network or DNS issue" >&2; exit 1; }

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ -z "$HTTP_CODE" ] || [ "$HTTP_CODE" -ne 201 ] 2>/dev/null; then
  echo "[volume-snapshot][error] API returned HTTP '${HTTP_CODE}': ${BODY}" >&2
  exit 1
fi

echo "[volume-snapshot] Snapshot created: ${DESCRIPTION}"
