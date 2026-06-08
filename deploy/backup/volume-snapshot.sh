#!/usr/bin/env bash
# Creates a Hetzner Volume snapshot via the Hetzner Cloud API.
# Cron runs this daily at 02:00 UTC via install-crons.sh.
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
