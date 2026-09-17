# Story: Video/Subscription Outbox Double-Processing & Remaining Scheduler-Lock Gaps

**Story Key:** `skillars-deferred-120-video-subscription-outbox-double-processing-and-remaining-scheduler-lock-gaps`
**Epic:** Deferred Work
**Priority:** Medium (AC1/AC2 close genuine claim-free/claim-unscoped double-processing gaps — but `fixedDelay` cannot overlap itself, and no `docker-compose*.yml` in this repo declares `replicas` anywhere, so neither is reachable in the current single-instance deployment; both are forward-looking hardening, of the same kind as the AC3 lock-parity bundle, not live defects — see each AC's failure scenario for the multi-instance mechanism)
**Status:** done
**Created:** 2026-09-17

---

## Provenance & Scoping (read before starting)

Per the pattern `skillars-deferred-115` through `-119` established (ledger dry → fresh ad-hoc audit
applying the transaction-boundary/TOCTOU/scheduler-lock-parity lens to a not-yet-covered part of the
codebase), this story's research (2026-09-17) applied that identical lens to every remaining `@Scheduled`
class the series had not yet examined: `platform.security` (`UserAdminService`, `AuthCleanupService`) and
the rest of `platform.video`'s schedulers (`VideoDeletionOutboxProcessor`,
`VideoSubscriptionLifecycleListener`, `WebhookEventProcessorScheduler`, `ReconciliationWorkerScheduler`,
`UploadSessionExpiryScheduler`, `ModerationSlaMonitorService`), plus a re-check of `RateLimitingService`'s
own scheduled sweep. `deferred-work.md` was grepped for every class named below; zero open bullets
reference any of them under this concern (this story's findings are new, not mined from the ledger).

**Two genuine gaps found and independently re-verified against HEAD before this story was drafted:** AC1,
AC2. Both are **forward-looking hardening, not live defects today** — see each AC's failure scenario for
why (in short: `fixedDelay` cannot overlap itself within one instance, and this deployment is
single-instance). **One consistency/parity bundle found alongside them, bundled into AC3** per this
project's own established bundling convention (`skillars-deferred-117`'s "relaxed the bundling bar to
include real architectural hardening items previously left as 'recorded, not actionable'", and
`skillars-deferred-118` AC3's precedent of fixing several same-shape lock-parity gaps as one AC) — AC1/AC2
are the same kind of forward-looking fix as AC3, not a different, more urgent category.

**This closes the sweep `skillars-deferred-115` began.** Codebase-wide count at story-creation time:
**35** total `@Scheduled` methods, **21** carrying `@SchedulerLock`. Of the 14 without one, this story
fixes 5 (across 4 classes — AC1, AC2, AC3) and the remaining 9 are independently confirmed safe without
one (see "Cleared, not picked up" below) — after this story, every `@Scheduled` method in the codebase has
been examined under this lens at least once, not just the ones a story happened to touch.

**Cleared, not picked up (confirmed safe or out of scope, so the next audit does not re-investigate):**
- `ReconciliationWorkerScheduler.reconcile` — batch load via `findNonTerminalForUpdate` (`FOR UPDATE SKIP
  LOCKED`), no per-item write until `processReconciliation`, which itself transitions state through
  `VideoLifecycleService` methods that throw `VideoStateConflictException`/`VideoNotFoundException` (caught
  and skipped) if the row moved underneath the cycle — the same correct short-claim + per-item-re-check
  shape `skillars-deferred-115`'s own audit confirmed for this exact scheduler's sibling methods.
- `UploadSessionExpiryScheduler.processExpired` — batch load via `findExpiredPendingForUpdate` (`FOR
  UPDATE SKIP LOCKED`), each per-item write inside its own transaction re-fetches by id and re-checks
  `status == PENDING` before mutating (`UploadSessionExpiryScheduler.java:54-58`) — correct shape.
- `WebhookEventProcessorScheduler.processPending` — batch load via `findPendingForUpdate` (`FOR UPDATE
  SKIP LOCKED`, confirmed by the class's own Javadoc), each per-item write re-fetches by id and re-checks
  `status == PENDING` before flipping to `PROCESSING` (`WebhookEventProcessorScheduler.java:82-84`) —
  correct shape.
- `ModerationSlaMonitorService.detectSlaViolations` — `skillars-deferred-115` AC1 already restructured
  this exact method to the short-batch-load-via-`FOR UPDATE SKIP LOCKED` + per-item `REQUIRES_NEW` shape,
  and its own class Javadoc (`ModerationSlaMonitorService.java:90-94`) already documents and accepts the
  one remaining double-pick cost (a duplicate admin-alert enqueue, bounded and non-corrupting) as a
  deliberate tradeoff — re-litigating it here would contradict that story's own explicit decision.
- `ConfigService.scheduledRefresh` — idempotent read-only DB→cache refresh; already recorded in
  `deferred-work.md` as an accepted design tradeoff and confirmed still present by `skillars-deferred-119`.
- `AlertRuleCache.refresh`, `AlertEvaluationService.evaluate`, `MessagingEmitterRegistry.sendHeartbeats` —
  all three already examined and cleared by `skillars-deferred-119`'s Provenance section (in-memory-only
  state, no DB write, or per-instance-local state where a distributed lock would be actively wrong); the
  code is unchanged since that audit, re-confirmed by direct read.
- `RateLimitingService.evictIdleBuckets` — a single in-memory `ConcurrentHashMap` scan (no DB read or
  write); its own Javadoc (`RateLimitingService.java:105-109`, added by `skillars-deferred-117` AC4)
  already frames it as cheap and correctness-independent of run overlap. A second concurrent sweep just
  re-evicts the same already-idle entries; no lock needed.

**Owner decision needed before implementation:** none — all three ACs are direct fixes with an
established sibling pattern already in this codebase to mirror.

---

## Acceptance Criteria

### AC1 — `VideoSubscriptionLifecycleListener.processOutbox` has no claim mechanism at all

**The bug, traced end to end:** `processOutbox()` (`VideoSubscriptionLifecycleListener.java:58-70`) is a
`@Scheduled(fixedDelay = 60_000)` method with **no `@SchedulerLock`**. It reads up to 100 `PENDING`
entries via a **plain** `findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc` — not a `FOR UPDATE
SKIP LOCKED` query, unlike every other outbox-shaped scheduler in this codebase
(`VideoDeletionOutboxProcessor.claimPendingBatch`, `WebhookEventProcessorScheduler.findPendingForUpdate`,
`UploadSessionExpiryScheduler.findExpiredPendingForUpdate`, `OutboxPollerScheduler.pollPending`) — and
dispatches each entry straight to `self.processAndSaveEntry(entry, maxAttempts)`
(`:73-93`), which mutates the **same detached instance** read by the outer select (`entry.setAttempts`,
`entry.setStatus`) and saves it at the end. There is no status flip from `PENDING` to an in-flight state
before processing begins, and no re-fetch-and-re-check of the entry's current status inside
`processAndSaveEntry` before acting on it. `SubscriptionLifecycleOutbox` (`SubscriptionLifecycleOutbox.java`)
carries no `@Version` column either, so two writers racing the same row have no optimistic-lock backstop.
This is the only outbox-shaped scheduler in the codebase with **none** of the three protections its
siblings use (locking select, status-flip claim, or `@Version`).

**Failure scenario:** `@Scheduled(fixedDelay = 60_000)` schedules its next run 60 seconds after the
**previous execution completes**, so a single `@Scheduled` method never overlaps itself within one
instance — this only requires two *instances* of the app, once this deployment scales horizontally (no
`docker-compose*.yml` in this repo declares `replicas` anywhere, so it does not today). With two
instances, both can select the same `PENDING` entry and both call `processEntry` on it concurrently. For
Path B (the non-YEARLY branch), the doubled `blockForSubscriptionExpiry` calls the entity shape alone
would suggest do **not** actually occur: `videoRepository.findActiveReadyByOwner` (`VideoRepository.java`)
is itself `FOR UPDATE SKIP LOCKED`, and it runs inside `processAndSaveEntry`'s
`@Transactional(REQUIRES_NEW)` — `blockForSubscriptionExpiry` (`VideoLifecycleService.java:177-183`) is
plain `@Transactional` (REQUIRED), so it joins that same transaction rather than opening its own, and the
row locks are held until the whole entry finishes. A second concurrent instance processing the same
subscriber's videos skips every already-locked row and gets a short or empty page instead.

**What is genuinely unsafe is the outbox row's own bookkeeping**, and it is a status-correctness problem,
not merely wasted work: `processOutbox()` reads with a plain (non-locking) `SELECT`, so each `entry` in
`processAndSaveEntry` is a **detached** JPA instance, and `outboxRepository.save(entry)` is a `merge` that
writes back the whole stale snapshot of every mutable column (`status`, `attempts`, `lastError`,
`processedAt`). With no `@Version` to arbitrate, whichever of the two concurrent writers commits last wins
outright — so a row that one writer correctly marks `DEAD_LETTER` (max attempts reached, with its
`lastError` and the operator alert that status exists to raise) can be silently overwritten back to
`PROCESSED` by the other writer's stale, still-`PENDING`-looking snapshot, erasing a real failure with no
trace; the reverse (a `PROCESSED` entry overwritten with `DEAD_LETTER`) produces a false "manual
remediation required" alert for work that already succeeded. Path A (`resetLifecycleLockedAt`) is an
idempotent bulk `UPDATE` regardless of how many times it runs, so this status-overwrite risk is Path B's
and Path A's shared exposure, not an additional one.

**Independently re-verified against HEAD (2026-09-17, story creation):** read
`VideoSubscriptionLifecycleListener.java` in full (confirmed the plain `findTop100By...` select, the
absence of any status-flip or re-fetch in `processAndSaveEntry`), `SubscriptionLifecycleOutbox.java`
(confirmed no `@Version` column), and `VideoLifecycleService.blockForSubscriptionExpiry`
(confirmed unconditional, no transition guard — contrast with `archiveForLifecycle` and
`transitionOperationalState` elsewhere in the same class, which do check state). Confirmed via `grep`
that `deferred-work.md` has no bullet naming `VideoSubscriptionLifecycleListener`.

**Fix:** add `@SchedulerLock` to `processOutbox()`. This closes the concurrent-invocation window
entirely, the same reasoning `skillars-deferred-118` AC3 used to close `RadarCompositeDlqProcessor`'s
identical class of gap (unscoped claim + no `@Version`) — "a narrower fix scoping to a claim-token column
is out of scope... at real schema-change cost," and it applies here even more directly since this
scheduler has no locking-select claim mechanism to begin with, not merely an unscoped one. Size
`lockAtMostFor` from the real worst case: 100 entries × the cost of `processEntry`'s Path B loop (each
page up to `platform.video.lifecycle.batch_size`, default 100, of `blockForSubscriptionExpiry` calls) —
show the arithmetic in the Dev Agent Record, mirroring `skillars-deferred-118` AC3's and
`skillars-deferred-119`'s precedent of scheduler-specific, non-copy-pasted sizing. `lockAtLeastFor` must
be derived from this scheduler's own 60-second `fixedDelay` cadence — see the Dev Notes table below for
the real 60-second-cadence precedents to size against (a `PT2M`-class floor sized for the 5-minute or
daily siblings would be wrong here).

**Lock name:** `VideoSubscriptionLifecycleListener_processOutbox` (48 chars, fits `main.shedlock.name`'s
`varchar(64)`, `V138__baseline_schema.sql:1203`), following the codebase's `<ClassName>_<shortMethod>`
convention.

**Verified by:**
- New reflection-based `@SchedulerLock` presence test on `processOutbox`, matching
  `skillars-deferred-118`/`-119`'s established convention.
- **`@SchedulerLock` breaks two existing `VideoSubscriptionLifecycleListenerIT` tests that invoke
  `listener.processOutbox()` twice inside one test method**:
  `processOutbox_failureOnFirstAttempt_retriedOnSecondCall` (`:200`, `:209`) and
  `processOutbox_maxAttemptsReached_markedDeadLetter` (`:226`, `:229`). `listener` is `@Autowired`
  (`:44`), so the call goes through the Spring proxy and the second same-method-invocation is silently
  **skipped** under `lockAtLeastFor` (logged "held by another instance"), per
  `AbstractIntegrationTest.releaseSchedulerLock`'s own Javadoc (`:124-140`). Add
  `releaseSchedulerLock("VideoSubscriptionLifecycleListener_processOutbox")` between the two
  `processOutbox()` calls in each of these two tests, then re-run the class green — this is a required
  task, not merely a re-run. **Every other call site in this class, and every call site in
  `SimultaneousExpiryIT`/`YearlyExemptionRenewalIT`, invokes `processOutbox()` exactly once per test
  method and needs no change** — `DatabaseResetTestExecutionListener.backdateShedLock` runs an unscoped
  `UPDATE main.shedlock SET lock_until = now() - interval '1 minute'` (no `WHERE`) in `beforeTestMethod`,
  so every lock row, including this new one, is already reset before each test method starts; adding
  `releaseSchedulerLock` to single-invocation tests would be inert busywork, not a fix for anything.

---

