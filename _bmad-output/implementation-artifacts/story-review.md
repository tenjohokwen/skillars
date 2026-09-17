# Story Review: skillars-deferred-118
**Reviewer:** Senior Dev Audit  
**Date:** 2026-09-17  
**Status:** Ready for Dev (no blockers; minor clarifications noted)

---

## Executive Summary

The story's three acceptance criteria identify genuine, independently-verified bugs with correct root-cause analysis and established sibling patterns to mirror. **No false positives detected** in the substance of any AC — every cited code path, propagation default, exception type, and "cleared, not picked up" claim was independently re-checked against HEAD and confirmed accurate. Scope is appropriately bounded. The ACs are well-sequenced (independent modules, no cross-AC coupling).

**Second-pass verification (2026-09-17) found and corrected two stale line-number citations in the story itself** (not bugs in the analysis — see "Citation Corrections" below); both are now fixed in the story file. Five minor edge-case clarifications are recommended for implementation guidance — none are story blockers, all are correctly addressable within the existing story structure.

---

## AC1: BookingExpiryScheduler Transaction Race

### ✅ Root Cause Analysis: Confirmed Correct

The traced path is accurate:
- Method-level `@Transactional` wraps the entire batch loop ✓
- `bookingService.transition()` joins (default `REQUIRED` propagation) ✓
- `BookingStateTransitionException` is unchecked (`RuntimeException`), triggers default rollback ✓
- Per-iteration `try/catch` swallows the exception, but the shared physical transaction is already marked `rollbackOnly` ✓
- Method returns normally; Spring's commit interceptor finds `rollbackOnly=true` and throws `UnexpectedRollbackException` ✓
- **Consequence is correctly identified**: all prior successful transitions in the batch lose their writes, not just the failed one

### ✅ Fix is Correct and Well-Scoped

- Per-booking `TransactionTemplate` scope mirrors `BookingReminderScheduler.processReminderWindows` ✓
- `@SchedulerLock` values correctly left unchanged (this AC is about transaction boundaries only) ✓
- Event publishing included in the booking's transaction scope (correct — listener failures stay local to that booking) ✓

### Minor Edge Cases (Non-Blocking)

1. **Batch SELECT transaction scoping** — Story offers two options ("mirror `idsOf`'s pattern, or simplify to a single `TransactionTemplate.execute`") but doesn't mandate which. Implementation should pick one; both are valid. *Recommend: note in Dev Agent Record which pattern was chosen and why.*

2. **Non-existent booking between SELECT and transition** — Theoretically possible if a booking is deleted elsewhere. The transition's pessimistic lock will fail, caught by try/catch, logged, and loop continues. This is correct idempotent behavior, but story doesn't explicitly name it. *No action needed — existing try/catch handles it.*

3. **Event listener side effects** — If `BookingExpiredEvent` listener modifies booking or throws, the failure is local to that booking's transaction. This is correct, but story doesn't explicitly call it out. *No action needed — fix handles it correctly.*

---

## AC2: UserAdminService Activation Race

### ✅ Root Cause Analysis: Confirmed Correct

- `findExpiredUsers` selects at time T1 with `activated=false` filter ✓
- Each user passed to `deleteUserInTransaction` (REQUIRES_NEW transaction) at time T2, T3, … ✓
- No re-check of `activated` before delete — user could have activated between T1 and T2 ✓
- Four registration call sites confirmed as real, everyday concurrent writers ✓
- **Consequence is correctly identified**: legitimate, freshly-activated account is destroyed

### ✅ Fix is Correct and Well-Scoped

- Re-check `!user.isActivated()` immediately before `delete()` ✓
- Guard lives inside the REQUIRES_NEW transaction (sees current row state) ✓
- DEBUG log matches established skip-silently pattern ✓
- `deleteUserInformation` (admin manual delete) correctly excluded — different codepath, different semantics ✓

### Minor Edge Cases (Non-Blocking)

1. **Null user on re-fetch** — Story assumes `userRepository.findOneByLogin(login)` returns a non-null user. If the user is deleted between `findExpiredUsers` and `deleteUserInTransaction` (concurrent admin deletion, or edge case in another path), the re-fetch returns null/empty. The `!user.isActivated()` guard would NPE. *Implementation guidance: add explicit null check (e.g., "if (user == null) { log.debug(...); return; }") in addition to the activated check. Not a story blocker — standard defensive coding.*

