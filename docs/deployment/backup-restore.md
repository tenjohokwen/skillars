# Backup and Restore Guide

This guide covers restoring the Skillars production database and data volume from backup. Use it when
data loss, corruption, or hardware failure requires recovery. For reverting a bad application deploy
(no data loss involved), use [`docs/deployment/rollback.md`](rollback.md) instead.

---

## When to Use Which Restore Path

| Situation | Use |
|---|---|
| Node hardware failure, volume corruption, or catastrophic data loss | Volume snapshot restore (Section B) |
| Database corruption, accidental data deletion, application bug | pg_dump restore (Section A) |
| Quarterly restore drill | Either path — record result in `deploy/backup/drill-log.md` |

Both restore scripts require SSH access to the Node as root and must be run from `/opt/skillars`.

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

## Section B: Restore from Volume Snapshot

Script: `deploy/backup/restore-from-snapshot.sh`

> **CRITICAL:** This path restores ALL data on the Hetzner Volume — PostgreSQL, Loki, Prometheus, Grafana, and Tempo — to the state at the time the snapshot was taken. Any data written between the snapshot and the failure is permanently lost. The recovery point objective (RPO) for snapshots is 24 hours.

This restore path requires a manual Hetzner Cloud Console step while the script waits. Ensure you have access to the Hetzner Console before starting.

Run as root on the Node:

```bash
sudo bash /opt/skillars/deploy/backup/restore-from-snapshot.sh
```

The script will:

1. Stop all Docker services (`docker compose down`)
2. Unmount the current data volume (`/opt/skillars/data` at `/dev/sdb`)
3. **Pause** and display manual instructions for Hetzner Console

At the pause, complete these steps in the Hetzner Cloud Console:

> **Before proceeding:** Verify the target snapshot's creation timestamp in the Hetzner Cloud Console to confirm it is the correct restore point. Creating a Volume from the wrong snapshot permanently replaces all data with no recovery path.

```
1. Go to Hetzner Cloud Console → Volumes
2. Detach the current volume from the server (if still attached)
3. Go to the target snapshot (Cloud Console → Volumes → Snapshots)
4. Confirm the snapshot timestamp matches the intended recovery point
5. Create a new Volume from that snapshot
6. Attach the new Volume to the server as /dev/sdb
7. Wait for Hetzner to confirm the attachment is complete
8. Press ENTER in the terminal to continue the script
```

After you press ENTER, the script continues automatically:

4. Mounts `/dev/sdb` at `/opt/skillars/data`
5. Restores subdirectory ownership for container users:
   - `prometheus/`: `65534:65534`
   - `loki/`, `tempo/`: `10001:10001`
   - `grafana/`: `472:472`
6. Starts all services (`docker compose up -d`)
7. Waits up to 120 seconds for the app container health check to reach `healthy`

---

## Section C: Data Integrity Verification

The pg_dump restore script (Section A) performs an automatic integrity check and reports the table count. For the volume snapshot restore (Section B), verify manually after the script completes:

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

After a volume snapshot restore, all services start automatically when the script completes. If any service failed to start or you need to restart manually:

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

Include: date, path used (dump or snapshot), environment (must be non-production), outcome (pass/fail), and RTO achieved.

If `deploy/backup/drill-log.md` does not exist yet, create it with the header row above before recording your first drill.
