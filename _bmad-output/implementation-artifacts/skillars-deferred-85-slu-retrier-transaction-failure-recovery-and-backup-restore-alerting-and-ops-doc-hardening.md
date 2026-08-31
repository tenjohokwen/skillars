# Story skillars-deferred-85: SLU persistence-retrier transaction-failure recovery, a real retry-proxy test, and backup/restore + alerting + ops-doc hardening

Status: ready-for-dev

## Story

As the platform owner,
I want (1) both SLU persistence retriers to actually recover — and log — a transaction begin/commit failure instead of letting it escape raw out of the async listener, (2) one test that proves the `@Retryable`/`@Recover` wiring is live so a regression can't pass CI green, (3) `restore-from-dump.sh` to stop leaving the app stopped on a mid-restore failure and to verify more than "at least one table exists", (4) `pg-backup.sh` to verify the dump actually landed in Object Storage intact, (5) `provision.sh` error lines to survive a plain stdout capture, (6) a loud failure when alert routing is misconfigured to nothing, and (7) the three ops-doc gaps (secret rotation, provision partial-failure recovery, Docker Hub pull limits) filled,
so that `skillars-deferred-84`'s own four code-review deferrals close, and the oldest still-open Deploy/Infra items in `deferred-work.md` (`deploy-3-2`, `deploy-3-1`, `deploy-3-3`, `deploy-1-5`, `deploy-1-4`) stop being re-flagged by every full-file audit.

## Story creation context

Per the standing `deferred-work.md` re-mining priority order (SLU/Radar first, then Deploy/Infra → Drills/Session-Builder → Auth/Registration → Messaging/Admin/Reviews/Disputes — see `[[project_skillars_release_workflow]]`):

**SLU/Radar is now essentially mined out of actionable, decision-light work.** `skillars-deferred-84` (just shipped) closed the last big items in the `skillars-5-1`…`5-4` code-review sections. What remains in those sections is either `[DISMISSED … ledger hygiene]`, `[DECIDED]`, or accepted test-hygiene nits (`skillars-5-6` AD-series). The **only** genuinely-open, genuinely-actionable SLU/Radar work is `skillars-deferred-84`'s **own** code-review deferrals (added to `deferred-work.md` on 2026-08-31 under its `## Deferred from: code review of skillars-deferred-84 …` heading, and in its story file's Review Findings "Deferred" section):

- **Both `SluPersistenceRetrier` and `SnapshotPersistenceRetrier` only `retryFor`/`@Recover` `DataAccessException`.** A failure raised at the `@Transactional` boundary (`TransactionSystemException`, `CannotCreateTransactionException` — subclasses of `org.springframework.transaction.TransactionException`, which does **not** extend `DataAccessException`) is neither retried nor recovered. It propagates raw out of `SluCalculationService.onBookingCompleted` (`@Async @TransactionalEventListener`) into `SimpleAsyncUncaughtExceptionHandler` as a bare stack trace — the intended `"… rows lost … manual recovery needed"` `log.error` never fires. → **AC1** (project owner decision 2026-08-31: widen both retriers — retry *and* `@Recover` — see Dev Notes).
- **No test exercises the actual Spring-AOP retry/`@Recover` path for either retrier.** All six existing tests use plain `new` instantiation; `…_writerThrows_propagatesToCaller` explicitly pins the *un-proxied* no-retry behaviour. Deleting `@Retryable` / `@Recover` / `@EnableRetry`, changing `retryFor`, or drifting a `@Recover` signature all pass CI green. → **AC2** (project owner decision 2026-08-31: add one shared `@SpringJUnitConfig` + `@EnableRetry` test covering both retriers).

**Deploy/Infra — next in priority order.** A full re-scan of the `deploy-*` sections of `deferred-work.md` against current source found that the CI/CD cluster from `## Deferred from: code review of skillars-deferred-10` is **stale — already done, never annotated**: `.github/dependabot.yml` exists (github-actions ecosystem + grouped Maven, added 2026-08-08), `pr-build.yml` already builds the image with `load: 'true'` and runs a Trivy scan (`severity: CRITICAL,HIGH`, `exit-code: '1'`, `.trivyignore`), and both workflows already share a `./.github/actions/docker-build` composite action instead of duplicating the `docker/build-push-action` SHA pin. Those `deferred-10` items (D0, D1, D2, D3, D4) are closed in Task 8 ledger hygiene, not re-implemented.

What genuinely **remains** open in Deploy/Infra, verified live against current source, and picked up here (project owner selected the full set 2026-08-31):

- **`restore-from-dump.sh` has no `trap … ERR`** (`deploy-3-2`, `[AUDIT 2026-08-27: still open]`). It runs `docker compose … stop app` early; any later failure (DB drop/recreate, `psql` restore, integrity check, health-wait) leaves the app stopped with no recovery and no signal. Its sibling `restore-from-volume-backup.sh` was given a `trap restore_failed ERR` when it was rewritten; that improvement was never ported here — so this is now an **inconsistency between the two restore scripts**, not just a gap. → **AC3**.
- **`restore-from-dump.sh`'s integrity check is `COUNT(*) … pg_tables WHERE schemaname='public' >= 1`** (`deploy-3-4`). This app puts almost all its tables in named schemas (`main`, `booking`, `payment`, `session`, `development`, `marketplace`, `messaging`, `admin`), so the `public`-only count is both weak *and* nearly meaningless — a badly-truncated restore can pass. → **AC3** (strengthen alongside the trap).
- **`pg-backup.sh` never verifies the upload** (`deploy-3-1`, "No upload integrity check (checksum / ETag verification after `aws s3 cp`)"). A silently-truncated or failed-but-exit-0 upload is undetectable until a restore fails. → **AC4**.
- **`provision.sh`'s `err()` writes only to stderr** (`deploy-1-4`) — an operator running `provision.sh > provision.log` (no `2>&1`) loses every error line. → **AC5**.
- **Alert routing fails silently when unconfigured** (`deploy-3-3`, "Empty notification vars cause silent delivery failure"). `docker-compose.yml` passes `GF_ALERT_NOTIFY_EMAIL=${GF_ALERT_NOTIFY_EMAIL:-}` / `GF_SLACK_WEBHOOK_URL=${GF_SLACK_WEBHOOK_URL:-}`; Grafana provisions the `notify-ops` contact point from `grafana-alerts.yml` regardless, and every alert then routes to a dead address. → **AC6**.
- **Three ops-doc gaps** (`deploy-1-5`): no secret-rotation procedure (PostgreSQL password, JWT secret, Grafana admin password); no `provision.sh` partial-failure recovery note; Docker Hub unauthenticated pull-rate-limit exposure undocumented. → **AC7**.

