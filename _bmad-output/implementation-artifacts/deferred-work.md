## Deferred from: code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11)
- `IllegalStateException` → HTTP 409 semantically wrong for missing config keys (should be 500 for misconfiguration); pre-existing ApiAdvice mapping not introduced by this story [ApiAdvice.java:existing illegalStateExceptionHandler]
- `refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409 instead of serving stale data during DB outage; acceptable design choice for this scope [ConfigService.java]
- Scheduled refresh + lazy TTL `ensureFresh()` can both fire near-simultaneously, causing ~2x DB polls per TTL period; minor efficiency concern, spec-designed dual-refresh pattern [ConfigService.java]
- IT test fixture hardcodes bcrypt hash for test user seed SQL; follows existing project IT test pattern [ConfigResourceIT.java:setUp]

## Deferred from: code review of deploy-3-4-operational-documentation-suite (2026-06-05)
- Integrity check (table count ≥ 1) is trivially weak — a partially-loaded dump that created only one table passes; pre-existing restore-from-dump.sh limitation [docs/deployment/backup-restore.md]
- DROP DATABASE may fail if services other than `app` hold open DB connections — script stops only `app` before drop; pre-existing script limitation [docs/deployment/backup-restore.md]
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md]
- /tmp space check in restore-from-dump.sh validates compressed dump size only — decompressed SQL is typically 5-10x larger; mid-restore /tmp exhaustion possible; pre-existing script gap [docs/deployment/backup-restore.md]
- APP_CID capture races container registration immediately after `docker compose start app` — health-wait loop can time out on a healthy app; pre-existing script race condition [docs/deployment/backup-restore.md]
- WebhookPermanentFailure Admin API re-trigger has no endpoint or auth reference — Admin API not defined in this story's scope; needs dedicated API documentation [docs/deployment/monitoring.md]
- CallbackRateZero public callback endpoint undocumented — application-specific URL not defined in deployment docs; needs a secrets-reference or application guide entry [docs/deployment/monitoring.md]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert
- CallbackFailureRatioHigh divide-by-zero on zero callback traffic — pre-existing rule divides rate by rate with no zero-denominator guard; fires spuriously during quiet periods [deploy/lgtm/alerts.yml]
- node_exporter network isolation — shares `skillars-internal` network with app containers; port 9100 reachable by any compromised container; FR-9 required this topology, changing it is out of scope
- Empty notification vars cause silent delivery failure — if `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` are empty (compose defaults), Grafana provisions the contact point but notifications silently fail; intentional spec design tradeoff (`${VAR:-}`)
- DiskDataVolumeHigh requires Hetzner Volume mounted at `/opt/skillars/data` — if volume not provisioned, no metrics series exists and alert never fires; infrastructure provisioning dependency

## Deferred from: code review of deploy-3-2-scripted-restore-process (2026-06-04)
- fstab not updated after snapshot restore — new volume mounted with `mount /dev/sdb ...` but no fstab update; volume won't auto-mount on reboot if volume UUID changed. Beyond Task 2 scope [restore-from-snapshot.sh:62]
- DOMAIN sourced from .env in restore-from-snapshot.sh but never used — spec says "source .env for DOMAIN" but no reference to DOMAIN in script [restore-from-snapshot.sh:19]
- App and DB left in partial state on mid-restore failure — no recovery trap by design; operator must manually restart the app service and investigate [restore-from-dump.sh:90]
- /dev/sdb hardcoded, no filesystem UUID verification — per spec; operator confirms correct attachment at the Hetzner Console ENTER prompt [restore-from-snapshot.sh:23]

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded; add lifecycle rules or a rotation script in a future backup hardening story
- install-crons.sh installs cron for the invoking user with no enforcement — typically root; document the expected user or add a guard in a future hardening pass
- No upload integrity check (checksum / ETag verification after aws s3 cp) — out of scope for this story
- No handling for Hetzner API HTTP 409 (action in progress) or 422 (quota exhausted) in volume-snapshot.sh — out of scope for this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-3-deployment-rollback-documentation (2026-06-04)
- No pre-deploy GHCR image existence check — no step to verify the image tag exists in GHCR before triggering deploy; typo causes mid-run failure after 2–5 min wait.
- No GHCR auth failure handling — no guidance if `docker login` fails (expired PAT, wrong token scope) before `docker compose pull`.
- Step 5 health check retry loop is manual — "retry after 10 seconds" gives no command to re-run; a simple loop would be deterministic [rollback.md:139–142].
- Partial pull failure leaves .env inconsistent — if `docker compose pull` times out, .env holds new tag but image not available; no recovery path documented [rollback.md:106].
- 60s smoke test window vs. `start_period: 60s` — docker-compose.yml sets start_period to 60s; slow JVM startup can exhaust all 12 smoke test retries during startup grace period, triggering false Auto-Revert [deploy.yml + docker-compose.yml].
- Auto-Revert fails if previous image deleted from GHCR — GHCR retention policies can evict old images; Auto-Revert pull then fails with `outcome=failed` and production may be in unknown state.
- `SSH_KNOWN_HOST` empty or multi-line edge cases — empty secret bypasses known-host verification; multi-line `ssh-keyscan` output is valid but undocumented [deploy.yml:27].
- Container name `skillars-app-1` hardcoded in expected output without explaining Docker Compose naming convention (project-service-index) [rollback.md:113].
- No explicit guidance if `docker compose pull app` fails mid-execution — the `&&` chain halts correctly, but no next-step is documented for auth errors, network timeout, or image-not-found.

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-2-1-automated-ci-build-pipeline (2026-06-04)
- No PR trigger — a broken `Dockerfile` or workflow is only discovered after merge to `main`; no pre-merge build validation. Add a `pull_request:` trigger (build-only, no push) in a future CI hardening pass.
- Actions pinned by floating tag not SHA digest — `checkout@v4`, `login-action@v3`, `build-push-action@v6` are mutable tags; a force-push to any tag is a supply-chain attack vector. Pin to immutable commit SHAs.
- ~~No `HEALTHCHECK` in `Dockerfile`~~ — **RESOLVED 2026-06-04**: added `HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3` using the actuator health endpoint.
- ~~No `--platform` flag on builder stage~~ — **RESOLVED 2026-06-04**: added `--platform=linux/amd64` to both `FROM` stages in `Dockerfile`.
- No `SPRING_PROFILES_ACTIVE` in `ENTRYPOINT` — the container boots on the base profile; prod-specific beans and any config not overridden by environment variables silently use dev defaults. Recommend documenting the required env var in the Compose service definition.
- No stable/latest symbolic tag alongside the SHA tag — downstream scripts and Helm charts must be updated on every push or they silently run stale images; a `main` or `latest` tag would provide a stable pointer.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.
- `acme.json` lives on root disk — server rebuild loses all TLS certificates; Let's Encrypt rate limits make reissuance slow; no backup or restore guidance exists.
- `ufw` installed by `provision.sh` but never enabled — Hetzner-level firewall is the sole protection layer with no host-level fallback [deploy/provision.sh].
- Redis data on named Docker volume (root disk), not Hetzner persistent Volume — session/cache data lost on server rebuild [docker-compose.yml].
- No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement.
- Docker Hub unauthenticated pull rate limits not documented — shared Hetzner egress IPs can hit the 100/6h limit; rare but unmitigated.
- Partial `provision.sh` failure recovery undocumented — `set -euo pipefail` exits on first error; re-run may silently skip a broken install block [deploy/provision.sh].
- No rollback procedure documented for a bad `APP_IMAGE` deploy when Flyway migrations have already run — operational concern for Epic 3.
- Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing or tuning guidance [deploy/lgtm/].
- `docker-compose-lgtm.yaml` in repo root has anonymous Grafana auth enabled and ports exposed — not warned against production use; dev-only artifact.
- No secret rotation procedure documented (PostgreSQL password, JWT secret, Grafana admin password) — ongoing operational maintenance concern.
- JWT_SECRET minimum length stated (64+) but Spring algorithm and actual enforcement not documented — application implementation detail.
- Grafana admin initial login not explicitly verified as part of Step 7 deployment completion check.
- `provision.sh` re-run while stack is live runs `chown -R` over live data mounts — safe with current UIDs but fragile on container image UID changes.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone.
- `/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails. The doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory [deploy/provision.sh:145].
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- `bantime=3600s` in fail2ban is a minimal starter value — inadequate for production. 1-hour bans are bypassed by slow-rate botnets. Pre-existing Story 1.1 config [deploy/provision.sh].
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.
- `apply-firewall.sh` accumulates old SSH allowlist rules when re-run with a different `SSH_ALLOWLIST_IP` — delete step targets `0.0.0.0/0` source, not the previously-set specific CIDR. Pre-existing script bug from Story 1.1 [deploy/firewall/apply-firewall.sh].

