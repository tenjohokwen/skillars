# Story Deploy-3.2: Scripted Restore Process

Status: done

## Story

As a developer responding to a data loss or Node failure,
I want an executable restore script in the repository that restores the application from either a volume snapshot or a `pg_dump`, with quarterly drill results recorded,
so that I can execute a full restore without improvising steps and within the RTO < 4-hour target.

## Acceptance Criteria

**AC-1: Restore from volume snapshot**
- Given the restore script exists in the repository
- When a developer executes it against a volume snapshot
- Then the application is restored to a running, healthy state with data from the snapshot — without requiring any undocumented manual steps
- And the script is executable (not merely a prose runbook) and stored in version control
- And the script is idempotent — re-running it on a partially restored environment does not create an inconsistent state

**AC-2: Restore from pg_dump**
- Given the restore script exists
- When a developer executes it against a `pg_dump` from Object Storage
- Then the application is restored with data from that dump, and data integrity is verified (e.g., row count check or health endpoint returns healthy post-restore)

**AC-3: Quarterly drill record**
- Given a quarterly restore drill is due
- When a developer runs the restore script on a non-production environment
- Then the result (pass/fail, date, environment used) is recorded in a designated location in the repository
- And the drill confirms the RTO < 4-hour target is achievable via the scripted process

---

## Tasks / Subtasks

- [x] Task 1: Create `deploy/backup/restore-from-dump.sh` — restore PostgreSQL from pg_dump in HOS (AC-2, AC-3)
  - [x] Source `/opt/skillars/.env` for all credentials
  - [x] Accept one argument: dump object key (e.g. `pg-backups/skillars-20260603T060000Z.sql.gz`); if `latest`, auto-discover using `aws s3 ls`
  - [x] Download dump from HOS to `/tmp/skillars-restore-TIMESTAMP.sql.gz` via `aws s3 cp`
  - [x] Guard: exit non-zero if downloaded file is empty or missing
  - [x] Stop `app` service only — keep `postgres` running: `docker compose -f /opt/skillars/docker-compose.yml stop app`
  - [x] Get postgres container ID via `docker compose -f /opt/skillars/docker-compose.yml ps -q postgres`
  - [x] Drop and recreate database (connected to `postgres` default db, not `$POSTGRES_DB`)
  - [x] Restore: `gunzip -c DUMP_FILE | docker exec -i -e PGPASSWORD=... CID psql -U USER -d DB`
  - [x] Data integrity check: query `pg_catalog.pg_tables` for public schema table count ≥ 1
  - [x] Start `app` service: `docker compose -f /opt/skillars/docker-compose.yml start app`
  - [x] Health poll: curl `http://localhost:8367/manage/health` until UP or 90-second timeout
  - [x] Remove temp dump file on success (leave it on failure for inspection)
  - [x] `set -euo pipefail`; executable (`chmod +x`); idempotent

- [x] Task 2: Create `deploy/backup/restore-from-snapshot.sh` — on-Node restore from Hetzner Volume snapshot (AC-1, AC-3)
  - [x] Print prerequisite instructions at startup (Hetzner Console steps before running this script)
  - [x] Source `/opt/skillars/.env` for DOMAIN
  - [x] Stop ALL services: `docker compose -f /opt/skillars/docker-compose.yml down`
  - [x] Unmount the volume: `umount /opt/skillars/data` (guard: only if currently mounted)
  - [x] Prompt: "Detach old volume and attach snapshot-restored volume in Hetzner Console. Press ENTER when done."
  - [x] Mount new volume: `mount /dev/sdb /opt/skillars/data`
  - [x] Recreate subdirectory ownership (postgres, prometheus, loki, tempo, grafana) — same as provision.sh step 6
  - [x] Start all services: `docker compose -f /opt/skillars/docker-compose.yml up -d`
  - [x] Health poll: curl `http://localhost:8367/manage/health` until UP or 120-second timeout (longer than dump restore: full `compose up -d` vs `start app`)
  - [x] `set -euo pipefail`; executable; idempotent (safe to re-run from any failed step)

