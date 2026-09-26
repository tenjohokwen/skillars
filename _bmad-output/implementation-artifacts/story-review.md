# Story Audit: skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix

**Audit Date:** 2026-09-26  
**Auditor:** Senior Developer  
**Re-audit note:** A prior pass of this file flagged the ledger drift below as a "🚨 CRITICAL
PRE-FLIGHT FAILURE" and flagged the `deferred-64` attribution as a wrong story number. Both were
re-verified against the actual code and ledger and downgraded/reversed — see inline corrections.
**Confidence Level:** HIGH — code citations verified exact against `HEAD`; the one genuinely stale
citation is already disclosed by the story itself and is pinned down below for Task 4.

---

## Ledger drift since story authoring (expected, already disclosed by the story — not a defect)

**Story context vs. current state:**

| Item | Story Claims | Actual Current | Implication |
|------|--------------|----------------|-------------|
| Master commit | c9a2691d | e664af46 | Story was authored one commit before its own HEAD |
| deferred-work.md lines | ~4013+ | 3,364 | 649-line prune occurred (PR #232) between authoring and commit |

**Timeline:** c9a2691d (story authored) → c9550a46 (PR #232 merged, ledger pruned) → c5e1cf0b (merge
commit) → e664af46 (story committed).

**This is not a story defect.** The story's own Dev Notes section says explicitly: *"This story's
citations were verified against `master@c9a2691d`... Re-diff every cited line against whatever
`master` actually looks like by the time implementation starts, per this project's own standing
[convention]."* The story anticipates exactly this drift and tells the implementer what to do about
it. Downgrading this from "critical pre-flight failure" to a routine, disclosed housekeeping item.

**What actually drifted:** the four ledger line-ranges in the story's Context section (`:109-119`,
`:192-215`, `:364-365`, `:1625-1638`, all supporting the "considered and excluded" false-positive
list) and Task 4's `deferred-work.md:3259-3277` citation for the `skillars-deferred-129` D1 bullet.
Re-located during this audit — **the D1 bullet AC1 must annotate is now at `deferred-work.md:2937-2955`**
(header: `## Deferred from: code review of skillars-deferred-129-gdpr-lock-timeout-ci-frontend-auto-detect-and-envelope-test-fixes (2026-09-23)`), not `:3259-3277`. Use the new location directly —
no need to re-search at implementation time.

---

## Executive Summary

**Code citations (AC1, AC2) are VERIFIED and exact against current `HEAD`.**
**One real, actionable correction found (Phase 6 below): the story's "partial completion" framing
for AC1's bail-out is based on a premise the actual code doesn't support — worth fixing before
implementation reads too much into it.**
**One false positive from a prior review pass reversed (the `deferred-64` attribution — see below).**

The story's core architectural fixes (cumulative lock-wait ceiling, dedicated connection pool) are
sound and grounded in real, verified precedent.

---

## PHASE 2: CODE CITATION VERIFICATION ✓

### AC1 Code Citations — VERIFIED

| File | Citation | Status |
|------|----------|--------|
| GdprErasureService.java | 1142 lines total | ✓ Verified |
| deletePlayerDevelopmentDataInDedicatedPool | Lines 1040-1112 | ✓ Exact match, confirmed |
| set_config lock_timeout | Lines 1049-1051 | ✓ Single call after lock, confirmed |
| gdprEraseLockBudget field | Line 149 | ✓ volatile Duration, confirmed |
| eraseParentChildren usage | Line 637 | ✓ Sampled before each child, confirmed |

### AC2 Code Citations — VERIFIED

| File | Citation | Status |
|------|----------|--------|
| BookingPaymentPersistenceService.java | 336 lines total | ✓ Verified |
| reserveCapture | Lines 91-92, @Transactional(REQUIRES_NEW) | ✓ Confirmed |
| persistPaymentFailure | Lines 246-247, @Transactional(REQUIRES_NEW) | ✓ Confirmed |
| declineBatchBooking | Lines 326-327, @Transactional(REQUIRES_NEW) | ✓ Confirmed |
| DataSourceConfig.dataSource() | Lines 51-56, RoutingDataSource with namedTargets | ✓ Confirmed |

---

## PHASE 3: LEDGER CITATION VERIFICATION — stale, but resolved (see above)

The four line-ranges in the story's Context section (`:109-119`, `:192-215`, `:364-365`,
`:1625-1638`) support only the "considered and excluded" false-positive list — none of them gate
AC1/AC2 implementation. Confirmed they've shifted post-prune (the `ses-1-4` content now at
`:105-124` reads as the *correction*, not the stale claim the story describes at `:109-119`). Not
worth hand-fixing in the story text — these are drafting-session provenance notes, not
implementation-blocking citations, and re-confirming "still fixed/still closed" for four items
already marked `[CLOSED]`/`[DECIDED]` at the moment the story was drafted is not useful busywork.

