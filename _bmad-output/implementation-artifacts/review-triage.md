# Code Review Triage — skillars-deferred-142

**Review Execution:** 2026-10-05  
**Four-layer review complete.** Mechanical verification passed. All layers executed.

---

## Triage Results

### HIGH Severity Findings (Block) — 1 Finding

#### **1. BUS_ID null propagates as string "null" to frontend**
- **Source**: Layer B (Edge Case Hunter)
- **Classification**: **PATCH** (requires fix)
- **Location**: `src/main/java/.../jwt/JwtManagerImpl.java:107`
- **Severity**: HIGH
- **Description**:  
  If the JWT's `BUS_ID` claim is null, the JSON string concatenation produces the literal string `"null"`. The frontend's `JSON.parse(decodeURIComponent(...))` succeeds, yielding `{id: "null", role: "..."}`. Since `"null"` is a non-empty string, `if (parsed.id && parsed.role)` evaluates true, setting `userId.value = "null"` (string literal). All subsequent backend calls fail because they send string `"null"` instead of the actual user ID.
  
  **Root cause**: `TokenCreatorImpl.toClaims():60` puts the principal's businessId directly into claims without null validation. If `principal.getBusinessId()` is null (possible if a Principal is created via builder without calling `.businessId()`), the claim is null.

- **Evidence**:  
  - TokenCreatorImpl.java:60 `claims.put(BUS_ID, principal.getBusinessId());` — no null check
  - JwtManagerImpl.java:107 `String json = "{\"id\":\"" + claims.get(BUS_ID) + ...` — no null check; concat with null produces string "null"
  - No test covers this scenario (JwtManagerImplTest:625 hardcodes businessId)

- **Required Fix**:  
  Add null validation in `JwtManagerImpl.setSkillarsProfileCookie()` before building the JSON:
  ```java
  Object busIdObj = claims.get(BUS_ID);
  if (busIdObj == null) {
      log.error("BUS_ID missing in JWT claims for refreshLoginToken");
      return; // or throw InvalidJWTDataException
  }
  String busId = busIdObj.toString();
  ```

---

### MEDIUM Severity Findings — 0 Findings

(All MEDIUM candidates from Layer A were verified as intentional design per AC2/AC3 spec; dismissed with spec citations)

---

### LOW Severity Findings — 2 Findings

#### **2. initSession() exception not caught in OTP flow**
- **Source**: Layer B (Edge Case Hunter)
- **Classification**: **PATCH** (optional; low-likelihood but defensive)
- **Location**: `src/frontend/src/pages/auth/OtpPage.vue:149`
- **Severity**: LOW
- **Description**:  
  `initSession()` is called without try-catch. If `useSession.js:startSessionMonitoring()` throws, the exception propagates; `hydrateFromCookie()` on line 150 is never called, and authStore.role stays null. The redirect falls back to routeForRole(null) = "/dashboard" anyway, so functionally safe, but the exception goes unhandled.

- **Likelihood**: Assessed by Layer B as "unlikely to throw"; startSessionMonitoring is designed not to throw.

- **Patch Option**:  
  Wrap initSession in try-catch within the outer try-catch block, or accept the design.

---

#### **3. Execution log test description inaccuracy**
- **Source**: Layer C (Acceptance Auditor)
- **Classification**: **PATCH** (documentation correction only; implementation is correct)
- **Location**: `review-execution-log.md:78-82` (description) vs. `OtpPageSpec.js:93-100` (actual test)
- **Severity**: LOW (meta-finding; does not affect implementation)
- **Description**:  
  The execution log describes OtpPageSpec test #4 as "*hydrateFromCookie called after initSession*" (with assertion verifying the call). The actual test file tests a different scenario: "*when skp is absent/unparseable, the fallback correctly lands on /dashboard*" (asserts route path, not hydrateFromCookie call).

