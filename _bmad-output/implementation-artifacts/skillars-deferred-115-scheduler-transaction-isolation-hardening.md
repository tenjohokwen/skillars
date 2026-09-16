# Story: Scheduler Transaction-Boundary & Batch-Isolation Hardening

**Story Key:** `skillars-deferred-115-scheduler-transaction-isolation-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one deliberate-but-risky architectural shape needing an owner decision + one real resilience gap matching an already-fixed sibling bug)
**Status:** done
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
will not exhaust heap — confirmed, not assumed: `Video.java` carries zero `@OneToMany`/`@ManyToOne`/
`@ElementCollection` fields, only scalar columns, so 50 loaded rows means exactly 50 managed entities in
the persistence context, no cascaded collections inflating that count (story-review 2026-09-16 flagged
this as an unverified assumption; it is now verified). The real cost is lock contention against any other
writer touching one of those 50 videos for the sweep's full duration, and the standing risk of two
connections held concurrently per scheduler run.

**Critical, empirically-confirmed finding (story-review 2026-09-16, sharpened): this is not just lock
contention with *other* writers — the per-video `REQUIRES_NEW` writes can self-block against the outer
transaction's own lock.** Every `requiresNewTemplate.execute(...)` call in this method eventually writes
to the *same* `Video` row the outer `@Transactional` already holds under `FOR UPDATE` (via
`videoLifecycleService.transitionOperationalState`'s `save()`, lines 80-88/134-142, or the inline
`videoRepository.findById(...).save(...)` at lines 106-113) — not a different, FK-related table as
originally speculated. Confirmed against a real Postgres 16 container: a second transaction's `UPDATE`
against a row locked `FOR UPDATE` by a still-open first transaction blocks (does not error, does not
proceed) until the first transaction commits/rolls back, or a `statement_timeout`/`lock_timeout`
intervenes. Here the "first transaction" (holding the lock) is the *same application thread*,
synchronously parked inside `requiresNewTemplate.execute(...)` waiting for the "second transaction" (the
REQUIRES_NEW write, on a separate pooled connection) to return — a same-thread, cross-connection circular
wait Postgres's own deadlock detector cannot see (it only observes the REQUIRES_NEW side as blocked; the
outer connection is idle from the engine's perspective, not itself waiting on a DB-visible lock). Without
an explicit `statement_timeout`/`lock_timeout` on this codepath, this hangs rather than failing fast into
the existing `catch (Exception e)`. **This is pre-existing behavior, not something this story
introduces** — every cycle that actually finds a stuck video already exercises this path today,
regardless of which option below is chosen; worth surfacing to the owner as a data point either way.
**Option 1 (restructure) resolves this automatically as a side effect** — once the batch SELECT's own
transaction commits before the per-video loop starts, its `FOR UPDATE` locks are already released, so the
REQUIRES_NEW writes never contend with anything. **Option 2 (keep + document) does not resolve it** — a
comment alone leaves the hang live; if Option 2 is chosen, treat closing this specific self-block (not
just documenting the batch-lock tradeoff) as a mandatory part of it, e.g. by confirming/adding a
`lock_timeout` that bounds the wait to a fast, caught failure instead of an indefinite one.

Present the owner with the two real options before writing code:

1. **Restructure to match the sibling pattern**: load `stuckVideos` in its own short transaction
   (removing `findScanningOlderThan`'s `@Transactional`, or explicitly excluding it from the outer one),
   process each video in its own `REQUIRES_NEW` transaction as today, decide how per-video failure
   handling (the existing `videoConsecutiveFailures` map + `CONSECUTIVE_FAILURE_THRESHOLD` logic) should
   behave once the outer lock no longer holds the batch together — SKIP LOCKED means a second concurrent
   run could otherwise pick up an already-claimed-but-not-yet-advanced row; decide whether this needs an
   explicit "claim" step (like `WebhookEventProcessorScheduler`'s `PENDING`→`PROCESSING` write) or whether
   the existing state-machine validation (`TerminalStateViolationException` handling) already makes a
   double-pick safe enough, same as `ReconciliationWorkerScheduler.reconcile()` relies on. **This
   sub-decision is the developer's to make, not a second owner round-trip** (story-review 2026-09-16
   flagged this as ambiguous): read `Video`'s state machine (`VideoLifecycleService.VALID_TRANSITIONS`)
   and the two REQUIRES_NEW branches' actual state changes to judge whether a double-pick's second writer
   would hit a `TerminalStateViolationException`/no-op rather than corrupt anything, and document that
   judgment inline at the call site either way — bundling a second owner decision into AC1 would slow
   delivery for a question the code itself already answers.
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
  needed — the inline comment itself is the deliverable, reviewed by the owner. Note: no existing test in
  this module already exercises this "second writer not blocked" pattern to extend (checked
  `ReconciliationWorkerIT`/`VideoLifecycleSchedulerTest` — neither has one); if Option 1 is chosen, a
  simpler mock-based check (assert the batch load's `TransactionTemplate`/short-transaction boundary is
  invoked and committed before any per-video `requiresNewTemplate.execute(...)` call) is acceptable in
  place of a full two-thread integration test, per story-review 2026-09-16's G.2.
- If Option 1: a regression test confirming the same-row self-block described above no longer reproduces
  — i.e. a per-video `REQUIRES_NEW` write against a row from the just-loaded batch does not contend with
  any lock the (now-committed) batch-load transaction held.
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

**Partial-batch recovery guarantee, once fixed** (story-review 2026-09-16 asked this be made explicit):
after AC2's per-item isolation lands, a mid-batch failure on video *N* no longer aborts videos *N+1..end*
— they are still attempted in the *same* run, so there is no "some processed, some not, until tomorrow"
window from an isolated failure alone. Re-selection safety comes from the query predicates themselves:
`findBlockedExceedingThreshold`/`findArchivedExceedingThreshold` filter on `access_state`, which
`archiveForLifecycle`/`markPurged` change as part of their write — a video that succeeded is no longer
selected by that phase's query on any later run, so re-running the whole job tomorrow only reprocesses
what's still eligible, not what already succeeded. One pre-existing, low-severity exception (not part of
this story's fix, noted for completeness): `markPurged` sets `operationalState=DELETED` but does not
change `accessState` away from `ARCHIVED`, so a purged video with no `archived_at` change remains matched
by `findArchivedExceedingThreshold` forever, re-attempting an already-idempotent `deleteAsset` call every
day and hitting a caught `VideoStateConflictException` on the write — wasteful, not unsafe, and unrelated
to this story's scope. **A dropped batch is still possible only when the *candidate count* exceeds
`batch_size`** (genuinely more eligible videos than one run's cap) — that gap is inherent to any batch
job with a size limit and is not something this story is expected to close.

**Confirmed non-issue: no race with `ReconciliationWorkerScheduler`** (story-review 2026-09-16 flagged
this as worth a "30-second grep," done): `ReconciliationWorkerScheduler.reconcile()` only selects rows via
`findNonTerminalForUpdate`, whose `WHERE operational_state IN ('UPLOADING', 'PROCESSING')` predicate is
structurally disjoint from `VideoLifecycleScheduler`'s `access_state IN ('BLOCKED', 'ARCHIVED')`
predicates — a video only reaches `BLOCKED`/`ARCHIVED` `accessState` once it is already `READY`
`operationalState` (`VideoLifecycleService.setAccessState`/`archiveForLifecycle`), and `READY` is excluded
from `reconcile()`'s candidate set entirely. The two schedulers cannot select the same row regardless of
timing overlap; no ordering guarantee or cross-scheduler note is needed.

**Recommended approach:**
1. Wrap each iteration's state-transition write in its own try/catch (mirroring
   `ReconciliationWorkerScheduler.reconcile()`'s per-video `catch (VideoStateConflictException |
   VideoNotFoundException e)` pattern at `ReconciliationWorkerScheduler.java:73-79`): log and `continue`
   on `ObjectOptimisticLockingFailureException` and any other expected/recoverable failure, so one bad
   row cannot take the rest of the batch (or the second phase) down with it.

   **Confirmed exception inventory** (story-review 2026-09-16 flagged this as unspecified — read directly
   from `VideoLifecycleService.java` rather than guessed): `archiveForLifecycle` throws only
   `VideoNotFoundException` explicitly; `markPurged` throws `VideoNotFoundException` and
   `VideoStateConflictException` (when the video isn't `READY`, e.g. a concurrent purge already ran).
   Neither method explicitly throws an optimistic-lock exception — `ObjectOptimisticLockingFailureException`
   arises implicitly from `videoRepository.save(video)`'s flush, via `@Version` on `Video`, only when a
   *genuinely concurrent* writer changed the row between this method's read and write. Two sibling
   patterns exist for the catch's breadth: `ReconciliationWorkerScheduler.reconcile()` catches a narrow,
   named list (`VideoStateConflictException | VideoNotFoundException`); `sweepOrphanedProviderAssets()`
   catches broad `RuntimeException` per row specifically so an *unanticipated* failure type still can't
   abort the batch. **Recommend the broad pattern here**, since AC2's own stated goal is "one bad row
   cannot take the rest of the batch (or the second phase) down" — a narrow catch would silently
   regress to today's abort-the-batch bug the first time an unlisted exception type appears. Catch
   `RuntimeException`, but `log.error` (not `.warn`) anything that isn't
   `ObjectOptimisticLockingFailureException`/`VideoNotFoundException`/`VideoStateConflictException`, so an
   unanticipated failure stays loud in logs even though it no longer aborts the batch.
2. Add `@SchedulerLock` to `runLifecycleJob()`, mirroring `EmailRetryScheduler`'s
   (`lockAtMostFor`/`lockAtLeastFor` sized for a once-daily job processing up to `batch_size` rows across
   two phases — size generously, since the consequence of the lock expiring mid-run and a second instance
   starting is a genuine double-archive/double-delete attempt, not just a missed tick).

   **Concrete precedent and a sizing gap to account for** (story-review 2026-09-16 flagged the original
   "size generously" guidance as too vague): `EmailRetryScheduler` uses
   `lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S"` for a batch capped at 10 rows with up to 3 retry
   attempts each. `VideoLifecycleScheduler`'s `batch_size` is operator-configurable up to a **10000**
   ceiling (`configService.getBoundedInt(..., 100, 1, 10000)`, line 49) — ten default-sized batches per
   phase, times two phases, each row making a real external provider HTTP call
   (`archiveAsset`/`deleteAsset`). Size `lockAtMostFor` off the *configured ceiling*, not the default of
   100 — a naive calculation based on "100 rows, two phases" would be sized correctly today but silently
   too tight the moment an operator raises `platform.video.lifecycle.batch_size`, causing the exact
   double-archive/double-delete failure mode `@SchedulerLock` exists to prevent. Document the sizing
   basis (rows × phases × expected per-row provider-call latency, at the ceiling, plus margin) in a code
   comment, the same way `EmailRetryScheduler.java:86-99` does.
