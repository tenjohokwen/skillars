# skillars-deferred-95: Deployment Visibility & Messaging Identity Inheritance Fixes

**Status:** WITHDRAWN — BOTH ACS ALREADY IMPLEMENTED | **Epic:** deferred | **Priority:** medium

---

## ⚠️ STORY WITHDRAWN — FINDINGS

**Verdict:** DO NOT IMPLEMENT — both acceptance criteria describe bugs that are already fixed in `master`.

Senior dev audit identified that:

| AC | Status | Closed By | Details |
|----|--------|-----------|---------|
| AC1 | ✅ FIXED | deferred-94 AC12 | Deploy workflow has `continue-on-error: true` on all 4 notify steps + `if: always()` on `Fail workflow` marker |
| AC2 | ✅ FIXED | deferred-16 AC4 | MessagingReportService.verifyIsParty already role-aware with PlayerProfileRepository injected |

Both fixes were merged in master at commits `c4e9366`, `71b08b7` (PR #151, `28354e7`) and `c7301e0` (2026-08-05) respectively.

**Root cause:** Story was scoped from stale deferred-work.md audit (2026-09-04) without re-verification against HEAD. Line numbers copied verbatim without checking if they were still accurate.

**Process lesson:** Story creation must diff cited line numbers against current HEAD and read same-file code-review deferrals before drawing scope.

**Real open issues identified by review:** See F6 findings in story-review.md — there are legitimately open deployment-visibility issues from deferred-94's code review (smoke-step-errors gap, HealthIndicator for notification channel health).

---

## Story Overview

Two independent, implementable bugs from the deferred-work.md ledger, each affecting different modules but both critical for operational clarity and feature correctness:

1. **Deployment visibility bug** — The GitHub Actions deploy workflow's failure-marker step is unreachable if a notification step (Slack/Email) throws, obscuring the real failure cause (deploy reverted) behind the notification error.

2. **Messaging identity inheritance bug** — `MessagingReportService.verifyIsParty()` is a hand-copy of `MessagingService.verifyIsParty()` that was never updated when the original fixed a silent-default identity bug. Abuse-report endpoints now have the same bug the main messaging endpoints fixed.

Both bugs are independent of ongoing stories (deferred-17 owns the timezone/drill issues; we're intentionally not bundling those). No migrations, no design decisions needed. Straightforward fixes with clear test patterns.

---

## User Story

**As a** DevOps/SRE engineer and developer maintaining Skillars,
**I want** deploy failures to be visibly marked in CI/CD and messaging endpoints to handle player/parent identity consistently,
**So that** operations can quickly identify what actually failed in a deploy, and abuse reporting works correctly for all user roles.

---

## Acceptance Criteria

### AC1: Fix GitHub Actions deploy workflow failure-marker unreachability

- **Task:** Make the `Fail workflow` notification step run unconditionally, even if prior notification steps (Slack/Email) throw
- **Details:** 
  - Current state: `.github/workflows/deploy.yml:184-188` defines a `Fail workflow` step that is supposed to mark the workflow as failed with a visible message
  - Problem: Four notification steps precede it at lines `:128`/`:139` (Slack success/failure) and `:150`/`:166` (Email success/failure)
  - If ANY of those four notification steps throw (unset `SMTP_HOST`, Slack webhook 5xx, timeout, etc.), the step that throws prevents all subsequent steps from running
  - Result: The workflow fails (GitHub marks it red), but the failure is attributed to the notification step, not the real cause (deploy reverted)
  - Symptom: On-call engineer sees "Email notification step failed" and must dig through logs to find "actually the deploy reverted and was the root cause"
- **Fix pattern:** Use `continue-on-error: true` on the notification steps OR wrap the `Fail workflow` step to run even if notifications throw
  - Recommended: Mark notification steps as `continue-on-error: true` so they never pre-empt the explicit failure marker
  - Alternative: Add `if: always()` to the `Fail workflow` step (but notification steps still need guards so they don't hide the actual failure)
  - Best practice: Combine both — mark notifications as non-blocking AND ensure failure step runs regardless
- **Files affected:** `.github/workflows/deploy.yml`
- **Verification:** 
  - Manually trace through: if notification-step-1 throws, do subsequent notification steps still run? Does `Fail workflow` still run?
  - Confirmation: The workflow's final status message (red badge) clearly indicates deploy failure, not notification failure
- **Test:** No new test needed (manual verification in CI run)
- **Rationale:** Operational clarity. The root cause (deploy failed) must be distinguishable from side-effect failures (notification delivery). This is a visibility-only fix.

### AC2: Fix `MessagingReportService.verifyIsParty()` identity bug inherited from `MessagingService`

- **Task:** Apply the same identity-fix that `skillars-deferred-16` applied to `MessagingService.verifyIsParty()` to the parallel method in `MessagingReportService`
- **Details:**
  - Deferred-16 AC4 fixed a silent-default identity bug in `MessagingService.verifyIsParty()`: 
    - Pre-fix: assumed `callerUserId == playerProfileId` interchangeably (works for direct players, breaks for parents managing children and coaches sending to players)
    - Fix: resolved the caller to their `playerProfileId` via `PlayerProfileRepository.findByUserId()` when caller is ROLE_PLAYER
  - Current problem: `MessagingReportService.verifyIsParty()` (lines ~127-141) is a hand-copy of the old `MessagingService` code with a comment "Duplicates `MessagingService.verifyIsParty()` — injecting `MessagingService` would create a circular dep"
  - Consequence: Abuse-report endpoints now have the exact same bug the main messaging endpoints fixed — they assume caller userId == player profile id
  - Symptom: A parent reporting an abuse incident would be silently treated as if they were a player; a coach reporting on their own messages would fail (different identity context)
- **Current code locations:**
  - `MessagingReportService.java:127-141` — the broken `verifyIsParty()` 
  - `MessagingService.java` (deferred-16 AC4) — the reference implementation showing the fix
  - The fix involves: `PlayerProfileRepository.findByUserId(callerUserId)` and switching from playerProfileId lookup to a proper role-aware identity resolution
- **Fix approach:** 
  - Mirror the deferred-16 AC4 fix exactly in `MessagingReportService`
  - Inject `PlayerProfileRepository` (no circular dep because `MessagingReportService` doesn't inject `MessagingService`)
  - Replace the silent default `playerProfileId` resolution with proper role-aware logic
  - Keep the circular-dep comment but update it to reflect the proper fix
- **Files affected:** `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingReportService.java`
- **Verification:**
  - Code inspection: Compare `MessagingReportService.verifyIsParty()` to `MessagingService.verifyIsParty()` — they should be identical (or have the same role-aware identity resolution)
  - Both files reference `PlayerProfileRepository.findByUserId()` for ROLE_PLAYER caller resolution
- **Test:** Existing `MessagingReportService` test suite should run green. If `MessagingReportService` has its own tests for `verifyIsParty()`, verify they pass; if not, no new test is needed (the method is internal and gates endpoints that are already tested at the HTTP layer)
- **Rationale:** Consistency with deferred-16's fix. Abuse-report endpoints (which gate admin/coach/player reports) must use the same identity resolution as the main messaging endpoints, or they will silently fail for certain caller roles.

---

## Technical Requirements

- No database schema changes
- No new external dependencies
- No design decisions needed
- Backward compatible (both are bug fixes, not feature changes)
- No i18n changes

---

## Dev Notes

### Why These Two Issues?

This story bundles two completely independent bugs:
- **Deploy visibility** is a GitHub Actions CI/CD issue (no backend/frontend code affected)
- **Messaging identity** is a backend messaging module issue

They are bundled because:
1. Both are "genuine one-off bugs" from the deferred ledger (not architectural refactoring, not a feature)
2. Both are implementable without waiting on other stories
3. They fill out a moderate-priority story (not too small, not too large)
4. Deferred-17 owns the timezone/drill bugs, so we're not picking those up here

### Circular Dependency Note

`MessagingReportService` and `MessagingService` have a circular dependency if we inject one into the other. The fix avoids this by injecting `PlayerProfileRepository` directly into `MessagingReportService`, just as `MessagingService` does. This is the established pattern in the codebase.

### Deferred Items NOT Included

The following items from deferred-work.md were deliberately excluded:
- **Timezone serialization bug** (deferred-17 AC1-AC2) — Let deferred-17 own it
- **DrillLibraryPage error handling** (deferred-17 AC5) — Let deferred-17 own it
- **VideoModerationEmailListener fail-open** (deferred-94 AC15) — Already analyzed and designed, pre-existing behavior
- **GHCR manifest-unknown edge case** (deferred-94 AC6) — Accepted design decision (self-heals, one-run delay)

---

## Acceptance Checklist

- [ ] AC1: Deploy workflow `Fail workflow` step runs even if prior notification steps throw
- [ ] AC1: GitHub Actions workflow syntax valid, can be dry-run or manually tested
- [ ] AC2: `MessagingReportService.verifyIsParty()` uses proper role-aware identity resolution
- [ ] AC2: `PlayerProfileRepository.findByUserId()` injected and used
- [ ] AC2: Code inspection confirms identity fix matches `MessagingService.verifyIsParty()` pattern
- [ ] All tests pass: `mvn -o verify`
- [ ] No new failures in CI/CD checks
- [ ] Code review green

---

## Success Criteria

✅ Deploy failure causes are unambiguous in GitHub Actions  
✅ Abuse-report endpoints use the same identity resolution as messaging endpoints  
✅ No regressions in existing functionality  
✅ Code review passes without major corrections

