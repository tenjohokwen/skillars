# Story: GDPR Erasure Connection-Pool & Auto-Retry Hardening, PackSessionService Lock/TOCTOU Fix & Test Hygiene

**Story Key:** `skillars-deferred-136-gdpr-datasource-retry-packsession-lock-fix`
**Epic:** Deferred Work
**Priority:** High (two GDPR-erasure production-safety gaps re-confirmed open across stories 128/129/130/135
and now explicitly greenlit rather than declined a 4th/5th time; one payment-path lock-scope + TOCTOU pair
whose sole "leave as-is" justification — byte-for-byte legacy parity — is now factually stale, the legacy
class having been deleted since; one CI test-infra residual; one mechanical test-hygiene cleanup).
**Status:** ready-for-dev
**Created:** 2026-09-25

---

## Context

Master is at `dd5ef063` (`skillars-deferred-135`, PR #230, merged — GdprErasureService alert-catch fix,
Stripe→payment reconciliation sweep, `CoachReviewRepository` NOWAIT conversion). Per this project's own
standing convention ("read same-day code-review deferrals before drawing scope"), this story was sourced
from a fresh full read of `deferred-work.md` (3936 lines) plus explicit re-verification of every candidate's
cited file:line against this `HEAD`. No 2026-09-25 ledger entries exist yet beyond `skillars-deferred-135`'s
own (fully closed by that story, not reopened here).

**Four owner decisions taken live (AskUserQuestion) during drafting:**

1. **`GdprErasureService`'s inner `REQUIRES_NEW` transaction (`deletePlayerDevelopmentData`) can wait up
   to 30s (Hikari's pool-wide `connection-timeout`) acquiring its connection, with no dedicated bound
   ("Hazard 2", `deferred-work.md:3118-3121`) — declined as a fix 3 times already (128/129/130) because
   the only real fix (a second, dedicated `HikariDataSource`) was costed as non-trivial each time.** →
   **Build the dedicated-datasource fix now.** No new production trigger has fired since the last decline,
   but the owner chose to close this proactively rather than defer a 4th time, now that this story's
   scope already includes comparable GDPR-erasure hardening work (AC2).
2. **`GdprErasureService.markFailed` (fixed today by `skillars-deferred-135` AC1) now reliably alerts on
   a FAILED erasure, but nothing auto-retries it.** → **Build a scheduled re-drive mechanism**, mirroring
   `EmailRetryScheduler`'s own established attempts/deadline/`@SchedulerLock` shape (the closest existing
   precedent in this codebase for "periodically re-attempt a failed durable operation").
3. **`PackSessionService.pausePack` holds a pessimistic lock across booking cancellations + event
   publishing, and has TOCTOU/under-confirmation gaps between reading conflicting bookings and applying
   the pause — all three (D1, D5, D8, `deferred-work.md:1191,1195,1196`) were left as-is on 2026-08-03
   solely to match a legacy `SessionPackService.pausePack()` byte-for-byte. That legacy class is
   confirmed DELETED from the codebase (Story 11.3) — the original justification no longer holds.** →
   **Fix now.** See AC3's own "found while re-verifying this AC's own premise" callout below — re-reading
   the ledger section surfaced a third, more severe item (D1) alongside the two (D5, D8) the owner was
   originally asked about; both share the identical stale-justification root cause and the same method,
   so this AC folds all three in. Flagged here explicitly since D1 was not part of the original question.
4. **`DatabaseResetTestExecutionListener`'s `ConditionTimeoutException` catch-and-proceed (test
   infrastructure only) leaves a >10s async-executor race window open.** → **Include as a filler AC** —
   but see AC4's own caveat below: that method's own Javadoc already explicitly considered and rejected
   "let it propagate / fail outright" as *"strictly worse"* (it would skip the reset transaction for the
   rest of the JVM's test run) — so the only viable lever is raising the wait bound, not failing outright
   as originally phrased to the owner.

**Plus two more ACs added without an owner decision (mechanical / no genuine tradeoff):**

5. Four test files still hand-build entities instead of using `Instancio`, against `project-context.md`'s
   own Testing Rules — flagged in `skillars-deferred-131`'s own code review, deferred there as "matches
   wider practice, not a regression." Confirmed all 4 still present and still hand-building at this `HEAD`.
6. Ledger hygiene (standard closeout for this story series) — including deleting one now-factually-wrong
   entry found during this story's own drafting (see AC6).

**Considered and explicitly excluded** (verified closed/stale/already-declined during drafting — do not
re-surface without new information): `ConfigBounds.BoundedKey`'s full call-site migration (declined a 4th
consecutive time by `skillars-deferred-135`); `ReviewFlagService.flag()` ↔ `GdprErasureService.erase()`
lock-order inversion (`[DECIDED: accepted risk]` since `skillars-deferred-132`, unreachable today by
construction); `CoachReviewRepository.findByIdForUpdate` NOWAIT conversion and the Stripe→payment
reconciliation sweep (both fully closed by `skillars-deferred-135`); `radar_composite_dlq`
post-erasure cleanup, `next_retry_at` app-clock skew, `AlertEvaluationService` scheduler-lock (all
`[DECIDED: accepted risk]`, no new trigger); `MessagingService.java:129-145`'s W1 TOCTOU (explicitly
spec-designed, not flagged for fixing by its own review).

