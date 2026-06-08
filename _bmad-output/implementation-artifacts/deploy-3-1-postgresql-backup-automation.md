# Story Deploy-3.1: PostgreSQL Backup Automation

Status: done

## Story

As a developer responsible for production data,
I want the Hetzner Volume snapshotted daily and `pg_dump` run every 6 hours with output pushed to Hetzner Object Storage,
so that data can be recovered to within 6 hours of any failure without manual intervention to trigger the backups.

## Acceptance Criteria

**AC-1: Daily volume snapshot automation**
- Given the daily snapshot automation is configured
- When 24 hours pass on the Node
- Then at least one Hetzner Volume snapshot is available — created automatically via the Hetzner API, not triggered manually
- And the snapshot automation is defined in version-controlled configuration (IaC or cron), not applied manually in the Hetzner UI

**AC-2: 6-hourly pg_dump to Hetzner Object Storage**
- Given the 6-hourly pg_dump automation is configured
- When 6 hours pass
- Then at least one `pg_dump` archive is available in Hetzner Object Storage — not stored on the Node's local disk
- And at least one dump is available at any point within the past 6 hours, satisfying the RPO < 6-hour target

**AC-3: Dump presence confirmed in Object Storage**
- Given the backup automation has run
- When the Object Storage bucket is inspected
- Then dump files are present and their timestamps confirm the 6-hour cadence is being met

---

## Tasks / Subtasks

- [x] Task 1: Create `deploy/backup/pg-backup.sh` — pg_dump + upload to HOS (AC-2, AC-3)
  - [x] Source `/opt/skillars/.env` for credentials
  - [x] Run `pg_dump` via `docker exec` into the running `postgres` container
  - [x] Compress dump with gzip, filename includes UTC timestamp
  - [x] Upload to Hetzner Object Storage using `aws` CLI with `--endpoint-url`
  - [x] Remove local temp file after successful upload
  - [x] Exit non-zero on any failure (set -euo pipefail)
  - [x] Script is executable (`chmod +x`) and idempotent (safe to run repeatedly)

- [x] Task 2: Create `deploy/backup/volume-snapshot.sh` — Hetzner Volume snapshot via API (AC-1)
  - [x] Source `/opt/skillars/.env` for `HCLOUD_TOKEN` and `HETZNER_VOLUME_ID`
  - [x] Call Hetzner Cloud API with `curl` to create a volume snapshot
  - [x] Label snapshot with date: `description = "daily-YYYY-MM-DD"`
  - [x] Validate API response for success (HTTP 201)
  - [x] Script is executable and idempotent

- [x] Task 3: Create `deploy/backup/install-crons.sh` — installs cron entries on the Node (AC-1, AC-2)
  - [x] Install pg-backup cron: every 6 hours (`0 */6 * * *`)
  - [x] Install volume-snapshot cron: daily at 02:00 UTC (`0 2 * * *`)
  - [x] Idempotent: only add entry if not already present (grep before adding)
  - [x] Write cron output to `/var/log/skillars-backup.log`
  - [x] Script is executable

- [x] Task 4: Update `deploy/provision.sh` (AC-1, AC-2)
  - [x] Add `awscli` to apt-get install line (Task 1 dependency)
  - [x] Add step at end: log instruction to run `deploy/backup/install-crons.sh` after provisioning
  - [x] Do NOT auto-run install-crons.sh from provision.sh (cron install requires .env secrets to be present first)

- [x] Task 5: Update `.env.example` with new backup variables (AC-1, AC-2)
  - [x] Add `HCLOUD_TOKEN`, `HETZNER_VOLUME_ID`
  - [x] Add `HOS_ACCESS_KEY`, `HOS_SECRET_KEY`, `HOS_BUCKET`, `HOS_ENDPOINT`
  - [x] Add `HOS_BACKUP_PREFIX` (optional key prefix, e.g. `pg-backups/`)
  - [x] Include generation/location comments consistent with existing .env.example style

- [x] Task 6: Update `docs/deployment/secrets-reference.md` (AC-1, AC-2)
  - [x] Add new "Server `.env`" rows for all 7 new variables
  - [x] Add note about running `install-crons.sh` after placing the updated `.env`

---

## Dev Notes

### Story scope: infrastructure scripts only — NO Java/Spring changes

