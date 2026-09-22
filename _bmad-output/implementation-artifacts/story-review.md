# Story Review — `skillars-deferred-128-gdpr-lock-scope-erase-deadline-scheduler-lock-and-claim-clock-fixes`

**Reviewer:** senior-dev audit (adversarial read against actual source, not against the story's own narrative)
**Date:** 2026-09-22
**Reviewed at:** `story/deferred-127-lock-config-scheduler-fixes@4c250cd0` (skillars-deferred-127 **is** merged — `23c912cc`, PR #215)
**Verdict:** **Changes required before dev.** The story is unusually well-cited (see §4 — nearly every
line reference checks out exactly), but three of its six fixes rest on premises that do not survive
contact with the source: AC1 silently breaks a documented transactional-outbox invariant and can
permanently orphan S3 blobs; AC6 is framed as "mechanical, no design decision needed" when four of its
five targets are deliberately-concurrent `SKIP LOCKED` workers; and AC1/AC2/AC4 all claim an existing
PARENT-branch test safety net that does not exist.

Every finding below was verified by opening the cited file. Findings I *considered and rejected* as
false positives are listed in §3 so they are not re-raised later.

---

## 1. Must fix before implementing

### H1 — AC1 breaks `BlobDeletionOutboxSupport`'s documented atomicity contract and can permanently orphan performance-report blobs

`GdprErasureService.deletePlayerDevelopmentData` collects every performance report's S3 `storage_key`
into the caller-owned `blobKeysToDelete` list (`GdprErasureService.java:267-273`) and then deletes the
`performance_reports` rows (`:274`). The rows are the **only** record of those keys. The enqueue that
schedules the actual S3 deletion happens later, back in `erase()`'s own transaction
(`:166`, `blobDeletionOutboxSupport.enqueue(blobKeysToDelete)`).

`BlobDeletionOutboxSupport`'s class Javadoc states the invariant explicitly
(`src/main/java/com/softropic/skillars/platform/filestorage/service/BlobDeletionOutboxSupport.java:20-22`):

> "Producers call `enqueue` *inside* their business transaction (so the rows commit atomically with
> the work that decided they should be deleted)"

and `enqueueOne` (`:50-62`) deliberately rethrows on any serialisation failure with the comment
*"rethrow so the producing (e.g. GDPR erasure) transaction rolls back rather than committing COMPLETED
with a PII key that was never scheduled for deletion."*

AC1 splits those two halves into **different transactions**. After AC1:

- child's `performance_reports` rows commit in the inner `REQUIRES_NEW` transaction;
- the outbox rows are written in the outer `erase()` transaction and commit only at outer commit;
- any failure between the inner commit and the outer commit — `refreshTokenRepository.markAllUsedByUserId`
  (`:154`), `gdprRequestRepository.deleteExpiredByUserId` (`:157`), the enqueue itself (`:166`), the
  `request.save` (`:172`), the event publishing (`:175-199`), or the outer commit — rolls back the outbox
  rows while leaving the `performance_reports` deletes durable.

The storage keys are then **irrecoverable**: on re-drive, `findByPlayerIdOrderByGeneratedAtDesc` returns
nothing, so the blobs are never enqueued again and are never deleted. That is not "a partially-erased
player is not less erased than intended" (AC1's own wording) — it is an Article 17 artefact that can
never be erased, created by the fix.

The story actually *names* `blobDeletionOutboxSupport.enqueue` as its example of a later failure step,
without noticing that this is the one step that owns the keys.

**Required:** move the blob enqueue for a child's report keys **inside** the same `REQUIRES_NEW` lambda
as that child's deletes (i.e. `blobDeletionOutboxSupport.enqueue(childKeys)` per child, before the
lambda returns), so the key-bearing rows and the deletion intent still commit atomically. Keep only the
GDPR-export zip keys (`:160-162`, derived from `gdpr_requests` rows that survive) on the outer path.
Update AC1's "accepted tradeoff" paragraph accordingly — the tradeoff is real but must not include
"permanently unerasable blobs."

---

### H2 — AC6 is not a mechanical fix: four of its five targets are deliberately-concurrent `SKIP LOCKED` workers

AC6 says the five annotations are a *"mechanical fix, no design decision needed."* Verified against
source, that is wrong for four of the five. Each already has per-row cross-node protection and is
designed to **scale out**, not to be serialised:

