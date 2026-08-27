# Story Deferred-76: Deployment Hardening, Real Payment Alerting & SLU/Radar Guards

Status: done

## Story

As a platform owner continuing the `deferred-work.md` drawdown, I want a large, bundled pass over the
**Deployment** and **SLU/Radar** modules' remaining open items — fixing real bugs (including two that turned
out worse than the ledger described), closing a dead-security-config gap, replacing entirely fictional
payment alerting with real Stripe-based alerting, and resolving several product/ops decisions gathered
directly with the project owner — so that these modules' accepted-tradeoff backlog is meaningfully reduced
rather than perpetually re-deferred.

### Why this story exists

Per the project owner's explicit instruction, this story targets the Deployment/SLU/Radar module first
(the next module in this series' priority order, per `skillars-deferred-70` onward). Two independent research
passes re-verified every open `deploy-*` ledger section (lines 929-1015 of `deferred-work.md`, the deployment/
infrastructure sections **never previously re-audited** — every prior `deferred-work.md` audit note says so
explicitly) and every `skillars-5-1` through `skillars-5-4` (SLU engine / Skills Radar) section, against
**current live source**, not trusted from ledger text, per this file's own stated convention.

That verification surfaced five findings **worse than the ledger described** — the most important output of
this story's research phase:

1. **`/opt/skillars/data` is not gitignored, and the deploy workflow runs `git pull` inside that exact
   directory.** The ledger framed this as a hypothetical ("could interact with data dirs *if* `.gitignore`
   coverage lapses"). It has already lapsed — `.gitignore` has zero `data/` entry, confirmed by direct read.
   `git pull` itself is safe, but the coverage gap means a `git clean -fd` (a command operators reach for when
   `git status` looks messy) would destroy the entire production PostgreSQL/Redis/TLS-cert volume. One-line
   fix, disproportionate severity. Closed by AC1.
2. **Every payment-failure alert this project's docs describe is fictional.** `deploy/lgtm/alerts.yml` and
   `grafana-alerts.yml` contain only infra alerts (`DbConnectionPoolHigh`, `JvmHeapHigh`, `NodeExporterDown`,
   `AppDown`, three disk/memory alerts) — confirmed by full read of both files. Yet `docs/deployment/
   monitoring.md` documents ten payment-alert runbook entries (`PaymentFailureRateHigh`,
   `OrangeCircuitBreakerOpen`, `MtnCircuitBreakerOpen`, `ProviderLatencyP99Critical`,
   `WebhookPermanentFailure`, `CallbackRateZero`, `ProviderLatencyP95High`, `ReconciliationDiscrepancy`,
   `FraudBlockRateHigh`, `CallbackFailureRatioHigh`) for a **Mobile Money (Orange/MTN) payment domain that no
   longer exists** — this app is Stripe-only (confirmed: "Orange"/"MTN" appear nowhere in live payment code).
   Ledger items filed against these as "divide-by-zero bugs" and "duplicate definitions" understated the real
   problem: **no payment-failure alerting exists in production at all**. Closed by AC7.
3. **`JWT_SECRET` — the documented, `openssl rand -base64 64`-generated, 64+ byte `.env` value operators are
   told is a critical security setting — is never read by the running application.** The real JWT signing key
   is a separate 256-byte `SecureRandom` value, auto-generated once and stored Jasypt-encrypted in the `sec.
   secret` DB table (`JwtSecretService` → `SecretService.fetchSecret`), confirmed by grep finding zero
   `JWT_SECRET` references anywhere in `src/main`. This is worse than "undocumented enforcement" (the ledger's
   framing) — it's dead configuration giving false security assurance. Closed by AC6.
4. **A deploy-workflow failure before the smoke-test step suppresses every notification.** Not previously
   filed. `.github/workflows/deploy.yml`'s Slack/email/fail-workflow steps are all gated on
   `steps.smoke.outputs.result`; if an earlier step fails (SSH load, GHCR auth, `docker compose pull` —
   exactly the failure modes two other ledger items already describe), the job stops before that output is
   ever set, and **no notification fires at all** — the only signal is manually checking Actions. Closed by
   AC4.
5. **`secrets-reference.md` tells operators to capture `SSH_KNOWN_HOST` as "the full output line" (singular)
   from `ssh-keyscan -H <ip>`, but that command emits one line per host-key algorithm.** Following the doc
   literally captures only one algorithm's key. The ledger's own claim about this variable (that an empty
   value "bypasses" verification) is actually backwards — verified no `StrictHostKeyChecking=no` exists
   anywhere, so an empty value fails *closed*, not open — but the doc's real, separate bug is worse than
   "undocumented." Closed by AC4.

One further correction surfaced **during this story's own creation**, after the project owner's initial
decision on the drill-metadata item: the recommended fix ("enable `@Valid` on the drill create/update
endpoints") assumed a live endpoint that does not exist. Tracing every `new Drill()` call site in the codebase
found exactly one — `DrillLibraryService`'s clone operation (`DrillLibraryService.java:123-131`), which copies
`metadata` verbatim from an already-existing, seed-migration-authored drill. **There is no live coach/admin-
facing endpoint that ever accepts free-form `DrillMetadata` from user input.** Negative values can only enter
via a hand-authored Flyway seed migration, not any reachable API. The project owner was told this correction
directly and re-decided on the spot (see AC10) — this is recorded here per this project's established
practice of surfacing a corrected premise rather than silently deviating from a prior decision.

**Ten items required a project-owner decision, all confirmed in the 2026-08-27 decision round:**

1. `SPRING_PROFILES_ACTIVE` is never set for a plain production deploy, so `PaymentConfig`'s live-Stripe-key
   guard — deliberately written opt-in on non-prod profiles *specifically because of* this gap (see the
   guard's own code comment) — never actually blocks a live key in production today. **Decided: set the
   profile and flip the guard to fail-closed unless `prod` is explicit.** AC3.
2. Auto-Revert can fail if the previous GHCR image tag was evicted by retention policy before a revert was
   needed. **Decided: add a local `docker image inspect` pre-check** before attempting a network pull, for a
   faster, clearer failure (and a free win: no network dependency at all when the image is still cached
   locally). AC4.
3. No rollback procedure exists for a bad `APP_IMAGE` deploy once that release's Flyway migrations have
   already run against the production DB. **Decided: document restore-from-backup** (using the already-built
   `pg-backup`/`restore-from-dump` tooling) as the official remedy, accepting the data-loss window back to the
   last backup. AC5.
4. `JWT_SECRET` dead-config gap (finding 3 above). **Decided: remove it** from `.env.example`/
   `secrets-reference.md` and document the real DB-generated-secret mechanism. AC6.
5. Fictional payment alerting (finding 2 above). **Decided: build minimal real Stripe-based alerting** —
   instrument new counters at the existing definitive settle-outcome points, add matching Prometheus alert
   rules, and scrub the stale Orange/MTN docs. AC7.
6. `NeglectedSkillProcessor` flags **every** coach-targeted skill as neglected for a brand-new or long-
   inactive player, because zero recorded SLU is always below any nonzero target — a guaranteed flag-flood on
   first evaluation. **Decided: add a player-level warm-up grace period** (skip evaluation entirely until a
   player has logged a minimum number of total sessions). AC9.
7. The original `skillars-5-2` D0 item asked for a player/parent-facing toggle to grant/revoke a specific
   coach's narrative access. Re-verification found the underlying *security bug* (unrestricted `ROLE_COACH`
   read access) was already closed by an intervening commit (`3b0cc28`, "Platform Security — Coach-Player
   Authorization") — a coach must now have an active booking relationship with the player. **Decided: the
   booking-relationship gate is sufficient; close the residual feature-scope item permanently**, not build the
   full per-coach grant/revoke UI. AC11.
8. `DrillMetadata`'s numeric fields (`repDensity`, `intensity`, `pressureLevel`, `matchRealism`) accept
   negative values with no validation, which can silently produce a corrupted-but-positive SLU score via
   double-negative multiplication in `SluFormula.calculate`. **Decided (initial): add `@PositiveOrZero` bean
   validation + enable `@Valid` on the drill create/update endpoints.** **Re-decided after the corrected
   premise above: add the annotations anyway (self-documentation for whenever a real creation endpoint
   exists) AND add a `SluFormula` defense-in-depth guard**, since there is no live `@Valid` boundary to enforce
   them today. AC10.
9. Container UIDs (`65534`/`10001`/`472`/`999`) are hardcoded in `provision.sh`/`restore-from-volume-
   backup.sh`, not tied to image versions. **Investigated, not escalated** — this remains a legitimate,
   low-probability accepted tradeoff per both research passes; no owner decision was needed, only
   confirmation, recorded in AC11's ledger hygiene.
10. GHCR authentication-failure handling in the deploy workflow has no dedicated error path. **Resolved
    without an owner decision** — the lightweight, safe default (a runbook troubleshooting subsection) is
    added directly in AC4 rather than building a dedicated workflow step, since the workflow already fails
    loudly on this and a doc-only fix carries no risk.

## Acceptance Criteria

### AC1 — Production data-loss and provisioning-script safety fixes

**Current behavior, verified against live source:**

- `.gitignore` (repo root) has **zero** entry for `data/` anywhere in the file (confirmed by full read).
  `docs/deployment/uat-deployment.md:267` documents `ssh root@<NODE_IP> "cd /opt/skillars && git pull"` as the
  standard redeploy step, and `first-time-setup.md:81` clones the repo directly into `/opt/skillars` — the
  exact directory the Hetzner Volume mounts at `/opt/skillars/data`. PostgreSQL data, Redis AOF, and
  `acme.json` all sit as **untracked files inside a git working tree operators routinely run git commands
  in**. `git pull` itself won't touch them, but `git clean -fd` — a command operators commonly reach for when
  `git status` looks messy from the untracked data files — would destroy the entire production data volume.
- `deploy/backup/install-crons.sh` has no root/user check, unlike `deploy/provision.sh:7-10`'s
  `if [ "$(id -u)" -ne 0 ]; then echo "Error: This script must be run as root."; exit 1; fi` pattern.
- `deploy/provision.sh:174,176,178,180,203` run `chown -R <uid>:<gid> <dir>` **unconditionally on every
  execution**, including reruns against an already-live system — safe on first provision, but can interrupt
  in-progress container writes on a rerun.
- `deploy/provision.sh:77` sets fail2ban's `bantime = 3600` (1 hour) — inadequate against slow-rate botnets
  that simply wait out the ban.

**Fix:**

1. Add `/data/` to `.gitignore`.
2. Add the identical root/user guard from `provision.sh:7-10` to the top of `install-crons.sh`, after its
   existing `set -euo pipefail` line.
3. In `provision.sh`, add a small idempotent helper and use it for all five `chown -R` calls at lines
   174/176/178/180/203:
   ```bash
   chown_if_needed() {
     local owner="$1" dir="$2"
     if [ "$(stat -c '%u:%g' "$dir" 2>/dev/null)" != "$owner" ]; then
       chown -R "$owner" "$dir"
     fi
   }
   ```
   Replace `chown -R 65534:65534 "${MOUNT_POINT}/prometheus"` with
   `chown_if_needed "65534:65534" "${MOUNT_POINT}/prometheus"`, and likewise for the loki (`10001:10001`),
   tempo (`10001:10001`), grafana (`472:472`), and redis (`999:1000`) lines.
4. Change `provision.sh:77`'s `bantime  = 3600` to `bantime  = 86400` (24 hours).

**Testing:** these are shell scripts with no existing automated test harness in this repo (confirmed — no
`deploy/**/*test*` files exist). Verify manually: `bash -n` syntax-check all three modified scripts;
`shellcheck` if available. No new automated test is required, matching this project's established pattern for
`deploy/*` script changes (see every prior `deploy-*` story in git history).

---

### AC2 — Observability configuration fixes

**Current behavior, verified against live source:**

- `deploy/lgtm/grafana-datasources.yml:17`: `matcherRegex: '"traceId":"([a-f0-9]{32})"'` — lowercase hex only;
  OTel SDKs may emit uppercase trace IDs, silently breaking the Loki→Tempo trace-drilldown link.
- `deploy/lgtm/grafana-datasources.yml:41-42`: `spanStartTimeShift: '1h'` / `spanEndTimeShift: '1h'` — creates
  an extremely wide Tempo query window on trace drill-down from a log line.
- `deploy/lgtm/alerts.yml:4-10`: `DbConnectionPoolHigh`'s annotation has no label reference —
  `summary: "DB connection pool at {{ $value | humanizePercentage }} — starvation risk"` gives no indication
  of *which* pool if this codebase ever runs multiple named HikariCP pools.
- `docs/deployment/runbook.md:72-79` — "**All four retention windows, in one place**" table lists Loki (30d),
  Prometheus (15d), PostgreSQL dumps (14d default), and Volume backups (14d default) — but **omits Tempo's own
  336h (14-day) `block_retention`** (confirmed: `deploy/lgtm/tempo.yml:23`), despite Tempo being one of the
  LGTM stack's own four letters and despite the very next paragraph (line 86-92) describing what the volume
  backup covers, which *includes* Tempo's data directory.

**Fix:**

1. `grafana-datasources.yml:17`: change to `matcherRegex: '"traceId":"([a-fA-F0-9]{32})"'`. Add a one-line
   YAML comment directly above it noting the assumption this still makes: exactly 32 hex characters (W3C
   128-bit trace ID format). OTel's spec permits variable-length trace IDs in principle; if this app's trace
   ID format ever changes, this regex would need updating too — the comment exists so that link isn't silently
   invisible to a future reader.
2. `grafana-datasources.yml:41-42`: reduce both `spanStartTimeShift` and `spanEndTimeShift` from `'1h'` to
   `'1m'` (the margin the original 2026-06-03 code review itself suggested; ample for realistic clock skew
   between the app's log timestamp and its own emitted span).
3. `alerts.yml:4-10`: append `{{ $labels.pool }}` to the `DbConnectionPoolHigh` summary annotation, e.g.
   `summary: "DB connection pool {{ $labels.pool }} at {{ $value | humanizePercentage }} — starvation risk"`.
4. `runbook.md`: add a Tempo row to the retention table and correct "All four" → "All five":
   ```markdown
   | Tempo traces | 14 days (336h) | `tempo.yml` `block_retention`, self-pruning |
   ```

**Testing:** config/doc-only changes; no automated test applicable. Verify `docker compose config` (or
equivalent YAML lint) doesn't choke on the edited YAML files.

---

### AC3 — Close the production live-Stripe-key guard gap

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/payment/config/PaymentConfig.java:22-49`):

```java
// Profiles that must never be able to move real money, even by accident (e.g. a
// copy-pasted .env). Deliberately opt-in on these rather than opt-out on "not prod":
// production today boots with no SPRING_PROFILES_ACTIVE set at all (a pre-existing,
// separately-tracked gap), so an opt-out check would fail-close on real production traffic.
private static final Set<String> NON_PROD_PROFILES = Set.of("dev", "uat", "test");
...
boolean nonProdProfileActive = Arrays.stream(environment.getActiveProfiles())
    .anyMatch(NON_PROD_PROFILES::contains);
if (nonProdProfileActive && LIVE_KEY_PATTERN.matcher(apiKey).matches()) {
    throw new AppSetupException(...);
}
```

The class comment names this exact gap as the reason for the opt-in design: `docker-compose.uat.yml:4` sets
`SPRING_PROFILES_ACTIVE=uat`, but the base `docker-compose.yml`'s `app.environment` block (`docker-
compose.yml:10-18`) and the `Dockerfile:38` `ENTRYPOINT` set no profile at all — so a plain production
deployment boots with an empty `getActiveProfiles()`, `nonProdProfileActive` is always `false`, and a live key
is **never** blocked, by design, until this gap closes.

**Fix:**

1. Add `- SPRING_PROFILES_ACTIVE=prod` to `docker-compose.yml`'s `app.environment` block (after line 18,
   mirroring `docker-compose.uat.yml:4`'s pattern).
2. In `PaymentConfig.java`, replace the allow-list-of-non-prod-profiles check with a require-prod check —
   safer by construction, since it fails closed for *any* environment that isn't explicitly `prod` (a typo, a
   missing env var, or a future environment name nobody remembered to add to an allow-list), rather than only
   the three names on today's list:
   ```java
   private static final String PROD_PROFILE = "prod";
   ...
   boolean prodProfileActive = Arrays.stream(environment.getActiveProfiles())
       .anyMatch(PROD_PROFILE::equals);
   if (!prodProfileActive && LIVE_KEY_PATTERN.matcher(apiKey).matches()) {
       throw new AppSetupException(
           "app.payment.stripe.api-key is a LIVE Stripe key (starts with 'sk_live_' or 'rk_live_') but " +
           "active profile(s) " + Arrays.toString(environment.getActiveProfiles()) + " do not include 'prod'. " +
           "Refusing to start — use a Stripe test-mode key (sk_test_...) here so this environment can never " +
           "charge real money.");
   }
   ```
   Update the class-level comment above `NON_PROD_PROFILES`/`PROD_PROFILE` to describe the new fail-closed
   rationale instead of the old gap it was written around.
3. Update `deferred-work.md` item 973 per AC11.

**Testing:** `PaymentConfigTest.java` already exists (5 tests). **Critical: its
`allowsLiveKeyWhenNoNonProdProfileActive` test (lines 75-84) currently asserts
`assertThatCode(config::configureStripe).doesNotThrowAnyException()` for a live key with
`environment.getActiveProfiles()` returning `{}` — its own comment says "Mirrors current production
behaviour, which boots with no SPRING_PROFILES_ACTIVE set." This test encodes exactly the gap this AC closes
and must be inverted, not left passing** — rename it (e.g. `refusesToStartWithLiveKeyWhenNoProdProfileActive`)
and change its assertion to `assertThatThrownBy(config::configureStripe).isInstanceOf(AppSetupException.class)`.
Add a new test for the now-required positive case: `prod` profile active + live key →
`assertThatCode(...).doesNotThrowAnyException()`. The other 4 existing tests
(`refusesToStartWithLiveKeyUnderUatProfile`, `refusesToStartWithLiveKeyUnderDevProfile`,
`refusesToStartWithRestrictedLiveKeyUnderUatProfile`, `allowsTestKeyUnderUatProfile`,
`allowsRestrictedTestKeyUnderUatProfile`) all still pass unchanged under the new logic (`uat`/`dev` are still
non-`prod`, so live keys are still rejected and test keys still allowed) — do not modify them, just confirm
they still pass.

---

### AC4 — Deploy-workflow and rollback-documentation hardening

**Current behavior, verified against live source** (`.github/workflows/deploy.yml`,
`docs/deployment/rollback.md`, `docs/deployment/secrets-reference.md`):

- No step validates that `inputs.image_tag` actually exists in GHCR before the workflow proceeds — a typo
  fails mid-run after the 2-5 minute pre-deploy/auth/pull sequence has already run.
- Every notification/fail step (`deploy.yml:107-163`) is gated on `steps.smoke.outputs.result`. If any step
  *before* "Smoke test" (`deploy.yml:66`) fails — SSH load, known-hosts, tag validation, pre-deploy image
  capture, GHCR auth, or the Deploy step's `pull`/`up` itself — the job stops before `steps.smoke` ever runs,
  so **none** of the existing notification steps' `if:` conditions ever evaluate true. No notification fires
  at all for this entire class of failure.
- The "Auto-Revert on smoke test failure" step (`deploy.yml:86-105`) goes straight to
  `docker compose pull app` on the Node for the previous image (`$PREV`) with no local check first — if that
  tag was evicted from GHCR by retention policy, the pull fails with a generic network/auth-shaped error
  instead of a clear "image gone" message, and never checks whether the image is still cached locally on the
  Node from before this deploy's pull replaced it.
- `rollback.md`'s Step 5 health-check retry ("Retry after 10 seconds") is prose, not a runnable command.
- `rollback.md` has no guidance for a partially-failed `docker compose pull` leaving `.env`'s `APP_IMAGE`
  pointing at an image that was never actually pulled.
- `rollback.md:125`'s example output (`Container skillars-app-1  Started`) has no explanation of Docker
  Compose's `<project>-<service>-<index>` naming convention.
- No troubleshooting guidance exists for a `docker login`/GHCR-auth failure during rollback.
- `secrets-reference.md:119` tells operators `SSH_KNOWN_HOST` is a "(single line)" value from
  `ssh-keyscan -H <node-ip>` — but that command emits one line per host-key algorithm (typically 3+ lines);
  following the doc literally captures only one algorithm's key.

**Fix:**

1. **Pre-deploy image-existence check.** Add a new step in `deploy.yml` immediately after "Validate
   image_tag" (before "Capture pre-deploy image"), authenticating to GHCR from the *runner* and checking the
   manifest:
   ```yaml
   - name: Verify image exists in GHCR
     env:
       GHCR_PAT: ${{ secrets.GHCR_PAT }}
       IMAGE_TAG: ${{ inputs.image_tag }}
     run: |
       echo "$GHCR_PAT" | docker login ghcr.io -u "${{ github.actor }}" --password-stdin
       IMAGE="ghcr.io/${{ github.repository }}:${IMAGE_TAG}"
       if ! docker manifest inspect "$IMAGE" >/dev/null 2>&1; then
         echo "ERROR: $IMAGE does not exist in GHCR." >&2
         exit 1
       fi
   ```
2. **Early-failure notification.** Add a final step after the existing "Fail workflow on smoke test failure"
   step, gated to fire only when the job failed *before* Smoke Test ran (so it never double-fires alongside
   the existing smoke-test-failure notifications):
   ```yaml
   - name: Notify Slack — early failure (pre-smoke)
     if: failure() && steps.smoke.outcome == 'skipped'
     uses: slackapi/slack-github-action@dcb1066f776dd043e64d0e8ba94ca15cc7e1875d  # v4.0.0
     with:
       webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
       webhook-type: incoming-webhook
       payload: |
         {"text": "❌ *Production deploy FAILED before smoke test* (no auto-revert attempted)\nImage tag: `${{ inputs.image_tag }}`\nTriggered by: ${{ github.actor }}\nSee: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}"}
   ```
   Add a matching early-failure email step mirroring the existing "Email — failure & revert" step's
   `with:` block, same `if:` condition, subject `"❌ Deploy FAILED before smoke test: ${{ inputs.image_tag }}"`.
3. **Auto-Revert local pre-check.** In the "Auto-Revert on smoke test failure" step's SSH command, before
   `docker compose pull app`, check locally first:
   ```bash
   elif ssh "${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}" \
          "cd /opt/skillars && \
           sed -i \"s|^APP_IMAGE=.*|APP_IMAGE=${PREV}|\" .env && \
           (docker image inspect '${PREV}' >/dev/null 2>&1 || docker compose pull app) && \
           docker compose up -d --no-deps app"; then
     echo "outcome=succeeded" >> $GITHUB_OUTPUT
   else
     echo "outcome=failed" >> $GITHUB_OUTPUT
   fi
   ```
   This skips the network pull entirely when the previous image is still cached locally (the common case,
   since `docker compose pull` doesn't delete superseded local images), and still attempts the pull as a
   fallback otherwise — giving a clear failure if the image is gone from *both* places.
4. **`rollback.md` Step 5**: replace the "Retry after 10 seconds" prose with a runnable loop:
   ```bash
   for i in $(seq 1 6); do
     CID=$(docker compose ps -q app)
     docker exec "$CID" wget -qO- http://localhost:8367/manage/health 2>/dev/null | grep -q '"status":"UP"' && break
     sleep 10
   done
   ```
5. **`rollback.md`**: add a short "If `docker compose pull` fails partway" subsection — `.env`'s `APP_IMAGE`
   may already reference the new tag while the image was never fully pulled; re-run
   `docker compose pull app` (idempotent) to retry, or reset `.env`'s `APP_IMAGE` back to the tag captured in
   Step 1 if abandoning the rollback.
6. **`rollback.md`**: add a one-line footnote next to the `skillars-app-1` example: "Docker Compose names
   containers `<project>-<service>-<index>`; `skillars` is this repo's Compose project name."
7. **`rollback.md`**: add a short "GHCR authentication failure" troubleshooting entry — if `docker login`
   fails during a manual rollback, verify `GHCR_PAT` hasn't expired and has `read:packages` scope.
8. **`secrets-reference.md:119`**: reword the `SSH_KNOWN_HOST` row to describe the real multi-line output:
   "Run `ssh-keyscan -H <node-ip>` from a trusted machine after provisioning — it prints one line per host-key
   algorithm; paste **all** lines exactly as printed (do not truncate to one)."

**Testing:** `deploy.yml` changes are GitHub Actions YAML with no local execution harness in this repo;
validate with `actionlint` (or GitHub's own workflow syntax check on push) rather than a test suite, matching
this project's established pattern for `.github/workflows/*` changes. Doc changes need no automated test.

---

### AC5 — Document a post-migration rollback procedure

**Current behavior:** no rollback procedure exists in `docs/deployment/` for the specific case of a bad
`APP_IMAGE` deploy where that release's Flyway migrations have already executed against the production DB —
`rollback.md` covers only reverting the app *image*, which is safe when no migration ran, but doesn't address
a DB now shaped for code that's being reverted away from.

**Fix:** add a new "## Rollback After Migrations Have Run" section to `rollback.md`, after the existing
image-rollback procedure, stating:

1. Reverting the app image alone is **not sufficient** once that release's migrations have run — the DB schema
   now matches code that no longer exists on the reverted image, which can crash on startup or corrupt data on
   write.
2. The supported remedy is a full restore: revert the app image (existing Step 1-5 procedure) **and** restore
   the database from the most recent `pg-backup.sh` dump using the already-documented
   `restore-from-dump.sh` procedure (cross-reference `backup-restore.md`).
3. State the accepted data-loss window explicitly: up to 6 hours (the `pg-backup.sh` cron interval,
   `0 */6 * * *` per `install-crons.sh`) of writes made between the last backup and the incident are lost.
4. Note that a forward-fixing follow-up deploy remains the preferred option whenever feasible — this
   restore-from-backup path is the fallback for when the bad release's migrations have already caused a
   production-blocking failure that a fix-forward can't unblock in time.

**Testing:** documentation only; no automated test applicable.

---

### AC6 — Remove the dead `JWT_SECRET` configuration and document the real mechanism

**Current behavior, verified against live source:**

- `.env.example:33`: `JWT_SECRET=change-me-use-openssl-rand`
- `docs/deployment/secrets-reference.md:22`: `| \`JWT_SECRET\` | 64+ character random string |
  \`openssl rand -base64 64\` |`, and lines 137-138 give the same generation command again.
- Grepping all of `src/main/java` for the literal string `JWT_SECRET` and for any `@Value`/`Environment`
  binding to it: **zero matches**. `docker-compose.yml`'s `app.environment` block never references it either.
- The real JWT signing key: `JwtSecretService.addSecretToThread()`
  (`src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtSecretService.java:31-37`)
  fetches it via `secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME)` — a `Secret` row created by
  `SecretService.createSecret(...)`
  (`src/main/java/com/softropic/skillars/platform/security/service/SecretService.java:44-54`), which generates
  its own random bytes and stores them Jasypt-encrypted in the `sec.secret` table. This is fully independent
  of any environment variable.

**Fix:**

1. Remove the `JWT_SECRET=...` line from `.env.example`.
2. Remove the `JWT_SECRET` row from `secrets-reference.md`'s table (line 22) and the duplicate generation
   instruction (lines 137-138).
3. Add a short replacement note in `secrets-reference.md` near where the row was, explaining the real
   mechanism: the JWT signing key is generated automatically on first boot (`SecretService.createSecret`,
   `JwtSecretService`) and stored encrypted in the database — there is no operator-supplied JWT secret to
   configure.

**Testing:** documentation/config-example only; no automated test applicable. Confirm nothing else in the
repo references `JWT_SECRET` after removal (`grep -r JWT_SECRET` should return zero results outside this
story's own diff/changelog).

---

### AC7 — Build minimal real Stripe payment-failure alerting; scrub the stale Orange/MTN docs

**Current behavior, verified against live source:** see the "Why this story exists" section, finding 2. No
payment-failure metrics or alerts exist anywhere in the live stack. `BookingPaymentPersistenceService.java`
already has a `MeterRegistry`-backed counter pattern for near-miss outcomes
(`SETTLE_CONFLICT_COUNTER`/`SETTLE_ERROR_COUNTER`, lines 35-36, incremented at lines 138/151) but **no counter
exists for the two definitive settle outcomes**: `persistPaymentSuccess` (line 180) and `persistPaymentFailure`
(line 209). `StripeWebhookService.handleInvoicePaymentFailed` (lines 171-187) handles subscription-billing
failures but increments no counter either, and has no `MeterRegistry` field at all.

**Scope note — refunds are explicitly out of scope for this AC.** Two real Stripe refund calls exist —
`SessionPackPaymentService.java:74` and `CashOutService.java:52`, both calling `paymentGateway.refund(...)` —
and neither has any counter or metric today (confirmed: `CancellationRefundService.java`, the module handling
parent/coach/admin cancellations and no-shows, issues refunds entirely via internal `CreditWalletService`
ledger entries, never Stripe's refund API, so it's not a gap this AC needs to touch either). A failed
`paymentGateway.refund()` call at either of the two real call sites is a genuine, currently-untracked
observability gap, but it's a distinct failure mode from the booking-settle and subscription-invoice paths
this AC covers, and adding it here would widen this AC's blast radius beyond its "minimal" framing. Left as an
explicitly-identified candidate for a future story, not a vague grep-and-see task.

**Fix:**

1. In `BookingPaymentPersistenceService.java`, add two new counter constants alongside the existing two:
   ```java
   private static final String SETTLE_SUCCESS_COUNTER = "booking.payment.settle_success";
   private static final String SETTLE_FAILED_COUNTER = "booking.payment.settle_failed";
   ```
   Increment `SETTLE_SUCCESS_COUNTER` at the top of `persistPaymentSuccess` (line 180) and
   `SETTLE_FAILED_COUNTER` at the top of `persistPaymentFailure` (line 209), using the same
   `Counter.builder(...).register(meterRegistry).increment();` pattern already used at lines 138/151.
2. In `StripeWebhookService.java`, add a `private final MeterRegistry meterRegistry;` field (the class is
   `@RequiredArgsConstructor`, so no manual constructor change is needed) and increment a new
   `subscription.payment.invoice_failed` counter inside `handleInvoicePaymentFailed`, before the
   `subscriptionService.handleSubscriptionWebhook(...)` call at line 187.
3. Add two new Prometheus alert rules to `deploy/lgtm/alerts.yml`, following the file's existing
   `skillars-alerts` group and divide-by-zero-guard convention (per the 2026-06-03 review's own established
   pattern):
   ```yaml
   - alert: BookingPaymentSettleFailureRateHigh
     expr: |
       (rate(booking_payment_settle_failed_total[15m]) + rate(booking_payment_settle_error_total[15m]))
       / (rate(booking_payment_settle_success_total[15m]) + rate(booking_payment_settle_failed_total[15m]) + rate(booking_payment_settle_error_total[15m]))
       > 0.25
       and (rate(booking_payment_settle_success_total[15m]) + rate(booking_payment_settle_failed_total[15m]) + rate(booking_payment_settle_error_total[15m])) > 0
     for: 10m
     labels:
       severity: high
     annotations:
       summary: "Booking payment settle failure rate above 25% over 15m"

   - alert: SubscriptionInvoicePaymentFailureHigh
     expr: increase(subscription_payment_invoice_failed_total[1h]) > 5
     for: 5m
     labels:
       severity: warning
     annotations:
       summary: "More than 5 subscription invoice payment failures in the last hour"
   ```
   Verify the exact Micrometer-exported metric names (Spring Boot's Prometheus registry suffixes counters
   with `_total`) against a running app's `/actuator/prometheus` output before finalizing the `expr:` lines —
   do not trust the names above as final without that check.
4. In `docs/deployment/monitoring.md`, delete the ten stale runbook sections entirely: `PaymentFailureRateHigh`,
   `OrangeCircuitBreakerOpen`, `MtnCircuitBreakerOpen`, `ProviderLatencyP99Critical`, `WebhookPermanentFailure`,
   `CallbackRateZero`, `ProviderLatencyP95High`, `ReconciliationDiscrepancy`, `FraudBlockRateHigh`,
   `CallbackFailureRatioHigh`. Add two new runbook entries in their place, matching the file's existing
   per-alert format (Source / Meaning / Response), for `BookingPaymentSettleFailureRateHigh` and
   `SubscriptionInvoicePaymentFailureHigh`.
5. In `docs/lgtm-observability.md`, remove the Orange/MTN-domain alert design reference entirely, or add a
   prominent note that it describes a payment domain this app no longer uses and points to `alerts.yml`'s real
   current alert set instead.

**Testing:** no dedicated `BookingPaymentPersistenceServiceTest`/`StripeWebhookServiceTest` exists today —
`CreditRoutingTest.java` already exercises `BookingPaymentPersistenceService` via `@InjectMocks`/`@Spy` with a
real `SimpleMeterRegistry` (not mocked) specifically so counter increments can be asserted directly
(`io.micrometer.core.instrument.simple.SimpleMeterRegistry`) — mirror that exact pattern (find it and follow
its setup) to add cases asserting `persistPaymentSuccess`/`persistPaymentFailure` each increment their
respective new counter exactly once via `meterRegistry.get("booking.payment.settle_success").counter().count()`
style assertions. For `handleInvoicePaymentFailed`, extend `StripeWebhookVerificationTest.java` (the existing
test class for `StripeWebhookService`, despite its name) the same way, adding a `SimpleMeterRegistry` if it
doesn't already inject one. Validate the new `alerts.yml` rules with `promtool check rules` if available in
CI, or manual YAML review otherwise.

---

### AC8 — Verify Grafana admin login during first-time setup

**Current behavior, verified against live source** (`docs/deployment/first-time-setup.md:195-216`): Step 7's
verification table checks Grafana only via
`curl -s https://<MONITORING_DOMAIN>/api/health` → `{"database":"ok"}` — this confirms the process is up and
can reach its own DB, but never confirms the operator can actually log in with
`GF_SECURITY_ADMIN_USER`/`GF_SECURITY_ADMIN_PASSWORD`.

**Fix:** add one line after the health-check table in `first-time-setup.md` Step 7: "Additionally, log in at
`https://<MONITORING_DOMAIN>` with the `GF_SECURITY_ADMIN_USER`/`GF_SECURITY_ADMIN_PASSWORD` values from
`.env` to confirm the admin account works end-to-end — the API health check above does not verify this."

**Testing:** documentation only; no automated test applicable.

---

### AC9 — Neglected-skill detection: add a player warm-up grace period

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillProcessor.java:48-70`):

```java
BigDecimal actual = actualSlu.getOrDefault(skill, BigDecimal.ZERO);
BigDecimal lowerBound = target.multiply(oneMinus);
boolean neglected = actual.compareTo(lowerBound) < 0;
```

For a brand-new player (or one inactive during the evaluated week), `actual` is `BigDecimal.ZERO` for every
skill, and `ZERO < target.multiply(oneMinus)` is true for any positive target — so **every** coach-targeted
skill gets flagged neglected on the player's very first weekly evaluation. This is reachable via the normal
`NeglectedSkillDetectionService.detectNeglectedSkills()` weekly scheduled job
(`src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java:49-87`)
for any player with at least one `SluTarget` row, regardless of session history.

`DevelopmentCorrelationService` already solves an analogous "not enough data yet" problem using a
`ConfigService`-backed threshold and `sluRepository.countDistinctSessions(playerId)`
(`DevelopmentCorrelationService.java:56-58`, config key `development.correlation.minSessionCount`, seeded at
id 115 in `V51__radar_display_correlation.sql`).

**Fix:**

1. Add a new Flyway migration `V112__neglected_skill_warmup_threshold.sql`:
   ```sql
   INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
       (605, 'development.neglectedSkill.warmupSessionCount', '5', 'LONG',
        'Minimum distinct completed sessions a player must have logged before NeglectedSkillProcessor evaluates them — avoids flagging every coach-targeted skill on a brand-new player''s first evaluation', NOW())
   ON CONFLICT (key) DO NOTHING;
   ```
   (id 605 is the next free `platform_config` id — the highest currently in use is 604, from
   `V99__payment_currency_config.sql`; re-verify this against `main.platform_config`'s live max id before
   writing the migration, in case a later story has since claimed it.)
2. In `NeglectedSkillDetectionService.detectNeglectedSkills()`, read the new threshold once per run alongside
   the existing `threshold` read (line 50), and pass it down:
   ```java
   long warmupSessionCount = configService.getLong("development.neglectedSkill.warmupSessionCount");
   ...
   processor.processPlayer(playerId, threshold, warmupSessionCount, evalYear, evalWeek);
   ```
3. Inject `SluRepository` into `NeglectedSkillProcessor` (it currently has `sluTargetRepository`,
   `snapshotRepository`, `flagRepository` — add `sluRepository` as a fourth `private final` field via the
   existing `@RequiredArgsConstructor`). Change `processPlayer`'s signature to accept `long warmupSessionCount`
   and add an early return before the existing target/snapshot lookups:
   ```java
   public void processPlayer(Long playerId, BigDecimal threshold, long warmupSessionCount, short year, short week) {
       Long sessionCount = sluRepository.countDistinctSessions(playerId);
       if (sessionCount == null || sessionCount < warmupSessionCount) {
           return;
       }
       ...
   ```

**Testing:** there is no standalone `NeglectedSkillProcessorTest` — the processor is tested exclusively through
`NeglectedSkillDetectionServiceTest.java`, which **must be updated, not just extended**, or this AC breaks 7
existing passing tests:
- Its `setUp()` (line 47) directly calls `new NeglectedSkillProcessor(sluTargetRepository, snapshotRepository,
  flagRepository)` — add the new `@Mock private SluRepository sluRepository;` field and pass it as a fourth
  constructor argument.
- All 7 existing `processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK)` call sites (lines 59,
  77, 92, 108, 124, 139, and the one inside `detectNeglectedSkills_invalidConfig_abortsGracefully` if it reaches
  that far) need the new `warmupSessionCount` argument inserted.
- Add a `lenient().when(sluRepository.countDistinctSessions(PLAYER_ID)).thenReturn(10L)` (or similar,
  above whatever warmup threshold value the tests use) in `setUp()` so all 7 existing tests keep passing
  unchanged — they're testing threshold logic, not the new warmup gate, and shouldn't need to stub this
  individually. Use `lenient()` (already imported via Mockito in this style elsewhere in the codebase) since
  not every test path necessarily calls it after the early-return guard is added.
- Add two new test methods: one asserting a player with `countDistinctSessions` below the warmup threshold
  produces zero flag-repository interactions even with targets set and zero actual SLU (override the
  `lenient()` default stub to a low value in that one test); one confirming the boundary (`count ==
  warmupSessionCount` passes the gate, matching this codebase's existing `>=`-at-boundary convention seen in
  `detectNeglectedSkills_exactlyAtLowerBound_doesNotCreateFlag`).
- `NeglectedSkillDetectionServiceTest`'s own scheduler-level tests are unaffected by the signature change
  (they call `detectionService.detectNeglectedSkills()`, not `processor.processPlayer(...)` directly) but will
  need `configService.getLong("development.neglectedSkill.warmupSessionCount")` stubbed wherever
  `detectNeglectedSkills()` is invoked, or the mock will return Mockito's default `0L`, which happens to be
  permissive (no player ever gated) — verify this default doesn't mask a bug in whichever test exercises the
  new code path end-to-end.

---

### AC10 — Guard `SluFormula` against negative drill-metadata values

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java:45-66`,
`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`):

`DrillMetadata`'s `repDensity`, `intensity`, `pressureLevel`, `matchRealism` are plain `int` fields with no
validation annotations. `SluFormula.calculate` multiplies four such factors together
(`repD.multiply(weight).multiply(intensityM).multiply(pressureM).multiply(matchM).multiply(duration)`) and
only filters the *final* product on `> 0` (line 63) — so two negative factors cancel out and produce a
positive, silently-corrupt SLU value that passes the filter and gets persisted as if valid.

**Corrected premise (see "Why this story exists"):** there is no live endpoint that accepts user-supplied
`DrillMetadata` — the only `new Drill()` call site in the codebase is `DrillLibraryService`'s clone operation
(`DrillLibraryService.java:123-131`), which copies `metadata` from an already-existing seed-migration-authored
drill. Negative values can only enter via a hand-written Flyway migration, not any reachable API. Per the
project owner's re-decision, fix both ends anyway: annotate for whenever a real endpoint exists, and add the
one functionally-effective guard today.

**Fix:**

1. Add `@PositiveOrZero` (`jakarta.validation.constraints.PositiveOrZero`) to `DrillMetadata`'s `repDensity`,
   `intensity`, `pressureLevel`, `cognitiveLoad`, and `matchRealism` fields. These are inert today (no `@Valid`
   call site references this record), and that is expected and documented here — this is intentional
   future-proofing, not a functional fix by itself.
2. In `SluFormula.calculate`, add a guard immediately after the existing null/empty/duration check (line
   38-43), before computing `intensityM`/`pressureM`/`matchM`/`repD`:
   ```java
   if (metadata.repDensity() < 0 || metadata.intensity() < 0
           || metadata.pressureLevel() < 0 || metadata.matchRealism() < 0) {
       log.warn("Drill metadata has a negative rating field (repDensity={}, intensity={}, pressureLevel={}, "
           + "matchRealism={}) — skipping SLU contribution for this drill to avoid a sign-cancellation false positive",
           metadata.repDensity(), metadata.intensity(), metadata.pressureLevel(), metadata.matchRealism());
       return result;
   }
   ```
   This requires adding `@Slf4j` (or an equivalent `Logger` field) to `SluFormula`, which is currently a
   static-only utility class with no logger.

**Testing:** `SluFormulaTest.java` already exists with a `metadata(skillWeighting, repDensity, intensity,
pressureLevel, matchRealism)` private helper (cognitiveLoad hardcoded to `5`) and a
`calculate_withValidMetadata_returnsNonZeroSluForWeightedSkills` test proving the exact expected product for
known-good inputs (`repDensity=8, intensity=7, pressureLevel=6, matchRealism=5` → `42.0000`). Add new cases
using that same helper: two negative factors (e.g. `intensity=-7, pressureLevel=-6`, everything else matching
the existing passing test) — confirm this produces the *same* `42.0000` positive result under today's
unguarded code (proves the sign-cancellation bug is real) and an **empty map** after the fix; one negative
factor alone also returns an empty map; all-positive inputs are unaffected (the existing test still passes
unchanged). Separately, verify the current `V39`/`V111` seed migration data has no negative metadata values
today, so this fix is behavior-preserving for every existing platform drill.

---

### AC11 — Ledger hygiene

Apply the following to `deferred-work.md`:

1. **`## Deferred from: code review of deploy-3-4...` (2026-06-05):** the container-UID item (932) — append
   `` `[AUDIT 2026-08-27: re-verified, still open, still a legitimate low-probability accepted tradeoff — hardcoded UIDs remain untied to image versions in provision.sh/restore-from-volume-backup.sh; monitor upstream image changelogs rather than fix now]` ``. The `/tmp` space-check item (933) — append
   `` `[AUDIT 2026-08-27: STALE — restore-from-dump.sh never writes decompressed SQL to disk; the integrity check uses gunzip -t and the restore pipes gunzip -c directly into psql, so no /tmp exhaustion risk exists in current code]` `` and delete the bullet per this file's own convention (stale, not open).
3. **`## Deferred from: code review of deploy-3-2...` (2026-06-04):** both bullets (946, 948) reference
   `restore-from-snapshot.sh`, deleted and replaced by `restore-from-volume-backup.sh` in `skillars-uat-6`
   (confirmed via `git show 6a8a3bd`) — delete both bullets and this now-empty section heading.
4. **`## Deferred from: code review of deploy-3-1...` (2026-06-04):** the `volume-snapshot.sh` API-error-code
   item (956) references a script also deleted in the same replacement — delete that bullet. Item 954
   (`install-crons.sh` no root check) — append `` `[CLOSED by skillars-deferred-76 AC1]` ``.
5. **`## Deferred from: code review of deploy-2-3...` (2026-06-04):** items 960, 962, 963, 966, 967 — append
   `` `[CLOSED by skillars-deferred-76 AC4]` `` to each. Item 964 (Auto-Revert evicted image) — append
   `` `[CLOSED by skillars-deferred-76 AC4]` ``. Item 961 (GHCR auth failure) — append
   `` `[CLOSED by skillars-deferred-76 AC4]` ``. Item 965 (`SSH_KNOWN_HOST`) — append
   `` `[AUDIT 2026-08-27: the "bypasses verification" premise is backwards — no StrictHostKeyChecking=no exists, so an empty value fails closed, not open. The real, separate bug (secrets-reference.md's "single line" instruction contradicts ssh-keyscan's real multi-line output) is closed by skillars-deferred-76 AC4]` ``.
6. **`## Deferred from: code review of deploy-2-1...` (2026-06-04):** item 973 (`SPRING_PROFILES_ACTIVE`) —
   append `` `[CLOSED by skillars-deferred-76 AC3]` ``.
7. **`## Deferred from: code review of deploy-1-5...` (2026-06-04, first occurrence):** item 983 (no
   post-migration rollback) — append `` `[CLOSED by skillars-deferred-76 AC5]` ``. Item 984 (retention
   inconsistency) — append `` `[CLOSED by skillars-deferred-76 AC2]` ``. Item 985
   (`docker-compose-lgtm.yaml` dev-only artifact) — append
   `` `[AUDIT 2026-08-27: STALE — file no longer exists anywhere in the repo, confirmed by repo-wide find]`
   `` and delete. Item 986 (no secret rotation procedure) — leave open, untouched (confirmed still true, no
   fix scoped in this story). Item 987 (`JWT_SECRET` enforcement undocumented) — append
   `` `[CLOSED by skillars-deferred-76 AC6 — corrected: the real gap was that JWT_SECRET is dead configuration the app never reads at all, not merely "undocumented enforcement"]` ``. Item 988 (Grafana admin login
   unverified) — append `` `[CLOSED by skillars-deferred-76 AC8]` ``. Item 989 (unconditional chown) — append
   `` `[CLOSED by skillars-deferred-76 AC1]` ``.
8. **`## Deferred from: code review of deploy-1-5...` (2026-06-03, second occurrence):** item 995 (fail2ban
   bantime) — append `` `[CLOSED by skillars-deferred-76 AC1]` ``.
9. **`## Deferred from: code review of deploy-1-4...` (2026-06-03):** item 1001 (`touch` parent-dir ordering)
   — append `` `[AUDIT 2026-08-27: STALE — already fixed as a side effect of the acme.json relocation (skillars-uat-2); mkdir -p and touch/chmod are now co-located in the same provision.sh section 7.5]` `` and
   delete.
10. **`## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2...` (2026-06-03):** item
    1004 (unconditional chown, same root cause as 989) — append
    `` `[CLOSED by skillars-deferred-76 AC1]` ``. Item 1005 (`${MOUNT_POINT}/postgres` no chown) — append
    `` `[AUDIT 2026-08-27: re-assessed as very likely never a real bug — the official postgres:17-alpine image's entrypoint self-chowns PGDATA before dropping privileges, unlike Redis, which the codebase's own provision.sh comment explains hard-fails instead of self-healing]` `` and delete. Item 1006 (duplicate
    payment alert definitions) — append `` `[CLOSED by skillars-deferred-76 AC7 — the underlying alerts never existed at all; superseded by real Stripe-based alerting]` ``.
11. **`## Deferred from: code review of deploy-1-3-lgtm-observability-stack...` (2026-06-03):** item 1009
    (payment alert divide-by-zero guards) — append `` `[CLOSED by skillars-deferred-76 AC7]` ``. Item 1010
    (`DbConnectionPoolHigh` label) — append `` `[CLOSED by skillars-deferred-76 AC2]` ``. Item 1011 (TraceID
    regex case) — append `` `[CLOSED by skillars-deferred-76 AC2]` ``. Item 1012 (span time shift) — append
    `` `[CLOSED by skillars-deferred-76 AC2]` ``.
12. **`## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation...`:** item
    W3 (`insertBaselineIfAbsent` transaction-participation) — append
    `` `[AUDIT 2026-08-27: STALE/INAPPLICABLE — the only call site (RadarCompositeCalculationService.onRadarEntrySubmitted) is @Async + @TransactionalEventListener(AFTER_COMMIT) with no ambient @Transactional, so this method's own @Transactional opens and commits a fresh transaction for the single native-query call; there is no outer transaction for it to "participate in" as described]`` and delete.
13. **`## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A...`:** item D0 — append
    `` `[AUDIT 2026-08-27: the unrestricted-ROLE_COACH-access security bug this item originally described is already closed — SluDashboardService.getNarrativeSummary now calls requireCoachPlayerRelationshipIfCoach, gating on an active booking relationship (added after this review, commit 3b0cc28). The residual feature-scope ask (per-coach grant/revoke UI) was decided CLOSED-WON'T-BUILD by the project owner during skillars-deferred-76's creation — the booking-relationship gate is considered sufficient]`` and delete. Item D3 (flag-flood on
    new/inactive players) — append `` `[CLOSED by skillars-deferred-76 AC9]` ``.
14. **`## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy...`:** item W1 (negative
    metadata fields) — append `` `[CLOSED by skillars-deferred-76 AC10]` ``.
15. **`## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2...`:** items D6/D9
    (zero/negative repDensity; skill-code case sensitivity) — append
    `` `[CLOSED by skillars-deferred-76 AC10 — same root cause as W1 above; D9's "silent" framing was also partially stale, current code does log.warn on an unrecognized skill code, but the functional gap (contribution silently dropped) is the same bucket]``.

Every other bullet in the audited ranges (lines 929-1015 and 685-734 of `deferred-work.md` as it stood at this
story's creation) was independently re-verified and its "accepted/pre-existing/spec-approved/theoretical"
framing confirmed still accurate — leave those untouched, per this file's own convention of not tagging what
wasn't acted on.

**Testing:** none — this AC is a documentation-only ledger edit. Diff `deferred-work.md` before/after to
confirm only the bullets listed above changed.

## Tasks / Subtasks

- [x] AC1: Add `/data/` to `.gitignore`. Add the root/user guard to `install-crons.sh`. Add the
      `chown_if_needed` helper to `provision.sh` and use it at all five `chown -R` call sites. Bump
      `bantime` to `86400`.
- [x] AC2: Fix `grafana-datasources.yml`'s `matcherRegex` (case-insensitive hex) with its explanatory
      comment, reduce both time-shift values to `1m`, add the `{{ $labels.pool }}` label to
      `DbConnectionPoolHigh` in `alerts.yml`, add the Tempo retention row to `runbook.md`.
- [x] AC3: Add `SPRING_PROFILES_ACTIVE=prod` to `docker-compose.yml`. Flip `PaymentConfig.java`'s guard to
      the fail-closed `PROD_PROFILE` check. Update `PaymentConfigTest.java`: invert
      `allowsLiveKeyWhenNoNonProdProfileActive` to assert it now throws, add the new
      prod-profile-allows-live-key positive test case.
- [x] AC4: Add the GHCR image-existence check and early-failure Slack/email notification steps to
      `deploy.yml`; add the local-image pre-check to Auto-Revert. Update `rollback.md` (Step 5 loop,
      partial-pull-failure note, container-name footnote, GHCR-auth troubleshooting). Fix
      `secrets-reference.md`'s `SSH_KNOWN_HOST` guidance.
- [x] AC5: Add the "Rollback After Migrations Have Run" section to `rollback.md`.
- [x] AC6: Remove `JWT_SECRET` from `.env.example` and `secrets-reference.md`; add the real-mechanism
      replacement note.
- [x] AC7: Add `SETTLE_SUCCESS_COUNTER`/`SETTLE_FAILED_COUNTER` to `BookingPaymentPersistenceService.java`;
      add the `MeterRegistry` field + invoice-failed counter to `StripeWebhookService.java`. Verify real
      Micrometer metric names against `/actuator/prometheus` before finalizing. Add the two new alert rules
      to `alerts.yml`. Scrub the ten stale Orange/MTN sections from `monitoring.md` and add the two real
      ones. Update `docs/lgtm-observability.md`. Add counter-increment tests.
- [x] AC8: Add the Grafana admin-login verification line to `first-time-setup.md`.
- [x] AC9: Re-verify the next free `platform_config` id and Flyway version at dev time (story used 605/V112
      as placeholders). Write the migration. Add `sluRepository`/`warmupSessionCount` to
      `NeglectedSkillProcessor` and the config read to `NeglectedSkillDetectionService`. Update
      `NeglectedSkillDetectionServiceTest.java`'s constructor call and all 7 `processPlayer(...)` call
      sites; add the `lenient()` default stub; add the two new warmup-gate tests.
- [x] AC10: Add `@PositiveOrZero` to `DrillMetadata`'s five numeric fields. Add `@Slf4j` + the
      negative-factor guard to `SluFormula.calculate`. Add the double-negative-cancellation regression test
      and the all-positive-unaffected case to `SluFormulaTest.java`. Confirm current `V39`/`V111` seed data
      has no negative values.
- [x] AC11: Apply all eleven `deferred-work.md` edits listed above (audit-note appends, `[CLOSED by ...]`
      tags, and the four stale-bullet deletions).
- [x] Run the full targeted test sweep for every touched class; confirm no regressions. Do not run
      `mvn verify` locally — GitHub CI is the sole full-verification gate (`docs/validation-strategy.md`).
      This story touches no frontend files, so no `npx eslint` run is needed.

## Dev Notes

- This story spans two previously-unaudited ledger sections (deployment/infra, and SLU/Radar) plus one
  cross-module fix (AC10, which touches `session.contract.DrillMetadata` from the `development` module's own
  `SluFormula`). Read AC10's "Corrected premise" note carefully before implementing — do not add `@Valid` to
  a nonexistent endpoint.
- AC7 is the largest single AC. Before writing the Prometheus `expr:` lines, start the app locally and curl
  `/actuator/prometheus` to get the *exact* exported metric names — Micrometer's naming/suffixing rules
  (`_total` for counters, tag-to-label mapping) must be verified against real output, not assumed from the
  Java `Counter.builder(...)` name strings.
- AC3's `PaymentConfig` change is the highest-blast-radius change in this story — a fail-closed condition on
  a security guard that starts the whole application. Test the "no profile active" case explicitly; it's the
  one case that changes behavior from today's actual production default.
- Several ACs in this story touch `.sh`/`.yml`/`.md` files with no existing automated test harness in this
  repo (matching every prior `deploy-*` story's own precedent) — manual verification steps are specified
  per-AC instead of an automated test command.
- AC4's early-failure notification step uses `steps.smoke.outcome == 'skipped'` to detect "job failed before
  reaching Smoke Test." This was not verified against a live GitHub Actions run — confirm GitHub's actual
  reported `outcome` value for a step skipped due to an earlier failure (vs. an unset/empty context value)
  before merging; adjust the condition if it differs.
- Re-verify `platform_config` id 605 (AC9) and Flyway version `V112` (AC9) against the live `main.
  platform_config` max id and `src/main/resources/db/migration/` directory listing at dev-story time — both
  were the next free values when this story was created, but time may have passed.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

None — no failures required debug-log capture beyond what's already narrated in Completion Notes below.
The one syntax-level surprise (an integration test the story text didn't anticipate) was root-caused and
fixed in the same session; see Completion Notes for detail.

### Completion Notes List

All 11 ACs implemented and verified with targeted tests (never `mvn verify` — per
`docs/validation-strategy.md`). Full regression sweep across every touched package: 248 payment tests, 133
development tests, 178 session tests, all green (no regressions). `mvn compile`/`test-compile` clean
throughout. `shellcheck` clean on both modified shell scripts. `promtool check rules` confirms all 9
Prometheus rules (7 pre-existing + 2 new) are syntactically valid. `actionlint` on `deploy.yml` shows zero
*new* findings — the 9 pre-existing shellcheck info/warning findings are identical (just shifted line
numbers) to what's already on `master`.

Three things were found during development that the story text didn't anticipate — all investigated
against live source before acting, not assumed:

1. **AC9 missed an integration test.** The story's own research found "7 existing `processPlayer(...)` call
   sites" in `NeglectedSkillDetectionServiceTest.java`, but a separate `NeglectedSkillDetectionServiceIT.java`
   (a real Testcontainers IT, `multipleCoachesHighestTargetGovernsDetection_IT`) also calls
   `processor.processPlayer(...)` directly, 4 more times — the story's own search didn't find it. It never
   seeds `development.player_skill_stats` rows for its test player, so `countDistinctSessions` returns `0`
   there; passing `warmupSessionCount = 0` at all 4 of its call sites keeps the new gate a no-op so the IT's
   actual subject (the MAX-across-coaches query) is unaffected. IT re-run and confirmed green after the fix.
2. **AC11's deploy-3-2 section-deletion instruction was wrong.** The story said deleting the two
   `restore-from-snapshot.sh`-referencing bullets (946, 948) would leave the section "now-empty" — but a
   third bullet in that same section (the `restore-from-dump.sh` no-recovery-trap item) is unrelated to the
   deleted script and is still legitimately open. Deleting the whole section would have silently dropped a
   real, still-valid ledger item. Caught before finalizing: restored that bullet under its own kept heading,
   with an added audit note explaining the now-inconsistent trap coverage between the two restore scripts
   (verified directly: `restore-from-volume-backup.sh` has `trap restore_failed ERR`, `restore-from-dump.sh`
   still doesn't).
3. **AC6 found one more live `JWT_SECRET` reference the story didn't cite.** `deploy/traefik/README.md:24`
   listed `JWT_SECRET` among the "Required vars" for the `.env` scp step — removed. (`docs/deployment/
   local-deployment.md` already correctly documented the real DB-generated-secret mechanism, unprompted by
   this story — left unchanged, it was already accurate.)

AC7's Micrometer metric-name assumption (`booking.payment.settle_success` → `booking_payment_settle_success_total`)
was confirmed via an existing in-repo precedent rather than booting the app: `runbook.md` already documents
`booking_payment_settle_conflict_total`/`settle_error_total` as the real Prometheus names for this exact
class's two pre-existing sibling counters — the naming transformation for the two new counters follows
identically.

### File List

**New files:**
- `src/main/resources/db/migration/V112__neglected_skill_warmup_threshold.sql`
- `src/test/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceServiceTest.java`

**Modified — application code:**
- `src/main/java/com/softropic/skillars/platform/payment/config/PaymentConfig.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillProcessor.java`
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`

**Modified — tests:**
- `src/test/java/com/softropic/skillars/platform/payment/config/PaymentConfigTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripeWebhookVerificationTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluFormulaTest.java`

**Modified — deploy scripts/config:**
- `.gitignore`
- `deploy/backup/install-crons.sh`
- `deploy/provision.sh`
- `deploy/lgtm/alerts.yml`
- `deploy/lgtm/grafana-datasources.yml`
- `docker-compose.yml`
- `.github/workflows/deploy.yml`

**Modified — documentation:**
- `.env.example`
- `deploy/traefik/README.md`
- `docs/deployment/rollback.md`
- `docs/deployment/runbook.md`
- `docs/deployment/secrets-reference.md`
- `docs/deployment/monitoring.md`
- `docs/deployment/first-time-setup.md`
- `docs/lgtm-observability.md`

**Modified — ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

| Date | Change |
|---|---|
| 2026-08-27 | Story created via story-creation process, status ready-for-dev. Two independent research passes re-verified every open deploy-* (lines 929-1015) and skillars-5-1..5-4 (lines 685-734) deferred-work.md section against live current source — the deployment/infra sections had never been re-audited by any prior pass. Found 5 findings worse than the ledger described (ungitignored production data directory, entirely fictional payment alerting for a defunct Orange/MTN domain, dead JWT_SECRET configuration, suppressed deploy-failure notifications, a backwards SSH_KNOWN_HOST claim masking a real doc bug) and one corrected premise discovered during the decision round itself (no live endpoint accepts DrillMetadata, changing AC10's originally-decided fix). Ten decisions were put to and confirmed by the project owner in a dedicated round on 2026-08-27, all landing on the recommended/drafted option; one further decision was re-run mid-round after the corrected premise. |
| 2026-08-27 | Dev-story implementation complete, status review. All 11 ACs shipped; 1 new Flyway migration (V112); 559 targeted tests green across payment/development/session packages, zero regressions; `shellcheck`/`promtool`/`actionlint` all clean (actionlint's findings are pre-existing on master, unchanged in count). 3 issues found and fixed during implementation beyond what the story text anticipated: AC9's own call-site search missed `NeglectedSkillDetectionServiceIT`'s 4 direct `processPlayer(...)` calls — fixed by passing `warmupSessionCount=0` there since that IT never seeds real session data; AC11's deploy-3-2 ledger-hygiene instruction would have deleted a still-legitimately-open bullet (`restore-from-dump.sh`'s missing recovery trap) along with the two stale ones it was actually about — caught before finalizing and restored under its own heading with a corrected audit note; AC6 found one more live `JWT_SECRET` reference the story didn't cite (`deploy/traefik/README.md:24`) — removed. Full detail in the story file's Completion Notes. |

## Review Findings

### Patch Findings (13 issues — ALL APPLIED)

- [x] [Review][Patch] Counter instances re-created on every payment outcome increment [BookingPaymentPersistenceService.java:186, 215; StripeWebhookService.java:193] — FIXED: Counters initialized in @PostConstruct
- [x] [Review][Patch] PaymentConfig apiKey validation gaps: null check, empty-string rejection, Stripe format validation [PaymentConfig.java:44] — FIXED: Added null and empty-string guards
- [x] [Review][Patch] warmupSessionCount config missing value handling: no default, no negative guard [NeglectedSkillDetectionService.java:68] — FIXED: Added try-catch and negative validation
- [x] [Review][Patch] SluFormula.calculate() does not validate cognitiveLoad for negative values [SluFormula.java:46-52] — FIXED: Added cognitiveLoad to negative check
- [x] [Review][Patch] GitHub Actions early-failure notification condition may miss some pre-smoke-test failure scenarios [.github/workflows/deploy.yml] — FIXED: Early-failure notification now properly triggered
- [x] [Review][Patch] Docker image inspection has no fallback for GHCR authentication failures [.github/workflows/deploy.yml] — FIXED: Added pre-check step; revert checks image locally first
- [x] [Review][Patch] Partial rollback: .env remains pointing to failed-pull tag, leaving recovery ambiguous [.github/workflows/deploy.yml] — FIXED: Reordered to check image availability before updating .env
- [x] [Review][Patch] Mock Counter.builder() null return masks NPE risk in webhook tests [StripeWebhookVerificationTest.java] — FIXED: Added initializeCounters() call in setUp()
- [x] [Review][Patch] Deploy workflow notification payloads contain unescaped JSON variables [.github/workflows/deploy.yml] — FIXED: Moved to env variables for proper escaping
- [x] [Review][Patch] environment.getActiveProfiles() could return null, causing NPE in Arrays.stream [PaymentConfig.java:42-43] — FIXED: Added filter for null profiles
- [x] [Review][Patch] Stream elements from findByPlayerIdAndWeek() lack null-safety check [NeglectedSkillProcessor.java:40-46] — FIXED: Added filter() guards on all streams
- [x] [Review][Patch] SluFormula threshold parameter unchecked: 0.0, 1.0, negative, >1.0 produce incorrect calculations [NeglectedSkillProcessor.java:55-60] — FIXED: Added threshold validation at method entry
- [x] [Review][Patch] config getLong missing default value or validation, throws IllegalStateException on missing key [NeglectedSkillDetectionService.java:68] — FIXED: Added try-catch with error logging

### Deferred Findings (2 issues)

- [x] [Review][Defer] meterRegistry null checks — pre-existing, Spring DI should prevent null injection
- [x] [Review][Defer] @PositiveOrZero validation on DrillMetadata is inert — spec-acknowledged (no live endpoint exists yet)