**Files to CREATE:**
- `deploy/backup/pg-backup.sh`
- `deploy/backup/volume-snapshot.sh`
- `deploy/backup/install-crons.sh`

**Files to UPDATE:**
- `deploy/provision.sh` — add `awscli` to apt-get line + log hint to run install-crons.sh
- `.env.example` — 7 new variable entries
- `docs/deployment/secrets-reference.md` — 7 new rows in the Server `.env` table

**Files to NOT touch:**
- `docker-compose.yml` — no changes needed
- `.github/workflows/ci.yml` — no changes needed
- `.github/workflows/deploy.yml` — no changes needed
- Any Java source file

---

### Task 1 Detail: `deploy/backup/pg-backup.sh`

#### Environment variables consumed (all read from `/opt/skillars/.env`)

| Variable | Used for |
|---|---|
| `POSTGRES_USER` | `pg_dump -U` argument |
| `POSTGRES_DB` | `pg_dump` database name argument |
| `POSTGRES_PASSWORD` | `PGPASSWORD` env var passed to pg_dump |
| `HOS_ACCESS_KEY` | aws CLI credential |
| `HOS_SECRET_KEY` | aws CLI credential |
| `HOS_BUCKET` | S3 bucket name |
| `HOS_ENDPOINT` | S3-compatible endpoint URL (e.g. `https://s3.fsn1.hetzner.com`) |
| `HOS_BACKUP_PREFIX` | Key prefix in bucket (e.g. `pg-backups/`), default `pg-backups/` |

#### pg_dump command pattern

```bash
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP_FILE="/tmp/skillars-${TIMESTAMP}.sql.gz"

docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" postgres \
  pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" \
  | gzip > "${DUMP_FILE}"
```

- **CRITICAL**: Use `docker exec postgres` — NOT `docker compose exec postgres` (exec requires a TTY; `docker exec` does not when piping).
- Use `--no-password` flag is NOT needed since `PGPASSWORD` env var suppresses the password prompt.
- The container name is `postgres` — Docker Compose names it `skillars-postgres-1` by default. Use `docker ps --filter name=postgres` or `docker compose ps -q postgres` to get the actual container name/ID.
- Safer pattern: `CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres)` then `docker exec -e PGPASSWORD="..." "$CID" pg_dump ...`

#### Upload command pattern

```bash
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
aws s3 cp "${DUMP_FILE}" \
  "s3://${HOS_BUCKET}/${HOS_BACKUP_PREFIX:-pg-backups/}skillars-${TIMESTAMP}.sql.gz" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

rm -f "${DUMP_FILE}"
```

- Always set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` as env vars — do NOT use `aws configure` (that writes to disk and creates state).
- `--no-progress` suppresses upload progress bar in cron logs.
- Remove local temp file immediately after successful upload (`set -euo pipefail` ensures rm only runs if cp succeeded).

#### Full script skeleton

```bash
#!/usr/bin/env bash
set -euo pipefail

# Source production env — reads POSTGRES_*, HOS_* variables
# shellcheck source=/dev/null
. /opt/skillars/.env

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP_FILE="/tmp/skillars-${TIMESTAMP}.sql.gz"
PREFIX="${HOS_BACKUP_PREFIX:-pg-backups/}"

# Get the running postgres container ID
CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "[pg-backup][error] postgres container not running" >&2
  exit 1
fi

echo "[pg-backup] Running pg_dump..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  pg_dump -U "${POSTGRES_USER:-postgres}" "${POSTGRES_DB:-skillars}" \
  | gzip > "${DUMP_FILE}"

echo "[pg-backup] Uploading to s3://${HOS_BUCKET}/${PREFIX}skillars-${TIMESTAMP}.sql.gz"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "${DUMP_FILE}" \
  "s3://${HOS_BUCKET}/${PREFIX}skillars-${TIMESTAMP}.sql.gz" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

rm -f "${DUMP_FILE}"
echo "[pg-backup] Done. $(date -u)"
```

---

### Task 2 Detail: `deploy/backup/volume-snapshot.sh`

#### Environment variables consumed (all read from `/opt/skillars/.env`)

| Variable | Used for |
|---|---|
| `HCLOUD_TOKEN` | Bearer token for Hetzner Cloud API |
| `HETZNER_VOLUME_ID` | Numeric ID of the Hetzner Volume to snapshot |

#### Hetzner API call (no hcloud CLI needed — curl only)

```bash
DATE=$(date -u +%Y-%m-%d)
DESCRIPTION="daily-${DATE}"

RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"description\": \"${DESCRIPTION}\"}" \
  "https://api.hetzner.cloud/v1/volumes/${HETZNER_VOLUME_ID}/actions/create_snapshot")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$HTTP_CODE" -ne 201 ]; then
  echo "[volume-snapshot][error] API returned HTTP ${HTTP_CODE}: ${BODY}" >&2
  exit 1
fi

echo "[volume-snapshot] Snapshot created: ${DESCRIPTION}"
```

- HTTP 201 = snapshot creation action accepted.
- No hcloud CLI install required — curl is available on Ubuntu 22.04 by default.

---

### Task 3 Detail: `deploy/backup/install-crons.sh`

#### Cron entries to install

| Job | Schedule | Meaning |
|---|---|---|
| `pg-backup.sh` | `0 */6 * * *` | Every 6 hours (00:00, 06:00, 12:00, 18:00 UTC) |
| `volume-snapshot.sh` | `0 2 * * *` | Daily at 02:00 UTC |

#### Idempotent installation pattern

```bash
CRON_LINE="0 */6 * * * /opt/skillars/deploy/backup/pg-backup.sh >> /var/log/skillars-backup.log 2>&1"
if ! crontab -l 2>/dev/null | grep -qF "pg-backup.sh"; then
  (crontab -l 2>/dev/null; echo "$CRON_LINE") | crontab -
  echo "[install-crons] pg-backup cron installed."
else
  echo "[install-crons] pg-backup cron already present — skipping."
fi
```

- Same pattern for volume-snapshot.sh.
- Log both stdout and stderr to `/var/log/skillars-backup.log`.
- Scripts are called by absolute path `/opt/skillars/deploy/backup/` — the repo is cloned to `/opt/skillars/` on the Node (confirmed by docker-compose.yml volume mounts referencing `./deploy/...`).

---

### Task 4 Detail: `deploy/provision.sh` changes

**Change 1 — Add awscli to apt-get install line (step 1 of provision.sh):**

```bash
# BEFORE:
apt-get install -y curl git unzip fail2ban ufw ca-certificates gnupg lsb-release

# AFTER:
apt-get install -y curl git unzip fail2ban ufw ca-certificates gnupg lsb-release awscli
```

**Change 2 — Add log hint at the end of provision.sh (after the "Provisioning complete" block):**

```bash
log "   4. (After placing .env) Install backup crons: bash ${DEPLOY_ROOT}/deploy/backup/install-crons.sh"
```

---

### Task 5 Detail: `.env.example` additions

Add a new section after the LGTM block:

```bash
# --- Backup: Hetzner Object Storage ---
# Hetzner Object Storage credentials (S3-compatible)
# Create a bucket in Hetzner Cloud Console → Object Storage
HOS_ACCESS_KEY=change-me
HOS_SECRET_KEY=change-me
# Bucket name (no protocol prefix)
HOS_BUCKET=skillars-backups
# Region endpoint — find in Hetzner Object Storage console (e.g. fsn1, nbg1, hel1)
HOS_ENDPOINT=https://s3.fsn1.hetzner.com
# Key prefix in the bucket — trailing slash required
HOS_BACKUP_PREFIX=pg-backups/