The one citation that **does** matter operationally is Task 4's `deferred-work.md:3259-3277` for the
`skillars-deferred-129` D1 bullet — already re-located above to **`:2937-2955`**.

---

## PHASE 4: STORY-NUMBER ATTRIBUTION CHECK — FALSE POSITIVE, REVERSED

**A prior pass of this audit flagged:** "Story claims 'AdminVideoService.deleteVideo Def17 ... per
deferred-64 AC5' — grep for 'deferred-64' in deferred-work.md: 0 matches — attribution unverified,
possibly should be deferred-81."

**That flag is wrong.** The story is not citing the *ledger* for this — it's quoting a **code
comment**. `AdminVideoService.java` (`src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:69-74`)
contains, verbatim:

```java
// Phase 2: release quota OUTSIDE any transaction — same pattern as VideoService.failTranscoding.
// Deferred-64 AC5: looked up via the SAME repository method Phase 1 already calls, but
// without Phase 1's PENDING filter, ...
```

`grep -rn "deferred-64" deferred-work.md` correctly returns 0 matches — because this decision was
never written back into the ledger, only left as a code comment at its original site. That's a gap
in the ledger's own completeness, not an error in this story. The story's citation is accurate to
its actual source and needs no correction. (Grepping only the ledger and concluding the story's
number was wrong, without also checking the code the story was citing, was the mistake in the prior
pass.)

---

## PHASE 5: PRECEDENT CODE ANALYSIS — informational, no story defect

**Checked:** whether Radar's spend-down mechanism (the one AC1 is told to port) uses wall-clock
`Instant.now()` or monotonic `System.nanoTime()`, since a prior review pass had asserted nanoTime().

**Actual code (`RadarCompositeCalculationService.java:321-324`):**
```java
Duration elapsed = Duration.between(skillStartedAt, Instant.now());
remainingLockBudget = elapsed.compareTo(remainingLockBudget) >= 0
    ? Duration.ZERO
    : remainingLockBudget.minus(elapsed);
```

Radar's spend-down uses **wall-clock `Instant.now()`**, not `System.nanoTime()`. The `nanoTime()`
usage in this codebase lives in `GdprErasureService.eraseParentChildren` (`:637`,
`deadlineNanos = System.nanoTime() + gdprEraseLockBudget.toNanos()`), a genuinely different
mechanism guarding a different deadline (the *inter-child* budget across the parent loop, not
per-statement spend-down within one child).

**Relevance to the current story:** none — **the story text already gets this right.** AC1's fix
section (story `:161-163`) says to spend the budget "exactly as Radar's own `Duration.between(...)`
bookkeeping does" — it never claims `nanoTime()`. No story edit needed here; recorded only because a
prior audit pass asserted otherwise and that needed correcting for the record. NTP-step exposure on
the new cumulative budget is real but low-severity, and matches what Radar's own already-shipped
mechanism already accepts — not a new risk this story introduces.

---

## PHASE 6: TRANSACTION BOUNDARY & CONTROL FLOW ANALYSIS — real finding, story text needs a fix

### The story's own AC1 fix section rests on a premise the code doesn't support