---

## AC1: Dedicated `HikariDataSource` for `GdprErasureService`'s `REQUIRES_NEW` connection acquisitions (closes "Hazard 2")

**Files:**
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` (new bean pair)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (`initTemplates`,
  `requiresNewTemplate` field — now needs its own `PlatformTransactionManager`, not the shared one)
- `src/test/java/com/softropic/skillars/config/TestConfig.java` (test-side equivalent bean)
- New/updated `GdprErasureIT` coverage

### Current state (re-verified against `HEAD`, exact current line numbers)

- `GdprErasureService.java:122` — `private TransactionTemplate requiresNewTemplate;`, built in
  `initTemplates()` (`:158-162`) from the shared, injected `PlatformTransactionManager` (`txManager` —
  the same one every other `@Transactional` bean in this app uses), itself backed by the single shared
  `HikariDataSource` `DataSourceConfig.dataSource`/`hikariConfig` beans (`DataSourceConfig.java:24-39`).
- `deletePlayerDevelopmentData` (`:885`) calls `assertConnectionPoolNotSaturated(playerId,
  "deletePlayerDevelopmentData")` (`:889`, guard body `:733-758`) immediately before
  `requiresNewTemplate.executeWithoutResult(...)` (`:890`). `erase()` (`:186`) does the identical
  same-thread pre-check before `self.eraseTransactional(...)` for the OUTER acquisition. Both pre-checks
  are same-thread, read-only `HikariPoolMXBean` reads (`waiting > 0 || (idle <= 0 && total >= max)`) —
  fail-fast with a `PessimisticLockingFailureException` the existing call sites already catch/alert on.
- **The pre-checks do not close the gap; they only shrink its probability.** Both are explicitly
  documented (`GdprErasureService.java:727-731`, `:181-184`) as having "a genuine TOCTOU gap: the pool's
  state can change between this check and the real acquisition immediately after it" — if the pre-check
  passes and the pool then saturates before the real `requiresNewTemplate` acquisition, that acquisition
  can still block for up to Hikari's pool-wide `connection-timeout` (30s per `application.yaml`, confirmed
  unchanged). `erase()`'s own Javadoc (`:164-185`) already names the fix this AC builds as option (a),
  previously re-costed and set aside: *"take over JPA bootstrapping for the whole app, since no
  `@EnableJpaRepositories` exists anywhere in this codebase"* — re-verify that constraint still holds
  before assuming a second `EntityManagerFactory` is required (it likely is NOT for this narrower fix; see
  design below).
- `DataSourceConfig.java` (full file read): the primary `dataSource`/`hikariConfig` beans are
  `@ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)` —
  entirely skipped in the Testcontainers test path, where Boot instead auto-configures the primary
  `DataSource` from `TestConfig.java`'s own `JdbcConnectionDetails` bean (`:44-64`, reading
  `SharedContainers.postgres()`'s `getJdbcUrl()`/`getUsername()`/`getPassword()`) — deliberately NOT a
  plain `@ServiceConnection @Bean` (see that file's own comment on why: container lifecycle/context-cache
  concerns). Any second datasource this AC adds needs an equivalent, separately-conditioned test path or
  it will have no coordinates to connect to during `GdprErasureIT`.

### The fix — design requirements (read the actual code first; this is a real design task, not a template fill)

- Add a second, small, dedicated `HikariDataSource` scoped ONLY to `GdprErasureService`'s two
  `REQUIRES_NEW` acquisitions (`eraseTransactional`, `deletePlayerDevelopmentData`) — a `@Bean
  DataSource gdprErasureDataSource(...)` in `DataSourceConfig` (or a new, narrowly-scoped
  `@Configuration`), same JDBC coordinates as the primary pool (reuse `DataSourceProperties` in
  production; reuse `TestConfig`'s existing `JdbcConnectionDetails`/`SharedContainers.postgres()`
  coordinates in test — do NOT stand up a second Postgres container), but its OWN small pool (e.g.
  `maximumPoolSize` 2-3) and its OWN `connection-timeout` — sized deliberately SHORTER than the primary
  pool's 30s, so a saturated primary pool cannot make this dedicated path wait as long, and this
  dedicated pool's own exhaustion fails fast on its own terms.
- Pair it with its own `PlatformTransactionManager` (a plain `DataSourceTransactionManager` or
  `JpaTransactionManager` — confirm which is correct: `requiresNewTemplate` currently participates in the
  same JPA/Hibernate `EntityManager` machinery as every other `@Transactional` method in this class, via
  the shared `EntityManagerFactory`. Investigate whether a second `DataSource` can back a
  `TransactionTemplate` that still uses the SAME `EntityManagerFactory`/persistence unit for its JPA work
  — Spring supports multiple `DataSource`-backed `PlatformTransactionManager`s sharing one
  `EntityManagerFactory` only if the `EntityManagerFactory` itself is NOT bound to a specific
  `DataSource` connection at the JDBC level in a way that conflicts; this is the one genuinely open
  technical question in this AC and must be resolved empirically, with a note in Dev Notes on what was
  found, before assuming the design below just works).
- `initTemplates()` (`GdprErasureService.java:158-162`) rebuilds `requiresNewTemplate` from this new,
  dedicated `PlatformTransactionManager` instead of the shared `txManager`.
- Add the test-side equivalent in `TestConfig.java`: a second `DataSource`/`HikariDataSource` bean built
  from the SAME `JdbcConnectionDetails`/`SharedContainers.postgres()` coordinates already used for the
  primary test datasource, conditioned the same way (`datasource.container=true`), so `GdprErasureIT`
  actually exercises the real dedicated-pool code path, not a silently-skipped no-op.
- Do not remove `assertConnectionPoolNotSaturated`'s two existing pre-check call sites — they remain a
  cheap, complementary fail-fast layer even once the dedicated pool exists (the dedicated pool can itself
  still saturate under sustained load; the pre-check still gives an early, cheap signal before even
  attempting that dedicated pool).

### Test plan

- A new or extended `GdprErasureIT` test that proves the dedicated pool is actually wired and used — e.g.
  assert the connection URL/pool name used by `deletePlayerDevelopmentData`'s transaction differs from
  the primary pool's (via a `HikariPoolMXBean` name check or a dsproxy-style interceptor), or a saturation
  test: exhaust the DEDICATED pool specifically (not the primary) and confirm `deletePlayerDevelopmentData`
  fails fast at the dedicated pool's own (shorter) `connection-timeout`, not the primary's 30s.
- Confirm existing `GdprErasureIT`/`GdprErasureServiceConcurrencyIT` (added by `skillars-deferred-135`)
  still pass unmodified — this AC must not change `erase()`'s observable behavior on the happy path.

---

## AC2: Scheduled auto-retry for `FAILED` `GdprRequest` rows

**Files:**
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (or a new
  `GdprErasureRetryScheduler`, mirroring `StripeSubscriptionReconciliationScheduler`'s thin-wrapper split)
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequestRepository.java` (new query method)
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequest.java` (likely needs a new column
  — see design below)
- New Flyway migration if a new column is added
- New scheduler IT

### Current state (re-verified against `HEAD`)

- `GdprRequestRepository.java` (full file read): `findByStatus(String status, Pageable pageable)`
  already exists — usable as-is for finding `FAILED` rows, paginated.
- `GdprRequest.java` (`:21-46+`): fields are `id`, `userId`, `requestType`, `status` (default `"PENDING"`),
  `createdAt` (set once at construction, `@Column(updatable = false)`), `completedAt`, `downloadUrl`,
  `expiresAt`. **No `updatedAt`/`failedAt`/attempt-count column exists** — there is currently no way to
  tell how long a row has been `FAILED`, or how many times it has already been re-driven. Both are needed
  for a re-drive sweep with a grace window and a retry cap (mirroring `EmailRetryScheduler`'s own
  `attempts`/`MAX_RETRY_ATTEMPTS` shape, `EmailRetryScheduler.java:54-62`) — this AC needs a new Flyway
  migration adding, at minimum, a retry-count column (e.g. `retry_count integer not null default 0`) and
  a way to bound the grace window (either a `failed_at` timestamp column, or reuse `createdAt` if the
  scheduler's own cadence — e.g. once daily — already provides enough of a grace window without a
  per-row timestamp; decide and document which during implementation).
- `GdprRequestService.requestErasure` (`:81-97`, full method read): only blocks a NEW request when a
  PENDING/PROCESSING row already exists for that `(userId, "ERASURE")` — a `FAILED` row does NOT block a
  fresh manual resubmit, and a manual resubmit creates a BRAND NEW `GdprRequest` row/id rather than
  reusing the FAILED one. This means a user's manual resubmit and this AC's own scheduled re-drive (if it
  re-drives the SAME `FAILED` row's own `requestId`) can race concurrently for the same `userId` — not
  data-unsafe (`erase()` is independently idempotent per `deletePlayerDevelopmentData`'s own Javadoc,
  `GdprErasureService.java:792-802`: "re-driving makes genuine forward progress with no data left
  unrecoverable"), but worth a design note and a dedup guard (e.g. the scheduler's own claim query should
  exclude a `FAILED` row whose `userId` currently has ANY PENDING/PROCESSING row already, mirroring
  `requestErasure`'s own precondition).
- No `@Scheduled` method anywhere in `platform.admin.service` (or elsewhere) re-drives a `FAILED`
  `GdprRequest` today — confirmed via full-package grep.

### The fix — design requirements

- New Flyway migration: add the retry-tracking column(s) identified above to `gdpr_requests`.
- A scheduled sweep (thin `@Scheduled`/`@SchedulerLock` wrapper class, mirroring
  `StripeSubscriptionReconciliationScheduler`'s split from `SubscriptionService`, or
  `EmailRetryScheduler`'s own inline shape — pick whichever this codebase's own convention favors once
  both are compared) that: finds `FAILED` `GdprRequest` rows past the grace window and under the retry
  cap, excludes any whose `userId` currently has a PENDING/PROCESSING row (dedup guard above), increments
  the retry counter, and re-invokes the SAME erasure path `GdprEventListener.onErasureRequested` uses
  (`gdprErasureService.erase(requestId, userId)` — confirm `userId` is still resolvable from the existing
  `GdprRequest` row, it should be) for each eligible row. On exhausting the retry cap, leave the row
  `FAILED` (the existing `markFailed` alert already covers "an admin needs to look at this" — no new
  alert type needed unless retry-cap-exhaustion specifically should be distinguishable from
  first-failure in the admin queue; decide during implementation whether that distinction has value).
- Size `@SchedulerLock`/cadence from real arithmetic (mirroring every sibling scheduler's own documented
  sizing paragraph) — GDPR erasure failures are expected to be rare; a low-frequency cadence (e.g. hourly
  or daily) is almost certainly sufficient and keeps this sweep cheap. Justify the chosen cadence and
  grace window explicitly, the way `StripeSubscriptionReconciliationScheduler.java:21-36` does.

### Test plan

- New scheduler/service test(s): a `FAILED` row past its grace window and under the retry cap gets
  re-driven and, on success, transitions out of `FAILED`; a row still within its grace window is left
  alone; a row at/over the retry cap is left `FAILED` and not re-driven; a `FAILED` row whose `userId`
  has a concurrent PENDING/PROCESSING row is skipped (dedup guard).
- Confirm the re-drive path reuses `erase()`'s own existing safety mechanisms (AC1's dedicated datasource,
  the connection-pool pre-check, the per-child deadline budget) rather than bypassing them.

---

## AC3: `PackSessionService.pausePack` — lock-scope, under-confirmation, and TOCTOU fixes (D1, D5, D8)

**Files:**
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (read `cancelDueToPause`, not modified — understand its optimistic-locking behavior first)
- New `PackSessionServiceConcurrencyIT` (or extend an existing pack-session test)

### Current state (re-verified against `HEAD`, exact current line numbers)

`pausePack` (`PackSessionService.java:140-247`, single `@Transactional` method):
- `:142-144` — acquires a `PESSIMISTIC_WRITE` lock on the `session_pack_purchases` row via
  `findByIdForUpdate` + `PessimisticLockRetryer.withBoundedRetry`, held for the rest of this
  `@Transactional` method (default propagation — no `REQUIRES_NEW` boundary anywhere inside it).
- `:195-196` — reads conflicting bookings ONCE via `bookingRepository.findConflictingBookingsForPause(...)`
  into `conflicting`.
- `:198-219` — **D1 (found while re-verifying this AC — not part of the original owner question, folded
  in because it shares D5/D8's identical stale "legacy byte-for-byte" justification and the same
  method):** `validatedIds` is computed as `confirmedIds.stream().distinct().filter(conflictMap::containsKey)`
  — this FILTERS the client-supplied `confirmedCancellationIds` down to whichever ones happen to match a
  real conflict. It never checks the reverse: that EVERY id in `conflicting` is covered by
  `confirmedIds`. A client that confirms only a subset of the real conflicts (or omits the field
  entirely on a stale/racing request) has that subset cancelled, and the pause is still applied
  unconditionally afterward (`:224-227`) — leaving the UN-confirmed conflicting bookings sitting inside a
  now-paused window with no further check or client-visible signal. This is the more severe of the three
  findings; D5/D8 are contention/timing issues, D1 is a plain missing-validation gap reachable on any
  single-request client bug or partial UI submission, no race required.
- `:220-222` — for each `validatedIds` entry, calls `bookingService.cancelDueToPause(bookingId, coachId,
  parentId)` (still inside the outer lock/transaction).
  `BookingService.cancelDueToPause` (`:665-685`, read in full): does its own `transition(...)` call which
  can throw `OptimisticLockingFailureException` (caught and re-thrown as `OperationNotAllowedException`
  409) if that SPECIFIC booking row was concurrently modified — this protects against a stale write to
  the SAME row, but does nothing for D1 (a booking never targeted for cancellation in the first place)
  or D8 (a booking that didn't exist yet at the time of the `:195-196` read).
- `:224-227` — pause applied and `purchase` saved, still under the same lock, with no re-check of
  `conflicting` immediately before this write.
- **D1's own originating ledger bullet** (`deferred-work.md:1191`) and **D5's/D8's** (`:1195`, `:1196`),
  under `## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)` — all
  three justified purely by "verified byte-for-byte identical to legacy `SessionPackService.pausePack()`;
  AC4 explicitly requires mirroring legacy here" / "same … shape/risk as the legacy method." Confirmed:
  `find src/main -iname SessionPackService.java` returns nothing — that legacy class is gone (deleted by
  Story 11.3). No `[CLOSED by ...]`/`[DECIDED ...]` annotation exists on any of the three at this `HEAD`.

