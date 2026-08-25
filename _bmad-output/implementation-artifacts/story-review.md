# Story Deferred-66 Audit Report
**Date:** 2026-08-25  
**Auditor Role:** Senior Developer  
**Story Status:** ready-for-dev

---

## Executive Summary

The story is well-structured and addresses two legitimate gaps with clear acceptance criteria. However, several corner cases, false assumptions, and incomplete verification could surface during or after implementation. Most are minor and manageable, but three areas warrant explicit developer attention:

1. **AC1 (frontend):** Locale string consistency and hint truncation risk unverified
2. **AC2 (backend):** Transaction rollback assumption not validated; exception hierarchy for OperationNotAllowedException undocumented
3. **Both ACs:** Missing flows for timezone consistency and concurrent request handling

---

## AC1: Reschedule End-Field Timezone Hint — AUDIT FINDINGS

### ✓ Verified Correct
- **i18n Key References:** Grep confirms `endDerivedHint` is only used in:
  - Three locale files (`en-US`, `de-DE`, `fr-FR`)
  - One template reference: `ParentBookingsPage.vue:109`
  - Safe to remove after replacing the reference
- **State Availability:** `browserTimezone` (line 137) and `rescheduleBookingTimezone` (line 142) are already defined in component
- **Sibling Consistency:** `startTimezoneHint` pattern on line 103 confirmed; AC1 correctly mirrors this approach

### ⚠️ False Assumptions & Missed Flows

#### 1. **Hint Display Inconsistency — DESIGN GAP**
**Issue:** The start field (`proposedStart`) has ONLY the timezone hint (`startTimezoneHint`). It does NOT have a separate "this is derived" hint. But the end field currently has ONLY a derivation hint (`endDerivedHint`), not timezone info.

The story proposes combining BOTH hints on the end field (`endDerivedHintWithTimezone`), creating asymmetry:
- **Start field:** "Entered in your browser timezone (America/New_York). The session itself is in UTC."
- **End field:** "[Auto-derived + timezone info combined]"

This violates the "mirroring" principle the story claims to follow.

**Action for dev:** Document this asymmetry in the PR, or verify with product that it's intentional.

---

#### 2. **Hint Truncation Risk — QA CONCERN**
**Issue:** Quasar's `q-input` hint slot has limited space. Combining "auto-derived" explanation + timezone statement could exceed typical hint rendering width, causing text wrapping or truncation across locales.

The story specifies combining both messages but doesn't consider:
- De-DE translations are typically 20% longer than English
- Fr-FR uses accents and longer words
- Mobile viewport width (hint might wrap to 2+ lines)

**Action for dev:** Test the combined hint on mobile (iPhone SE, iPad) and desktop at 80%/100%/120% zoom. Verify no truncation or awkward wrapping occurs in any locale.

---

#### 3. **Timezone Null-Safety — RUNTIME RISK**
**Issue:** Story assumes `rescheduleBookingTimezone` is always populated from `booking.canonicalTimezone`. But:
- What if the booking was created without a canonical timezone?
- What if the database value is null?

The component would render a hint with empty timezone: "Session timezone is ."

**Action for dev:** 
- In `openRescheduleDialog`, verify `booking.canonicalTimezone` is not null before setting `rescheduleBookingTimezone`, OR
- Update the i18n key to handle empty timezone with fallback string like "(timezone unknown)"

---

#### 4. **Locale Wording Style Unverified — TRANSLATION QA**
**Issue:** Story says to "match each locale's existing `startTimezoneHint`/`endDerivedHint` wording style". The grep shows keys exist, but doesn't verify:
- Whether the wording style is actually consistent between the two
- Whether combining them will feel natural in each locale

**Action for dev:** Read the full text of both hint keys in de-DE and fr-FR before drafting the combined message. Ensure the new key feels natural to a native speaker.

---

#### 5. **Manual Testing Scope Missing**
**Issue:** Story relies entirely on manual verification. But testing criteria are underspecified:
- All three locales or just en-US?
- Mobile viewports?
- All browsers or just Chrome?

**Action for dev:** Create a detailed manual test checklist:
- [ ] en-US: hint readable without wrapping at 100% zoom (desktop & mobile)
- [ ] de-DE: hint readable without wrapping at 100% zoom (desktop & mobile)
- [ ] fr-FR: hint readable without wrapping at 100% zoom (desktop & mobile)
- [ ] Start field hint unchanged
- [ ] End field hint includes both derivation + timezone info

---

## AC2: BookingCompletionService Lock-Conflict Handling — AUDIT FINDINGS

### ✓ Verified Correct
- **Three Unguarded Methods Identified Correctly:**
  - `startSession` (line 54, no try/catch)
  - `initiateQuickComplete` (line 111, no try/catch)
  - `submitWrapUp` LIVE-mode branch (line 148, no try/catch)
- **Four Guarded Methods Correctly Excluded:**
  - `endSession` (line 69, has try/catch)
  - `pauseSession` (line 82, has try/catch)
  - `resumeSession` (line 95, has try/catch)
  - `confirmCompletion` (line 175, has try/catch)
- **Exception Imports Already Present:** Both `OptimisticLockingFailureException` (line 27) and `OperationNotAllowedException` (line 19) are imported at class level

---

### ⚠️ Critical Assumptions Not Verified

#### 1. **Transaction Rollback Behavior Assumed But Not Documented — CORRECTNESS RISK**
**Issue:** The story's entire reasoning for why `submitWrapUp`'s prior `save()` is safe rests on this claim:

> "when the new catch re-throws `OperationNotAllowedException`, Spring rolls back the entire transaction — including the just-saved `SessionCompletionData` row"

