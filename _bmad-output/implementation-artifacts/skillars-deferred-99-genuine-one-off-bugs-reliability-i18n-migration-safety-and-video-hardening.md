# skillars-deferred-99: Genuine One-Off Bugs — Reliability, i18n, Migration Safety & Video Hardening

**Status:** done | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-99
**Branch:** `story/deferred-99-genuine-bugs-and-gaps`
**Created:** 2026-09-07

---

## Story Overview

A large cross-cutting cleanup story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`. The "genuine one-off bugs & gaps" class is **not** exhausted, so per the story-creation directive this bundle takes those first and then reaches into the **Database/Performance** and **Video** buckets. All items were re-verified against `HEAD` (`master` @ `69a1ca8`) at story creation — the verification results are recorded per-AC under **Verified at HEAD**.

Themes:

1. **Payment safety** — `purchasePack`'s compensating refund can silently drop money with no reconciliation record.
2. **Shutdown reliability** — graceful-shutdown forced termination protects only 1 of 7 executor pools; the durable-outbox pool is unprotected.
3. **i18n robustness** — a fail-fast `MessageSource` turns any missing base-bundle key into a 500 for non-de/fr/en clients; phone/national-id validation is hard-wired to one market.
4. **Test/tooling correctness** — the OTP-resend 409 is proven only at the DB layer; `MigrationLint` re-walks the tree per identifier; a concurrency IT asserts on wall-clock timing.
5. **Observability** — `SmtpHealthIndicator` is serial/unbounded/uncached and mis-handles implicit-TLS; deploy smoke-step *errors* (vs `result=fail`) skip notification and auto-revert.
6. **DB hygiene** — migration online-safety conventions owed for `V60`/`V94`/`V117`; `player_slu_weekly_snapshot_applied` has no retention; `PessimisticLockRetryer` holds a pooled connection while sleeping.
7. **Video hardening** — `long` overflow in quota arithmetic; `BandwidthResetService` period drift; drill-video load-failure gives the user no feedback; `authorizePlayback` has no non-gating latency signal.

**Source:** `deferred-work.md` (re-verified 2026-09-07 against `master@69a1ca8`). Cited ledger line ranges are as of that commit.

---

## User Story

**As a** platform engineer responsible for Skillars' reliability, correctness and operability,
**I want** the highest-value latent bugs and gaps in `deferred-work.md` fixed together — payment reconciliation, shutdown safety, i18n robustness, migration hygiene, and video hardening —
**So that** money is never silently lost, a deploy/restart cannot strand durable work, non-Cameroon clients don't hit 500s, and video failures are visible to users and operators.

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet it closes) and **Test** (verification). Frontend ACs must pass `npx eslint src/` + `npx prettier --check src/` and `quasar build`. Backend ACs: GitHub CI is the full-verification gate (do **not** run `mvn verify` locally); `mvn -o test-compile` + targeted `mvn -o test -Dtest=...` for the touched classes is the local sanity bar.

### AC1: `purchasePack` compensating refund must not silently drop money

- **Task:** Make `SessionPackPaymentService.purchasePack`'s post-charge failure path safe: catch **any** throwable from the compensating `paymentGateway.refund(...)`, and persist a durable reconciliation record when that refund fails.
- **Verified at HEAD:** `SessionPackPaymentService.purchasePack` — after `chargeAndCapture(...)` succeeds, `createPurchase(...)` is wrapped in `catch (Exception e)`, but the compensating `paymentGateway.refund(paymentIntentId, tier.getTotalPrice())` inside it is wrapped only in `catch (PaymentGatewayException refundEx)`. Any other throwable (unchecked, unwrapped `StripeException`) propagates out and **skips** the `throw new PaymentGatewayException("payment.lifecycleFailure")`. Even on the caught path, the refund failure is only `log.error`'d — there is **no persisted record** for reconciliation. Worst case: parent charged, no `session_pack_purchases` row, no refund, nothing to reconcile from. Confirmed live at `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (~L86–L101).
- **Fix approach:**
  - Widen the inner catch to `catch (Exception refundEx)` (mirror `handlePackBasedBooking`'s deliberate broad catch, `PaymentLifecycleService.java:167`, and its recorded rationale — narrowest common supertype covering both known and future unchecked throws, still excluding `Error`).
  - On refund failure, write a durable record. Prefer the **existing** mechanism: check for a `payment_failures` / `PaymentFailureRecord` table or a generic outbox row (`deferred-91` AC1 generic transactional outbox). If a suitable persistence path exists, reuse it; otherwise add a minimal `payment.stripe_refund_failures` row (payment_intent_id, amount, parent_id, pack_tier_id, error, created_at) via a new Flyway migration (next free `V###`, follow `docs/deployment/migration-conventions.md` — additive only, no lock-heavy DDL).
  - Keep the outer `throw new PaymentGatewayException("payment.lifecycleFailure")` on all paths so the caller/UX still sees a clean failure.
  - **Idempotency / persist-failure:** the reconciliation write is the *last* line of defence — if it throws, the refund attempt is lost from the DB (the exact failure this AC exists to stop). Make the compensating refund idempotent so a caller retry cannot double-refund: pass a deterministic Stripe idempotency key (`refund:{paymentIntentId}`) on the `refund(...)` call **and/or** check for an existing reconciliation row for that `paymentIntentId` before re-issuing. Persist the reconciliation row (or outbox entry) *before* — or in the same local transaction boundary as — the state that would trigger a retry, so a persist failure surfaces loudly (propagates as `payment.lifecycleFailure`) rather than silently dropping the record. Whichever mechanism, record it in the Dev Agent Record.
- **Files:** `SessionPackPaymentService.java`; possibly a new migration + repo/entity; `SessionPackPaymentServiceTest` / an IT.
- **Test:** unit — `refund()` throws an unchecked exception → outer `PaymentGatewayException("payment.lifecycleFailure")` still thrown **and** a reconciliation record persisted; `refund()` throws `PaymentGatewayException` → same; `refund()` succeeds → no reconciliation record; a second `purchasePack` for the same `paymentIntentId` after a refund failure does **not** issue a second refund (idempotency key or row check); happy path unchanged.
- **Ledger:** `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "`SessionPackPaymentService.purchasePack` compensating refund is best-effort only" (the `[PICKED UP by skillars-deferred-94 AC17]` javadoc-only note does not close the behaviour).

### AC2: Forced-termination escalation on the durable-work executor pools

- **Task:** Extend the `shutdownNow()` + bounded second-wait escalation currently applied only to `gracefulFixedPool` (used by `storageUploadExecutor`) to the `ThreadPoolTaskExecutor` pools that run durable or state-changing work — at minimum `outboxDrainPool`, `sendMailPool`, `sluRetryExecutor`, `moderationTaskExecutor`, `reportExecutor`, and `taskExecutor`.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java` — `gracefulFixedPool(...)` (L192+) escalates to `shutdownNow()` then waits `FORCED_TERMINATION_SECONDS` (L228–L232). Its javadoc (L77–L82) lists the sibling `ThreadPoolTaskExecutor` pools whose shutdown timeout is handled by Spring's `ExecutorConfigurationSupport.awaitTerminationIfNecessary`, which logs at WARN and **returns** — non-daemon workers can run on against a torn-down context after a failed `@SpringBootTest`, an `/actuator/restart`, or an embedded-container stop. `outboxDrainPool` drains refunds / transactional email / SLU — exactly the work that must not run half-torn-down.
- **Fix approach:** a `ThreadPoolTaskExecutor` subclass (or a `BeanPostProcessor` across the async config classes) that, after `awaitTermination` blows its budget, calls `getThreadPoolExecutor().shutdownNow()` and waits a short bounded slice. Total added worst-case wait must stay inside the `stop_grace_period` (55 s) headroom — size each pool's slice so the sum ≤ ~7 s (the current headroom the re-review names).
- **Files:** `ExecutorShutdown.java`, `AsyncConfig` / the executor `@Configuration` classes, `ExecutorShutdownConfigurationTest`.
- **Test:** `ExecutorShutdownConfigurationTest` asserts every named pool escalates to `shutdownNow()` on await-timeout (submit a non-terminating task, drive shutdown, assert interruption / forced termination within budget).
- **Ledger:** `## Deferred from: re-review of the applied skillars-deferred-92 patches (2026-09-04)` — "The forced-termination escalation covers one thread pool out of seven…".

### AC3: Eliminate the `NoSuchMessageException` 500 class for non-de/fr/en clients

- **Task:** Close the runtime-500 risk created by `MvcConfig.messageSource` `setFallbackToSystemLocale(false)` with no `setUseCodeAsDefaultMessage`: any key present in code but missing from **every** bundle (`messages_<locale>` *and* base `messages.properties`) throws `NoSuchMessageException` at request time for a locale outside de/fr/en.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/security/config/MvcConfig.java` (~L52) — confirm `setFallbackToSystemLocale(false)` present, `setUseCodeAsDefaultMessage` absent. Existing guards: `MessageBundleParityTest` (locale-to-locale parity), `MigrationConventionLintTest` (unrelated). No test asserts **base-bundle** completeness against the keys code actually resolves.
- **Fix approach (do both):**
  1. Add a build-time test that scans `src/main` for message-code string literals passed to `messageSource.getMessage(...)` / `@*Message` / validation `{...}` templates and asserts every one exists in base `messages.properties`. Scope pragmatically to the resolution helpers actually used (grep first). Fail the build on a gap. **Known limitation to document in the test javadoc:** it catches string-literal codes only — dynamically-constructed keys (`prefix + var`, `"key." + locale.getLanguage()`) are invisible to it, so runtime-2 (below) is the real safety net for those.
  2. As defence-in-depth, set `setUseCodeAsDefaultMessage(true)` **and** add a `MessageSource` wrapper (or `AbstractMessageSource` subclass) that logs `WARN` once per missing code, so a gap degrades to "code shown to user + WARN" instead of a 500. The "once" tracker must be **bounded** — a Caffeine cache (`maximumSize(1000)`, ~10-min expiry) or equivalent, not an unbounded `Set<String>`, so a production bug that resolves many distinct dynamic missing keys cannot leak memory. Keep de/fr/en fail-fast semantics via the parity test.
- **Files:** `MvcConfig.java`, a new `*MessageBundleCompletenessTest`, possibly a small `MessageSource` decorator.
- **Test:** the new completeness test (red if a code is missing from base); a slice/unit test that a deliberately-missing code returns the code + logs WARN rather than throwing.
- **Ledger:** `## Deferred from: code review of skillars-deferred-92 (2026-09-04)` — "`MvcConfig.messageSource` `setFallbackToSystemLocale(false)` with no `setUseCodeAsDefaultMessage`".

### AC4: Prove the OTP-resend 409 through the HTTP endpoint

- **Task:** Add integration coverage that drives `/api/security/coach/resend-otp` and `/api/security/player/resend-otp` into the `uq_pot_one_active_per_user` (V121) collision and asserts **HTTP 409** with body `security.otpResendInProgress` — not just the DB-layer `DataIntegrityViolationException`.
- **Verified at HEAD:** `CoachRegistrationResourceIT` / `PlayerRegistrationResourceIT` already have a `skillars-deferred-89 AC7: /resend-otp parity` block with `RESEND_OTP_ENDPOINT` constants and a "second used=false row … rejected by uq_pot_one_active_per_user" case, but grep finds **no** `otpResendInProgress` / `statusCode(409)` assertion in either file — the 409 is proven "spy-free at the DB" only. `ApiAdvice`'s `CONSTRAINT_MAPPINGS` entry for the constraint is present (verify). Mirror `ParentRegistrationResourceIT`'s resend-otp 409 case exactly.
- **Fix approach:** seed an active (used=false, unexpired) OTP row for the fixture user, call the endpoint a second time, assert `409` + `errorKey == "security.otpResendInProgress"`. Spy-free (no `@MockitoSpyBean` — respect the CI 37-context ceiling); reuse the existing `secondActiveOtpInsert_…` fixture shape.
- **Files:** `CoachRegistrationResourceIT.java`, `PlayerRegistrationResourceIT.java`.
- **Test:** the two new IT cases (this AC *is* the test).
- **Ledger:** `## Deferred from: code review of skillars-deferred-89-… (2026-09-01)` — "AC7's 409 path is never proven through the endpoint".

### AC5: `SmtpHealthIndicator` — bounded, cached, and implicit-TLS aware

- **Task:** Make the SMTP health probe (1) run its per-provider checks **in parallel** under an overall deadline, (2) serve a **short-TTL cached** aggregate rather than probing on every scrape, and (3) do a **TLS handshake** for implicit-TLS providers (port 465) instead of expecting a plaintext `220` banner.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/notification/health/SmtpHealthIndicator.java` — `SOCKET_TIMEOUT_MS = 5000` + `CONNECT_TIMEOUT_MS = 5000` (= 10 s/provider), `doHealthCheck` iterates `emailProperties.getProviderConfigs()` serially, no caching, no aggregate timeout. Reads a plaintext greeting via a raw `Socket` — a 465 endpoint expects TLS first and sends no plaintext `220`, so it always reports DOWN.
- **Fix approach:** submit per-provider probes to a small bounded executor (or `CompletableFuture.allOf` with `orTimeout`); **overall budget = `max(perProviderConnectTimeout + perProviderSocketTimeout)` + a fixed 1 s scheduling/overhead slack**, exposed as `app.notification.smtp-health.overall-timeout-ms` with that formula as the default so it can be tuned without a code change. Cache the resulting `Health` for `app.notification.smtp-health.ttl` (default 60 s) behind an `AtomicReference` with a timestamp; for `port == 465` (or a `ssl`/`implicitTls` provider flag) open an `SSLSocket` and treat a completed handshake as UP. **TLS trust policy:** production validates against the default JVM trust store (a bad chain ⇒ DOWN, which is correct); tests point at a stub with a self-signed cert, so the test wires a permissive `TrustManager` via the probe bean rather than the indicator hard-coding trust-all — state which in the Dev Agent Record. Keep this in its own health group so it stays out of the deploy smoke-test aggregate (as `deferred-96` AC2 established).
- **Files:** `SmtpHealthIndicator.java`, `application.yaml` (ttl property + group already present), a probe-executor bean, `SmtpHealthIndicatorTest` / IT.
- **Test:** N configured providers complete within ≈ one timeout, not N×; two calls inside the TTL issue only one round of socket probes; a 465 provider reports UP against a TLS-capable stub and DOWN when the handshake fails.
- **Ledger:** `## Deferred from: code review of skillars-deferred-96 (2026-09-07)` — both `SmtpHealthIndicator` bullets (serial/no-TTL; port-465 always DOWN).

### AC6: Deploy smoke-step *error* handling — verify the deferred-96 fix, close the residual

- **Task:** Confirm the `deferred-94` code-review bullet ("Smoke step erroring, not `result=fail`, produces a red deploy run with no notification and no auto-revert") is closed by what shipped in `deferred-96` (`542eab1`), close the one narrow gap that remains, and annotate the ledger.
- **Verified at HEAD (`542eab1` on `master`, ancestor of this branch — this AC's premise was written against the pre-`542eab1` tree):** `.github/workflows/deploy.yml` **already** carries the fix the bullet asked for:
  - `Smoke test` step: `set +e`, `echo "result=$RESULT" >> $GITHUB_OUTPUT` in the subshell, **plus** a fallback `if ! grep -q "^result=" $GITHUB_OUTPUT; then echo "result=error" ...` after it, then `exit $EXIT_CODE`; `timeout-minutes: 15`; ssh calls use `-o ConnectTimeout=10 -o BatchMode=yes` (deploy.yml ~L91–120).
  - `Auto-Revert` gate: `failure() && (steps.smoke.outputs.result == 'fail' || steps.smoke.outcome == 'failure')` (~L124).
  - `Notify Slack — failure & revert` / `Email — failure & revert`: `always() && (result == 'fail' || result == 'error' || outcome == 'failure' || outcome == 'cancelled')` (~L161/L190).
  - `Fail workflow on smoke test failure`: `always() && (result == 'fail' || outcome == 'failure' || outcome == 'cancelled')` (~L209).
  - Pre-smoke early-failure notifies deliberately stay scoped to `steps.smoke.outcome == 'skipped'` (~L221/L232) with an inline comment that `'failure'` must **not** be matched there or every failure double-notifies. This is why the story's original "`guard on outcome != 'success'`" phrasing is *not* the right fix — it would collapse that split.
- **Residual to close (the only genuine gap):** if the smoke poll *passes* (`exit 0`) but the `$GITHUB_OUTPUT` write itself fails, `result` is empty, `outcome == 'success'`, and **neither** the success-notify (`success() && result == 'pass'`) **nor** any failure path fires — a silent no-op on a "green" run. Make the success path also require a positive signal / treat "passed but no `result`" as an error: e.g. `success() && steps.smoke.outputs.result != 'pass'` → route to the failure-notify, or make the fallback `echo` write to a second file the step can re-read. Keep the change minimal.
- **Coordination:** `deferred-97` AC3/AC4 are earmarked for the smoke poll-window and the "ssh failure swallowed to `echo 0`" bullets — do **not** touch those loops here; this AC is only the error-visibility residual + ledger close.
- **Files:** `.github/workflows/deploy.yml`.
- **Test:** workflow, not app code — code inspection (the residual conditional added) + a forced-error dry run on a throwaway branch (`kill $$` mid-smoke → expect Slack/email failure notification + `Fail workflow` red; a passing run with a chmod-blocked `$GITHUB_OUTPUT` → expect failure-notify, not silence).
- **Ledger:** `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "Smoke step erroring (not `result=fail`) …". Mark `[CLOSED by skillars-deferred-99 AC6]` (the substance shipped in `deferred-96`; this AC finishes the tail). Feeds AC17.

### AC7: `MigrationLint` reference scan — cache the tree walk, use word boundaries

- **Task:** `MigrationLint`'s dropped-identifier reference scan must walk `src/main` **once** (build an index), not once per dropped identifier; and `MigrationConventionLintTest.realMigrations_aboveBaseline_areClean` must match identifiers on **word boundaries** so an unrelated class containing a dropped name as a substring cannot break the build on an untouched migration.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/db/MigrationLint.java` — **the word-boundary half already landed**: `referencesIn(...)` (~L806) builds `tablePattern` / `identifierPattern` / `camelPattern` via `wordBoundary(token)` = `Pattern.compile("\\b" + Pattern.quote(token) + "\\b")` (~L850), and the javadoc records the "bare substring match made every `id`-class column name unusable (matched `id` inside `Invalid`)" fix. **So this AC reduces to the caching half + a regression test.** The re-walk *is* still present: `referencesIn` is called once per dropped identifier (~L737) and each call does `Files.walk` + `Files.readString` over every `.java/.sql/.yaml/.yml/.xml/.properties/.html/.json` under the source roots.
- **Fix approach:** read each in-scope file under the source roots once into an in-memory map (path → file body, or a pre-tokenised `Set<String>`); resolve every dropped identifier against that map; keep the existing `\b`-anchored matching. Keep the fixture-corpus path (`FIXTURE_SOURCES`) behaviour identical. Scope note: this path only runs for above-`DEFERRED_92_BASELINE` migrations with an unmarked `DROP`, so the "fails in milliseconds" claim already holds for the real corpus — the win is bounding the fixture-corpus cost and removing the O(identifiers × tree) shape.
- **Files:** `MigrationLint.java`, `MigrationConventionLintTest`.
- **Test:** (a) a throwaway class whose name *contains* a real dropped identifier as a substring (e.g. dropped `player_id`, class mentions `player_id_new` alongside the owning table) → lint stays green, proving `\b` anchoring holds for underscore-adjacent names; (b) the same dropped identifier as a bare token in a real reader → lint still fires. A timing/allocation assertion is optional (don't make it flaky).
- **Ledger:** `## Deferred from: code review of skillars-deferred-92, chunk 2 (2026-09-04)` — the `MigrationLint` re-walk / real-tree fragility bullet.

### AC8: Migration online-safety — pay the owed conventions debt (docs + checklist, no rewrites)

- **Task:** Add the pre-production trigger note the `deferred-92` work explicitly left owed for `V60` / `V94` / `V117` to `docs/deployment/migration-conventions.md`, plus a concise "expand/contract go-forward checklist" section. **Do not** edit any applied migration (Flyway checksums whole files).
- **Verified at HEAD:** `deferred-92` story-creation notes — "The pre-production trigger for `V60`/`V94`/`V117` in `docs/deployment/migration-conventions.md` is unaffected and still owed." Multiple ledger bullets (`uat-3` D13, `deferred-33` `V97`, `deferred-40` `V98`, `deferred-84` `V117`) name the same `ACCESS EXCLUSIVE` / unbatched / non-`CONCURRENTLY` class. `MigrationConventionLintTest` already enforces the mechanical subset for `V128+`.
- **Fix approach:** in `migration-conventions.md`, add a section that (1) lists `V60`, `V94`, `V117` (and `V97`, `V98`) as known pre-production exceptions with the specific lock/scan hazard each carries, (2) states the required action before the first production-scale deploy (re-issue as `ADD CONSTRAINT … NOT VALID` + later `VALIDATE`, `CREATE INDEX CONCURRENTLY`, batched backfill), (3) points at `MigrationConventionLintTest` as the forward guard. Optionally add a `PR-checklist` line.
- **Files:** `docs/deployment/migration-conventions.md`, optionally `.github/pull_request_template.md`.
- **Test:** doc change — none; ensure `MigrationConventionLintTest` still passes.
- **Ledger:** consolidates `uat-3` D13, `deferred-33`, `deferred-40` `V98`, `deferred-84` `V117`, and the `deferred-92` "pre-production trigger … still owed" note into one **[PICKED UP / partially closed]** annotation (the doc obligation closes; the eventual migration re-issue stays a pre-prod task).

### AC9: `player_slu_weekly_snapshot_applied` retention/pruning

- **Task:** Add a scheduled pruning job for `development.player_slu_weekly_snapshot_applied` — its 5-column PK is led by a random session UUID and rows accumulate for the lifetime of the deployment, removed today only by the per-player GDPR-erasure cascade.
- **Verified at HEAD:** `src/main/resources/db/migration/V119__player_slu_weekly_snapshot_applied.sql` — marker table, `player_id -> main.player_profiles(id) ON DELETE CASCADE`, no session FK, no pruning job. Growth is low (one row per session × skill × ISO-week) — this is housekeeping, not urgent, but unbounded.
- **Fix approach:** a `@Scheduled` + `@SchedulerLock` service in `platform.development.service` that deletes marker rows older than `app.slu.snapshot-applied.retention-days` (default 90) — the markers only exist to make `upsertAddIdempotent` idempotent across a retry window measured in minutes, so a 90-day floor is very safe. Batch the delete (`DELETE … WHERE ctid IN (SELECT ctid … LIMIT :batch)` loop) per `migration-conventions.md` — this is the house pattern; the subselect+delete run in one snapshot-consistent statement, so a concurrent write cannot make it delete an unintended row. Add the row age via `applied_at`/`created_at` — if V119 has no timestamp column, add one nullable via a new additive migration. **NULL policy — pick one and record it:** either backfill existing rows to the migration's deploy date in that same migration (then NULL never occurs and everything ages out), or accept "NULL = keep forever" explicitly and note that pre-migration rows are then only ever removed by the GDPR cascade. Default recommendation: backfill to deploy date — it is a marker table, the rows carry no audit value.
  - **Failure visibility:** wrap the job body in try/catch that logs `WARN`/`ERROR` on failure and increments a `slu.snapshot_applied.prune.failures` counter (mirror the `@SchedulerLock` job pattern already used elsewhere in `platform.development`), so a silently-failing job does not let the table grow unbounded unnoticed.
- **Files:** new pruning service + config property; possibly a new additive migration; an IT seeding old + recent markers.
- **Test:** IT — seed markers with `created_at` older/newer than the window, run the job, assert only stale rows removed and idempotency of `upsertAddIdempotent` is unaffected for recent sessions.
- **Ledger:** `## Deferred from: code review of skillars-deferred-86-… (2026-08-31)` — the `player_slu_weekly_snapshot_applied` retention bullet.

### AC10: `PessimisticLockRetryer` — bound and instrument the connection hold

- **Task:** The retry loop sleeps while holding the transaction's pooled JDBC connection (up to the ~3.2 s documented budget under contention). Make the budget explicitly configurable, add a metric for total wait time and retry count, and state the connection-hold characteristic plainly in the class javadoc and the architecture/project-context notes.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java` — savepoint-based retry-in-place (Spring's declarative `Propagation.NESTED` is unavailable — `DefaultJpaDialect` has no savepoint support, per the Dev Agent Record), so the sleep necessarily holds the connection. All 16 current call sites are read-only (`findByIdForUpdate` + optional `refresh`).
- **Fix approach:** externalise the retry budget / backoff to `app.persistence.lock-retry.*` config; wrap the loop in a Micrometer `Timer` (`persistence.lock_retry`) tagged by outcome, and a counter for attempts; expand the javadoc's warning; add a one-liner to `project-context.md` / architecture doc so the pooled-connection hold is a documented, sized tradeoff rather than a latent surprise. Do **not** restructure to a connection-releasing mechanism (out of scope; dialect limitation stands).
- **Files:** `PessimisticLockRetryer.java`, a config properties record, `project-context.md` (or `architecture.md`), `PessimisticLockRetryerTest`.
- **Test:** unit — budget honoured from config; timer/counter emitted on contention; behaviour unchanged for the no-contention path.
- **Ledger:** `## Deferred from: code review of skillars-deferred-62-… (2026-08-24)` — the "sleeps while still holding the transaction's pooled JDBC connection" bullet (the `Supplier` side-effect contract and savepoint-call-failure bullets stay open — note them, don't close).

### AC11: `long` overflow guard in `QuotaService` storage/bandwidth arithmetic

- **Task:** Guard `storageUsedBytes + requestedBytes` (and the bandwidth equivalent) against `long` overflow — today an overflow wraps negative and silently *passes* the quota check.
- **Verified at HEAD:** `QuotaService.check` (`src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java:45`) — `quota.getStorageUsedBytes() + activeReservedBytes + requestedBytes <= storageQuota`, a **three-term** `long` sum, no guard. `deferred-work.md` `## Deferred from: code review of skillars-6-1-… (2026-06-20)` Def3 confirms: "theoretical at practical quota sizes (max ~9.2 EB); no guard exists." Re-check `reserve` and any bandwidth sibling for the same shape.
- **Fix approach:** prefer the **pre-check** `requestedBytes > storageQuota - used - activeReservedBytes` (subtraction on non-negative operands cannot overflow, and it reads as intent) over `Math.addExact`. If `Math.addExact` is used instead, on `ArithmeticException` log at **ERROR** ("quota arithmetic overflow — used/reserved/config out of range") *and then* return the `QUOTA_EXCEEDED`-class rejection: an overflow at these magnitudes is a data/config bug, not a normal over-quota request, and must not be silently indistinguishable from one. Same treatment for the bandwidth accounting if it shares the pattern.
- **Files:** `QuotaService.java` (+ bandwidth sibling), `QuotaServiceTest`.
- **Test:** unit — `used` near `Long.MAX_VALUE`, `requested` large → clean `QUOTA_EXCEEDED` rejection, not a wrap-around pass.
- **Ledger:** `## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)` — Def3.

### AC12: `BandwidthResetService` period-drift fix

- **Task:** Anchor `bandwidth_period_start` to the **calendar** first-of-month, not `NOW()` on the actual (possibly late) run date, so a delayed job run doesn't permanently shift the monthly boundary.
- **Verified at HEAD:** `deferred-work.md` `## Deferred from: code review of skillars-6-1-… (2026-06-20)` Def8: "`BandwidthResetService` period drift when job runs late — `bandwidth_period_start` set to `NOW()` on actual run date, not 1st of month." Re-verify `src/main/java/.../video/service/BandwidthResetService.java` (method `resetMonthlyBandwidth`) at HEAD.
- **Fix approach:** compute the period start as `YearMonth.now(clock).atDay(1).atStartOfDay(ZoneOffset.UTC)` (use `ClockProvider`), independent of when the job actually fires; keep the reset idempotent (a second run in the same month is a no-op).
- **Files:** `BandwidthResetService.java`, its test (unit or IT) with a pinned late-run clock via `TestClockProvider`.
- **Test:** pin the clock to e.g. the 4th of the month, run the reset, assert `bandwidth_period_start` == 1st 00:00 UTC; run again → no-op.
- **Ledger:** `## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)` — Def8.

### AC13: De-flake `DrillUploadServiceConcurrencyIT` lock-causality assertion

- **Task:** Replace the wall-clock timing assertion in `deleteVideo_videoRowHeldByAnotherTransaction_waitsOutTheLockBeforeCompleting` (`elapsedMillis >= holdMillis - 200` after a hardcoded ~1200 ms external hold) with a deterministic ordering signal.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java` — `deferred-83` code review flagged the timing assertion as a known CI-flakiness class; the 200 ms tolerance was a deliberate fallback after a Mockito-based approach proved infeasible against the Spring Data repo interface.
- **Fix approach:** use a `CountDownLatch`/`Phaser` so the test thread releases the external lock only *after* observing the delete thread is blocked on it (e.g. poll `pg_stat_activity` / `pg_locks` for the waiter, or a `TransactionTemplate` + latch handshake), then assert the delete completes *after* release — an ordering assertion, not a duration one. **Every `await` / poll must be bounded** (`latch.await(30, SECONDS)` and `assertThat(awaited).isTrue()`, an Awaitility `atMost(...)` on the `pg_stat_activity` poll) so a broken handshake fails the test fast instead of hanging the IT (and the Failsafe run) indefinitely. Keep the existing file's other timing tests untouched unless trivially convertible.
- **Files:** `DrillUploadServiceConcurrencyIT.java`.
- **Test:** the rewritten case is the test — it must fail if the in-service pessimistic lock is removed and pass deterministically with it.
- **Ledger:** `## Deferred from: code review of skillars-deferred-83-… (2026-08-30)` — the wall-clock-timing bullet.

### AC14: Drill-video load-failure UX — auto-retry once, then a toast

- **Task:** When a drill video fails to load (expired signed URL, network), stop the current silent background refetch-with-no-feedback behaviour: retry **once** with a freshly-requested signed URL, and if it still fails show a dismissible error toast (i18n key) and an inline "unavailable" state on the player.
- **Verified at HEAD:** `deferred-work.md` `## Deferred from: code review of skillars-deferred-75 (2026-08-27)` — "video load fails … code silently refetches drill data in background. Video element remains broken with no user notification. [`DrillCard.vue`, `DrillDetailPanel.vue`, and page handlers]". Re-check those components + the pages that host them at HEAD; identify the existing refetch path.
- **Fix approach:** on the `<video>` / player `error` event: (1) call the drill/video API once more to get a fresh signed URL and re-set `src`; (2) if the retry's load also errors, emit a `$q.notify` error toast with a new `development.*` / `video.*` i18n key (add to `en-US`, `de-DE`, `fr-FR` — parity test will enforce), and render an inline "Video unavailable" panel instead of a broken element; (3) do not loop. Keep `getSkillTrends`-style API additions out of scope — reuse the existing video URL endpoint.
  - **Lifecycle / spam guards:** guard the retry's async continuation against an unmounted component — capture an `isActive` ref (or `onScopeDispose`/`onBeforeUnmount` flag, or an `AbortController` on the fetch) so a retry that resolves after the user has navigated back to the skill list does **not** fire a toast on a page they left. De-dupe toasts per video id (only the first failure for a given `drillId`/`videoId` in a short window shows a toast; the inline "unavailable" state carries the rest) so repeatedly clicking play on a permanently-broken video does not stack notifications.
- **Decision (project owner, 2026-09-07):** **toast + one auto-retry** (not a manual-retry button, not "leave silent").
- **Files:** `src/frontend/src/components/session/DrillCard.vue`, `DrillDetailPanel.vue`, the drill/session page handlers, the 3 i18n bundles.
- **Test:** no frontend unit harness (see `frontend-test-framework-initiative` backlog) — verify by `eslint` + `prettier` + `quasar build` green and a manual dev-server exercise with a deliberately-expired URL; document the manual steps in the story's Dev Agent Record, matching this repo's established frontend-verification path.
- **Ledger:** `## Deferred from: code review of skillars-deferred-75 (2026-08-27)` — the video-error-handling-UX bullet.

### AC15: Non-gating `authorizePlayback` latency signal

- **Task:** Replace the unscraped `log.info` of `authorizePlayback` p50/p95/p99 (left in `PlaybackServiceIT` when `deferred-89` AC5 removed the flaky `p99 < 200ms` merge gate) with a real, **non-gating** latency signal in production code.
- **Verified at HEAD:** `deferred-work.md` `## Deferred from: skillars-deferred-89 story creation (2026-08-31)` — "No non-gating perf-trend signal for `authorizePlayback` … a dedicated non-gating perf-tracking job that records the distribution over time is not built." `PlaybackServiceIT` at HEAD still logs to Failsafe output nothing scrapes.
- **Fix approach:** add `@Observed(name = "video.playback.authorize")` (or an explicit Micrometer `Timer`) around `PlaybackService.authorizePlayback` so p50/p95/p99 land in the existing Prometheus/OTLP pipeline — the same observability stack the rest of the app uses. **No merge gate, no scheduled job** — the metric backend does the aggregation over time. Remove the dead perf-logging from `PlaybackServiceIT` (or convert it to a plain functional assertion). Mirror the read-time-derivation philosophy of `deferred-98`'s SLU trend signal: derive from what's already emitted, add no new storage.
- **Files:** `PlaybackService.java` (annotation/timer), `PlaybackServiceIT.java` (drop the log), possibly a Grafana panel note in `docs/`.
- **Test:** a slice/unit test asserting the timer/observation is registered and records on an `authorizePlayback` call; `PlaybackServiceIT` still green.
- **Ledger:** `## Deferred from: skillars-deferred-89 story creation (2026-08-31)` — the `authorizePlayback` non-gating-perf-signal residual.

### AC16: Configurable per-market phone & national-id validation

- **Task:** Remove the hard-wired Cameroon assumptions from phone / national-id validation and its user-facing copy: the English source strings say a number must be "exactly 9 digits", "start with digit 6", "(MTN, Orange, NextTel)", and `auth.nationalId` renders "Numéro CNI". Make the rule **config-driven per market**, defaulting to permissive international.
- **Verified at HEAD:** `deferred-work.md` `## Deferred from: skillars-deferred-92 story creation and implementation (2026-09-04)` — the `validation.phone.*` / `auth.nationalId` Cameroon-drift bullet; and `## chunk 3` — `auth.phoneHintFormat` "9 digits starting with 6" translated verbatim into de-DE/fr-FR. Re-verify the validator (`IanaTimezoneValidator` neighbour — likely a `@CamPhone` / phone constraint under `infrastructure/validation`) and the three i18n bundles at HEAD.
- **Decision (project owner, 2026-09-07):** **configurable per-market** — a config-driven regex + hint key per market, default permissive E.164-ish.
- **Fix approach:**
  - Introduce `app.validation.phone` config: `default-pattern` (permissive: optional leading `+`, then 7–15 digits — this is a *minimum-viable* rule, not strict E.164; it deliberately does not try to cover short codes or extensions), `default-hint-key`, and an optional `markets: { <code>: { pattern, hint-key } }` map.
  - **Market resolution — define it explicitly (this is the open question the AC must answer, not leave to dev):** resolve the active market with a fixed precedence — (1) an explicit request signal if one exists (e.g. a `market` field on the registration DTO / an `X-Market` header), else (2) the authenticated user's stored profile market if present, else (3) the region subtag of the resolved request `Locale` (`fr-CM` → `CM`) mapped through the `markets` map, else (4) the default (permissive) pattern. A `Locale` with no region (bare `fr`) falls straight through to the default — never guess a country from a language. Put this resolver in one place (a `MarketResolver` bean) so the validator, the hint-key lookup, and any future market-driven copy all share it. Record the chosen signal names in the Dev Agent Record.
  - Rewrite the phone constraint validator to resolve pattern + hint from config via that resolver (fall back to the default). Keep the annotation; change its backing logic. Preserve the existing `CamPhoneValidator` pipe-template message pattern (per `deferred-18`'s recorded lesson about `LangIso2`-style bare templates resolving to nothing).
  - Replace the Cameroon-specific text in `validation.phone.*`, `auth.phoneHintFormat`, `auth.nationalId` across `en-US` / `de-DE` / `fr-FR` with locale-neutral / market-driven wording (`auth.nationalId` → a generic "ID / passport number" label unless a market config says otherwise). Parity test enforces the three bundles.
  - Rename the constraint from `@CamPhone` → `@Phone` (or keep the annotation name, note the misnomer) — pick the lower-churn option and record it.
- **Files:** the phone validator + a `@ConfigurationProperties` record under `infrastructure/validation` or `config`, `application.yaml` (defaults), the 3 i18n bundles, every DTO using the constraint (compile check), validator tests, `CoachProfileBuilderIT` / registration ITs.
- **Test:** unit — default config accepts `+15551234567` and `77012345` and rejects `abc` / a 4-digit string; a configured market pattern is enforced when the caller's market resolves to it (via each precedence tier); a bare-language `Locale` (`fr`, no region) falls through to the default pattern, not a guessed country; `@Valid` cascade still fires (regression against `deferred-18`'s `@Valid`-cascade trap). i18n parity test green.
- **Ledger:** closes the `deferred-92` `validation.phone.*` / `auth.nationalId` bullet and the chunk-3 `auth.phoneHintFormat` bullet (both were flagged as *product* questions — this AC carries the project-owner decision).

### AC17: Ledger hygiene — prune the verified-stale `1-7` section, cross-reference the rest

- **Task:** Apply `deferred-work.md`'s own delete-outright convention to items this story's verification pass proved resolved, and add `[CLOSED by skillars-deferred-99 ACn]` (or `[PICKED UP …]`) annotations to the bullets this story closes.
- **Verified at HEAD (stale — delete):**
  - `## Deferred from: code review of 1-7-session-refresh-mechanism-fix (2026-09-02)` — **entire section**:
    - "Three sibling docs still assert `rint` TTL = 15 min" → `security-api-endpoints.md:252` and `frontend-integration-guide.md:1076` now say `rint = JWT_TTL + 60s (~16 min)`, absolute epoch ms. Fixed.
    - "Prettier fails on the two touched frontend files at `HEAD`" → `npx prettier --check src/App.vue src/boot/axios.js` passes at `69a1ca8` (deferred-92 AC1 + CI gate). Fixed.
    - "1.7b promoted to ready-for-dev with prerequisite unclosed" / "Story file and all three 1.7b artifacts are untracked" → `skillars-1-7b-…` is `done`; `1-7-REVIEW-UPDATES.md`, `1-7b-ARCHITECTURAL-DECISION.md`, `1-7b-session-refresh-rint-contract-fix.md` are git-tracked. Moot.
    - "'5 minutes before expiry' is stated as exact" → `session-refresh-mechanism.md` now frames `WARNING_THRESHOLD` as a fixed client-side constant with an explicit Known-Limitations note on the 30 s tick imprecision (L64–65, L535, L602). Defused; delete or leave a one-line "documented tradeoff" note — dev's call, record which.
    - "`story-review.md` is a rotating single file" → recurring process observation already recorded three more times later in the ledger; delete this instance (keep the most recent).
  - `## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)` and `## … (round 2) …` — the `sessionManager.js` "zero frontend tests" and "early return leaves no timer armed" bullets are superseded: the early-return decision is documented in code (deferred-98 AC2, rewritten), and the frontend-test gap is now consolidated in `frontend-test-framework-initiative`. Replace both with a single pointer to that backlog item + the deferred-98 comment, or delete if fully covered — verify against `sessionManager.js` at HEAD first.
- **Close on completion:** annotate `[CLOSED by skillars-deferred-99 ACn]` on the bullets AC1–AC16 resolve (deferred-94 purchasePack, deferred-92 thread-pool + MvcConfig + MigrationLint, deferred-89 AC7 + authorizePlayback, deferred-96 SmtpHealthIndicator ×2, deferred-94 deploy-smoke-error, deferred-86 retention, deferred-62 connection-hold [partial], deferred-6-1 Def3 + Def8, deferred-83 timing, deferred-75 video UX, deferred-92 phone/nationalId + chunk-3 phoneHintFormat), then delete per convention if the whole section empties (AC8/AC10 are **partial** → `[PICKED UP]`, not deleted).
- **Fix approach:** mechanical, verified line-for-line before writing (every surviving line must appear in the pre-edit file, same order, nothing reworded) — the same discipline the 2026-08-24 / 2026-09-04 prune passes used.
- **Files:** `_bmad-output/implementation-artifacts/deferred-work.md`.
- **Test:** none (ledger). Sanity: `grep -c "## Deferred from:"` before/after; confirm no open item text was lost.
- **Ledger:** this AC operates on the ledger itself.

---

## Tasks / Subtasks

- [x] **T1 — Payment refund safety (AC1)**
  - [x] Widen the inner refund catch to `Exception`; keep the outer `payment.lifecycleFailure` throw on every path
  - [x] Add a minimal additive `V130__stripe_refund_failures.sql` + entity/repo + `RefundReconciliationService` (REQUIRES_NEW); idempotency key on the refund call
  - [x] Unit tests: unchecked refund throw, checked refund throw, refund success (all still wrap; only failures record)
- [x] **T2 — Executor forced-termination (AC2)**
  - [x] `GracefulShutdownTaskExecutor` subclass escalating to `shutdownNow()` + bounded wait
  - [x] Apply to `outboxDrainPool`, `sendMailPool`, `sluRetryExecutor`, `moderationTaskExecutor`, `reportExecutor`, `taskExecutor`; ≤6 s escalation within the ~7 s headroom
  - [x] `ExecutorShutdownConfigurationTest` per-pool escalation assertions
- [x] **T3 — MessageSource robustness (AC3)**
  - [x] Base-bundle completeness test (`MessageCodeBaseBundleCompletenessTest` — literal `.getMessage("x.y")` + `message="{x.y}"` + every `EmailTemplate.*.subjectKey()`; documents dynamic-key limitation)
  - [x] `setUseCodeAsDefaultMessage(true)` + `WarnOnMissingMessageSource` (bounded LRU warn-once); de/fr/en fail-fast still via parity test
- [x] **T4 — OTP-resend 409 IT (AC4)**
  - [x] `CoachRegistrationResourceIT` + `PlayerRegistrationResourceIT`: `resendOtp_concurrentActiveOtpInsert_returns409_otpResendInProgress` — holder tx + `pg_stat_activity` lock-wait poll drives the endpoint into `uq_pot_one_active_per_user`, asserts 409 + `security.otpResendInProgress` (spy-free)
- [x] **T5 — SmtpHealthIndicator (AC5)**
  - [x] Parallel probes on a bounded pool + overall deadline; short-TTL `AtomicReference` cache; `probeImplicitTlsConnection` (SSLSocket handshake) for port 465 / `implicit-tls: true`; `SmtpHealthProperties` config
  - [x] Tests: parallel ≈ 1× timeout; TTL dedupe (1 probe on 2 scrapes); 465 UP/DOWN via handshake seam
- [x] **T6 — Deploy smoke-error visibility (AC6)**
  - [x] Verified `542eab1` already covers the `deferred-94` bullet (outcome-guards, `result=error` fallback, `timeout-minutes`, ssh `ConnectTimeout`)
  - [x] Residual closed: smoke step `exit 1` if `result` can't be persisted; `Fail workflow` marker also catches `outcome==success && result!=pass`
  - [ ] Forced-error dry run on a throwaway branch (manual, pre-merge); ledger `[CLOSED by … AC6]` annotation done in T17
- [x] **T7 — MigrationLint caching + word boundaries (AC7)**
  - [x] `sourceCorpus(roots)` — walk + read the source tree once, memoised (`SOURCE_CORPUS_CACHE`); `referencesIn` resolves against it. `\b` matching confirmed already landed.
  - [x] `referenceScan_underscoreAdjacentName_isNotAMatch` regression test (dropped `player_session_id` vs `player_session_id_new`, + bare-token control)
- [x] **T8 — Migration conventions debt (AC8)**
  - [x] `migration-conventions.md`: added a scannable **Go-forward checklist** + a **Pre-production migration debt** pointer near the top; the full V60/V94/V117 redo trigger + per-migration table were already there (deferred-91 D7). Doc-only.
- [x] **T9 — SLU marker retention (AC9)**
  - [x] V119 already has `applied_at TIMESTAMPTZ NOT NULL DEFAULT now()` — no migration, no NULL policy needed.
  - [x] `SluSnapshotAppliedRetentionService` (`@Scheduled` cron + `@SchedulerLock`, `platform.development.service`); `pruneAppliedBefore` ctid-batch native delete; `app.slu.snapshot-applied.{retention-days:90, prune-cron}`; try/catch + `slu.snapshot_applied.prune.{deleted,failures}` counters
  - [x] `SluSnapshotAppliedRetentionServiceIT` (stale removed, recent kept, no-op when nothing stale)
- [x] **T10 — PessimisticLockRetryer (AC10)**
  - [x] Retry budget/backoff was already `app.locking.retry.*`; added `MeterRegistry` (constructor), `persistence.lock_retry` timer (tag `outcome`) + `.retries`/`.exhausted` counters; expanded class javadoc (connection-hold cost model) + architecture.md note. 2 new unit tests.
- [x] **T11 — Quota overflow guard (AC11)** — `exceedsQuota(...)` helper using `Math.addExact` (ERROR-logs a real overflow, then rejects); applied to `check` and `reserve`. Bandwidth counter gates nothing / SQL-side → not touched. Near-`MAX_VALUE` unit test.
- [x] **T12 — Bandwidth period anchor (AC12)** — `BandwidthResetChunkProcessor` stamps `bandwidth_period_start = YearMonth.from(clock-now UTC).atDay(1).atStartOfDay(UTC)`; both the SET value and the predicate's "current month" are bound params from one `ClockProvider` instant (deterministic under a pinned clock). New IT: pinned to the 4th → period start is the 1st; re-run no-op.
- [x] **T13 — De-flake concurrency IT (AC13)** — `deleteVideo_videoRowHeldByAnotherTransaction_...` now: holder holds the lock until a latch (not `sleep(1200)`); assert `deleteVideo` is still blocked (`contender.get(750ms)` → `TimeoutException`) while held, then release, then it completes. Ordering assertion, no `elapsedMillis` tolerance. Postgres-specific note added. Other timing tests in the file untouched.
- [x] **T14 — Video load-failure UX (AC14)** — new `useDrillVideoPlayback` composable (retry-once → toast + inline "unavailable" tile; per-drill-id state reset; module-level toast de-dup; `onBeforeUnmount` active-flag guard). Wired into `DrillCard.vue` + `DrillDetailPanel.vue` (`:key="drill.videoUrl"` reload, `v-else-if="videoFailed"` tile); pages' `handleVideoError` reduced to the silent refetch. `session.drillLibrary.videoUnavailable` added to en-US/de-DE/fr-FR. `eslint` + `prettier` + `quasar build` all green.
- [x] **T15 — authorizePlayback latency signal (AC15)** — production `@Observed(name="video.playback.authorize")` + `VideoMetrics.recordPlaybackAuthorizeLatency` already exist on the 4-arg `authorizePlayback` (`VideoPlayResource`'s call path). Removed the dead p50/p95/p99 `log.info` loop from `PlaybackServiceIT`; added `authorizePlayback_recordsNonGatingLatencyObservation` asserting the timer registers + records.
- [x] **T16 — Configurable phone/national-id validation (AC16)**
  - [x] `PhoneValidationProperties` (`app.validation.phone`: `default-pattern` `^\+?\d{7,15}$`, `default-hint-key`, `markets` map) + `ValidationConfig`
  - [x] `MarketResolver` (explicit signal → [profile market: inert, no field yet] → locale region subtag → default; bare-language locale falls through, never guesses)
  - [x] `PhoneRuleRegistry` (compiles the market-resolved pattern; static publish for the `ConstraintValidator`; bad-pattern → permissive fallback). `CamPhoneValidator` rewritten config-driven, keeps `@CamPhone` name (noted misnomer) + pipe-template message
  - [x] `PhoneNumberUtil.fromString` made best-effort — a non-CM number no longer throws, stored with `provider=null`
  - [x] Locale-neutral copy: backend `validation.phone.{digitCount,firstDigit,operator}` + new `hintDefault` (all 4 bundles, parity green); frontend `auth.phoneHintFormat` + `auth.nationalId` (en/de/fr)
  - [x] `PhoneValidationConfigTest` (8 tests: resolver precedence, default/market/bad pattern, validator accepts `+15551234567`/`77012345`, rejects `abc`/4-digit)
  - **Residual (recorded):** operator-level identification (`Provider` enum) stays CM-only — a second market needing it requires a provider abstraction, out of scope. `CamMobileValidator.validate`'s throwing contract unchanged for direct callers.
- [x] **T17 — Ledger hygiene (AC17)**
  - [x] Deleted the `## Deferred from: code review of 1-7-session-refresh-mechanism-fix (2026-09-02)` section (all 6 bullets verified stale line-for-line at master; a `<!-- ... -->` breadcrumb records the verification); added a `[NOTE ...]` pointer on the 1-7b `sessionManager.js` bullet (`frontend-test-framework-initiative` + deferred-90 rationale in code) — the two 1-7b bullets are deliberate documented tradeoffs, not stale, so kept.
  - [x] `[CLOSED by skillars-deferred-99 ACn]` on the bullets AC1–AC7, AC9, AC11–AC16 close; `[PICKED UP …]` on AC8 (doc obligation) and AC10 (instrumentation; connection-releasing redesign stays open). Header count 110 → 109.

---

## Dev Notes

### Non-negotiables (project-context.md)

- **No local `mvn verify`.** GitHub CI is the sole full-verification gate. Local bar: `mvn -o test-compile` + `mvn -o test -Dtest=<touched classes>`.
- Java 17 / Spring Boot 3.5.11. Request/response DTOs are `record`s. `@PreAuthorize` with `SecurityConstants` on every resource method. `@Observed(name=...)` for metrics. Jakarta Validation on request records.
- Flyway only for schema. **Every migration `> V121` follows `docs/deployment/migration-conventions.md`** (expand/contract; additive first; guarded `DROP` last; FK/CHECK `NOT VALID` then `VALIDATE`; `CREATE INDEX CONCURRENTLY` on hot tables; batched backfills). `MigrationConventionLintTest` runs in the `test` phase and fails the build on the mechanical subset — an `-- migration-lint: allow-*` opt-out needs a real reason.
- Package hierarchy `com.softropic.skillars.platform.{module}.{layer}` (`api` / `service` / `repo` / `contract` / `config`). Schedulers driving domain lifecycle live in `platform.{module}.service`, not `infrastructure`.
- Frontend: Quasar 2.16 / Vue 3.5 `<script setup>`, `async/await` (no `.then()`), all API calls in `src/frontend/src/api/*.api.js`, all user-facing text via `vue-i18n`. **Prettier mandatory** for `.js/.vue/.scss/.json`. No frontend unit-test harness exists (`frontend-test-framework-initiative` backlog) — frontend ACs verified by `eslint` + `prettier` + `quasar build` + documented manual dev-server exercise.
- Testing: `@SpringBootTest` + `@Testcontainers` for ITs (no DB mocking), **Instancio** for DTO/entity data, **AssertJ** `assertThat`, **Awaitility** for async. Pin time with `TestClockProvider` / `ClockProvider`.
- **Test-data isolation:** any new IT fixture id range must be registered in `docs/testing/test-data-isolation.md` (free blocks: `9653`–`9690`, `9710`–`9790`, `9840`–`9890`, `9910`–`9990`).
- CI Spring-context ceiling is **37** — do not add a `@MockitoSpyBean` / `@MockitoBean` combination that forks a new context (relevant to AC4).

### Files this story touches (read before editing)

| File | AC | Current state to preserve |
|---|---|---|
| `platform/payment/service/SessionPackPaymentService.java` | 1 | `chargeAndCapture` → `createPurchase` → `toResponse` flow; the deliberate compensating-refund pattern (external HTTP call, not `@Transactional`) — widen the catch, don't wrap the method |
| `infrastructure/threadpool/ExecutorShutdown.java` + async `@Configuration`s | 2 | `gracefulFixedPool`'s existing `shutdownNow()` escalation for `storageUploadExecutor` — extend the pattern, keep queued-task semantics per pool as documented in the javadoc |
| `platform/security/config/MvcConfig.java` | 3 | `setFallbackToSystemLocale(false)` is deliberate (AC12.4 of deferred-92) — add a default-message path, don't re-enable system-locale fallback |
| `platform/security/api/CoachRegistrationResourceIT.java`, `PlayerRegistrationResourceIT.java` | 4 | existing `skillars-deferred-89 AC7` resend-otp block + fixtures — add cases, mirror `ParentRegistrationResourceIT` |
| `platform/notification/health/SmtpHealthIndicator.java` | 5 | its dedicated health group (deferred-96 AC2) must stay out of the deploy smoke-test aggregate |
| `.github/workflows/deploy.yml` | 6 | deferred-96 AC1's `result=fail` / `outcome=='skipped'` handling — extend to `outcome != 'success'`, don't regress the working paths |
| `test/.../db/MigrationLint.java` + `MigrationConventionLintTest` | 7 | `FIXTURE_SOURCES` path behaviour must stay identical; the `V128` `DEFERRED_92_BASELINE` constant |
| `docs/deployment/migration-conventions.md` | 8 | append-only; don't contradict the existing expand/contract rules or `MigrationConventionLintTest` |
| `platform/development/**` (new pruning service) + `V119` marker table | 9 | `upsertAddIdempotent` idempotency window (minutes) — a 90-day retention floor must not affect it |
| `infrastructure/persistence/PessimisticLockRetryer.java` | 10 | savepoint retry-in-place is deliberate (no dialect savepoint support) — instrument, don't restructure |
| `platform/video/service/QuotaService.java`, `BandwidthResetService.java` | 11,12 | idempotent-reset and idempotent-`release()` contracts |
| `test/.../session/api/DrillUploadServiceConcurrencyIT.java` | 13 | the other timing-based cases in the file — leave them unless trivially convertible |
| `components/session/DrillCard.vue`, `DrillDetailPanel.vue` + drill/session pages | 14 | the existing signed-URL fetch path — retry once, don't loop |
| `platform/video/service/PlaybackService.java`, `PlaybackServiceIT.java` | 15 | `authorizePlayback` behaviour unchanged; only add observation + drop the dead perf log |
| phone constraint validator + DTOs under `infrastructure/validation` | 16 | the pipe-template message pattern (deferred-18 lesson); `@Valid` cascade on nested `List`s |
| `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` | 14,16 | bundle parity (`MessageBundleParityTest` / frontend parity check) — add keys to all three |
| `_bmad-output/implementation-artifacts/deferred-work.md` | 17 | delete-outright convention; line-for-line verification before writing; keep `[DISMISSED]`/`[DECIDED]`/`[PICKED UP]` bullets |

### Verification pass results carried into scope (2026-09-07, `master@69a1ca8`)

- **Included as live:** AC1 (purchasePack — confirmed at `SessionPackPaymentService.java` ~L86–101), AC2 (`ExecutorShutdown.java` — `gracefulFixedPool` L192/L228 is the only pool that escalates to `shutdownNow()`; `configureGracefulShutdown` L251 just sets `setAwaitTerminationSeconds` for the other 6 `ThreadPoolTaskExecutor` pools — its own javadoc says "seven pools", matching this story's "1 of 7"), AC3 (`MvcConfig.java` ~L52), AC5 (`SmtpHealthIndicator.java` — serial 10 s/provider, no TTL), AC9 (`V119`), AC10 (`PessimisticLockRetryer.java`), AC11 (`QuotaService.java:45` — three-term `long` sum, no guard).
- **Included, re-verify exact lines during dev:** AC4 (resend-otp ITs — endpoint cases exist for 400 + DB-level rejection; **confirmed no `409` / `security.otpResendInProgress` endpoint assertion** in Coach/Player ITs), AC12/AC13 (video service method names/lines — `BandwidthResetService.resetMonthlyBandwidth` at L52; period-start assignment may live in `BandwidthResetChunkProcessor`), AC14 (`DrillCard`/`DrillDetailPanel` refetch path).
- **Verified DONE / near-done at HEAD (narrowed the AC):** AC6 — `deferred-96`'s `542eab1` (in `master`, ancestor of this branch) already shipped the outcome-guard, `result=error` fallback, `timeout-minutes` and ssh `ConnectTimeout` the `deferred-94` bullet asked for; AC6 is now verify + one narrow residual + ledger close. AC7 word-boundary half — `MigrationLint.wordBoundary()` (`\b`-anchored) **already landed**; AC7 is now the caching half + a regression test.
- **Excluded as STALE (feeds AC17 delete list):** the `1-7` (2026-09-02) section — rint-TTL docs fixed, Prettier passes at HEAD, 1-7b `done` + tracked, "5 min exact" defused.
- **Decisions taken (project owner, 2026-09-07):** phone validation → configurable per-market (AC16); video load-fail UX → toast + one auto-retry (AC14); `authorizePlayback` perf signal → include (AC15); scope → genuine bugs + DB/Perf + Video.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — every AC cites its `## Deferred from:` heading above.
- [Source: `_bmad-output/project-context.md`] — critical implementation rules (DDD boundaries, migration safety, testing).
- [Source: `docs/deployment/migration-conventions.md`] — expand/contract migration rules (AC8, AC9).
- [Source: `docs/testing/test-data-isolation.md`] — IT fixture id registry (AC4, AC9, AC12).
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-98-*.md`] — the SLU trend signal precedent AC15 mirrors (read-time derivation, no new storage).
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-96-*.md`] — `SmtpHealthIndicator` origin + the health-group governance constraint (AC5, AC6).

### Project Structure Notes

- New backend code stays in `platform.{module}.{layer}` / `infrastructure.*` per the DDD boundary rules; the SLU marker-pruning scheduler (AC9) belongs in `platform.development.service`, not `infrastructure`.
- New Flyway migrations (AC1 optional, AC9 likely) take the next free `V###` (currently `V130+`) and must be additive-only per `migration-conventions.md`.
- No new module needed; all ACs extend existing modules (`payment`, `notification`, `development`, `video`, `security`) or infrastructure (`threadpool`, `persistence`, `validation`).

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (bmad-dev-story workflow)

### Debug Log References

- Local sanity bar per story: `mvn -o test-compile -DskipFrontend` (clean) + `mvn -o test -Dtest=<all touched unit tests>` → **119/119 green**. Frontend: `npx prettier --check 'src/**/*.{js,vue,scss,json}'` clean, `npx eslint src/` clean, `npx quasar build` succeeded.
- **Integration tests were NOT run locally** (Testcontainers; CI is the sole full-verification gate per project rule): `SluSnapshotAppliedRetentionServiceIT`, `BandwidthResetChunkingIT` (AC12 case), `DrillUploadServiceConcurrencyIT` (AC13 rewrite), `PlaybackServiceIT` (AC15 case), and the `resendOtp_concurrentActiveOtpInsert_returns409_otpResendInProgress` cases in `Coach`/`PlayerRegistrationResourceIT` (AC4) — all compile; the AC4 + AC13 cases use a `pg_stat_activity` / latch handshake to stay deterministic, watch CI for lock-wait timing on the first run.
- AC6's forced-error deploy dry run on a throwaway branch is a manual pre-merge step.

### Completion Notes List

**T1 — AC1 `purchasePack` compensating-refund safety**
- Widened the inner refund `catch (PaymentGatewayException)` to `catch (Exception)` in `SessionPackPaymentService.purchasePack`; excludes `Error` only. The outer `throw new PaymentGatewayException("payment.lifecycleFailure")` runs on every path.
- Idempotent refund: `StripeClient.createRefund` overloaded with an idempotency-key arg; `StripePaymentGateway.refund` now passes `"refund-" + paymentIntentId` so a re-attempt replays the original refund at Stripe instead of double-crediting.
- Durable reconciliation: new additive migration `V130__stripe_refund_failures.sql` (bare `CREATE TABLE`, inline `UNIQUE (payment_intent_id)`, no index/FK/lock-taking DDL — passes MigrationConventionLintTest), entity `StripeRefundFailure`, repo `StripeRefundFailureRepository`, and `RefundReconciliationService.recordRefundFailure` (`@Transactional(REQUIRES_NEW)` — its own boundary because `purchasePack` is not `@Transactional`). A re-attempt for the same PaymentIntent bumps `attempts` rather than inserting a duplicate.
- If the reconciliation write itself throws, it is caught, logged at ERROR ("MANUAL RECOVERY REQUIRED" with intentId/amount/parentId/packTierId), and a clean `payment.lifecycleFailure` is still thrown.
- Tests: 3 new cases in `SessionPackPaymentServiceTest` (refund throws unchecked → still wraps + records; refund throws `PaymentGatewayException` → still wraps + records; refund succeeds → no record). 13/13 green.

**T2 — AC2 forced-termination escalation on the 6 `ThreadPoolTaskExecutor` pools**
- New `GracefulShutdownTaskExecutor extends ThreadPoolTaskExecutor` in `infrastructure.threadpool`: `shutdown()` calls `super.shutdown()` (Spring's `awaitTerminationSeconds` wait, which otherwise only logs) then, if the live `ThreadPoolExecutor` is not terminated, escalates to `shutdownNow()` + a `FORCED_TERMINATION_SECONDS` (=1s) bounded wait — the same end state `ExecutorShutdown.gracefulFixedPool` already gives the raw `storageUploadExecutor`.
- All 6 pool `@Bean` sites switched `new ThreadPoolTaskExecutor()` → `new GracefulShutdownTaskExecutor()`: `outboxDrainPool`, `sluRetryExecutor`, `reportExecutor`, `moderationTaskExecutor`, `sendMailPool`, `taskExecutor`. `configureGracefulShutdown(...)` unchanged (still sets the await fields).
- Budget: 6 pools × ≤1s escalation = ≤6s, inside the ~7s `stop_grace_period` headroom (55s − ~48s existing await budget). Javadoc on `ExecutorShutdown.FORCED_TERMINATION_SECONDS` / `configureGracefulShutdown` updated.
- Tests: `ExecutorShutdownConfigurationTest` gains `everyPoolIsAGracefulShutdownTaskExecutor` (a plain `ThreadPoolTaskExecutor` pool fails the build) and `gracefulShutdownTaskExecutor_forcesTerminationAfterItsBudget` (direct, fast: a 30s sleeper is interrupted after a 1s budget). 8/8 green.

**T3 — AC3 `NoSuchMessageException` 500 class**
- `MvcConfig.messageSource` now builds a `WarnOnMissingMessageSource` (new, `infrastructure.i18n`) with `setUseCodeAsDefaultMessage(true)`. A code missing from every bundle now returns the code + one bounded WARN (LRU, `MAX_WARNED_CODES = 1000`) instead of a request-time 500. `setFallbackToSystemLocale(false)` kept.
- Build-time gate `MessageCodeBaseBundleCompletenessTest`: scans `src/main` for literal `.getMessage("x.y")` and `message = "{x.y}"`, plus every `EmailTemplate.*.subjectKey()`, and asserts each is in base `messages.properties`. Documents that dynamically-built codes are out of a literal scan's reach (that's what the runtime net is for). Currently 39 codes checked, all green.
- Tests: `WarnOnMissingMessageSourceTest` (missing code → returns code with `useCodeAsDefault`; still throws without it; bounded tracker survives 5000 distinct misses). 10/10 green with `MessageBundleParityTest`.

**T4 — AC4 OTP-resend 409 through the endpoint**
- `resendOtp_concurrentActiveOtpInsert_returns409_otpResendInProgress` added to `CoachRegistrationResourceIT` and `PlayerRegistrationResourceIT`. Deterministic + spy-free: a holder transaction inserts a conflicting `used=false` row and stays open; the `/resend-otp` request's `saveAndFlush` INSERT blocks on `uq_pot_one_active_per_user`; Awaitility polls `pg_stat_activity` for the lock-wait, then the holder commits → request INSERT fails `23505` → `ApiAdvice` → 409 `security.otpResendInProgress`.
- Not run locally (Testcontainers IT — CI gate). Compiles clean.

**T5 — AC5 `SmtpHealthIndicator` bounded / cached / implicit-TLS**
- Rewrote to probe providers in parallel on a bounded daemon pool under one overall deadline (`overallTimeoutMillis()` = `max(connect+read)+1s` unless overridden), and to serve the aggregate `Health` from an `AtomicReference<Cached>` for `ttl` (default 60s).
- `probeImplicitTlsConnection` (new package-private seam): `SSLSocket` + `startHandshake()` with the JVM default trust store, used for `port == 465` or `ProviderConfig.implicitTls == true`. Production trusts via the default store (bad chain ⇒ DOWN); tests override the seam rather than trust-all.
- New `SmtpHealthProperties` (`app.notification.smtp-health.*`: `ttl`, `overall-timeout`, `probe-pool-size`), registered in `ComponentConfig`; `ProviderConfig.implicitTls` added; `application.yaml` documents the block.
- Existing 15 hermetic tests preserved (seam + detail strings unchanged); 4 new AC5 tests (parallel timing, TTL dedupe, 465 UP, 465 DOWN). 19/19 green.

**T7 — AC7 MigrationLint tree-walk caching** — `SOURCE_CORPUS_CACHE` memoises `readSourceCorpus(roots)` (path → body); `referencesIn` resolves against it instead of its own `Files.walk` + `readString` per dropped identifier. Word-boundary matching confirmed already at HEAD. `referenceScan_underscoreAdjacentName_isNotAMatch` regression test (13/13 green).

**T8 — AC8 migration conventions doc** — added a compressed "Go-forward checklist" + a "Pre-production migration debt" pointer near the top of `docs/deployment/migration-conventions.md`. The V60/V94/V117 redo trigger + per-migration table were already present (deferred-91 D7). Doc-only.

**T9 — AC9 SLU marker retention** — V119 already carries `applied_at TIMESTAMPTZ NOT NULL DEFAULT now()`, so no migration / NULL policy needed. `SluSnapshotAppliedRetentionService` (`@Scheduled` cron `0 30 3 * * *` + `@SchedulerLock`, `platform.development.service`); `PlayerSluWeeklySnapshotAppliedRepository.pruneAppliedBefore` (ctid-batch native delete); `app.slu.snapshot-applied.{retention-days:90,prune-cron}`; try/catch + `slu.snapshot_applied.prune.{deleted,failures}` counters. `SluSnapshotAppliedRetentionServiceIT` (Testcontainers — CI gate).

**T10 — AC10 PessimisticLockRetryer instrumentation** — budget/backoff already `app.locking.retry.*`. Added `MeterRegistry` (constructor); `persistence.lock_retry` `Timer` tagged `outcome` (`success`/`exhausted`/`error`) + `persistence.lock_retry.{retries,exhausted}` counters. Class javadoc gained a "Cost model — a documented, sized tradeoff" section; `architecture.md` gained a one-liner. 2 new unit tests (11/11 green).

**T11 — AC11 quota overflow** — `QuotaService.exceedsQuota(used, reserved, requested, limit)` uses `Math.addExact`; an `ArithmeticException` is logged at ERROR ("quota-tracking or config bug") and returns "over quota" rather than wrapping negative and passing. Applied to `check` + `reserve`. Bandwidth counter is DB-side and gates nothing → not touched. Near-`MAX_VALUE` unit test (4/4 green).

**T12 — AC12 bandwidth period anchor** — `BandwidthResetChunkProcessor.resetChunk()` computes `periodStart = YearMonth.from(Instant.now(ClockProvider.getClock()).atZone(UTC)).atDay(1).atStartOfDay(UTC)` and passes both it (SET) and the captured `now` (predicate `CAST(? AS timestamptz)`) as bound params — deterministic under a pinned clock. New `BandwidthResetChunkingIT` case: pinned to the 4th → all rows stamped the 1st; re-run no-op. Other timing tests in that file left as-is.

**T13 — AC13 de-flake concurrency IT** — `deleteVideo_videoRowHeldByAnotherTransaction_...` rewritten: holder holds the `SELECT … FOR UPDATE` until a `releaseLock` latch (not `sleep(1200)`); `assertThatThrownBy(() -> contender.get(750ms)).isInstanceOf(TimeoutException.class)` proves it's still blocked, then release, then `contender.get(20s)` completes. Ordering assertion, zero wall-clock tolerance. Postgres-`FOR UPDATE` note added. Other timing tests untouched.

**T14 — AC14 video load-failure UX** — new `useDrillVideoPlayback` composable: first `@error` → silent one-shot retry (emit `video-error` → parent refetches → `:key="drill.videoUrl"` reloads); second `@error` → `videoFailed` + one dismissible `$q.notify` (module-level `Set<drillId>` de-dup) + `emit('video-load-failed')`; per-drill-id `watch` reset; `onBeforeUnmount` `active` flag so a late retry can't toast a page the user left. Wired into `DrillCard.vue` + `DrillDetailPanel.vue` (inline "unavailable" tile); `DrillSuggestionPanel.vue` passthrough; the 3 pages' `handleVideoError` reduced to the silent refetch. `session.drillLibrary.videoUnavailable` in en-US/de-DE/fr-FR. `eslint` + `prettier --check` + `quasar build` all green.

**T15 — AC15 authorizePlayback latency signal** — production `@Observed(name="video.playback.authorize")` + `VideoMetrics.recordPlaybackAuthorizeLatency` already on the 4-arg `authorizePlayback` (the `VideoPlayResource` call path; the 2-/3-arg convenience overloads self-call and bypass the proxy). Removed the dead p50/p95/p99 `log.info` loop + `nearestRankIndex` from `PlaybackServiceIT`; added `authorizePlayback_recordsNonGatingLatencyObservation` (calls the 4-arg overload, asserts the timer registers + records).

**T16 — AC16 configurable per-market phone validation** — `PhoneValidationProperties` (`app.validation.phone`: `default-pattern` `^\+?\d{7,15}$`, `default-hint-key`, `markets` map) + `ValidationConfig`; `MarketResolver` (explicit signal → [profile: inert] → locale region subtag → default; bare-language locale falls through); `PhoneRuleRegistry` (compiles the resolved pattern, static publish for the `ConstraintValidator`, bad-pattern → permissive fallback). `CamPhoneValidator` rewritten config-driven, `@CamPhone` name kept (noted misnomer), pipe-template message preserved. `PhoneNumberUtil.fromString` made best-effort — a non-CM number no longer throws (stored with `provider=null`). Backend `validation.phone.{digitCount,firstDigit,operator}` neutralised + `hintDefault` added across all 4 bundles (parity green); frontend `auth.phoneHintFormat` + `auth.nationalId` reworded locale-neutral (en/de/fr). `PhoneValidationConfigTest` (8 tests). **Residual:** operator-level `Provider` identification stays CM-only (needs a provider abstraction); `CamMobileValidator.validate`'s throwing contract unchanged for direct callers.

**T17 — AC17 ledger hygiene** — deleted the verified-stale `## Deferred from: code review of 1-7-session-refresh-mechanism-fix (2026-09-02)` section (6 bullets; breadcrumb comment left); `[NOTE …]` on the 1-7b `sessionManager.js` bullet; `[CLOSED by skillars-deferred-99 ACn]` / `[PICKED UP …]` annotations on the AC1–AC16 target bullets. `## Deferred from:` header count 110 → 109.

**T6 — AC6 deploy smoke-step error visibility**
- Confirmed `542eab1` (deferred-96, in `master`) already shipped the `outcome`-guards, `result=error` fallback, `timeout-minutes: 15`, and ssh `-o ConnectTimeout=10 -o BatchMode=yes` the `deferred-94` review asked for.
- Residual closed in `.github/workflows/deploy.yml`: the smoke step now `exit 1`s (with `::error::`) if it still cannot persist `result` — so a *passing* poll with an unwritable `$GITHUB_OUTPUT` becomes `outcome=failure` and engages the revert/notify/marker steps instead of a silent green run. `Fail workflow` marker also fires on `outcome==success && result!=pass`.
- Forced-error dry run on a throwaway branch is a manual pre-merge step (noted in AC6). YAML validated.

### File List

- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (M — AC1)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java` (M — AC1)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` (M — AC1)
- `src/main/java/com/softropic/skillars/platform/payment/service/RefundReconciliationService.java` (A — AC1)
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeRefundFailure.java` (A — AC1)
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeRefundFailureRepository.java` (A — AC1)
- `src/main/resources/db/migration/V130__stripe_refund_failures.sql` (A — AC1)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java` (M — AC1)
- `src/main/java/com/softropic/skillars/infrastructure/threadpool/GracefulShutdownTaskExecutor.java` (A — AC2)
- `src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java` (M — AC2 javadoc)
- `src/main/java/com/softropic/skillars/platform/outbox/config/OutboxConfig.java` (M — AC2)
- `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java` (M — AC2)
- `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java` (M — AC2)
- `src/main/java/com/softropic/skillars/infrastructure/config/AsyncConfig.java` (M — AC2)
- `src/test/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdownConfigurationTest.java` (M — AC2)
- `src/main/java/com/softropic/skillars/infrastructure/i18n/WarnOnMissingMessageSource.java` (A — AC3)
- `src/main/java/com/softropic/skillars/platform/security/config/MvcConfig.java` (M — AC3)
- `src/test/java/com/softropic/skillars/i18n/MessageCodeBaseBundleCompletenessTest.java` (A — AC3)
- `src/test/java/com/softropic/skillars/infrastructure/i18n/WarnOnMissingMessageSourceTest.java` (A — AC3)
- `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java` (M — AC4)
- `src/test/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResourceIT.java` (M — AC4)
- `src/main/java/com/softropic/skillars/platform/notification/health/SmtpHealthIndicator.java` (M — AC5)
- `src/main/java/com/softropic/skillars/platform/notification/contract/SmtpHealthProperties.java` (A — AC5)
- `src/main/java/com/softropic/skillars/platform/notification/contract/ProviderConfig.java` (M — AC5)
- `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java` (M — AC5)
- `src/main/resources/application.yaml` (M — AC5)
- `src/test/java/com/softropic/skillars/platform/notification/health/SmtpHealthIndicatorTest.java` (M — AC5)
- `.github/workflows/deploy.yml` (M — AC6)
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (M — AC7)
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` (M — AC7)
- `docs/deployment/migration-conventions.md` (M — AC8)
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSluWeeklySnapshotAppliedRepository.java` (M — AC9)
- `src/main/java/com/softropic/skillars/platform/development/service/SluSnapshotAppliedRetentionService.java` (A — AC9)
- `src/test/java/com/softropic/skillars/platform/development/service/SluSnapshotAppliedRetentionServiceIT.java` (A — AC9)
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java` (M — AC10)
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerTest.java` (M — AC10)
- `_bmad-output/planning-artifacts/architecture.md` (M — AC10)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` (M — AC11)
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaServiceTest.java` (M — AC11)
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetChunkProcessor.java` (M — AC12)
- `src/test/java/com/softropic/skillars/platform/video/service/BandwidthResetChunkingIT.java` (M — AC12)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java` (M — AC13)
- `src/frontend/src/composables/useDrillVideoPlayback.js` (A — AC14)
- `src/frontend/src/components/session/DrillCard.vue` (M — AC14)
- `src/frontend/src/components/session/DrillDetailPanel.vue` (M — AC14)
- `src/frontend/src/components/session/DrillSuggestionPanel.vue` (M — AC14)
- `src/frontend/src/pages/coach/DrillLibraryPage.vue` (M — AC14)
- `src/frontend/src/pages/coach/SessionBuilderPage.vue` (M — AC14)
- `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue` (M — AC14)
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (M — AC14)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (unchanged — `@Observed` already present; AC15)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` (M — AC15)
- `src/main/java/com/softropic/skillars/infrastructure/validation/PhoneValidationProperties.java` (A — AC16)
- `src/main/java/com/softropic/skillars/infrastructure/validation/MarketResolver.java` (A — AC16)
- `src/main/java/com/softropic/skillars/infrastructure/validation/PhoneRuleRegistry.java` (A — AC16)
- `src/main/java/com/softropic/skillars/infrastructure/validation/ValidationConfig.java` (A — AC16)
- `src/main/java/com/softropic/skillars/infrastructure/validation/CamPhoneValidator.java` (M — AC16)
- `src/main/java/com/softropic/skillars/infrastructure/validation/CamPhone.java` (M — AC16 javadoc)
- `src/main/java/com/softropic/skillars/infrastructure/util/PhoneNumberUtil.java` (M — AC16)
- `src/main/resources/i18n/{messages,messages_en,messages_de,messages_fr}.properties` (M — AC16, + AC3-adjacent nothing)
- `src/test/java/com/softropic/skillars/infrastructure/validation/PhoneValidationConfigTest.java` (A — AC16)
- `_bmad-output/implementation-artifacts/deferred-work.md` (M — AC17)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (M — status)

### Code Review Findings

**Code review completed (2026-09-07, backend chunk).** Parallel adversarial, edge-case, and acceptance audits identified 36 findings across three review layers. After deduplication and triage:

**Decision-Needed (must resolve before merge):**
- [ ] [Review][Decision] **AC11 quota overflow: three-term sum order-dependent** — `Math.addExact` guard effectiveness depends on evaluation order of `used + reserved + requested`. Need user decision: (1) reorder as three separate `addExact` calls with intermediate bounds checks, (2) use a pre-check `requested > limit - used - reserved` to avoid overflow entirely, or (3) accept current implementation with explicit documentation of the assumption. Evidence: Blind Hunter finding.

**Patches (fixable without user input):**
- [ ] [Review][Patch] **AC1 refund idempotency key implementation unverified** — `StripePaymentGateway.refund()` must pass the idempotency key through to Stripe; verify `StripeClient.createRefund()` includes the key parameter. [StripePaymentGateway.java:1380]
- [ ] [Review][Patch] **AC1 reconciliation-write failure masking** — when `RefundReconciliationService.recordRefundFailure()` throws, the exception context is lost. Throw a distinct exception or use an outbox pattern to make the failure distinguishable for retry logic. [SessionPackPaymentService.java:1655–1658]
- [ ] [Review][Patch] **AC5 SmtpHealthIndicator cache uses nanoTime() (NTP regression risk)** — replace `System.nanoTime()` with monotonic time source (e.g., `System.currentTimeMillis()` or `Instant.now()`) to handle NTP clock adjustments safely. [SmtpHealthIndicator.java:430–435]
- [ ] [Review][Patch] **AC5 SMTP health cache concurrent update race** — synchronize the `AtomicReference.set()` or use a `ConcurrentHashMap` entry to prevent two threads from overwriting each other's cache compute during TTL boundary. [SmtpHealthIndicator.java:96]
- [ ] [Review][Patch] **AC2 executor forced-termination timing: 1s per pool may be insufficient** — current `FORCED_TERMINATION_SECONDS = 1` may interrupt long-running final cleanup. Add configurable per-pool timeout or increase the baseline. [ExecutorShutdown.java:461–468]
- [ ] [Review][Patch] **AC3 message source bounded-warn cache (1000 entries) untuned** — make `MAX_WARNED_CODES` configurable via `app.i18n.warn-cache-size` to avoid silent degradation if deployment uses >1000 distinct dynamic keys. [WarnOnMissingMessageSource.java:lineN/A]
- [ ] [Review][Patch] **AC5 SMTP probe pool size default not visible** — verify `SmtpHealthProperties.probePoolSize()` has a non-zero default; if default is 0, "parallel" probes will serialize. [SmtpHealthIndicator.java:78]
- [ ] [Review][Patch] **AC9 SLU marker batch delete loop: mid-batch failure handling** — add explicit error handling in `pruneAppliedBefore()` — if deletion fails mid-batch, log the failure and either skip to the next batch or fail the job cleanly. [SluSnapshotAppliedRetentionService.java:lineN/A]
- [ ] [Review][Patch] **AC16 phone pattern compilation failure (PatternSyntaxException) has no fallback** — wrap `PhoneRuleRegistry.ruleFor()` regex compilation in try-catch; on invalid regex, log ERROR and return the permissive default pattern, not an uncaught exception. [CamPhoneValidator.java:170–177]
- [ ] [Review][Patch] **AC6 deploy workflow YAML condition depends on undefined behavior** — clarify the logic: `(steps.smoke.outcome == 'success' && steps.smoke.outputs.result != 'pass')` assumes `outputs.result` is never `'pass'` if `outcome != 'success'`. Document the invariant or simplify. [.github/workflows/deploy.yml:28]
- [ ] [Review][Patch] **AC12 bandwidth period month-boundary clock drift** — ensure `BandwidthResetChunkProcessor` captures `Instant.now()` once at the start and uses it consistently throughout the batch. If clock advances mid-batch, period boundaries drift. [BandwidthResetChunkProcessor.java:lineN/A]

**Deferred (pre-existing, not in scope):**
- [x] [Review][Defer] **MigrationLint source corpus cache never invalidates (test isolation)** — deferred, pre-existing. The cache persists across test runs in the same JVM; consider clearing it at test class end or using a ClassRule.
- [x] [Review][Defer] **PessimisticLockRetryer dead code / double stop guard fragile** — deferred, pre-existing. A future refactor could remove the guard; add a regression test or a clearer comment to protect it.

**Findings Summary:**
- Decision-needed: 1
- Patches: 11
- Deferred: 2
- Dismissed: 6 (PhoneRuleRegistry silent fallback, AC3 dynamic-key limitation by design, AC6 condition subtlety acceptable, SMTP daemon graceful shutdown acceptable, video toast per-drill de-dup known limit, GitHub Actions outcome enum future-proofing)

---

## Change Log

- **2026-09-07**: Story created from `deferred-work.md` (re-verified against `master@69a1ca8`). Scope: genuine one-off bugs + DB/Performance + Video buckets. 17 ACs. Project-owner decisions recorded for AC14 (video UX), AC15 (playback signal), AC16 (phone validation), and overall scope.
- **2026-09-07 (self-audit pass, `story-review.md`)**: Re-verified every AC against HEAD source.
  - **AC6 narrowed** — `deferred-96`'s `542eab1` already shipped the smoke-step `outcome`-guard, `result=error` fallback, `timeout-minutes` and ssh `ConnectTimeout`; AC6 is now verification + the single residual (passing smoke with an unwritable `$GITHUB_OUTPUT` no-ops silently) + ledger close. Coordination note added re `deferred-97` AC3/AC4.
  - **AC7 narrowed** — `MigrationLint.wordBoundary()` (`\b`-anchored) confirmed already landed; AC7 is now the tree-walk caching + a regression test.
  - **AC1/AC3/AC5/AC9/AC11/AC13/AC14/AC16 hardened** with defensive detail surfaced by the audit: AC1 refund idempotency + reconciliation-write-failure handling; AC3 dynamic-key limitation + bounded WARN cache; AC5 concrete timeout formula + TLS trust policy; AC9 explicit NULL-row policy + job-failure metric; AC11 pre-check preferred + ERROR-log on overflow + noted the three-term sum; AC13 bounded `await`s; AC14 unmount/navigation-away guard + per-video toast de-dupe; **AC16 market-resolution precedence made explicit** (the one substantive gap — request signal → profile → locale region → default; never guess country from a bare language).
  - **Review false positives rejected** (recorded, no story change): AC2 "budget contradiction" (the "~7 s" is the *added* forced-termination headroom = 55 − ~48, not the per-pool budget) and AC2 "grep missed `storageUploadExecutor`" (it is the already-covered 7th pool, stated as such); AC6 "`neutral` outcome uncaught" (GitHub Actions has no `neutral` step outcome); AC7 "`\bplayer_id\b` matches inside `player_id_new`" (`_` is a `\w` char — it does not).
- **2026-09-07 (implementation, bmad-dev-story)**: All 17 ACs implemented. 26 new files, 43 modified (see File List). 119/119 touched unit tests green; frontend `eslint`/`prettier`/`quasar build` green; `mvn -o test-compile` clean. ITs deferred to CI (see Debug Log). Verified-during-dev discoveries folded in: AC6 & AC7 confirmed substantially landed at HEAD (narrowed to residual + verification); AC9's V119 already has `applied_at NOT NULL` (no migration); AC10's retry budget already externalised (instrumentation only); AC15's `@Observed` already on the production path (dead IT log removed + assertion added). AC16 shipped with a recorded residual (operator-level `Provider` detection stays CM-only). Story → **review**.
