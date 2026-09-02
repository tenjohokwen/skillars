# Story 1.7b: Session Refresh `rint` Contract Fix (Deferred)

Status: blocked-pending-decision

## Story

As a frontend and backend developer,
I want the session refresh mechanism to use an architecture-aligned `rint` contract that survives sliding-window JWT re-issuance and multi-tab scenarios,
so that idle tabs can discover that sibling tabs have extended the session and don't unnecessarily log out active users.

## Context

Story 1.7a (Documentation & Comments Fix) is ready-for-dev. This story addresses the behavioral backend changes that were deferred due to blocking issues identified in the senior dev review.

**Blocking issues from review (story-review.md):**
- **B1:** Current `rint` formula breaks the session warning dialog
- **B2:** Requested "decreasing `rint`" is impossible under sliding-window JWT
- **B3:** 401 error path doesn't match frontend's expectation
- **M1:** Idle background tabs force-logout active tabs
- **M4:** Existing tests will break without update

## Acceptance Criteria

### PREREQUISITE: Confirm 401 Error Path ✅ DECISION MADE

✅ **CONFIRMED:** JWT expiration emits plain 401 (not ErrorDto)

**Root cause:** `JWTAuthorizationFilter.java:93-97` catches `JWTExpiredException` and emits `res.sendError(SC_UNAUTHORIZED)` before exception reaches `@RestControllerAdvice.jwtExpirationHandler()` (which would return ErrorDto).

**Decision:** Use **Option B1 — Backend Fix**
- Update JWTAuthorizationFilter to emit ErrorDto instead of plain 401
- Aligns with rest of application's error handling pattern
- More robust than frontend workaround

### Backend: Redesign `rint` as Absolute Expiry Timestamp

**Given** a user authenticates and receives session cookies
**When** the backend issues a JWT with expiry time T
**Then** the `rint` cookie is set to T (absolute epoch milliseconds, not time-delta)
**Example:** If JWT issued at 1:00 PM and expires at 1:15 PM (900 seconds later), `rint = 1694850900000` (epoch ms of 1:15 PM)

**Given** the same user makes a second authenticated request at 1:05 PM (5 minutes later)
**When** the backend re-issues the JWT with a fresh 15-minute expiry (now expires at 1:20 PM)
**Then** the `rint` cookie is updated to the new expiry time (1:20 PM epoch ms), reflecting the extension

**Given** `rint` is set on every authenticated response
**When** multiple requests are made within a session
**Then** `rint` increases over time as the TTL resets: `1694850900000` → `1694850960000` → `1694851020000` (same pattern, but in epoch ms, not delta)

**Given** the `rint` cookie carries the absolute expiry timestamp
**When** the `maxAge` is set
**Then** it should be slightly larger than `JWT_TTL` (e.g., 920 seconds instead of 900) so the client can still read "session expired at T" even after a brief idle period

### Frontend: Read `rint` as Absolute Timestamp

**Given** the `rint` cookie contains an absolute epoch millisecond timestamp
**When** `syncWarningThresholdFromCookie()` runs
**Then** it calculates remaining time as: `timeUntilExpiry = rint - Date.now()`
**And** the warning threshold is a client-side constant: `WARNING_THRESHOLD = 5 * 60 * 1000` (5 minutes)
**And** warning shows when: `timeUntilExpiry <= WARNING_THRESHOLD`

**Given** a backend changes its `JWT_TTL` from 15 to 30 minutes
**When** the frontend reads `rint` from the cookie
**Then** no frontend code changes are needed — the contract is time-based, not TTL-based
**And** the hardcoded `SESSION_TTL = 15 * 60 * 1000` can be removed or marked as legacy

### Multi-Tab Activity Sync (Fixed by Absolute Timestamp)

**Given** Tab A is actively using the session (making requests every 30 seconds)
**When** Tab B is idle and its local timer counts down toward zero
**Then** Tab B reads the `rint` cookie before firing `session:expired`, discovers it has been extended by Tab A's requests
**And** Tab B's timer resets based on the new `rint` value — **idle tab no longer force-logs-out active tab**

### Fix 401 Error Path (Backend: Emit ErrorDto from Filter) ✅ DECISION MADE

**Given** a JWT token expires and an authenticated request is made
**When** `JWTAuthorizationFilter` catches `JWTExpiredException`
**Then** the response contains an `ErrorDto` (not a plain 401) with:
  - HTTP status: 401 Unauthorized
  - Body format: `{errorMsg: {errorKey: "security.sessionExpired", message: "..."}}`
  - Matches the format produced by `ApiAdvice.jwtExpirationHandler()`