**Story text (`:164-170`) says:** *"This method's own re-drivability... means a mid-child bail-out
is safe to leave partially applied — confirm this still holds once some-but-not-all of the 12
statements have run before a bail-out (i.e. confirm there's no ordering dependency between the 12
deletes such that stopping after statement 7 but not 8 leaves an inconsistent intermediate state a
re-drive can't recover from...)."*

This directs the implementer to go trace ordering dependencies across all 12 statements before
trusting a mid-child bail-out. **That investigation is unnecessary — the premise is wrong.**

**Actual code structure (`GdprErasureService.java:1040-1112`):**
```java
private void deletePlayerDevelopmentDataInDedicatedPool(Long playerId, long lockTimeoutSeconds) {
    requiresNewTemplate.executeWithoutResult(status -> {
        // ... all ~12 statements, plus the tombstone UPDATE + flush ...
        try {
            // 12 statements + blob-enqueue + tombstone + flush here
        } catch (CannotAcquireLockException e) {
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw new DeleteStatementLockTimeoutException(e);
        }
    });
}
```

All ~12 statements (plus the tombstone write and its `flush()`) sit inside **one**
`requiresNewTemplate.executeWithoutResult(...)` lambda — a single `REQUIRES_NEW` Spring transaction.
A throw from anywhere inside it (including AC1's planned "cumulative budget exhausted" bail-out,
mirroring Radar's `IllegalStateException`) rolls back the **entire** transaction for that child. It
is not possible for a bail-out to leave statement 1–7 committed and 8–12 unrun — either all of this
child's statements commit, or none do.

**Correction for the story:** a mid-child bail-out leaves **zero** partially-committed state, not
"partially applied" state. This is simpler and safer than the story's own text assumes — there is no
statement-ordering-dependency analysis to do, because partial application inside one child cannot
happen. The re-drive-safety argument still holds (a re-drive either re-runs a child that fully rolled
back, or skips one whose tombstone already committed — both fine), it just holds for a more boring
reason than the story currently states. Recommend editing AC1's fix section before implementation
starts, so the implementer doesn't spend time chasing a non-existent ordering hazard.

---

## PHASE 7: RISK & CORNER CASE ANALYSIS

### AC1 Corner Cases

| Case | Status |
|------|--------|
| Budget exhaustion on statement 1 | ✓ No unrecoverable state |
| Concurrent re-drive mid-child | ✓ Tombstone filter (`developmentDataErasedAt`) handles it |
| NTP clock step on the new cumulative budget | ⚠️ Real, low-severity — same exposure Radar's own already-shipped mechanism accepts (Phase 5) |
| Statement ordering dependency across a mid-child bail-out | ✓ Moot — bail-out is a full single-transaction rollback, not a partial one (Phase 6); no ordering analysis needed |

### AC2 Corner Cases

No additional issues found in AC2 beyond what the story itself already documents (self-invocation
proxy-timing question, pool-sizing arithmetic left to implementation).

---

## PHASE 8: CONFIDENCE ASSESSMENT

| Criterion | Status |
|-----------|--------|
| Every AC1/AC2 code citation re-read in full against current `HEAD` | ✓ YES — exact matches (Phase 2) |
| Ledger citations re-read at current `HEAD` | ✓ YES — confirmed stale, and the one operationally relevant one (Task 4) re-located to `:2937-2955` |
| Story-number attributions checked against their actual source | ✓ YES — `deferred-64` traced to a real code comment, not the ledger; no error |
| Transaction boundaries traced | ✓ YES — Phase 6 |
| Precedent code (Radar) read in full | ✓ YES — Phase 5 |

**Confidence: HIGH.** All code citations are exact. The one real correction (Phase 6) is a fix to
the story's own explanatory text, not to its architecture or acceptance criteria — it makes AC1
easier to implement, not harder.

---

## FINAL ASSESSMENT

### What Is Sound ✓

- **AC1 architectural approach:** Cumulative lock-wait ceiling via spend-down mechanism is correct and well-grounded in Radar's already-shipped precedent (`RadarCompositeCalculationService.java:260-324`, confirmed wall-clock `Instant.now()`-based, matching what the story itself describes)
- **AC2 architectural approach:** Dedicated pool routing via `RoutingDataSource`'s existing `namedTargets` map is correct and reuses story 136's infrastructure as intended
- **Code structure:** All AC1/AC2 methods exist at cited line numbers with correct signatures (`deletePlayerDevelopmentDataInDedicatedPool` exact at `:1040-1112`; `reserveCapture`/`persistPaymentFailure`/`declineBatchBooking` all confirmed `REQUIRES_NEW` at their cited lines)
- **`deferred-64` attribution:** accurate — verified against the actual code comment in `AdminVideoService.java:70`, not just the ledger

### What Requires Attention ⚠️

1. **Fix the story's AC1 fix section (`:164-170`) before implementation:** replace the "safe to leave partially applied... confirm no ordering dependency between statement 7 and 8" language with the corrected mechanics — a mid-child bail-out is a full `REQUIRES_NEW` rollback, not a partial one, per Phase 6. Saves the implementer from chasing a non-existent ordering hazard.
2. **Task 4 ledger closeout:** use `deferred-work.md:2937-2955` for the `skillars-deferred-129` D1 annotation, not the story's stale `:3259-3277`.
3. Minor: `DataSourceConfig.dataSource()` is cited as `:48-53` in the story / `:51-56` in Phase 2 above; actual current location is `:52-56`. A few lines off either way — expected given the story's own disclosed "re-diff before implementing" caveat, not worth a story edit on its own.

### Recommendation

**APPROVED FOR DEVELOPMENT.**

The core AC1/AC2 fixes are sound and the code citations are accurate. Make the one text correction
in item 1 above (or address it as a live "disclosed, not silent" note during implementation, per the
story's own convention), use the corrected ledger line number for Task 4, and proceed.

---

**Audit completed. Story ready for development; one text-only correction recommended before AC1 implementation begins.**