- [x] Task 3: Create `deploy/backup/drill-log.md` — quarterly drill record template (AC-3)
  - [x] Create the file with a table: columns = Date, Environment, Method (snapshot/dump), Result (pass/fail), RTO Achieved, Notes
  - [x] Include one row of example data (marked as Example) showing the format
  - [x] Add instruction comment: "Append one row per quarterly drill"

---

## Dev Notes

### Story scope: pure shell scripts + one markdown file — NO Java/Spring changes

**Files to CREATE:**
- `deploy/backup/restore-from-dump.sh`
- `deploy/backup/restore-from-snapshot.sh`
- `deploy/backup/drill-log.md`

**Files to NOT touch:**
- `docker-compose.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`
- `deploy/provision.sh`
- `.env.example`
- `docs/deployment/secrets-reference.md`
- Any Java source file

> `docs/deployment/backup-restore.md` is created in Story 3.4 (Operational Documentation Suite), not here. Story 3.2 scope is the executable scripts and the drill record only.

---

### Task 1 Detail: `deploy/backup/restore-from-dump.sh`

#### Argument handling

```bash
DUMP_KEY="${1:-}"  # e.g. "pg-backups/skillars-20260603T060000Z.sql.gz" or "latest"
if [ -z "$DUMP_KEY" ]; then
  echo "Usage: $0 <s3-object-key | latest>" >&2
  exit 1
fi
```

If `latest`:
```bash
DUMP_KEY=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 ls "s3://${HOS_BUCKET}/${PREFIX}" --endpoint-url "${HOS_ENDPOINT}" \
    | sort | tail -1 | awk '{print $4}'
)
DUMP_KEY="${PREFIX}${DUMP_KEY}"
```

#### Download from HOS

```bash
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
LOCAL_DUMP="/tmp/skillars-restore-${TIMESTAMP}.sql.gz"

echo "[restore-dump] Downloading s3://${HOS_BUCKET}/${DUMP_KEY}..."
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${DUMP_KEY}" "${LOCAL_DUMP}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

if [ ! -s "${LOCAL_DUMP}" ]; then
  echo "[restore-dump][error] Downloaded file is empty or missing." >&2
  exit 1
fi
```

#### Stop app, get postgres container

```bash
echo "[restore-dump] Stopping app service..."
docker compose -f /opt/skillars/docker-compose.yml stop app

CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "[restore-dump][error] postgres container not running." >&2
  exit 1
fi
```

#### Drop and recreate database

Connect to default `postgres` DB (not `$POSTGRES_DB`) to issue DDL. `$POSTGRES_USER` was created as a superuser by the `postgres:17-alpine` image.

```bash
echo "[restore-dump] Dropping existing database..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${POSTGRES_DB:-skillars}\";"

echo "[restore-dump] Recreating database..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "CREATE DATABASE \"${POSTGRES_DB:-skillars}\" OWNER \"${POSTGRES_USER:-postgres}\";"
```

#### Restore dump

Use `-i` (interactive/stdin) flag for piped input:
```bash
echo "[restore-dump] Restoring dump..."
gunzip -c "${LOCAL_DUMP}" | \
  docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  --set ON_ERROR_STOP=1
```

- `--set ON_ERROR_STOP=1` causes psql to abort and exit non-zero on any SQL error (critical: without this, psql swallows errors and exits 0).
- `set -euo pipefail` + `ON_ERROR_STOP=1` together ensure the script exits non-zero if restore fails.

#### Data integrity check

```bash
TABLE_COUNT=$(docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -t -c "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname='public';" \
  2>/dev/null | tr -d ' \n')

if [ -z "${TABLE_COUNT}" ] || [ "${TABLE_COUNT}" -lt 1 ]; then
  echo "[restore-dump][error] Data integrity check failed: no public tables found." >&2
  exit 1
fi
echo "[restore-dump] Integrity check: ${TABLE_COUNT} public tables found."
```

#### Start app and poll health

```bash
echo "[restore-dump] Starting app service..."
docker compose -f /opt/skillars/docker-compose.yml start app

echo "[restore-dump] Waiting for app health..."
DEADLINE=$(($(date +%s) + 90))
until curl -sf http://localhost:8367/manage/health > /dev/null 2>&1; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    echo "[restore-dump][error] App did not become healthy within 90 seconds." >&2
    exit 1
  fi
  sleep 3
done
echo "[restore-dump] App is healthy."
```