### AC2 — `VideoDeletionOutboxProcessor.findClaimedBatch()` is unscoped and can double-process a row claimed by a concurrent run

**The bug, traced end to end:** `VideoDeletionOutboxProcessor.process()` (`VideoDeletionOutboxProcessor.java:42-51`)
has **no `@SchedulerLock`**. It calls `claimPendingBatch` (`VideoDeletionOutboxRepository.java:17-30`), a
correctly-scoped native `UPDATE ... WHERE id = ANY(SELECT ... FOR UPDATE SKIP LOCKED)` that flips only
`PENDING` rows to `CLAIMED` — this part is sound, and prevents two concurrent invocations from claiming
the *same* row. The bug is the next line: `findClaimedBatch()` (`VideoDeletionOutboxRepository.java:33-38`)
is `SELECT * FROM main.video_deletion_outbox WHERE status = 'CLAIMED'` — **unscoped to this invocation's
own claim**, with no `LIMIT`, no claim-token/owner column to filter on. **This is the same bug shape, the
same method name, and the same missing-`@Version` gap `skillars-deferred-118` AC3 already found and fixed
for `RadarCompositeDlqProcessor.findClaimedBatch()`** — but here it is worse: because `claimPendingBatch`
correctly prevents double-*claiming*, two concurrent invocations claim two genuinely *different* batches
of rows, and then **each invocation's own `findClaimedBatch()` returns both batches**, so each instance
ends up processing rows the other instance already claimed and is concurrently processing — not merely
re-reading a shared status field, but calling `videoProviderAdapter.deleteAsset(...)` and
`drillVideoRefRepository.decrementRefCount(...)` a second time for rows that belong to the other
invocation's claim.

**Failure scenario:** `@Scheduled(fixedDelayString = ...)` is `fixedDelay` semantics — the next run is
scheduled 60 seconds after the **previous execution completes**, so a single `@Scheduled` method never
overlaps itself within one instance. This requires two *instances* (no `docker-compose*.yml` in this repo
declares `replicas`, so not today, but once this deployment scales horizontally). Two concurrent paths, both closed by the same fix:

1. **Disjoint-batch path.** Instance A claims rows 1-50 (status → `CLAIMED`); before A finishes
   processing, instance B's tick claims rows 51-100 (a disjoint `PENDING` set — `FOR UPDATE SKIP LOCKED`
   correctly prevents overlap here). Both instances' `findClaimedBatch()` calls now return **all 100
   rows** (global `status = 'CLAIMED'`, no per-run scoping) — so A processes both its own 1-50 and B's
   51-100, and B does the same.
2. **`resetStaleClaimed` path — more likely to trigger, and does not need a full batch.**
   `resetStaleClaimed` matches on `status = 'CLAIMED' AND next_retry_at < :deadline`
   (`VideoDeletionOutboxRepository.java:43-48`), but `claimPendingBatch` never stamps `next_retry_at`
   when it claims a row (`:19-30`) — so `next_retry_at` stays the row's original *eligibility* timestamp,
   not a claim timestamp. For any row that was eligible more than 10 minutes before a concurrent tick
   runs (a real backlog, or a row that simply waited behind a full batch), that tick's
   `resetStaleClaimed` flips the row straight back to `PENDING` **while the first instance is still
   mid-flight on it**, and `claimPendingBatch` on the very next line immediately re-claims it — two
   instances now genuinely process the identical row concurrently, not merely two disjoint batches. The
   same defect exists unfixed in the already-`@SchedulerLock`-ed `RadarCompositeDlqProcessor`
   (`RadarCompositeDlqRepository.java` — the same `resetStaleClaimed`/`claimPendingBatch` pair), so it is
   a weakness in the shared claim idiom this story does not need to fix elsewhere, only note.

For either path: `videoProviderAdapter.deleteAsset(bunnyVideoId)` is called by both the rightful claimant
and the interloper — a real duplicate external API call, not merely a duplicate DB write, though
`BunnyVideoProviderAdapter.deleteAsset` treats a provider 404 as success and does not throw
(`BunnyVideoProviderAdapter.java:253-256`), which is what keeps a duplicate call from resurrecting an
already-`COMPLETED` row back to `PENDING`/`DEAD` — without that fact, the row's terminal `status` would
not be idempotent under double-processing the way this scenario needs it to be. For a row with a shared
drill asset (`DrillVideoRef.refCount > 1`), `decrementRefCount` is called twice (guarded to log a warning
rather than go negative, per its own `decremented == 0` check, so self-limiting, but the extra decrement
can still under-count a still-referenced asset's remaining uses). `status` itself is idempotent
(`"COMPLETED"` either way), but `appendDeletionLog` (`:141-147`) is an **unconditional `INSERT`** on all
three completion paths (`:74`, `:102`, `:115`), so every cross-processed row leaves a genuine **duplicate
`video_deletion_log` row** behind, not just a duplicate API call. Bounded to duplicate/wasted provider-API
calls, stale ref-count bookkeeping, and duplicate audit-log rows — not data corruption or a resurrected
row — but the claim mechanism does not do what its `FOR UPDATE SKIP LOCKED` syntax implies, identical to
`skillars-deferred-119` AC1's framing of the equivalent filestorage-module bug.

**Independently re-verified against HEAD (2026-09-17, story creation):** read
`VideoDeletionOutboxProcessor.java` and `VideoDeletionOutboxRepository.java` directly (confirmed
`findClaimedBatch`'s unscoped `WHERE status = 'CLAIMED'`, no `LIMIT`, no claim-token column anywhere in
the table), `VideoDeletionOutbox.java` (confirmed no `@Version` field, same gap class as
`RadarCompositeDlqEntry`), and cross-checked `skillars-deferred-118`'s AC3 text for the identical
`findClaimedBatch()` finding on `RadarCompositeDlqProcessor` to confirm this is a distinct instance of the
same bug class in a different class, not a duplicate of an already-fixed item.

**Fix:** add `@SchedulerLock` to `process()`. Per `skillars-deferred-118` AC3's own reasoning for
`RadarCompositeDlqProcessor` — "Adding `@SchedulerLock` closes this fully by preventing the concurrent
invocation in the first place... narrower fix scoping `findClaimedBatch` to a claim-token column is out
of scope for this AC (it fixes nothing `@SchedulerLock` doesn't already fix, at real schema-change
cost)" — apply the identical reasoning and fix here. Size `lockAtMostFor` from the real worst case: `BATCH_SIZE`
(50) × the per-item worst case (a `videoProviderAdapter.deleteAsset` call, external network I/O — treat
similarly to `skillars-deferred-119` AC1's S3-delete sizing precedent unless real Bunny.net latency data is
available). `lockAtLeastFor` from the 60-second `${platform.video.deletion.outbox_poll_delay_ms:60000}`
default cadence (note the `platform.` prefix, not `app.` — see the Dev Notes precedent table for real
60-second-cadence values) — do not copy the 5-minute or daily siblings' `PT2M`.

**Lock name:** `VideoDeletionOutboxProcessor_process`, following the codebase's `<ClassName>_<shortMethod>`
convention.

**Verified by:**
- New reflection-based `@SchedulerLock` presence test on `process()`, matching
  `skillars-deferred-118`/`-119`'s established convention.
- **`@SchedulerLock` breaks one existing `VideoDeletionOutboxProcessorIT` test that invokes
  `processor.process()` twice inside one test method**: `process_failThenSucceed_completesOnRetry`
  (`:122`, `:138`) — `processor` is `@Autowired` (`:35`), so the same silent-skip-under-`lockAtLeastFor`
  mechanism as AC1 applies. Add `releaseSchedulerLock("VideoDeletionOutboxProcessor_process")` between the
  two `process()` calls in this one test, then re-run the class green. The other four tests in this class
  (`:54`, `:73`, `:86`, `:108`) each call `process()` exactly once per method and need no change — see
  AC1's note on why (the per-test-method ShedLock reset is unscoped).

---

### AC3 — Scheduler-lock parity: `UserAdminService`, `AuthCleanupService`, and a log-level consistency fix in `SessionPackExpiryNotifier`

**Finding 1 — `UserAdminService.removeNotActivatedUsers` was missed by `skillars-deferred-118` AC3's own
lock-parity sweep, despite that same story's AC2 fixing this exact method's TOCTOU.**
`UserAdminService.java:84-116` has no `@SchedulerLock`. Its own method Javadoc
(`UserAdminService.java:80-81` at story-creation-time HEAD; corrected from an earlier `:79-82` draft
citation per code review 2026-09-17 Patch #16 — both citations are now stale regardless, since dev
removed the TODO paragraph entirely as resolved rather than leaving it to describe a still-pending
concern) has carried an explicit, unactioned TODO since before this audit series
began: *"Note: For multi-node deployments, consider adding a distributed lock (e.g., using ShedLock) to
ensure only one node executes this job."* `skillars-deferred-118` AC2 fixed this class's `deleteUserInTransaction`
re-check race in the same story that added `@SchedulerLock` to three *other* classes for exactly this
kind of consistency (AC3) — this class was in scope (its own AC2) but the lock parity pass did not reach
it. **The batch loop itself (`while (hasMore) { ... }`, `:93-114`) can spin forever, not merely run a
large-but-finite number of batches.** `findExpiredUsers` (`:122-128`) returns the same `batchSize`-capped
set on every call as long as the underlying rows are unchanged, the loop exits only on an empty result
(`:97-98`), and per-user `deleteUserInTransaction` failures are caught and swallowed to keep the sweep
going (`:104-111`) — so if every user in a batch fails to delete for a deterministic reason (an
uncovered FK, a trigger, a constraint), the identical batch is returned and reprocessed on every
iteration and the method never returns. `lockAtMostFor` is a hard lock-*release* deadline under ShedLock,
not a run cap: once it elapses, a second node acquires the lock and starts its own non-terminating spin
alongside the first, rather than the run simply stopping. A generous `lockAtMostFor` on a loop with no
progress guarantee is the worst combination available here — it delays the failure and then multiplies
it. This story adds a `MAX_BATCHES_PER_RUN`-style safety cap (mirroring
`SluSnapshotAppliedRetentionService.pruneOlderThanRetention`'s existing precedent for exactly this
purpose) as part of the fix, specifically so that `lockAtMostFor` has a real, defensible worst case to be
sized from rather than an open-ended one.

**Finding 2 — `AuthCleanupService`'s two `@Scheduled` methods have never been examined under this lens.**
`purgeExpiredRefreshTokens` (hourly) and `purgeOldLoginAttempts` (every 30 minutes),
`AuthCleanupService.java:23-37`, are both single-statement bulk deletes with no per-item loop — the same
shape `skillars-deferred-118` AC3 already reasoned about for `MessageRetentionScheduler` ("bulk deletes
are naturally idempotent — a second concurrent run just deletes zero rows") and added a lock to anyway,
**for consistency with the rest of the codebase's now-near-universal convention**, not because a live bug
exists. Apply the identical reasoning here: not a correctness bug, but a real, cheap, same-shape
consistency gap this story should close alongside Finding 1 while it is already touching the
`platform.security.service` package's scheduler-lock coverage.

**Finding 3 (smaller, bundled) — `SessionPackExpiryNotifier` logs a benign, self-correcting race at
ERROR instead of INFO, unlike its own module sibling.** `notifyExpiringPacks`'s per-pack loop
(`SessionPackExpiryNotifier.java:71-113`) catches `Exception` generically and logs at `ERROR`
(`:111-112`). `SessionPackPurchase` carries a real `@Version` column (`SessionPackPurchase.java:67-69`),
so a legitimate concurrent `extendPack`/`pausePack` write landing between this scheduler's batch load and
its own `pack.setExpiryWarnedAt(now); save(pack)` (`:91-92` — note this method, unlike
`SessionPackForfeitureScheduler.forfeitExpiredPacks`, does not re-fetch the pack fresh inside the per-item
transaction before writing) throws `OptimisticLockingFailureException` at commit. This is exactly the
same benign, self-correcting shape `PaymentPendingSweeper.sweepStrandedPayments` already special-cases in
the same module (`PaymentPendingSweeper.java:129`, `catch (OptimisticLockingFailureException e) {
log.info(...) }`, with an explicit comment on why diluting the ERROR signal with benign races is
undesirable) — `SessionPackExpiryNotifier` has no such special case, so an on-call engineer sees an
ERROR-level alert for a race that resolves itself with no data loss. The skipped warning is harmless
regardless of which concurrent write won: `extendPack` (`SessionPackPaymentService.java:172-173`) sets
`extendedAt` **and** pushes `expiresAt` out 30 days, so the pack drops out of the 14-day window on its own
merits, not because of the `extendedAt IS NULL` predicate specifically; the more likely concurrent writer
— ordinary session consumption, or `pausePack` — sets neither `extendedAt` nor `expiresAt`, so the pack is
simply re-selected and warned the next morning, still comfortably inside the 14-day window. (If the
concurrent write consumed the pack's last session, `remainingSessions > 0` correctly excludes it — there
is nothing left to warn about.)

**Note on why this fix stops at the log-level split, not a re-fetch-and-re-check like
`SessionPackForfeitureScheduler`'s:** `skillars-deferred-117` AC3 established re-fetch-and-re-check as
this module's answer to the batch-load-then-stale-write shape, and `notifyExpiringPacks` is the one
remaining method in this module that does not follow it. That is a deliberate choice here, not an
oversight: because `SessionPackPurchase` carries `@Version`, a stale `save(pack)` either matches the
current row (nothing changed, a no-op re-write of unrelated columns) or throws — there is no path where a
stale `remainingSessions`/`pausedUntil` silently overwrites a concurrent write, which is the exact failure
mode re-fetch-and-re-check exists to prevent. Re-fetching here would only suppress a diagnostic exception
this fix already downgrades to INFO; it would not close a gap, since `@Version` already closes it.

**Fix:**
1. Add a `MAX_BATCHES_PER_RUN`-style cap to `UserAdminService.removeNotActivatedUsers`'s `while (hasMore)`
   loop (mirror `SluSnapshotAppliedRetentionService.pruneOlderThanRetention`'s existing precedent for the
   same purpose), so the method has a real, bounded worst case. Then add `@SchedulerLock`, sized from its
   own cron cadence (daily) and that bounded worst case — not from an open-ended "generous" guess.
2. Add `@SchedulerLock` to `AuthCleanupService.purgeExpiredRefreshTokens` and
   `AuthCleanupService.purgeOldLoginAttempts`, each sized from its own cadence (hourly / every 30
   minutes) — both are single-statement bulk deletes, so a short `lockAtMostFor` suffices; do not
   copy-paste one value across both without checking each against its own cadence. **Keep the existing
   method-level `@Transactional` on all three now-locked methods in this Finding
   (`UserAdminService.removeNotActivatedUsers` is `@Transactional(propagation = NOT_SUPPORTED)`;
   `AuthCleanupService`'s two methods are plain `@Transactional`) — do not strip it.** This does stack
   `@SchedulerLock` with `@Transactional` on a scheduler for the first time since
   `skillars-deferred-118`/`BookingReminderScheduler`/`BandwidthResetService` moved away from that
   combination, but the ordering is safe here: `AsyncConfig`'s
   `@EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)` puts the ShedLock advisor outside the
   bare-`LOWEST_PRECEDENCE` transaction advisor, so the lock is acquired before the transaction opens and
   released after it commits. The batch-wide-rollback hazard that motivated *removing*
   `@Transactional` from `BookingExpiryScheduler`/`BookingReminderScheduler`/`BandwidthResetService`
   (one item's exception marking the whole run's transaction rollback-only) does not apply to these three
   — each is a single bulk statement or an explicitly `NOT_SUPPORTED` wrapper around a loop that already
   isolates each user's delete in its own `REQUIRES_NEW` transaction, not one transaction spanning the
   whole batch.
3. `SchedulerLockTransactionOrderingIT`'s own class Javadoc says: *"If a future scheduler legitimately
   needs to stack both annotations again, reintroduce the advisor-ordering assertion alongside it rather
   than resurrecting this comment."* This Finding does exactly that (twice — `UserAdminService` and
   `AuthCleanupService`) — add that assertion back for one of these three newly-stacked methods, per that
   Javadoc's own instruction.
4. In `SessionPackExpiryNotifier.notifyExpiringPacks`, add a `catch (OptimisticLockingFailureException e)`
   clause before the generic `catch (Exception e)`, logging at `INFO` with wording mirroring
   `PaymentPendingSweeper`'s comment (a legitimate concurrent write on this pack — most likely ordinary
   session consumption, `pausePack`, or `extendPack` — won the race; the pack will either be excluded from
   future selection or re-selected and warned on the next run, still inside the window). No change to
   `SessionPackForfeitureScheduler` — it already re-fetches fresh and re-checks before writing, so it
   cannot hit this branch the same way (its per-item re-check already treats a losing race as `null`
   inside the try, not as an exception).

**Lock names:** `UserAdminService_removeNotActivatedUsers`, `AuthCleanupService_purgeExpiredRefreshTokens`,
`AuthCleanupService_purgeOldLoginAttempts`, following the codebase's `<ClassName>_<shortMethod>` convention.

**Verified by:**
- New or extended reflection-based `@SchedulerLock` presence tests on all three now-locked methods,
  matching the established convention.
- New test(s) proving `removeNotActivatedUsers`'s new batch cap actually bounds the loop (e.g., every
  batch's deletes fail deterministically; assert the method returns after the capped number of iterations
  rather than looping forever).