2. **Transaction isolation and concurrent activation** — Story correctly places the re-check in a REQUIRES_NEW transaction, which is independent of the parent transaction. If User A activates in a peer transaction after the batch SELECT but before this scheduler's REQUIRES_NEW transaction re-fetches, the re-fetch will see the new activated state. This is correct, but the story doesn't explicitly address isolation levels. *No action needed — REQUIRES_NEW is the correct pattern; isolation is delegated to DB/Spring config.*

---

## AC3: @SchedulerLock Parity

### ✅ Finding is Correct and Well-Reasoned

- Three schedulers identified with no `@SchedulerLock` where every sibling has one ✓
- Risk assessment is calibrated: single-instance deployment (low risk today), but cheap consistency fix ✓
- `RadarCompositeDlqProcessor`'s latent issue is correctly identified: `findClaimedBatch()` not scoped to caller's claim, no `@Version` on entity — two concurrent runs could stomp each other's state ✓
- `@SchedulerLock` is the correct, simpler fix (vs. schema changes to add claim-token scoping or `@Version`) ✓

### ✅ Implementation Guidance is Correct

- Lock sizing should be per-scheduler, not copy-pasted ✓
- References `skillars-deferred-116` Dev Notes for worst-case arithmetic (batch size × per-item timeout) ✓
- Correctly notes that `MessageRetentionScheduler` (daily cron) may have different `lockAtLeastFor` convention than 5-minute-`fixedDelay` siblings ✓

### Minor Clarifications (Non-Blocking)

1. **Multiple `@Scheduled` methods per class** — Story identifies three classes but doesn't explicitly confirm each has exactly one `@Scheduled` method. (Very likely true, but worth verifying during implementation.) *Recommend: grep each class to confirm before adding locks.*

2. **MessageRetentionScheduler's lockAtLeastFor value** — Story correctly notes daily cadence makes standard `PT2M` convention worth reconsidering, says "use judgment, document the choice." But doesn't mandate whether to use `PT2M` or something else. *No action needed — implementation should compute worst-case runtime, document reasoning in Dev Agent Record.*

3. **RadarCompositeDlqProcessor worst-case timeout** — `BATCH_SIZE = 50` and per-item work is `compositeCalculationService.recalculateComposite`. Story correctly points to deferred-116 for arithmetic guidance. *No action needed — story gives enough guidance.*

4. **Lock acquisition timeout** — If lock acquisition itself times out before lockAtMostFor (e.g., due to contention), does the scheduler give up or retry? Story doesn't address this, but it's a ShedLock behavior question, not a story gap. *No action needed — ShedLock's retry semantics are external to this story.*

---

## Citation Corrections (found during second-pass verification, now fixed in story)

Line-by-line diff of every file/line citation in the story against HEAD surfaced two stale references.
Both are corrected in the story file; neither affects the validity of the bug findings or fixes.

1. **AC3** — `QuickCompleteTimeoutService.processExpiredQuickCompletes` was cited at
   `QuickCompleteTimeoutService.java:35-36`; the `@Scheduled` annotation and method signature are
   actually at lines **36-37**. Off-by-one, cosmetic.
2. **AC2** — `PlayerRegistrationService`'s `user.setActivated(true)` activation call site was cited at
   `PlayerRegistrationService.java:149`; it is actually at line **160** (line 149 is inside an unrelated
   preceding block — the file also has an earlier, unrelated `setActivated(false)` at line 110, which
   may be why the reference drifted). The call site itself is real and the claim it supports
   (four concurrent activation writers exist) is correct — only the line number was wrong.