#### Cleanup (success path only)

```bash
rm -f "${LOCAL_DUMP}"
echo "[restore-dump] Done. $(date -u)"
```

Do NOT `rm` in a `trap` — leave the dump file on failure so the operator can inspect it.

#### Full script skeleton

```bash
#!/usr/bin/env bash
# Restore PostgreSQL from a pg_dump archive stored in Hetzner Object Storage.
# Usage: ./restore-from-dump.sh <s3-object-key | latest>
#   Example: ./restore-from-dump.sh pg-backups/skillars-20260603T060000Z.sql.gz
#   Example: ./restore-from-dump.sh latest
set -euo pipefail

DUMP_KEY="${1:-}"
if [ -z "$DUMP_KEY" ]; then
  echo "Usage: $0 <s3-object-key | latest>" >&2
  exit 1
fi

# shellcheck source=/dev/null
. /opt/skillars/.env

PREFIX="${HOS_BACKUP_PREFIX:-pg-backups/}"
PREFIX="${PREFIX%/}/"

if [ "$DUMP_KEY" = "latest" ]; then
  echo "[restore-dump] Discovering latest dump in s3://${HOS_BUCKET}/${PREFIX}..."
  FILENAME=$(
    AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
    AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
    aws s3 ls "s3://${HOS_BUCKET}/${PREFIX}" \
      --endpoint-url "${HOS_ENDPOINT}" \
    | sort | tail -1 | awk '{print $4}'
  )
  if [ -z "$FILENAME" ]; then
    echo "[restore-dump][error] No dump files found in s3://${HOS_BUCKET}/${PREFIX}" >&2
    exit 1
  fi
  DUMP_KEY="${PREFIX}${FILENAME}"
  echo "[restore-dump] Latest dump: ${DUMP_KEY}"
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
LOCAL_DUMP="/tmp/skillars-restore-${TIMESTAMP}.sql.gz"

echo "[restore-dump] Downloading s3://${HOS_BUCKET}/${DUMP_KEY}..."
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${DUMP_KEY}" "${LOCAL_DUMP}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

if [ ! -s "${LOCAL_DUMP}" ]; then
  echo "[restore-dump][error] Downloaded file is empty or missing." >&2
  exit 1
fi

echo "[restore-dump] Stopping app service..."
docker compose -f /opt/skillars/docker-compose.yml stop app

CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "[restore-dump][error] postgres container not running." >&2
  exit 1
fi

echo "[restore-dump] Dropping and recreating database ${POSTGRES_DB:-skillars}..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${POSTGRES_DB:-skillars}\";"
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "CREATE DATABASE \"${POSTGRES_DB:-skillars}\" OWNER \"${POSTGRES_USER:-postgres}\";"

echo "[restore-dump] Restoring dump (this may take several minutes)..."
gunzip -c "${LOCAL_DUMP}" | \
  docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  --set ON_ERROR_STOP=1

TABLE_COUNT=$(docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -t -c "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname='public';" \
  2>/dev/null | tr -d ' \n')

if [ -z "${TABLE_COUNT}" ] || [ "${TABLE_COUNT}" -lt 1 ]; then
  echo "[restore-dump][error] Integrity check failed: no public tables found." >&2
  exit 1
fi
echo "[restore-dump] Integrity check: ${TABLE_COUNT} public tables found."

echo "[restore-dump] Starting app service..."
docker compose -f /opt/skillars/docker-compose.yml start app

echo "[restore-dump] Waiting for app health (up to 90s)..."
DEADLINE=$(($(date +%s) + 90))
until curl -sf http://localhost:8367/manage/health > /dev/null 2>&1; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    echo "[restore-dump][error] App did not become healthy within 90s." >&2
    exit 1
  fi
  sleep 3
done

rm -f "${LOCAL_DUMP}"
echo "[restore-dump] Restore complete. $(date -u)"
```

---

### Task 2 Detail: `deploy/backup/restore-from-snapshot.sh`

This script handles the on-Node portions of a volume snapshot restore. The Hetzner Cloud Console/API operations (detach old volume, create new from snapshot, attach new) are a required manual step; the script pauses and prompts for them.