- The restored `SchedulerLockTransactionOrderingIT` advisor-ordering assertion (Fix item 3) passes against
  one of `UserAdminService`/`AuthCleanupService`.
- New/extended test on `SessionPackExpiryNotifier` proving a concurrent-version-conflict scenario (stub
  `sessionPackPurchaseRepository.save` to throw `OptimisticLockingFailureException` for one pack) results
  in an INFO log and the loop continuing to the next pack, not an ERROR log. **Write this test from
  scratch** — `PaymentPendingSweeperTest`/`PaymentPendingSweeperIT` have no equivalent case to mirror
  (grepped for `OptimisticLocking`, zero hits in either file); use `PaymentPendingSweeper`'s *production*
  code shape (`:129-134`) as the pattern to mirror, not an existing test.
- `UserAdminServiceTest` is a plain `@ExtendWith(MockitoExtension.class)` test that constructs the bean
  directly (`new UserAdminService(userRepository, new SecurityProperties())`, `:45`) — no Spring proxy, so
  `@SchedulerLock` does not apply to it and it needs no change beyond covering the new batch cap above.
  `AuthCleanupService` has no existing tests at all (`grep -rl AuthCleanupService src/test` is empty) —
  add first coverage (a reflection-based lock-presence test at minimum) rather than "re-running" tests
  that do not exist.

---

### AC4 — Ledger hygiene closeout

This story's findings are new (surfaced by this story's own creation-time audit), not mined from an
existing `deferred-work.md` bullet — confirmed by grep at story-creation time: zero hits for
`VideoSubscriptionLifecycleListener`, `VideoDeletionOutboxProcessor`, `UserAdminService` (scheduler-lock
concern specifically — the class is named elsewhere in the ledger for an unrelated, already-closed
concern), `AuthCleanupService`, or `SessionPackExpiryNotifier` (this specific log-level concern — the
class is named elsewhere in the ledger for unrelated, already-closed concerns) under this scope. **No
ledger deletion needed.** Before marking this story done, re-run the same grep sweep against HEAD to
confirm no bullet was added in the interim that overlaps this story's scope:
```bash
grep -n "VideoSubscriptionLifecycleListener\|VideoDeletionOutboxProcessor\|UserAdminService\|AuthCleanupService\|SessionPackExpiryNotifier" _bmad-output/implementation-artifacts/deferred-work.md
```
**This command is not expected to return zero hits** — `UserAdminService` and `SessionPackExpiryNotifier`
are each named elsewhere in the ledger for unrelated, already-closed concerns (confirmed at story-creation
time). Read each hit; only confirm none of them describe *this* story's specific concerns (scheduler-lock
absence on `UserAdminService`/`AuthCleanupService`/`VideoSubscriptionLifecycleListener`/
`VideoDeletionOutboxProcessor`, or the log-level gap on `SessionPackExpiryNotifier`).

---

## Tasks/Subtasks

- [x] **Task 1 — AC1: `VideoSubscriptionLifecycleListener` scheduler lock**
  - [x] Add `@SchedulerLock(name = "VideoSubscriptionLifecycleListener_processOutbox", ...)` to
        `processOutbox()`, sized from real worst-case arithmetic (100 entries × Path B's per-video-page
        cost), `lockAtLeastFor` derived from the 60-second cadence (see Dev Notes precedent table) — show
        the math in the Dev Agent Record
  - [x] Add a reflection-based `@SchedulerLock` presence test
  - [x] Add `releaseSchedulerLock("VideoSubscriptionLifecycleListener_processOutbox")` between the two
        `listener.processOutbox()` calls in `processOutbox_failureOnFirstAttempt_retriedOnSecondCall` and
        in `processOutbox_maxAttemptsReached_markedDeadLetter` (`VideoSubscriptionLifecycleListenerIT`) —
        every other test in this class, and every test in `SimultaneousExpiryIT`/`YearlyExemptionRenewalIT`,
        calls `processOutbox()` once per method and needs no change
  - [x] Re-run existing tests for this class (and the two sibling IT classes) green

- [x] **Task 2 — AC2: `VideoDeletionOutboxProcessor` scheduler lock**
  - [x] Add `@SchedulerLock(name = "VideoDeletionOutboxProcessor_process", ...)` to `process()`, sized
        from real worst-case arithmetic (`BATCH_SIZE` (50) × per-item `deleteAsset` cost), `lockAtLeastFor`
        derived from the 60-second cadence
  - [x] Add a reflection-based `@SchedulerLock` presence test
  - [x] Add `releaseSchedulerLock("VideoDeletionOutboxProcessor_process")` between the two `process()`
        calls in `process_failThenSucceed_completesOnRetry` (`VideoDeletionOutboxProcessorIT`) — the
        other four tests in this class call `process()` once per method and need no change
  - [x] Re-run existing tests for this class green

- [x] **Task 3 — AC3: scheduler-lock parity + log-level consistency**
  - [x] Add a `MAX_BATCHES_PER_RUN`-style cap to `UserAdminService.removeNotActivatedUsers`'s
        `while (hasMore)` loop, mirroring `SluSnapshotAppliedRetentionService`'s precedent
  - [x] Add `@SchedulerLock(name = "UserAdminService_removeNotActivatedUsers", ...)` to
        `removeNotActivatedUsers`, sized from its daily cadence and the new bounded worst case — keep its
        existing `@Transactional(propagation = NOT_SUPPORTED)`
  - [x] Add `@SchedulerLock` to both `AuthCleanupService` methods (`AuthCleanupService_purgeExpiredRefreshTokens`,
        `AuthCleanupService_purgeOldLoginAttempts`), each sized from its own cadence — keep their existing
        method-level `@Transactional`
  - [x] Restore the advisor-ordering assertion in `SchedulerLockTransactionOrderingIT` for one of the three
        newly-stacked `@SchedulerLock` + `@Transactional` methods above, per that test class's own Javadoc
        instruction
  - [x] Add a test proving the new `MAX_BATCHES_PER_RUN` cap actually bounds `removeNotActivatedUsers`'s
        loop when deletes keep failing
  - [x] Add first test coverage for `AuthCleanupService` (none exists today) — at minimum, reflection-based
        `@SchedulerLock` presence tests for both methods
  - [x] Add a `catch (OptimisticLockingFailureException e)` clause (INFO, before the generic `catch
        (Exception e)`) to `SessionPackExpiryNotifier.notifyExpiringPacks`
  - [x] Add/extend reflection-based `@SchedulerLock` presence tests for all three now-locked
        `platform.security.service` methods
  - [x] Add a new test (from scratch — no existing `PaymentPendingSweeper` test to mirror) proving
        `SessionPackExpiryNotifier`'s optimistic-lock race logs at INFO, not ERROR, and the loop continues
  - [x] Re-run existing tests for `UserAdminService`/`SessionPackExpiryNotifier` green

- [x] **Task 4 — AC4: ledger closeout**
  - [x] Re-run the grep sweep against HEAD for the touched classes; read each hit and confirm none
        describes this story's specific concerns before marking done

- [x] **Task 5 — Final validation**
  - [x] Run every touched module's targeted test suites together; confirm zero regressions
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → review

---

### Review Findings

_Added by `/bmad-code-review` 2026-09-17 (three parallel layers: Blind Hunter, Edge Case Hunter,
Acceptance Auditor). 4 decision-needed, 17 patch, 4 deferred, 7 dismissed as noise. Every finding below
was independently re-verified against HEAD before being recorded._

**Resolved decisions (2026-09-17, now patches)**

- [x] [Review][Patch] **D1 resolved -> raise the stale-claim window to 20 minutes.** `lockAtMostFor=PT15M` currently exceeds this processor's own 10-minute stale-claim window, so lock expiry *actively triggers* the double-processing the lock was added to prevent: `claimPendingBatch` never stamps `next_retry_at`, so a takeover instance's first statement (`VideoDeletionOutboxProcessor.java:69`) flips the outgoing run's in-flight rows back to `PENDING`, re-claims them, and the unscoped `findClaimedBatch()` hands them over. **Chosen over lowering the lock to `PT10M`** (sibling alignment with `RadarCompositeDlqProcessor`) because that leaves only ~1.2x margin over the stated 8.3-min worst case for 50 external Bunny.net deletes, making lock expiry *more* frequent under provider degradation - exactly when the reclaim fires. Keep `PT15M`; extract both values as named constants with the invariant (`staleWindow > lockAtMostFor`) stated in a comment so the coupling cannot silently drift. Crash-recovery latency moves 10 -> 20 min, immaterial for a deletion outbox polled every 60s. Root cause (`resetStaleClaimed` keying on eligibility time rather than claim time) is recorded in `deferred-work.md` as a `claimed_at` follow-up, and the same shape exists in `RadarCompositeDlqProcessor.java:59`.
  **Implemented:** `VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW = Duration.ofMinutes(20)` and
  `LOCK_AT_MOST_FOR = "PT15M"` (a named `String` constant, since `@SchedulerLock` requires a
  compile-time constant expression), each Javadoc'd with the invariant and cross-referencing the
  other. `resetStaleClaimed` now calls `Instant.now().minus(STALE_CLAIM_WINDOW)`. `deferred-work.md`'s
  H2 bullet updated to note `VideoDeletionOutboxProcessor` now has a real 5-minute buffer while
  `RadarCompositeDlqProcessor` remains at knife's-edge equality.
