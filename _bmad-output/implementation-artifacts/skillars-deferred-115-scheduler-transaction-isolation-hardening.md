# Story: Scheduler Transaction-Boundary & Batch-Isolation Hardening

**Story Key:** `skillars-deferred-115-scheduler-transaction-isolation-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one deliberate-but-risky architectural shape needing an owner decision + one real resilience gap matching an already-fixed sibling bug)
**Status:** ready-for-dev
**Created:** 2026-09-16

---

## Provenance & Scoping (read before starting)

This story was mined from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: ad-hoc audit of notification + video modules (2026-09-16)` section — both findings
from a directly-requested audit (not tied to a specific story's code review) of transaction-boundary,
TOCTOU, and batch-memory patterns across every `@Scheduled`/batch-processing class in the notification
and video modules, performed as a follow-up to `skillars-deferred-114`. Line citations below were
verified against HEAD at story-creation time by reading the actual files, not trusted from the ledger
text.

**Scope was deliberately narrow.** The audit covered every scheduler/batch processor in
`platform.notification.*` and `platform.video.service`/`platform.video.repo` — `MailManager`,
`EnvelopeEntityRepository`, `EmailRetryScheduler`, `OutboxRowProcessor`, `ReconciliationWorkerScheduler`,
`WebhookEventProcessorScheduler`, `UploadSessionExpiryScheduler`, `QuotaReservationBatchExpirer`/
`QuotaReservationTimeoutService`, `BandwidthResetChunkProcessor`, `VideoDeletionService`,
`AlertEvaluationService`/`AlertRuleCache`, `BookingEmailListener` — and found only the two classes named
below deviating from this module's own established "short transaction for the batch SELECT, separate
transaction per row" pattern. Everything else already follows it correctly; do **not** re-audit those
classes as part of this story, and do not extend this story's scope to other modules (booking, payment,
messaging, marketplace, etc.) — that was explicitly out of scope for the audit this story is mined from.

| # | Finding | Outcome |
|---|---|---|
| 1 | `ModerationSlaMonitorService` holds a batch's locks/entities for the whole method | **AC1 below** — needs an owner decision (see AC1), not a unilateral fix. |
| 2 | `VideoLifecycleScheduler` has no per-item exception isolation or `@SchedulerLock` | **AC2 below** — a real fix, mirroring a pattern this codebase already applied elsewhere. |

AC3 is the standard ledger-hygiene closeout every `skillars-deferred-*` story in this project performs.

---

## User Story

As a **Platform Engineer**, I want **`ModerationSlaMonitorService`'s batch-lock/entity-lifetime shape
explicitly reviewed and decided on, and `VideoLifecycleScheduler` hardened with the same per-item
failure isolation and cluster-wide mutual exclusion every sibling scheduler in this module already
has**, so that **a single video's optimistic-lock conflict or state-machine violation can no longer
silently drop an entire day's lifecycle batch, and the SLA monitor's lock-holding footprint is a
documented, owner-approved tradeoff rather than an unreviewed side effect of its own bug-fix history**.

---

## Acceptance Criteria

### AC1: `ModerationSlaMonitorService.detectSlaViolations()` — batch lock/entity-lifetime shape (decision required)

**Given** `detectSlaViolations()` carries a method-level `@Transactional` (`ModerationSlaMonitorService.java:56`)
that wraps its entire body, including the `findScanningOlderThan(threshold, Instant.now(), 50)` call
(`ModerationSlaMonitorService.java:66`) and the full `for` loop over up to 50 `stuckVideos`,

**And** `VideoRepository.findScanningOlderThan` (`VideoRepository.java:47-57`) is itself only
`@Transactional` with default (`REQUIRED`) propagation and issues `SELECT ... FOR UPDATE SKIP LOCKED`
— so when called from inside `detectSlaViolations()`'s already-open transaction, it **joins** that
transaction instead of opening its own short one,

**When** the scheduler runs (every 5 minutes by default, `app.video.moderation.sla-monitor-delay-ms`),

**Then today**: all ≤50 selected `Video` rows stay row-locked under `FOR UPDATE`, and their entities
stay managed in one Hibernate persistence context, for the *entire* method duration — not just the
read. This includes every nested `requiresNewTemplate.execute(...)` per-video round-trip
(`ModerationSlaMonitorService.java:80-88`, `106-113`, `134-142`), each of which needs a *second*,
concurrently-held physical connection from the pool while the outer transaction is merely suspended,
not released.