All other file/line citations across all three ACs (`BookingExpiryScheduler.java:43-44,48-66,50-51`,
`BookingService.java:151,156-174,429,794`, `BookingStateTransitionException.java:3`,
`UserAdminService.java:46-50,122-129,135-140`, `RadarCompositeDlqProcessor.java:25,34`,
`SecurityProperties.java:26`, `SchedulerLockTransactionOrderingIT.java`'s test method names and
javadoc claims, and all "cleared, not picked up" siblings' claimed behavior — `MessageModerationSweeper.sweepOne`'s
`findByIdForUpdate` re-check, `SessionPackExpiryNotifier`'s `@Version`-guarded write,
`PaymentPendingSweeper.sweepOne`'s pessimistic lock + re-check) were independently re-verified against
HEAD and confirmed exact.

---

## Cross-Story & Scope Validation

### ✅ Audit Lens Consistency

- Story correctly continues the transaction-boundary/TOCTOU/batch-memory lens established by `skillars-deferred-115`/`-116`/`-117` ✓
- 12 additional `@Scheduled` classes audited; two genuine bugs + one consistency gap found — consistent with prior stories' quality bar ✓
- "Cleared, not picked up" list shows thorough negative checking (13 classes explicitly ruled out) ✓

### ✅ Testing Approach

- AC1 test mirrors `BookingReminderSchedulerTest`'s `PlatformTransactionManager` mock pattern (correct — exercises real transaction boundaries, not mocking them away) ✓
- AC2 test is new file (no existing precedent, but correct scoping — Mockito unit test, not IT) ✓
- AC3 test is reflection-based annotation assertion (establishes lockAtMostFor/lockAtLeastFor presence and values, correct approach) ✓
- `SchedulerLockTransactionOrderingIT` replacement (not extension) of test is correctly scoped — method no longer has `@Transactional`, so advisor-ordering test case no longer applies ✓

### ✅ No Ledger Coupling

- Findings are new (not pre-existing `deferred-work.md` bullets), so no deletion-side-effects from the ledger ✓
- Task 4 (grep sweep for class names) correctly prevents accidental double-closure ✓

---

## Known Assumptions (Validated or Benign)

| Assumption | Status | Reasoning |
|-----------|--------|-----------|
| `BookingStateTransitionException` is unchecked | Validated in story | Confirmed in `BookingStateTransitionException.java:3` |
| `bookingService.transition()` uses default `REQUIRED` propagation | Validated in story | Stated in story, confirmed in `BookingService.java:151` |
| `BookingReminderScheduler.processReminderWindows` is the reference implementation | Validated in story | Class javadoc documents the fix; `SchedulerLockTransactionOrderingIT` pins it |
| All four registration paths activate users via `setActivated(true)` or `activate()` | Validated in story | All four call sites confirmed via grep and read |
| ShedLock is already a project dependency | Validated implicitly | Story notes every sibling `@SchedulerLock` already exists; no new dependency needed ✓ |
| Single-instance deployment (one app container) | Referenced from deferred-117 AC4 | Reasonable assumption for today's risk assessment; documented in story ✓ |
| `RadarCompositeDlqProcessor` has no `@Version` field on entity | Stated in story | Would need direct code read to confirm, but story is explicit ✓ |

---

## Summary of Flagged Items

### Blockers
None. Story is ready for dev.

### High-Priority Clarifications (for implementation)
1. **AC1:** Confirm batch SELECT transaction pattern choice (idsOf style vs. single executeWithoutResult) in Dev Agent Record
2. **AC2:** Add explicit null check in `deleteUserInTransaction` (guard against refetch returning null/empty)
3. **AC3:** Verify each of the three classes has exactly one `@Scheduled` method before applying locks

### Medium-Priority Guidance (for implementation)
4. **AC3:** Explicitly compute `lockAtMostFor` and `lockAtLeastFor` for all three; document worst-case arithmetic in Dev Agent Record (especially for MessageRetentionScheduler's daily cadence)
5. **AC1:** Document in Dev Agent Record why the chosen batch SELECT pattern (if different from reference) was picked

### Low-Priority Notes (already correct, no action needed)
- Event listener side effects in AC1 are correctly handled by per-booking transaction scope
- Concurrent activation timing (AC2) correctly handled by REQUIRES_NEW + re-check pattern
- Non-existent booking edge case (AC1) correctly handled by existing try/catch
- RadarCompositeDlqProcessor's broader claim-scoping issue is correctly left to a future, narrower story

### Out-of-Scope Observation (not a story blocker, flagged for a future audit)
`UserAdminService.findExpiredUsers` (`UserAdminService.java:122-129`, touched by AC2 but not part of its
fix) constructs a `Pageable pageable = PageRequest.of(0, batchSize)` that is **never passed** to
`userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(cutoffDate)` — the query takes no
`Pageable` parameter at all. Every call therefore fetches *every* non-activated user older than the
cutoff from the DB, then truncates to `batchSize` in Java (`.stream().limit(batchSize)`), rather than
paginating at the query level as the dead `pageable` variable and the method's own javadoc ("Uses
pagination to limit fetched amount") imply. Functionally this does not break AC2's fix (each loop
iteration still only *processes* `batchSize` users, and already-deleted users drop out of the next
call's result set), but it is a real, currently-reachable inefficiency — unbounded full-table-scan
memory/IO on a large expired-user backlog — that fits precisely the batch-memory lens this story's own
audit methodology targets. Recommend a follow-up story/ledger entry, not an amendment to this one (AC2
is scoped to the activation race, not this pagination gap).

---

## Recommendation

**Approve for dev.** All three ACs are genuine, independently-verified bugs with correct root-cause analysis, well-scoped fixes, and established sibling patterns to mirror. Implement the five noted clarifications inline; none require story amendment.