| method | existing cross-node protection | verified at |
|---|---|---|
| `UploadSessionExpiryScheduler.processExpired` | `findExpiredPendingForUpdate` = `FOR UPDATE SKIP LOCKED`, plus a `status != PENDING` re-check inside the per-row tx | `UploadSessionRepository.java:27-29`; `UploadSessionExpiryScheduler.java:35-58` |
| `ReconciliationWorkerScheduler.reconcile` | `findNonTerminalForUpdate` = `FOR UPDATE SKIP LOCKED`, plus `Video.@Version` optimistic locking | `VideoRepository.java:39-41`; `ModerationSlaMonitorService.java:79-94` documents the `@Version` reliance by name |
| `WebhookEventProcessorScheduler.processPending` | `findPendingForUpdate` = `FOR UPDATE SKIP LOCKED` **plus** an explicit `PENDING → PROCESSING` claim step | `VideoWebhookEventRepository.java:41-43`; `WebhookEventProcessorScheduler.java:52-58` |
| `ModerationSlaMonitorService.detectSlaViolations` | `findScanningOlderThan` = `FOR UPDATE SKIP LOCKED`; double-pick explicitly analysed and **accepted** | `VideoRepository.java:62-64`; `ModerationSlaMonitorService.java:79-94` |

`WebhookEventProcessorScheduler`'s own class Javadoc (`:39-42`) states the design outright:

> "Concurrent processing is prevented using `FOR UPDATE SKIP LOCKED` during event retrieval, ensuring
> that only one scheduler node processes a given event at any time."

Adding `@SchedulerLock` to a 5-second-cadence queue drainer converts N-node parallel drain into
single-node drain. On a 3-node deployment that is a ~3× reduction in webhook throughput under backlog —
a real capacity change presented as an annotation sweep.

**Second, concrete regression in the same method:** `processPending` publishes two Micrometer gauges
*before* the batch load (`WebhookEventProcessorScheduler.java:63-66`,
`videoMetrics.updateWebhookQueueDepth(...)` / `updateActiveUploadSessions(...)`). ShedLock's advisor is
configured outermost (`AsyncConfig.java:30-36`), so on a losing node the **whole method** is skipped,
including the gauge updates. N−1 nodes' `webhook_queue_depth` / `active_upload_sessions` gauges freeze
at their last value forever, and any cross-instance max/avg aggregation silently reports stale data.

**Required:** AC6 must either (a) re-scope to the subset where mutual exclusion is genuinely wanted,
with a per-method justification of the throughput cost, or (b) keep all five but state the
serialisation tradeoff explicitly and move `processPending`'s gauge writes outside the locked method
(e.g. a separate tiny unlocked `@Scheduled` metrics tick). Either way the "no design decision needed"
framing has to go, and AC7 bullet 4 must not be deleted outright as "fully fixed" on that framing.

---

### H3 — The PARENT branch has **zero** existing test coverage; all three ACs that restructure it claim a safety net that does not exist

AC1, AC2 and AC4 all restructure the same PARENT-branch loop (`GdprErasureService.java:148-151`), and
all three lean on existing tests:

- AC1 Tests: *"Existing `GdprErasureIT.erase_playerUser_…`/`erase_parentUser_…` tests must stay green."*
- AC2 Tests: *"Existing `GdprErasureIT` PARENT-branch tests with a small number of children … must stay green."*
- AC4 Tests: *"Existing PARENT-branch tests with all children present and reachable must stay green."*

Verified in `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`:

- There is **no** `erase_parentUser_*` test. (`erase_playerUser_*` tests do exist.)
- `setUp()` inserts exactly one `main.player_profiles` row (`:118-123`), linked by `user_id` to
  `SELF_PLAYER_USER_ID`. **No row anywhere in the fixture has `parent_id` set** — grep for `parent_id`
  in that file returns only `messaging.conversations`, `payment.parent_credit_ledger` and
  `video_approval_requests` usages.
- Therefore `playerProfileRepository.findByParentIdOrderByIdAsc(PARENT_ID)` returns an **empty list**
  in every existing test, and the PARENT loop body has never executed in CI.

The ledger bullet itself says so for AC4's case — *"No test covers it."* — which the story quotes
elsewhere but does not carry into its Tests sections.

