# Audit Review: skillars-deferred-116 Story

**Reviewer:** Senior Engineer Audit  
**Date:** 2026-09-16  
**Status:** FINDINGS IDENTIFIED — see below for actionable clarifications needed before dev starts

---

## Executive Summary

The story correctly identifies a real bug class (batch-level transaction isolation) and proposes the right pattern (per-item transacted processing + try/catch), mirrored from an established in-module sibling. **However, several corner cases and implementation ambiguities need clarification before dev work starts**, particularly around data consistency assumptions, transaction boundaries for side effects, and testing coverage gaps.

---

## Critical Issues (Dev-Blocking)

### 1. **AC1 & AC2: Event Publishing & Side Effect Isolation**

**Issue:** The story does not clarify how `SubscriptionExpiredEvent` logging and `syncMarketplaceTier`'s side effects (marketplace tier creation) interact with per-item transaction boundaries.

**Current assumption:** Implicitly assumes both are fired/committed inside the per-item transaction.

**Why this matters:** If `SubscriptionExpiredEvent` is published synchronously via `ApplicationEventPublisher`, it fires within the transaction and is rolled back if the transaction fails — this is safe. But if any listener tries to access the newly-applied change via a separate lookup (e.g., "fetch the change I just applied"), it will fail because the transaction hasn't committed yet in the listener's context. More concretely: if another component is observing `SubscriptionExpiredEvent` and assumes the change is already committed to the DB, per-item isolation breaks that assumption.

**Fix required before dev:** 
- Confirm `syncMarketplaceTier` is idempotent and safe to retry (the story mentions it creates a new `CoachSubscription` if none exists — what if a concurrent write created one between the initial read and the per-item transaction write?). 
- Confirm `SubscriptionExpiredEvent` listeners do not assume transactional consistency across the change AND any marketplace effects.
- Document in the story whether events are fired inside or outside the per-item transaction, and justify the choice.

---

### 2. **AC2: Concurrent Modification Race Between Read & Write**

**Issue:** AC2 acknowledges concurrent modifications are possible (webhook-driven status updates mid-sweep) and calls out optimistic-lock failures, but does not address a critical case.

**Scenario:** The batch read filters on `status = 'PAST_DUE'` and `dueDate < cutoffTime`. Between the read and the per-item downgrade write:
- A concurrent webhook updates `dueDate` (e.g., customer made a payment, extending the due date) or changes `status` to something other than `PAST_DUE`.
- The per-item transaction throws an optimistic-lock error (version mismatch) or the write silently succeeds but leaves the row in an inconsistent state.

**Why this matters:** The story assumes "a row that failed to downgrade is still `PAST_DUE` and still past the cutoff, so it stays eligible with no extra bookkeeping needed" — but this breaks if the concurrent update changed the filtering criteria (e.g., `dueDate` now in the future). The row would be excluded from tomorrow's run, creating a gap.

**Fix required before dev:**
- Read `PastDueGracePeriodTest.java` to understand the expected concurrency model. Is optimistic locking the intended defense? If so, what version field is used?
- Clarify: If a row's `dueDate` is updated concurrently to after the cutoff, is it acceptable for that row to be skipped indefinitely (no longer `PAST_DUE`, no longer retried)?
- If the answer is "yes, that's fine because a payment landed and extended the due date," explicitly document this assumption in AC2. If "no, we need to handle this," add a task to the story.

---

### 3. **AC1: Applied Flag Re-Selection Assumption Not Verified**

**Issue:** AC1 assumes leaving `applied = false` on failure is sufficient for re-selection "once the underlying data issue is fixed." But there's no verification that the batch read query actually filters on `applied = false` or that someone will fix the underlying data issue.

**Why this matters:** If the batch query is something like `findPendingForScheduler(now)` and `pending` is defined by a composite condition (e.g., `applied = false AND status = 'PENDING'`), then:
- If a malformed `to_tier` row is left with `applied = false`, will it be re-selected tomorrow? Only if its `status` is still `PENDING`.
- What if the data issue is never fixed (e.g., a legacy row with an invalid tier that was never supposed to exist)? It would be re-selected forever, log errors forever, and clutter error streams.

**Fix required before dev:**
- Read the actual repository method `coachSubscriptionChangeRepository.findPendingForScheduler(now)` and its database query. Verify the query selects on `applied = false` (or equivalent).
- Confirm whether indefinite retry of malformed data is acceptable or if there should be a retry-limit + alert mechanism.
- If indefinite retry is unacceptable, document in the story that this is a known limitation and propose a follow-up task (e.g., add a `retry_count` column, skip after N failures).

---

## High Priority Issues (Should Clarify Before Dev)

### 4. **AC3: `lockAtMostFor` Sizing Not Grounded**

**Issue:** AC3 says "Size `lockAtMostFor` generously relative to realistic batch sizes" but provides no concrete baseline.

**Current assumption:** "Generous" means something like 15 minutes (by analogy to `SessionPackForfeitureScheduler`).

