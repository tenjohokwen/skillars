# Story: GDPR Erasure Lock-Scope & Erase-Deadline, Scheduler-Lock Coverage & Claim-Stamp Clock Fixes

**Story Key:** `skillars-deferred-128-gdpr-lock-scope-erase-deadline-scheduler-lock-and-claim-clock-fixes`
**Epic:** Deferred Work
**Priority:** High (a real FK-lock exposure widening concurrent-insert latency on 7 tables during every
GDPR erasure; a genuinely unbounded-latency call site inside `PessimisticLockRetryer`'s own documented
"short call sites only" precondition; a benign-disappearance edge case that can fail an entire multi-child
GDPR erasure; five DB/side-effect-touching `@Scheduled` methods with no cross-instance mutual exclusion) —
plus a documentation-only Hikari-pressure justification and a latent clock-coupling fix, plus the standard
ledger closeout.
**Status:** ready-for-dev
**Created:** 2026-09-22

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md`
(`## Deferred from: code review of skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes (2026-09-21)`
— surfaced by `/bmad-code-review` across four parallel layers when skillars-deferred-127 was reviewed),
per this project's established "read same-day code-review deferrals before drawing scope" convention,
plus the one still-genuinely-open bullet left over from the prior deferral
(`## Deferred from: code review of skillars-deferred-126-... (2026-09-21)`).

**Both sections were re-read in full against the actual current ledger content (not paraphrased) before
drafting this story.** Neither section carries any `[CLOSED by ...]` annotation on the items this story
closes — confirmed by direct inspection; skillars-deferred-127 only merged its own review response the
same day this story was created, so nothing in its fresh section has been touched yet.

### skillars-deferred-127's review section — five bullets, all fresh, none `[DECIDED]`/`[CLOSED]`

1. **FK-lock exposure** — `erase()` holds the `player_profiles` `FOR UPDATE` lock for its full remaining
   transaction after skillars-deferred-127 AC1's shared-lock fix, blocking FK `FOR KEY SHARE` RI checks
   on 7 child tables for concurrent inserts. **Closed by AC1 below** (owner decision, `AskUserQuestion`:
   narrow the lock scope).
2. **`PessimisticLockRetryer` is now used from exactly the long-running call site its own Javadoc
   forbids** — no cap on N children per PARENT erasure, no per-erase deadline. **Closed by AC2 below**
   (owner decision: add a per-erase deadline).
3. **Hikari pool pressure from AC3's 8 concurrent schedulers is undocumented and `8` is underived.**
   **Closed by AC3 below** (owner decision: document the derivation only, no behavior change).
4. **Five DB-touching `@Scheduled` methods still carry no `@SchedulerLock`** — flagged in the ledger as
   pre-existing/multi-instance-only, not worsened by skillars-deferred-127's diff, but a genuine
   actionable gap. **Closed by AC6 below** (mechanical fix, no design decision needed).
5. **`orElseThrow` inside the multi-child PARENT loop aborts an entire erasure if one child's profile row
   vanishes.** **Closed by AC4 below** (owner decision: skip missing children, continue siblings).

Bullets 2 and 4 above are **not the same finding** — bullet 2 is about `PessimisticLockRetryer`'s own
long-running-call-site precondition (closed by AC2, a per-erase time budget); bullet 4 is about five
*unrelated* video/notification schedulers having no `@SchedulerLock` at all (closed by AC6, a mechanical
annotation addition). Keeping them distinct matters because AC2's fix does not touch any of AC6's five
files, and AC6's fix does not touch `GdprErasureService` at all.

### skillars-deferred-126's review section — one bullet still genuinely open

