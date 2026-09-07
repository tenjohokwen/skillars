# skillars-deferred-99: Genuine One-Off Bugs — Reliability, i18n, Migration Safety & Video Hardening

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
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
- **Files:** `SessionPackPaymentService.java`; possibly a new migration + repo/entity; `SessionPackPaymentServiceTest` / an IT.
- **Test:** unit — `refund()` throws an unchecked exception → outer `PaymentGatewayException("payment.lifecycleFailure")` still thrown **and** a reconciliation record persisted; `refund()` throws `PaymentGatewayException` → same; `refund()` succeeds → no reconciliation record; happy path unchanged.
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
  1. Add a build-time test that scans `src/main` for message-code string literals passed to `messageSource.getMessage(...)` / `@*Message` / validation `{...}` templates and asserts every one exists in base `messages.properties`. Scope pragmatically to the resolution helpers actually used (grep first). Fail the build on a gap.
  2. As defence-in-depth, set `setUseCodeAsDefaultMessage(true)` **and** add a `MessageSource` wrapper (or `AbstractMessageSource` subclass) that logs `WARN` once per missing code, so a gap degrades to "code shown to user + WARN" instead of a 500. Keep de/fr/en fail-fast semantics via the parity test.
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
- **Fix approach:** submit per-provider probes to a small bounded executor (or `CompletableFuture.allOf` with `orTimeout`), overall budget ≈ `max(perProviderTimeout) + slack`; cache the resulting `Health` for `app.notification.smtp-health.ttl` (default 60 s) behind an `AtomicReference` with a timestamp; for `port == 465` (or a `ssl`/`implicitTls` provider flag) open an `SSLSocket` and treat a completed handshake as UP. Keep this in its own health group so it stays out of the deploy smoke-test aggregate (as `deferred-96` AC2 established).
- **Files:** `SmtpHealthIndicator.java`, `application.yaml` (ttl property + group already present), a probe-executor bean, `SmtpHealthIndicatorTest` / IT.
- **Test:** N configured providers complete within ≈ one timeout, not N×; two calls inside the TTL issue only one round of socket probes; a 465 provider reports UP against a TLS-capable stub and DOWN when the handshake fails.
- **Ledger:** `## Deferred from: code review of skillars-deferred-96 (2026-09-07)` — both `SmtpHealthIndicator` bullets (serial/no-TTL; port-465 always DOWN).

### AC6: Deploy smoke-step *error* must notify and auto-revert

