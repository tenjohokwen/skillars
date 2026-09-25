# Story: GDPR Erasure Connection-Pool & Auto-Retry Hardening, PackSessionService Lock/TOCTOU Fix & Test Hygiene

**Story Key:** `skillars-deferred-136-gdpr-datasource-retry-packsession-lock-fix`
**Epic:** Deferred Work
**Priority:** High (two GDPR-erasure production-safety gaps re-confirmed open across stories 128/129/130/135
and now explicitly greenlit rather than declined a 4th/5th time; one payment-path lock-scope + TOCTOU pair
whose sole "leave as-is" justification — byte-for-byte legacy parity — is now factually stale, the legacy
class having been deleted since; one CI test-infra residual; one mechanical test-hygiene cleanup).
**Status:** done
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

- [x] 1. **AC1:** Resolve the open `EntityManagerFactory`-sharing question empirically first (see AC1's
   own design note). Add the dedicated `HikariDataSource`/`PlatformTransactionManager` pair in
   `DataSourceConfig`, rewire `initTemplates()`, add the test-side equivalent in `TestConfig.java`. New
   `GdprErasureIT` coverage proving the dedicated pool is actually used and independently bounded.
   **Design corrected during implementation — see Completion Notes: the spike disproved the
   PlatformTransactionManager-swap design; implemented via `RoutingDataSource` instead
   (`initTemplates()` was NOT changed).**
- [x] 2. **AC2:** New Flyway migration for the retry-tracking column(s). Build the scheduled re-drive
   (service method + thin scheduler wrapper), reusing `erase()`'s existing entry point and safety
   mechanisms. Dedup guard against a concurrent manual resubmit. Size `@SchedulerLock`/cadence from real
   arithmetic. New tests per AC2's test plan.
   **Real bug found and fixed during implementation — see Completion Notes: the initial re-query-page-0
   loop hung indefinitely against a persistently dedup-skipped candidate; fixed by tracking
   already-considered ids within one sweep invocation.**
- [x] 3. **AC3:** Resolve the lock-scope-narrowing safety question from the critical caveat FIRST, before
   changing anything. Fix D1 (under-confirmation check) and D8 (re-check-before-write) unconditionally;
   fix D5 (lock scope) only if the investigation confirms it's safe, otherwise disclose why it was left
   as-is. New concurrency/unit tests per AC3's test plan.
   **D5 investigated and disclosed-and-declined (narrowing would introduce a second correctness surface
   disproportionate to the ask); D1/D8 fixed, requiring a self-invocation split for D8's rollback to work.**
- [x] 4. **AC4:** Raise `quiesceAsyncExecutors`'s `atMost` bound with a documented rationale. Manual
   verification run, noted in Dev Agent Record.
