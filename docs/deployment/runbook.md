# Operational Runbook

This guide covers step-by-step remediation for three production failure scenarios: disk exhaustion,
PostgreSQL service down, and Redis OOM or container restart loop. Each scenario includes detection,
remediation, and verification. A developer can resolve each scenario by following this runbook alone.

---

## Scenario 1: Disk Exhaustion on the Node

### Detection

- `DiskDataVolumeHigh` alert fires: data volume `/opt/skillars/data` exceeds 80% used
- `DiskRootHigh` alert fires: root disk `/` exceeds 80% used
- Or: application writes fail with "no space left on device" in logs

```bash
# Confirm disk usage:
df -h
# Look for / or /opt/skillars/data showing > 80% Use%

# Find top consumers on the data volume:
du -sh /opt/skillars/data/*
# Typical large consumers: postgres/, loki/, prometheus/, grafana/

# Find top consumers on root (usually Docker image layers):
du -sh /var/lib/docker/overlay2/* 2>/dev/null | sort -h | tail -20
```

### Remediation

**For root disk (`/`) — usually Docker layer accumulation:**

```bash
# Remove unused Docker images (safe — only removes untagged or unreferenced images):
docker image prune -a -f

# Remove stopped containers:
docker container prune -f

# Remove build cache:
docker builder prune -f

# Remove unused anonymous volumes — explicitly exclude named volumes to protect redis-data:
# First confirm the redis-data volume exists before pruning:
docker volume ls | grep redis-data
# Then prune only anonymous volumes (those with no name):
docker volume ls -q --filter "dangling=true" | grep -v "^skillars_redis-data$" | xargs -r docker volume rm
```

> **CAUTION:** Do NOT run `docker volume prune` without explicit safeguards. The `skillars_redis-data` named volume stores Redis AOF persistence data. Deleting it invalidates all active user sessions and distributed locks. The `--filter "label!=..."` syntax requires Docker Engine 20.10+ and may silently have no effect on older versions. The `docker volume ls | xargs` approach above is safer.

**For data volume (`/opt/skillars/data`) — usually Loki or Prometheus accumulation:**

```bash
# Check Loki storage size:
du -sh /opt/skillars/data/loki/

# Check Prometheus storage size:
du -sh /opt/skillars/data/prometheus/

# If Loki is oversized (retention is 30 days configured in loki.yml):
# Loki self-prunes per its retention configuration. Restart to trigger compaction:
docker compose restart loki
# Wait 5 minutes, then re-check size.

# If Prometheus is oversized (retention is 15 days):
# Prometheus self-prunes on schedule. Restart to trigger immediate compaction:
docker compose restart prometheus
```

### Verification

```bash
df -h
# Expected: both / and /opt/skillars/data below 80% Use%

# Confirm all services are still healthy after cleanup:
docker compose ps
# All services should show: health: healthy or state: Up
```

---

## Scenario 2: PostgreSQL Service Down

### Detection

- `AppDown` alert fires: Spring Boot cannot connect to the database — its health endpoint fails — Prometheus scrape returns no data for more than 1 minute
- Or: application logs contain `Connection refused` or `FATAL: the database system is starting up`
- Or: `docker compose ps postgres` shows state `Exit` or `Restarting`

```bash
# Check postgres container state:
docker compose ps postgres

# Check postgres logs for the failure reason:
docker compose logs --tail=100 postgres

# Check if PostgreSQL was OOM-killed by the kernel:
dmesg | grep -i oom | tail -10

# Check if disk space caused the failure:
df -h /opt/skillars/data
```

Common causes:

- Container was OOM-killed by the kernel (kernel logs will show the kill event)
- Data volume ran out of disk space (postgres cannot write WAL files)
- Container health check is failing due to a startup error

### Remediation

**If postgres exited cleanly and can be restarted:**