### ⚠️ Critical caveat, found while re-verifying this AC's own premise — read before implementing

**Naively narrowing the `session_pack_purchases` lock scope (moving the `findByIdForUpdate` acquisition
to immediately before the final `:224-227` write, instead of at the top of the method) would trade D5's
risk for a worse one: it breaks the atomicity this method currently relies on between "cancel the
confirmed bookings" and "apply the pause."** Today, holding the lock for the WHOLE method means the
cancellations and the pause application either all commit together or all roll back together. If the lock
acquisition instead moved to just before the final write, and that later re-check discovered the pack was
concurrently paused/modified by someone else, the bookings cancelled earlier in this same transaction
would still need to be rolled back too (they're in the same `@Transactional` boundary, so a later
exception DOES roll everything back — this is actually fine, since Spring rolls back the whole method on
an uncaught exception) — so a same-transaction narrow-then-recheck-then-throw design is actually safe by
default rollback semantics, AS LONG AS the recheck throws rather than silently proceeding. **The dev-story
implementer must design this deliberately, not assume "narrow the lock" is a drop-in change** — investigate
whether Postgres `FOR UPDATE`/`FOR KEY SHARE` behavior on the OTHER tables these cancellations touch
(via `cancelDueToPause`'s own optimistic-locked `transition`) creates any lock-ordering concern once the
`session_pack_purchases` lock is acquired LATER relative to those other row touches, before finalizing the
design.

### The fix

- **D1:** after computing `validatedIds`, verify every id in `conflicting` is present in `confirmedIds`
  (or equivalently, that `validatedIds` covers all of `conflicting`, not just a filtered subset) — if not,
  return the SAME kind of `PauseConflictResponse(false, items, null)` used for the original unconfirmed
  case (`:202-208`) instead of silently proceeding with a partial cancellation set.
- **D8:** immediately before `:224-227`'s write (after the cancellation loop), re-run
  `findConflictingBookingsForPause` with the SAME parameters. If any NEW conflict appears that wasn't in
  the original `conflicting` set (and therefore couldn't have been in `confirmedIds`), do not apply the
  pause — return the fresh conflict list the same way, so the client can re-confirm.
- **D5:** re-scope the `session_pack_purchases` lock per the caveat above, ONLY if the investigation
  there confirms it's safe to do without introducing a new lock-ordering or atomicity hazard; if it isn't
  cleanly safe, an acceptable, disclosed fallback is leaving the lock held for the whole method (matching
  today's shape) while still fixing D1 and D8 — say so explicitly if that's the outcome, per this
  project's own "disclosed, not silent" scoping convention.

### Test plan

- New/extended concurrency or unit test(s): (a) confirming a partial-`confirmedIds` request (D1) is
  rejected with the unconfirmed-conflict response, not silently applied; (b) a booking created between
  the conflict read and the pause-apply write (D8) — simulate via a test seam or a genuinely concurrent
  second thread — is caught by the re-check and blocks the pause; (c) the existing happy-path
  `pausePack` tests (fully-confirmed, no new conflicts) still pass unmodified.

---

## AC4: `DatabaseResetTestExecutionListener` — bound the async-executor quiesce wait (test infra only)

**Files:**
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java`

### Current state (re-verified against `HEAD`)

`quiesceAsyncExecutors` (full method + its own Javadoc read): `Awaitility.await().atMost(Duration.ofSeconds(10))...`
catches `ConditionTimeoutException` and logs+proceeds. **The method's own Javadoc already explicitly
considered and rejected letting this propagate** — doing so "would skip the reset transaction below
entirely for this test method, leaving stale data in place for both this test and every subsequent one
for the rest of the JVM — strictly worse than proceeding with a residual … deadlock risk for this single
invocation." So "fail the reset outright" (as originally phrased to the owner) is not actually a live
option without re-litigating that already-reasoned tradeoff.

### The fix

Raise the `atMost` bound (the only lever this method's own reasoning leaves open) from 10s to a value
sized from real evidence — check whether the 9-consecutive-run reproduction study referenced in this
method's own Javadoc recorded any actual async-task duration data to size a new bound from; if not, pick
a conservative multiple (e.g. 20-30s) and document the reasoning inline, matching this method's own
existing documentation density. Do not change the catch-and-proceed shape itself.

### Test plan

No new automated test practical for this (it's exercised implicitly by the whole test suite's own
listener lifecycle) — verify via a targeted local run (this is test infrastructure, not the project's own
`mvn verify`-is-the-only-gate convention, which applies to production-code verification) that the change
doesn't regress reset behavior, and note the manual verification performed in Dev Agent Record.

---

## AC5: Test hygiene — replace hand-built entities with `Instancio`

**Files:**
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminReviewServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerIsolationTest.java`

### Current state (re-verified against `HEAD`, exact current line numbers)

- `AdminReviewServiceTest.java:57` — `CoachReview review = new CoachReview();`
- `ReviewFlagServiceTest.java:96,101,124,129` — `new CoachProfile()`/`new CoachReview()` (two occurrences
  each, in what look like two separate test methods).
- `PastDueGracePeriodTest.java:90,164,173` — `new CoachProfile()`, `new PaymentCoachSubscription()`,
  `new PaymentPlayerSubscription()`.
- `SubscriptionSchedulerIsolationTest.java:100,279,317,350,370,380,390,398,406,417` — `new CoachProfile()`,
  `new CoachSubscription()` (×3), `new CoachSubscriptionChange()`, `new PlayerSubscriptionChange()`,
  `new PaymentCoachSubscription()` (×2), `new PaymentPlayerSubscription()` (×2). This file has by far the
  most occurrences — budget the most time here.

### The fix

Replace each `new <Entity>()` + manual field-setter chain with `Instancio.of(<Entity>.class)...create()`,
setting only the fields the test actually asserts on or that are required for the code path under test
(mirroring how this codebase's other `Instancio`-based tests are already written elsewhere — grep for an
existing `Instancio.of(CoachProfile.class)` or similar call as a style reference before starting). Do not
change test assertions or behavior — this is a construction-mechanism swap only.

### Test plan

All 4 files' existing test methods must continue passing unmodified in behavior — this AC changes how
test fixtures are built, not what is asserted. Run each file's full test class after conversion.

---

## AC6: Ledger hygiene

Standard closeout task for this story series:

- **Hazard 2** (`deferred-work.md:3118-3121`, most recently re-confirmed
  `[AUDIT 2026-09-23 (skillars-deferred-130)]` at `:3126-3133`) — append `[CLOSED by skillars-deferred-136
  AC1 — <one-line summary of the actual dedicated-datasource mechanism landed, once implementation
  confirms it>]`.
- **`markFailed` auto-retry residual** — this story's own AC2 is the first to build it; add a note where
  `markFailed`'s own alert-only precedent is discussed (search for where `skillars-deferred-133`/`135`
  documented "alert-only, no auto-retry" and annotate `[CLOSED by skillars-deferred-136 AC2 — <summary>]`).
- **D1, D5, D8** (`deferred-work.md:1191,1195,1196`) — append `[CLOSED by skillars-deferred-136 AC3 —
  <summary, including whether D5's lock-scope narrowing landed or was disclosed-and-declined per the
  caveat>]` to all three, individually.
- **CI reset-quiesce residual** (referenced in `DatabaseResetTestExecutionListener`'s own Javadoc, cross-
  check whether `deferred-work.md` has its own separate bullet for this or only the inline code comment —
  search before assuming) — annotate `[CLOSED by skillars-deferred-136 AC4 — <new bound>]` if a ledger
  bullet exists; if not, this is a code-comment-only closeout, note that explicitly rather than inventing
  a ledger bullet that never existed.
- **Instancio test-hygiene item** (originating in `skillars-deferred-131`'s own code review — locate the
  exact bullet by searching for the 4 file names together) — append `[CLOSED by skillars-deferred-136 AC5]`.
- **Delete/correct the stale entry:** "`acceptBooking` fires `PAYMENT_CAPTURED` without a real capture"
  (old `skillars-7-1` D4, most recently re-confirmed "genuinely still open" 2026-09-08) — confirmed
  FACTUALLY WRONG at this `HEAD`: `BookingService.acceptBooking` (`:349-414`, re-verify exact lines at
  implementation time) only ever transitions a booking to `PAYMENT_PENDING`; the actual `PAYMENT_CAPTURED`
  transition now fires only from `BookingPaymentPersistenceService` after a real Stripe/session-pack
  capture (confirmed at `BookingPaymentPersistenceService.java:234,279,307` — re-verify exact lines at
  implementation time). This architecture was built by Story 7.2 and has matured since; the ledger's own
  last "still open" re-confirmation was itself wrong by the time it was written, or the code changed
  after without the ledger being updated. Annotate `[CLOSED by skillars-deferred-136 AC6 — re-verified
  factually incorrect at current HEAD; acceptBooking has transitioned to PAYMENT_PENDING-only since
  Story 7.2, not PAYMENT_CAPTURED, for some time]` rather than silently deleting the bullet — matches this
  project's own "annotate inline, do not delete" convention.
- Re-run the standard grep sweep for every file this story touches (`GdprErasureService.java`,
  `DataSourceConfig.java`, `GdprRequestRepository.java`, `GdprRequest.java`, `PackSessionService.java`,
  `DatabaseResetTestExecutionListener.java`, the 4 AC5 test files) across the full ledger — confirm no
  other bullet references these files in a way this story's changes affect.
- Add `last_updated`/`development_status` entries to `sprint-status.yaml` per the standing convention.

**Explicitly not in scope, left open:**
- Any further GDPR-erasure hardening beyond AC1/AC2 (e.g. bounding `deletePlayerDevelopmentData`'s
  per-child hold time directly, rather than the aggregate deadline budget already in place) — not
  reopened by this story.
- The AC2 dedup-guard design question (whether retry-cap exhaustion needs its own distinguishable alert
  reason) — implementation decides and documents, not a pre-baked requirement.

---

## Tasks

- [ ] 1. **AC1:** Resolve the open `EntityManagerFactory`-sharing question empirically first (see AC1's
   own design note). Add the dedicated `HikariDataSource`/`PlatformTransactionManager` pair in
   `DataSourceConfig`, rewire `initTemplates()`, add the test-side equivalent in `TestConfig.java`. New
   `GdprErasureIT` coverage proving the dedicated pool is actually used and independently bounded.
- [ ] 2. **AC2:** New Flyway migration for the retry-tracking column(s). Build the scheduled re-drive
   (service method + thin scheduler wrapper), reusing `erase()`'s existing entry point and safety
   mechanisms. Dedup guard against a concurrent manual resubmit. Size `@SchedulerLock`/cadence from real
   arithmetic. New tests per AC2's test plan.
- [ ] 3. **AC3:** Resolve the lock-scope-narrowing safety question from the critical caveat FIRST, before
   changing anything. Fix D1 (under-confirmation check) and D8 (re-check-before-write) unconditionally;
   fix D5 (lock scope) only if the investigation confirms it's safe, otherwise disclose why it was left
   as-is. New concurrency/unit tests per AC3's test plan.
- [ ] 4. **AC4:** Raise `quiesceAsyncExecutors`'s `atMost` bound with a documented rationale. Manual
   verification run, noted in Dev Agent Record.
- [ ] 5. **AC5:** Convert all 4 test files' hand-built entities to `Instancio`, one file at a time,
   re-running each file's tests after its own conversion.
- [ ] 6. **AC6:** Ledger closeout (6 items annotated/closed including the one stale-entry correction, grep
   sweep, `sprint-status.yaml` update).
- [ ] 7. Full targeted-suite regression run for every touched class/package (`platform.admin.service`,
   `platform.payment.service` incl. `PackSessionService`, the 4 AC5 test classes, plus `GdprErasureIT`,
   `GdprErasureServiceConcurrencyIT`, any new AC1/AC2/AC3 tests) — no local `mvn verify` (GitHub CI is
   this project's sole full-verification gate, per standing convention).

---

## Dev Notes

- **AC1 is the highest-risk/highest-uncertainty piece of this story** — the `EntityManagerFactory`
  multi-`DataSource` question is genuinely open (see AC1's own design note) and must be resolved with a
  real spike/read of Spring's `JpaTransactionManager`/`LocalContainerEntityManagerFactoryBean` docs and
  this project's own persistence config before writing the fix, not assumed to "just work" from the
  `TransactionTemplate` swap alone.
- **AC2's schema decision (which column(s), what grace-window mechanism) is intentionally left for
  implementation**, not pre-specified in exact DDL — pick the smallest shape that lets the sweep
  distinguish "just failed" from "safe to retry" and "exhausted," matching `EmailRetryScheduler`'s own
  `attempts`/`deadline`/`retry` shape as the closest precedent, not a mandate to copy it exactly.
- **AC3's D1 finding was surfaced during this story's own drafting, not part of the original owner
  question** — flagged prominently in Context above; if in doubt about scope, treat D1 as in-scope (same
  method, same stale-justification root cause as D5/D8, and arguably the most serious of the three) but
  raise it again with the owner if implementation reveals it's larger than described here.
- **This story's citations were verified against `master@dd5ef063`** (post `skillars-deferred-135`/PR
  #230). Re-diff every cited line against whatever `master` actually looks like by the time
  implementation starts, per this project's own standing "diff cited lines to ensure they're still
  accurate" convention — this is especially important for AC6's `BookingService`/
  `BookingPaymentPersistenceService` line citations, which were not re-verified to the exact line during
  this story's own drafting (marked "re-verify exact lines at implementation time" above).

---

## Dev Agent Record

### Completion Notes

_(Filled in by `/bmad-dev-story` on implementation.)_

### File List

_(Filled in by `/bmad-dev-story` on implementation.)_

### Change Log

- 2026-09-25: Story created via `/bmad-create-story`, sourced from a fresh full read of
  `deferred-work.md` plus re-verification of every candidate against `master@dd5ef063`. Four owner
  decisions taken live (AskUserQuestion): build the dedicated-`HikariDataSource` fix for GDPR erasure's
  "Hazard 2" now (AC1); build a scheduled GDPR erasure auto-retry mechanism (AC2); fix
  `PackSessionService.pausePack`'s lock-scope/TOCTOU/under-confirmation gaps now that their sole
  legacy-parity justification is stale (AC3, scope expanded from D5/D8 to also include D1, found during
  drafting — see Context); include the CI reset-quiesce residual as a filler AC (AC4, though "fail
  outright" as originally framed turned out to already be considered-and-rejected by the code's own
  Javadoc — only the wait-bound lever is actually viable). Plus two decision-free ACs: Instancio test
  hygiene (AC5) and ledger closeout (AC6, including correcting one now-factually-wrong ledger entry found
  during drafting). Status: ready-for-dev.

## Story Completion Status

Not yet implemented. Status: ready-for-dev.
