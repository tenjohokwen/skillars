# Story 1.7 Review Updates & Story Split

**Date:** 2026-09-02
**Reviewer:** Senior Dev Review (story-review.md)
**Status:** Story 1.7a updated and ready-for-dev. Story 1.7b deferred pending architectural decision.

---

## Summary

The initial Story 1.7 contained **3 blocking issues** that would cause regressions if implemented. Rather than lose the documentation value, the story has been **split**:

- **Story 1.7a** (this update): Documentation & comments fixes — LOW RISK, ready-for-dev
- **Story 1.7b** (deferred): Backend `rint` contract redesign — REQUIRES ARCHITECTURE DECISION first

---

## Changes Made to Story 1.7a

### Scope Narrowed

**Removed (deferred to 1.7b):**
- Backend changes to `rint` cookie value/TTL
- Frontend session monitoring fallback initialization
- Multi-tab session expiry handling
- Session warning dialog timer fixes
- Frontend session state refactoring

**Kept (low-risk, ready-for-dev):**
- Documentation updates to fix non-existent code path references
- Backend comments/JavaDoc for endpoint clarity
- Code quality cleanup (cookie substring checks)

### Acceptance Criteria Updated

**Old:** 6 major areas (doc + 5 behavioral changes)  
**New:** 4 focused areas (all documentation/comments)

1. ✅ Update `docs/session-refresh-mechanism.md` with correct code paths
2. ✅ Document `/refresh` vs `/api/auth/refresh` distinction
3. ✅ Clarify `SESSION_REFRESH_COUNTDOWN` constant usage
4. ✅ Fix minor robustness issues (cookie checks)

### False Positives Corrected

| Issue | Error | Fix |
|---|---|---|
| N1 | Package paths wrong (`com.skillars`) | Corrected to `com.softropic.skillars` |
| N2 | Default timeout "5 min" → actually "2 min" | Updated to actual `DEFAULT_WARNING_THRESHOLD` |
| N3 | Some validations "don't exist" → actually exist | Acknowledged, marked verify-only |
| N4 | `beforeunload` wrong mechanism | Removed, deferred to 1.7b |
| N5 | Untestable ACs (exact timing) | Reframed as verification-only |
| N6 | Dev Notes factually wrong | Corrected cookie security attributes |

### Dev Notes Clarified

**Added architectural context:**
- Explained sliding-window JWT expiry (why `rint` is always ~600000 ms)
- Clarified "activity" means API requests only, not user input
- Documented actual session cookie values and TTLs
- Noted unresolved issues deferred to 1.7b
- Corrected all file paths to use actual package structure

---

## Blocking Issues & Why They Were Deferred

### B1: The `rint` Change Kills Session Warning Dialog

**Problem:** Changing `rint` from fixed value to dynamic countdown breaks the frontend formula without an accompanying frontend change.

```
Today: rint = 600000, frontend: threshold = 900000 - 600000 = 300000 (5 min) ✓
After change: rint = 900000, frontend: threshold = 900000 - 900000 = 0 ✗ (guard rejects)
```

**Root cause:** Three different `rint` formulas in the codebase (backend code, drift analysis, ACs) are mutually incompatible.

**Deferred to 1.7b:** Will redesign `rint` as **absolute expiry timestamp** (epoch milliseconds), which is implementable under sliding expiry and fixes both B1 and M1.

### B2: "Decreasing `rint` Values" Are Impossible

**Problem:** AC claims `rint` decreases over time (900000 → 800000 → 700000). This cannot happen because JWT is re-issued with fresh full TTL on every request.

**Root cause:** Story misunderstood the sliding-window design.

**Deferred to 1.7b:** Will specify absolute timestamp contract instead, eliminating this confusion.

### B3: 401 Error Path Mismatch

**Problem:** The axios interceptor expects `errorKey === 'security.sessionExpired'` in the error response. But the JWT filter emits a plain 401 without `ErrorDto` wrapper.

```
Frontend gate: if (errorKey === 'security.sessionExpired') { logout }
Actual filter: res.sendError(HttpServletResponse.SC_UNAUTHORIZED)  // no ErrorDto
Result: errorKey = '', gate never fires, user just fails
```

**Deferred to 1.7b:** Requires fixing the filter to emit `ErrorDto`, or relaxing the frontend gate. Needs 5-minute manual check first (expire token, watch network tab).

---

## Major Issues Not Addressed (Deferred to 1.7b)

### M1: Idle Background Tab Force-Logs-Out Active Tab

**Scenario:**
1. Tab A actively making requests → JWT extended every 30 seconds
2. Tab B idle → local timer counts down, never sees Tab A's activity
3. After 15 min, Tab B's timer fires `session:expired` → logs out
4. Result: Tab A (which was actively in use) is now logged out

**Why not fixed here:** Requires absolute-timestamp contract for `rint` so all tabs can read "session expires at T".

### M2: Logout Button Bounces User Back to App

**Problem:** `useSession.handleLogout()` doesn't clear the Pinia `authStore`, leaving `skp` cookie intact. User redirected to `/login` but auth guard sees `skp` still valid → bounces back into app.

**Why not fixed here:** Requires comparing and aligning two logout paths (`useSession` vs `MainLayout`), risky behavioral change.

### M3: `skp` TTL Advice is Backwards

**Problem:** AC suggested using `skp` cookie (7-day TTL) instead of `user` cookie (15-min TTL). Actually backwards — `user` is the live session, `skp` is stale.

**Why not fixed here:** Requires rethinking session state hydration strategy.

### M4: Existing Tests Will Break

**Problem:** `JwtManagerImplTest` asserts exact `rint` value twice. Changing `rint` breaks the build before dev even starts.

**Why not fixed here:** If `rint` contract changes to 1.7b design, test assertions need rewriting. Coupled to the architectural decision.

---

## What Story 1.7b Should Address

(To be created after this story completes)

**Primary: Redesign `rint` Contract**
- Change from fixed value (600000) to **absolute expiry timestamp** (epoch milliseconds)
- Specify backend formula: `rint = JWT.expiresAt = now + JWT_TTL`
- Specify frontend formula: `timeUntilExpiry = rint - Date.now()`
- Eliminates hardcoded `SESSION_TTL` in frontend

**Secondary: Fix Coupled Issues**
- B3: Make filter emit `ErrorDto` or loosen frontend gate
- M2: Align logout paths between `useSession` and `MainLayout`
- M1: Idle tabs now read `rint` to discover sibling activity
- M4: Update `JwtManagerImplTest` assertions

**Prerequisite:** Manual check before 1.7b starts
- Expire a JWT token
- Fire an API call
- Observe 401 response body shape (confirms/denies B3)
- This single observation determines 3 other AC locations

---

## Status Tracking

| Item | Status | Notes |
|---|---|---|
| Story 1.7a | ready-for-dev | Documentation & comments. Safe to assign to dev. |
| Story 1.7b | blocked-pending-decision | Requires architectural decision on `rint` contract. |
| Manual JWT expiry check | To-do | Run before 1.7b starts — 5 minute check de-risks B3. |

---

## Files Updated

- ✅ `1-7-session-refresh-mechanism-fix.md` — Scope narrowed, ACs updated, false positives corrected
- ✅ `1-7-REVIEW-UPDATES.md` — This file (summary of changes)
- 📋 `1-7b-session-refresh-rint-contract-fix.md` — To be created
