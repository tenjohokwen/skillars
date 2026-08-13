# Backup and Restore Guide

This guide covers restoring the Skillars production database and data volume from backup. Use it when
data loss, corruption, or hardware failure requires recovery. For reverting a bad application deploy
(no data loss involved), use [`docs/deployment/rollback.md`](rollback.md) instead.

---

## When to Use Which Restore Path

| Situation | Use |
|---|---|
| Node hardware failure, volume corruption, or catastrophic data loss | Volume backup restore (Section B) |
| Database corruption, accidental data deletion, application bug | pg_dump restore (Section A) |
| Quarterly restore drill | Either path — record result in `deploy/backup/drill-log.md` |

Both restore scripts require SSH access to the Node as root and must be run from `/opt/skillars`.

> **File-level volume backup runs daily.** `deploy/backup/volume-backup.sh` archives everything
> under `/opt/skillars/data` — Loki, Prometheus, Grafana, Tempo, Redis AOF, `acme.json` — to Hetzner
> Object Storage every day at 02:00 UTC, excluding `postgres/` (already covered by `pg-backup.sh`'s
> pg_dump stream, Section A). It replaces the earlier `volume-snapshot.sh` mechanism, which called a
> Hetzner Cloud API endpoint that does not exist and never produced a working backup — see
> `deferred-work.md`, `skillars-uat-3` D1.

---

## Retention

Both backup streams are pruned daily at **03:30 UTC** by `deploy/backup/prune-backups.sh`, installed
by `install-crons.sh`. The schedule is clear of both producers (`pg-backup.sh` at `0 */6`,
`volume-backup.sh` at `0 2`), so retention never races an upload.

| Stream | Variable | Default | Effect |
|---|---|---|---|
| PostgreSQL dumps in Object Storage | `BACKUP_RETENTION_DAYS` | `14` | At the 6-hourly cadence this keeps ~56 dumps |
| — minimum kept regardless of age | `BACKUP_RETENTION_MIN_KEEP` | `8` | Safety floor — see below |
| Volume backups in Object Storage | `VOLUME_BACKUP_RETENTION_DAYS` | `14` | At the daily cadence this keeps ~14 backups |
| — minimum kept regardless of age | `VOLUME_BACKUP_RETENTION_MIN_KEEP` | `4` | Safety floor — see below |

**Safety rails.** The failure mode of a pruner that mis-parses a listing is an emptied backup
bucket, so:

- The newest `BACKUP_RETENTION_MIN_KEEP` / `VOLUME_BACKUP_RETENTION_MIN_KEEP` objects are retained
  **unconditionally**, whatever the age computation decides. A clock skew or a bad cutoff cannot
  get past that floor.
- An **empty listing is a fatal error**, not "nothing to prune" — a changed prefix and an empty
  bucket look identical from the pruner's side, and only one of them is benign.
- A key whose `%Y%m%dT%H%M%SZ` stamp cannot be parsed is skipped and reported, never deleted. Age
  comes from that stamp, not from the object's mtime.
- A failure in one half does not skip the other; the exit code reflects both.

**Run the first production prune as a dry run.** It prints exactly what it would delete and exits 0
without deleting anything:

```bash
sudo /opt/skillars/deploy/backup/prune-backups.sh --dry-run
```

---

## Section A: Restore from pg_dump

Script: `deploy/backup/restore-from-dump.sh`

Run as root on the Node from `/opt/skillars`:

```bash
cd /opt/skillars

# Restore the latest dump:
sudo bash deploy/backup/restore-from-dump.sh latest

# Or restore a specific dump by S3 object key:
sudo bash deploy/backup/restore-from-dump.sh pg-backups/skillars-20260603T060000Z.sql.gz
```

The script performs these steps automatically:

1. Loads `/opt/skillars/.env` for `HOS_*` and `POSTGRES_*` environment variables
2. Lists objects in the Hetzner Object Storage bucket to find the latest dump (when `latest` is specified)
3. Checks `/tmp` has enough free space before downloading
4. Downloads the dump to `/tmp/skillars-restore-<timestamp>.sql.gz`
5. Validates gzip integrity before touching the database
6. Stops the `app` service (`docker compose stop app`)
7. Drops and recreates the target database inside the running `postgres` container
8. Pipes the decompressed dump through `docker exec -i` into psql
9. Integrity check: counts public tables — exits with error if count is less than 1
10. Starts the `app` service (`docker compose start app`)
11. Waits up to 90 seconds for the app container health check to reach `healthy`
12. Deletes the temporary dump file