# --- Backup: Hetzner Cloud API ---
# API token for creating volume snapshots (read/write scope required)
HCLOUD_TOKEN=change-me
# Numeric ID of the Hetzner Volume attached to this Node
# Find in Hetzner Cloud Console → Volumes → click the volume → copy the ID from the URL
HETZNER_VOLUME_ID=12345678
```

---

### Task 6 Detail: `docs/deployment/secrets-reference.md` additions

Add 7 rows to the **Server `.env`** table:

| Variable | Format | How to obtain or generate |
|---|---|---|
| `HCLOUD_TOKEN` | Hetzner Cloud API token (64-char hex) | Hetzner Cloud Console → Security → API Tokens → Generate API Token (Read/Write); used only by `volume-snapshot.sh` on the Node |
| `HETZNER_VOLUME_ID` | Integer, e.g. `12345678` | Hetzner Cloud Console → Volumes → click the volume → the numeric ID appears in the URL (`/volumes/<id>`) |
| `HOS_ACCESS_KEY` | String | Hetzner Cloud Console → Object Storage → your bucket → Access Keys → Create access key; copy Access Key ID |
| `HOS_SECRET_KEY` | String | Same creation flow as `HOS_ACCESS_KEY`; copy Secret Access Key (shown once) |
| `HOS_BUCKET` | String, e.g. `skillars-backups` | Create a private bucket in Hetzner Cloud Console → Object Storage; use the exact bucket name here |
| `HOS_ENDPOINT` | HTTPS URL, e.g. `https://s3.fsn1.hetzner.com` | Hetzner Object Storage endpoint for your datacenter region (fsn1 = Falkenstein, nbg1 = Nuremberg, hel1 = Helsinki) — visible in the bucket details page |
| `HOS_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Choose a key prefix to organize backups within the bucket; default `pg-backups/` |

Also add a post-table note:
```
> **After placing the updated `.env`**, run the backup cron installer:
> ```bash
> bash /opt/skillars/deploy/backup/install-crons.sh
> ```
> This is required once per Node. Re-running is safe (idempotent).
```

---

### Infrastructure Context

**Server root:** `/opt/skillars` — all scripts run from here; repo is cloned to this directory (confirmed by `./deploy/lgtm/...` volume mounts in docker-compose.yml)

**PostgreSQL container:** `postgres:17-alpine`, data at `/opt/skillars/data/postgres` (Hetzner Volume mount)

**Hetzner Volume:** `/dev/sdb` mounted at `/opt/skillars/data` (from provision.sh line 145)

**Docker Compose service name:** `postgres` (used in `docker compose ps -q postgres`)

**Node OS:** Ubuntu 22.04 LTS (from provision.sh comment)

**awscli package name on Ubuntu 22.04:** `awscli` (version 1.x from Ubuntu repos) — sufficient for `s3 cp`; no need for aws-cli v2 for this use case

**Hetzner Object Storage:**
- S3-compatible (AWS S3 API)
- Endpoint format: `https://s3.<region>.hetzner.com` (e.g., `https://s3.fsn1.hetzner.com`)
- Bucket URL: `https://<bucket>.s3.<region>.hetzner.com`
- Auth: standard AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY — set as env vars, NOT via `aws configure`

**Hetzner Cloud API for volume snapshots:**
- Endpoint: `POST https://api.hetzner.cloud/v1/volumes/{id}/actions/create_snapshot`
- Auth: `Authorization: Bearer <HCLOUD_TOKEN>`
- Success response: HTTP 201

**Cron log:** `/var/log/skillars-backup.log` — both scripts append here via cron redirect `>> /var/log/skillars-backup.log 2>&1`

---

### Previous Story Intelligence (deploy-2-3 learnings)

