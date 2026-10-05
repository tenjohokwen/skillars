# Verification Target List — skillars-deferred-142

**Generated:** 2026-10-05 (manual review execution gate)

---

## Section 1: Verification Targets (T1–T7 from Diff)

| # | Category | Target | Contract / Finding | Status |
|---|---|---|---|---|
| T1.1 | Member Read | `claims.get(BUS_ID)` in `JwtManagerImpl:107` | Must exist in Claims (placed by `TokenCreatorImpl.toClaims:60`); type `String`; verified: ✓ |
| T1.2 | Member Read | `authStore.role` in `OtpPage.vue:155` | Hydrated by `hydrateFromCookie()` (`:150`); type `String\|null`; may be null on missing/unparseable cookie; verified: ✓ |
| T1.3 | Member Read | `route.query.redirect` in `OtpPage.vue:151` | Vue Router `route` object query param; type `String\|undefined`; verified: ✓ |
| T1.4 | Member Read | `claims.get(ROLES)` (implicit in `getAuthoritiesSilently`, line 126) | Type `String` (JSON list); verified in `TokenCreatorImpl:50`; verified: ✓ |
| T2.1 | Method Call | `getAuthoritiesSilently(claims)` in `JwtManagerImpl:95` | Private method at `:124-132`; returns `Collection<SimpleGrantedAuthority>`; never null (empty List on JSON error); verified: ✓ |
| T2.2 | Method Call | `authStore.hydrateFromCookie()` in `OtpPage.vue:150` | Action in `auth.store.js:111-120`; parses URL-decoded JSON `{"id","role"}`; verified: ✓ |
| T2.3 | Method Call | `routeForRole(authStore.role)` in `OtpPage.vue:155` | Function in `src/router/roleRoutes.js:24-30`; returns string route path; accepts null, returns `/dashboard` fallback; verified: ✓ |
| T2.4 | Method Call | `SkillarsRole.valueOf(name)` in `JwtManagerImpl:99` | Java enum; throws `IllegalArgumentException` on no match; valid values `COACH, PARENT, PLAYER, ADMIN`; verified: ✓ |
| T2.5 | Method Call | `StringUtils.removeStart(authority.getAuthority(), "ROLE_")` in `JwtManagerImpl:96` | Commons Lang 3 utility; verified imported `:18`; verified: ✓ |
| T2.6 | Method Call | `CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue, false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax")` in `JwtManagerImpl:109` | Existing utility; 6-arg overload with SameSite; verified: ✓ |
| T2.7 | Method Call | `URLEncoder.encode(json, StandardCharsets.UTF_8)` in `JwtManagerImpl:108` | Java stdlib; verified imported `:24-25`; verified: ✓ |
| T2.8 | Method Call | `router.push(safePath)` in `OtpPage.vue:156` | Vue Router method; verified: ✓ |
| T2.9 | Method Call | `router.replace('/player/dashboard')` in `VideoManagementPage.vue:108` | Vue Router method; verified: ✓ |
| T3.1 | Precedent File | `LoginPage.vue:168-175` open-redirect guard | Pattern: `typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')`; **VERIFIED: OtpPage.vue now uses identical guard at `:152-154`**; verified: ✓ |
| T3.2 | Precedent File | `AuthService.login():131-134` skp cookie building | Pattern: JSON `"{\"id\":\""+id+\",\"role\":\""+role+"\"}"`, URL-encoded, passed to `CookieUtil.addCookie`; **VERIFIED: JwtManagerImpl now replicates exact shape at `:107-109`**; verified: ✓ |
| T4.1 | i18n Key | `auth.twoFactorTitle` (OtpPage.vue:6) | Scope: all three locales en-US/fr-FR/de-DE; verified: ✓ |
| T4.2 | i18n Key | `auth.enterOtp`, `auth.otpSent`, `auth.otpCodeExpiry` (OtpPage.vue:10-11) | Verified in all three locales: ✓ |
| T4.3 | i18n Key | `error.helpCode`, `auth.otpNotReceived`, `auth.resendOtp`, `common.back`, `auth.login` (OtpPage.vue) | Verified in all three locales: ✓ |
| T4.4 | i18n Key | `video.management.accessDenied`, `video.management.loadError` (VideoManagementPage.vue) | Verified in all three locales: ✓ |
| T5.1 | Enum Literal | `SkillarsRole.COACH`, `PARENT`, `PLAYER`, `ADMIN` in story text | Confirmed at `:99` via `SkillarsRole.valueOf()`; `ROLE_LTD_ADMIN` (`:100`) intentionally maps to fallback `"ADMIN"`; **VERIFIED: V139 seed data confirms 1:1 mapping for role-equivalent authorities**; verified: ✓ |
| T6.1 | Route/Permission | `/otp` route (`routes.js:24-28`) | `meta: { requiresGuest: true }`; unchanged; verified: ✓ |
| T6.2 | Route/Permission | `/player/videos` route (`routes.js:255-260`) | `meta: { requiresAuth: true, role: 'PLAYER' }`; unchanged; verified: ✓ |
| T6.3 | Route/Permission | `/player/dashboard` route (`routes.js:261-268`) | `meta: { requiresAuth: true, role: 'PLAYER' }`; exists (created by deferred-141); verified: ✓ |
| T7.1 | Shared Symbol | `routeForRole()` function (`roleRoutes.js:24-30`) | Used by LoginPage.vue (:174) + now OtpPage.vue (:155); no change; consumers: 2 (LoginPage, OtpPage); verified: ✓ |
| T7.2 | Shared Symbol | `hydrateFromCookie()` action (`auth.store.js:111-120`) | Used by LoginPage.vue + now OtpPage.vue; no change; parses `skp` cookie; verified: ✓ |
| T7.3 | Shared Symbol | `SKILLARS_PROFILE_COOKIE` constant (`SecurityConstants:104`) | Used by: AuthService.login() (:133), AuthService.refresh() (:219), JwtManagerImpl.setSkillarsProfileCookie() (new, `:109`); no change to constant; verified: ✓ |
| T7.4 | Shared Symbol | `CookieUtil.addCookie()` (`CookieUtil:17-26`) | Called by: AuthService.login() (:134), AuthService.refresh() (:220), JwtManagerImpl.setSkillarsProfileCookie() (new, `:109`); no change; verified: ✓ |
| T7.5 | Shared Symbol | `.auth-banner` CSS class (scoped in OtpPage.vue/LoginPage.vue) | Used in OtpPage.vue `:41` (error banner); unchanged; no shared-symbol change; verified: ✓ |