#### On-Node volume structure

All persistent data lives on the Hetzner Volume mounted at `/opt/skillars/data`:

| Subdirectory | Container | Owner (uid:gid) |
|---|---|---|
| `data/postgres` | postgres | root (postgres manages its own files) |
| `data/prometheus` | prometheus | 65534:65534 (nobody) |
| `data/loki` | loki | 10001:10001 |
| `data/tempo` | tempo | 10001:10001 |
| `data/grafana` | grafana | 472:472 |

Redis uses a Docker named volume (`redis-data`), not the Hetzner Volume — it is not restored by this script.

#### Full script skeleton

```bash
#!/usr/bin/env bash
# Restore Node from a Hetzner Volume snapshot.
# This script handles the on-Node steps. You must perform Hetzner Console steps manually at the prompt.
#
# Prerequisites:
#   1. SSH access to the Node as root
#   2. Repository cloned at /opt/skillars and /opt/skillars/.env in place
#
# Steps handled by this script:
#   A. Stop all Docker services
#   B. Unmount the current volume (PAUSE — you detach/attach in Hetzner Console)
#   C. Mount the new volume
#   D. Restore subdirectory ownership
#   E. Start all Docker services
#   F. Verify application health
set -euo pipefail

# shellcheck source=/dev/null
. /opt/skillars/.env

COMPOSE_FILE="/opt/skillars/docker-compose.yml"
DATA_DIR="/opt/skillars/data"
VOLUME_DEVICE="/dev/sdb"

log() { echo "[restore-snapshot] $*"; }
err() { echo "[restore-snapshot][error] $*" >&2; }

# ── A. Stop all services ──────────────────────────────────
log "Stopping all Docker services..."
docker compose -f "${COMPOSE_FILE}" down

# ── B. Unmount current volume ─────────────────────────────
if mountpoint -q "${DATA_DIR}"; then
  log "Unmounting ${DATA_DIR}..."
  umount "${DATA_DIR}"
else
  log "${DATA_DIR} is not currently mounted — skipping umount."
fi

# ── MANUAL HETZNER STEP ───────────────────────────────────
echo ""
echo "============================================================"
echo "  MANUAL STEP REQUIRED IN HETZNER CLOUD CONSOLE"
echo "============================================================"
echo "  1. Go to Hetzner Cloud Console → Volumes"
echo "  2. Detach the current volume from this server (if attached)"
echo "  3. Go to the snapshot you want to restore"
echo "     (Cloud Console → Volumes → Snapshots or via the volume's actions)"
echo "  4. Create a new Volume from that snapshot"
echo "  5. Attach the new Volume to this server as /dev/sdb"
echo "  6. Wait for Hetzner to confirm the attachment is complete"
echo "============================================================"
echo ""
read -r -p "Press ENTER when the new volume is attached as /dev/sdb..."

# ── C. Mount new volume ───────────────────────────────────
if ! [ -b "${VOLUME_DEVICE}" ]; then
  err "Device ${VOLUME_DEVICE} not found. Ensure the volume is attached in Hetzner Console."
  exit 1
fi

if mountpoint -q "${DATA_DIR}"; then
  log "${DATA_DIR} is already mounted — skipping mount (idempotent)."
else
  log "Mounting ${VOLUME_DEVICE} at ${DATA_DIR}..."
  mount "${VOLUME_DEVICE}" "${DATA_DIR}"
fi

# ── D. Restore subdirectory ownership ────────────────────
log "Restoring subdirectory ownership..."
mkdir -p "${DATA_DIR}/postgres"
mkdir -p "${DATA_DIR}/prometheus"
chown -R 65534:65534 "${DATA_DIR}/prometheus"
mkdir -p "${DATA_DIR}/loki"
chown -R 10001:10001 "${DATA_DIR}/loki"
mkdir -p "${DATA_DIR}/tempo"
chown -R 10001:10001 "${DATA_DIR}/tempo"
mkdir -p "${DATA_DIR}/grafana"
chown -R 472:472 "${DATA_DIR}/grafana"

# ── E. Start services ─────────────────────────────────────
log "Starting all Docker services..."
docker compose -f "${COMPOSE_FILE}" up -d

# ── F. Health check ───────────────────────────────────────
log "Waiting for app health (up to 120s)..."
DEADLINE=$(($(date +%s) + 120))
until curl -sf http://localhost:8367/manage/health > /dev/null 2>&1; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    err "App did not become healthy within 120s."
    err "Check logs: docker compose -f ${COMPOSE_FILE} logs app --tail=50"
    exit 1
  fi
  sleep 5
done

log "Restore complete. App is healthy. $(date -u)"
```