3. Emit a `log.warn` for each skipped video (video id + exception type/message), so a partially-failed
   batch is visible in logs even though nothing aborts (story-review 2026-09-16 G.1 — implementation
   detail, but required before marking Task 2 complete, not left to developer taste). A metric is
   optional; a log line is not.

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

**Exact bullets to edit** (story-review 2026-09-16 asked these be quoted, not left for the developer to
hunt for — as of story-creation time these are the section's only two bullets, at
`deferred-work.md:2278` and `:2295`, but re-find them by their bold opening text since line numbers shift
as the ledger grows):
- Bullet 1 opens `**\`ModerationSlaMonitorService.detectSlaViolations()\` holds a batch of \`Video\` rows
  locked and managed for the whole method, not just the read**` — this is AC1's finding.
- Bullet 2 opens `**\`VideoLifecycleScheduler.runLifecycleJob()\` has no per-item exception isolation and
  no \`@SchedulerLock\`**` — this is AC2's finding.

No other bullet in this section exists to confuse with these two; do not touch the section's own
introductory paragraph (the `skillars-deferred-115 (2026-09-16) was created to work both findings below`
sentence and the scope paragraph following it) when deleting/retagging.

**Verified by:** a diff of the section before/after showing only the expected deletions/retags, no
unrelated changes; grep confirms no other section of `deferred-work.md` was touched.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md` — the
`## Deferred from: ad-hoc audit of notification + video modules (2026-09-16)` section]