> **If the script exits with an error after stopping the `app` service:** The app remains stopped and the database may be empty. Bring the app back online immediately: `docker compose start app`. Then either investigate the failure output or re-run the script with a known-good dump.

**Expected output on success:**

```
[restore-dump] Integrity check: N public tables found.
[restore-dump] Waiting for app health (up to 90s)...
[restore-dump] Restore complete. Thu Jun  5 06:00:00 UTC 2026
```

**External verification after restore:**

```bash
# Load environment variables if not already in your shell:
source /opt/skillars/.env

# Verify the application is reachable externally:
curl -s https://${DOMAIN}/actuator/health | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['status'])"
# Expected: UP
```

> **Note:** The management port 8367 is not exposed to the host. Use `docker exec` for internal health checks — see the verification command in Section D. The external `/actuator/health` URL routes through Traefik.

---

## Section B: Restore from Volume Backup

Script: `deploy/backup/restore-from-volume-backup.sh`

> **CRITICAL:** This path restores everything on the volume backup — Loki, Prometheus, Grafana, Tempo, Redis AOF, and `acme.json` — to the state at the time the backup archive was created. It does **not** restore PostgreSQL (excluded from the archive on purpose; use Section A for that). Any data written between the backup and the failure is permanently lost. The recovery point objective (RPO) for volume backups is 24 hours.

Run as root on the Node:

```bash
cd /opt/skillars

# Restore the latest volume backup:
sudo bash deploy/backup/restore-from-volume-backup.sh

# Or restore a specific backup by S3 object key:
sudo bash deploy/backup/restore-from-volume-backup.sh volume-backups/skillars-volume-20260813T020000Z.tar.gz
```

The script performs these steps automatically:

1. Loads `/opt/skillars/.env` for `HOS_*` environment variables
2. Prompts for confirmation before overwriting non-postgres data under `/opt/skillars/data`
3. Lists objects in the Hetzner Object Storage bucket to find the latest backup (when no key is given)
4. Stops all Docker services (`docker compose down`)
5. Downloads the archive to `/tmp` and extracts it over `/opt/skillars/data`
6. Restores subdirectory ownership for container users:
   - `redis/`: `999:1000`
   - `prometheus/`: `65534:65534`
   - `loki/`, `tempo/`: `10001:10001`
   - `grafana/`: `472:472`
   - `traefik/`: `700` (directory), `traefik/acme.json`: `600`
7. Starts all services (`docker compose up -d`)

---

## Section C: Data Integrity Verification

The pg_dump restore script (Section A) performs an automatic integrity check and reports the table count. For the volume backup restore (Section B), verify manually after the script completes:

```bash
# Load environment variables:
source /opt/skillars/.env

# Check all services are running:
docker compose ps
# Expected: all services show health: healthy or state: Up

# Check the PostgreSQL table count manually:
CID=$(docker compose ps -q --status running postgres | head -1)
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -t -c "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname='public';"
# Expected: a non-zero integer

# Check application health from inside the container:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

---

## Section D: Bringing the Application Back Online Post-Restore

After a volume backup restore, all services start automatically when the script completes. If any service failed to start or you need to restart manually:

```bash
cd /opt/skillars

# Start any stopped service:
docker compose start <service>

# Or restart everything:
docker compose up -d

# Wait ~30 seconds, then verify app health:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

Traefik routes traffic to the app container automatically once its health check passes — no additional networking steps are required.

---

## Quarterly Restore Drill Reminder

Run a restore drill each quarter against a non-production environment. After every drill, record the result in `deploy/backup/drill-log.md`:

| Date | Environment | Method | Result | RTO Achieved | Notes |
|---|---|---|---|---|---|

Include: date, path used (dump or volume backup), environment (must be non-production), outcome (pass/fail), and RTO achieved.

If `deploy/backup/drill-log.md` does not exist yet, create it with the header row above before recording your first drill.