- **`now()` is `transaction_timestamp()`**, so `claimPendingBatch`/`resetStaleClaimed`'s claim-stamp and
  stale-claim deadline are correct today only because each repository call happens to run in its own
  short transaction — nothing enforces it. **Re-verified directly against HEAD as still open** (no
  `[CLOSED]`/`[DECIDED]` tag on this bullet; every other bullet in that same section already carries one
  from skillars-deferred-127's own closeout). **Closed by AC5 below.**

Every other bullet in the skillars-deferred-126 section is already `[CLOSED by skillars-deferred-127 ...]`
or `[DECIDED: accepted risk — skillars-deferred-126/127]` and is **not** touched by this story — including
the two `[DECIDED: accepted risk — skillars-deferred-127]` bullets added by skillars-deferred-127's own
code review (the `radar_composite_dlq` cleanup gap, and the new `markFailed`/no-`AdminAlert` path); both
remain accepted-as-documented, not reopened here.

### Out of scope (explicitly, not re-decided here)

- Reaching further into `deferred-work.md` beyond these two same-day-adjacent sections — this story's
  7-AC bundle (6 fixes + the standard ledger closeout) is already larger than this series' usual 4–5 AC
  size; no further ledger-mining was attempted.
- Building `AdminAlert`/auto-retry machinery for `GdprErasureService.markFailed` — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- Clearing stale `radar_composite_dlq` rows for an erased player — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.

All citations below were independently re-verified against
`story/deferred-127-lock-config-scheduler-fixes@6e9b034c` (skillars-deferred-127's own final,
post-code-review commit — the branch this repo's worktree tooling had checked out elsewhere at
story-creation time; this worktree's own `HEAD` predates skillars-deferred-127 entirely, so all
verification for this story was done by reading that branch's tip directly via `git show`, not by
running anything in this worktree's own checkout). **Re-verify against whatever `HEAD` looks like at
actual implementation time** — by then skillars-deferred-127 should be merged to `master` and this
worktree's own checkout should reflect it; if any citation below has drifted, that is expected and should
simply be corrected, not treated as a sign something else is wrong.

---

## AC1 — Narrow the `player_profiles` FOR UPDATE lock scope so it no longer spans `erase()`'s full remaining transaction

### Context

`GdprErasureService.erase`
(`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:81-203`) is a
single `@Transactional(propagation = Propagation.REQUIRES_NEW)` method. Its private
`deletePlayerDevelopmentData(Long playerId, ...)` helper (`:253-281`) takes the `player_profiles`
pessimistic lock skillars-deferred-127 AC1 added (`:254-256`, `lockRetryer.withBoundedRetry(() ->
playerProfileRepository.findByIdForUpdate(playerId)...)`), runs 13 bulk deletes plus a
`performance_reports` scan (`:258-274`), and stamps the `developmentDataErasedAt` tombstone (`:279-280`)
— all inside `erase()`'s one outer transaction. Because a Postgres `SELECT ... FOR UPDATE` row lock
releases only at transaction end, that lock is held not just for the duration of
`deletePlayerDevelopmentData` itself but for everything `erase()` does **afterward**, in the same
transaction:

- `refreshTokenRepository.markAllUsedByUserId(userId)` — `:154`
- `gdprRequestRepository.deleteExpiredByUserId(userId, ...)` — `:157`
- `blobDeletionOutboxSupport.enqueue(blobKeysToDelete)` / `.requestDrainAfterCommit()` — `:166-167`
- the `request.setStatus("COMPLETED")` save and both `eventPublisher.publishEvent(...)` calls — `:170-199`

None of that later work touches `player_profiles` or its FK'd children, so holding the lock through it
serves no purpose — it only widens the exposure window. Seven tables carry a `FOREIGN KEY ... REFERENCES
main.player_profiles(id)` and are therefore subject to an RI `SELECT 1 ... FOR KEY SHARE` check on every
concurrent `INSERT` referencing that same row, which conflicts with `FOR UPDATE` and blocks for however
long the outer lock is held (re-verified at `V138__baseline_schema.sql:4084` `coach_radar_preferences`,
`:4091` `player_radar_baselines`, `:4098` `player_radar_composites`, `:4105`
`player_slu_weekly_snapshot_applied`, `:4231` `parent_player_links`, `:4497` `payment.player_subscriptions`,
`:4504` `payment.player_subscription_changes`). Those inserting callers set no `lock_timeout`, so the wait
is bounded only by Postgres defaults (effectively unbounded absent a statement/connection-level timeout).
A PARENT erasure with N children widens this to N profiles' worth of exposure, since each child's lock is
acquired inside the same single `erase()` transaction and, per `deletePlayerDevelopmentData`'s own current
Javadoc (`:234-239`), "accumulates with every other player's lock already acquired earlier in the same
`erase` call until that whole transaction commits."

**Owner decision (already taken): narrow the lock scope.** Split `erase()` so
`deletePlayerDevelopmentData`'s lock acquisition and 14 deletes/tombstone-stamp commit in their **own**
short transaction per child, releasing that child's `player_profiles` lock immediately afterward —
**before** the unrelated downstream steps (refresh-token revoke, `gdprRequest` cleanup, blob outbox
enqueue) run in `erase()`'s own transaction.

**Established precedent for the mechanism:** `ModerationSlaMonitorService`
(`src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:36,44,52-56`)
already solves the identical problem — a method that must commit a per-item write independently of its
caller's own transaction, from a `private final` collaborator on the *same* bean (so a plain
`@Transactional` on a private/self-invoked method would silently not apply, per Spring AOP's proxy
limitation) — via a `PlatformTransactionManager txManager` field plus a `TransactionTemplate
requiresNewTemplate` built once in a `@PostConstruct` method with
`setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)`. `TransactionTemplate` manages
its transaction programmatically, so it works correctly even when invoked from `this.` inside another
`@Transactional` method on the same bean — no self-invocation caveat, unlike a second `@Transactional`
annotation would have.

**Accepted, intentional side effect of this fix — document it plainly, do not let it surprise a future
reader:** with `deletePlayerDevelopmentData` now committing in its own transaction, a later failure in
`erase()` (e.g. in `blobDeletionOutboxSupport.enqueue`) can no longer roll back an already-processed
child's development-data deletion. Previously the whole `erase()` was one atomic unit; after this fix, a
GDPR erasure that fails partway through can leave one or more children's development data durably deleted
while the account-level anonymisation, refresh-token revocation, etc. did not complete — the request still
routes to `markFailed` (`GdprEventListener.onErasureRequested`'s catch, `:36-40`) and is re-drivable, but
a re-drive resumes into an already-partially-erased state rather than a clean one. This is the deliberate
cost of narrowing the lock; it is not a data-integrity bug (a partially-erased player is not "less erased"
than intended — Article 17 only requires eventual full erasure, not atomicity of it), but it must be
documented on `deletePlayerDevelopmentData`'s own Javadoc, not left implicit.

### Tasks

1. Re-verify all line citations above against current `HEAD` before implementing.
2. Add `private final PlatformTransactionManager txManager` and a `private TransactionTemplate
   requiresNewTemplate` field to `GdprErasureService`, plus a `@PostConstruct void initTemplates()`
   building it with `PROPAGATION_REQUIRES_NEW` — mirror `ModerationSlaMonitorService.java:36,44,52-56`
   exactly (same imports: `jakarta.annotation.PostConstruct`,
   `org.springframework.transaction.PlatformTransactionManager`,
   `org.springframework.transaction.TransactionDefinition`,
   `org.springframework.transaction.support.TransactionTemplate`).
3. Wrap `deletePlayerDevelopmentData`'s current body (`:254-280`) in
   `requiresNewTemplate.executeWithoutResult(status -> { ... })` so the lock acquisition, all 14
   deletes/scan, and the tombstone stamp commit as their own transaction and release the lock as soon as
   the lambda returns — before control returns to `erase()`.
4. `blobKeysToDelete` (the `List<String>` passed in from `erase()`, populated inside the
   `performance_reports` loop at `:267-273`) is a plain in-memory list, not a persisted collection —
   confirm mutating it from inside the new `REQUIRES_NEW` lambda still works exactly as before (it does;
   this is not itself a transactional concern), but re-verify rather than assume, since this is exactly
   the kind of subtle behavior a lift-into-`TransactionTemplate` refactor can silently break if the list
   reference itself were ever reassigned instead of mutated in place.
5. Update `deletePlayerDevelopmentData`'s Javadoc (`:214-252`) to document: (a) the method now commits
   independently in its own transaction, releasing its `player_profiles` lock before returning; (b) the
   accepted atomicity tradeoff above — a later `erase()` failure can no longer roll back an
   already-committed child's development-data deletion.
6. Grep-sweep `GdprErasureIT` and any other test asserting on `erase()`'s joint-rollback behavior (e.g. a
   test that forces a late failure and expects development-data deletions to have rolled back with it) —
   any such assertion is now stale by design and must be updated, not left to fail silently as a "flaky"
   test.

### Tests

- A `GdprErasureIT` test proving the lock is genuinely released early: start `erase()` for a PARENT with
  at least one child, then — timed to land after `deletePlayerDevelopmentData`'s expected commit point but
  before `erase()` itself returns/commits (e.g. via a test hook, a `CountDownLatch` injected through a
  test-only seam, or by asserting on a concurrent `FOR UPDATE`/insert against one of the 7 FK'd tables
  succeeding quickly rather than blocking) — confirm a concurrent locker/inserter is not blocked for the
  rest of `erase()`'s duration. Follow `RadarCompositeCalculationServiceConcurrencyIT`'s own
  Testcontainers-plus-real-threads pattern for asserting on lock timing; do not assert on wall-clock
  timing alone without a deterministic synchronization point.
- A `GdprErasureIT` test proving the accepted tradeoff from Task 5 is real: inject a failure into a step
  *after* `deletePlayerDevelopmentData` has already committed (e.g. via a test double/spy on
  `blobDeletionOutboxSupport`), and assert the player's development-data rows are gone and the tombstone
  is set (i.e., NOT rolled back) even though the overall `erase()` call throws and the request ends up
  `FAILED`.
- Existing `GdprErasureIT.erase_playerUser_...`/`erase_parentUser_...` tests must stay green with
  unchanged externally-observable single-threaded (no contention, no injected failure) behavior.

---

## AC2 — Bound the total lock-holding/backoff time across all children in one `erase()` run (per-erase deadline)

### Context

`PessimisticLockRetryer`'s own class Javadoc (`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:45-50`)
states: "all current call sites are short read-then-maybe-refresh operations (`findByIdForUpdate` +
optional `refresh`). If a future call site is long-running, or the pool is small relative to the
contended row's traffic, revisit this." skillars-deferred-127 AC1 made
`GdprErasureService.deletePlayerDevelopmentData` — 14 bulk deletes across 13 tables plus a
`performance_reports` scan, invoked once per child in the PARENT-branch loop
(`GdprErasureService.java:148-150`, `playerProfileRepository.findByParentIdOrderByIdAsc(userId).forEach(pp
-> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete))`) — exactly such a call site. Nothing
today bounds N (the number of children a single PARENT can have), nor the cumulative time `erase()` can
spend across all of them waiting on `PessimisticLockRetryer`'s own jittered backoff (default 8 attempts,
100ms→800ms×1.6, ~3.2s worst case per call, per that same Javadoc's "Cost model" section, `:38-56`).