Not picked up (each needs its own design decision, project owner deferred both 2026-08-31): the `:latest`-tag ordering race (`skillars-deferred-23` — would need a `git merge-base --is-ancestor` guard in `ci.yml`), and outbound-egress firewall rules for the observability containers (`deploy-1-5` — an incomplete allowlist breaks real outbound calls; needs careful enumeration). `V117`'s non-online-safe `ADD CONSTRAINT` + non-`CONCURRENTLY` index (`skillars-deferred-84` deferral) also stays deferred — it is an explicitly accepted codebase-wide migration convention at current table size, not this story's concern.

**Nine ACs spanning backend resilience, a real test, four shell/infra scripts, and three doc sections — comfortably past this project's "no small stories" bar.**

## Acceptance Criteria

### AC1 — Both SLU persistence retriers retry *and* `@Recover` a `TransactionException`, not just a `DataAccessException`.

- **`SluPersistenceRetrier`** (`src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java`):
  - Add `org.springframework.transaction.TransactionException` to `@Retryable`'s `retryFor`: `retryFor = {DataAccessException.class, TransactionException.class}`.
  - Add a second `@Recover` method: `recoverSluSaveFailure(TransactionException ex, List<PlayerSkillStat> rows)`, body identical in shape to the existing `recoverSluSaveFailure(DataAccessException ex, …)` — same `log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed", …)` message, same no-rethrow contract. Do **not** widen the existing `@Recover` to a common supertype; Spring Retry selects the closest-matching `@Recover` by throwable type + trailing-arg signature, and two explicit methods are unambiguous here (a `TransientDataAccessResourceException` still resolves to the `DataAccessException` overload, a `TransactionSystemException` to the new one).
  - Import `org.springframework.transaction.TransactionException`.
- **`SnapshotPersistenceRetrier`** (`…/development/service/SnapshotPersistenceRetrier.java`): the exact same two changes — `retryFor = {DataAccessException.class, TransactionException.class}`, plus `recoverSnapshotWriteFailure(TransactionException ex, List<PlayerSkillStat> stats, short isoYear, short isoWeek)` mirroring the existing `DataAccessException` overload's body and message verbatim.
- **Retry-safety (verify by reading `SnapshotBatchWriter.java` + `SluRepository`; no code change expected)**: `SnapshotBatchWriter.writeAll` is `@Transactional` and issues only additive `upsertAdd` calls, and `SluRepository.saveAll` runs in its own implicit transaction. A `TransactionException` at begin means nothing ran; at commit means the whole transaction rolled back (nothing partially persisted). Either way a whole-method retry re-runs against a clean slate — the same reasoning `SnapshotPersistenceRetrier`'s class javadoc already states for `DataAccessException`. If reading the code turns up a path that reads mid-batch state non-transactionally, stop and flag it rather than proceeding.
- **Update the class javadoc** of both retriers where it currently implies `DataAccessException` is the only failure class handled (if it does), so the next reader isn't misled.
- **Do not add `application*.yaml` keys** for `app.slu.retry.*` / `app.slu.snapshot-retry.*` — confirmed by grep that none exist today; inline `@Value`-style defaults are the established pattern for these two beans, not a gap to fill.
- **Update the existing unit tests** (`SluPersistenceRetrierTest`, `SnapshotPersistenceRetrierTest`) with one added case each: `recoverSluSaveFailure_transactionException_logsAndDoesNotRethrow` / `recoverSnapshotWriteFailure_transactionException_logsAndDoesNotRethrow`, mirroring the existing `recover…_logsAndDoesNotRethrow` case shape but passing a `TransactionSystemException` (`org.springframework.transaction.TransactionSystemException`) — asserts `assertThatCode(...).doesNotThrowAnyException()`. Plain instantiation, no Spring context, matching the file's existing style.
- Test: `mvn -o test -Dtest=SluPersistenceRetrierTest,SnapshotPersistenceRetrierTest`, confirm green.

### AC2 — One shared Spring-context test proves the `@Retryable`/`@Recover` proxy path is live for both retriers.

- New test `SluRetrierProxyRetryIT` (package `com.softropic.skillars.platform.development.service`), **not** extending `AbstractIntegrationTest` and **not** `@SpringBootTest` — it uses a minimal standalone context so it starts no container:
  - `@ExtendWith(SpringExtension.class)` + `@SpringJUnitConfig(SluRetrierProxyRetryIT.Config.class)` (or `@ContextConfiguration`), with a `static class Config` annotated `@Configuration` + `@org.springframework.retry.annotation.EnableRetry` that declares four beans: the real `SluPersistenceRetrier`, the real `SnapshotPersistenceRetrier`, and `Mockito.mock(SluRepository.class)` / `Mockito.mock(SnapshotBatchWriter.class)` as their collaborators.
  - `@BeforeEach` resets the two mocks (`Mockito.reset(...)`).