**Summary:** 28 targets verified, all resolvable. No unresolved rows. All shared symbols (T7) have enumerated consumers below.

---

## Section 2: Blast-Radius Sweep for Shared Symbols

### routeForRole() (`src/router/roleRoutes.js:24-30`)
- **Consumers:** LoginPage.vue `:174`, OtpPage.vue `:155` (new)
- **Change impact:** No change to function; OtpPage's new call is **intended and verified**.
- **Blast radius:** None (function unchanged).

### hydrateFromCookie() (`src/stores/auth.store.js:111-120`)
- **Consumers:** LoginPage.vue `:168` (onMounted), OtpPage.vue `:150` (new in handleSubmit)
- **Change impact:** No change to function; OtpPage's new call is **intended and verified**.
- **Blast radius:** None (function unchanged).

### SKILLARS_PROFILE_COOKIE constant (`src/infrastructure/security/SecurityConstants:104`)
- **Consumers:** AuthService.login() `:133`, AuthService.refresh() `:219`, JwtManagerImpl.setSkillarsProfileCookie() (new `:109`)
- **Change impact:** No change to constant; new consumer in JwtManagerImpl is **intended and scoped to refreshLoginToken path**.
- **Blast radius:** None (constant unchanged).

### CookieUtil.addCookie() (`src/infrastructure/security/CookieUtil:17-26`)
- **Consumers:** AuthService.login() `:134`, AuthService.refresh() `:220`, JwtManagerImpl.setSkillarsProfileCookie() (new `:109`), JwtManagerImpl.generateAnonymousSession() `:145`, JwtManagerImpl.removeTwoFactorCookie() (indirect via removeSessionCookie)
- **Change impact:** No change to function; new call from JwtManagerImpl.setSkillarsProfileCookie() is **intended**.
- **Blast radius:** None (function unchanged).

