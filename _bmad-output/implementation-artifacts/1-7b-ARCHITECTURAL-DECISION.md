# Story 1.7b: Architectural Decisions — CONFIRMED

**Date:** 2026-09-02
**Status:** UNBLOCKED ✅ Ready for development

---

## Decision 1: `rint` Cookie Contract — Absolute Timestamp ✅

### What Changed

| Aspect | Current (Broken) | New Design |
|---|---|---|
| `rint` value | Fixed 600000 ms (5 min before expiry) | Absolute epoch milliseconds (e.g., 1694850900000) |
| Updates | Never (fixed at login) | Every authenticated response (re-calculated when JWT extends) |
| Frontend calculation | `threshold = SESSION_TTL - rint` | `timeUntilExpiry = rint - Date.now()` |
| Hardcoded frontend constant | `SESSION_TTL = 900000` | Not needed — use elapsed time instead |

### Backend Implementation

```java
// JwtManagerImpl.createLoginCookies() — every authenticated response
long expiresAtEpochMs = JWT.expiresAt();  // epoch milliseconds
String rint = String.valueOf(expiresAtEpochMs);
CookieUtil.addCookie(res, SESSION_REFRESH_COUNTDOWN, rint, false, 
    (int) (JWT_TTL.toSeconds() + 60));  // TTL slightly > JWT_TTL for grace period
```

### Frontend Implementation

```javascript
// src/frontend/src/plugins/sessionManager.js
export function syncWarningThresholdFromCookie() {
  const rint = readRintCookie();
  if (rint === null) return;
  
  const expiresAtMs = Number(rint);
  const timeUntilExpiry = expiresAtMs - Date.now();
  
  const WARNING_THRESHOLD = 5 * 60 * 1000;  // 5 minutes constant (UX choice)
  
  if (timeUntilExpiry > 0 && timeUntilExpiry > WARNING_THRESHOLD) {
    warningThreshold.value = timeUntilExpiry - WARNING_THRESHOLD;
  }
}
```

### Why This Design

**Solves 3 blocking issues:**

1. **B1: Formula compatibility** — `rint - Date.now()` is the same across frontend/backend (not a complex delta calculation)
2. **B2: Sliding-window compatible** — Absolute timestamp increases when JWT is extended (not a countdown)
3. **M1: Multi-tab idle logout** — Idle tab reads sibling's increased `rint` and discovers session was extended

**Additional benefits:**