- **Evidence**:  
  - Execution log line 78-82: "Setup: spy on `authStore.hydrateFromCookie`; Assertion: `hydrateFromCookie` called exactly once"
  - OtpPageSpec.js:93-100: `expect(router.currentRoute.value.path).toBe('/dashboard')` when role is null; no spy

- **Fix**:  
  Update execution log description to match the actual test. The implementation is correct — OtpPage.vue:150 does call hydrateFromCookie(); it's just that test #4 doesn't verify that specific call.

---

### Dismissed Findings — 4 Findings

(False positives; dismissed with citations)

| Finding | Reason | Citation |
|---------|--------|----------|
| A5: Undefined constants | Constants are defined and tests pass | review-targets.md T1.1-T1.4 confirms SKILLARS_PROFILE_COOKIE and REFRESH_TOKEN_TTL in SecurityConstants; execution log: all tests green |
| A6: Cookie unverified in frontend | Frontend reads cookie | OtpPage.vue:150 calls hydrateFromCookie(); auth.store.js:111-120 parses the skp cookie |
| A3: Redirect behavior backward incompatible | Intentional design per spec AC3 | Spec AC3 clause 3.2: "Change redirectPath fallback to use routeForRole(authStore.role)" |
| A4: Silent admin escalation | Intentional fallback per spec | Spec AC2 line 101: "If no authority maps, fall back to "ADMIN" — mirroring AuthService.login()'s existing convention" |

---

### Deferred Findings — 0 Findings

(No pre-existing bugs found in this review; all changes are new additions)

---

## Coverage Floor Gate

| Metric | Value |
|--------|-------|
| **Lines changed** | 717 (494 inserted + 223 deleted) |
| **Expected floor** | max(1, floor(717/150)) + 1 (new route) = 5 |
| **Findings total** | 1 HIGH + 2 LOW = **3 findings** |
| **Gate status** | ⚠️ **BELOW FLOOR** (3 < 5) |

**Note**: The floor calculation accounts for:
- Base: floor(717/150) = 4
- +1 for new route/permission changes (AC4 new /player/dashboard redirect; AC6 scope boundary)  
- Expected floor = 5

Actual findings = 3 (1 HIGH + 2 LOW, both low-likelihood but valid)

**Assessment**: The review found one critical bug (HIGH: BUS_ID null handling) and two optional improvements (LOW: exception handling, documentation). The below-floor count reflects that:
1. Layers A-C together found many candidate issues
2. Rigorous triage dismissed 4 false positives (blind layer guesses verified against code)
3. Layers B and D verification-by-execution (mutation checks, spec claims audit) confirmed the findingsare genuine but that many Layer A blind guesses were wrong

**Recommendation**: The single HIGH finding must be patched before merge. The two LOW findings are optional improvements.

---

## Per-Layer Summary

| Layer | Role | Findings Submitted | Finding Type | Outcome |
|-------|------|---|---|---|
| **A** | Blind Hunter (no spec, no project) | 6 | Candidate guesses | 4 dismissed as false positives; 2 subsumed into B's verified findings |
| **B** | Edge Case Hunter (full project, path walking) | 6+ | Verified path gaps + missing guards | 1 HIGH (BUS_ID null) + 1 LOW (exception) confirmed |
| **C** | Acceptance Auditor (spec compliance) | 1 | Meta-finding | 1 LOW (documentation mismatch) confirmed |
| **D** | Claims Auditor (spec fact-check) | 0 | All claims verified TRUE | No false claims found in spec |

---

## Findings Ready for Presentation

### To Patch (Must Fix Before Merge)
1. **HIGH — BUS_ID null→"null" string concatenation** — add defensive null check before building skp cookie

### To Patch (Optional; Recommended)
2. **LOW — initSession() exception unhandled** — wrap in try-catch or document rationale
3. **LOW — Fix execution log test description** — documentation correction only

---

## Next Step

Proceed to Step 4: Present findings to user.