```bash
docker compose start postgres

# Wait 15 seconds for postgres to start accepting connections, then verify:
docker compose ps postgres
# Expected: health: healthy
```

**If postgres is in a restart loop (state shows `Restarting`):**

```bash
# Read the logs to identify the error BEFORE attempting a restart:
docker compose logs --tail=200 postgres

# If the error is disk-related, free space first (see Disk Exhaustion scenario), then restart:
docker compose restart postgres

# If the error is an OOM kill, postgres will restart automatically (restart: unless-stopped).
# Wait 30 seconds and check state — postgres should recover on its own after a kernel OOM kill.
```

**If the app container also exited during the postgres outage:**

```bash
# After postgres is healthy, restart the app:
docker compose restart app
```

### Verification

```bash
# Confirm postgres is healthy:
docker compose ps postgres
# Expected: health: healthy

# Wait ~30 seconds for the app to reconnect to postgres, then check app health:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

> **Note:** The management port 8367 is not exposed to the Node host. Always use `docker exec` — do not attempt to curl port 8367 from the Node directly.

---

## Scenario 3: Redis OOM / Container Restart Loop

### Detection

- `MemoryPressureHigh` alert fires AND the Redis container is restarting
- Or: `docker compose ps redis` shows state `Restarting`
- Or: application logs contain `NOAUTH`, `ERR max number of clients reached`, or `Connection refused` to Redis
- Or: `docker compose logs redis --tail=50` shows `OOM command not allowed` or the container keeps cycling

**Context:** The Redis container has a 256m Docker memory limit. Redis uses `--appendonly yes` with AOF persistence to the `redis-data` named volume. When the Docker memory limit is exceeded, the kernel OOM-kills the Redis process and Docker restarts it automatically (`restart: unless-stopped`).

```bash
# Confirm Redis state and recent logs:
docker compose ps redis
docker compose logs --tail=50 redis

# Check if Redis is being OOM-killed by the kernel:
dmesg | grep -i oom | tail -10
```

### Remediation

**If Redis is in a restart loop and the AOF is intact (most common case):**

```bash
# Docker restarts Redis automatically. The restart loop typically self-resolves as Redis loads the AOF.
# Wait 60 seconds and check:
docker compose ps redis
# Expected: state Up and health: healthy
```

**If Redis cannot start due to AOF corruption:**

```bash
docker compose stop redis

# WARNING: The following CLEARS all Redis data.
# All session tokens and distributed locks will be invalidated.
# All active user sessions will end — users must re-login.
docker volume inspect redis-data  # confirm this is the correct volume before proceeding

docker run --rm -v skillars_redis-data:/data alpine \
  sh -c "ls -la /data && rm -f /data/appendonly.aof /data/dump.rdb"

docker compose start redis
```

> **CAUTION:** Only clear the AOF if Redis cannot start and you have confirmed the file is corrupt from the logs. Data loss is irreversible.

**If Redis keeps being OOM-killed and the AOF is not corrupt:**

```bash
# Check current Redis memory usage:
docker exec $(docker compose ps -q --status running redis | head -1) redis-cli info memory \
  | grep -E "used_memory_human|maxmemory_human"

# Flush the database to free memory (active user sessions will be invalidated):
docker exec $(docker compose ps -q --status running redis | head -1) redis-cli FLUSHDB

# Restart Redis:
docker compose restart redis
```

**If the node-level `MemoryPressureHigh` alert is the root cause (not just Redis):**

```bash
# Identify which container is consuming the most memory:
docker stats --no-stream
# Find the container with the highest MEM USAGE and follow its specific runbook scenario
```

### Verification

```bash
# Confirm Redis is running and responding:
docker compose ps redis

docker exec $(docker compose ps -q --status running redis | head -1) redis-cli ping
# Expected: PONG

# Confirm the app has reconnected to Redis:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

> **Note:** If the app container stopped during the Redis outage, restart it first: `docker compose restart app`. Wait ~30 seconds before checking health.