**This is the exact "batch loaded inside one long transaction, persistence context holds everything"
shape every sibling scheduler in this module deliberately avoids** — compare
`ReconciliationWorkerScheduler.reconcile()`, `WebhookEventProcessorScheduler.processPending()`,
`UploadSessionExpiryScheduler.processExpired()`, all of which load their batch inside a short, separate
`TransactionTemplate.execute(...)` block and process/write each row in its own later transaction,
specifically to release the SELECT's lock (and detach its entities) before any per-row work begins.

**Do not unilaterally "fix" this by mechanically copying the sibling pattern.** The class's own comment
at `ModerationSlaMonitorService.java:38-40` shows the author already knew the outer transaction holds
these locks, and deliberately added `REQUIRES_NEW` per-video sub-transactions specifically so one
video's failure cannot roll back the whole batch — a real, considered mitigation, just not a complete
one. At 50 rows (hardcoded literal, not config-bound — see the "Known characteristic" note below) this
will not exhaust heap; the real cost is lock contention against any other writer touching one of those
50 videos for the sweep's full duration, and the standing risk of two connections held concurrently per
scheduler run. Present the owner with the two real options before writing code:

1. **Restructure to match the sibling pattern**: load `stuckVideos` in its own short transaction
   (removing `findScanningOlderThan`'s `@Transactional`, or explicitly excluding it from the outer one),
   process each video in its own `REQUIRES_NEW` transaction as today, decide how per-video failure
   handling (the existing `videoConsecutiveFailures` map + `CONSECUTIVE_FAILURE_THRESHOLD` logic) should
   behave once the outer lock no longer holds the batch together — SKIP LOCKED means a second concurrent
   run could otherwise pick up an already-claimed-but-not-yet-advanced row; decide whether this needs an
   explicit "claim" step (like `WebhookEventProcessorScheduler`'s `PENDING`→`PROCESSING` write) or whether
   the existing state-machine validation (`TerminalStateViolationException` handling) already makes a
   double-pick safe enough, same as `ReconciliationWorkerScheduler.reconcile()` relies on.
2. **Keep the current shape, but as a documented decision**: add an inline code comment at the
   `@Transactional` on `detectSlaViolations()` explaining *why* this scheduler holds the batch lock for
   the full sweep (rather than the sibling pattern), what bounds the blast radius (batch size, 5-minute
   interval, connection-pool headroom), and why that's an accepted tradeoff — so the next reader doesn't
   have to reconstruct this analysis from scratch.

**Known characteristic worth fixing regardless of which option is chosen:** the batch size (`50`) is a
hardcoded literal at the call site (`ModerationSlaMonitorService.java:66`), unlike every sibling
scheduler's `properties.getReconciliation().getBatchSize()`/config-bound equivalent. Make it configurable
(mirror `platform.video.reconciliation.batch-size`'s pattern) regardless of which option above is chosen
— this is a real gap either way.

**Verified by:**
- Whichever option is chosen, a test proving the batch-load transaction's scope: for Option 1, that the
  SELECT's lock is released before per-video processing begins (e.g. a concurrent-access test showing a
  second reader/writer is not blocked once the load completes); for Option 2, no behavioral test is
  needed — the inline comment itself is the deliverable, reviewed by the owner.
- If Option 1: existing `ModerationSlaMonitorService`-adjacent tests (if any exist — check
  `src/test/java/.../platform/video/service/` for the class name) re-run green; the per-video failure
  paths (`TerminalStateViolationException` handling, `videoConsecutiveFailures` threshold) re-verified
  unaffected by the restructure.
- Batch-size externalization: a test asserting the configured value (not `50`) is what's passed to
  `findScanningOlderThan`.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:38-40,56-66`,
`src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:47-57`]

---

### AC2: `VideoLifecycleScheduler` — per-item failure isolation + cluster-wide mutual exclusion

**Given** `runBlockedToArchivedPhase`/`runArchivedToDeletedPhase` (`VideoLifecycleScheduler.java:57-90`,
`92-117`) each loop over a batch of `Video` candidates, wrapping only the external provider call
(`archiveAsset`/`deleteAsset`) in try/catch, with the state-transition write
(`transactionTemplate.execute(...)` calling `videoLifecycleService.archiveForLifecycle`/`markPurged`,
lines `81-85`/`106-112`) left unguarded,

**And** `Video` carries `@Version` (confirmed in `Video.java:27`), so a genuine concurrent write to the
same row throws `ObjectOptimisticLockingFailureException` — which nothing here catches,

**And** `runLifecycleJob()` (`VideoLifecycleScheduler.java:38`) carries no `@SchedulerLock`, unlike every
sibling scheduler sharing its "short `FOR UPDATE SKIP LOCKED` SELECT, then separate per-row
transactions" shape — `EmailRetryScheduler` added `@SchedulerLock` specifically to close this exact
window (see its own `EmailRetryScheduler.java:86-99` "Code review 2026-09-15 (C1)" comment: a row's
`FOR UPDATE SKIP LOCKED` lock only holds for the lifetime of the SELECT's own short transaction, so a
row is re-selectable by a second pod — or this same pod's next tick — between that transaction's commit
and the actual state-changing write completing), and `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`
carries the same lock for the identical reason,

**When** a single video in a batch of up to `platform.video.lifecycle.batch_size` (default 100, ceiling
10000) throws any unchecked exception from the state-transition write — an optimistic-lock conflict from
a genuinely concurrent writer (another pod, an admin action, `ReconciliationWorkerScheduler`), or any
other `RuntimeException` from `videoLifecycleService`/`quotaService`,

**Then today**: the exception propagates out of the `for` loop, out of `runBlockedToArchivedPhase`
(or `runArchivedToDeletedPhase`), out of `runLifecycleJob()` uncaught — silently dropping every remaining
candidate in that phase's batch, **and skipping the entire second phase** if the exception occurs during
`runBlockedToArchivedPhase` (since `runArchivedToDeletedPhase` is only reached if the first phase call
returns normally). Since this job runs once/day (`cron = "0 0 3 * * *"` default), the recovery window for
a dropped batch is a full day, not the 30-60s the other schedulers self-heal within.

**Recommended approach:**
1. Wrap each iteration's state-transition write in its own try/catch (mirroring
   `ReconciliationWorkerScheduler.reconcile()`'s per-video `catch (VideoStateConflictException |
   VideoNotFoundException e)` pattern at `ReconciliationWorkerScheduler.java:73-79`): log and `continue`
   on `ObjectOptimisticLockingFailureException` and any other expected/recoverable failure, so one bad
   row cannot take the rest of the batch (or the second phase) down with it.
2. Add `@SchedulerLock` to `runLifecycleJob()`, mirroring `EmailRetryScheduler`'s
   (`lockAtMostFor`/`lockAtLeastFor` sized for a once-daily job processing up to `batch_size` rows across
   two phases — size generously, since the consequence of the lock expiring mid-run and a second instance
   starting is a genuine double-archive/double-delete attempt, not just a missed tick).

**Verified by:**
- A test forcing `videoLifecycleService.archiveForLifecycle` (or `markPurged`) to throw for one video in
  a multi-video batch, asserting the remaining videos in that phase are still processed and, for a
  failure during phase one, that phase two still runs.
- `@SchedulerLock` presence confirmed the same way `EmailRetryScheduler`'s is likely already tested (or,
  if no existing precedent test covers `@SchedulerLock` behavior directly, at minimum confirm the
  annotation is present with a sensible `lockAtMostFor`/`lockAtLeastFor` via a plain reflection/annotation
  check — check `EmailRetryScheduler`'s or `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`'s
  own test suites first for an existing pattern to extend rather than inventing one).

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java:38,57-90,92-117`,
`src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java:73-79,174-175`
(sibling per-item + `@SchedulerLock` patterns to mirror),
`src/main/java/com/softropic/skillars/platform/notification/infrastructure/EmailRetryScheduler.java:86-104`
(the exact prior fix for this same race class)]

---

### AC3: Ledger hygiene

**Given** this story closes both findings in `deferred-work.md`'s
`## Deferred from: ad-hoc audit of notification + video modules (2026-09-16)` section,

**Then**: update that section per this file's own stated convention (delete a bullet outright if closed
by a real fix; retag `[DECIDED <date> (skillars-deferred-115): ...]` if AC1 lands as Option 2 — a
decision, not a code fix). Reconstruction check: every surviving line in the target section must match
the pre-edit content, in order, with only the specified deletions/retags applied.