**Required:** budget the missing fixture as explicit scope: a PARENT with ≥2 child `player_profiles`
rows (`parent_id = PARENT_ID`), each seeded with at least one row in a table
`deletePlayerDevelopmentData` deletes plus one `performance_reports` row carrying a `storage_key` (so
H1's path is covered too). Without it AC1/AC2/AC4 ship into an untested branch and none of the three
"must stay green" statements means anything.

---

## 2. Should fix

### M1 — AC6 + `ModerationSlaMonitorService`'s node-local failure counter: a documented guarantee silently degrades

`videoConsecutiveFailures` is a plain in-memory `HashMap<UUID,Integer>`
(`ModerationSlaMonitorService.java:49-50`), and the escalation rule is documented as
*"three consecutive failures = 15 minutes of unrecoverable failure"* (`:181-185` in the per-video catch).
That arithmetic only holds because **every** instance runs **every** cycle today, so each node
accumulates a complete local history.

Under `@SchedulerLock`, exactly one arbitrary node runs each cycle. A node observes roughly 1/N of
cycles, and a node that skips a cycle neither increments nor clears its counter — so the counters
diverge per node and the "3 consecutive" threshold is reached late, erratically, or not at all. The
forced-`FAILED` + admin-alert backstop that exists specifically so an infinite failure loop "cannot
escape notice" becomes the thing that escapes notice.

**Fix:** if `ModerationSlaMonitorService_detectSlaViolations` keeps its lock, move the counter to a
persisted column (e.g. reuse `moderationRetryCount`-style state) or state the degradation explicitly in
AC6 and in the class Javadoc.

### M2 — AC6's `AlertEvaluationService` rationale is still wrong, one level deeper than the story looked

The story corrects the ledger's "DB-touching" framing to "duplicate ops-alert emails." That correction
is also wrong. `AlertEvaluationService.computeMetricValue` is a **stub**
(`AlertEvaluationService.java:84-88`):

```java
private double computeMetricValue(String metricName) {
    // TODO: Implement generic metric computation or add domain-specific metrics here.
    // Payment-specific metrics (FAILURE_RATE, etc.) have been removed during template conversion.
    return -1.0;
}
```

`evaluate()` guards on `if (actualValue < 0) continue;` (`:58-61`), so **no rule can ever breach and no
`AlertFiredEvent` can ever be published**, on any number of instances. There is no duplicate-email risk
to close. (`getCounter` at `:96-102` is also dead — nothing calls it.)

Net effect of adding `@SchedulerLock` here: a `main.shedlock` row acquire/release every 30 s, forever,
on a method whose own Javadoc advertises *"This service is NOT @Transactional — it reads in-memory
Micrometer counters only. No DB call is made during evaluation"* (`:23-24`). The AC turns a
deliberately DB-free job into a DB-touching one to protect against an impossible outcome.

**Fix:** drop `AlertEvaluationService` from AC6 and record in the ledger closeout that it is inert
pending `computeMetricValue`'s implementation — then it can be locked as part of whatever story actually
implements metric computation.

### M3 — AC2 does not close the ledger bullet it is assigned to, but AC7 deletes that bullet anyway

Bullet 2's concern is `PessimisticLockRetryer`'s own documented precondition: the retryer **sleeps while
holding the caller's pooled JDBC connection** (`PessimisticLockRetryer.java:40-50`), so a *long-running
call site* pins a connection for a long time.

AC2's deadline is checked **between children** (Task 4: *"before each child's `deletePlayerDevelopmentData`
call, checks whether `Instant.now()` is already past `deadline`"*). It therefore bounds the *number of
children processed*, and nothing else. After AC1 each child runs in its own transaction on its own
connection, so the quantity the Javadoc warns about — one connection held for the duration of one
long-running call — is **completely unchanged** by AC2. A single child with a slow
`performance_reports` scan plus a contended `~3.2 s` backoff still holds its connection exactly as long
as before; the budget only refuses to start child *k+1*.

AC7 Task 2 nevertheless instructs: *"Bullet 2 — delete outright if AC2 ships as scoped."*

**Fix:** either extend AC2 to bound a *single* child's work (a `statement_timeout` / `lock_timeout` on
the inner transaction, mirroring `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s per-statement approach in
`RadarCompositeCalculationService`), or change AC7 to annotate bullet 2
`[DECIDED: accepted risk — skillars-deferred-128]` with this reasoning rather than deleting it.

Precision note for whoever rewrites AC2's context: the supplier actually passed to `withBoundedRetry`
is short (`findByIdForUpdate(...).orElseThrow(...)`, `GdprErasureService.java:254-255`) — the 11 deletes
run *after* it returns. The ledger's "used from the long-running call site its own Javadoc forbids"
framing is loose; the genuine concern is connection hold-time for the whole inner transaction, which is
a slightly different (and still real) claim.

### M4 — AC2's deadline converts a latency problem into a GDPR request that can never complete

AC2 Task 4 says: on deadline expiry, *"stops processing further children and throws (do not silently
truncate)"*, propagating to `GdprEventListener.onErasureRequested`'s catch (`GdprEventListener.java:36-41`),
which calls `markFailed` and `log.error`s. Task 4 explicitly declines to add alert/retry machinery,
citing the existing `[DECIDED: accepted risk — skillars-deferred-127]` on `markFailed`'s lack of alerting.

Combined, that means: a PARENT whose child count × per-child time exceeds the budget produces a `FAILED`
GDPR erasure with **no admin alert and no auto-retry**, on **every** re-drive, indefinitely. The
statutory obligation the code exists to satisfy is the thing that silently stops being met. This is
materially worse than the latency it replaces.

Note the partial mitigation and its limit: after AC1, already-processed children stay deleted, so a
re-drive makes forward progress (re-deletes are cheap no-ops). But nothing *triggers* a re-drive, and
nothing tells an operator one is needed.

**Fix:** at minimum, the deadline path must raise an `AdminAlert` (the `adminAlertRepository` is already
injected at `GdprErasureService.java:57`) rather than relying on a `log.error`. That is a small,
targeted addition, distinct from the "build AdminAlert machinery across every `markFailed` cause"
concern the `[DECIDED]` bullet declines.

### M5 — AC2's "30 s = Hikari `connection-timeout`" derivation is a category error

AC2 Task 2's lead justification: *"30 s matches HikariCP's own `connection-timeout: 30000`
(`application.yaml:134`) — no other caller of the shared pool is expected to wait longer than that for a
connection, so bounding this call site to the same ceiling keeps it from being an outlier."*

`connection-timeout` bounds how long a caller waits to **acquire** a connection. AC2's budget bounds how
long `erase()` **holds** connections (serially) and how long the HTTP request thread is occupied. These
are unrelated quantities; equating them gives the number a false provenance. The story does hedge
("verify this reasoning holds … a starting point, not a mandate"), but this is the only derivation
offered.

**Fix:** derive the budget from something it actually bounds — e.g. the caller's own latency tolerance.
Worth noting for that derivation: `erase()` runs on the **request thread** via a synchronous
`@TransactionalEventListener(AFTER_COMMIT)` (`GdprEventListener.java:21-35`), so the budget is directly
added to an HTTP response time. 30 s is a long time to hold an HTTP request open; 5–10 s may be the
better ceiling.

### M6 — AC2's test guidance points at a seam that does not exist

AC2 Tests: *"Check how `RadarCompositeCalculationServiceConcurrencyIT` overrides a normally-fixed timing
constant for its own tests and follow the same seam if one already exists."*

Verified: that IT has no such seam. Its only timing constant is
`private static final long LOCK_HOLD_MILLIS = 1200`
(`RadarCompositeCalculationServiceConcurrencyIT.java:44`) — the **test's own** locker-thread hold
duration, used at `:77`. There is no `@TestPropertySource`, no `ReflectionTestUtils`, no
`@DynamicPropertySource`, no production-constant override anywhere in the file.

**Fix:** decide the seam in the story rather than deferring to a non-existent precedent. Given AC2
Task 3 already notes that deferred-127's `createOrReadBack` upsert makes a new bounded key viable, a
`ConfigBounds` key + `@TestPropertySource` is the cleanest option — but note `createOrReadBack`
hardcodes `ConfigValueType.LONG` (`ConfigService.java:250-255`), so the key must be seconds-as-long, not
a `Duration`.

### M7 — AC3's connection-pressure math is not derivable as instructed

AC3 Task 2 asks for a comment stating *"the `PessimisticLockRetryer` backoff-while-holding-a-connection
mechanism that makes '8 threads busy' translate into 'up to 8 connections parked, not doing DB work.'"*

That step does not follow. Only **two** `@Scheduled` methods in the whole codebase can reach
`PessimisticLockRetryer` at all:

- `PaymentPendingSweeper.sweepStrandedPayments` → `sweepOne` → `lockRetryer.withBoundedRetry`
  (`PaymentPendingSweeper.java:110-112, 141, 149`) — 15-minute cadence, `@SchedulerLock`ed.
- `RadarCompositeDlqProcessor.process` → `compositeCalculationService.recalculateComposite`
  (`RadarCompositeDlqProcessor.java:157, 257`) — 60 s cadence, `@SchedulerLock`ed.

Spring's `ReschedulingRunnable` means each `@Scheduled` method has at most one in-flight run per JVM, so
the true worst case is **2** scheduler threads sleeping in `withBoundedRetry`, not 8 — and both are
mutually exclusive across instances anyway.

The underlying pressure argument is still sound and worth documenting; it just needs the correct
mechanism: **8 scheduler threads can hold up to 8 pooled connections for the duration of their
transactions**, which is the real 8-of-25 claim. The retryer's backoff-while-holding is a narrower,
2-thread sub-case.

**Fix:** write the comment with the correct derivation. This is an AC whose entire deliverable is a
correct derivation, so getting the number right is the whole job.

### M8 — AC3's "seven `@Async` executor pools" correction is itself imprecise

The story corrects the ledger's "six `@Async` executors" to seven, citing `ExecutorShutdown.java:77-83`
and `:103-114`. Both citations are **exact** (verified). But the seven are
*`ExecutorShutdown`-covered thread pools*, not seven `@Async` executors:

- `storageUploadExecutor` is a raw `java.util.concurrent.ThreadPoolExecutor` passed to the AWS SDK
  (`S3StorageService.java:56`, `AsyncRequestBody.fromInputStream(data, contentLength, storageUploadExecutor)`)
  — never an `@Async` qualifier target. `ExecutorShutdown.java:104-106` says as much: *"is a raw
  `ThreadPoolExecutor` rather than a `ThreadPoolTaskExecutor`, which is why an inventory built by
  grepping for `ThreadPoolTaskExecutor` missed it."*

**Fix:** say "seven application-managed thread pools (not all `@Async`)" in the new comment. Given AC3
is documentation-only and makes a point of correcting the ledger's count, the replacement wording should
be right.

### M9 — AC1 leaves the outer persistence context holding stale `PlayerProfile` entities

After AC1, the tombstone (`playerProfile.setDevelopmentDataErasedAt(...)`, `GdprErasureService.java:279-280`)
is written in the **inner** EntityManager. The outer persistence context still holds the
`PlayerProfile` instances returned by `findByParentIdOrderByIdAsc` at `:149`, with
`developmentDataErasedAt == null`.

`erase()` then calls `findByParentIdOrderByIdAsc(userId)` **again** at `:191` (for
`AccountDeletionRequestedEvent`'s `linkedPlayerIds`). Hibernate's first-level cache returns the same
stale managed instances, not the post-tombstone DB state.

Harmless **today** — only `PlayerProfile::getId` is read (`:192`) — but it is a real divergence the
current single-transaction design cannot produce, and the next person to read any other field off those
entities gets pre-erasure data with no warning.

**Fix:** AC1 Task 5's Javadoc update should state this explicitly, and the PARENT loop should either
`entityManager.detach(pp)` after each child or re-read by id where freshness matters.

### M10 — AC1 commits the *sticky* tombstone before the erasure is known to succeed

`developmentDataErasedAt` is documented as one-way — *"Never reset back to null: a `player_profiles` row
is never 'un-erased'"* (`GdprErasureService.java:277-278`) — and `RadarCompositeCalculationService`
hard-skips every recalculation once it is set (`:234-238`, `return;`).

Pre-AC1 the tombstone committed atomically with the erasure. Post-AC1 it commits early. If `erase()`
then fails (H1's window, M4's deadline throw, or the already-`[DECIDED]` `PessimisticLockingFailureException`
path), the outcome is: a **live, non-anonymised, still-logging-in player** whose radar composites
silently stop updating forever, with no reset path by design.

AC1's "accepted tradeoff" paragraph covers deleted *data* but not this — a permanent behavioural change
to an account whose erasure never happened.

**Fix:** document it in AC1 Task 5, and consider stamping the tombstone from the outer transaction (it
is the one write in `deletePlayerDevelopmentData` that is not lock-scoped work) — though note that
changes the lock-ordering argument in `RadarCompositeCalculationService.java:230-233`, so it needs its
own analysis rather than a drive-by change.

### M11 — AC1 enlarges the blast radius of an already-`[DECIDED]` failure mode, without saying so

`deferred-work.md` already records (`[DECIDED: accepted risk — skillars-deferred-127]`) that
`recalculateComposite` can hold the shared `player_profiles` lock for up to
`RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS` (120 s) while the erasure's `PessimisticLockRetryer` budget is
only ~3.2 s — so a contended erasure now fails fast, routing to `markFailed` with no alert.

AC4 catches only `ResourceNotFoundException`. A `PessimisticLockingFailureException` on child B still
aborts the whole PARENT erasure — but **after AC1** it aborts with children A…B−1 already durably
deleted and the account never anonymised. Pre-AC1 that same failure rolled everything back to a clean
re-drivable state.

Not necessarily wrong to accept, but AC1 changes the character of an item the story explicitly lists as
"not reopened here," and says nothing about it.

**Fix:** one paragraph in AC1's tradeoff section connecting the two, and a decision on whether the
PARENT loop should also skip-and-continue on `PessimisticLockingFailureException` (arguably yes — a
contended child is retryable, a failed whole-request is not).

---

## 3. Considered and rejected — do **not** re-raise these

Checked against source and found sound, to prevent false positives in later review rounds:

1. **"`@SchedulerLock` will break the ~30 existing ITs that call these five methods directly."**
   Rejected. `SchedulingConfig.java:30-34` confirms tests invoke scheduled methods through the Spring
   proxy and the lock advisor applies. But AC6 specifies `lockAtLeastFor = "PT0S"` for all five, and
   with `usingDbTime()` (`ShedLockConfig.java:110`) unlock sets `lock_until = now()`, so back-to-back
   calls within one test method re-acquire cleanly. The ITs that need `releaseSchedulerLock`
   (`VideoDeletionOutboxProcessorIT:185`, `VideoSubscriptionLifecycleListenerIT:215`) are precisely the
   ones whose locks use `lockAtLeastFor = PT30S`. **See L2 for the trap this creates.**
2. **"AC1's `REQUIRES_NEW` reproduces `ModerationSlaMonitorService`'s same-thread cross-connection
   self-deadlock (`:58-77`)."** Rejected. That bug needed the *outer* transaction to hold a `FOR UPDATE`
   lock on a row the inner transaction writes. Here the lock acquisition itself moves into the inner
   transaction; `erase()`'s outer transaction never locks `player_profiles`, and the inner
   `UPDATE player_profiles` does not change FK columns so Postgres skips the parent-side RI check.
3. **"AC1 will break `erase_blockedByCompetingPlayerProfileLock_waitsThenSucceeds` /
   `erase_concurrentWithRecalculateStyleWriter_doesNotResurrectRadarData`."** Rejected — read both
   (`GdprErasureIT.java:580-640`, `:681-…`). Both assert on the lock being taken and on contention
   timing, not on the transaction it is taken in; both survive AC1 unchanged.
4. **"AC1 weakens deferred-127 AC1's erasure↔recalculation serialization."** Rejected. The tombstone
   check (`RadarCompositeCalculationService.java:234-238`) is what closes the resurrection race, and
   AC1 makes it visible *earlier*, not later. Strictly better.
5. **"AC5's `clock_timestamp()` breaks the claim-batch identity predicate."** Rejected. `clock_timestamp()`
   is evaluated per row (unlike `now()`), so batch rows get non-identical `claimed_at` — which would
   have broken the pre-deferred-124 design, but deferred-124 AC4 moved the identity predicate to
   `claimed_by` in both repositories (`VideoDeletionOutboxRepository.java:44`,
   `RadarCompositeDlqRepository.java:50/97/114/124`). Safe. **Add it to AC5 as a stated verification
   item — the story does not mention per-row evaluation at all.**
6. **"AC5's premise is false — a test already enforces `process()` being non-`@Transactional`."**
   Rejected. `SchedulerLockTransactionOrderingIT.assertNotTransactional` covers `BookingExpiryScheduler`,
   `BookingReminderScheduler`, `BandwidthResetService`, `AuthCleanupService`, `UserAdminService` — **not**
   `VideoDeletionOutboxProcessor.process` or `RadarCompositeDlqProcessor.process`. The story's "nothing
   enforces it" is correct for exactly the two methods AC5 targets.
7. **"AC6's five-method inventory is incomplete."** Rejected — enumerated every `@Scheduled` in
   `src/main/java` and cross-checked for `@SchedulerLock`. Exactly nine are unlocked: the five AC6
   targets plus `ConfigService.scheduledRefresh`, `AlertRuleCache.refresh`,
   `MessagingEmitterRegistry.sendHeartbeats`, `RateLimitingService.evictIdleBuckets` — precisely the
   four the story excludes as node-local. Inventory is exactly right.
8. **"AC1's `EntityManager` won't rebind inside the `TransactionTemplate`."** Rejected — the pattern
   (`private final EntityManager` + `@RequiredArgsConstructor`) is used by 13 services here and resolves
   to a transaction-aware shared proxy, which `JpaTransactionManager` re-binds on `REQUIRES_NEW`
   suspend/begin. **But see L4** — this is the one line where a mistake would be silent.

---

## 4. Citation audit

The story claims every citation was re-verified. Spot-checked **all** of them. Result: excellent, with
one exception.

**Exact (verified line-by-line):**

- All seven `REFERENCES main.player_profiles` FKs — `V138__baseline_schema.sql:4084, 4091, 4098, 4105,
  4231, 4497, 4504`. Exactly seven in the whole migration tree; every line number correct.
- `GdprErasureService`: `erase` `:81-203`; PLAYER branch `:139-147`; PARENT branch `:148-150`;
  `:154`, `:157`, `:166-167`, `:170-199`; helper `:253-281`; lock `:254-256`; tombstone `:279-280`;
  Javadoc `:214-252`, `:226-232`, `:234-239`. All correct.
- `GdprEventListener.onErasureRequested` catch `:36-40`, log line `:40`. Correct.
- `application.yaml`: Quartz `threadCount: 3` `:38`; `isClustered: true` `:42`; scheduler comment
  `:53-62`; `pool.size: 8` `:63-66`; `connection-timeout: 30000` `:134`; `maximum-pool-size: 25` `:137`;
  `minimum-idle: 8` `:138`. All correct — and all three corrections the story makes to the ledger's own
  citations (`:120` → `:137`, `:236-238` → `:254-255`, "six" → "seven") are genuine improvements.
- `ExecutorShutdown.java:77-83` (the seven-pool list) and `:103-114` (the self-correction). Exact.
- `PessimisticLockRetryer.java`: cost model `:38-56`; connection-hold `:40-44`; "short call sites"
  `:45-50`; non-lock-exception behaviour `:118-124`. Correct.
- `VideoDeletionOutboxRepository.java:40, 49, 103, 105` and `RadarCompositeDlqRepository.java:35, 44,
  85, 87`. All four pairs exact.
- `ReconciliationWorkerScheduler.java:174-175` (`PT10M`/`PT0S`), `:176`; `WebhookEventProcessorScheduler.java:62`;
  `ModerationSlaMonitorService.java:98`; `AlertEvaluationService.java:55`;
  `UploadSessionExpiryScheduler.java:32`. All correct, all default cadences correct.
- `BookingExpiryScheduler_expire` `lockAtMostFor = "PT15M"` at `fixedDelay = 5 MINUTES` — the "~3×
  convention" AC6 cites for `ModerationSlaMonitorService_detectSlaViolations` is real
  (`BookingExpiryScheduler.java:63-65`).
- `ConfigStartupAssertion` fail-fast on `lockAtLeastFor > lockAtMostFor` and on
  negative/unparseable durations — behaves exactly as described (loop `:236-265`, `parseLockDuration`
  `:273-300`; the cited `:254-296` is a couple of lines off but points at the right code).
- `ConfigService.updateConfig`'s `HAS_CODE_DEFAULT` upsert (`:213-234` + `createOrReadBack` `:249-267`)
  — AC2 Task 3's claim about it is accurate.
- Provenance: deferred-127's section has exactly five bullets, none `[CLOSED]`/`[DECIDED]`
  (`deferred-work.md:2898-2955`); deferred-126's section has exactly one genuinely open bullet, the
  `now()`/`clock_timestamp()` one (`:2757-2896`). Verified — the scoping narrative is honest.

**Wrong:**

- **The delete count, and the story contradicts itself three ways.** AC1 Context says *"13 bulk deletes
  plus a `performance_reports` scan (`:258-274`)"*; AC1 Task 3 says *"all 14 deletes/scan"*; AC2 says
  *"14 bulk deletes across 13 tables plus a `performance_reports` scan."* The actual body
  (`GdprErasureService.java:258-275`) contains **11 bulk deletes across 11 tables**: `player_timeline`,
  `slu`, `slu_weekly_snapshot`, `player_slu_weekly_snapshot_applied`, `slu_target`,
  `neglected_skill_flag`, `player_radar_baseline`, `player_radar_composite`, `radar_assessment`,
  `performance_report`, `homework_completion`. The cited range `:258-274` also **excludes**
  `homeworkCompletionRepository.deleteAllByPlayerId(playerId)` at `:275`, which is inside the block being
  lifted into the `TransactionTemplate` lambda. The inherited "14 across 13" comes from the ledger
  bullet verbatim and was evidently not re-counted.

---

## 5. Lower-priority notes

- **L1 — Dev Notes are stale.** *"This worktree's own checkout predates skillars-deferred-127 entirely
  (its `HEAD` is skillars-deferred-126's merge commit)"* and the "whoever implements this must first
  confirm 127 is merged" caveat are both obsolete: 127 merged as `23c912cc` (PR #215) and `HEAD` is
  `4c250cd0`. Harmless — and all `6e9b034c`-based citations survived the merge intact — but the
  paragraph should be trimmed so nobody re-runs the `git show` verification dance.

- **L2 — AC6's `lockAtLeastFor` is load-bearing, and the story elides the clause that says so.**
  AC6 quotes `ReconciliationWorkerScheduler`'s precedent comment but stops one clause early. The full
  comment (`:171-173`) is:

  > "the only goal is mutual exclusion of concurrent runs across instances; the 5-minute `fixedDelay`
  > already spaces runs out, so there is no reason to hold the lock after the method returns **(and a
  > minimum hold would make back-to-back calls in a test silently skip)**."

  AC6 Task 2 then invites the implementer to treat the values as *"starting points to verify/adjust
  during implementation, not a mandate."* Any non-`PT0S` value silently breaks the ~30 existing
  direct-call sites in `WebhookPipelineIT` (`:50, 64, 75, 89, 103, 110, 122`), `ReconciliationWorkerIT`
  (`:47, 64, 80, 92, 106`), `VideoUploadPipelineIT` (`:77, 98, 111, 124, 137, 151`) and
  `VideoUploadConfirmationIT` (`:98, 118`) — as *silently skipped runs*, i.e. confusing assertion
  failures, not lock errors. **Make `lockAtLeastFor = "PT0S"` a mandate in AC6, not a suggestion**, and
  quote the full precedent comment.

- **L3 — File List / AC7 sweep gaps.** Three files the story's changes make stale are in neither the
  File List nor AC7 Task 5's sweep list:
  - `PlayerProfileRepository.java:16-22` — the `findByParentIdOrderByIdAsc` comment justifies
    `ORDER BY id` as *"deterministic acquisition order for `GdprErasureService.erase`'s PARENT-branch
    loop, each iteration of which now takes a `player_profiles` pessimistic lock."* After AC1 the loop
    holds one lock at a time and releases it before the next, so the acquisition-order rationale needs
    rewording (the retry-budget-determinism half stays valid).
  - `SchedulingConfig.java:19-20` — *"all 11 `@SchedulerLock` jobs"*. Actual count today is **26**;
    AC6 would take it to 31. Already stale before this story, but AC7's sweep is the natural place.
  - `PessimisticLockRetryer.java` — AC7 Task 5 says its "revisit this" Javadoc *"may now be worth a
    small update"*, yet the file is absent from the File List. Pick one.

- **L4 — Add an explicit AC1 verification step for the `EntityManager` rebind.** The single line where
  the `TransactionTemplate` refactor would fail silently-then-loudly is
  `entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE)` (`GdprErasureService.java:256`):
  it only works if the injected shared-EM proxy resolves to the *inner* transaction's EntityManager, the
  same one `playerProfileRepository.findByIdForUpdate` used. It does (`JpaTransactionManager` suspends
  the outer `EntityManagerHolder` on `REQUIRES_NEW`), but AC1 should say so and the first IT run should
  confirm it rather than leaving a `detached entity passed to refresh` surprise.

- **L5 — AC4's catch should be narrowed.** Task 2 wraps the whole `deletePlayerDevelopmentData` call in
  `catch (ResourceNotFoundException e)`. Today the only thrower inside is the `orElseThrow` at `:255`,
  so the catch is precise — but it is precise by accident. Either catch at the lock-acquisition site
  specifically, or add a comment pinning the assumption so a future `ResourceNotFoundException` from
  deeper in the delete chain is not silently swallowed as "child vanished."

- **L6 — AC2 Task 5's "fast" assumption is unexamined.** It scopes the deadline clock to the PARENT loop
  because *"the account anonymisation and message/review deletion work preceding the loop (`:93-123`) is
  fast."* `messageRepository.deleteAllBySenderId`, `adminAlertRepository.resolveOpenAlertsForDeletedMessages`
  and `coachReviewRepository.deleteNonApprovedByAuthorId` are unbounded bulk deletes over
  potentially-large tables. The scoping choice is still probably right (those are not the long-running
  *lock-holding* concern), but "fast" should be dropped as the reason.

---

## 6. Summary of required story edits

| # | Severity | AC | Action |
|---|---|---|---|
| H1 | High | AC1 | Move the per-child blob enqueue inside the `REQUIRES_NEW` lambda; rewrite the "accepted tradeoff" paragraph |
| H2 | High | AC6 | Drop the "mechanical, no design decision" framing; justify or re-scope the four `SKIP LOCKED` workers; fix `processPending`'s gauge staleness |
| H3 | High | AC1/AC2/AC4 | Add the missing PARENT-with-children `GdprErasureIT` fixture as explicit scope; delete the three false "existing tests must stay green" claims |
| M1 | Med | AC6 | Address `videoConsecutiveFailures`' node-local counter degradation |
| M2 | Med | AC6 | Drop `AlertEvaluationService` — `computeMetricValue` is a `-1.0` stub, no alert can ever fire |
| M3 | Med | AC2/AC7 | AC2 does not close ledger bullet 2; extend it or annotate rather than delete |
| M4 | Med | AC2 | Deadline path must raise an `AdminAlert`, not just `log.error` |
| M5 | Med | AC2 | Re-derive the budget from latency, not from Hikari `connection-timeout` |
| M6 | Med | AC2 | Choose the test seam in-story; the cited `RadarCompositeCalculationServiceConcurrencyIT` precedent does not exist |
| M7 | Med | AC3 | Correct the mechanism: 8 connections held for transaction duration, not 8 parked in retryer backoff (only 2 jobs reach it) |
| M8 | Med | AC3 | "Seven application-managed pools (not all `@Async`)" — `storageUploadExecutor` is an SDK executor |
| M9 | Med | AC1 | Document/handle the stale outer-PC `PlayerProfile` entities re-read at `:191` |
| M10 | Med | AC1 | Document the early-committed sticky tombstone's effect on a failed erasure |
| M11 | Med | AC1/AC4 | Connect AC1 to the `[DECIDED]` `PessimisticLockingFailureException` path; decide whether to skip-and-continue on it too |
| §4 | Low | AC1/AC2 | Fix the delete count: **11 deletes across 11 tables at `:258-275`**, not 13/14 across 13 |
| L1–L6 | Low | various | Stale Dev Notes; make `PT0S` a mandate; add three files to the sweep; EM-rebind check; narrow AC4's catch; drop "fast" |

**Not disputed and ready as written:** AC5 (both repositories, both queries, all four citations exact;
premise verified as genuinely unenforced) — add only the per-row-`clock_timestamp()`-evaluation note
from §3.5. AC7's structure and the provenance/scoping section are sound; only the individual
delete-vs-annotate decisions for bullets 2 and 4 need revising per M3 and H2.