- **Server path confirmed:** `/opt/skillars` for all file operations
- **Secrets pattern:** New operational secrets go in `.env` (server-side), not GitHub Actions secrets — backup scripts run on-Node
- **No .env editing with text editors:** Use sed or env-var sourcing; never open in vi/nano unless necessary — the `.env` file is mode 600 and sourcing it is the clean pattern
- **Docker compose run location:** Commands using `docker compose` must be run from `/opt/skillars` or pass `-f /opt/skillars/docker-compose.yml`
- **Container name pattern:** Compose names containers `skillars-<service>-1`; prefer `docker compose ps -q <service>` over hardcoding container names
- **Idempotency is required (NFR-3):** All scripts must check-before-act; running provision.sh or install-crons.sh a second time must not break the system

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 3.1 — PostgreSQL Backup Automation]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-14 — Daily volume snapshot via Hetzner API, automated, version-controlled]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-15 — 6-hourly pg_dump to Hetzner Object Storage, not local disk]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-3 — Scripts must be idempotent]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-7 — RTO < 4 hours]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-8 — RPO < 24h via volume snapshot; < 6h via pg_dump]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Restore script: FR-16 requires executable script"]
- [Source: docker-compose.yml — postgres service: image postgres:17-alpine, volume /opt/skillars/data/postgres, healthcheck uses pg_isready]
- [Source: deploy/provision.sh — server root /opt/skillars, volume device /dev/sdb mounted at /opt/skillars/data, apt-get install line, Ubuntu 22.04]
- [Source: .env.example — POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD variable names]
- [Source: docs/deployment/secrets-reference.md — Server .env table format, GitHub Actions secrets table format]
- [Source: _bmad-output/implementation-artifacts/deploy-2-3-*.md#Dev Notes — /opt/skillars server path confirmed, docker compose ps -q pattern]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No debug issues encountered._

### Completion Notes List

- Implemented all 6 tasks as pure infrastructure scripts — zero Java/Spring changes.
- `pg-backup.sh`: uses `docker compose ps -q postgres` to get container ID (avoids hardcoding `skillars-postgres-1`), pipes pg_dump through gzip, uploads to HOS via aws s3 cp with env-var credentials, removes temp file on success. `set -euo pipefail` ensures non-zero exit on any failure.
- `volume-snapshot.sh`: pure curl call to Hetzner Cloud API; validates HTTP 201; labels snapshot `daily-YYYY-MM-DD`.
- `install-crons.sh`: idempotent — greps existing crontab before adding each entry; both jobs redirect stdout+stderr to `/var/log/skillars-backup.log`.
- `provision.sh`: `awscli` added to apt-get line; log hint for running install-crons.sh added at end as step 4 (not executed automatically).
- `.env.example`: 7 new variables in two clearly-labelled sections (HOS and Hetzner Cloud API), matching existing comment style.
- `secrets-reference.md`: 7 new rows added to Server `.env` table; post-table note added instructing one-time `install-crons.sh` run.
- Full Java test suite: 0 failures, 0 errors — no regressions.

### File List

- deploy/backup/pg-backup.sh (created)
- deploy/backup/volume-snapshot.sh (created)
- deploy/backup/install-crons.sh (created)
- deploy/provision.sh (modified)
- .env.example (modified)
- docs/deployment/secrets-reference.md (modified)
- _bmad-output/implementation-artifacts/deploy-3-1-postgresql-backup-automation.md (story file)
- _bmad-output/implementation-artifacts/sprint-status.yaml (status update)

### Change Log

- 2026-06-04: Implemented PostgreSQL Backup Automation (Deploy-3.1) — created 3 backup scripts, updated provision.sh with awscli dependency, added 7 new variables to .env.example and secrets-reference.md.

### Review Findings

- [x] [Review][Decision] Trap removes DUMP_FILE on all exits including upload failure — resolved: removed `trap` line; dump is preserved on upload failure for inspection; explicit `rm -f` at end of script handles success-path cleanup. [`deploy/backup/pg-backup.sh`]

- [x] [Review][Patch] pg_dump pipeline may produce a truncated/zero-byte dump that is silently uploaded to S3 — fixed: added `[ -s "$DUMP_FILE" ]` guard before upload; empty/missing dump aborts with non-zero exit. [`deploy/backup/pg-backup.sh:26-29`]

- [x] [Review][Patch] HOS_BACKUP_PREFIX without a trailing slash produces a malformed S3 object key — fixed: added `PREFIX="${PREFIX%/}/"` normalisation after default assignment. [`deploy/backup/pg-backup.sh:13`]

- [x] [Review][Patch] curl transport failure in volume-snapshot.sh loses context and produces a misleading arithmetic error — fixed: added `|| { echo "...curl failed"; exit 1; }` on the curl call; added empty-string guard before `-ne 201` check. [`deploy/backup/volume-snapshot.sh:17-25`]

- [x] [Review][Defer] PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) [`deploy/backup/pg-backup.sh:22`] — deferred, pre-existing/spec-prescribed pattern
- [x] [Review][Defer] Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — deferred, project-wide pattern, not introduced by this story
- [x] [Review][Defer] No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded — deferred, out of scope for this story
- [x] [Review][Defer] install-crons.sh installs cron for the invoking user (typically root) with no enforcement — deferred, pre-existing design decision
- [x] [Review][Defer] No upload integrity check (checksum / ETag verification) — deferred, out of scope for this story
- [x] [Review][Defer] No handling for Hetzner API HTTP 409 (action in progress) or 422 (quota exhausted) — deferred, out of scope for this story
- [x] [Review][Defer] awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — deferred, spec-approved as sufficient