**Why this matters:** 
- If a run takes 14 minutes today but data grows 10x in the next quarter, the same `PT15M` lock might not be "generous" anymore, and the method could silently start overlapping runs.
- Conversely, `PT15M` might be overkill if typical runs are under 30 seconds, wasting lock resources.
- The story explicitly notes "there is no config-bound batch-size ceiling on either query today" as a "known gap" but chooses not to add one. This is a reasonable scope decision, but it means `lockAtMostFor` is a fragile proxy for "max realistic runtime."

**Fix required before dev:**
- Query the production DB to estimate actual batch sizes at today's scale: how many pending coach changes, pending player changes, and past-due subscriptions are there on a typical day?
- Calculate realistic runtimes based on measured query performance and processing time per item.
- Document the sizing basis in code comments (exact format shown in the story: "This runs daily at 02:00 UTC, typically processing ~N items in ~M seconds under current load; PT15M is 10x the expected max. If batch sizes exceed X items, revisit this value.").
- Consider adding a TODO comment flagging the missing batch-size ceiling as a follow-up.

---

### 5. **AC1 & AC2: Testing Coverage Gaps**

**Issue:** Test verification sections specify "at least one valid coach change and at least one valid player change" but don't enumerate all failure scenarios.

**Current assumptions:**
- Testing when coach fails, then player succeeds → both loops function independently ✓
- Testing when player fails, then coach succeeds → covered by symmetry ✓
- Testing when BOTH fail in the same run → **NOT explicitly mentioned**

**Why this matters:** If the first coach change fails and causes an early return before processing players, the test would pass but the implementation would be wrong. The story doesn't say to test both failing simultaneously.

**Fix required before dev:**
- Expand the verification section to explicitly list test matrix:
  - Valid coach + valid player (baseline, should both apply)
  - Invalid coach + valid player (coach fails, player still applies)
  - Valid coach + invalid player (player fails, coach still applies)
  - Invalid coach + invalid player (both fail, both stay unapplied/eligible for retry)
- Similarly for AC2: if the coach downgrade throws but player hasn't been processed yet, confirm player still processes.

---

### 6. **AC1: `syncMarketplaceTier` Side Effects Under Per-Item Isolation**

**Issue:** `syncMarketplaceTier` is called inside the `for` loop and creates a new `CoachSubscription` row if one doesn't exist. The story says "keep working identically inside the new per-item transaction" but doesn't address a race.

**Scenario:**
- Thread A reads pending coach change for Coach #1.
- Thread A calls `syncMarketplaceTier(Coach#1, ATHLETE)` → no `CoachSubscription` exists, so it creates one.
- Before Thread A commits, Thread B (a concurrent API request or another scheduler instance) also tries to create a `CoachSubscription` for Coach #1.
- One of them gets a unique constraint violation.

**Current assumption:** The `@SchedulerLock` in AC3 prevents two scheduler instances from running simultaneously, so Thread B's write is delayed. But within the same scheduler instance, per-item `transactionTemplate.execute(...)` creates new DB connections, which means isolation level matters.

**Why this matters:** If isolation level is `READ_COMMITTED`, Thread A's uncommitted `INSERT` is not visible to Thread B inside a separate transaction, and if Thread B also tries to insert, both might try to create the same row.

**Fix required before dev:**
- Verify `transactionTemplate.execute(...)` uses the same isolation level as `@Transactional` (which defaults to `READ_COMMITTED`).
- If `syncMarketplaceTier` uses an explicit `INSERT ... ON CONFLICT DO UPDATE` (upsert) pattern, this is safe. Confirm this by reading the implementation.
- If it uses a read-then-insert pattern, document the assumption that within a single scheduler instance, no two per-item transactions will concurrently try to insert the same `CoachSubscription` (true due to single-threaded scheduler, but worth stating).

---

## Medium Priority Issues (Good to Clarify)

### 7. **AC1 & AC2: Initial Batch Read Not Protected**

**Issue:** The batch read (`coachSubscriptionChangeRepository.findPendingForScheduler(now)`) is the first thing that happens. If it fails (DB connection timeout, disk full, etc.), the entire method fails and is caught by Spring's default `LoggingErrorHandler`.

**Why this matters:** The story doesn't discuss whether the batch read itself should be wrapped in try/catch or if it's acceptable to fail the entire run. For other schedulers (e.g., `SessionPackForfeitureScheduler`), this is probably fine because the read is fast. But if the pending-changes query is expensive, should it have a timeout?

**Clarification:** This is probably not an issue, and the default behavior is fine. But the story doesn't mention it, leaving it ambiguous.

---

### 8. **AC2: Missing Downgrade Logic Clarity**

**Issue:** AC2 describes downgrading coaches to `SCOUT`/`CANCELLED` based on status, but the story doesn't specify which status gets which tier.

**Current assumption:** Implicitly, the code determines which tier based on the subscription's current status. But is this in the DB row itself, or is there a state machine?