### ROLES claim (implicit in getAuthoritiesSilently)
- **Reader:** JwtManagerImpl.setSkillarsProfileCookie() (new `:95`), JwtManagerImpl.createLoginCookies() (existing `:229-230`)
- **Change impact:** No change to claim format; new derivative logic in setSkillarsProfileCookie is **self-contained, does not modify claim**.
- **Blast radius:** None (claim unchanged).

**Summary:** 5 T7 symbols swept; all have enumerated consumers; none introduce regressions.

---

## Section 3: Execution Gate Results

### 3.1: Test Suite Execution

**Backend tests (JwtManagerImpl, SecurityIT):**
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```
- `SecurityIT.loginWith2FAWhenAccountEnabled()` with new skp presence assertion: ✓ PASS
- `JwtManagerImplTest.testRefreshLoginToken_setsSkillarsProfileCookieFromRolesClaim()`: ✓ PASS (new, role-mapping case)
- `JwtManagerImplTest.testRefreshLoginToken_fallsBackToAdminWhenNoAuthorityMapsToSkillarsRole()`: ✓ PASS (new, fallback case)

**Frontend tests:**
```
Test Files  2 passed (2)
     Tests  5 passed (5)
```
- `OtpPageSpec.js` (4 tests): ✓ PASS
  - Redirect fallback resolves per role
  - Unsafe/absolute redirect rejected
  - skp-absent degraded case → `/dashboard`
  - `hydrateFromCookie` called after verify
- `VideoManagementPageSpec.js` (1 test): ✓ PASS
  - 403 redirects to `/player/dashboard`, not `/dashboard`

### 3.2: Mutation Checks

**AC2 mutation (delete skp cookie setting):**
- Reverted `JwtManagerImpl:84` (`setSkillarsProfileCookie(res, claims)` call)
- `SecurityIT.loginWith2FAWhenAccountEnabled()` assertion: ✗ FAIL (as expected)
- `JwtManagerImplTest.testRefreshLoginToken_setsSkillarsProfileCookieFromRolesClaim()`: ✗ FAIL (as expected)
- Restored code: ✓ PASS (confirmed)
- **Verdict: Mutations are genuine, not false positives.**

### 3.3: Linting

**ESLint + Prettier check:**
- `src/frontend/src/pages/auth/OtpPage.vue`: ✓ PASS (0 violations)
- `src/frontend/src/pages/VideoManagementPage.vue`: ✓ PASS (0 violations)

### 3.4: CSS/Layout Measurement

**Story AC5:** Testing & Verification (no visual AC)
- **Skipped:** No CSS/layout changes in this story; no measurement needed.

---

## Summary

**Execution gate status:** ✓ PASSED
- 28/28 verification targets resolved
- 0 unresolved rows
- Backend: 3 tests green, mutations confirmed genuine
- Frontend: 5 tests green (OtpPage 4/4, VideoManagementPage 1/1)
- Linting: 0 violations
- Coverage floor calculation: `lines = 494 + 223 = 717; floor = max(1, floor(717/150)) = 4`. Findings expected: ≥4 (4 base + 0 new endpoint + 1 new permission/route change T6 + 0 CSS AC + 0 mutation claim = 4 minimum). **Actual findings from review layers will be compared.**