---

### Task 3 Detail: `deploy/backup/drill-log.md`

```markdown
# Restore Drill Log

Append one row per quarterly drill. Record every drill, including failures.
Drills should target a non-production environment to avoid production data exposure.

| Date | Environment | Method | Result | RTO Achieved | Notes |
|---|---|---|---|---|---|
| 2026-06-01 | local docker (non-prod) | pg_dump | PASS | 38 min | Example row — delete before first real drill |
```

---

### Infrastructure Context (from Story 3.1 and provision.sh)

**Server root:** `/opt/skillars` — all scripts use absolute paths from here

**Volume device:** `/dev/sdb` mounted at `/opt/skillars/data` (ext4, fstab entry with `nofail`)

**PostgreSQL:**
- Container image: `postgres:17-alpine`
- Data directory: `/opt/skillars/data/postgres` → `/var/lib/postgresql/data` (bind mount in docker-compose.yml)
- Service name for docker compose: `postgres`
- Get CID: `docker compose -f /opt/skillars/docker-compose.yml ps -q postgres`
- POSTGRES_USER is the superuser on this image (postgres:17-alpine sets it via `POSTGRES_USER` env var)
- Connect to `postgres` DB (not `$POSTGRES_DB`) when issuing DROP/CREATE DATABASE

**App service:**
- Docker Compose service name: `app`
- Internal management port: `8367` (Spring Boot management port, not the main API port 9990)
- On-Node health URL: `http://localhost:8367/manage/health` (NOT `/actuator/health` — Traefik rewrites to `/manage/health`)
- docker-compose.yml healthcheck uses: `wget -qO- http://localhost:8367/manage/health`

**HOS credentials pattern (from pg-backup.sh):**
```bash
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 <command> --endpoint-url "${HOS_ENDPOINT}"
```
Never use `aws configure` — always pass credentials as env vars.

**Dump format:** Plain SQL piped through gzip (`pg_dump ... | gzip`) — restore with `gunzip -c | psql`, NOT `pg_restore`.

**Critical: `docker exec` with piped stdin requires `-i` flag:**
```bash
gunzip -c dump.sql.gz | docker exec -i -e PGPASSWORD=... "$CID" psql ...
# -i = keep stdin open for piping; WITHOUT -i psql closes immediately
```

---

### Previous Story Intelligence (deploy-3-1 learnings)