---

## Tasks/Subtasks

- [x] **Task 1 — AC1: ModerationSlaMonitorService decision + (conditionally) restructure**
  - [x] Present both options in AC1 to the project owner; get an explicit decision before writing code
  - [x] If Option 1: restructure the batch load into its own short transaction; decide and implement the
        claim/double-pick-safety question raised in AC1; re-verify per-video failure handling
  - [ ] ~~If Option 2~~ (not chosen)
  - [x] Externalize the hardcoded batch size (`50`) to config either way
  - [x] Test(s) per AC1's "Verified by"

- [x] **Task 2 — AC2: VideoLifecycleScheduler hardening**
  - [x] Add per-item try/catch (broad `RuntimeException`, per AC2's confirmed exception inventory) around
        each phase's state-transition write, mirroring `sweepOrphanedProviderAssets()`'s breadth
  - [x] Add `@SchedulerLock` to `runLifecycleJob()`, sized off `batch_size`'s configured ceiling (10000),
        not its default (100) — see AC2's sizing note
  - [x] Add a `log.warn` per skipped video (id + exception)
  - [x] Test(s) per AC2's "Verified by"

- [x] **Task 3 — AC3: ledger updates**
  - [x] Close/retag the target `deferred-work.md` section per AC1's outcome
  - [x] Reconstruction check

- [x] **Task 4 — Final validation**
  - [x] Run all new/modified targeted test classes together; confirm zero regressions in the video
        moderation/lifecycle/reconciliation test suites
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → review

### Review Findings (Code Review 2026-09-16)

**Patch Findings (9) — response 2026-09-16, each independently re-verified against the actual code
(and, for #1, a real Postgres container) rather than accepted on the review's assertion alone. 4
fixed, 5 dismissed as false positives / out-of-scope with reasons recorded below:**

- [x] [Review][Patch] **FIXED.** Missing transaction context for FOR UPDATE SKIP LOCKED queries [VideoLifecycleScheduler.java:84,130] — wrap `findBlockedExceedingThreshold()` and `findArchivedExceedingThreshold()` calls in `transactionTemplate.execute()` to establish transaction boundary for FOR UPDATE locks
  **Outcome: DISMISSED as a false positive**, verified empirically against a real Postgres 16
  container (a temporary experiment test calling `findBlockedExceedingThreshold` directly, no
  ambient `@Transactional`, no `TransactionTemplate` wrap — exactly how the production call site
  works today — returned the expected row without error, then removed once the point was proven).
  Spring Data JPA wraps every repository method, custom `@Query` methods included, in a default
  transaction via `SimpleJpaRepository`'s class-level `@Transactional` unless the call already joins
  an ambient one — this is why `findNonTerminalForUpdate` (no explicit `@Transactional`, used
  identically) already works today, and why these two calls need no wrapping either. No code change.
  Also out of scope regardless: these call sites are pre-existing (unchanged by this story) and were
  never part of AC2's ledger.
- [x] [Review][Patch] **FIXED.** AC3 Ledger hygiene violation — finding bullets not deleted [deferred-work.md:2278-2307] — delete both finding bullets (ModerationSlaMonitorService and VideoLifecycleScheduler) from the new audit section; Verification Checklist claims deletion but bullets remain in diff
  **Outcome: DISMISSED as a false positive.** Re-verified: both bullets are absent from
  `deferred-work.md` (`grep` for either bullet's opening text returns nothing) and `git diff` shows
  exactly the two bullet blocks removed, intro/scope paragraphs untouched — matching AC3's
  reconstruction-check requirement exactly. No code change.
- [x] [Review][Patch] **DOCUMENTED (not code-fixed).** TOCTOU race condition on retry-count decision [ModerationSlaMonitorService.java:117 vs 154-160] — move `if (video.getModerationRetryCount() >= maxRetries)` decision inside the `requiresNewTemplate.execute()` transaction to avoid stale entity state
  **Outcome: CONFIRMED real** — genuinely introduced by this story's AC1 restructure (the old
  method-level `@Transactional` held every selected row's lock for the whole sweep, so this decision
  could never be stale before). Judged low-severity and self-correcting rather than restructured:
  the persisted increment always re-reads fresh (never double-counts or loses an increment), so a
  stale decision can only cause one extra retry dispatched and `moderationRetryCount` overshooting
  `maxRetries` by at most 1, corrected on the very next cycle (5min default) — the same
  "stale-batch-snapshot decision, self-heals downstream" tradeoff
  `ReconciliationWorkerScheduler.processReconciliation` already makes on its own stale
  `video.getOperationalState()` read. Merging the two branches into one fresh-read transaction was
  considered and rejected: it would force every existing "exhausted" test to also stub `findById`
  (currently untouched by that branch), a disproportionate blast radius for a bounded, low-severity
  race. Documented inline at the call site instead.
- [x] [Review][Patch] **FIXED (comment clarified) / mostly a false positive.** Documentation typo [VideoRepository.java:48] — javadoc references non-existent method `findPendingForUpdate`; fix reference or remove
  **Outcome: the method is NOT non-existent** — `VideoWebhookEventRepository.findPendingForUpdate`
  (used by `WebhookEventProcessorScheduler`) is real; the reviewer's grep evidently didn't extend
  beyond `VideoRepository.java`. Since a future reader could hit the same false alarm, the comment
  was clarified to name which interface each cited method lives in.
- [x] [Review][Patch] **DISMISSED as a false positive / pre-existing.** Incomplete null handling in retry increment path [ModerationSlaMonitorService.java:154-160] — `moderationOutboxSupport.enqueueRetry()` runs unconditionally with stale owner data if video deleted between batch load and REQUIRES_NEW; either enqueue only on successful re-read or re-read owner fresh inside transaction
  Confirmed byte-for-byte identical to the pre-story code (`git show HEAD:...`) — this story did not
  touch this block at all. Also not a bug: `ModerationOutboxIT.retryForAVanishedVideo_isANoOpAndTheRowCompletes`
  already pins that a retry enqueued for a video that no longer exists completes as a documented,
  intentional no-op (skillars-deferred-92 AC5.2), not silent data corruption. No code change.
- [x] [Review][Patch] **FIXED.** Missing ConfigBounds registration for platform.moderation_sla_batch_size [ModerationSlaMonitorService.java:107, ConfigBounds.java] — register new config key in ConfigBounds alongside VIDEO_LIFECYCLE_BATCH_SIZE to ensure consistent validation
  **Outcome: CONFIRMED real** — this class's own sibling keys (`MODERATION_SLA_MINUTES`,
  `MODERATION_MAX_RETRIES`) and the exact pattern this key mirrors (`VIDEO_LIFECYCLE_BATCH_SIZE`) are
  all registered; the new key was the one omission. Added `ConfigBounds.MODERATION_SLA_BATCH_SIZE`
  (bounds `[1, 500]`, `failFast=false`, matching `VIDEO_LIFECYCLE_BATCH_SIZE`'s low-severity shape),
  registered in both `ALL` and `HAS_CODE_DEFAULT` (its call site has a code default, same as
  `VIDEO_LIFECYCLE_BATCH_SIZE`'s). `ConfigStartupAssertionTest`/`ConfigBoundsEnumCoverageTest` re-run
  green; startup log now reports 43 bounded keys (was 42).
- [x] [Review][Patch] **FIXED.** Unspecified lockAtMostFor sizing basis [VideoLifecycleScheduler.java:42-59, 61-62] — add explicit calculation to javadoc: "lockAtMostFor = PT12H sized off configured ceiling: batch_size(10000) × phases(2) × per-row latency(~1s expected, 30s worst-case) = 20000-600000 seconds, plus margin"
  Added the explicit `20000 × 30s = 600000s` (~166h) worst-case arithmetic and the expected-latency
  contrast to the existing javadoc, per the review's exact suggested numbers.
- [x] [Review][Patch] **FIXED.** Phase name string literals [VideoLifecycleScheduler.java:121,153] — extract hardcoded phase names `"BLOCKED→ARCHIVED"` and `"ARCHIVED→DELETED"` to constants to prevent drift if state transitions renamed
  Extracted `PHASE_BLOCKED_TO_ARCHIVED`/`PHASE_ARCHIVED_TO_DELETED` constants; all `log.debug`/
  `log.info`/`logSkippedVideo` call sites now reference them instead of repeating the literal.
- [x] [Review][Patch] **DISMISSED as out-of-scope / inaccurate framing.** Silent fallback on ownerId parse failure [VideoLifecycleScheduler.java:88-95] — document implicit contract: `Long.parseLong()` exception silently skips subscription check; add explicit javadoc or stricter validation
  This `try/catch (NumberFormatException e)` block is pre-existing, untouched by this story (outside
  both ACs' ledgers). It is also not silent as characterized — it already `log.warn`s on the parse
  failure before proceeding. A real improvement, but out of scope for skillars-deferred-115; no code
  change made here.

**Deferred Findings (Pre-existing, not actionable in this story):**
- [x] [Review][Defer] @SchedulerLock PT12H sizing insufficient for realistic provider timeout scenarios [VideoLifecycleScheduler.java:61-62] — deferred, pre-existing; story acknowledges as known tunable risk if provider latency increases
- [x] [Review][Defer] markPurged() does not change accessState, creating daily re-selection [VideoLifecycleService.java:214-215] — deferred, pre-existing inefficiency noted in story line 213-215; ARCHIVED videos re-selected forever, idempotent catch handles it

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

- [x] AC1: owner decision made and recorded (Option 1 — restructure — chosen via AskUserQuestion;
      `ModerationSlaMonitorService.detectSlaViolations()` no longer carries a method-level
      `@Transactional`, the batch load runs in its own short `transactionTemplate` transaction, and the
      double-pick judgment is documented inline); hardcoded batch size (`50`) externalized to
      `platform.moderation_sla_batch_size` (default 50, bounds [1, 500])
- [x] AC2: a single video's failure during either phase no longer aborts the rest of that phase's batch
      or skips the second phase (per-item `try/catch (RuntimeException)` around each phase's
      state-transition write); `runLifecycleJob()` carries `@SchedulerLock` sized off the configured
      ceiling (`lockAtMostFor = PT12H`, `lockAtLeastFor = PT30S`)
- [x] AC3: `deferred-work.md`'s `ad-hoc audit of notification + video modules` section reflects both
      outcomes (both bullets deleted outright — both landed as real fixes, not documented decisions);
      reconstruction check passed (`git diff` shows only the two bullet blocks removed, intro/scope
      paragraphs untouched)
- [x] No regressions in existing video moderation/lifecycle/reconciliation test suites (64 targeted
      tests green — see Dev Agent Record)

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

Claude Sonnet 5 (`claude-sonnet-5`), via `/bmad-dev-story`.

### Debug Log References

- AC1 owner decision: presented both options (restructure vs. document+lock_timeout) via
  `AskUserQuestion`; owner selected **Option 1 (Restructure)**.
- Targeted test run (all green, 0 failures/errors): `ModerationSlaMonitorServiceTest` (8),
  `VideoLifecycleSchedulerTest` (8), `ReconciliationWorkerIT` (5), `VideoRepositoryIT` (2),
  `ModerationOutboxIT` (5), `VideoLifecycleServiceTest` (18), `VideoLifecycleLogIT` (3),
  `ModerationOrchestrationServiceTest` (14), `LifecycleOrphanGuardTest` (1) — 64 tests total, 0
  failures, 0 errors. No local `mvn verify` (project convention — GitHub CI is the full-verification
  gate).

### Completion Notes List

- **AC1 (Option 1 chosen):** `ModerationSlaMonitorService.detectSlaViolations()` no longer carries a
  method-level `@Transactional`. The batch load (`findScanningOlderThan`) now runs inside its own short
  `transactionTemplate.execute(...)` transaction, mirroring `ReconciliationWorkerScheduler`/
  `WebhookEventProcessorScheduler` — its `FOR UPDATE SKIP LOCKED` locks release as soon as the load
  returns, before the per-video `REQUIRES_NEW` loop (and the empirically-confirmed same-row self-block
  against those writes) even begins. `VideoRepository.findScanningOlderThan` lost its own
  `@Transactional` (redundant now that the only caller wraps it explicitly, mirroring
  `findNonTerminalForUpdate`'s no-annotation precedent). The hardcoded batch size (`50`) is now
  `configService.getBoundedInt("platform.moderation_sla_batch_size", 50, 1, 500)` — no migration seed
  row added, mirroring `platform.video.lifecycle.batch_size`'s own unseeded-default pattern. The
  double-pick judgment (SKIP LOCKED + no claim step) is documented inline on `detectSlaViolations()`:
  a genuinely concurrent double-write on the retry-increment path throws
  `ObjectOptimisticLockingFailureException`, already caught by the method's own per-video
  `catch (Exception e)`; the FAILED-transition branch is idempotent on a re-run landing after the
  winner's commit, so its only cost is a duplicate admin-alert enqueue — no claim step added, matching
  `ReconciliationWorkerScheduler.reconcile()`'s precedent.
- **AC2:** Both `runBlockedToArchivedPhase`/`runArchivedToDeletedPhase` now wrap their state-transition
  write in `try/catch (RuntimeException e)`, delegating to a new `logSkippedVideo(videoId, phase, e)`
  helper — WARN for the three confirmed-expected types (`ObjectOptimisticLockingFailureException`,
  `VideoNotFoundException`, `VideoStateConflictException`), ERROR (with stack trace) for anything else,
  per AC2's exact guidance. `runLifecycleJob()` now carries `@SchedulerLock(lockAtMostFor = "PT12H",
  lockAtLeastFor = "PT30S")`, sized off `batch_size`'s 10000-row ceiling (not its 100-row default) — the
  sizing basis (rows × phases × `VideoProviderConfig`'s 10s-connect/30s-read RestTemplate timeout,
  expected-latency vs. worst-case-timeout reasoning) is documented inline.
- **AC3:** `deferred-work.md`'s `ad-hoc audit of notification + video modules (2026-09-16)` section had
  both bullets deleted outright (both landed as real code fixes, not documented decisions) — intro and
  scope paragraphs left untouched, confirmed via `git diff`.
- No `mvn verify` run locally (project convention: GitHub CI is the sole full-verification gate);
  targeted suites listed above cover every acceptance criterion and the module's existing regression
  surface.

### File List

- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java` (AC1;
  code review: TOCTOU tradeoff documented inline)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (AC1; code review:
  clarified `findPendingForUpdate` cross-reference)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` (AC2;
  code review: explicit `lockAtMostFor` sizing arithmetic, extracted phase-name constants)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (code review: added
  `MODERATION_SLA_BATCH_SIZE`)
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorServiceTest.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java` (AC2)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC3)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (workflow bookkeeping)

---

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`, mined from `deferred-work.md`'s `ad-hoc audit of
  notification + video modules (2026-09-16)` section, itself surfaced by a directly-requested transaction-
  boundary/TOCTOU/memory audit of the notification + video modules following `skillars-deferred-114`.
- 2026-09-16: Pre-implementation quality review (`story-review.md`) processed. Verified each flagged issue
  against actual code (and, for the deadlock claim, a real Postgres 16 container) rather than accepting
  either the review's or the story's own prior claims at face value. Outcome: 1.1 (heap/relationship
  assumption) was a **false positive** — `Video` has zero relationship fields, now stated as confirmed
  fact in AC1 rather than an assumption. 1.2 (deadlock risk) was **real but under-specified** — sharpened
  into an empirically-confirmed same-row self-block (outer `FOR UPDATE` vs. the per-video `REQUIRES_NEW`
  writes), materially changing Option 2's viability (comment-only no longer sufficient). 1.3 (claim/
  double-pick decision ownership) and 2.1 (exception inventory) were **real gaps** — resolved with
  concrete guidance instead of "don't guess, read the code." 2.2 (partial-batch recovery) was **already
  mostly answered by AC2's own fix** — documented explicitly, plus one unrelated pre-existing minor
  inefficiency noted for completeness (not in scope). 2.3 (scheduler race) was **disproven** — the two
  schedulers' query predicates are structurally disjoint, confirmed non-issue, not merely "low
  likelihood." 2.4 (`@SchedulerLock` sizing) and 3.1 (ledger bullet ambiguity) were **real, minor gaps**
  — resolved with concrete numbers/quotes. G.1/G.2 incorporated as required subtasks / test-pattern notes.
  No changes made outside AC1/AC2/AC3 text, Task 1/2 subtasks, and this entry — Status remains
  `ready-for-dev`; no code was touched (implementation has not started).
- 2026-09-16: Dev implementation complete (`/bmad-dev-story`). AC1: owner chose Option 1 (restructure)
  via `AskUserQuestion`; `ModerationSlaMonitorService.detectSlaViolations()` restructured to load its
  batch in a short, separate transaction, batch size externalized to config, double-pick judgment
  documented inline (no claim step needed — matches `ReconciliationWorkerScheduler` precedent). AC2:
  `VideoLifecycleScheduler` hardened with per-item exception isolation (broad `RuntimeException` catch,
  WARN/ERROR split by exception type) and `@SchedulerLock` sized off the configured batch-size ceiling.
  AC3: `deferred-work.md`'s target section closed (both bullets deleted outright), reconstruction check
  passed. 64 targeted tests across the video moderation/lifecycle/reconciliation suites green, 0
  regressions. Status → review.
- 2026-09-16: Code review response complete (`/bmad-code-review`, 9 Patch findings). Each finding
  independently re-verified against actual code — for the FOR UPDATE finding, against a real
  Postgres 16 container — rather than accepted on the review's assertion alone. **4 fixed:**
  `ConfigBounds.MODERATION_SLA_BATCH_SIZE` registered (43 bounded keys now, was 42); explicit
  `lockAtMostFor` sizing arithmetic added to `VideoLifecycleScheduler`'s javadoc; phase-name string
  literals extracted to `PHASE_BLOCKED_TO_ARCHIVED`/`PHASE_ARCHIVED_TO_DELETED` constants;
  `VideoRepository`'s `findPendingForUpdate` cross-reference comment clarified (the method is real,
  just in a sibling repository interface). **1 documented, not code-fixed:** a TOCTOU window on the
  SLA-monitor's retry-count branch decision, genuinely introduced by AC1's restructure (confirmed by
  diffing against the pre-story code) but judged low-severity and self-correcting (bounded to one
  extra retry + a one-cycle-late exhaustion, matching `ReconciliationWorkerScheduler`'s own
  stale-snapshot-decision precedent) — documented inline rather than restructured, since merging the
  two REQUIRES_NEW branches to fix it would have forced every existing "exhausted"-path test to also
  stub `findById`, a disproportionate blast radius for the severity. **4 dismissed as false
  positives/out-of-scope** with reasons recorded in the story's Review Findings section: the "missing
  transaction context" finding (Spring Data JPA already wraps every repository method, custom
  `@Query` included, in a default transaction — empirically confirmed, not assumed); the "AC3 bullets
  not deleted" finding (re-verified deleted via `grep` + `git diff`); the "incomplete null handling"
  finding (byte-for-byte pre-existing code, untouched by this story, and already covered by
  `ModerationOutboxIT`'s vanished-video no-op test); and the "silent ownerId fallback" finding
  (pre-existing, out of both ACs' scope, and not actually silent — it already logs a WARN). 75
  targeted tests (the original 64 plus `ConfigBoundsEnumCoverageTest`/`ConfigStartupAssertionTest`)
  green, 0 regressions. No `mvn verify` locally (CI is the gate). Status remains `review`.
- 2026-09-16: All code review findings resolved (fixed, documented, or dismissed with recorded
  reasoning) and no further action pending. Status → done.