**Why this matters:** Knowing whether the tier is determined by a column or a function affects what gets tested. If it's a function, the test should verify the function logic is called correctly.

**Clarification:** Read `checkPastDueGracePeriod()` in `SubscriptionService.java` to see the actual logic. The story should mention this explicitly in AC2's "Files to Read Before Implementation" section (it does mention the method, but not which part of the code determines the tier).

---

### 9. **AC3: `lockAtLeastFor` Timing Assumption**

**Issue:** AC3 proposes `lockAtLeastFor = "PT2M"`, mirroring `SessionPackForfeitureScheduler`. But the scheduler runs daily (`0 0 2 * * *` and `0 0 3 * * *`), so why is a 2-minute minimum lock needed?

**Current assumption:** The 2-minute minimum is to prevent back-to-back lock acquisitions if the method completes very quickly, preventing thrashing. But on a daily scheduler, this seems overly conservative.

**Why this matters:** If `lockAtLeastFor = PT2M` and the method completes in 5 seconds, the lock is held for 2 minutes anyway, delaying potential re-runs (though re-runs shouldn't happen for 24 hours anyway on a daily job). This might be an over-engineering, or it might be a defensive measure for future changes.

**Clarification:** The story should justify why `PT2M` is appropriate for a daily job. If it's just "follow the pattern for consistency," say so. If it's defensive, document that.

---

## Low Priority Issues (FYI / Nice to Have)

### 10. **AC4: Ledger Hygiene Not Fully Specified**

**Issue:** AC4 says to delete the three bullets from `deferred-work.md` but doesn't specify exactly which section or what "reconstruction check" means precisely.

**Clarification:** The story does reference the section name (`## Deferred from: ad-hoc audit of payment module subscription schedulers (2026-09-16)`), so this is clear enough. The "reconstruction check" is "diff before/after shows only deletions, no unrelated changes" — reasonable.

**Recommendation:** No action needed; this is a style point.

---

### 11. **AC1 & AC2: Logging Granularity**

**Issue:** The fix says to `log.error(...)` with the change's id and the exception, but doesn't specify the log level or message format.

**Why this matters:** If you log a hundred errors per day, ops might set the log level to WARN or ERROR to filter noise. But then you miss the change id in structured logging.

**Recommendation:** Before dev, add a note to the story about structured logging format (e.g., "log.error('Failed to apply pending change: {}', changeId, exception)") to match the project's logging conventions.

---

### 12. **Missing Idempotency Confirmation for `applyPendingChanges`**

**Issue:** AC3 acknowledges that applying the same `toTier` twice is a "no-op in outcome" but notes the `change.setApplied(true)` write and event logging would race.

**Why this matters:** This implies idempotency is "good enough," but `@SchedulerLock` is meant to prevent that. Once `@SchedulerLock` is added, the race goes away. But what if someone later removes `@SchedulerLock` by accident? The idempotency check becomes important as a defense-in-depth.

**Recommendation:** Add a note to the story: "Idempotency is achieved via the tier change itself being a no-op + `@SchedulerLock` preventing concurrent runs. If `@SchedulerLock` is ever removed or misconfigured, idempotency of the tier application is the last line of defense."

---

## Questions for the Developer to Resolve During Implementation

These are questions the dev should investigate and document answers in the code:

1. **Does `SubscriptionExpiredEvent` get published inside the per-item transaction, or can it leak?** If it leaks, do listeners assume transactional consistency?

2. **What is the actual transaction isolation level for `transactionTemplate.execute(...)`?** Confirm it's `READ_COMMITTED` and safe for concurrent writes.

3. **If a pending change's `to_tier` is malformed (never fixed), will it be retried forever?** Is this acceptable, or should there be a retry limit?

4. **What are the actual batch sizes at current scale?** Measure and use real numbers to justify `lockAtMostFor = PT15M`.

5. **Does `syncMarketplaceTier` use an upsert pattern, or could concurrent writes to `CoachSubscription` fail?** Read the implementation and document your findings.

6. **What determines whether a past-due coach is downgraded to `SCOUT` vs `CANCELLED`?** Is it a column, enum, or state machine?

---

## Recommendation

**This story is ready for dev to start, subject to clarifications above.** The three main issues (event/side-effect isolation, concurrent race in AC2, testing coverage) should be resolved during implementation and documented via code comments or tests. The dev should:

1. Before touching code: Read `SessionPackForfeitureScheduler` in full and confirm its pattern handles all edge cases the story relies on.
2. During implementation: Resolve questions 1–6 above and leave breadcrumbs in comments.
3. During testing: Execute the full test matrix (AC1: coach-only-fail, player-only-fail, both-fail; AC2: coach-only-fail, player-only-fail, both-fail).
4. Before review: Verify `@SchedulerLock` sizing with real batch-size data.

---

## Sign-Off

No false positives identified. The story's core diagnosis (batch-level isolation bug) is correct, and the proposed fix pattern is proven (already in use by 5 other schedulers). The issues flagged above are clarifications, not blockers.

**Approved for dev with noted caveats.**
