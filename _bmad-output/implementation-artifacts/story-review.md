# Story Audit: Deferred-115 Scheduler Transaction Isolation Hardening

**Auditor:** Senior dev review  
**Date:** 2026-09-16  
**Story:** skillars-deferred-115-scheduler-transaction-isolation-hardening

---

## Summary

The story is well-scoped and grounded in real findings. **AC1 and AC2 are well-written, but three medium-severity gaps require clarification before implementation.** AC3 (ledger hygiene) has one minor vagueness. No false positives detected; all issues below are actionable.

---

## AC1: ModerationSlaMonitorService — Batch Lock/Entity-Lifetime Decision

### ✅ Strengths
- Correctly identifies the "outer transaction holds all locks" antipattern
- Both options are genuinely viable; appropriately punts to owner for business/risk tradeoff
- Correctly flags that the existing `REQUIRES_NEW`-per-video mitigation is intentional, not an oversight
- Correctly externalized the hardcoded `50` as a separate config task

### ⚠️ Issues

#### Issue 1.1: Heap assumption doesn't account for entity relationships
**Location:** Page 2, "at 50 rows...this will not exhaust heap"

The story assumes 50 `Video` rows fit in heap. **But**: does `Video` have `@OneToMany` collections (e.g., `List<VideoAsset>`, `List<PlaybackEvent>`) with eager loading or cascade fetch? If so:
- 50 videos × N related entities per video exceeds heap risk assessment
- Hibernate's dirty-checking on a managed persistence context scales with loaded entities, not just rows

**Recommendation**: Before presenting options to owner, verify `Video.java`'s relationships. If there are non-lazy collections, either:
- Mention this in Option 2's documented tradeoff, or
- Factor it into sizing the batch smaller if Option 1 is chosen

**Not a blocker**: Small heap impact is plausible, but assumption should be verified, not stated.

---

#### Issue 1.2: Deadlock risk not mentioned if nested per-video queries depend on locked batch
**Location:** Lines 80-88, 106-113 (the `requiresNewTemplate.execute(...)` calls)

The `REQUIRES_NEW` per-video transactions call back to `videoLifecycleService.*` methods. **Question**: Do those methods issue queries that touch `Video` rows or tables that the outer transaction is also holding locks on?

**Example scenario** (hypothetical deadlock):
1. Outer transaction locks 50 `Video` rows via `FOR UPDATE SKIP LOCKED`
2. Per-video REQUIRES_NEW tries to read from a related table that has a foreign-key constraint back to `Video`
3. Another writer attempts to update a locked `Video` row, waiting for the lock
4. The per-video query needs a lock on the related table that the other writer holds
5. Deadlock: outer transaction blocked on the other writer; per-video transaction blocked on the other writer's dependent lock

**Recommendation**: In the "Files to Read Before Implementation," add a check:
- Read `videoLifecycleService.archiveForLifecycle()` and `markPurged()` call chains
- Verify they do not query tables with FK constraints back to `Video`
- Verify they do not indirectly lock the same `Video` rows

**Likelihood**: Low in practice (good schema design prevents this), but worth a check.

---

#### Issue 1.3: Option 1's claim/double-pick strategy deferred without owner input
**Location:** AC1, Option 1, "decide whether this needs an explicit 'claim' step"

The story correctly identifies that removing the outer lock opens a double-pick race: a second pod could select the same row between the SELECT's commit and the per-row write. The story offers two sub-options:
- Add an explicit PENDING→PROCESSING write (like `WebhookEventProcessorScheduler`)
- Rely on state-machine validation (like `ReconciliationWorkerScheduler`)

**Problem**: This sub-decision is deferred to implementation time, but it's architectural: it changes whether per-video retries are safe, whether a manual recovery step exists, whether the state machine is a robust guard or just luck.

**Recommendation**: Clarify in Task 1 (or in the owner-presentation step) whether the developer should:
- Option A: Ask owner for a second sub-decision (claim step or not?), or
- Option B: Make this judgment call based on `Video`'s state machine robustness (recommend reading `Video.java` + state-transition code to decide)

Current text suggests Option A (owner decides), but it's implicit.

---

### ✅ Non-Issues (verified correct)
- "SKIP LOCKED means a second concurrent run could pick up already-claimed-but-not-yet-advanced row" — correctly identifies the race
- Batch size `50` is a hardcoded literal confirmed at the specified line
- Config externalization tied to `platform.video.reconciliation.batch-size` pattern is real and findable