**This assumes:**

1. `OptimisticLockingFailureException` triggers rollback (it's a RuntimeException subclass, should work, but not explicit)
2. `OperationNotAllowedException` triggers rollback (custom platform exception, hierarchy unknown)
3. No explicit `@Transactional(rollbackFor=...)` configuration overrides defaults

**Risk if Wrong:**
- If `OperationNotAllowedException` doesn't trigger rollback, the saved `SessionCompletionData` persists even though the booking transition failed
- A retry would hit the idempotency guard but proceed to a stale transition that fails for a different reason
- Silent corruption of booking state

**Action for dev (BEFORE implementation):**
- Verify `OperationNotAllowedException` extends `RuntimeException`
- Verify no custom `@Transactional` configuration overrides rollback behavior
- Add a unit test: mock transition to throw exception, verify idempotency guard works on retry

---

#### 2. **SecurityError.MISSING_RIGHTS Is Semantically Wrong**
**Issue:** The story uses `SecurityError.MISSING_RIGHTS` for concurrency errors. But `MISSING_RIGHTS` means "authorization error", not "concurrent modification". More correct codes might be `CONFLICT` or `STALE_STATE`.

**However:** The four existing guarded methods already use `MISSING_RIGHTS`, so this maintains consistency.

**Action for dev:** Document in PR that `MISSING_RIGHTS` is used for consistency with existing code, not for semantic correctness.

---

#### 3. **Client Retry Strategy Unspecified — INTEGRATION CONCERN**
**Issue:** The story says the exception is "retry-able" (403), but:
- How should the client retry? Immediately? With backoff?
- Should the frontend disable the button to prevent double-clicks?

**Action for dev:** Verify the frontend already has retry logic or double-click prevention. If not, file a separate UX story.

---

### ⚠️ Test Coverage Gaps

#### 4. **Asymmetric Test Coverage — MAINTENANCE CONCERN**
**Issue:** The story adds tests for the three newly-guarded methods but NOT for the four already-guarded methods (by design, since backfilling tests is out of scope).

**Result:** After AC2 ships, we'll have test coverage for OptimisticLockingFailureException on 3 methods but not 4 others that handle it identically. This creates inconsistent test coverage.

**Action for dev:** Document in PR that a future "test backfill" pass should add equivalent tests for `endSession`, `pauseSession`, `resumeSession`, and `confirmCompletion`.

---

## Missed Flows & Corner Cases

### Flow 1: Concurrent Reschedule + Session Completion
**Scenario:** Parent reschedules session while coach is completing it, both increment booking version.

**Impact:** submitWrapUp fails with 403. Coach sees "Booking status changed concurrently — retry", but reschedule is pending, so retrying might not make sense.

**Is it handled?** Partially. The 403 is correct, but UX doesn't explain why. Out of scope for this story.

---

### Flow 2: startSession Double-Click
**Scenario:** Coach clicks "Start Session" twice rapidly, causing concurrent requests.

**Impact:** Second request fails with 403, coach retries, succeeds.

**Is it handled?** Yes, by new try/catch. But verify the frontend prevents double-clicks (button should show `:loading` state).

**Action for dev:** Verify the button has `:loading` or `:disable` state while request is pending.

---

### Flow 3: Browser Timezone = Session Timezone (AC1)
**Scenario:** The hint would say: "Entered in your timezone (America/New_York). The session itself is in America/New_York."

**Impact:** Redundant and confusing.

**Is it handled?** No. Out of scope, but worth noting for future enhancement.

---

## Summary of Issues by Severity

| # | Severity | Category | AC | Issue |
|---|----------|----------|-----|-------|
| 1 | Medium | Design Gap | AC1 | Hint display asymmetry (start vs. end fields) |
| 2 | Medium | QA | AC1 | Hint truncation on mobile / DE / FR |
| 3 | Medium | Runtime | AC1 | Timezone null-safety |
| 4 | Low | Translation | AC1 | Locale wording style consistency |
| 5 | Low | QA | AC1 | Manual testing scope underspecified |
| 6 | **High** | **Correctness** | **AC2** | **Transaction rollback assumption not verified** |
| 7 | Low | Semantic | AC2 | MISSING_RIGHTS error code semantically wrong |
| 8 | Low | Integration | AC2 | Client retry strategy unspecified |
| 9 | Low | Maintenance | AC2 | Asymmetric test coverage |
| 10 | Low | UX | Both | Concurrent operation flows not addressed |

---

## Recommended Pre-Dev Checklist

- [ ] **AC1:** Verify locale wording styles in de-DE and fr-FR for both hint keys
- [ ] **AC1:** Confirm `booking.canonicalTimezone` is always present (not null)
- [ ] **AC1:** Create mobile/zoom test matrix (all locales, 80-120% zoom)
- [ ] **AC2:** Verify `OperationNotAllowedException` extends `RuntimeException`
- [ ] **AC2:** Review @Transactional configuration on `submitWrapUp`
- [ ] **AC2:** Verify frontend prevents double-clicks on session action buttons
- [ ] **AC2:** Add unit test for submitWrapUp transaction rollback on concurrent modification

---

## Conclusion

**Overall Assessment:** Story is well-written and ready for dev. AC1 has manageable UX/translation risks. AC2 has one critical assumption (transaction rollback behavior) that must be verified before implementation. None are blockers, but developer should complete pre-dev checklist before starting.

**Risk Level:** Medium (critical transaction rollback assumption) + Low (multiple minor gaps)

**Recommendation:** Proceed to dev with pre-dev verification checklist completed.