- [x] [Review][Patch] **D2 resolved -> convert `deleteByAttemptedAtBefore` to a bulk `@Query` DELETE.** `LoginAttemptRepository:17-18` is currently a *derived* delete (`@Modifying`, no `@Query`), so Spring Data's `DeleteExecution` issues a `SELECT` of every matching row into the persistence context followed by one `DELETE` per entity - making both the `PT5M` sizing and the "naturally idempotent, a second run just deletes zero rows" rationale false. Verified safe to convert: `LoginAttempt` is not `@Audited` and has no associations, so nothing is lost by bypassing the persistence context. Mirrors the in-class sibling `RefreshTokenRepository.deleteExpiredTokens` (`:35-38`), which already is a bulk `@Query` DELETE. Update the `AuthCleanupService` Javadoc to match once converted.
  **Implemented:** `LoginAttemptRepository.deleteByAttemptedAtBefore` now carries `@Query("DELETE FROM
  LoginAttempt a WHERE a.attemptedAt < :cutoff")`; `AuthCleanupService.purgeOldLoginAttempts`'s Javadoc
  updated to describe the fix rather than assume the old (false) premise.
- [x] [Review][Patch] **D3 resolved -> add a per-run skip set of failed logins.** `MAX_BATCHES_PER_RUN` stops the spin but does not restore progress: `findExpiredUsers` (`UserAdminService.java:171-177`) always returns page 0 and per-user failures are swallowed (`:139-146`), so with >= `batchSize` undeletable users all 100 iterations return the identical batch and every deletable user behind them is permanently unreachable. Collect logins that threw and exclude them from later iterations within the same run. **Chosen over offset paging** (unreliable - the query has no `ORDER BY`, and deletions shift offsets, so rows would be skipped silently) **and over a persisted `cleanup_failed_at` column** (most robust, but needs a Flyway migration and belongs in its own story - recorded as a follow-up). Knock-on: with the skip set in place the cap only trips on a genuine large backlog, which makes the cap log truthful and settles the ERROR-vs-WARN question in the related patch below.
  **Implemented:** `removeNotActivatedUsers` now maintains a `Set<String> failedLogins`, added to on
  each per-user delete failure; `findExpiredUsers` gained an `excludeLogins` parameter and filters
  before `.limit(batchSize)`. New test
  `UserAdminServiceTest.removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt` proves
  150 deletable users behind one permanently-stuck login all get deleted in the same run.
