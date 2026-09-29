#!/usr/bin/env bash
# Companion to docs/deployment/local/start.sh — stops the local Skillars stack with the same
# --env-file .env.local flag start.sh uses, so grafana/traefik's interpolated env vars resolve
# cleanly instead of throwing ${VAR:?...} warnings on 'down'/'ps'/'logs'.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../.."
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local down