- No frontend `SESSION_TTL` hardcode needed (contract is time-based)
- Survives laptop sleep and clock drift (server's timestamp is authority)
- Simpler to reason about ("expiry at T" vs. "refresh every N ms")

---

## Decision 2: 401 Error Path Fix — Backend (JWTAuthorizationFilter) ✅

### Finding (B3)

**Problem:** JWT filter emits plain 401 that doesn't reach ErrorDto handler

```
JWTAuthorizationFilter.java:93-97
  catch (JWTExpiredException | ...) {
    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);  // plain 401
  }

Result: Frontend gets {timestamp, status, error, path} — no errorKey field
Frontend gate: if (errorKey === 'security.sessionExpired') never fires
```

### Solution

Update `JWTAuthorizationFilter` to emit ErrorDto before sending error:

```java
catch (AccountStatusException | AuthorizationException | AccessDeniedException e) {
    securityUtil.logout(res);
    
    // Emit ErrorDto instead of plain 401
    ErrorMsg errorMsg;
    if (e instanceof JWTExpiredException) {
        errorMsg = new ErrorMsg("security.sessionExpired", 
            messageSource.getMessage("error.session.expired", null, locale));
    } else {
        errorMsg = new ErrorMsg("security.unauthorized",
            messageSource.getMessage("error.unauthorized", null, locale));
    }
    
    ErrorDto errorDto = new ErrorDto(errorMsg);
    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    res.setContentType("application/json");
    res.getWriter().write(objectMapper.writeValueAsString(errorDto));
    return;
}
```

### Frontend Impact

**Before (broken):**
```javascript
// axios.js:140-141
const errorKey = data?.errorMsg?.errorKey || '';
if (status === 401 && errorKey === 'security.sessionExpired') {
    authStore.logout();  // never fires because errorKey is ''
}
// User's request just fails silently
```

**After (fixed):**
```javascript
// Same gate now works because filter emits ErrorDto with errorKey
const errorKey = data?.errorMsg?.errorKey || '';
if (status === 401 && errorKey === 'security.sessionExpired') {
    authStore.logout();  // fires correctly
    router.push('/login');
}
```

### Why Backend Fix (not Frontend Workaround)

| Approach | Pros | Cons |
|---|---|---|
| **B1 (Backend fix)** | Aligns with application error pattern; robust; works throughout system | Slight more backend complexity |
| **B2 (Frontend workaround)** | Simple | Masks other 401 scenarios; inconsistent with app patterns |

**Decision:** B1 (Backend) — more principled, better for long-term maintainability.

---

## Related Fixes (Enabled by Above Decisions)

### M1: Idle Tab Force-Logout ✅ FIXED

**Before:** Background tab's timer fires `session:expired` even though foreground tab has active session

**After:** Idle tab reads `rint` cookie before firing timer, discovers sibling extended JWT, resets its own timer

**How it works:**
```javascript
// sessionManager.js — check interval (every 30 seconds)
const expiresAt = readRintCookie();  // absolute timestamp
const remaining = expiresAt - Date.now();

if (remaining <= 0) {
  // Expired
  dispatch session:expired
} else if (remaining < warningThreshold) {
  // Show warning
} else {
  // Session OK, timer continues
}
```

Tab A makes request → updates `rint` to future timestamp
Tab B reads `rint` on next check interval → sees increased value → resets its countdown

### M2: Logout Bounces Back ✅ SPECIFIED

**AC:** Logout button must clear Pinia `authStore` (not just call API)

```javascript
// useSession.js:handleLogout() — to be fixed
stopSessionMonitoring();
await authApi.logout();           // POST /api/logout
authStore.logout();               // Clear Pinia state ← ADD THIS
playerStore.resetSelfPlayerId();
cleanup();
deleteUserCookie();               // Clear skp
router.push('/login');
```

Without this, `skp` cookie survives and user bounces back into app.

### M4: Test Breakage ✅ SPECIFIED

**Files affected:**
- `JwtManagerImplTest.java:291-292`
- `JwtManagerImplTest.java:504-505`

**Before:**
```java
assertThat(sessionTimeoutWarningInterval)
  .isEqualTo(String.valueOf(JWT_TTL.minusMinutes(5).toMillis()));
  // Expects: "600000"
```

**After:**
```java
long rintValue = Long.parseLong(extractCookie(mockResponse, SESSION_REFRESH_COUNTDOWN));
assertThat(rintValue).isGreaterThan(System.currentTimeMillis() + 14 * 60 * 1000)  // within JWT TTL range
                    .isLessThan(System.currentTimeMillis() + 16 * 60 * 1000);
// Expects: epoch ms within +/- 1 minute of (now + JWT_TTL)
```

---

## Implementation Sequence

**Story 1.7a (Documentation) — Ready Now**
1. Update docs to use actual code paths
2. Add backend comments for endpoint clarity
3. Fix package path errors

**Story 1.7b (Backend Behavioral Changes) — After 1.7a**

Phase 1: Absolute Timestamp Contract
1. [ ] Update `JwtManagerImpl.createLoginCookies()` to set `rint = epoch ms`
2. [ ] Update `JWTAuthorizationFilter.extendTtlOfToken()` to refresh `rint` with new epoch
3. [ ] Update `JwtManagerImplTest` assertions to validate epoch timestamp range
4. [ ] Test: multiple sequential requests show increasing `rint` values

Phase 2: Fix 401 Error Path
1. [ ] Update `JWTAuthorizationFilter` catch block to emit ErrorDto
2. [ ] Test: axios interceptor correctly detects `errorKey === 'security.sessionExpired'`

Phase 3: Multi-tab & Logout Fixes
1. [ ] Frontend `syncWarningThresholdFromCookie()` reads absolute timestamp
2. [ ] Test: idle tab discovers sibling's session extension
3. [ ] Fix `useSession.handleLogout()` to clear `authStore`
4. [ ] Test: logout button fully clears session

---

## Blockers Cleared

| Blocker | Status |
|---|---|
| B1: Formula incompatibility | ✅ Absolute timestamp design eliminates it |
| B2: Decreasing `rint` impossible | ✅ Absolute timestamp (increases) replaces countdown |
| B3: 401 error path mismatch | ✅ Backend fix specified (emit ErrorDto from filter) |
| M1: Idle tab force-logout | ✅ Multi-tab sync via absolute timestamp |
| M2: Logout bounces back | ✅ AC specified (clear authStore) |
| M4: Test breakage | ✅ New assertions specified |

**Story 1.7b is now READY FOR DEVELOPMENT** 🚀