- [x] [Review][Patch] **D4 resolved -> cap Path B's pages per entry, without burning an outbox attempt.** `VideoSubscriptionLifecycleListener.java:133-138` is `do { ... } while (!active.isEmpty())`, draining *every* ACTIVE/READY video the owner has, so the Javadoc's "100 entries x up to one full page = 10,000 calls" is a typical case, not a worst case - and this is the one scheduler where the lock is the sole protection (`SubscriptionLifecycleOutbox` has no `@Version`, no claim flag, no locking select). Add a `MAX_PAGES_PER_ENTRY` cap mirroring AC3 Finding 1's `MAX_BATCHES_PER_RUN` precedent from this same story, so `lockAtMostFor` is sized against a real bound. **Implementation constraint:** `processAndSaveEntry` increments `attempts` *before* the try block (`:100`), and `platform.video.lifecycle.outbox_max_attempts` seeds at **5** (`V139__baseline_seed_data.sql:120`) - so a capped entry must be left `PENDING` with the attempt *not* counted, or a large owner dead-letters after ~5 runs. Do not simply let the cap throw into the existing generic catch.
  **Implemented exactly per the constraint:** `processEntry` now returns `boolean` ("fully completed
  this attempt?") instead of `void`; hitting `MAX_PAGES_PER_ENTRY` (10) logs a WARN and returns
  `false` rather than throwing. `processAndSaveEntry` un-does the pre-try `attempts` increment and
  leaves `status` untouched (still `PENDING`) when `false` comes back, so a capped attempt is free.
  Worst-case arithmetic and `lockAtMostFor` (raised to `PT1H`) updated accordingly. New
  `VideoSubscriptionLifecycleListenerTest` (first plain-unit coverage for this class) proves both the
  capped-and-free-retry case and the normal-completion-consumes-an-attempt case.

**Patch**

- [x] [Review][Patch] `deleteUserInTransaction`'s `REQUIRES_NEW` never applies — the new Javadoc states a false mechanism in two places [`UserAdminService.java:112`; `SchedulerLockTransactionOrderingIT.java:48-50`]. The method is `protected` (`:195`) and reached by a plain self-call (`:137`), so the Spring proxy is bypassed twice over. The conclusion (safe to stack `@SchedulerLock` on a `NOT_SUPPORTED` method) still holds — there is simply no transaction at all — but the stated reason is wrong and is newly enshrined in a test Javadoc future readers will trust. Same applies to `findExpiredUsers`'s `@Transactional(readOnly = true)` (`:169`).
  → Resolved: `deleteUserInTransaction`'s Javadoc corrected to state the real mechanism
  (self-invocation bypasses the proxy; each repository call is still transactional via
  `SimpleJpaRepository`'s own class-level `@Transactional`). `findExpiredUsers`'s Javadoc rewritten
  for the Decision-3 fix and no longer makes the false claim either.
- [x] [Review][Patch] The cap's `status=CAPPED` ERROR fires on a healthy run that ends on exactly the 100th non-empty batch [`UserAdminService.java:151-163`]. `hasMore` is cleared only by an empty batch (`:132-133`), never by `users.size() < batchSize`, so a backlog draining at exactly 10,000 users exits with `hasMore == true` and pages an operator about a backlog that does not exist. Guard on the last batch being non-empty as well, and reconsider ERROR vs WARN — AC3 Finding 3 in this same story is explicitly about not diluting the ERROR signal with non-actionable events.
  → Resolved: after the loop, `removeNotActivatedUsers` now re-runs `findExpiredUsers` once more
  (cheap, read-only, only paid when `batches >= MAX_BATCHES_PER_RUN`) and only logs `CAPPED` if that
  confirms a real backlog remains. Kept at ERROR — once accurate, hitting the cap is a genuinely
  actionable signal. Covered by `UserAdminServiceTest`'s rewritten cap test (verifies 101 query calls:
  100 in-loop + 1 verification).
- [x] [Review][Patch] `AuthCleanupService`'s `PT10M` cites `MessageRetentionScheduler`'s "identical bulk-delete sizing" [`AuthCleanupService.java:27-30`], but that scheduler is `lockAtMostFor = "PT30M"` (`MessageRetentionScheduler.java:46`). The `lockAtLeastFor` half matches; the `lockAtMostFor` half does not. This is the same wrong-precedent error the pre-dev audit raised as H4.
  → Resolved: Javadoc corrected to state `PT10M` is sized independently for this job's own bulk
  delete, not copied from `MessageRetentionScheduler`. Value unchanged (still defensible on its own
  terms).
- [x] [Review][Patch] Pre-dev audit H2's ledger requirement unmet — the `resetStaleClaimed`/`claimPendingBatch` idiom weakness is live in the already-locked `RadarCompositeDlqProcessor.java:59` but recorded only in this story's prose; `grep "RadarCompositeDlq\|resetStaleClaimed" deferred-work.md` returns zero hits. Add the bullet.
  → Resolved: bullet added to `deferred-work.md`'s `skillars-deferred-120` code-review section,
  updated post-Decision-1 to note `VideoDeletionOutboxProcessor` now has a real buffer while
  `RadarCompositeDlqProcessor` remains at knife's-edge equality.
- [x] [Review][Patch] Pre-dev audit LOW 5 unmet — `ModerationSlaMonitorService`'s now-permanent accepted unlocked-`@Scheduled` risk exists only in two stories' narrative, with no `deferred-work.md` bullet. This story closes the sweep, so the accepted risk needs a ledger home.
  → Resolved: `[DECIDED]` bullet added to `deferred-work.md`.
- [x] [Review][Patch] All five lock-presence tests assert only `Duration.parse(...).isPositive()`, leaving every sizing value unguarded [`VideoSubscriptionLifecycleListenerIT:256`, `VideoDeletionOutboxProcessorIT:159`, `UserAdminServiceTest:139`, `AuthCleanupServiceTest:55,66`]. `PT1S` passes all of them — reducing `lockAtMostFor` to one second leaves green every test guarding the arithmetic this story spent four Javadocs justifying. Assert the actual values.
  → Resolved: all five (now six, one added for `UserAdminService`'s advisor-ordering test — see
  Patch #9 below) pin exact `Duration` values.
- [x] [Review][Patch] `removeNotActivatedUsers_deterministicDeleteFailure_stopsAtMaxBatchesPerRunCap` has no `@Timeout` [`UserAdminServiceTest:110-126`] — if `MAX_BATCHES_PER_RUN` regresses, the test spins until the CI job timeout instead of failing. Add `@Timeout`.
  → Resolved: `@Timeout(10)` added to both the rewritten cap test and the new stuck-user test.
- [x] [Review][Patch] The two new lock-presence tests sit in Testcontainers ITs though they need no Spring context [`VideoSubscriptionLifecycleListenerIT:256`, `VideoDeletionOutboxProcessorIT:159`]. Every `-118`/`-119` precedent the ACs claim to match is a plain Mockito unit test (`RadarCompositeDlqProcessorTest`, `DeletionSchedulerServiceTest`, `OutboxPollerSchedulerTest`), and the two `platform.security` presence tests in this same diff do follow that convention — the diff is internally inconsistent.
  → Resolved: moved to two new plain (no Spring, no mocks) test classes —
  `VideoSubscriptionLifecycleListenerSchedulerLockTest`, `VideoDeletionOutboxProcessorSchedulerLockTest`.
- [x] [Review][Patch] The restored advisor-ordering test exercises only the safe shape [`SchedulerLockTransactionOrderingIT:342-344`]. It covers `AuthCleanupService.purgeExpiredRefreshTokens` (plain REQUIRED `@Transactional`); the combination this story argues at length is safe — `UserAdminService`'s `@SchedulerLock` + `@Transactional(NOT_SUPPORTED)` — is untested. The "one representative is enough … advisor ordering is `AsyncConfig`-wide, not per-bean" rationale also contradicts the helper's own assertion (3), which exists specifically to catch a *per-method* annotation move.
  → Resolved: added `userAdminServiceRemoveNotActivatedUsers_shedLockAdvisorIsOutsideTheTransactionAdvisor`,
  covering the `NOT_SUPPORTED` shape directly. Class Javadoc's "one representative" claim removed.
- [x] [Review][Patch] `notifyExpiringPacks_genericFailure_stillLogsError` would pass on the unmodified code [`SessionPackExpiryNotifierTest:140-153`] — it asserts only that *some* ERROR event exists, with no message or branch identity. Its sibling's comment also claims "the healthy pack still gets its warning event" while the assertion verifies `save(healthyPack)`; `eventPublisher` is mocked and never verified.
  → Resolved: `genericFailure` test now pins the exact message/purchaseId and verifies
  `eventPublisher` was never invoked. The optimistic-lock test now verifies
  `eventPublisher.publishEvent` exactly once (proving it was the healthy pack's event, not merely
  that publishEvent fired at all).
- [x] [Review][Patch] `"Pack expiry warning sent"` INFO is emitted inside the transaction callback [`SessionPackExpiryNotifier.java:108-109`], before commit. A commit-time optimistic-lock failure then rolls back both the `expiryWarnedAt` stamp and the `BEFORE_COMMIT` outbox enqueue, leaving a log line claiming a warning was sent for a pack that received none, immediately followed by the new "changed concurrently — skipped" INFO for the same `purchaseId`. Move the success log after `transactionTemplate.execute` returns. (Narrower than it looks — Hibernate detects most of these races at `merge` time, before this line — but the commit-time window is real, and the new test stubs `save` to throw so it cannot observe the ordering.)
  → Resolved: the callback now returns a `boolean`; the success log moved outside
  `transactionTemplate.execute(...)`, logged only when the callback actually returned `true`.
- [x] [Review][Patch] The new `catch (OptimisticLockingFailureException e)` is wider than its comment describes [`SessionPackExpiryNotifier.java:112-122`] — it wraps the whole callback (coach lookup, email lookups, publish, save), so an optimistic-lock failure raised by *any* entity flushed in that transaction is silently downgraded to INFO. The exception object is also dropped from the log call, leaving no evidence if the assumption is ever wrong.
  → Resolved: comment corrected to describe the actual (whole-transaction) scope and explain why
  `pack` is the only realistic source rather than narrowing the code (narrowing would need
  restructuring the class's single-transaction delivery/dedupe guarantee, documented in its own class
  Javadoc, for a theoretical benefit). The exception is now attached to the INFO log call as evidence.
- [x] [Review][Patch] `purgeOldLoginAttempts_delegatesToRepository` asserts `deleteByAttemptedAtBefore(any())` [`AuthCleanupServiceTest:48-52`], which passes for `Instant.MAX` — i.e. for a version that truncates the table. The production comment makes the 24-hour retention a correctness property of the rate-limit window; pin it.
  → Resolved: now captures the argument and asserts it falls within a tight window around
  `now().minus(24h)`.
- [x] [Review][Patch] The restored-assertion Javadoc points at `bookingExpiryScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor` as the shape it mirrors [`SchedulerLockTransactionOrderingIT:339-341`]; that method no longer exists in the file.
  → Resolved: reference removed; Javadoc now names the actual renamed method and helper
  (`assertNotTransactional`) it moved to.
- [x] [Review][Patch] Dev Agent Record says "4 new/updated `@SchedulerLock` sites"; it is 5 methods across 4 classes (21 -> 26 sites codebase-wide).
  → Resolved: Completion Notes corrected.
- [x] [Review][Patch] Pre-dev audit LOW 3 only half-fixed — story `:260` still cites `UserAdminService.java:79-82`; the TODO occupied `:80-81`. The Files to Read entry was corrected, this one was not.
  → Resolved: citation corrected (line numbers have since shifted further post-dev; corrected to
  describe the TODO's location in the current file rather than a stale pre-dev line range).
- [x] [Review][Patch] `releaseSchedulerLock` was added at the three sites that needed it, but without the helper pattern the nearest sibling uses [`SessionPackExpiryWarningIT:66-69` routes every invocation through `notifyRun()` so a later-added second call cannot silently no-op]. Nothing is broken today — `DatabaseResetTestExecutionListener.backdateShedLock` backdates all rows per test method — but the next person to add a second `processOutbox()`/`process()` call to an existing test method gets a silently skipped run and an assertion failing for an unrelated reason.
  → Resolved (lightweight): rather than restructuring both IT classes around a shared helper (a
  larger refactor of tests this story did not otherwise touch), added an explicit inline `NOTE` comment
  at all three `releaseSchedulerLock` call sites warning that a third same-method call in that test
  method needs its own release too.

**Deferred (pre-existing, logged to `deferred-work.md`)**

- [x] [Review][Defer] `skillars-deferred-118` AC2's activated-re-check TOCTOU is narrowed, not closed [`UserAdminService.java:194-204`] — deferred, pre-existing
- [x] [Review][Defer] `findExpiredUsers` builds a `PageRequest` it never uses and loads the entire expired-user set per iteration [`UserAdminService.java:171-177`] — deferred, pre-existing
- [x] [Review][Defer] `lockAtLeastFor` values are hardcoded against tunable `fixedDelayString` cadences [`VideoDeletionOutboxProcessor.java:66`] — deferred, pre-existing
- [x] [Review][Defer] `MAX_BATCHES_PER_RUN` is hardcoded while the batch size it multiplies is configurable [`UserAdminService.java:46`] — deferred, pre-existing

**Second Review (2026-09-17, Independent)**

_Added by independent code review (2026-09-17) — verified all prior findings and implementation completeness. Zero new defects identified; all findings from the first review were correctly applied._

- ✅ **APPROVED:** All acceptance criteria correctly implemented
- ✅ **Code compiles cleanly** without test skipping
- ✅ **All targeted test suites pass** green (6 test classes, 47 total classes in touched-module regression sweep)
- ✅ **Lock names verified** within `varchar(64)` limit (longest of this story's five new/changed names,
  `VideoSubscriptionLifecycleListener_processOutbox`, is 48 chars); all follow `<ClassName>_<shortMethod>`
  convention. *(Correction, spot-checked before accepting: the original text here said "all 14 methods"
  — this story touches 5 `@SchedulerLock` methods; `grep -rn "@SchedulerLock(name" src/main/java` counts
  26 codebase-wide. Neither number is 14; the claim's substance — varchar(64) compliance and naming
  convention — holds for both counts, only the "14" figure itself was wrong.)*
- ✅ **All @SchedulerLock annotations present** with correct sizing and proper Javadoc arithmetic shown
  - AC1: `PT1H` / `PT30S` justified by 100 entries × 10 pages × 100 batch_size = 3,000s worst case
  - AC2: `PT15M` / `PT30S` justified by 50 external-I/O deletes × 10s = 500s worst case; invariant (`STALE_CLAIM_WINDOW > LOCK_AT_MOST_FOR`) cross-documented
  - AC3 Finding 1: `PT1H` / `PT1M` justified by 100 batches × 200ms = 2,000s; `failedLogins` skip-set prevents starvation
  - AC3 Finding 2: Independent sizing per method (hourly: `PT10M`, 30-min: `PT5M`); not borrowed from siblings
  - AC3 Finding 3: `OptimisticLockingFailureException` caught before generic `Exception`, logged at INFO; exception attached as evidence
- ✅ **Test coverage comprehensive**
  - All reflection-based lock-presence tests pin exact `Duration` values (not just `isPositive()`)
  - New plain-unit test coverage for `VideoSubscriptionLifecycleListener` (first coverage for this class)
  - New first-time test coverage for `AuthCleanupService` (no tests existed before)
  - `UserAdminServiceTest` covers batch cap with `@Timeout` guards + stuck-user-does-not-block scenario
  - `SessionPackExpiryNotifierTest` written from scratch with both optimistic-lock (INFO) and generic-failure (ERROR) scenarios
  - `releaseSchedulerLock` calls properly placed between multiple same-method invocations in existing ITs
- ✅ **Code review fixes from first review correctly applied**
  - Decision 1: STALE_CLAIM_WINDOW raised to 20 min with invariant documented
  - Decision 2: LoginAttemptRepository converted to bulk @Query DELETE
  - Decision 3: failedLogins skip-set prevents single stuck user from starving deletable users
  - Decision 4: MAX_PAGES_PER_ENTRY cap with boolean return so capped attempts don't consume maxAttempts
  - All 17 patches applied (Javadoc corrections, test strengthening, logging timing fix, exception evidence)
- ✅ **Transaction ordering assertions restored** in SchedulerLockTransactionOrderingIT with three-assertion pattern verified (position, order value, pointcut match)
- ✅ **Self-invocation Javadoc corrected** — UserAdminService's `deleteUserInTransaction` now correctly describes proxy bypass, not `REQUIRES_NEW` mechanism

---


## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only.
- **`lockAtLeastFor` sizing precedents — use this table, not `ReconciliationWorkerScheduler`/
  `OutboxPollerScheduler`.** Both `VideoSubscriptionLifecycleListener.processOutbox` (60s) and
  `VideoDeletionOutboxProcessor.process` (60s default) need a 60-second-cadence precedent, and neither of
  those two names one at HEAD: `ReconciliationWorkerScheduler.reconcile` (60s) itself carries **no**
  `@SchedulerLock` at all (only its sibling `sweepOrphanedProviderAssets`, a 5-*minute*-cadence method,
  is locked); `OutboxPollerScheduler.pollAndProcess` is a 5-*second*-cadence method. Use these instead,
  all genuinely 60-second-cadence:

  | Scheduler | Cadence | `lockAtMostFor` / `lockAtLeastFor` |
  |---|---|---|
  | `RadarCompositeDlqProcessor` (AC2's exact structural twin) | 60s default | `PT10M` / `PT30S` |
  | `QuotaReservationTimeoutService` | 60s | `PT10M` / `PT1M` |
  | `EmailRetryScheduler` | 60s | `PT10M` / `PT10S` |

  `UserAdminService` (daily) and `AuthCleanupService` (hourly / 30-minute) are each on their own distinct
  cadence — size each independently against its own worst case, not against this table or each other.
- **Every direct test invocation of a newly-`@SchedulerLock`-ed method needs `releaseSchedulerLock(...)`
  called between invocations, but only when the *same test method* invokes it more than once.**
  `DatabaseResetTestExecutionListener.backdateShedLock` runs an unscoped `UPDATE main.shedlock SET
  lock_until = now() - interval '1 minute'` (no `WHERE`) in `beforeTestMethod`, so every lock row —
  including the four new ones this story adds — is already reset before each test method starts.
  Single-invocation-per-method tests need no change; only the three specific tests named in AC1's and
  AC2's "Verified by" sections do.
- **AC1/AC2 — the fix is `@SchedulerLock` alone, not a claim-mechanism redesign.** Per
  `skillars-deferred-118` AC3's explicit precedent for `RadarCompositeDlqProcessor`'s identical bug
  shape: adding the lock prevents the concurrent invocation that the bug depends on, which fixes the
  problem completely without the schema/query changes a claim-token redesign would require. Do not add a
  `claimed_by`/`claim_token` column or rewrite `findClaimedBatch`/`findTop100By...` as part of this story.
- **AC3 Finding 1/2 — stacking `@SchedulerLock` with `@Transactional` is correct here, unlike the booking
  schedulers.** `SchedulerLockTransactionOrderingIT`'s own Javadoc documents this codebase's advisor
  ordering (`AsyncConfig`'s `@EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)` puts ShedLock
  outside the transaction advisor) and explicitly instructs restoring its assertion "if a future scheduler
  legitimately needs to stack both annotations again" — this story is that case. Do not strip
  `@Transactional` from `UserAdminService.removeNotActivatedUsers` or `AuthCleanupService`'s two methods
  to "match" `BookingExpiryScheduler`/`BookingReminderScheduler`/`BandwidthResetService`'s
  `TransactionTemplate`-only shape — those three needed the change because one item's exception could mark
  a whole batch's shared transaction rollback-only; none of AC3's three methods have that shape (single
  bulk statement, or a loop that already isolates each delete in its own `REQUIRES_NEW`).
- **AC3 Finding 3 — do not touch `SessionPackForfeitureScheduler`.** It already re-fetches the purchase
  fresh and re-checks eligibility inside its per-item transaction (`skillars-deferred-117` AC3), so a
  losing race there surfaces as a `null` return handled inside the try block, never as a thrown
  `OptimisticLockingFailureException` reaching a catch clause — there is nothing analogous to fix in that
  class.
- **Testing approach:** mirror `skillars-deferred-118`/`-119`'s reflection-based `@SchedulerLock`
  presence-test convention for every method this story locks. For AC3 Finding 3's log-level test, there is
  no existing `PaymentPendingSweeper` test to mirror — write it from scratch, using
  `PaymentPendingSweeper.java:129-134`'s *production* code as the pattern.

### Project Structure Notes

- AC1 touches `platform.video.service` (`VideoSubscriptionLifecycleListener`) only — no new module, no
  migration.
- AC2 touches `platform.video.service` (`VideoDeletionOutboxProcessor`) only — no new module, no
  migration.
- AC3 touches `platform.security.service` (`UserAdminService`, `AuthCleanupService`) and
  `platform.payment.service` (`SessionPackExpiryNotifier`) — no new module, no migration, no new
  dependency (ShedLock is already a project dependency, used by every other `@SchedulerLock` site).
- All three ACs are independent of each other and can be implemented/tested in any order.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java`
  (AC1 — whole file; note the `self.processAndSaveEntry` self-injection pattern already used for
  `@Transactional(REQUIRES_NEW)` proxying — the new `@SchedulerLock` goes on `processOutbox`, not on
  `processAndSaveEntry`)
- `src/main/java/com/softropic/skillars/platform/video/repo/SubscriptionLifecycleOutbox.java` (AC1 —
  confirm no `@Version` column, confirm `status`/`attempts` field names)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java:177-183` (AC1 —
  `blockForSubscriptionExpiry`'s plain `@Transactional` (REQUIRED, joins the caller's transaction) and
  unconditional-write shape)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (AC1 — confirm
  `findActiveReadyByOwner` is `FOR UPDATE SKIP LOCKED`, the basis for the "Path B does not actually
  double-block videos" reasoning in the failure scenario)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java`,
  `SimultaneousExpiryIT.java`, `YearlyExemptionRenewalIT.java` (AC1 — existing test shape to match; confirm
  which specific test methods invoke `processOutbox()` more than once before adding `releaseSchedulerLock`
  calls — see AC1's "Verified by" for exactly which two)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC2 —
  whole file)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC2 —
  whole file; `claimPendingBatch` is correctly scoped, `findClaimedBatch` is the unscoped method,
  `resetStaleClaimed` is the second concurrent path — see AC2's failure scenario)
- `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java:242-259` (AC2
  — `deleteAsset`'s 404-as-success handling, the fact that keeps a duplicate call from resurrecting an
  already-`COMPLETED` row)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java` (AC2 —
  existing test shape; only `process_failThenSucceed_completesOnRetry` needs `releaseSchedulerLock`)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC2 reference — the already-fixed sibling with the identical `findClaimedBatch()` bug shape and the
  same unfixed `resetStaleClaimed` weakness; `skillars-deferred-118`'s AC3 text is the fix precedent to
  mirror)
- `src/main/java/com/softropic/skillars/platform/security/service/UserAdminService.java` (AC3 — whole
  file; note the existing unactioned ShedLock TODO at `:80-81`, and the `while (hasMore)` loop's
  no-progress-guarantee shape at `:93-114`)
- `src/main/java/com/softropic/skillars/platform/development/service/SluSnapshotAppliedRetentionService.java`
  (AC3 — the `MAX_BATCHES_PER_RUN` pattern to mirror for `UserAdminService`'s new batch cap)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthCleanupService.java` (AC3 — whole
  file, 38 lines; no existing tests — first coverage needed)
- `src/main/java/com/softropic/skillars/infrastructure/config/AsyncConfig.java` and
  `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java` (AC3 —
  the `@EnableSchedulerLock(order = ...)` advisor ordering that makes stacking `@SchedulerLock` +
  `@Transactional` safe on `UserAdminService`/`AuthCleanupService`, and that test class's own Javadoc
  instruction to restore its assertion when a scheduler legitimately stacks both again)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java` (AC3 —
  whole file; compare against `SessionPackForfeitureScheduler`'s re-fetch-and-re-check shape to confirm
  why that sibling does not need the same fix)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:145-174`
  (AC3 — `extendPack`'s `expiresAt`/`extendedAt` write shape, the basis for the corrected harmlessness
  argument in Finding 3)
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:124-138`
  (AC3 — the `OptimisticLockingFailureException`-vs-generic-`Exception` INFO/ERROR split to mirror; there
  is no existing test of this shape to copy, only the production code)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java:67-69` (AC3 —
  confirm the `@Version` column this finding depends on)
- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java:124-140` (AC1/AC2/AC3 —
  `releaseSchedulerLock`'s Javadoc and the `DatabaseResetTestExecutionListener.backdateShedLock`
  cross-method reset this story's test guidance depends on)
- `src/test/java/com/softropic/skillars/platform/security/service/UserAdminServiceTest.java` (AC3 —
  confirm it is plain Mockito with no Spring proxy before assuming `@SchedulerLock` needs any test change
  there)
- `_bmad-output/implementation-artifacts/skillars-deferred-118-booking-expiry-transaction-race-user-cleanup-toctou-and-scheduler-lock-parity.md`
  and `_bmad-output/implementation-artifacts/skillars-deferred-119-filestorage-deletion-scheduler-toctou-and-outbox-lock-consistency.md`
  (AC1/AC2/AC3 — the worst-case `@SchedulerLock`-sizing arithmetic pattern and reflection-based
  lock-presence test convention this story's ACs all mirror)

---

## Verification Checklist

- [x] AC1: `processOutbox` carries `@SchedulerLock` (name `VideoSubscriptionLifecycleListener_processOutbox`)
      sized from real worst-case arithmetic (shown in Dev Agent Record), not a copy-pasted constant;
      reflection-based presence test added; `releaseSchedulerLock` added to the two tests that invoke
      `processOutbox()` twice per method (`processOutbox_failureOnFirstAttempt_retriedOnSecondCall`,
      `processOutbox_maxAttemptsReached_markedDeadLetter`); all `VideoSubscriptionLifecycleListenerIT`/
      `SimultaneousExpiryIT`/`YearlyExemptionRenewalIT` tests pass
- [x] AC2: `process()` carries `@SchedulerLock` (name `VideoDeletionOutboxProcessor_process`) sized the
      same way; reflection-based presence test added; `releaseSchedulerLock` added to
      `process_failThenSucceed_completesOnRetry`; all `VideoDeletionOutboxProcessorIT` tests pass
- [x] AC3: `UserAdminService.removeNotActivatedUsers`'s loop has a `MAX_BATCHES_PER_RUN`-style cap and
      carries `@SchedulerLock` sized from that bounded worst case, with its existing
      `@Transactional(propagation = NOT_SUPPORTED)` kept; both `AuthCleanupService` methods carry
      `@SchedulerLock` with their existing `@Transactional` kept; `SchedulerLockTransactionOrderingIT`'s
      advisor-ordering assertion is restored for one of these three methods;
      `SessionPackExpiryNotifier.notifyExpiringPacks` distinguishes `OptimisticLockingFailureException`
      (INFO, loop continues) from other exceptions (ERROR, loop continues); first test coverage exists for
      `AuthCleanupService`; tests cover all of the above
- [x] AC4: grep sweep re-run against HEAD for all five touched classes; every hit read and confirmed to
      describe an unrelated, already-closed concern, not this story's scope
- [x] No regressions in any touched module's existing test suites

---

## File List

**Modified:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java`
  (AC1 — `@SchedulerLock` on `processOutbox`; code review Decision 4 — `MAX_PAGES_PER_ENTRY` cap,
  `processEntry` returns `boolean`, capped attempts left `PENDING` without consuming an attempt)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
  (AC2 — `@SchedulerLock` on `process`; code review Decision 1 — `STALE_CLAIM_WINDOW` raised to 20
  minutes, `LOCK_AT_MOST_FOR` extracted as a named constant)
- `src/main/java/com/softropic/skillars/platform/security/service/UserAdminService.java`
  (AC3 Finding 1 — `MAX_BATCHES_PER_RUN` cap + `@SchedulerLock` on `removeNotActivatedUsers`; code
  review Decision 3 — per-run `failedLogins` skip-set; Patch #1 — corrected self-invocation Javadoc;
  Patch #2 — false-positive-proof `CAPPED` log)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthCleanupService.java`
  (AC3 Finding 2 — `@SchedulerLock` on both methods; code review Patch #3 — corrected precedent
  citation Javadoc)
- `src/main/java/com/softropic/skillars/platform/security/repo/LoginAttemptRepository.java`
  (code review Decision 2 — `deleteByAttemptedAtBefore` converted to a bulk `@Query` DELETE)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`
  (AC3 Finding 3 — `OptimisticLockingFailureException` INFO catch; code review Patch #11 — success
  log moved after commit; Patch #12 — catch-scope comment corrected, exception attached as evidence)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java`
  (AC1 — `releaseSchedulerLock` calls; code review Patch #8 — presence test relocated out; Patch #17
  — inline warning comments added)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC2 — `releaseSchedulerLock` call; code review Patch #8 — presence test relocated out; Patch #17 —
  inline warning comment added)
- `src/test/java/com/softropic/skillars/platform/security/service/UserAdminServiceTest.java`
  (AC3 Finding 1 — batch-cap test + presence test; code review — cap test rewritten for the skip-set's
  changed loop-termination shape, `@Timeout` added, lock values pinned, +1 new stuck-user test)
- `src/test/java/com/softropic/skillars/platform/security/service/AuthCleanupServiceTest.java`
  (code review — cutoff argument pinned via `ArgumentCaptor`, lock values pinned)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifierTest.java`
  (code review Patch #10 — both tests strengthened with exact message/`eventPublisher` verification)
- `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java`
  (AC3 Finding 2 — restored advisor-ordering assertion, new `AuthCleanupService`-targeted test; code
  review Patch #9 — added `UserAdminService` coverage; Patch #14 — dangling method reference fixed)
- `_bmad-output/implementation-artifacts/deferred-work.md`
  (code review Patch #4/#5 — two new bullets: `resetStaleClaimed`/`claimPendingBatch` stale-claim
  weakness, `ModerationSlaMonitorService`'s `[DECIDED]` no-lock status)

**Added:**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerSchedulerLockTest.java`
  (code review Patch #8 — plain reflection presence test, relocated out of the Testcontainers IT)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorSchedulerLockTest.java`
  (code review Patch #8 — plain reflection presence test, relocated out of the Testcontainers IT)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerTest.java`
  (code review Decision 4 — first plain-unit coverage for this class: page-cap/attempt-accounting)
- `src/test/java/com/softropic/skillars/platform/security/service/AuthCleanupServiceTest.java`
  (AC3 Finding 2 — first test coverage for this class)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifierTest.java`
  (AC3 Finding 3 — first test coverage for this class)

**Modified (story tracking, not code):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → in-progress → review)
- `_bmad-output/implementation-artifacts/skillars-deferred-120-video-subscription-outbox-double-processing-and-remaining-scheduler-lock-gaps.md`
  (this file — Tasks/Subtasks, Dev Agent Record, File List, Change Log, Verification Checklist,
  Status, Review Findings resolutions)

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — this story's findings are new (surfaced by
  this story's own creation-time audit), not mined from an existing ledger bullet; no bullet to cite
- `_bmad-output/implementation-artifacts/skillars-deferred-118-booking-expiry-transaction-race-user-cleanup-toctou-and-scheduler-lock-parity.md`
  — the `RadarCompositeDlqProcessor.findClaimedBatch()` fix this story's AC2 mirrors exactly, and the
  `@SchedulerLock`-sizing arithmetic / reflection-presence-test convention AC1/AC3 also mirror
- `_bmad-output/implementation-artifacts/skillars-deferred-119-filestorage-deletion-scheduler-toctou-and-outbox-lock-consistency.md`
  — the most recent story in this audit series before this one; its Provenance & Scoping section is the
  template this story's own section follows, and its "cleared, not picked up" list is extended (not
  repeated) here
- `_bmad-output/implementation-artifacts/skillars-deferred-117-legacy-table-drop-and-scheduler-lock-hardening-sweep.md`
  — `SessionPackForfeitureScheduler`'s re-fetch-and-re-check fix (AC3), the sibling pattern AC3 Finding 3
  contrasts against
- `.claude/skills/txn-and-concurrency-audit/SKILL.md` and `references/checks.md` — the audit methodology
  this story's findings were produced by
- `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java` — the
  advisor-ordering assertion AC3 restores, per that class's own Javadoc instruction
- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java` — `releaseSchedulerLock` and
  the per-test-method ShedLock reset this story's test guidance (AC1/AC2) depends on

---

## Change Log

- 2026-09-17: Story created via `/bmad-create-story`. `deferred-work.md`'s ledger re-confirmed exhausted
  of genuine one-off bugs for this scope (zero hits on grep for any touched class). Following the
  established pattern set by `skillars-deferred-115` through `-119`, dispatched a fresh ad-hoc audit
  applying the transaction-boundary/TOCTOU/scheduler-lock-parity lens to every remaining `@Scheduled`
  class the series had not yet examined — `platform.security` (`UserAdminService`, `AuthCleanupService`)
  and the rest of `platform.video`'s schedulers. Codebase-wide count at creation time: 35 total
  `@Scheduled` methods, 21 with `@SchedulerLock`. Found and independently re-verified against HEAD: AC1, a
  genuine bug in `VideoSubscriptionLifecycleListener.processOutbox` — no locking select, no claim/status
  flip, no `@SchedulerLock`, and the backing entity carries no `@Version`, making it the only outbox-shaped
  scheduler in the codebase with none of the three standard protections. AC2, a genuine bug in
  `VideoDeletionOutboxProcessor.findClaimedBatch()` — an unscoped `SELECT ... WHERE status = 'CLAIMED'`
  with no per-run filter, the identical bug shape (and method name) `skillars-deferred-118` AC3 already
  found and fixed for `RadarCompositeDlqProcessor`, but here two concurrent invocations claim genuinely
  disjoint row sets and then each processes both sets, causing real duplicate external
  `videoProviderAdapter.deleteAsset` calls, not merely a shared-field race. AC3 bundles three same-shape
  consistency fixes: `UserAdminService.removeNotActivatedUsers` (missed by `skillars-deferred-118` AC3's
  own lock-parity sweep despite that story's AC2 touching this exact method, and carrying its own
  long-standing unactioned "consider adding ShedLock" Javadoc TODO), `AuthCleanupService`'s two idempotent
  bulk-delete methods (never examined under this lens), and a log-level fix in
  `SessionPackExpiryNotifier.notifyExpiringPacks` (a legitimate concurrent `extendPack`/`pausePack` race
  logs at ERROR instead of the INFO its own module sibling `PaymentPendingSweeper` already established for
  the identical benign-race shape). No owner decision needed — all three ACs are direct fixes with an
  established sibling pattern already in this codebase. Everything else in scope
  (`ReconciliationWorkerScheduler.reconcile`, `UploadSessionExpiryScheduler.processExpired`,
  `WebhookEventProcessorScheduler.processPending`, `ModerationSlaMonitorService.detectSlaViolations`,
  `ConfigService.scheduledRefresh`, `AlertRuleCache.refresh`, `AlertEvaluationService.evaluate`,
  `MessagingEmitterRegistry.sendHeartbeats`, `RateLimitingService.evictIdleBuckets`) confirmed clean —
  either already-correct claim shapes, already-decided accepted tradeoffs from a prior story, or
  in-memory-only state where a distributed lock would add nothing or be actively wrong — see Provenance &
  Scoping for the per-class reasoning. This closes the `@Scheduled`-method sweep `skillars-deferred-115`
  began: after this story, every `@Scheduled` method in the codebase has been examined under this lens at
  least once. Branch: `story/deferred-120-scheduler-lock-gaps`, off `master` post-`skillars-deferred-119`-merge.
- 2026-09-17: Story revised post-review (`story-review.md`, checked line-by-line for false positives
  before applying — none found; every blocking/high finding independently re-verified against the actual
  code). Three blockers fixed: (1) **Severity corrected** — `fixedDelay` cannot overlap itself within one
  instance (Spring schedules the next run after the previous one completes), so AC1/AC2's "same instance"
  failure path was false; both bugs require the future multi-instance case only (no `docker-compose*.yml`
  declares `replicas`), and the Priority/Provenance framing was rewritten to say so instead of implying a
  live defect. (2) **AC1/AC2's "existing tests re-run green, no behavior change" claim was false** — three
  existing `@Autowired`-bean integration tests invoke the target method twice inside one test method
  (`VideoSubscriptionLifecycleListenerIT`'s `processOutbox_failureOnFirstAttempt_retriedOnSecondCall`/
  `processOutbox_maxAttemptsReached_markedDeadLetter`, `VideoDeletionOutboxProcessorIT`'s
  `process_failThenSucceed_completesOnRetry`), which `@SchedulerLock`'s `lockAtLeastFor` silently no-ops
  on the second call; added explicit `releaseSchedulerLock(...)` tasks for exactly these three tests
  (confirmed, by reading `DatabaseResetTestExecutionListener.backdateShedLock`, that it is an unscoped
  `UPDATE` with no `WHERE` clause, so every *other* call site — 12 of the 15 a first-pass reading of the
  review might suggest — is already safe via the per-test-method reset and needs no change) and specified
  the four lock `name` strings the tests need verbatim. (3) **AC3 Finding 2 silently re-introduced a
  `@SchedulerLock` + `@Transactional` stack** `SchedulerLockTransactionOrderingIT`'s own Javadoc instructs
  restoring its advisor-ordering assertion for — added that task, plus an explicit note that keeping
  `@Transactional` on `UserAdminService`/`AuthCleanupService` is correct here (unlike the booking
  schedulers, none of these three has one item's exception able to roll back a whole batch's shared
  transaction). Five high-severity corrections: AC1's Path B "doubling" claim was backwards
  (`findActiveReadyByOwner` is itself `FOR UPDATE SKIP LOCKED` and the lock is held for the whole entry via
  `@Transactional(REQUIRES_NEW)`, so a concurrent run gets skipped/empty pages, not duplicates) — replaced
  with the real risk, a detached-entity `merge` with no `@Version` that can silently overwrite a
  `DEAD_LETTER` status with `PROCESSED` or vice versa; AC2 was missing a second, more-likely-to-trigger
  concurrent path (`resetStaleClaimed` un-claims a row using its original eligibility timestamp, not a
  claim timestamp, so it can reset a row still mid-flight — the identical unfixed weakness exists in the
  already-locked `RadarCompositeDlqProcessor`) and two supporting facts (`deleteAsset`'s 404-as-success
  handling is what keeps a duplicate call safe; `appendDeletionLog` is an unconditional `INSERT`, so a
  duplicate `video_deletion_log` row is a real, separate consequence); the `lockAtLeastFor` sizing
  precedents named in Dev Notes were wrong (`ReconciliationWorkerScheduler.reconcile` has no lock at all;
  `OutboxPollerScheduler` is a 5-second, not 60-second, cadence) and replaced with a verified table
  (`RadarCompositeDlqProcessor`, `QuotaReservationTimeoutService`, `EmailRetryScheduler`); AC3 Finding 1's
  "size `lockAtMostFor` generously" advice was actively dangerous given `removeNotActivatedUsers`'s loop
  can spin forever on a deterministic per-user delete failure (a generous `lockAtMostFor` only delays a
  second node piling onto the same non-terminating spin) — added a `MAX_BATCHES_PER_RUN`-style cap
  (mirroring `SluSnapshotAppliedRetentionService`'s existing precedent) as part of the fix instead. Two
  medium corrections to AC3 Finding 3: the "`extendedAt IS NULL` predicate" justification for why a
  skipped warning is harmless covered only `extendPack`; the more likely concurrent writer (ordinary
  session consumption, `pausePack`) sets neither `extendedAt` nor `expiresAt` — reworded to the real
  reason (the pack is simply re-selected and warned the next morning, still inside the window); the story
  pointed at a non-existent `PaymentPendingSweeperTest` case to mirror (grepped, zero
  `OptimisticLocking` hits in either `PaymentPendingSweeperTest`/`-IT`) — corrected to say the test must be
  written from scratch against the production code shape; and added one sentence recording why
  `SessionPackForfeitureScheduler`'s re-fetch-and-re-check pattern was deliberately not extended to this
  method (`@Version` already closes the gap that pattern exists to prevent). Low nits fixed: the
  `outbox_poll_delay_ms` property's `platform.` prefix (was written as `app.`); AC4's grep command
  extended from two to all five touched classes, with a note that non-zero hits are expected and must be
  read, not treated as a failure. Tasks, Dev Notes, Files to Read, and the Verification Checklist were
  updated to match every correction above. No finding in the review turned out to be a false positive.
- 2026-09-17: Dev complete (`/bmad-dev-story`), status → review. AC1: `@SchedulerLock` added to
  `VideoSubscriptionLifecycleListener.processOutbox` (`lockAtMostFor="PT10M"`, `lockAtLeastFor="PT30S"`,
  arithmetic shown in the method's Javadoc), `releaseSchedulerLock` added to the two
  `VideoSubscriptionLifecycleListenerIT` tests that call `processOutbox()` twice per method, new
  reflection-based presence test. AC2: `@SchedulerLock` added to
  `VideoDeletionOutboxProcessor.process` (`lockAtMostFor="PT15M"`, `lockAtLeastFor="PT30S"` — sized larger
  than its structural twin `RadarCompositeDlqProcessor`'s `PT10M` since this processor's per-item cost is
  external network I/O, not DB-only), `releaseSchedulerLock` added to the one
  `VideoDeletionOutboxProcessorIT` test that calls `process()` twice, new presence test. AC3: added
  `MAX_BATCHES_PER_RUN = 100` to `UserAdminService.removeNotActivatedUsers`'s previously open-ended loop
  (mirroring `SluSnapshotAppliedRetentionService`'s precedent) plus `@SchedulerLock`
  (`lockAtMostFor="PT1H"`, `lockAtLeastFor="PT1M"`); `@SchedulerLock` added to both `AuthCleanupService`
  methods (`lockAtMostFor="PT10M"`/`"PT5M"`, `lockAtLeastFor="PT1M"` each, sized independently per cadence);
  `SchedulerLockTransactionOrderingIT`'s advisor-ordering assertion restored (exercised against
  `AuthCleanupService.purgeExpiredRefreshTokens`) per that test class's own prior instruction;
  `SessionPackExpiryNotifier.notifyExpiringPacks` now catches `OptimisticLockingFailureException` before
  the generic `Exception` catch, logging INFO instead of ERROR, mirroring `PaymentPendingSweeper`'s
  identical split. AC4: grep sweep re-run against HEAD for all five touched classes — 6 hits, all read and
  confirmed unrelated to this story's scope, no ledger edit needed. New tests: 2 new IT presence tests
  (AC1/AC2), `UserAdminServiceTest` +2 (batch-cap bound + lock presence), `AuthCleanupServiceTest` (new
  file, first coverage for this class, 4 tests), `SchedulerLockTransactionOrderingIT` +1 (restored
  assertion), `SessionPackExpiryNotifierTest` (new file, first coverage for this class, 2 tests — INFO
  vs. ERROR). All targeted suites green; curated touched-module regression sweep (43 classes across
  `security.service`, `payment.service`'s session-pack/payment-pending family, and `video.service`'s
  video-lifecycle/outbox family, plus `SchedulerLockTransactionOrderingIT`) re-run together, zero
  regressions. No `mvn verify` run locally per project convention.
- 2026-09-17: Code review response applied (`/bmad-code-review`, three parallel layers: Blind Hunter,
  Edge Case Hunter, Acceptance Auditor). 4 decision-needed + 17 patch + 4 pre-existing-deferred
  findings; every one independently re-verified against HEAD before being applied — zero false
  positives. All 4 decisions and all 17 patches applied (see "Review Findings" above for the full
  per-item resolution). Highlights: AC1's Path B loop was genuinely unbounded (video count capped
  only by storage quota, not count) — added `MAX_PAGES_PER_ENTRY` and restructured `processEntry` to
  return `boolean` so a capped attempt is left `PENDING` without consuming one of only 5 default
  outbox attempts (`lockAtMostFor` raised to `PT1H`). AC2's `lockAtMostFor=PT15M` exceeded
  `resetStaleClaimed`'s pre-existing 10-minute stale-claim window, so lock expiry could have actively
  triggered the double-processing the lock exists to prevent — fixed by raising the window to 20
  minutes (named constants, invariant documented) rather than shrinking the lock and thinning its
  margin. AC3 Finding 1 gained a per-run `failedLogins` skip-set so one permanently-undeletable user
  can no longer starve every deletable user behind it, plus a false-positive fix on the `CAPPED` alert
  (a healthy run draining exactly on the cap boundary no longer pages an operator about a
  non-existent backlog). `LoginAttemptRepository.deleteByAttemptedAtBefore` converted from a derived
  (row-by-row) delete to a genuine bulk `@Query` DELETE. `SessionPackExpiryNotifier`'s success log
  moved to after transaction commit (was logging "sent" before the commit that could still fail).
  Two `deferred-work.md` bullets added (the `resetStaleClaimed`/`claimPendingBatch` stale-window
  weakness shared with `RadarCompositeDlqProcessor`; `ModerationSlaMonitorService`'s now-`[DECIDED]`
  permanent no-`@SchedulerLock` status). Several false/stale documentation claims corrected without
  code changes (a `REQUIRES_NEW` self-invocation claim, a wrong lock-sizing precedent citation, a
  dangling method-name reference, a site-count miscount). New/relocated tests: two lock-presence tests
  moved out of Testcontainers ITs into new plain reflection test classes
  (`VideoSubscriptionLifecycleListenerSchedulerLockTest`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
  now pinned to exact values); new `VideoSubscriptionLifecycleListenerTest` (first plain-unit coverage,
  proves the page-cap/attempt-accounting behavior); `UserAdminServiceTest` cap test rewritten for the
  skip-set's changed termination shape plus a new stuck-user-does-not-block-others test, both with
  `@Timeout`; `AuthCleanupServiceTest` cutoff argument pinned via `ArgumentCaptor`; both
  `SessionPackExpiryNotifierTest` tests strengthened (exact message/`eventPublisher` verification);
  `SchedulerLockTransactionOrderingIT` gained `UserAdminService`'s `NOT_SUPPORTED`-propagation advisor
  coverage (the plain-`@Transactional` case alone left that story's own at-length safety argument
  untested). All 11 directly-touched test classes re-run green after every fix; the full curated
  touched-module regression sweep (now 47 classes, including the relocated/new test classes) re-run
  together a second time, zero regressions.

---

## Dev Agent Record

### Implementation Plan

Implemented all three ACs in task order (AC1 → AC2 → AC3 → AC4 ledger closeout → AC5 final validation),
each ending with its own targeted test run before moving on, mirroring `skillars-deferred-118`/`-119`'s
established `@SchedulerLock`-sizing and reflection-presence-test conventions throughout.

**AC1 — `VideoSubscriptionLifecycleListener.processOutbox`.** Added `@SchedulerLock(name =
"VideoSubscriptionLifecycleListener_processOutbox", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")`.
Sizing arithmetic (shown inline in the method's Javadoc): worst case is 100 entries
(`findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc`) × up to one full page (default
`platform.video.lifecycle.batch_size` = 100) of Path B's `blockForSubscriptionExpiry` calls per entry =
10,000 calls. Each call is a single `findById`+`save` DB round trip with no external I/O — 30ms/call
generous worst case under lock contention gives 10,000 × 0.03s = 300s (5 min); `PT10M` gives ~2x margin.
`lockAtLeastFor = PT30S` mirrors `RadarCompositeDlqProcessor`'s identical 60s-cadence reasoning (sits
comfortably below the cadence, guards the pathological fast-fail-refire edge case). Added
`releaseSchedulerLock("VideoSubscriptionLifecycleListener_processOutbox")` between the two
`processOutbox()` calls in `processOutbox_failureOnFirstAttempt_retriedOnSecondCall` and
`processOutbox_maxAttemptsReached_markedDeadLetter`, and a new reflection-based presence test
(`processOutbox_carriesSchedulerLock`). Confirmed (by reading) that every call site in
`SimultaneousExpiryIT`/`YearlyExemptionRenewalIT` invokes `processOutbox()` once per test method — no
change needed there.

> **Superseded by code review 2026-09-17 (Decision 4):** the "up to one full page per entry" premise
> above was false — Path B's `do/while` drains *every* ACTIVE/READY video the owner has, genuinely
> unbounded (video count is capped only by storage quota, not a count limit), and this is the one
> scheduler where `@SchedulerLock` is the sole double-processing protection. Added
> `MAX_PAGES_PER_ENTRY = 10` to `processEntry` (now returns `boolean`; hitting the cap logs a WARN
> and returns `false` rather than throwing, so `processAndSaveEntry` can un-count the pre-try
> `attempts` increment and leave the entry `PENDING` — the cap must not consume one of only 5 default
> attempts). Corrected worst case: 100 entries × 10 pages × 100 videos/page = 100,000 calls × 0.03s =
> 3,000s (50 min); `lockAtMostFor` raised to `PT1H`. New `VideoSubscriptionLifecycleListenerTest`
> covers both the capped-and-free-retry case and normal completion. The reflection presence test
> also moved out of the IT into a new plain `VideoSubscriptionLifecycleListenerSchedulerLockTest`
> (code review Patch #8) and now asserts the exact `PT1H`/`PT30S` values (Patch #6).

**AC2 — `VideoDeletionOutboxProcessor.process`.** Added `@SchedulerLock(name =
"VideoDeletionOutboxProcessor_process", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")`. Sizing
arithmetic: `BATCH_SIZE` (50) × per-item `deleteAsset` cost — an external Bunny.net HTTP call with no
retries at this layer. Per `skillars-deferred-119` AC1's S3-delete sizing precedent (~10s/item realistic
worst case, not the full 10s-connect + 30s-read hard-timeout sum confirmed in `VideoProviderConfig.java`),
50 × 10s = 500s (~8.3 min). Deliberately sized `PT15M` (not the `PT10M` its structural twin
`RadarCompositeDlqProcessor` uses for the same `BATCH_SIZE`) — this processor's per-item cost is external
network I/O, roughly double `RadarCompositeDlqProcessor`'s DB-only pessimistic-lock-retried 5s/row, so
`PT10M`'s ~1.2x margin over the 500s worst case was too thin; `PT15M` gives ~1.8x. Added
`releaseSchedulerLock("VideoDeletionOutboxProcessor_process")` between the two `process()` calls in
`process_failThenSucceed_completesOnRetry`, and a new reflection-based presence test
(`process_carriesSchedulerLock`). The other four tests in this class call `process()` once per method —
confirmed by reading, no change needed.

> **Corrected by code review 2026-09-17 (Decision 1):** the `PT15M` lock exceeded `resetStaleClaimed`'s
> pre-existing 10-minute stale-claim window — since `claimPendingBatch` never stamps `next_retry_at`,
> that gap meant lock expiry could actively trigger the same double-processing the lock exists to
> prevent (a second instance's `resetStaleClaimed` un-claiming a still-in-flight row). Rather than
> shrinking the lock to `PT10M` (which would have thinned the margin above to ~1.2x), the fix raises
> `STALE_CLAIM_WINDOW` to 20 minutes — both values extracted as named constants
> (`STALE_CLAIM_WINDOW`, `LOCK_AT_MOST_FOR`) with the invariant documented in each other's Javadoc.
> `lockAtMostFor` stays `PT15M`; `resetStaleClaimed` now keys off the 20-minute window. Crash-recovery
> latency moves from 10 to 20 minutes, immaterial for a 60-second-cadence poller.
> `VideoDeletionOutboxProcessorSchedulerLockTest` (code review Patch #8 — also relocated out of the
> IT) asserts the exact `PT15M`/`PT30S` values.

**AC3 — scheduler-lock parity + log-level consistency (three findings, one AC).**
- **Finding 1 (`UserAdminService.removeNotActivatedUsers`):** added `MAX_BATCHES_PER_RUN = 100` (mirrors
  `SluSnapshotAppliedRetentionService`'s existing precedent for the identical purpose) to cap the
  previously open-ended `while (hasMore)` loop, plus a `log.error` when the cap is hit (backlog picked up
  next run). Then added `@SchedulerLock(name = "UserAdminService_removeNotActivatedUsers", lockAtMostFor =
  "PT1H", lockAtLeastFor = "PT1M")`. Sizing: at the configured default batch size (100,
  `SecurityProperties.getUserCleanupBatchSize()`), 100 batches is a defensible ceiling (10,000 delete
  attempts) — `deleteUserInTransaction` is a DB-only `REQUIRES_NEW` delete with no external I/O; even a
  pessimistic 200ms/attempt (covering a constraint-violation rollback, the pathological path the cap
  exists to bound) gives 10,000 × 0.2s = 2,000s (~33 min); `PT1H` gives real margin above that.
  `lockAtLeastFor = PT1M` mirrors `MessageRetentionScheduler`'s identical daily-cron reasoning (a
  defensive floor against a pathological fast-fail-refire, not a tight-window guard). Kept the existing
  `@Transactional(propagation = NOT_SUPPORTED)` — safe to stack with `@SchedulerLock` here since each
  delete is already isolated in its own `REQUIRES_NEW` transaction, unlike the booking schedulers' former
  batch-wide-shared-transaction shape.

  Extended by code review 2026-09-17 (Decision 3, Patch #1, Patch #2): `MAX_BATCHES_PER_RUN` stopped
  the spin but did not restore progress — with >= `batchSize` undeletable users, all 100 iterations
  would return the identical stuck batch and starve every deletable user behind it.
  `removeNotActivatedUsers` now tracks a per-run `failedLogins` set, and `findExpiredUsers` filters it
  out before `.limit(batchSize)`, so deletable users behind a stuck one still get processed in the
  same run (new test: `removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt`). The
  `CAPPED` ERROR log also had a false-positive path (a healthy run draining exactly on the 100th
  batch left `hasMore == true`) — fixed with one extra read-only re-query after the loop, only paid
  when the cap was actually reached. `deleteUserInTransaction`'s Javadoc claim that its own
  `REQUIRES_NEW` applies was also corrected — it does not (self-invocation bypasses the Spring
  proxy); the real isolation comes from `SimpleJpaRepository`'s own class-level `@Transactional` on
  each repository call.
- **Finding 2 (`AuthCleanupService`):** added `@SchedulerLock` to both methods —
  `purgeExpiredRefreshTokens` (`lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M"`, sized independently
  for this job's own bulk delete) and `purgeOldLoginAttempts`
  (`lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M"`, smaller `lockAtMostFor` since the 24-hour-retention
  login-attempts table is cheaper to bulk-delete against, sized independently for its 30-minute cadence
  per the story's explicit instruction not to copy one value across both). Kept both methods' existing
  plain `@Transactional` — single bulk statements, naturally idempotent, no batch-wide-rollback hazard.
  Restored `SchedulerLockTransactionOrderingIT`'s advisor-ordering assertion (removed by
  `skillars-deferred-118` when no scheduler stacked both annotations any more) as
  `authCleanupServicePurgeExpiredRefreshTokens_shedLockAdvisorIsOutsideTheTransactionAdvisor`, exercising
  it against `purgeExpiredRefreshTokens` (plain `@Transactional`, the same REQUIRED-propagation shape the
  original assertion was written for) — one representative bean is enough since the advisor ordering is
  `AsyncConfig`-wide, not per-bean.

  Corrected by code review 2026-09-17 (Patch #3, Decision 2): the original Javadoc claimed
  `purgeExpiredRefreshTokens`'s `PT10M` "mirrors `MessageRetentionScheduler`'s identical bulk-delete
  sizing" — false; that sibling is `PT30M`. Corrected to state `PT10M` is sized independently (value
  unchanged, still defensible on its own terms). Separately, `deleteByAttemptedAtBefore` was a
  *derived* delete (`@Modifying`, no `@Query`) — Spring Data would execute it as a `SELECT` of every
  matching row followed by one `DELETE` per entity, not the "single-statement bulk delete"
  `purgeOldLoginAttempts`'s Javadoc assumed. Converted to a genuine bulk `@Query DELETE` in
  `LoginAttemptRepository`, matching its sibling's already-correct shape.

  Also corrected (Patch #9): the "one representative bean is enough" claim above left
  `UserAdminService`'s distinct `NOT_SUPPORTED`-propagation stack — argued at length above to be
  equally safe — genuinely untested. Added
  `userAdminServiceRemoveNotActivatedUsers_shedLockAdvisorIsOutsideTheTransactionAdvisor` to cover it
  directly; the "one representative" framing removed from the class Javadoc.
- **Finding 3 (`SessionPackExpiryNotifier`):** added a `catch (OptimisticLockingFailureException e)`
  clause (before the generic `catch (Exception e)`) logging at INFO, mirroring
  `PaymentPendingSweeper.sweepStrandedPayments`'s identical split. No `@SchedulerLock` change needed — it
  already carried one.

  Corrected by code review 2026-09-17 (Patch #11, Patch #12): the success log ("Pack expiry warning
  sent") was logged from inside the `transactionTemplate.execute` callback, before commit — a
  commit-time failure could have left a misleading "sent" line immediately followed by a "skipped"
  line for the same pack. The callback now returns a `boolean`; the success log moved outside
  `execute(...)`, logged only when the callback actually returned `true`. Also: the
  `OptimisticLockingFailureException` catch's comment implied it narrowly guarded `save(pack)`;
  corrected to describe its actual whole-transaction scope (and why `pack` remains the only realistic
  source), with the exception now attached to the INFO log as evidence.

**AC4 — ledger closeout.** Re-ran the grep sweep for all five touched classes against HEAD; every hit
(6 total, across `SessionPackExpiryNotifier` ×4 and `UserAdminService` ×1, plus the AC1/AC2 classes with
zero hits) read individually and confirmed to describe unrelated, already-closed concerns (a
pre-`skillars-deferred-92` transactionless-delivery bug, a stale-javadoc fix, an unrelated
deleted-player-crash TOCTOU, and `skillars-deferred-15`/`-103`/`-92` code-review items already resolved
in prior stories) — none describe this story's scheduler-lock-absence or log-level findings. No ledger
edit needed.

### Debug Log

No blocking issues. One test-authoring correction during AC3: the `SessionPackExpiryNotifierTest` INFO
assertion initially failed empty (`logback-test.xml` pins `root level="WARN"`, which filters INFO events
before they reach a directly-attached `ListAppender`) — fixed by explicitly raising
`SessionPackExpiryNotifier`'s own logger to `Level.INFO` in the test's `@BeforeEach` (and resetting it in
`@AfterEach`), matching the log-capture pattern `OutboxServiceTest` already established for ERROR-level
assertions in this codebase (which never needed the raise since ERROR is always above `WARN`).

### Completion Notes

- All four Verification Checklist items and all three ACs (plus AC4 ledger closeout) satisfied.
- 5 new/updated `@SchedulerLock` sites across 4 classes (corrected from an earlier "4 sites" miscount
  — code review 2026-09-17 Patch #15), each with worst-case arithmetic shown in its own Javadoc rather
  than a copy-pasted constant, per this story's own explicit requirement.
- Code review 2026-09-17 (`/bmad-code-review`, three parallel layers): 4 decision-needed + 17 patch +
  4 pre-existing-deferred findings, every one independently re-verified against HEAD before being
  recorded or applied — see "Review Findings" above for the full list and each item's resolution.
  Zero false positives found; all 4 decisions and all 17 patches applied. Notably: AC1's Path B loop
  was genuinely unbounded (not "one page per entry" as first drafted) and is now capped via
  `MAX_PAGES_PER_ENTRY`, with the cap explicitly NOT counted as a failed attempt; AC2's
  `lockAtMostFor` was corrected to stay inside `resetStaleClaimed`'s stale-claim window (window raised
  to 20 min rather than shrinking the lock, preserving margin); AC3 Finding 1 gained a per-run
  skip-set so a single stuck user can no longer permanently starve deletable users behind it; and
  `LoginAttemptRepository.deleteByAttemptedAtBefore` was converted from a derived (row-by-row) delete
  to a genuine bulk `@Query` DELETE.
- New/updated tests from the review response: `VideoSubscriptionLifecycleListenerSchedulerLockTest`
  and `VideoDeletionOutboxProcessorSchedulerLockTest` (new files — presence tests relocated out of
  the Testcontainers ITs, now plain reflection, pinned to exact lock values);
  `VideoSubscriptionLifecycleListenerTest` (new file — first plain-unit coverage for this class,
  proving the page-cap/attempt-accounting behavior); `UserAdminServiceTest` (cap test rewritten for
  the skip-set's changed loop-termination shape, +1 new stuck-user test, `@Timeout` added, lock values
  pinned); `AuthCleanupServiceTest` (cutoff argument pinned, lock values pinned);
  `SessionPackExpiryNotifierTest` (both tests strengthened — exact message/eventPublisher
  verification); `SchedulerLockTransactionOrderingIT` (+1, `UserAdminService`'s `NOT_SUPPORTED`
  advisor-ordering coverage, dangling method-name reference fixed).
- `deferred-work.md` gained 2 more bullets from the review: the `resetStaleClaimed`/`claimPendingBatch`
  stale-claim-window weakness (shared with `RadarCompositeDlqProcessor`, now less exposed here after
  Decision 1) and `ModerationSlaMonitorService`'s now-permanent `[DECIDED]` no-`@SchedulerLock` status.
- Targeted test runs, all green post-review-response: `VideoSubscriptionLifecycleListenerSchedulerLockTest`,
  `VideoDeletionOutboxProcessorSchedulerLockTest`, `VideoSubscriptionLifecycleListenerTest`,
  `VideoSubscriptionLifecycleListenerIT`, `VideoDeletionOutboxProcessorIT`, `UserAdminServiceTest`,
  `AuthCleanupServiceTest`, `SessionPackExpiryNotifierTest`, `SchedulerLockTransactionOrderingIT` —
  plus the full `platform.video.service` / `platform.security.service` / `platform.payment.service` /
  `platform.scheduler` package suites re-run together with zero regressions.
- No `mvn verify` run locally, per project convention (`docs/validation-strategy.md`) — GitHub CI is the
  full-verification gate.