- **Task:** In `.github/workflows/deploy.yml`, make the auto-revert / fail-marker / failure-notification steps fire when the `Smoke test` step **errors** (ssh/sleep failure, runner kill, `$GITHUB_OUTPUT` write failure) — not only when it completes with `result=fail`.
- **Verified at HEAD:** `deferred-96` AC1 hardened the `result=fail` and `outcome == 'skipped'` paths but the re-review (`deferred-94` code review, 2026-09-07) found the `outcome == 'failure'` path still uncovered: `steps.smoke.outputs.result` is never written, the pass/fail/revert/marker branches all skip, and the pre-smoke notifications gate on `'skipped'` not `'failure'`.
- **Fix approach:** guard the downstream steps on `steps.smoke.outcome != 'success'` (covers `failure`, `cancelled`, `skipped`) with explicit `failure()`/`always()` status functions, per `deferred-96` AC1's Option-A pattern; ensure the smoke command is wrapped `set +e; …; echo "result=…" >> "$GITHUB_OUTPUT"` so `result` is always written even on a non-zero exit; add `timeout-minutes` to the smoke step and `ConnectTimeout=10 -o BatchMode=yes` to its ssh calls.
- **Files:** `.github/workflows/deploy.yml`.
- **Test:** workflow, not app code — verify by code inspection (every revert/notify/marker conditional carries an explicit status function and falls back to `outcome`) and a forced-error dry run (`kill $$` / `|| exit 1` mid-smoke) on a throwaway branch.
- **Ledger:** `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "Smoke step erroring (not `result=fail`) produces a red deploy run with no notification and no auto-revert".

### AC7: `MigrationLint` reference scan — cache the tree walk, use word boundaries

- **Task:** `MigrationLint`'s dropped-identifier reference scan must walk `src/main` **once** (build an index), not once per dropped identifier; and `MigrationConventionLintTest.realMigrations_aboveBaseline_areClean` must match identifiers on **word boundaries** so an unrelated class containing a dropped name as a substring cannot break the build on an untouched migration.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/db/MigrationLint.java` (~L508) — re-walk-per-identifier confirmed by the chunk-2 review; the class javadoc claims "fails in milliseconds". The word-boundary half was "largely subsumed by the word-boundary fix filed as a patch in the same chunk" — verify whether that patch landed; if it did and the real-tree fragility is gone, this AC reduces to the caching half + a regression test.
- **Fix approach:** read each `.java/.sql/.yaml/.yml/.xml` under `src/main` once into an in-memory map (path → tokenised content or a `Set<String>` of word tokens); resolve every dropped identifier against that map; use `\b`-anchored matching. Keep the fixture-corpus path (`FIXTURE_SOURCES`) behaviour identical.
- **Files:** `MigrationLint.java`, `MigrationConventionLintTest`.
- **Test:** a new case adding a throwaway class whose name contains a real dropped identifier as a substring → lint stays green; a timing/allocation sanity assertion is optional (don't make it flaky).
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
- **Fix approach:** a `@Scheduled` + `@SchedulerLock` service in `platform.development.service` that deletes marker rows older than `app.slu.snapshot-applied.retention-days` (default 90) — the markers only exist to make `upsertAddIdempotent` idempotent across a retry window measured in minutes, so a 90-day floor is very safe. Batch the delete (`DELETE … WHERE ctid IN (SELECT ctid … LIMIT :batch)` loop) per `migration-conventions.md`. Add the retention column's age via `applied_at`/`created_at` — if V119 has no timestamp column, add one nullable via a new additive migration and backfill lazily (new rows stamped; NULL treated as "keep").
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
- **Verified at HEAD:** `QuotaService.check` / `QuotaService.reserve` (video module) — `deferred-work.md` `## Deferred from: code review of skillars-6-1-… (2026-06-20)` Def3: "Long arithmetic overflow in `storageUsedBytes + requestedBytes` — theoretical at practical quota sizes (max ~9.2 EB); no guard exists." Re-verify the exact method names/lines at `src/main/java/.../video/service/QuotaService.java` before editing.
- **Fix approach:** use `Math.addExact(...)` and treat `ArithmeticException` as "request exceeds quota" (return the same `QUOTA_EXCEEDED`-class rejection), or a pre-check `requestedBytes > limit - used`. Same for `BandwidthResetService` / bandwidth accounting if it shares the pattern.
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
- **Fix approach:** use a `CountDownLatch`/`Phaser` so the test thread releases the external lock only *after* observing the delete thread is blocked on it (e.g. poll `pg_stat_activity` / `pg_locks` for the waiter, or a `TransactionTemplate` + latch handshake), then assert the delete completes *after* release — an ordering assertion, not a duration one. Keep the existing file's other timing tests untouched unless trivially convertible.
- **Files:** `DrillUploadServiceConcurrencyIT.java`.
- **Test:** the rewritten case is the test — it must fail if the in-service pessimistic lock is removed and pass deterministically with it.
- **Ledger:** `## Deferred from: code review of skillars-deferred-83-… (2026-08-30)` — the wall-clock-timing bullet.

### AC14: Drill-video load-failure UX — auto-retry once, then a toast

- **Task:** When a drill video fails to load (expired signed URL, network), stop the current silent background refetch-with-no-feedback behaviour: retry **once** with a freshly-requested signed URL, and if it still fails show a dismissible error toast (i18n key) and an inline "unavailable" state on the player.
- **Verified at HEAD:** `deferred-work.md` `## Deferred from: code review of skillars-deferred-75 (2026-08-27)` — "video load fails … code silently refetches drill data in background. Video element remains broken with no user notification. [`DrillCard.vue`, `DrillDetailPanel.vue`, and page handlers]". Re-check those components + the pages that host them at HEAD; identify the existing refetch path.
- **Fix approach:** on the `<video>` / player `error` event: (1) call the drill/video API once more to get a fresh signed URL and re-set `src`; (2) if the retry's load also errors, emit a `$q.notify` error toast with a new `development.*` / `video.*` i18n key (add to `en-US`, `de-DE`, `fr-FR` — parity test will enforce), and render an inline "Video unavailable" panel instead of a broken element; (3) do not loop. Keep `getSkillTrends`-style API additions out of scope — reuse the existing video URL endpoint.
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
  - Introduce `app.validation.phone` config: `default-pattern` (permissive E.164: optional `+`, 7–15 digits), `default-hint-key`, and an optional `markets: { <code>: { pattern, hint-key } }` map.
  - Rewrite the phone constraint validator to resolve pattern + hint from config (fall back to the default). Keep the annotation; change its backing logic. Preserve the existing `CamPhoneValidator` pipe-template message pattern (per `deferred-18`'s recorded lesson about `LangIso2`-style bare templates resolving to nothing).
  - Replace the Cameroon-specific text in `validation.phone.*`, `auth.phoneHintFormat`, `auth.nationalId` across `en-US` / `de-DE` / `fr-FR` with locale-neutral / market-driven wording (`auth.nationalId` → a generic "ID / passport number" label unless a market config says otherwise). Parity test enforces the three bundles.
  - Rename the constraint from `@CamPhone` → `@Phone` (or keep the annotation name, note the misnomer) — pick the lower-churn option and record it.
- **Files:** the phone validator + a `@ConfigurationProperties` record under `infrastructure/validation` or `config`, `application.yaml` (defaults), the 3 i18n bundles, every DTO using the constraint (compile check), validator tests, `CoachProfileBuilderIT` / registration ITs.
- **Test:** unit — default config accepts `+15551234567` and `77012345` and rejects `abc` / a 4-digit string; a configured market pattern is enforced when the caller's market is set; `@Valid` cascade still fires (regression against `deferred-18`'s `@Valid`-cascade trap). i18n parity test green.
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

- [ ] **T1 — Payment refund safety (AC1)**
  - [ ] Widen the inner refund catch to `Exception`; keep the outer `payment.lifecycleFailure` throw on every path
  - [ ] Reuse existing failure-persistence (`deferred-91` outbox / a `payment_failures` table) or add a minimal additive `V###` + entity/repo
  - [ ] Unit tests: unchecked refund throw, checked refund throw, refund success, happy path
- [ ] **T2 — Executor forced-termination (AC2)**
  - [ ] `ThreadPoolTaskExecutor` subclass / `BeanPostProcessor` escalating to `shutdownNow()` + bounded wait
  - [ ] Apply to `outboxDrainPool`, `sendMailPool`, `sluRetryExecutor`, `moderationTaskExecutor`, `reportExecutor`, `taskExecutor`; size waits within the ~7 s headroom
  - [ ] `ExecutorShutdownConfigurationTest` per-pool escalation assertions
- [ ] **T3 — MessageSource robustness (AC3)**
  - [ ] Base-bundle completeness test (scan code for resolved codes)
  - [ ] `setUseCodeAsDefaultMessage(true)` + WARN-once decorator; keep de/fr/en fail-fast via parity test
- [ ] **T4 — OTP-resend 409 IT (AC4)**
  - [ ] `CoachRegistrationResourceIT` + `PlayerRegistrationResourceIT`: drive the `uq_pot_one_active_per_user` collision, assert 409 + `security.otpResendInProgress`
- [ ] **T5 — SmtpHealthIndicator (AC5)**
  - [ ] Parallel probes + overall deadline; short-TTL cached aggregate; TLS handshake for port 465
  - [ ] Tests: N providers ≈ 1× timeout; TTL dedupe; 465 UP/DOWN
- [ ] **T6 — Deploy smoke-error visibility (AC6)**
  - [ ] `deploy.yml`: `steps.smoke.outcome != 'success'` guards + explicit status functions; smoke command always writes `result`; `timeout-minutes` + ssh `ConnectTimeout`
  - [ ] Forced-error dry run on a throwaway branch
- [ ] **T7 — MigrationLint caching + word boundaries (AC7)**
  - [ ] Single indexed tree walk; `\b`-anchored identifier matching; regression test for substring false-positive
- [ ] **T8 — Migration conventions debt (AC8)**
  - [ ] `migration-conventions.md`: `V60`/`V94`/`V117` (+`V97`/`V98`) pre-prod exception list + expand/contract checklist + PR-checklist line
- [ ] **T9 — SLU marker retention (AC9)**
  - [ ] Timestamp column on the marker table if absent (additive `V###`); `@Scheduled` + `@SchedulerLock` batched pruning; retention-days config
  - [ ] IT: stale vs recent markers; idempotency unaffected
- [ ] **T10 — PessimisticLockRetryer (AC10)**
  - [ ] Externalise retry budget/backoff to config; Micrometer timer + counter; expand javadoc + architecture note
- [ ] **T11 — Quota overflow guard (AC11)** — `Math.addExact` / pre-check in `QuotaService`; near-`MAX_VALUE` unit test
- [ ] **T12 — Bandwidth period anchor (AC12)** — calendar first-of-month via `ClockProvider`; late-run clock test; idempotent re-run
- [ ] **T13 — De-flake concurrency IT (AC13)** — latch/lock-observation handshake replacing the wall-clock assertion
- [ ] **T14 — Video load-failure UX (AC14)** — one auto-retry with fresh URL → error toast + inline unavailable state; 3 i18n bundles; manual dev-server verification documented
- [ ] **T15 — authorizePlayback latency signal (AC15)** — `@Observed` / Micrometer timer around `authorizePlayback`; remove dead `PlaybackServiceIT` perf log; registration test
- [ ] **T16 — Configurable phone/national-id validation (AC16)**
  - [ ] `app.validation.phone` properties (default permissive E.164 + per-market map)
  - [ ] Rewrite the phone constraint to resolve pattern/hint from config; keep pipe-template message pattern
  - [ ] Locale-neutral copy in `en-US`/`de-DE`/`fr-FR` for `validation.phone.*`, `auth.phoneHintFormat`, `auth.nationalId`
  - [ ] Validator tests + `@Valid` cascade regression + i18n parity
- [ ] **T17 — Ledger hygiene (AC17)**
  - [ ] Delete the verified-stale `1-7` section (line-for-line verified); reconcile the two 1-7b `sessionManager.js` bullets against HEAD
  - [ ] `[CLOSED by skillars-deferred-99 ACn]` / `[PICKED UP …]` annotations for AC1–AC16; prune emptied sections

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

- **Included as live:** AC1 (purchasePack — confirmed at `SessionPackPaymentService.java` ~L86–101), AC2 (`ExecutorShutdown.java` L192/L228), AC3 (`MvcConfig.java` ~L52), AC5 (`SmtpHealthIndicator.java` — serial 10 s/provider, no TTL), AC9 (`V119`), AC10 (`PessimisticLockRetryer.java`).
- **Included, re-verify exact lines during dev:** AC4 (resend-otp ITs — endpoint cases exist, 409-body assertion appears absent), AC7 (`MigrationLint.java` ~L508 — confirm whether the same-chunk word-boundary patch landed), AC11/AC12/AC13 (video service method names/lines), AC14 (`DrillCard`/`DrillDetailPanel` refetch path).
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

_TBD by dev agent_

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

- **2026-09-07**: Story created from `deferred-work.md` (re-verified against `master@69a1ca8`). Scope: genuine one-off bugs + DB/Performance + Video buckets. 17 ACs. Project-owner decisions recorded for AC14 (video UX), AC15 (playback signal), AC16 (phone validation), and overall scope.