## Deferred from: code review of deploy-1-4-security-hardening (2026-06-03)
- `err()` writes to stderr — lost in stdout-only log capture; if callers redirect stdout to a log file, error messages won't appear in it [deploy/provision.sh].
- `touch` will error without parent dir if sections are reordered — parent dir (`${DEPLOY_ROOT}/traefik`) is created in section 5 which runs first; only an issue if the script structure is modified [deploy/provision.sh].

## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2 (2026-06-03)
- `chown` calls in provision.sh run unconditionally on every execution — safe for first provision, but re-running against a live system can interrupt in-progress container writes; document script as "first provision only" [deploy/provision.sh].
- `${MOUNT_POINT}/postgres` has no `chown` after `mkdir -p` — Postgres (UID 999) will fail to write on a fresh volume. Pre-existing from Story 1.2; fix in Story 1.4 or a dedicated housekeeping ticket [deploy/provision.sh:126].
- Duplicate logical alert definitions for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen exist in both `alerts.yml` (Prometheus rules) and `grafana-alerts.yml` (Grafana unified alerts) — different notification paths with no Alertmanager wired; revisit when Alertmanager is added to avoid double-paging.

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
- Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) in `deploy/lgtm/alerts.yml` — pre-existing in root `alerts.yml`; copied per spec. Guards like `and (...) > 0` needed on all ratio denominators.
- `DbConnectionPoolHigh` alert has no label selector — pre-existing in root `alerts.yml`. Add `by (pool)` clause or label filter.
- TraceID regex `[a-f0-9]{32}` only matches lowercase hex; OTel SDKs may emit uppercase. Pre-existing in root `grafana-datasources.yml`.
- `spanStartTimeShift`/`spanEndTimeShift` of 1h creates extremely wide Loki query windows on trace drill-down. Pre-existing in root `grafana-datasources.yml`. Reduce to 1m/1m.
- Prometheus has no `depends_on: app` in compose — cold-start scrape failures on first `docker compose up`. Acceptable gap; scrapes recover once app is healthy.
- LGTM data `mkdir -p` calls gated inside Hetzner Volume device `if [ -b ]` check — consistent with existing postgres pattern. If volume is absent at provision time, Docker auto-creates dirs as root (further compounds the permission issue once it's resolved).

## Deferred from: code review (2026-05-26)
- ~~Potential Path Traversal [S3StorageService.java]~~ — **RESOLVED 2026-05-28**: `StorageKeyGenerator` strips `/` from `entity` and `entityId` inputs via `[^a-zA-Z0-9_-]` sanitization. The `/` chars in the composed S3 key come exclusively from hardcoded format-string separators, not user input.
- ~~Unbounded Thread Pool [BlobstoreConfig.java]~~ — **RESOLVED 2026-05-28**: Replaced `Executors.newFixedThreadPool` with `ThreadPoolExecutor` backed by `ArrayBlockingQueue(100)` and `CallerRunsPolicy`. Pool size and queue capacity configurable via `app.storage.executor.*`.