**Verified by:** a diff of the section before/after showing only the expected deletions/retags, no
unrelated changes; grep confirms no other section of `deferred-work.md` was touched.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md` — the
`## Deferred from: ad-hoc audit of notification + video modules (2026-09-16)` section]

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: ModerationSlaMonitorService decision + (conditionally) restructure**
  - [ ] Present both options in AC1 to the project owner; get an explicit decision before writing code
  - [ ] If Option 1: restructure the batch load into its own short transaction; decide and implement the
        claim/double-pick-safety question raised in AC1; re-verify per-video failure handling
  - [ ] If Option 2: write the inline decision comment; no behavioral change
  - [ ] Externalize the hardcoded batch size (`50`) to config either way
  - [ ] Test(s) per AC1's "Verified by"

- [ ] **Task 2 — AC2: VideoLifecycleScheduler hardening**
  - [ ] Add per-item try/catch around each phase's state-transition write, mirroring
        `ReconciliationWorkerScheduler.reconcile()`'s pattern
  - [ ] Add `@SchedulerLock` to `runLifecycleJob()`, sized for a once-daily, two-phase, up-to-batch-size run
  - [ ] Test(s) per AC2's "Verified by"

- [ ] **Task 3 — AC3: ledger updates**
  - [ ] Close/retag the target `deferred-work.md` section per AC1's outcome
  - [ ] Reconstruction check