- **`docker compose ps -q postgres`** over hardcoded `skillars-postgres-1` — compose may change suffix
- **`set -euo pipefail`** on all scripts — non-zero exit on any failure
- **Idempotency is NFR-3** — every script must check-before-act
- **Non-zero exit after failure** — preserve artifacts (dump files) for operator inspection on failure; do NOT use trap to auto-clean
- **File size guard** — check `[ -s "${FILE}" ]` after downloads/dumps before proceeding
- **PREFIX normalisation** — `${PREFIX%/}/` strips and re-adds trailing slash
- **`--no-progress`** — suppress progress bars in all aws CLI calls (cron/log noise)
- **`docker exec -e PGPASSWORD`** — passes password to psql cleanly without a pgpass file
- **`curl` error handling** — check exit code; use `|| { ...; exit 1; }` on curl calls
- **awscli on Ubuntu 22.04** is v1 from Ubuntu apt repo — `s3 ls` output format: `date time size filename` (4 columns)

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 3.2 — Scripted Restore Process]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-16 — Executable restore script, quarterly drill, RTO < 4h]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-3 — Idempotency]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-7 — RTO < 4 hours]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-8 — RPO < 24h snapshot, < 6h pg_dump]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Restore script: FR-16 requires executable script, not prose runbook"]
- [Source: deploy/provision.sh#step 6 — volume device /dev/sdb, mount point /opt/skillars/data, subdirectory ownership]
- [Source: docker-compose.yml — app service: port 8367 for management, healthcheck wget localhost:8367/manage/health]
- [Source: docker-compose.yml — postgres: postgres:17-alpine, bind mount /opt/skillars/data/postgres]
- [Source: docker-compose.yml — LGTM services: prometheus uid 65534, loki/tempo uid 10001, grafana uid 472]
- [Source: deploy/backup/pg-backup.sh — aws CLI pattern, PREFIX normalisation, docker compose ps -q, set -euo pipefail]
- [Source: _bmad-output/implementation-artifacts/deploy-3-1 review — ON_ERROR_STOP=1 for psql, -i flag for docker exec piped input]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — no blockers encountered.

### Completion Notes List

- Task 1: Created `deploy/backup/restore-from-dump.sh` — sources `.env`, accepts dump key or `latest`, downloads from HOS, stops app only, drops/recreates DB, restores with `gunzip -c | psql --set ON_ERROR_STOP=1`, verifies public table count ≥ 1, health-polls for 90 s, removes temp file on success only. `set -euo pipefail`; executable; idempotent. Passes shellcheck.
- Task 2: Created `deploy/backup/restore-from-snapshot.sh` — stops all services via `compose down`, unmounts with `mountpoint -q` guard, pauses with clear Hetzner Console instructions, mounts with block-device guard + idempotent mountpoint check, restores subdirectory ownership (prometheus 65534, loki/tempo 10001, grafana 472), starts services, health-polls 120 s. `set -euo pipefail`; executable; idempotent. Passes shellcheck.
- Task 3: Created `deploy/backup/drill-log.md` — markdown table with Date/Environment/Method/Result/RTO Achieved/Notes columns, example row marked for deletion before first real drill, instruction to append one row per quarterly drill.

### File List

- deploy/backup/restore-from-dump.sh (created)
- deploy/backup/restore-from-snapshot.sh (created)
- deploy/backup/drill-log.md (created)

### Change Log

- 2026-06-04: Implemented scripted restore process — created restore-from-dump.sh (AC-2, AC-3), restore-from-snapshot.sh (AC-1, AC-3), and drill-log.md (AC-3)

---

### Review Findings

- [x] [Review][Decision→Patch] `sh -c` env var pattern aligned to spec's `-e PGPASSWORD` flag — refactored drop, recreate, restore pipeline, and integrity-check to use `docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" ... psql -U "${POSTGRES_USER:-postgres}"` [restore-from-dump.sh:83–97] ✅ fixed
- [x] [Review][Patch] Health check curls port 8367 which is NOT exposed to the host — replaced `curl localhost:8367` with `docker inspect --format '{{.State.Health.Status}}'` using the app container ID [restore-from-dump.sh:108] [restore-from-snapshot.sh:85] ✅ fixed
- [x] [Review][Patch] `latest` discovery: added `awk 'NF==4'` filter before sort to skip `PRE` pseudo-directory lines [restore-from-dump.sh:27] ✅ fixed
- [x] [Review][Defer] fstab not updated after snapshot restore — new volume mounted via `mount ${VOLUME_DEVICE} ${DATA_DIR}` but `/etc/fstab` is not updated; on next reboot the new volume will not auto-mount if the original fstab entry used the old volume's UUID. [restore-from-snapshot.sh:62] — deferred, beyond Task 2 spec scope
- [x] [Review][Defer] DOMAIN sourced from .env but never referenced in the script — minor spec completeness gap (Task 2 subtask says "Source .env for DOMAIN"), not functional. [restore-from-snapshot.sh:19] — deferred, not functional
- [x] [Review][Defer] App + DB left in partial state on mid-restore failure — no auto-recovery trap; by design per spec (spec explicitly says "do NOT rm in a trap", operator must manually intervene). [restore-from-dump.sh:90] — deferred, intentional per spec NFR
- [x] [Review][Defer] /dev/sdb hardcoded with no filesystem UUID verification — per spec; operator is required to confirm correct attachment before pressing ENTER at the prompt. [restore-from-snapshot.sh:23] — deferred, per spec; documented in manual step instructions