- Six tests, all asserting through the proxied bean (autowired), driving the mock's behaviour with `doThrow(...).when(...)`:
  1. `saveSluWithRetry_persistentDataAccessException_retriesToMaxThenRecovers` — mock `saveAll` always throws `TransientDataAccessResourceException`; call `saveSluWithRetry`; assert it returns normally (no exception — `@Recover` absorbed it) **and** `verify(sluRepository, times(3)).saveAll(any())` (default `max-attempts:3`).
  2. `saveSluWithRetry_persistentTransactionException_retriesToMaxThenRecovers` — same, mock throws `TransactionSystemException` — proves the new `retryFor` entry **and** the new `@Recover` overload are both wired (without AC1's changes this test fails: either the exception propagates, or Spring Retry can't find a matching `@Recover`).
  3. `saveSluWithRetry_succeedsOnSecondAttempt_noRecover` — mock throws once then succeeds; assert normal return **and** `verify(..., times(2)).saveAll(any())`.
  4–6. The identical three cases for `snapshotPersistenceRetrier.writeAllWithRetry(stats, (short) 2026, (short) 35)` against the `SnapshotBatchWriter` mock.
- **Context-count ceiling**: this class adds exactly **one** new Spring context (`missCount` +1). `.github/scripts/assert-context-count.sh`'s ceiling is currently `37` and `pr-build.yml` runs `assert-context-count.sh build.log 37`. After implementation, check the `missCount` line in the build output (or the CI `pr-build` "Spring context count (AC3)" group):
  - If it reports **≤ 37**, no change needed.
  - If it reports **38**, bump the `CEILING="${2:-37}"` default to `38` **and** the literal `37` in `pr-build.yml`'s `assert-context-count.sh build.log 37` invocation to `38`, and append a comment block to `assert-context-count.sh` in the same style as its existing `CEILING = 37, deliberate +1 (skillars-deferred-83 AC1)` note:
    > `CEILING = 38, deliberate +1 (skillars-deferred-85 AC2). SluRetrierProxyRetryIT loads a tiny standalone @SpringJUnitConfig + @EnableRetry context (no container, no autoconfiguration) to exercise the real @Retryable/@Recover AOP proxy for SluPersistenceRetrier and SnapshotPersistenceRetrier — the only way to prove the annotation wiring is live, which plain-instantiation unit tests structurally cannot. One extra context, reproducible exactly on every local and CI run, not ordering-dependent thrashing.`
  - If it reports **> 38**, something else forked — investigate (`python3 scratchpad/ctxkeys.py` per the script's own guidance) before touching the ceiling.
- Test: `mvn -o test -Dtest=SluRetrierProxyRetryIT`, confirm all six green. Then a broader run to catch the context count: `mvn -o test -Dtest='com.softropic.skillars.platform.development.**'` and eyeball the `missCount` line.

### AC3 — `restore-from-dump.sh` restarts the app on any mid-restore failure, and its post-restore integrity check is meaningful.

- **`trap`** (`deploy/backup/restore-from-dump.sh`): after the `docker compose … stop app` line, register an `ERR` trap (and clear it on the success path before `rm -f "${LOCAL_DUMP}"`), mirroring `restore-from-volume-backup.sh`'s pattern exactly:
  ```bash
  restore_failed() {
    echo "[restore-dump][error] restore step failed — restarting the app service with the database currently on disk so it does not stay down" >&2
    docker compose -f /opt/skillars/docker-compose.yml start app || true
  }
  trap restore_failed ERR
  ```
  Place the `trap … ERR` immediately after `docker compose … stop app` succeeds, and `trap - ERR` immediately after the app-health-wait loop passes. Note the failure mode this addresses is "app left stopped", **not** "database left half-restored" — the script already drops+recreates the DB, so a failure mid-`psql` leaves an empty/partial DB that the *next* restore run overwrites cleanly; the trap's job is only to not leave the app down and silent.
- **Integrity check** (same file): replace the `pg_tables WHERE schemaname='public'` count with a check that actually reflects a healthy restore of *this* app:
  - Count user tables across all non-system schemas: `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type='BASE TABLE';` — assert `>= 80` (the app has 117+ Flyway migrations creating well over that many tables; 80 is a conservative floor that still catches a badly truncated restore — leave a comment saying so and to bump it only if it ever false-fails on a legitimately smaller schema).
  - Assert `flyway_schema_history` restored intact: it exists (`information_schema.tables … table_name='flyway_schema_history'`), has `>= 100` rows, and has zero failed migrations (`SELECT COUNT(*) FROM flyway_schema_history WHERE success = false` returns `0`). Do not hard-code an expected max `version` — the image's migration set changes every release and the script has no reliable way to know it.
  - Keep the existing "value is empty → fail" guard around each `psql -t` capture. Keep `ON_ERROR_STOP=1` on the restore `psql` (already present — it is the first line of defence and must stay).
- **While here**, adopt the `log()` / `err()` helper functions `restore-from-volume-backup.sh` defines (`log() { echo "[restore-dump] $*"; }` / `err() { echo "[restore-dump][error] $*" >&2; }`) and use them in place of the raw `echo … >&2` / `echo "[restore-dump] …"` lines, so the two restore scripts read consistently. Do not change the `[restore-dump]` prefix.
- Test: `bash -n deploy/backup/restore-from-dump.sh` (syntax) and `shellcheck deploy/backup/restore-from-dump.sh` clean (match the standard the other `deploy/backup/*.sh` scripts already pass — they carry `# shellcheck source=env-guard.sh` directives, so shellcheck is clearly run against them). A live end-to-end restore run is not expected in this story (no disposable environment); the change is verified by `shellcheck` + reading.

### AC4 — `pg-backup.sh` verifies the uploaded dump before declaring success.

- **`deploy/backup/pg-backup.sh`**: after the `aws s3 cp … --no-progress` upload, before `rm -f "${DUMP_FILE}"`, add a verification step:
  - Capture the local size: `LOCAL_SIZE=$(stat -c %s "${DUMP_FILE}")`.
  - `aws s3api head-object --bucket "${HOS_BUCKET}" --key "${PREFIX}skillars-${TIMESTAMP}.sql.gz" --endpoint-url "${HOS_ENDPOINT}"` (with the same `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env the `cp` uses), parse `ContentLength` (via `jq -r '.ContentLength'` — `jq` is already an apt dependency installed by `provision.sh`), and assert it equals `LOCAL_SIZE`. Fail loudly (`err` + `exit 1`) on mismatch or on a `head-object` that returns nothing (object absent → upload silently failed).
  - Additionally, when the upload was a single-part `PUT` (which a `pg-backup` dump of this app's size always is for Hetzner Object Storage's default 8 MB multipart threshold — but guard for it anyway), the `head-object` `ETag` is the MD5 hex of the body: strip surrounding quotes and any `-N` multipart suffix; if there is **no** `-` in the ETag, compare it to `md5sum "${DUMP_FILE}" | cut -d' ' -f1` and fail on mismatch. If the ETag *does* carry a `-N` suffix (multipart), skip the MD5 comparison and log that only the size was verified (a comment should explain why — multipart ETags are not a whole-object MD5).
  - Only `rm -f "${DUMP_FILE}"` and print `[pg-backup] Done.` after both checks pass.
- Mirror the same size + best-effort-ETag verification into **`deploy/backup/volume-backup.sh`** after its own `aws s3 cp` (it has the identical unverified-upload gap; the ledger only named `pg-backup.sh` but the fix is one function and both scripts run on the same cron). Factor the check into a small shared function only if both call sites end up byte-identical — otherwise inline it in each, consistent with this repo's `deploy/backup/*.sh` convention of not over-abstracting (see `deferred-work.md`, `skillars-deferred-24` GUARD_PATH item — a DRY refactor of a duplicated guard block was explicitly deferred there as needing its own sign-off; do not silently introduce a new shared helper file).
- Test: `bash -n` + `shellcheck` clean on both scripts.

### AC5 — `provision.sh` error lines survive a plain stdout capture.

- **`deploy/provision.sh`**: change `err() { echo "[provision][error] $*" >&2; }` so the line reaches **both** stdout (for an operator running `provision.sh > provision.log` with no `2>&1`) and stderr (for terminal-red visibility and stream-aware tooling). Recommended one-liner for the Ubuntu 22.04 target: `err() { echo "[provision][error] $*" | tee /dev/stderr; }` — `tee` writes the line to its own stdout **and** duplicates it to `/dev/stderr`. Document, in a one-line comment, the known cosmetic cost: a caller that *does* redirect `2>&1` sees the line twice. A plain dual-`echo` (`echo … >&2; echo …`) is an acceptable alternative with the same double-print tradeoff — pick one, comment the reason.
- Leave `log()` unchanged (stdout only, as now).
- The identical `err() { … >&2; }` in `restore-from-volume-backup.sh` and the one AC3 adds to `restore-from-dump.sh` are **out of scope for this AC** — those scripts run under cron with `>> "${LOG}" 2>&1` (see `install-crons.sh`), so their stderr is already captured. The ledger item is `provision.sh`-specific because `provision.sh` is the one run interactively by an operator. Do not touch the others.
- Test: `bash -n deploy/provision.sh` + `shellcheck deploy/provision.sh` clean; a quick manual check — `bash -c 'err() { echo "[provision][error] $*" | tee /dev/stderr; }; err test' > /tmp/o.log 2>/dev/null; grep -q "\[error\] test" /tmp/o.log` succeeds (error line present in stdout-only capture).

### AC6 — Misconfigured alert routing fails loudly instead of silently dropping every alert.

- **`deploy/provision.sh`**, inside the existing `.env` handling block (currently around lines 133–147, "`.env` — enforce mode 600 if present; warn but continue if absent"): when `${ENV_FILE}` **is present**, read `GF_ALERT_NOTIFY_EMAIL` and `GF_SLACK_WEBHOOK_URL` from it (a targeted `grep -E '^GF_(ALERT_NOTIFY_EMAIL|SLACK_WEBHOOK_URL)=' "${ENV_FILE}" | cut -d= -f2-` is enough — do not `source` the whole file). If **both** are empty or absent, `err` a message naming both variables and pointing at `docs/deployment/secrets-reference.md`, then `exit 1`. This is a hard error, not a warning: Grafana will provision the `notify-ops` contact point (`grafana-alerts.yml`) regardless and silently route every firing alert to nowhere. `provision.sh` is idempotent and safe to re-run, so a hard stop that the operator fixes in `.env` and re-runs past is the right failure mode — matching the pattern `docker-compose.yml` already uses for `GF_SECURITY_ADMIN_PASSWORD` / `MONITORING_DOMAIN` (`${VAR:?…}`).
  - When `${ENV_FILE}` is **absent**, keep the existing warn-and-continue behaviour (the operator is told to place `.env` and re-run; the check fires on that re-run).
- **`deploy/lgtm/alerts.yml` — verification only, no change expected.** Re-read it and confirm every ratio-style alert already carries an `and (denominator) > 0` guard (`BookingPaymentSettleFailureRateHigh` and `DiskDataVolumeHigh` do today; `SubscriptionInvoicePaymentFailureHigh` is an `increase(...) > 5` count with no division). The `deferred-work.md` items about `CallbackFailureRatioHigh` / `PaymentFailureRateHigh` divide-by-zero (`deploy-3-3`, `deploy-1-3`) reference alerts that no longer exist — `skillars-deferred-76 AC7` replaced that whole family with the current Stripe-based set. If the re-read confirms this, those items are closed in Task 8; if it turns up an unguarded ratio, add the `and (…) > 0` guard and note it.
- Test: `bash -n deploy/provision.sh` + `shellcheck` clean; manual check that the new block `exit 1`s given an `.env` with both vars blank and passes given at least one set.

### AC7 — The three `deploy-1-5` ops-doc gaps are filled.

- **Secret rotation** — add a `## Secret Rotation` section to `docs/deployment/secrets-reference.md` (after `## Notes on Secret Generation`). Cover, as a numbered runbook per secret, the rotation procedure for:
  - **`POSTGRES_PASSWORD`** — `ALTER ROLE … WITH PASSWORD` inside the postgres container, update `/opt/skillars/.env`, `docker compose up -d` to recreate `app` (and any other consumer) with the new value; note that `pg-backup.sh` / `restore-from-dump.sh` read it from the same `.env` so no separate update is needed.
  - **JWT secret** — identify the actual key first: `deferred-work.md` (`deploy-1-5`, `[CLOSED by skillars-deferred-76 AC6]`) records that `JWT_SECRET` was **dead configuration the app never read** and the real signing key is elsewhere. Grep `src/main` for the property the JWT signing bean actually binds (`jjwt` / `JwtService` / `*.jwt.*` in `application.yaml`) and document rotation of *that* — including the operational consequence (every in-flight access token and refresh token is invalidated the moment the app restarts with a new key; all sessions must re-authenticate). If the app genuinely has no externally-supplied JWT secret (it is generated at boot), say so explicitly and describe "rotation" as "restart the app" with the same session-invalidation consequence.
  - **`GF_SECURITY_ADMIN_PASSWORD`** — update `.env`, `docker compose up -d grafana`; note Grafana persists the admin user in `/opt/skillars/data/grafana`, so the env var only takes effect on a container recreate, and an already-changed-in-UI password is **not** overridden by the env var (Grafana behaviour) — the reset path if the UI password is lost is `grafana-cli admin reset-admin-password` inside the container.
  - A short lead-in paragraph: there is no scheduled rotation cadence today; these are the procedures for a compromise-triggered or policy-triggered rotation.
- **`provision.sh` partial-failure recovery** — add a subsection to `docs/deployment/first-time-setup.md` (in or next to its provisioning section): `provision.sh` runs `set -euo pipefail` and exits on the first error; it is idempotent and every step is guarded, so the recovery procedure is "fix the reported cause, re-run `provision.sh` — completed steps are detected and skipped". Call out the one genuinely non-idempotent hazard the ledger names (`chown -R` over live data mounts on a re-run — already mitigated by `chown_if_needed`, note that) and the ordering constraint that the Hetzner Volume must be attached before the run that is expected to mount it.
- **Docker Hub pull rate limits** — add a note to `docs/deployment/first-time-setup.md` (near the Docker install / `docker compose up` step): unauthenticated pulls from Docker Hub are rate-limited (100 / 6h per source IP), shared Hetzner egress IPs can hit it, and the symptom is `toomanyrequests` on `docker compose pull`. Mitigation: `docker login` with a free Docker Hub account on the Node raises the limit to 200/6h; the images this stack pulls from Hub are `grafana/grafana`, `redis`, `prom/*`, `grafana/loki`, `grafana/tempo` (GHCR-hosted `app` image is unaffected).
- Test: none (docs only). Prettier is not applied to `.md` under `docs/` (confirm against the repo's existing `docs/deployment/*.md` — they are hand-formatted); match the surrounding heading style and line width of the file each section is added to.

### AC8 — Ledger hygiene (`deferred-work.md`).

Done as part of this story's implementation (not story creation), because several closures depend on reading current source that the dev agent will have open anyway:

- **Mark closed** (already done, unannotated — verified live in this story's creation): under `## Deferred from: code review of skillars-deferred-10 (2026-07-02)` — D0 (`pr-build.yml` now builds with `load: 'true'` and scans), D2 (`.github/dependabot.yml` exists — github-actions + grouped Maven), D3 (both workflows share `./.github/actions/docker-build`), D4 (Trivy scan present in `pr-build.yml`, fails on CRITICAL/HIGH). Leave D1 (`ci.yml` branch-name — already deleted), D5 (build-layer caching in `pr-build.yml` — still genuinely absent; the composite action's caching is not verified here, leave open), D6 (uncited Hetzner-outage doc callout — still open, leave).
- **Mark closed** — under `## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)` and `deploy-3-3`: the `CallbackFailureRatioHigh` / `PaymentFailureRateHigh` / `FraudBlockRateHigh` divide-by-zero items, *if* AC6's re-read of `alerts.yml` confirms those alerts no longer exist and the current ratio alerts are guarded. Cite `skillars-deferred-76 AC7`.
- **Mark closed by this story** — `deploy-3-2` (restore-from-dump.sh trap → AC3), `deploy-3-4` (weak integrity check → AC3), `deploy-3-1` (no upload integrity check → AC4), `deploy-1-4` (`err()` to stderr → AC5), `deploy-3-3` (empty notification vars → AC6), and the three `deploy-1-5` doc items (→ AC7). Remove the closed bullets outright per this file's stated first rule ("Items are deleted outright once they are implemented"), or tag `[CLOSED by skillars-deferred-85 ACn]` if a section would be left empty and you're unsure — match whatever the two or three most recent closures in the file did.
- **Add** this story's own new deferrals (if any surface during implementation) under a new `## Deferred from: … skillars-deferred-85 …` heading.
- **Do not** touch: the `:latest` ordering race (`skillars-deferred-23`), the egress-firewall item (`deploy-1-5`), `V117` online-safe migration (`skillars-deferred-84` deferral), `deferred-10` D5/D6 — all explicitly out of scope.

## Tasks / Subtasks

- [ ] **Task 1: SLU retrier `TransactionException` recovery (AC: #1)**
  - [ ] `SluPersistenceRetrier` — `retryFor = {DataAccessException.class, TransactionException.class}` + new `recoverSluSaveFailure(TransactionException, List)` overload + import
  - [ ] `SnapshotPersistenceRetrier` — same two changes (`recoverSnapshotWriteFailure(TransactionException, List, short, short)`)
  - [ ] Read `SnapshotBatchWriter.java` + `SluRepository` to confirm whole-method retry stays safe for a rolled-back `TransactionException`; flag if not
  - [ ] Update class javadocs where they imply `DataAccessException` is the only handled class
  - [ ] Add one `…_transactionException_logsAndDoesNotRethrow` case to each of `SluPersistenceRetrierTest` / `SnapshotPersistenceRetrierTest`
  - [ ] `mvn -o test -Dtest=SluPersistenceRetrierTest,SnapshotPersistenceRetrierTest` green
- [ ] **Task 2: Real retry-proxy test (AC: #2)**
  - [ ] `SluRetrierProxyRetryIT` — `@SpringJUnitConfig` + nested `@Configuration @EnableRetry`, 4 beans (2 real retriers + 2 Mockito mocks), 6 tests (persistent-`DataAccessException`, persistent-`TransactionException`, succeed-on-2nd — for each retrier)
  - [ ] `mvn -o test -Dtest=SluRetrierProxyRetryIT` green
  - [ ] Check `missCount` in a `development.**` run; if 38, bump `assert-context-count.sh` ceiling 37→38 + `pr-build.yml`'s literal + the documented comment block; if >38, investigate before touching the ceiling
- [ ] **Task 3: `restore-from-dump.sh` trap + integrity check (AC: #3)**
  - [ ] `trap restore_failed ERR` after `stop app`; `trap - ERR` after the health-wait passes
  - [ ] Replace `public`-only table count with all-schema count `>= 80` + `flyway_schema_history` intact (`>= 100` rows, zero `success = false`)
  - [ ] Adopt `log()` / `err()` helpers from `restore-from-volume-backup.sh`
  - [ ] `bash -n` + `shellcheck` clean
- [ ] **Task 4: Upload integrity verification (AC: #4)**
  - [ ] `pg-backup.sh` — `head-object` `ContentLength` == local size + best-effort single-part ETag/MD5 check after `aws s3 cp`; only `rm` + "Done" on pass
  - [ ] Mirror into `volume-backup.sh`
  - [ ] `bash -n` + `shellcheck` clean on both
- [ ] **Task 5: `provision.sh` `err()` to stdout+stderr (AC: #5)**
  - [ ] `err() { echo "[provision][error] $*" | tee /dev/stderr; }` + one-line comment on the `2>&1` double-print cost
  - [ ] `bash -n` + `shellcheck` clean; manual stdout-capture check
- [ ] **Task 6: Loud failure on unconfigured alert routing (AC: #6)**
  - [ ] `provision.sh` `.env` block — hard `exit 1` when `.env` present and both `GF_ALERT_NOTIFY_EMAIL` + `GF_SLACK_WEBHOOK_URL` empty; unchanged warn-and-continue when `.env` absent
  - [ ] Re-read `alerts.yml`, confirm ratio alerts are guarded; note or fix
  - [ ] `bash -n` + `shellcheck` clean; manual check both branches
- [ ] **Task 7: Ops docs (AC: #7)**
  - [ ] `secrets-reference.md` — `## Secret Rotation` (PostgreSQL password, JWT signing key [grep for the real property first], Grafana admin password)
  - [ ] `first-time-setup.md` — provision.sh partial-failure recovery subsection + Docker Hub pull-rate-limit note
- [ ] **Task 8: Ledger hygiene (AC: #8)**
  - [ ] Close `deferred-10` D0/D2/D3/D4 (already-done-unannotated), `deploy-3-2`, `deploy-3-4`, `deploy-3-1`, `deploy-1-4`, `deploy-3-3`, the three `deploy-1-5` doc items, and (conditionally) the `deploy-1-3`/`deploy-3-3` divide-by-zero items
  - [ ] Add a `## Deferred from: … skillars-deferred-85 …` section only if implementation surfaces new deferrals

## Dev Notes

### Source ledger mapping

| AC | `deferred-work.md` source |
|----|---------------------------|
| AC1 | `## Deferred from: code review of skillars-deferred-84 …` — "only retry and `@Recover` `DataAccessException` — transaction begin/commit failures are neither retried nor recovered" |
| AC2 | same section — "No test exercises the actual retry / `@Recover` proxy path for any SLU retrier" |
| AC3 | `deploy-3-2` (2026-06-04) — "no recovery trap … `[AUDIT 2026-08-27: still open … inconsistency between the two restore scripts]`" + `deploy-3-4` (2026-06-05) — "Integrity check (table count ≥ 1) is trivially weak" |
| AC4 | `deploy-3-1` (2026-06-04) — "No upload integrity check (checksum / ETag verification after `aws s3 cp`)" |
| AC5 | `deploy-1-4` (2026-06-03) — "`err()` writes to stderr — lost in stdout-only log capture" |
| AC6 | `deploy-3-3` (2026-06-05) — "Empty notification vars cause silent delivery failure" |
| AC7 | `deploy-1-5` (2026-06-04) — "No secret rotation procedure documented", "Partial `provision.sh` failure recovery undocumented", "Docker Hub unauthenticated pull rate limits not documented" |
| AC8 | `skillars-deferred-10` D0/D2/D3/D4 (stale-closed), `deploy-1-3`/`deploy-3-3` ratio-guard items (conditionally stale-closed) |

Project-owner decisions (2026-08-31, during this story's creation): AC1 = widen both retriers (retry + recover), not log-only; AC2 = one shared `@SpringJUnitConfig` test; AC3–AC7 = full Deploy/Infra bundle as scoped; **not** picked up = `:latest` ordering guard, egress firewall (each its own design story).

### Architecture / conventions to follow

- **`@Retryable` self-invocation**: both retriers are already separate `@Component`s specifically so `@Retryable` goes through the AOP proxy (their class javadocs cite the same pitfall documented on `BookingService.acceptAndInitiatePayment` and `TimelineEventListener`'s `@Lazy @Autowired self`). AC1 does not change that structure — it only widens `retryFor` and adds a `@Recover` overload on the existing beans.
- **`@Recover` selection**: Spring Retry matches by (throwable assignability, then trailing-arg signature). Two explicit `@Recover` methods — one `DataAccessException`, one `TransactionException` — are unambiguous because neither is assignable to the other (`TransactionException` and `DataAccessException` both extend `org.springframework.core.NestedRuntimeException` but are siblings). Do **not** collapse to a single `@Recover(RuntimeException …)` — it would also swallow programming errors (`NPE`, `IllegalStateException`) that should surface.
- **`spring-retry` + `@EnableRetry`** are already on the classpath and active app-wide (`skillars-deferred-77 AC8` established this — "spring-retry was already a `pom.xml` dependency with `@EnableRetry` already active"). AC2's test declares its own `@EnableRetry` on its local `@Configuration` because it does **not** load the main application context.
- **Context-count gate**: `.github/scripts/assert-context-count.sh` gates `missCount` from `DefaultContextCache` (an upper bound on distinct contexts built). Ceiling is `37`, last moved by `skillars-deferred-83 AC1` with a documented deliberate `+1`. AC2's one new `@SpringJUnitConfig` context is the same kind of deterministic, reproducible `+1` — bump with the same style of justification comment **only if CI actually reports 38**. `IntegrationTestConventionTest` may flag the new class in the test phase; if it does, the class genuinely is a deliberate fork and that is expected — see `docs/testing/` for how the project handles a sanctioned fork.
- **Shell scripts** (`deploy/backup/*.sh`, `deploy/provision.sh`): `set -euo pipefail`, `# shellcheck source=…` directives on sourced files, `[script-name]` / `[script-name][error]` log prefixes, `log()`/`err()` helpers in the newer scripts. `shellcheck` is clearly run against `deploy/backup/*.sh` (they carry source directives). Do **not** introduce a new shared helper file for the AC4 verification — `skillars-deferred-24` explicitly deferred a DRY refactor of a duplicated guard block across these same scripts as needing its own sign-off; inline or a same-file function only.
- **`aws` CLI**: awscli v1 from Ubuntu apt (`provision.sh` installs it). `aws s3api head-object` and `--endpoint-url "${HOS_ENDPOINT}"` for Hetzner Object Storage are already used across `restore-from-dump.sh` / `prune-backups.sh`. `jq` is an installed apt dependency — use it to parse `head-object` JSON.
- **Grafana alert provisioning**: `deploy/lgtm/grafana-alerts.yml` (Grafana unified alerting, provisioned read-only into the container) defines the `notify-ops` contact point with `${GF_ALERT_NOTIFY_EMAIL}` / `${GF_SLACK_WEBHOOK_URL}` expansion; `deploy/lgtm/alerts.yml` is the separate Prometheus rules file. `docker-compose.yml` (grafana service, ~lines 213–231) passes both env vars with `:-` defaults (empty when unset). AC6's guard belongs in `provision.sh`, not in `docker-compose.yml` (a `${VAR:?}` there would force *both* to be set, breaking a Slack-only or email-only setup).
- **`monitoring.md`** already carries per-alert runbook anchors (e.g. `#bookingpaymentsettlefailureratehigh`) referenced from `alerts.yml`. AC7 does not touch `monitoring.md`.
- **Testing**: per `docs/validation-strategy.md` / `[[feedback_no_local_mvn_verify]]`, do **not** run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<Class>` for touched Java; `bash -n` + `shellcheck` for touched scripts.

### Files being modified — current state

- **`SluPersistenceRetrier.java`** — `@Retryable(retryFor = DataAccessException.class, maxAttemptsExpression = "${app.slu.retry.max-attempts:3}", backoff = @Backoff(delayExpression = "${app.slu.retry.backoff-initial-ms:100}", multiplierExpression = "${app.slu.retry.backoff-multiplier:2.0}"))` on `saveSluWithRetry(List<PlayerSkillStat>)` → `sluRepository.saveAll(rows)`; one `@Recover recoverSluSaveFailure(DataAccessException, List)` logging "… rows lost for session {} … manual recovery needed". Must preserve: the separate-bean structure, the `app.slu.retry.*` namespace, the no-rethrow `@Recover` contract.
- **`SnapshotPersistenceRetrier.java`** — same shape, `app.slu.snapshot-retry.*` namespace, `writeAllWithRetry(List<PlayerSkillStat>, short isoYear, short isoWeek)` → `snapshotBatchWriter.writeAll(...)`; `@Recover recoverSnapshotWriteFailure(DataAccessException, List, short, short)`. Class javadoc already documents the retry-safety reasoning for `writeAll` being `@Transactional` + additive-upsert-only — AC1's `TransactionException` addition rides on exactly that reasoning.
- **`SluCalculationService.java`** — `@Async @TransactionalEventListener(AFTER_COMMIT) onBookingCompleted`; calls `sluPersistenceRetrier.saveSluWithRetry(stats)` then `snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek)`. **Not modified** by this story — it is the caller whose raw-exception-escape AC1 fixes upstream, in the retriers.
- **`SluPersistenceRetrierTest.java` / `SnapshotPersistenceRetrierTest.java`** — 3 plain-Mockito cases each (`…_delegatesTo…`, `recover…_logsAndDoesNotRethrow`, `…_throws_propagatesToCaller`). AC1 adds one `TransactionException` recover case to each; keep the existing three untouched.
- **`deploy/backup/restore-from-dump.sh`** — `set -euo pipefail`; raw `echo … >&2` (no `log`/`err` helpers); `docker compose … stop app` with **no** `trap`; integrity check counts `pg_tables WHERE schemaname='public' >= 1`; `rm -f "${LOCAL_DUMP}"` at the end. Must preserve: `ON_ERROR_STOP=1` on the restore `psql`, the `gunzip -t` pre-check, the `/tmp` free-space pre-check, the `--set` / `-t -c` capture-then-empty-check pattern.
- **`deploy/backup/restore-from-volume-backup.sh`** — the reference for AC3's `trap` (`restore_failed() { err …; docker compose … up -d; }` + `trap restore_failed ERR` / `trap - ERR`) and for the `log()`/`err()` helper style. **Not modified.**
- **`deploy/backup/pg-backup.sh`** — `set -euo pipefail`; `aws s3 cp … --no-progress` then unconditional `rm -f "${DUMP_FILE}"` + "Done". Has an empty-file pre-check (`[ ! -s "${DUMP_FILE}" ]`) already.
- **`deploy/backup/volume-backup.sh`** — same upload shape; already has a `trap cleanup EXIT` for the temp archive and a nuanced `tar` exit-code check. AC4 adds post-upload verification here too.
- **`deploy/provision.sh`** — `log()` / `err()` at lines 14–15 (`err` → `>&2` only); `chown_if_needed` idempotent helper; `.env` block ~lines 133–147 (mode-600 enforce if present, warn+continue if absent — does **not** currently read any `.env` values). Runs as root, `set -euo pipefail`, idempotent, re-run-safe.
- **`deploy/lgtm/alerts.yml`** — Prometheus rules; `BookingPaymentSettleFailureRateHigh` and `DiskDataVolumeHigh` already carry `and (denominator) > 0` guards; no `Callback*` / `FraudBlockRate*` / `PaymentFailureRate*` alerts present. AC6 re-reads to confirm; change only if an unguarded ratio is found.
- **`docs/deployment/secrets-reference.md`** — sections: `## Server .env`, `## GitHub Actions Secrets`, `## Notes on Secret Generation`. AC7 adds `## Secret Rotation` after the last.
- **`docs/deployment/first-time-setup.md`** — the provisioning walkthrough. AC7 adds the partial-failure-recovery subsection + Docker Hub note.
- **`.github/scripts/assert-context-count.sh`** — `CEILING="${2:-37}"`, invoked as `assert-context-count.sh build.log 37` in `pr-build.yml`. Touched by AC2 **only if** `missCount` hits 38.

### Project Structure Notes

Touches: 2 Java main classes (`development/service/{SluPersistenceRetrier,SnapshotPersistenceRetrier}.java`), 3 Java test classes (2 updated, 1 new — `development/service/SluRetrierProxyRetryIT.java`), 4 shell scripts (`deploy/backup/{restore-from-dump,pg-backup,volume-backup}.sh`, `deploy/provision.sh`), 2 docs (`docs/deployment/{secrets-reference,first-time-setup}.md`), conditionally `.github/scripts/assert-context-count.sh` + `.github/workflows/pr-build.yml`, and `deferred-work.md`. **No** Flyway migration, **no** frontend change, **no** i18n change, **no** API contract change, **no** new fixture-id ranges (AC2's test uses Mockito mocks; the scripts have no automated tests). `alerts.yml` is verify-only.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of skillars-deferred-84 …` (AC1/AC2), `deploy-3-2` / `deploy-3-4` / `deploy-3-1` / `deploy-1-4` / `deploy-3-3` / `deploy-1-5` sections (AC3–AC7), `## Deferred from: code review of skillars-deferred-10` (AC8).
- `_bmad-output/implementation-artifacts/skillars-deferred-84-…-skill-toggle-debounce.md` — Review Findings "Deferred" section; the `SluPersistenceRetrier`/`SnapshotPersistenceRetrier` "mirror exactly" directive AC1 completes.
- `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java`, `SnapshotPersistenceRetrier.java`, `SluCalculationService.java`; `src/main/java/com/softropic/skillars/platform/development/repo/SnapshotBatchWriter.java`.
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerResilienceTest.java` — nearest existing retry test, but note it exercises a programmatic `RetryTemplate`, **not** declarative `@Retryable`; AC2 needs the `@EnableRetry` + AOP-proxy approach instead.
- `deploy/backup/restore-from-volume-backup.sh` — the `trap … ERR` + `log()`/`err()` reference for AC3.
- `.github/scripts/assert-context-count.sh` — the context-count gate and its own documented precedent (`skillars-deferred-83 AC1`) for a deliberate `+1`.
- `[[project_skillars_release_workflow]]`, `[[feedback_no_local_mvn_verify]]`.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-08-31: Story created from `deferred-work.md` mining. SLU/Radar sections (`skillars-5-1`…`5-4`) confirmed mined out by `skillars-deferred-84` — only that story's own code-review deferrals remain actionable (AC1: both SLU retriers ignore `TransactionException`; AC2: no test covers the real `@Retryable`/`@Recover` proxy path). Priority order then moves to Deploy/Infra: a live re-scan found the `skillars-deferred-10` CI/CD cluster (Dependabot, Trivy scan, shared `docker-build` composite action) already shipped unannotated (→ Task 8 hygiene), leaving the oldest still-open items — `restore-from-dump.sh` no `trap` + weak integrity check (AC3), `pg-backup.sh` unverified upload (AC4), `provision.sh` `err()` lost in stdout capture (AC5), silent alert-routing misconfiguration (AC6), and three `deploy-1-5` doc gaps (AC7). Project-owner decisions (2026-08-31): widen both retriers (retry + `@Recover`, not log-only); one shared `@SpringJUnitConfig` retry-proxy test; full Deploy/Infra bundle as scoped; `:latest`-ordering guard and egress firewall each deferred to their own design story. Status: ready-for-dev.