**This story's own AC1 changes the exact shape of the risk, without eliminating the underlying concern:**
before AC1, all N children's locks accumulated on the *single* connection backing `erase()`'s one outer
transaction, so a PARENT with N children could hold **one** connection for up to N × ~3.2s while
simultaneously holding N row locks at once. After AC1, each child's
`deletePlayerDevelopmentData` call acquires and releases its **own** `REQUIRES_NEW` connection
sequentially, so no single connection is held across children and locks no longer accumulate — but the
loop as a whole can still take up to N × ~3.2s of **wall-clock** time with nothing capping N, which is
still squarely the "long-running call site" and "pool is small relative to the contended row's traffic"
scenario `PessimisticLockRetryer`'s Javadoc warns about, just expressed as request latency and serial
connection churn rather than simultaneous connection pinning. **Re-verify this reframing once AC1 is
implemented** — do not assume it holds without re-reading the actual post-AC1 code.

**Owner decision (already taken): add a per-erase deadline.** Bound the total lock-holding/backoff time
across all children in one `erase()` run so a PARENT with large N cannot accumulate unbounded
connection-hold/request-latency time.

### Tasks

1. Re-verify citations above against current `HEAD`, including after AC1 lands (line numbers inside
   `GdprErasureService` will shift once AC1's `TransactionTemplate` plumbing is added).
2. Add a named budget constant — e.g. `private static final Duration GDPR_ERASE_LOCK_BUDGET =
   Duration.ofSeconds(30);` — with a documented derivation. A reasonable starting point: 30s matches
   HikariCP's own `connection-timeout: 30000` (`application.yaml:134`, unchanged by this story) — no
   other caller of the shared pool is expected to wait longer than that for a connection, so bounding this
   call site to the same ceiling keeps it from being an outlier; ~30s is also roughly 9× a single child's
   ~3.2s worst case, leaving room for several genuinely-contended children before the budget trips.
   Verify this reasoning holds, or substitute a different bound with its own documented derivation, during
   implementation — this is a starting point, not a mandate.
3. Decide during implementation whether this budget is a plain `private static final Duration` or a new
   `ConfigBounds`/`platform_config` bounded key (this project's established convention for comparable
   runtime-tunable durations, e.g. `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`). Note: skillars-deferred-127
   AC2's own upsert-on-write fix to `ConfigService.updateConfig` already closes the "a new
   `HAS_CODE_DEFAULT` key is unwritable until an operator hand-inserts a row" gap a brand-new bounded key
   here would otherwise hit — so a runtime-tunable key is now a genuinely viable option, not just a
   constant, if a config-driven budget is preferred.
4. Convert the PARENT-branch `.forEach(...)` (`GdprErasureService.java:148-150`, or wherever AC1's edits
   leave it) into an explicit loop — a `Stream.forEach` cannot cleanly early-exit — that:
   - computes `deadline = Instant.now().plus(GDPR_ERASE_LOCK_BUDGET)` once, before the first child;
   - before each child's `deletePlayerDevelopmentData` call, checks whether `Instant.now()` is already
     past `deadline`; if so, stops processing further children and throws (do not silently truncate) —
     log which requestId/userId/how-many-of-N children were actually processed before stopping, so the
     eventual `[GDPR_ERASURE_FAILED]` log line (`GdprErasureService`'s existing pattern,
     `GdprEventListener.java:40`) is diagnosable.
   - lets the thrown exception propagate through `erase()` to `GdprEventListener.onErasureRequested`'s
     existing catch-all (`:36-40`), which already routes any exception to `markFailed` — no new
     alert/retry machinery is needed here (deliberately consistent with the already-`[DECIDED: accepted
     risk — skillars-deferred-127]` position on `markFailed`'s own lack of alerting).
5. Confirm the deadline clock starts at the top of the PARENT-branch loop specifically, not at the top of
   `erase()` as a whole — the account anonymisation and message/review deletion work preceding the loop
   (`:93-123`) is fast and unrelated to the long-running-call-site concern this AC targets. Re-verify this
   scoping choice makes sense once Task 4 is drafted, rather than assuming it.
6. The PLAYER branch (`:139-147`) only ever calls `deletePlayerDevelopmentData` once — it is inherently
   bounded by a single `PessimisticLockRetryer` call's own ~3.2s worst case already. Do not add deadline
   machinery to the PLAYER branch; this AC is a PARENT-branch-only concern.

### Tests

- A test proving a PARENT whose combined per-child processing time would exceed the configured budget
  stops early, marks the request `FAILED` (not `COMPLETED`, not silently truncated with no trace), and
  logs how many children were actually processed — construct this with a small budget override (a
  test-only constructor/setter, or `@TestPropertySource` if the budget is made config-driven per Task 3)
  rather than waiting out a real 30s window in a test. Check how
  `RadarCompositeCalculationServiceConcurrencyIT` overrides a normally-fixed timing constant for its own
  tests and follow the same seam if one already exists, rather than inventing a new one.
- Existing `GdprErasureIT` PARENT-branch tests with a small number of children and no contention must stay
  green and unaffected in timing.

---

## AC3 — Document the Hikari connection-pressure derivation behind `spring.task.scheduling.pool.size: 8` (no behavior change)

### Context

`application.yaml:53-66` already documents *why* `spring.task.scheduling.pool.size: 8` exists (fixing
single-thread `@Scheduled` starvation, skillars-deferred-127 AC3) but not the connection-pressure `8`
itself creates against the shared `datasource.hikari.maximum-pool-size: 25` (`application.yaml:137`,
`minimum-idle: 8` at `:138`). That pool is also shared by Tomcat request-handling threads, the clustered
Quartz job store (`org.quartz.threadPool.threadCount: 3`, `application.yaml:38`,
`org.quartz.jobStore.isClustered: true`, `:42`), and seven `@Async` executor pools — re-verified against
`infrastructure.threadpool.ExecutorShutdown`'s own authoritative inventory (`ExecutorShutdown.java:77-83`,
which explicitly corrects an earlier undercount: **"There are seven pools, not the five the story
enumerated"**, `:103-114`): `outboxDrainPool`, `sluRetryExecutor`, `storageUploadExecutor`,
`sendMailPool`, `moderationTaskExecutor`, `taskExecutor`, `reportExecutor`. (The original ledger bullet's
own "six `@Async` executors" estimate is therefore itself slightly stale — use seven, the authoritative
current count, in the new comment, not the ledger bullet's number.)

Any of the 8 scheduler-pool threads that lands on a job calling into `PessimisticLockRetryer` can hold a
pooled connection while sleeping between attempts — the retryer's own Javadoc is explicit about this
(`PessimisticLockRetryer.java:40-44`) — so worst case, 8 scheduler threads in backoff can park 8 of the 25
connections doing nothing, while `connection-timeout: 30000` (`application.yaml:134`) makes any other
caller (an HTTP request thread, a Quartz job, an `@Async` task) queue up to 30s before failing if the pool
is momentarily exhausted.

**Owner decision (already taken): document the derivation only, no behavior change.** Add an
`application.yaml` comment showing the connection-pressure math (worst-case concurrent DB-touching
scheduled jobs vs. 25 total connections, headroom for Tomcat/Quartz/`@Async`) to justify `8` as a chosen
number, not just as a fix for starvation.

### Tasks

1. Re-verify all citations above against current `HEAD` before implementing, including re-counting the
   actual number of DB-touching `@Scheduled` methods with a cadence of 60s or less (the deferred-127
   review bullet's own "ten high-cadence jobs (5s–60s)" figure should be re-derived, not assumed —
   `grep -rn "@Scheduled" src/main/java` and cross-reference each hit's `fixedDelay`/`fixedRate` against
   whether its body touches the database).
2. Extend the existing `application.yaml:53-62` comment block (or add a clearly-linked follow-on comment
   immediately after it, before `pool:\n  size: 8` at `:63-66`) with the connection-pressure math: total
   Hikari capacity (25), the scheduler pool's own worst-case share (8), what that leaves for Tomcat +
   clustered Quartz (3 threads) + the seven `@Async` executors, and the `PessimisticLockRetryer`
   backoff-while-holding-a-connection mechanism that makes "8 threads busy" translate into "up to 8
   connections parked, not doing DB work." State explicitly that `8` was chosen with this pressure in
   mind, not picked independent of it.
3. This is a comment-only change — do **not** alter `spring.task.scheduling.pool.size`'s actual value, and
   do not add a new `ConfigBounds`/`platform_config` key for it (skillars-deferred-127's own rationale for
   keeping it a static property — a thread pool cannot be resized at runtime without recreating the
   scheduler bean — is unaffected by this AC and still applies).

### Tests

- None expected — this is a documentation-only change to a YAML comment. Confirm the application still
  boots normally (no test assertion needed beyond the existing Spring context tests already covering
  `application.yaml`'s parseability).

---

## AC4 — PARENT-loop resilience: skip a vanished child, continue remaining siblings

### Context

`GdprErasureService.deletePlayerDevelopmentData`'s per-child lock acquisition
(`:254-255`, `lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
.orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")))`)
throws `ResourceNotFoundException`, unretried by `PessimisticLockRetryer` (which explicitly does not
absorb a non-lock exception, per its own Javadoc, `:118-124`), if the child's `player_profiles` row is
gone by the time the lock is actually attempted. For a PARENT with children `[A, B, C]` processed via the
loop at `:148-150`, if B's row vanishes between `findByParentIdOrderByIdAsc` returning the list and this
method's lock attempt for B (most plausibly because B was erased by a *second*, concurrent GDPR request —
a benign, already-complete outcome), that exception currently propagates out of the whole `erase()` call,
routing the entire request to `markFailed` (`GdprEventListener.java:36-40`) — needlessly, since B's
disappearance means there is nothing left to erase for B at all.

**Interaction with this story's own AC1:** before AC1, a mid-loop failure additionally rolled back A's
already-completed deletions (all inside the same one transaction); after AC1, A's deletion has already
committed independently by the time B's lock attempt runs, so AC1 alone reduces — but does not eliminate —
the blast radius of this bug. The request still ends up `FAILED` and needs a manual/automatic resubmission
purely because of a benign, already-resolved disappearance. This AC closes that residual.

**Owner decision (already taken): skip missing children, continue siblings.** Treat a vanished child
profile as already-erased and continue processing the remaining siblings, instead of failing the whole
PARENT request.

**This is deliberately narrower than the PLAYER branch's existing `orElse`-skip pattern
(`:139-147`, `ifPresentOrElse` skipping with a `log.warn` when no profile exists at all) — do not conflate
the two.** The PLAYER branch resolves its own profile via `findByUserId` *before* calling
`deletePlayerDevelopmentData` at all, so a legitimately-never-built profile is distinguished one level up,
outside the method. The PARENT branch instead trusts `findByParentIdOrderByIdAsc`'s result and only
discovers a vanished row *inside* `deletePlayerDevelopmentData`, at lock-acquisition time — this AC's fix
belongs at the PARENT-loop call site (or via a caught exception around each iteration), not inside
`deletePlayerDevelopmentData` itself, since `deletePlayerDevelopmentData`'s own Javadoc
(`:226-232`) is correct that a not-found *at that exact point* is a real anomaly for whichever caller
reaches it — the fix is in how the PARENT loop *reacts* to that anomaly (skip-and-continue), not in
suppressing the method's own not-found signal.

### Tasks

1. Re-verify citations above against current `HEAD`, including after AC1's and AC2's edits to the same
   loop (this AC's fix and AC2's deadline-check both restructure the same `.forEach` into an explicit
   loop — implement them together, in one pass over the loop body, rather than editing it twice).
2. In the PARENT-branch loop, wrap each child's `deletePlayerDevelopmentData` call in a `try { ... } catch
   (ResourceNotFoundException e) { ... }` that logs (at `warn`, not `error` — this is the expected/benign
   case, distinguished from a genuine anomaly) that the child's profile vanished before its lock could be
   acquired — treated as already-erased — and `continue`s to the next sibling, rather than letting the
   exception propagate and fail the whole request.
3. Confirm this `catch` is scoped to exactly the PARENT-branch loop, not applied to the PLAYER branch's
   single call site (which never reaches this exception today, since it resolves its own profile via
   `findByUserId` first) or turned into a blanket catch-and-continue anywhere else `deletePlayerDevelopmentData`
   is called.
4. Confirm this AC's `catch` and AC2's deadline-`throw` are not confused with each other in the loop body
   — a vanished child (`ResourceNotFoundException`) should `continue`; an exhausted deadline should stop
   the loop entirely (`break`/`throw`). Write the loop so both conditions are visibly distinct, not
   collapsed into one generic catch-and-decide branch.

### Tests

- A `GdprErasureIT` test: a PARENT with children `[A, B]`; delete B's `player_profiles` row directly
  (simulating a concurrent erasure having already completed for B) between the loop's
  `findByParentIdOrderByIdAsc` read and its lock attempt for B (e.g. via a test hook, or by pre-deleting B
  before the loop even starts if a mid-loop injection point is impractical — a pre-deleted B is a
  simpler, equally valid reproduction of "vanished before lock attempt"); assert `erase()` completes
  successfully, A's development data is fully deleted, and B's absence produced only a `warn` log, not a
  failed request.
- Existing PARENT-branch tests with all children present and reachable must stay green and unaffected.

---

## AC5 — Use `clock_timestamp()` instead of `now()` for the claim-stamp write in `claimPendingBatch`/`resetStaleClaimed`

### Context

`now()` is Postgres's `transaction_timestamp()` — constant for the whole transaction, not
statement-accurate. `VideoDeletionOutboxRepository.claimPendingBatch`
(`src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java:40,49`,
`SET status = 'CLAIMED', claimed_at = now(), claimed_by = :runId`) and its `resetStaleClaimed` sibling
(`:103,105`, `claimed_at < now() - make_interval(secs => :staleWindowSeconds)`), plus the identical pair in
`RadarCompositeDlqRepository`
(`src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java:35,44`
and `:85,87`), are correct today only because `process()` carries no `@Transactional` and ShedLock's own
accessor runs `REQUIRES_NEW` — so each `@Modifying @Transactional` repository call gets a fresh
transaction and `now()` happens to equal statement time. Nothing in the code enforces this; adding
`@Transactional` to `process()` (or calling it from any transactional caller) in some future change would
silently join both statements into one transaction, stamping `claimed_at` with the *outer* transaction's
start time and freezing `resetStaleClaimed`'s deadline for the whole run — eroding the `MAX_RUN_DURATION <
lockAtMostFor < STALE_CLAIM_WINDOW` margin (skillars-deferred-126 AC1) from both ends at once, silently
and with no compiler/test signal.

`clock_timestamp()` returns the actual current wall-clock time at the moment it is evaluated, regardless
of transaction boundaries — using it here removes the hidden transaction-boundary coupling entirely,
independent of whether some future change ever adds `@Transactional` to `process()`.

**Owner decision (already taken, mechanical — no design choice needed): use `clock_timestamp()` instead of
`now()` for the claim-stamp write(s).**

### Tasks

1. Re-verify the four exact citations above against current `HEAD` before implementing (they may shift
   slightly if AC1–AC4 above are implemented first and touch unrelated line numbers elsewhere in the
   repo — these two repository files are untouched by AC1–AC4, so drift here should only come from
   unrelated intervening changes, if any).
2. In `VideoDeletionOutboxRepository.claimPendingBatch` (`:40`), change `claimed_at = now()` to
   `claimed_at = clock_timestamp()`. In `resetStaleClaimed` (`:103`), change `claimed_at < now() -
   make_interval(...)` to `claimed_at < clock_timestamp() - make_interval(...)`.
3. Make the identical two changes in `RadarCompositeDlqRepository` (`:35` and `:85`).
4. Update each query's surrounding comment (e.g. `VideoDeletionOutboxRepository.java:27-34`,
   `RadarCompositeDlqRepository.java:22-29`, both of which currently narrate the `now()`-based "database's
   own now()" reasoning) to describe `clock_timestamp()` instead, preserving the still-accurate parts of
   each comment (the "matching `ShedLockConfig`'s `usingDbTime()` choice" framing, and the "DB time, not
   app time" rationale, both remain true — only the specific function name and its transaction-scoping
   caveat need correcting).
5. Do **not** touch any other `now()` usage in either repository (e.g. the `next_retry_at <= :now` bind
   parameter comparisons, or any other query) — this AC is scoped exactly to the claim-stamp write and its
   paired staleness read, per the ledger bullet's own scope; the `next_retry_at` app-clock eligibility gap
   is a separate, already-`[DECIDED: accepted risk — skillars-deferred-126]` item, not touched here.

### Tests

- Confirm existing `VideoDeletionOutboxProcessor`/`RadarCompositeDlqProcessor`-adjacent IT tests that
  assert on `claimed_at` values (if any) still pass — `clock_timestamp()` and `now()` produce
  observably-identical results for any test that does not span multiple statements inside one open
  transaction, so no existing assertion should need to change.
- No new test is expected to meaningfully distinguish `now()` from `clock_timestamp()` behavior in this
  project's existing test infrastructure (the difference only manifests across multiple statements inside
  one transaction, which neither repository method currently runs inside) — if a narrow test can
  demonstrate the two functions' distinct semantics directly (e.g. a raw `EXPLAIN`/query test asserting
  the SQL text itself uses `clock_timestamp()`), add it; otherwise document why not in the Dev Agent
  Record rather than inventing a strained multi-statement-transaction test.

---

## AC6 — Add `@SchedulerLock` to the five DB/side-effect-touching `@Scheduled` methods that still lack it

### Context

Five `@Scheduled` methods carry no `@SchedulerLock`, confirmed still true at `HEAD`:

- `UploadSessionExpiryScheduler.processExpired()`
  (`src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java:32`,
  `@Scheduled(fixedDelayString = "${app.video.upload.expiry-scheduler-delay-ms:30000}", ...)`, 30s
  default cadence) — DB-touching (`transactionTemplate.execute` reads/writes `UploadSession`).
- `ReconciliationWorkerScheduler.reconcile()`
  (`src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java:51`,
  `@Scheduled(fixedDelayString = "${app.video.reconciliation.fixed-delay-ms:60000}", ...)`, 60s default
  cadence) — DB-touching. Notably, the **same file**'s sibling method
  `sweepOrphanedProviderAssets()` (`:174-176`) already has
  `@SchedulerLock(name = "ReconciliationWorkerScheduler_sweepOrphanedProviderAssets", lockAtMostFor =
  "PT10M", lockAtLeastFor = "PT0S")` — `reconcile()` itself does not, an inconsistency within one class.
- `WebhookEventProcessorScheduler.processPending()`
  (`src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:62`,
  `@Scheduled(fixedDelayString = "${app.video.webhook.processor-delay-ms:5000}", ...)`, 5s default
  cadence) — DB-touching.
- `ModerationSlaMonitorService.detectSlaViolations()`
  (`src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:98`,
  `@Scheduled(fixedDelayString = "${app.video.moderation.sla-monitor-delay-ms:300000}")`, 5-minute
  default cadence) — DB-touching.
- `AlertEvaluationService.evaluate()`
  (`src/main/java/com/softropic/skillars/platform/notification/service/AlertEvaluationService.java:55`,
  `@Scheduled(fixedDelayString = "${alert.evaluation-interval-ms:30000}")`, 30s default cadence).

  **Correction found during this story's own verification, not present in the original ledger bullet:**
  `AlertEvaluationService.evaluate()` is **not actually DB-touching** — its own class Javadoc says so
  explicitly (`:23-24`, "This service is NOT `@Transactional` — it reads in-memory Micrometer counters
  only. No DB call is made during evaluation") and the method body confirms it: it reads
  `alertRuleCache.getCachedRules()` (an in-memory cache, separately refreshed by `AlertRuleCache.refresh()`
  — itself correctly one of the ledger's own "four correctly-unlocked node-local caches") and publishes a
  same-thread `@EventListener` `AlertFiredEvent`
  (`src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java:44-45`),
  whose handler logs a warning and, for an `EMAIL` channel, sends a synchronous ops email via
  `MailManager.sendEmailSync` — no repository/entity-manager call anywhere in this path. **The real
  multi-instance concern here is duplicate ops-alert emails, not a DB race**: on N running instances, each
  independently evaluates the same cached rules on its own 30s cadence and would each fire (and email) the
  same breach independently, with no cross-instance coordination. `@SchedulerLock` is still the right fix
  for that duplicate-notification risk — but describe it accurately (duplicate side effect, not "DB
  race") rather than repeating the ledger bullet's imprecise "DB-touching" framing for this one method.

The other four unlocked `@Scheduled` methods the ledger bullet itself already excludes —
`ConfigService.scheduledRefresh`, `AlertRuleCache.refresh`, `MessagingEmitterRegistry.sendHeartbeats`,
`RateLimitingService.evictIdleBuckets` — are genuinely node-local caches needing no lock, and are **not**
touched by this AC.

**Owner decision (already taken, mechanical — no design choice needed): add `@SchedulerLock` to these five
methods, following the existing pattern already used elsewhere in this codebase.** The clearest in-repo
precedent for a "pure mutual exclusion, no reason to hold the lock after the method returns" job — which
is what all five of these are, since none of them has its own documented "prevent back-to-back reruns"
requirement the way e.g. `BookingExpiryScheduler_expire`'s `lockAtLeastFor = "PT2M"` does — is
`ReconciliationWorkerScheduler.sweepOrphanedProviderAssets` itself (`:171-175`): `lockAtLeastFor = "PT0S"`,
with the class's own comment explaining why ("the only goal is mutual exclusion of concurrent runs across
instances; the ... `fixedDelay` already spaces runs out, so there is no reason to hold the lock after the
method returns").

### Tasks

1. Re-verify all five citations above against current `HEAD` before implementing.
2. Add `net.javacrumbs.shedlock.spring.annotation.SchedulerLock` to each of the five methods, naming each
   lock `"<ClassName>_<methodName>"` per the project-wide convention (confirmed across every existing
   `@SchedulerLock` usage, e.g. `ReconciliationWorkerScheduler_sweepOrphanedProviderAssets`,
   `BookingExpiryScheduler_expire`), with `lockAtLeastFor = "PT0S"` for all five (mutual-exclusion-only,
   per the precedent above) and a `lockAtMostFor` sized to comfortably exceed each job's own worst-case
   single-run duration with margin. Starting points to verify/adjust during implementation, not a
   mandate:
   - `UploadSessionExpiryScheduler_processExpired` — `lockAtMostFor = "PT5M"` (10× the 30s default
     cadence).
   - `ReconciliationWorkerScheduler_reconcile` — `lockAtMostFor = "PT10M"`, matching its own sibling
     `sweepOrphanedProviderAssets`'s value in the same class.
   - `WebhookEventProcessorScheduler_processPending` — `lockAtMostFor = "PT2M"` (the 5s cadence's own
     per-batch work is expected to be fast; 2 minutes is a generous margin, not a tight fit).
   - `ModerationSlaMonitorService_detectSlaViolations` — `lockAtMostFor = "PT15M"` (3× the 5-minute
     default cadence, mirroring `BookingExpiryScheduler_expire`'s own ~3× convention).
   - `AlertEvaluationService_evaluate` — `lockAtMostFor = "PT2M"` (evaluation is in-memory-only and
     should be fast; sized for margin, not for actual expected runtime).
3. `ConfigStartupAssertion` (`src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java`)
   fail-fasts on any `@SchedulerLock` whose `lockAtLeastFor` exceeds its `lockAtMostFor`, and on any
   attribute that resolves to a negative or unparseable duration (`:254-296`) — confirm all five new
   annotations pass this assertion at boot (a fast, cheap sanity check to run locally before considering
   this AC done, distinct from a full `mvn verify`).
4. `AlertEvaluationService` currently has no `@RequiredArgsConstructor`/Lombok — it uses an explicit
   constructor (`:41-47`). Confirm adding the `@SchedulerLock` import/annotation does not require any
   other structural change to this class (it should not — `@SchedulerLock` is a method-level annotation
   with no constructor dependency).

### Tests

- Confirm each of the five modified files still compiles and their existing scheduler-adjacent tests (if
  any) stay green.
- No new test is expected to meaningfully prove cross-instance mutual exclusion for any of these five in
  this project's existing single-instance test infrastructure — per this project's own established
  convention (see skillars-deferred-127 AC3's own Tests section reasoning for the identical situation).
  If `ConfigStartupAssertion`'s own test suite already covers "every `@SchedulerLock` has sane bounds"
  structurally, confirm the five new annotations are picked up by it automatically; otherwise document why
  not in the Dev Agent Record.

---

## AC7 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story genuinely
fixes; annotate `[DECIDED: accepted risk — skillars-deferred-128]` what it deliberately does not fix;
leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes (2026-09-21)`:
   - **Bullet 1** (FK-lock exposure) — delete outright, fully fixed by AC1.
   - **Bullet 2** (`PessimisticLockRetryer` long-running call site) — delete outright if AC2 ships as
     scoped; if implementation finds a per-erase deadline insufficient to fully close the concern for some
     reason discovered mid-implementation, annotate `[DECIDED: accepted risk — skillars-deferred-128]`
     instead and record why.
   - **Bullet 3** (Hikari pool pressure undocumented) — delete outright, fully addressed by AC3 (a
     documentation fix closes a documentation gap).
   - **Bullet 4** (five `@Scheduled` methods with no `@SchedulerLock`) — delete outright, fully fixed by
     AC6; record the `AlertEvaluationService.evaluate()` correction (not actually DB-touching; the real
     risk is duplicate ops-alert emails) inline in the closeout note so a future reader does not re-inherit
     the imprecise framing.
   - **Bullet 5** (`orElseThrow` aborts whole PARENT erasure) — delete outright, fully fixed by AC4.
3. Under `## Deferred from: code review of skillars-deferred-126-... (2026-09-21)`:
   - The `now()`/`clock_timestamp()` bullet — delete outright, fully fixed by AC5.
   - Leave every other (already `[CLOSED]`/`[DECIDED]`) bullet in that section untouched.
4. Add a `## Last audit: 2026-09-22 (skillars-deferred-128 story creation)` narrative section — update to
   `... dev-story completion` when implementation finishes — summarizing what closed and what (if
   anything) was accepted-and-documented instead, matching this series' established style.
5. Grep-sweep every file this story touches (`GdprErasureService`, `PessimisticLockRetryer` — read-only,
   not modified, but its Javadoc's "revisit this" language may now be worth a small update noting the call
   site *was* revisited and bounded — `VideoDeletionOutboxRepository`, `RadarCompositeDlqRepository`,
   `UploadSessionExpiryScheduler`, `ReconciliationWorkerScheduler`, `WebhookEventProcessorScheduler`,
   `ModerationSlaMonitorService`, `AlertEvaluationService`, `application.yaml`) against the rest of the
   ledger for any other stale reference this story's changes might affect.

---

## Dev Notes

- **Module boundaries respected:** AC1/AC2/AC4 touch `platform.admin` (`GdprErasureService`) only; AC3
  touches `application.yaml` (comment-only); AC5 touches `platform.video.repo` and
  `platform.development.repo`; AC6 touches `platform.video.service` (three classes) and
  `platform.notification.service` (one class). No cross-module leakage beyond what each AC's own fix
  requires.
- **No new Flyway migration is needed for any AC.** All seven ACs are Java/YAML/comment changes against
  existing schema and existing repository methods.
- **This worktree's own checkout predates skillars-deferred-127 entirely** (its `HEAD` is
  skillars-deferred-126's merge commit) — every citation in this story was verified by reading
  `story/deferred-127-lock-config-scheduler-fixes@6e9b034c` directly via `git show`, not by grepping this
  worktree's own working files. **Whoever implements this story must first confirm skillars-deferred-127
  is actually merged to `master`/present in their own checkout** — if it is not yet merged, every citation
  above referencing skillars-deferred-127's own code (the shared lock, `developmentDataErasedAt`, the
  `HAS_CODE_DEFAULT` upsert, the scheduler pool, `ShedLockConfig`'s surrogate-pair fix) will not resolve,
  and this story cannot be implemented until it is.
- **Testing convention (per this project's `docs/validation-strategy.md`):** targeted tests only for the
  affected classes — no `mvn verify` run locally. Frontend is untouched by this story.
- **Re-verify every file/line citation in this story against `HEAD` immediately before implementing each
  AC** — several of this story's own citations already needed correcting once during its creation (the
  "six `@Async` executors" → seven correction; the `AlertEvaluationService` "DB-touching" →
  "duplicate-notification" correction).

---

## File List (expected — reconcile against the actual final diff before AC7)

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (AC1, AC2, AC4)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1, AC2, AC4 tests)
- `src/main/resources/application.yaml` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/notification/service/AlertEvaluationService.java` (AC6)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review at dev-story completion)

---

## Change Log

- 2026-09-22: Story created via manual ledger-mining process (this project's established convention for
  `skillars-deferred-N` stories). Mined skillars-deferred-127's own fresh same-day code-review deferral
  (5 bullets, all open, none `[DECIDED]`/`[CLOSED]`) plus the one still-genuinely-open bullet remaining
  from skillars-deferred-126's own deferral section (`now()`/`clock_timestamp()`). All citations
  independently re-verified against `story/deferred-127-lock-config-scheduler-fixes@6e9b034c`
  (skillars-deferred-127's own final post-code-review commit) — this worktree's own checkout predates
  skillars-deferred-127 entirely, so verification was done via `git show` against that branch tip rather
  than this worktree's own files (see Dev Notes). Four owner decisions taken live (`AskUserQuestion`,
  reported by the requesting session, not re-asked here): (1) narrow the `player_profiles` FOR UPDATE lock
  scope so `deletePlayerDevelopmentData` commits independently, releasing the lock before erase()'s
  unrelated downstream steps; (2) add a per-erase deadline bounding total lock-holding/backoff time across
  a PARENT's children; (3) document (not change) the Hikari connection-pressure derivation behind the
  scheduler pool size 8; (4) skip a vanished child and continue remaining siblings in the PARENT erasure
  loop instead of failing the whole request. Two further mechanical fixes bundled in, no decision needed:
  (5) `clock_timestamp()` instead of `now()` for the outbox/DLQ claim-stamp writes; (6) `@SchedulerLock`
  added to five previously-unlocked `@Scheduled` methods. Two corrections found during this story's own
  verification, not present in the original ledger bullets: the Hikari-pressure bullet's "six `@Async`
  executors" estimate is actually seven, per `ExecutorShutdown.java`'s own authoritative, previously
  self-corrected count; and `AlertEvaluationService.evaluate()` — one of the "five DB-touching" scheduler
  bullet's five methods — is not actually DB-touching at all (confirmed by its own class Javadoc and
  method body), so AC6 documents its real multi-instance risk as duplicate ops-alert emails rather than
  repeating the imprecise "DB-touching" framing for that one method.