- [ ] **Task 4 — Final validation**
  - [ ] Run all new/modified targeted test classes together; confirm zero regressions in the video
        moderation/lifecycle/reconciliation test suites
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only (video module: moderation, lifecycle, reconciliation).
- **Risk areas:**
  - AC1 is a **decision task first, implementation task second** — do not restructure
    `ModerationSlaMonitorService` without an explicit owner sign-off on which option was chosen; the
    class's existing `REQUIRES_NEW`-per-video mitigation shows this shape was already deliberated once,
    so a unilateral "fix" risks re-breaking something the original author already reasoned through.
  - AC2 is a more mechanical fix with direct precedent to mirror in the same module
    (`ReconciliationWorkerScheduler`, `EmailRetryScheduler`) — lower risk, but confirm
    `videoLifecycleService.archiveForLifecycle`/`markPurged`'s actual thrown-exception types before
    writing the catch clause (don't guess; read the methods).
- **Testing approach:** favor real, container-backed integration tests over mocks wherever this module
  already does (see `ReconciliationWorkerScheduler`'s and `WebhookEventProcessorScheduler`'s existing
  test suites, if present, for the established pattern before writing new tests from scratch).
- **Project structure:** both touched classes are in `platform.video.service` — no new module, no
  infrastructure-layer changes, no migration.

### Project Structure Notes

- All touched files are within `platform.video.service`/`platform.video.repo` — no new module.
- `@SchedulerLock` (`net.javacrumbs.shedlock.spring.annotation.SchedulerLock`) is already a project
  dependency, used by four other schedulers in this codebase — no new dependency needed.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java` (AC1 —
  whole file, only ~157 lines)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:42-57` (AC1 —
  `findNonTerminalForUpdate` vs. `findScanningOlderThan`'s differing `@Transactional` presence)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` (AC2 —
  whole file, only ~127 lines)
- `src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java:48-90,
  168-221` (AC1 & AC2 — the sibling pattern both ACs mirror: short-transaction batch load, per-item
  try/catch, `@SchedulerLock` on the sweep method)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/EmailRetryScheduler.java:86-131`
  (AC2 — the exact prior fix for the "SELECT FOR UPDATE lock releases before the real write happens"
  race class, including its own code-review-sourced javadoc explaining why)
- `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:59-113`
  (AC1 — the explicit PENDING→PROCESSING "claim" step pattern, relevant if AC1's Option 1 needs one)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` (AC2 —
  `archiveForLifecycle`/`markPurged`'s actual thrown-exception types)
- `src/main/java/com/softropic/skillars/platform/video/repo/Video.java:27` (AC2 — confirms `@Version`
  presence, the mechanism that makes a concurrent-write conflict throw rather than silently corrupt)

---

## Verification Checklist

- [ ] AC1: owner decision made and recorded (either a restructure + tests, or a documented inline
      comment); hardcoded batch size (`50`) externalized to config either way
- [ ] AC2: a single video's failure during either phase no longer aborts the rest of that phase's batch
      or skips the second phase; `runLifecycleJob()` carries `@SchedulerLock`
- [ ] AC3: `deferred-work.md`'s `ad-hoc audit of notification + video modules` section reflects both
      outcomes; reconstruction check passes
- [ ] No regressions in existing video moderation/lifecycle/reconciliation test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: ad-hoc audit of
  notification + video modules (2026-09-16)`
- `_bmad-output/implementation-artifacts/skillars-deferred-114-mail-concurrency-quota-audit-ses-preflight.md`
  — sibling story this audit followed on from; same project conventions (ledger hygiene, no local
  `mvn verify`, real-Postgres-backed tests preferred)

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`, mined from `deferred-work.md`'s `ad-hoc audit of
  notification + video modules (2026-09-16)` section, itself surfaced by a directly-requested transaction-
  boundary/TOCTOU/memory audit of the notification + video modules following `skillars-deferred-114`.