- [x] 5. **AC5:** Convert all 4 test files' hand-built entities to `Instancio`, one file at a time,
   re-running each file's tests after its own conversion.
   **Real bug found and fixed during implementation — see Completion Notes: Instancio's random boolean
   generation flipped `CoachSubscriptionChange`/`PlayerSubscriptionChange.applied` non-deterministically;
   pinned to `false` (the real entity's own default) once a test failure surfaced it.**
- [x] 6. **AC6:** Ledger closeout (6 items annotated/closed including the one stale-entry correction, grep
   sweep, `sprint-status.yaml` update).
- [x] 7. Full targeted-suite regression run for every touched class/package (`platform.admin.service`,
   `platform.payment.service` incl. `PackSessionService`, the 4 AC5 test classes, plus `GdprErasureIT`,
   `GdprErasureServiceConcurrencyIT`, any new AC1/AC2/AC3 tests) — no local `mvn verify` (GitHub CI is
   this project's sole full-verification gate, per standing convention).
   **91 targeted tests across 11 classes, 0 failures, 0 errors — see Completion Notes for the full
   breakdown.**

### Review Findings

- [x] [Review][Decision] Dedup-guard TOCTOU overclaim in `retryFailedErasures` — a manual resubmit
  created by the user between the `existsByUserIdAndRequestTypeAndStatusIn` check and the `erase()`
  call is not caught (the Javadoc's "this sweep never races a concurrent manual resubmit" only holds
  for a *persistently*-skipped row, not this narrower window). Low-severity in practice (duplicate
  erasure work on the same user, not corruption). **Owner decision: soften the Javadoc only** — disclosed
  as an accepted residual rather than closed; no behavior change. [GdprErasureService.java:509-513,539-543]
- [x] [Review][Patch] `GdprErasureDataSourceRoutingIT`'s saturation test doesn't exercise the real ~10s
  Hikari connection-timeout — `assertConnectionPoolNotSaturated`'s pre-check fires near-instantly once
  the dedicated pool is exhausted, so the test's own comment ("must fail near the dedicated pool's own
  10s connection-timeout") misdescribes what's actually proven (a near-instant pre-check rejection, not
  the real Hikari-level timeout). **Fixed:** Javadoc and inline assertion message corrected to describe
  the pre-check rejection; assertion tightened from `< 20s` to `< 5s` to actually pin the near-instant
  behavior (re-run: 2/2 passing, 0.434s total). [GdprErasureDataSourceRoutingIT.java:150-153]
- [x] [Review][Patch] `retryFailedErasures`'s retry query has no `requestType` filter — not currently
  exploitable (an `EXPORT` request's `FAILED` status never gets a `failed_at` stamp, so SQL's `NULL <`
  semantics already exclude it), but this relies on an undocumented, fragile invariant spanning two
  files (`GdprExportService`'s failure path never sets `failedAt`). **Fixed:** renamed the repository
  method to `findByRequestTypeAndStatusAndFailedAtBeforeAndRetryCountLessThan` with an explicit
  `requestType = "ERASURE"` argument; updated both `GdprErasureServiceTest` mock call sites.
  [GdprRequestRepository.java:26; GdprErasureService.java:530-531]
- [x] [Review][Patch] `retryFailedErasures`'s per-candidate dedup-check/`incrementRetryCount` calls are
  not covered by the loop's own exception isolation — only the `erase()` call itself is try/caught;  an
  exception from `existsByUserIdAndRequestTypeAndStatusIn` or `self.incrementRetryCount` propagates out
  of the whole sweep (no catch in `GdprErasureRetryScheduler` either), aborting every remaining
  candidate for the run instead of just skipping the one that failed. **Fixed:** widened the try/catch
  to wrap the whole per-candidate body (dedup check through `erase()`); a `continue` for the dedup-skip
  path still works correctly inside the try. [GdprErasureService.java:539-551]
- [x] [Review][Patch] `gdprErasureHikariConfig`'s `autoCommit`/timezone settings are hardcoded literal
  duplicates of the primary pool's config (`application.yaml`-driven) rather than derived from a shared
  source — a real drift risk if the primary pool's autocommit/timezone config ever changes centrally.
  **Fixed:** both values now read via `@Value("${spring.datasource.hikari.auto-commit}")` /
  `@Value("${spring.datasource.hikari.connection-init-sql}")` — the SAME yaml keys the primary pool's own
  `@ConfigurationProperties` binding reads, eliminating drift by construction rather than by convention.
  [DataSourceConfig.java:95-96]
- [x] [Review][Patch] `RoutingDataSource`'s constructor manually invokes `afterPropertiesSet()`, but
  Spring's container will invoke it again automatically during normal bean lifecycle processing (the
  class implements `InitializingBean` via `AbstractRoutingDataSource`) — harmless today since the method
  is idempotent, but undocumented and fragile. **Fixed:** removed the manual call (both construction
  sites are `@Bean` factory methods, so the container always invokes it); documented why in a comment.
  [RoutingDataSource.java:39]
- [x] [Review][Patch] `DataSourceConfig.dataSource(HikariConfig, HikariConfig)` relies on Spring
  resolving its two same-typed `HikariConfig` parameters by matching parameter names to bean names — a
  mechanism that depends on the build compiling with parameter-name metadata (`-parameters`). Currently
  safe only because `spring-boot-starter-parent` enables that flag by default. **Fixed:** added explicit
  `@Qualifier("hikariConfig")`/`@Qualifier("gdprErasureHikariConfig")` to the two parameters.
  [DataSourceConfig.java:50]
- [x] [Review][Defer] `TestConfig`'s new dedicated-pool `DataSource` bean adds connection-pool pressure
  (a 3-connection pool per Spring test context) on top of the existing 25-connection primary pool, on a
  test suite whose own Dev Agent Record already acknowledges resource-contention flakiness across ~40
  context permutations. Matches the existing primary-pool bean's own shape (neither sets
  `minimumIdle`), and is required by AC1's own test plan to prove a genuinely separate, independently-
  pooled dedicated DataSource — deferred, pre-existing test-infra tradeoff, not blocking.
  [TestConfig.java:94-106]

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