**Implementation approach:**
- In `JWTAuthorizationFilter.java:93-97` (catch block for AuthorizationException)
- Detect when caught exception is `instanceof JWTExpiredException`
- Emit ErrorDto with `errorKey = "security.sessionExpired"` instead of plain `res.sendError()`
- For other AuthorizationException types, emit `errorKey = "security.unauthorized"`
- Use same response body structure as `ApiAdvice` handlers (via MessageSource for i18n)

**Result:** Frontend axios interceptor gate (`if (errorKey === 'security.sessionExpired')`) now fires correctly

### Fix Logout Behavior (M2 from review)

**Given** a user clicks the Logout button in the session warning dialog
**When** the logout completes
**Then** the user is redirected to `/login` and **cannot be bounced back into the app**
**Requires:**
- `useSession.handleLogout()` must clear the Pinia `authStore` (not just call API logout)
- `skp` cookie must be cleared (currently survives logout because only Spring's logout filter is called)
- **Or:** Align `useSession.handleLogout()` with `MainLayout.handleLogout()` (the known-working version)

### Update Existing Tests (M4 from review)

**Given** `src/test/java/.../jwt/JwtManagerImplTest.java` contains two assertions
**When** the `rint` value changes from fixed 600000 to dynamic timestamp
**Then** update both assertions:
  - Line ~291-292
  - Line ~504-505
- Replace `assertThat(sessionTimeoutWarningInterval).isEqualTo(String.valueOf(JWT_TTL.minusMinutes(5).toMillis()))` with new assertion that validates the timestamp is within acceptable range

### Testing Checklist

- [ ] Manual JWT expiry check completed (PREREQUISITE) — result documented
- [ ] `rint` cookie contains absolute epoch milliseconds on every authenticated response
- [ ] `rint` increases as JWT is re-issued (older expiry → newer expiry)
- [ ] Frontend reads `rint` as absolute timestamp
- [ ] Warning threshold is 5 minutes before `rint` value
- [ ] Idle background tab reads sibling's `rint` and doesn't force-logout
- [ ] 401 error path works (depends on PREREQUISITE result)
- [ ] Logout button fully clears session (no `skp` cookie survives)
- [ ] `JwtManagerImplTest` assertions pass with new `rint` format
- [ ] Multi-tab session behavior: active tab extends session, idle tabs discover it

## Dev Notes

### Architecture Decisions ✅ CONFIRMED

**1. Absolute Timestamp Contract for `rint`**
- Approved (see Design Rationale below)
- Implementation: `rint = JWT.expiresAt` (epoch milliseconds)
- Frontend: `timeUntilExpiry = rint - Date.now()`

**2. Fix 401 Error Path (B3)**
- Confirmed: JWT filter emits plain 401, not ErrorDto
- Decision: Backend fix (Option B1) — update JWTAuthorizationFilter to emit ErrorDto
- Advantage: Aligns with application's error handling; robust vs. frontend workaround

### Design Rationale for Absolute Timestamp Contract

**Why this design over the original fixed-value contract:**

| Aspect | Original (600000 ms) | Absolute Timestamp (epoch ms) |
|---|---|---|
| Sliding expiry | ✗ Breaks countdown formula | ✓ Works cleanly |
| Multi-tab sync | ✗ All tabs see same value (can't detect extension) | ✓ Tabs see increased value when active sibling extends |
| No hardcoded TTL in frontend | ✗ `SESSION_TTL` hardcoded | ✓ Removed (use elapsed time instead) |
| Survives laptop sleep | ✗ Depends on client timer | ✓ Read absolute expiry, calculate from `Date.now()` |
| Clock drift resilient | ✗ No | ✓ Timestamp is server authority |

### Coupling with Related Issues

- **M1 (idle tab force-logout):** Fixed automatically by multi-tab sync
- **B3 (401 path):** Requires PREREQUISITE check and conditional fix
- **M2 (logout bounces back):** Requires aligning logout paths or clearing `skp`
- **M4 (test failures):** Requires assertion updates once new contract is live

---

## Status

✅ **UNBLOCKED — Ready to Proceed**

**Decisions confirmed:**
1. ✅ Absolute timestamp contract for `rint` — APPROVED
2. ✅ 401 error path (B3) — Backend fix confirmed
3. ✅ Multi-tab fix (M1) — Addressed by absolute timestamp design
4. ✅ Logout fix (M2) — Specified in ACs
5. ✅ Test updates (M4) — Specified in ACs

**Next step:** Assign to developer after Story 1.7a (documentation) is complete. No further architecture decisions needed.

---

## Files

- `1-7-session-refresh-mechanism-fix.md` (1.7a — documentation-only, ready-for-dev)
- `1-7-REVIEW-UPDATES.md` (summary of story split and false positive corrections)
- `1-7b-session-refresh-rint-contract-fix.md` (this file — behavioral fix, deferred)
- `story-review.md` (senior dev review with full analysis)