---

## AC2: VideoLifecycleScheduler — Per-Item Failure Isolation + Cluster-Wide Mutual Exclusion

### ✅ Strengths
- Correctly identifies that optimistic-lock exceptions propagate out of the `for` loop
- Correctly notes that phase 2 is skipped if phase 1 fails (real impact, no recovery until next day)
- `@SchedulerLock` remedy is well-grounded in `EmailRetryScheduler` precedent
- Recommended approach (per-item try/catch + `@SchedulerLock`) mirrors working pattern in same module

### ⚠️ Issues

#### Issue 2.1: "Any other expected/recoverable failure" is too vague
**Location:** AC2, "catch (ObjectOptimisticLockingFailureException and any other expected/recoverable failure)"

The story says "don't guess; read the methods," which is correct advice, but doesn't list what those exceptions are. **Problem**: The developer must:
1. Read `videoLifecycleService.archiveForLifecycle()` and `markPurged()`
2. Trace their callee chains (`quotaService` calls, etc.)
3. Decide which exceptions are "expected/recoverable" (should skip and continue) vs. "unexpected" (should propagate and abort)

If the developer guesses wrong, a skipped exception could mask a genuine bug.

**Recommendation**: Add to "Files to Read Before Implementation" or Task 2:
- Read `VideoLifecycleService.archiveForLifecycle()` and `markPurged()` completely
- **List the exception types** they throw (don't just say "check it")
- Document expected ones in the method's javadoc or task notes before writing the catch clause

**Example**: The task should say something like:
> "Confirm that `archiveForLifecycle` throws only `OptimisticLockingFailureException`, `VideoNotFoundException`, and `IllegalStateException`. Only catch the recoverable ones (the first two); allow `IllegalStateException` to propagate."

---

#### Issue 2.2: Partially-processed batch state recovery is not specified
**Location:** AC2, "the recovery window for a dropped batch is a full day"

The story correctly notes a full-day gap between ticks, but doesn't clarify:
- **If phase 1 partially succeeds** (videos 1–30 move to ARCHIVED, but video 31 throws an exception):
  - Videos 32–100 remain in BLOCKED state (not processed)
  - What state should the ledger record to ensure videos 1–30 and 32–100 are both picked up tomorrow?
  - **Is there a risk that videos 1–30 are double-processed** if phase 2 runs on them before the next day's phase 1 re-runs?

- **If this is the intended behavior**: the story should say so explicitly (e.g., "re-running phase 1 tomorrow will re-process the entire batch, including already-archived videos; they are idempotent").
- **If this is not safe**: the story should recommend a "claim" step (similar to Issue 1.3) or a checkpoint.

**Recommendation**: Clarify the partially-processed recovery guarantee:
> "If phase 1 processes videos 1–30 successfully then fails on video 31, tomorrow's run will re-process all 100. Videos 1–30 will be re-checked and idempotently re-archived (or skipped if already archived). This is safe because `videoLifecycleService.archiveForLifecycle()` is idempotent."

(Or specify that it's NOT safe and recommend a different strategy.)

---

#### Issue 2.3: Potential race between VideoLifecycleScheduler and ReconciliationWorkerScheduler not mentioned
**Location:** AC2, references `ReconciliationWorkerScheduler` but doesn't discuss interactions

Both `VideoLifecycleScheduler` and `ReconciliationWorkerScheduler` perform state-transition writes to `Video`. **Question**: Can they race on the same video?

**Example race**:
1. `VideoLifecycleScheduler.runBlockedToArchivedPhase()` tries to archive video #123
2. Concurrently, `ReconciliationWorkerScheduler.reconcile()` tries to clean up the same video
3. One throws `OptimisticLockingFailureException`; the story says skip it
4. **Result**: Video #123 is neither archived nor cleaned up; it's stuck

The story doesn't address:
- Do these schedulers run at different times (no overlap risk)?
- Is the optimistic lock sufficient to ensure one succeeds and the other fails safely?
- Should there be an ordering guarantee (lifecycle before reconciliation, or vice versa)?

**Likelihood**: Low (different jobs, different schedules), but worth verifying.

**Recommendation**: Check `VideoLifecycleScheduler`'s cron schedule (`cron = "0 0 3 * * *"` mentioned in the story) and `ReconciliationWorkerScheduler`'s schedule. If they could overlap, add a note about the expected behavior (one aborts, the next tick cleans up, etc.).

---

#### Issue 2.4: @SchedulerLock sizing guidance is vague
**Location:** AC2, "size generously, since the consequence of... mid-run... is a genuine double-archive/double-delete"

The story says "size generously" but doesn't give concrete guidance:
- What is "generous"? 2× the expected duration? 10×?
- The cron runs "once/day" (`0 0 3 * * *`), but how long does a single run take?
  - A 100-video batch with 2 phases could take seconds to minutes depending on asset cleanup latency
- What are `EmailRetryScheduler`'s actual `lockAtMostFor`/`lockAtLeastFor` values?

**Recommendation**: Task 2 should include a sub-step:
> "Check `EmailRetryScheduler.java:86-131` for its `@SchedulerLock` values and sizing rationale. Size `VideoLifecycleScheduler`'s lock to cover 2 phases × 100 rows, plus a safety margin. Document the sizing choice in a comment."

**Example bounds** (not part of this story, but illustrative):
- If a single `archiveAsset` call takes ~100ms and phase 1 processes 100 videos: ~10s baseline
- Add phase 2 and variance: 30s plausible
- Set `lockAtMostFor = "PT2M"` (2 minutes) to be safe

---

### ✅ Non-Issues (verified correct)
- `ObjectOptimisticLockingFailureException` is the right exception to catch (confirmed by `@Version` annotation on `Video`)
- Full-day recovery window is correctly calculated (`cron = "0 0 3 * * *"` = once daily)
- `@SchedulerLock` precedent in `EmailRetryScheduler` is real and appropriate

---

## AC3: Ledger Hygiene

### ⚠️ Issue

#### Issue 3.1: Exact lines to edit in deferred-work.md are not specified
**Location:** AC3, "update that section per this file's own stated convention"

The story says to delete bullets if closed by a real fix, or retag them as `[DECIDED <date> (skillars-deferred-115): ...]` if AC1 lands as Option 2. **But**: The story doesn't quote the exact bullets from `deferred-work.md` that should be edited.

The story references `_bmad-output/implementation-artifacts/deferred-work.md` but the developer must hunt for the `## Deferred from: ad-hoc audit of notification + video modules (2026-09-16)` section and find the two bullets (presumably listing `ModerationSlaMonitorService` and `VideoLifecycleScheduler` findings) themselves.

**Risk**: The developer might:
- Edit the wrong section (if there are multiple audits on 2026-09-16)
- Miss a bullet that should be deleted
- Accidentally edit surrounding context

**Recommendation**: Quote the exact bullets from `deferred-work.md` in AC3, or add a task step:
> "Read `_bmad-output/implementation-artifacts/deferred-work.md:line-number` (the `## Deferred from: ad-hoc audit...` section). Locate the two bullets for `ModerationSlaMonitorService` and `VideoLifecycleScheduler`. Delete/retag per AC1's outcome."

**Workaround for dev**: The story does say "Reconstruction check: every surviving line in the target section must match the pre-edit content, in order, with only the specified deletions/retags applied." This catches mistakes, but it's a check, not a prevention.

---

### ✅ Non-Issues
- Retag format `[DECIDED <date> (skillars-deferred-115): ...]` is clear
- "Reconstruction check" approach (diff before/after) is sound

---

## General Issues (Cross-Cutting)

### Issue G.1: Logging/alerting strategy for skipped rows not mentioned
**Location:** AC2 implementation, but not in story text

After the fix, per-item failures are silently skipped (logged as `continue` in the for loop). **Questions for implementation**:
- Should each skipped video emit a `log.warn()`?
- Should there be a metric (e.g., `lifecycle.scheduler.skipped.count`)?
- What should on-call see in logs to realize a batch partially failed?

**Recommendation**: Add a note in AC2 Task 2:
> "Decide logging level for skipped videos (recommend `log.warn("Skipped video {}: {}", videoId, exception.getMessage())`). Verify that logging exists before marking complete."

**Not a blocker**: This is implementation taste, but worth calling out explicitly.

---

### Issue G.2: Testing pattern for "concurrent access not blocked" (AC1, Option 1) is complex
**Location:** AC1, "Verified by: A concurrent-access test showing a second reader/writer is not blocked once the load completes"

This test is non-trivial:
- Must run two threads/pods concurrently
- First thread does SELECT FOR UPDATE SKIP LOCKED (in its own short transaction)
- Second thread tries to write the same row (must block, then unblock once first thread commits)
- Third thread tries to write *after* SELECT commits but *before* per-row processing starts (should succeed if load is in its own transaction, should block if not)

**Recommendation**: If Option 1 is chosen, check if `ReconciliationWorkerScheduler` or `WebhookEventProcessorScheduler` already have a test for this pattern. If yes, extend it; if no, recommend a simpler test (e.g., mock the TransactionTemplate and assert it's called twice, not once).

**Not a blocker**: The story correctly identifies the need; implementation can figure out the test harness.

---

### Issue G.3: File List and Change Log are empty
**Location:** Story template sections

Expected for a ready-for-dev story. Not an issue, just noting: the story should remind the developer to fill these in at completion time.

**Current state**: Correct (empty is OK for ready-for-dev).

---

## False Positives Checked (and Rejected)

### ❌ "Option 1's restructure might regress per-video failure handling"
- The story says "re-verify per-video failure handling" — this is a good reminder, not a missing issue
- Existing tests should catch this; no new risk introduced

### ❌ "VideoLifecycleScheduler batch size (default 100) is too large"
- The story mentions "up to `platform.video.lifecycle.batch_size` (default 100, ceiling 10000)"
- No issue here; it's configurable and sized appropriately for a once-daily job

### ❌ "Shedlock table doesn't exist"
- The story says "@SchedulerLock (`net.javacrumbs.shedlock.spring.annotation.SchedulerLock`) is already a project dependency"
- If the dependency exists and 4 other schedulers use `@SchedulerLock`, the shedlock table and migrations are already in place
- No new risk

---

## Summary Table

| Issue | Severity | Type | Recommendation |
|-------|----------|------|-----------------|
| 1.1: Heap assumption + entity relationships | Medium | Assumption gap | Verify Video.java relationships before presenting options to owner |
| 1.2: Deadlock risk in nested per-video queries | Low | Missed flow | Check videoLifecycleService call chains for FK-back queries |
| 1.3: Option 1's claim/double-pick sub-decision deferred | Medium | Scope ambiguity | Clarify whether owner also decides claim strategy, or dev does |
| 2.1: Exception types not specified | Medium | Assumption gap | Read and list exceptions from videoLifecycleService methods; don't guess in catch clause |
| 2.2: Partially-processed batch recovery guarantee missing | Medium | Missed flow | Clarify idempotency guarantee for phase 1 re-runs and phase 2 double-processing risk |
| 2.3: Race between Lifecycle and Reconciliation schedulers | Low | Missed flow | Check cron schedules; verify no overlapping state-transition races |
| 2.4: @SchedulerLock sizing guidance vague | Low | Implementation detail | Reference EmailRetryScheduler's actual sizing; add sizing comment to code |
| 3.1: Exact deferred-work.md lines not specified | Low | Process clarity | Quote the bullets to delete/retag, or add line numbers |
| G.1: Logging strategy for skipped rows | Low | Implementation detail | Decide log level (recommend warn); document before completion |
| G.2: Concurrent-access test harness complexity | Low | Testing risk | Check existing patterns first; adapt rather than invent |

---

## Recommendation

**Proceed with implementation**, with these before-dev steps:

1. **Read `Video.java` and `VideoLifecycleService` in full** (already in story's file list, but emphasis them)
2. **Resolve Issue 1.1** (heap + relationships) before owner presentation
3. **Clarify Issue 1.3** (claim/double-pick decision ownership) in Task 1
4. **Document Issue 2.1** (exception list) as a sub-step of Task 2
5. **Verify Issue 2.2** (recovery guarantee) by reading Video's state machine; add a note to Task 2 about idempotency confirmation
6. **Quick check Issue 2.3** (Lifecycle vs. Reconciliation race) — likely safe, but a 30-second grep for cron schedules is worth it

**Non-blocking notes**:
- Issues 2.4, 3.1, G.1, G.2 are implementation details or process clarity — not false positives, just nice-to-clarify before starting

---

## Conclusion

This is a well-scoped, grounded story. The two main issues (AC1 options, AC2 failure handling) are correctly identified and remedied. **No red flags; three medium-severity assumptions need verification, but none are blockers.** Estimated 1–2 hours to address these before development begins.