**AC1 — design deviation found and corrected during implementation, resolved live via AskUserQuestion:**
Before writing any production code, built a throwaway spike test (`JpaTransactionManager` constructed
against a deliberately unreachable `DataSource` but the app's real, shared `EntityManagerFactory`) —
it PASSED, i.e. the JPA query succeeded anyway. This empirically disproves the design AC1 sketched
(share the `EntityManagerFactory`, swap in a new `PlatformTransactionManager` backed by a second
`DataSource`): connection acquisition for JPA operations is governed entirely by whichever `DataSource`
the shared `EntityManagerFactory`'s own Hibernate `ConnectionProvider` was bootstrapped against, not by
whatever `DataSource` a differently-backed transaction manager is handed — the dedicated pool would
have sat completely unused. Also confirmed `deletePlayerDevelopmentData` is too deeply JPA-managed
(11 Spring Data repository bulk-deletes, `entityManager.refresh`/`flush`, a native query) to drop to
plain JDBC without a large rewrite, and a genuine second persistence unit would require
`@EnableJpaRepositories` partitioning across ~11 repositories spanning 4-5 modules — out of proportion
to this AC.

Presented the finding to the owner with three options (AbstractRoutingDataSource / second persistence
unit / decline AC1 a 5th time); owner chose **AbstractRoutingDataSource**. Implemented as:
- `infrastructure.config.RoutingDataSourceContext` (generic `ThreadLocal<String>` key holder) and
  `infrastructure.config.RoutingDataSource` (generic `AbstractRoutingDataSource` subclass, owns its
  targets' lifecycle via `DisposableBean`) — both fully business-agnostic, reusable by any future module
  needing a second dedicated pool.
- `DataSourceConfig.dataSource()` now builds a `RoutingDataSource` wrapping the primary Hikari pool
  (default target) plus a new `gdprErasureHikariConfig`-backed pool (max 3, connection-timeout 10s vs.
  primary's 30s, `auto-commit=false`/UTC `connection-init-sql` mirrored from the primary pool — required
  for correctness given Hibernate's global `connection.provider_disables_autocommit=true` setting)
  keyed `DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY`. Only ONE `DataSource` bean exists in the
  context, so no `@EnableJpaRepositories`/multi-persistence-unit changes were needed anywhere.
- `GdprErasureService.erase()` and `.deletePlayerDevelopmentData()` each set the routing key
  immediately before their own `REQUIRES_NEW`-entering call and clear it in a `finally` block —
  `initTemplates()`/`requiresNewTemplate`'s own `PlatformTransactionManager` wiring was **NOT** changed
  from the shared `txManager`, contrary to the story's original text (the routing key is what selects
  the pool now, not the transaction manager).
- `assertConnectionPoolNotSaturated` was updated to resolve the GDPR pool specifically off the routing
  `DataSource` (`RoutingDataSource.getNamedTarget`) instead of casting the injected `dataSource` field
  directly to `HikariDataSource` — the original cast would have silently no-op'd forever post-fix, since
  `dataSource` is now a `RoutingDataSource`, not a `HikariDataSource`. Caught by running the existing
  `GdprErasureIT` pool-saturation tests, not by static analysis.
- `TestConfig.java` gained an explicit `@Bean DataSource dataSource()` (conditioned on
  `datasource.container=true`, mirroring `DataSourceConfig`'s own opposite condition) building both
  pools directly from `SharedContainers.postgres()`'s coordinates — this bean provides Spring Boot's own
  `DataSourceAutoConfiguration` a reason to back off (`@ConditionalOnMissingBean(DataSource.class)`), so
  `jdbcConnectionDetails()` is no longer what builds the primary pool in tests (left in place, harmless).
- New `GdprErasureDataSourceRoutingIT` (2 tests): (1) a direct `HikariPoolMXBean.getActiveConnections()`
  delta proof that a routing-key-tagged acquisition checks out a connection from the dedicated pool
  specifically, not the primary; (2) a saturation test exhausting the dedicated pool's 3 connections then
  driving a real `erase()` call, asserting it fails within 20s (near the dedicated pool's 10s timeout),
  nowhere near the primary's 30s.
- Two pre-existing `GdprErasureIT` tests (`erase_connectionPoolSaturated_failsFastInsteadOfBlockingFor...`,
  `erase_connectionPoolSaturated_routedThroughListener_...`) needed updating, not left unmodified per the
  story's own AC1 test plan: their premise (saturating the primary pool blocks `erase()`) is exactly what
  this AC fixes — post-fix, saturating the PRIMARY pool no longer affects `eraseTransactional`'s
  acquisition at all, so both were rewritten to saturate the DEDICATED pool instead (via
  `RoutingDataSource.getNamedTarget`), which is what `assertConnectionPoolNotSaturated` now actually
  checks. This is a deliberate, necessary test-behavior change, not scope creep — the story's own
  "must not change `erase()`'s observable happy-path behavior" constraint does not cover these
  failure-path saturation tests, whose whole point was to characterize the exact hazard AC1 closes.
- Targeted regression: `GdprErasureIT` 33/33, `GdprErasureServiceConcurrencyIT` 1/1,
  `GdprErasureServiceTest` 7/7, `GdprErasureDataSourceRoutingIT` 2/2 (new) — zero regressions. A broader
  `platform.admin.**`+`platform.security.**` sweep (534 tests) surfaced 2 unrelated context-startup
  failures (`AdminCoachEnforcementConcurrencyIT`, `AdminCoachEnforcementIsolationRuntimeIT`) that
  reproduced as resource contention from running ~40 Spring context permutations back-to-back in one
  local JVM, not a regression — both pass cleanly (10/10, 3/3) re-run in isolation. No local `mvn verify`
  per project convention.

**AC2 — real infinite-loop bug found and fixed during implementation, not by static review:** the
scheduled re-drive (`GdprErasureService.retryFailedErasures()` + thin `GdprErasureRetryScheduler`
wrapper) initially re-queried `PageRequest.of(0, 50)` in a loop until the page came back empty,
reasoning that every processed candidate would drop out of the `(FAILED, failedAt < graceDeadline,
retryCount < cap)` filter. That reasoning missed the dedup-skip path: a candidate skipped because its
`userId` has a concurrent PENDING/PROCESSING row is left completely unchanged (same `failedAt`, same
`retryCount`, still `FAILED`), so it re-matched the identical filter on every subsequent iteration
forever. Caught live: a real `GdprErasureRetryIT` run hung indefinitely (confirmed via `pg_stat_activity`
— the SAME `SELECT ... FROM admin.gdpr_requests` query re-issued in a tight loop, CPU actively burning,
no lock waits). Fixed by tracking `Set<UUID> consideredThisSweep` and looping only while the most recent
page contained at least one id not already in that set — bounded by the total distinct matching row
count, terminates even when every remaining candidate is persistently skipped (a fast, sub-second unit
test in `GdprErasureServiceTest` now pins this: a mocked repository returning the identical single-row
page forever, asserting the dedup check fires exactly once, not per iteration). New migration `V154`
adds `retry_count`/`failed_at` to `admin.gdpr_requests`; `failed_at` (not `createdAt`, which is set once
at construction) is stamped by `markFailedStatusUpdate`, giving the grace-window check a value that
actually reflects the most recent failure. Grace window 1h, retry cap 3, cadence `0 0 6 * * *`
(`lockAtMostFor PT30M`, `lockAtLeastFor PT2M`) — sizing documented inline mirroring sibling schedulers'
own convention. Dedup guard mirrors `GdprRequestService.requestErasure`'s own PENDING/PROCESSING
precondition. New `GdprErasureRetryIT` (4 tests, real Postgres): past-grace-window redrive → COMPLETED;
within-grace-window left alone; at-retry-cap left alone; concurrent-PENDING-row dedup-skipped. Both new
IT test files' seeded `main."user"` rows initially used a 1-character `password_hash` placeholder,
which passed at INSERT time but tripped `User.password`'s bean-validation `@Size(min=60,max=60)` on the
UPDATE `eraseTransactional`'s own anonymization step issues — surfaced as a real test failure
(`ConstraintViolationException`) once a test path actually reached that step; fixed with a proper
60-character placeholder in both `GdprErasureRetryIT` and `GdprErasureDataSourceRoutingIT`.

**AC3 — `PackSessionService.pausePack` D1/D5/D8:** D1 fixed by checking `validatedIds.containsAll(
conflictMap.keySet())` right after computing `validatedIds` — a partial confirmation now returns the
same unconfirmed-conflict response as zero confirmations, instead of cancelling the confirmed subset and
applying the pause anyway. D8 fixed by re-running `findConflictingBookingsForPause` with identical
parameters immediately before the final write, under the still-held lock; any id not already in the
original conflict set aborts. Because the cancellation loop may have already written to this transaction
by that point, the D8 abort had to be an exception crossing the `@Transactional` proxy boundary, not a
normal return (a normal return would commit the loop's cancellations despite "pause not applied") —
required splitting `pausePack` into a thin, non-transactional wrapper (catches a new private
`PauseWindowConflictException` and converts it back to the identical `PauseConflictResponse(false, items,
null)` client-facing shape) plus `pausePackTransactional` (the real `@Transactional` method), mirroring
`GdprErasureService.erase`/`eraseTransactional`'s established self-invocation pattern — including adding
the analogous `self` field. **This broke the existing pure-Mockito `PackSessionServicePauseTest`'s 12
tests** (`self` stays `null` outside a Spring context, so `self.pausePackTransactional(...)` NPEs) —
fixed with the SAME established codebase pattern already used for `GdprErasureServiceTest`'s own
identical `self` field: `ReflectionTestUtils.setField(packSessionService, "self", packSessionService)`
in `@BeforeEach`. D5 investigated per the story's own critical caveat: `BookingService.transition()`
(reached via `cancelDueToPause`) was read in full and confirmed to lock exactly one row, on the `booking`
table, no lock-ordering hazard from other tables — but narrowing the `session_pack_purchases` lock scope
would also require moving the pre-existing "one pause per lifetime" check to right before the final
write (so a second concurrent `pausePack` can't pass it under an unlocked read), a second correctness
surface beyond what D5 itself asked for. Disclosed-and-declined per the story's own sanctioned fallback;
D1/D8 fixed unconditionally. Two new unit tests in `PackSessionServicePauseTest` (partial confirmation →
conflict response, not silent cancel; a re-sequenced mock stub simulating a late-appearing conflict →
blocks the pause) — the story's own test plan explicitly sanctions "a test seam" for D8, not requiring
genuine two-thread concurrency; the self-invocation split's actual rollback behavior relies on the same
Spring proxy mechanism already exercised by `GdprErasureService`'s own shipped, tested split.

**AC4 — `DatabaseResetTestExecutionListener.quiesceAsyncExecutors`:** `atMost` raised 10s → 30s (a
conservative 3x multiple; no empirical async-task-duration data exists to size a tighter bound from, per
the skillars-deferred-132 AC1 Fix 6 reproduction study's own gap). Catch-and-proceed shape unchanged, per
the story's own constraint. Test infrastructure only — verified via this story's own many other
`AbstractIntegrationTest`-extending targeted runs (91 tests across 11 classes), which implicitly exercise
this listener's lifecycle on every test method; no dedicated test added, matching the story's own test
plan.

**AC5 — Instancio conversions, all 4 files, 18 hand-built instances:** `AdminReviewServiceTest`,
`ReviewFlagServiceTest`, `PastDueGracePeriodTest`, `SubscriptionSchedulerIsolationTest`, one file at a
time, each file's own tests re-run after its own conversion (matching the story's own instruction).
**Real test-fragility bug found and fixed, not by static review:** `SubscriptionSchedulerIsolationTest`
failed after conversion (`Expecting value to be false but was true`) — Instancio's random boolean
generation had flipped `CoachSubscriptionChange`/`PlayerSubscriptionChange.applied` (defaults to `false`
on the real entity; several tests depend on that starting value, e.g. "malformed tier leaves
applied=false so it is re-selected next run") non-deterministically. Fixed by explicitly pinning
`.set(field(...::isApplied), false)` in both `coachChange`/`playerChange` helpers — the general lesson
(explicitly pin any field a test's OWN assertions depend on for its default/initial value, not just the
fields the original hand-built code happened to set) is recorded here for the next file this pattern is
applied to. Checked `PaymentCoachSubscription`/`PaymentPlayerSubscription.cancelAtPeriodEnd` (the other
boolean-with-a-default field across the 4 files) for the same risk — confirmed unread by any code path
these two files' tests exercise, safe to leave Instancio-random.

**AC6 — ledger closeout:** all 6 items closed in `deferred-work.md` (Hazard 2 under AC1's own entry;
the `markFailed` auto-retry residual under skillars-deferred-133 AC1 Fix 1's own bullet; D1/D5/D8
individually under the skillars-11-1 section; the CI reset-quiesce residual under skillars-deferred-132
AC1 Fix 6's own residual note; the Instancio-hygiene bullet under skillars-deferred-131's own review
section — all 4 file names matched exactly; the stale `acceptBooking`/`PAYMENT_CAPTURED` entry annotated
(not deleted) at both its original D4 bullet and its most recent 2026-09-08 re-confirmation, re-verifying
`BookingService.acceptBooking:350-414` only ever reaches `PAYMENT_PENDING` and `BookingPaymentPersistenceService.java:234,279,307`
are the real `PAYMENT_CAPTURED` sites — both line citations re-verified exact-match against current
HEAD, no drift). Grep sweep across all touched files (`GdprErasureService.java`, `DataSourceConfig.java`,
`GdprRequestRepository.java`, `GdprRequest.java`, `PackSessionService.java`,
`DatabaseResetTestExecutionListener.java`, the 4 AC5 test files) found 3 further `GdprErasureService.java`
mentions and 1 `PackSessionService.java` mention, all already `[CLOSED by skillars-deferred-132 ...]` or
unrelated to this story's changes — no action needed. `sprint-status.yaml` updated.

### File List

- `src/main/java/com/softropic/skillars/infrastructure/config/RoutingDataSourceContext.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/config/RoutingDataSource.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` (modified)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (modified)
- `src/test/java/com/softropic/skillars/config/TestConfig.java` (modified)
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureDataSourceRoutingIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (modified — 2 pool-saturation tests updated for the routing design)
- `src/main/resources/db/migration/V154__gdpr_requests_retry_tracking.sql` (new)
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequest.java` (modified — `retryCount`/`failedAt` fields)
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequestRepository.java` (modified — new query method)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureRetryScheduler.java` (new)
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureRetryIT.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` (modified — D1/D8 fixes, self-invocation split)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java` (modified — 2 new tests, `self` field fix)
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java` (modified — `atMost` 10s→30s)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminReviewServiceTest.java` (modified — Instancio)
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceTest.java` (modified — Instancio)
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java` (modified — Instancio)
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerIsolationTest.java` (modified — Instancio)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — AC6 ledger closeout)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — story status)

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
- 2026-09-25: `/bmad-dev-story` implementation complete. All 6 ACs + full regression run done. AC1's
  design was corrected mid-implementation after an empirical pre-spike disproved the story's own
  sketched mechanism (owner decision taken live, AskUserQuestion) — built via `RoutingDataSource`
  instead. AC2 and AC5 each surfaced a genuine bug during implementation (an infinite-loop risk in the
  retry sweep; a non-deterministic test failure from Instancio's random boolean generation), both found
  and fixed before completion, not left for review. 91 targeted tests across 11 classes, 0 failures, 0
  errors. Status: review.
- 2026-09-25: Code review completed. 7 findings applied (1 Decision — softened `retryFailedErasures`'s
  Javadoc for a low-severity dedup-guard TOCTOU overclaim, disclosed not fixed; 5 Patch — saturation-test
  assertion tightened, retry query given an explicit `requestType` filter, per-candidate loop exception
  isolation widened, `gdprErasureHikariConfig` autocommit/timezone now read from the same yaml keys as
  the primary pool instead of hardcoded, `RoutingDataSource`'s redundant manual `afterPropertiesSet()`
  call removed; 1 Defer — `TestConfig`'s extra 3-connection test pool, a pre-existing test-infra
  tradeoff, not blocking). Status: done.

## Story Completion Status

Implemented. All 6 ACs complete, 91 targeted tests green, ledger closed out. Code review completed
2026-09-25 — 7 findings (1 Decision, 5 Patch, 1 Defer), all applied and re-verified. Status: done.
