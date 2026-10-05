# skillars-deferred-143: Account-Lock Enforcement Gap and Incomplete Forced-Logout Session Termination

**Story ID:** deferred-143
**Epic:** Deferred-Work Cleanup Bundle
**Status:** done
**Scope Level:** Small-Medium (four methods changed in `AuthService` — `login()`, `refresh()`, `logout()`, `clearAuthCookies()` — plus a new constructor dependency; one method added + one deleted in `SecurityUtil`, plus a new constructor dependency; one new `@Transactional(REQUIRES_NEW)` query method on `RefreshTokenRepository`; one catch-block change in `JWTAuthorizationFilter`. No schema change, no frontend change, no new REST endpoint.)
**Date Created:** 2026-10-05
**Pre-Implementation Review:** Two rounds of `story-review.md` completed 2026-10-05 against HEAD `3a62698c`, both independently re-verified rather than trusted (see "Resolution of pre-implementation review" at the end of this file for the full disposition — round 2 found two load-bearing design defects that are reflected in the design below, not just citation drift).
**Source:** Manual security analysis of the auth cookie/session pipeline (requested directly by Mbah, 2026-10-05) — not sourced from `deferred-work.md` or a `/bmad-code-review` run.

---

## User Story

As **the operator of this application**, I want a locked account to be unable to obtain a *new* session via login, and an existing session to lose access within the system's normal revocation window, so that **locking an account is a real control, not a cosmetic one**.

As **the operator of this application**, I want a forced, server-initiated logout caused by a genuine denial (stolen/fixed token, locked account, disabled account, malformed token) to fully revoke the session's refresh token and clear every auth cookie — durably, even if the request that discovered the denial itself fails — so that **a hardened `POST /api/auth/refresh` (today dead, but a real production endpoint) cannot be used to walk back in on a token that should already be dead**.

---

## Context: Current State (verified against HEAD `3a62698c`, 2026-10-05; re-verified by two rounds of `story-review.md`, same HEAD)

### Finding 1 — `AuthService.login()`/`refresh()` never check `user.isLocked()` — unbounded for new logins, already bounded to ~5 minutes for existing sessions

`AuthService.java:95-97` checks only `user.isActivated()`:
```java
if (!user.isActivated()) {
    throw new DisabledException("Account is not activated");
}
```
`AuthService.refresh()` (`:139-223`) loads the user at `:188` and checks **nothing** about account status before minting a new JWT.

`User.lock()` (`User.java:380-386`, doc sentence at `:381`: *"Locks this user account, preventing login."*) sets `User.isLocked()` (`:246-248`). **`isLocked()` is never checked in `AuthService.login()` or `refresh()`** — but it is checked in 13 other places in `src/main` (`Principal.java:151`; `RegistrationOtpResendSupport.java:71`; three call sites each in `CoachRegistrationService.java`, `ParentRegistrationService.java`, `PlayerRegistrationService.java`; `UserErasedEventListener.java:25`; plus `User.java:409,426,457`'s own password-reset guards). Those three registration services already implement the exact "reject a locked user" shape `ensureAccountIsLive` needs (e.g. `CoachRegistrationService.java:129`) — real in-repo precedent to follow, not a novel pattern.

**The gap is real but narrower than "locking does nothing."** `DaoAuthProvider.authorize()` — the call that enforces `accountNonLocked` via `Principal.instanceFrom`'s wiring (`Principal.java:148-151`) into Spring's `AccountStatusUserDetailsChecker` — is **not** confined to the dead `/authenticate` path. It also runs inside the *live* `JWTAuthorizationFilter.attemptAuthorization()`, at `:189` (DB-refresh-token lapsed) and `:200` (all refresh tokens revoked), gated on `DB_REFRESH_TOKEN_INTERVAL = 5 min` (`SecurityConstants.java:120`). The filter's own class Javadoc (`:80-85`) states this directly: *"`daoAuthProvider.authorize(...)` re-checks the account against the DB so locked / deactivated / force-logged-out users are caught."* So:
- an **existing** session is force-denied within **≤5 minutes** of the account being locked, with zero code change, today — bounded, by design, not a defect;
- but a locked user can obtain a **brand-new** session via `POST /api/auth/login` **indefinitely**, since `AuthService.login()` is a completely separate, hand-rolled authentication path (`AuthService.java:90-93`: `passwordEncoder.matches(...)` directly, never routed through `daoAuthProvider`/Spring's `AuthenticationManager`) — this part is genuinely unbounded and is the strongest case for this story.

This is not purely hypothetical: `GdprErasureService.eraseTransactional()` (`:277-278`) already calls `user.setLocked(true)` in production — but it also sets `activated=false` in the same call, so the *existing* `isActivated()` check happens to catch that one caller. `UserAdminService.lockUserAccount()` (`UserAdminService.java:116-122`, `@PreAuthorize(HAS_ADMIN_ROLE)`) sets `locked=true` **without** also deactivating — it has no controller wiring yet (confirmed by repo-wide grep: only the service method and `UserServiceIT` reference it) — so it is a landmine for whoever wires it up: the account would still be able to log back in at will via `POST /api/auth/login`, and its *existing* sessions would survive up to 5 more minutes.

**Why the gap exists:** `/authenticate` (`JWTAuthenticationFilter` → Spring's real `AuthenticationManager`/`DaoAuthProvider.authorize()`) correctly rejects locked/disabled accounts, but `authApi.login()` (the frontend call to it) has **zero callers anywhere in `src/frontend/src`** — dead code. The live login path, `LoginPage.vue:167` → `authApi.skillarsLogin()` → `POST /api/auth/login` → `AuthService.login()`, hand-rolls its own password check and never touches that pipeline.

**Out of scope for this story:** whether to delete or wire up the dead `/authenticate` + OTP pipeline is a product decision, not a safety fix. This story makes `AuthService.login()`/`refresh()` enforce the same lock check the live filter path already enforces for existing sessions.

### Finding 2 — forced server-side logout clears too few cookies and never revokes the refresh token, for the subset of causes that actually warrant it

`JWTAuthorizationFilter.doFilterInternal()` catches `AccountStatusException | AuthorizationException | AccessDeniedException` at `:150` and calls `securityUtil.logout(res)` at `:152` for **every** cause reaching that block, before writing the 401. That set is wide: per the catch block's own comment, it includes `AccountExpiredException`, `CredentialsExpiredException`, `LockedException`, `InvalidJWTDataException`, `JWTTheftException`, `JWTExpiredException`, and missing-token `AccessDeniedException` — **and it also catches `AuthorizationException("Missing rights", MISSING_RIGHTS)`** from `daoAuthProvider.checkAuthorities(...)` (an authenticated user hitting an endpoint their role doesn't permit — a routine "wrong page for this role" event, not a session compromise).

`SecurityUtil.logout()` (`SecurityUtil.java:137-140`) only calls `loginTokenManager.deleteLoginToken(response)`, which (`JwtManagerImpl.java:182-190`) clears exactly six cookies — `potc`, `bcookie`, `user`, `admin`, `ION`, `rint` — and **never** `rtkn` (`REFRESH_TOKEN_COOKIE`) or `skp` (`SKILLARS_PROFILE_COOKIE`), and never revokes the underlying `RefreshToken` DB row.

Compare `AuthService.logout()` (`:225-239`), which correctly clears `rtkn` + `skp` in addition to `deleteLoginToken()` and marks the presented refresh token used (`:226-235`). Only the voluntary logout path does this.

**But not every cause caught at `:150` should get the same treatment.** Two of them are routine, expected, unauthenticated-path traffic, and the filter's own code already says so:
- `JWTExpiredException` (`JWTExpiredException.java:8`, extends `AuthorizationException`) fires on every ordinary 15-minute idle-out.
- `MissingAuthenticationException` (same hierarchy) fires on every tokenless request to a secured URL — crawlers, stale bookmarks, pre-login SPA routes.

`JWTAuthorizationFilter.java:146-149` (`mintHelpCode`'s comment) and `:304-311` (`maybePublishSecurityAlert`'s Javadoc, from `skillars-deferred-90` AC5/F22) both say this explicitly: these two are *"expected traffic on an unauthenticated, unrate-limited path"*, and `SecurityAlertEvent` is deliberately **not** fired for them — *"alerting on those would turn an unauthenticated, unrate-limited path into an audit-trail flood / DB-write amplifier."* Attaching a DB lookup-and-revoke to the same two causes would reintroduce exactly the write-amplification class that fix exists to prevent, on a path with no rate limiting. It would also be self-defeating against this story's own purpose: `rtkn` has a deliberate 7-day TTL (`SecurityConstants.java:105`) that outlives the 15-minute JWT specifically so `POST /api/auth/refresh` can trade it for a new session — revoking it on every ordinary idle-out means that endpoint, the moment anyone wires the SPA to call it, is permanently useless, since the token would already be dead before it's ever needed.

The existing `maybePublishSecurityAlert` already draws this routine-vs-genuine line with a `genuineDenial` predicate (`:312-319`): `JWTTheftException || InvalidJWTDataException || AccountStatusException`. This story reuses that shape for the revocation decision — **with one correction**: `DaoAuthProvider.authorize()` (`:47-51`) catches Spring's `AccountStatusException` internally and rewraps it as this project's own `AuthorizationException` with `SecurityError.ACCOUNT_NOT_LOGIN_ABLE`, so a locked/disabled account arriving via the filter's DB-reauth path (`:189`/`:200`) never reaches the catch block as a raw `AccountStatusException` — it arrives wrapped, and a naive `instanceof AccountStatusException` check would silently miss the exact accounts this story exists to protect. (The existing `maybePublishSecurityAlert` has this same blind spot today — pre-existing, out of scope, but worth not repeating here.)

### Finding 3 — `AuthService.refresh()` has no account-status check, which matters as defence-in-depth for a currently-dead endpoint, not as an active exploit

`sessionManager.js:265` calls `sessionApi.refresh()`, which is `session.api.js:4-6`: **`GET /refresh`**, not `POST /api/auth/refresh`. `GET /refresh` is a `SECURED_MAPPINGS` endpoint (`AppEndpoints.java:19,67`) — it passes through `JWTAuthorizationFilter.attemptAuthorization()` *before* reaching `SessionRefreshFilter`, exactly as that filter's own Javadoc states (`:90-97`: *"This is why `GET /refresh` keeps a session alive… For full token rotation… use `POST /api/auth/refresh` instead"*). That means `GET /refresh` already gets the DB re-auth check described in Finding 1 — a locked account calling it is **denied, not resurrected**.

`POST /api/auth/refresh` (`authApi.skillarsRefresh()`, `auth.api.js:45-47`) — the endpoint `AuthService.refresh()` actually backs — has **zero callers anywhere in `src/frontend/src`**, the same dead-code shape already established for `authApi.login()`. (Independently corroborated by the checked-in `src/frontend/coverage/coverage-final.json`, which records `skillarsRefresh` at hit count `0`.)

**So there is no live resurrection chain today.** `AuthService.refresh()`'s missing account-status check is real and worth fixing — for the same reason `AuthService.login()`'s is — but as defence-in-depth for whenever `POST /api/auth/refresh` is wired up, not as an active bypass of anything happening in production right now.

---

## Centralized Remediation Design

Two single-owner responsibilities, not three independent patches:

### A. Account-liveness check — centralized in `AuthService`

A new private helper, called from both methods that currently have incomplete or missing checks. Not a new class: `AuthService` is the only class that holds the `User` entity in both places this is needed, and it mirrors the shape already used three times over in the registration services (Finding 1).

```java
private void ensureAccountIsLive(User user) {
    if (!user.isActivated()) {
        throw new DisabledException("Account is not activated");
    }
    if (user.isLocked()) {
        throw new LockedException("Account is locked");
    }
}
```

- `login()` (`:95-97`): replace the existing standalone `if (!user.isActivated())` block with a call to `ensureAccountIsLive(user)`.
- `refresh()` (`:188`): add a call to `ensureAccountIsLive(user)` immediately after `userRepository.findById(...)` resolves the user, **before** the new `RefreshToken` is created/saved (`:195-201`) and before `createLoginToken` is called (`:203`). On failure, fall through to Part B's teardown.

`LockedException`/`DisabledException` need no new error-handling code: `ApiAdvice.java:274-279` and `:281-286` already map them to 401 with `errorKey` `security.accNotEnabled`/`security.accLocked`, and all three frontend locales already carry translated copy for both keys. This story reuses existing, working infrastructure.

### B. Session termination — promoted to one method on `SecurityUtil`, with the refresh-token revocation done as an independently-committing bulk update

**Why not a plain `find → setUsed(true) → save`, the way `AuthService.logout()` does it today:** `AuthService` carries class-level `@Transactional` (`AuthService.java:47`); `AuthResource` carries none (confirmed: no `@Transactional` import or annotation anywhere in `AuthResource.java`); `spring.jpa.open-in-view: false` (`application.yaml:158`) means no view-layer transaction extends it either. So `AuthService.refresh()` is its own outermost transaction boundary. `LockedException` is a `RuntimeException`, so throwing it from inside `ensureAccountIsLive` — called from `refresh()` — rolls back the **entire** method's transaction, including the refresh token's `setUsed(true)`/`saveAndFlush` that already happened at `:177-180` *before* `ensureAccountIsLive` runs, and it would just as surely discard a plain managed `save()` performed afterward on the rejection path. **The codebase already knows this and has a working pattern for it:** `RefreshTokenRepository.markAllUsedByUserId` (`:30-33`) is annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)` specifically so the revocation survives a surrounding rollback — documented at `:20-28`, citing `skillars-deferred-100` AC3 / `skillars-deferred-101` AC11. This story adds a sibling method using the identical pattern, keyed by token hash instead of user id:

```java
// RefreshTokenRepository.java — new method, same REQUIRES_NEW shape as markAllUsedByUserId
// (:30-33) and for the identical reason: a caller that revokes-then-throws (AuthService.refresh's
// ensureAccountIsLive rejection) must not have the revocation undone by its own rollback.
@Modifying
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 WHERE r.tokenHash = :tokenHash")
void markUsedByTokenHash(@Param("tokenHash") String tokenHash);
```

```java
// SecurityUtil.java
// New dependency: RefreshTokenRepository. SecurityUtil currently depends only on
// LoginTokenManager (SecurityUtil.java:40,43) — confirmed no circular risk: neither
// LoginTokenManager nor its sole implementation JwtManagerImpl reference AuthService or
// SecurityUtil, and RefreshTokenRepository is a leaf Spring Data repository.
//
// The revocation below is safe to call from ANY transactional context — including none at all
// (the filter has no ambient transaction) — because markUsedByTokenHash commits independently in
// its own REQUIRES_NEW transaction regardless of caller. SecurityUtil itself needs no
// @Transactional annotation (and could not be given one even if desired — it is `public final
// class SecurityUtil`, SecurityUtil.java:38, implementing no interface, so Spring cannot proxy it).
public void terminateSession(HttpServletRequest req, HttpServletResponse res) {
    SecurityContextHolder.clearContext();
    String rawToken = CookieUtil.getCookieValue(req, REFRESH_TOKEN_COOKIE);
    if (rawToken != null) {
        refreshTokenRepository.markUsedByTokenHash(sha256Hex(rawToken));
    }
    loginTokenManager.deleteLoginToken(res);
    CookieUtil.removeCookie(REFRESH_TOKEN_COOKIE, res, true, "Lax");
    CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, res, false, "Lax");
}
```

Needs a `sha256Hex` helper identical to `AuthService`'s existing private static one (`AuthService.java:259-267`) — duplicate the four lines; this project has no existing shared crypto-helper location and one two-call-site static method does not warrant inventing one.

**`SecurityUtil`'s constructor is hand-written, not Lombok-generated** (`SecurityUtil.java:43-45`, unlike `AuthService`'s `@RequiredArgsConstructor`) — adding `RefreshTokenRepository` means manually adding the parameter, the `private final` field, and the assignment.

**Placement decision (`SecurityUtil` vs. `AuthService`), made explicitly:** the pre-implementation review raised, as a judgment call rather than a defect, that `AuthService` — which already owns `RefreshTokenRepository` and already contains nearly this exact code at `:225-245` — is the lower-blast-radius home, since `SecurityUtil` is injected across 52 other files as a stateless `SecurityContext` accessor with no DB awareness today. This story keeps `terminateSession` on `SecurityUtil` anyway: the `REQUIRES_NEW` design above means `SecurityUtil` never needs transactional semantics of its own (the only reason the placement would have mattered), and keeping it there avoids widening `JWTAuthorizationFilter`'s constructor and `SecurityConfiguration`'s manual filter-wiring (`SecurityConfiguration.java:200-209`) with a new `AuthService` dependency the filter does not otherwise need. `SecurityUtil` does gain one new field it will carry for exactly one method's use — judged an acceptable, narrow widening against avoiding a second file's constructor-surgery.

### C. Call sites — genuine denials route through `terminateSession`; routine, expected-traffic causes are unchanged

`JWTAuthorizationFilter`'s catch block gains a predicate, deliberately correcting `maybePublishSecurityAlert`'s wrapped-exception blind spot (see Finding 2):

```java
private boolean isGenuineDenial(Exception cause) {
    return cause instanceof JWTTheftException
        || cause instanceof InvalidJWTDataException
        || cause instanceof AccountStatusException
        || (cause instanceof AuthorizationException ae
            && ae.getErrorCode() == SecurityError.ACCOUNT_NOT_LOGIN_ABLE);
}
```

```java
catch (AccountStatusException | AuthorizationException | AccessDeniedException e) {
    if (isGenuineDenial(e)) {
        securityUtil.terminateSession(req, res);
    } else {
        loginTokenManager.deleteLoginToken(res);
    }
    // ...existing helpCode/alert/writeUnauthorized logic, unchanged
}
```

Both `AuthorizationException` and `SecurityError` are already imported in this file (`:15`, `:21`) — no new imports needed. `MissingAuthenticationException`, `JWTExpiredException`, and a plain `AuthorizationException(MISSING_RIGHTS)` (wrong-role-for-this-page, from `daoAuthProvider.checkAuthorities`) all fall to the `else` branch — **today's exact behavior, unchanged**.

**All call sites:**

1. `JWTAuthorizationFilter.java:152`: replaced by the branch above (`req` is already the enclosing `doFilterInternal` parameter).
2. `AuthService.logout()` (`:225-239`): replace its hand-rolled cookie-clearing + token-marking body with a call to `securityUtil.terminateSession(req, res)`. Requires `AuthService` to gain `SecurityUtil` as a new constructor dependency (via its existing `@RequiredArgsConstructor`).
3. `AuthService`'s private `clearAuthCookies(HttpServletResponse res)` (`:241-245`) is called from **five** places inside `refresh()` — `:162`, `:167`, `:173`, `:184`, `:189` (not three; `:166` is `refreshTokenRepository.markAllUsedByUserId(ownerId)`, not a call site — double-check the live line numbers when implementing, since this file is actively being edited by this same story). `refresh(HttpServletRequest req, HttpServletResponse res)` already has `req` throughout, so change `clearAuthCookies` to `clearAuthCookies(HttpServletRequest req, HttpServletResponse res)`, update all five call sites to pass `req`, and have its body become a single call to `securityUtil.terminateSession(req, res)`.
4. Delete `SecurityUtil.logout(HttpServletResponse)` (`:137-140`) outright. Confirmed by repo-wide grep: exactly one caller in `src/main` (item 1, updated) and referenced in tests only by `JWTAuthorizationFilterTest.java`.

---

## Acceptance Criteria

**AC1 — Locked accounts cannot obtain a new session via login**
- [x] `AuthService.login()` rejects a user with `locked = true` (regardless of `activated`) with a `LockedException`, surfaced as the existing 401 `security.accLocked` response — no new error-handling code added.
- [x] `AuthService.login()`'s existing `isActivated()` rejection behavior (401 `security.accNotEnabled`) is unchanged in outcome, now routed through the shared `ensureAccountIsLive` helper.

**AC2 — Locked or disabled accounts cannot refresh an existing session, and the presented token dies even though the rejection rolls back `AuthService.refresh()`'s own transaction**
- [x] `AuthService.refresh()` rejects a user with `locked = true` or `activated = false` the same way `login()` does, checked immediately after the user is loaded and before any new refresh token is issued.
- [x] On that rejection, the refresh token presented in the `rtkn` cookie is durably marked used via `RefreshTokenRepository.markUsedByTokenHash` (`@Transactional(REQUIRES_NEW)`), and the cookies are cleared — verified by a test that confirms the row is `used = true` in the database *after* the request completes, not merely that `save()` was invoked (the whole point of the REQUIRES_NEW design is that a request-scoped mock/assertion can look correct while the real transaction discards it).

**AC3 — Every genuine forced denial fully ends the session; routine, expected traffic is untouched**
- [x] `JWTAuthorizationFilter`'s catch block routes `JWTTheftException`, `InvalidJWTDataException`, any raw `AccountStatusException` (`LockedException`, `DisabledException`, `AccountExpiredException`, `CredentialsExpiredException`), and `AuthorizationException` carrying `SecurityError.ACCOUNT_NOT_LOGIN_ABLE` through `securityUtil.terminateSession(req, res)` — clearing `rtkn`/`skp` in addition to the six cookies `deleteLoginToken` already clears, and durably revoking the presented refresh token.
- [x] `JWTExpiredException`, `MissingAuthenticationException`, and `AuthorizationException(MISSING_RIGHTS)` continue to call `loginTokenManager.deleteLoginToken(res)` only — **no behavior change** for these three, and no new DB read/write is introduced on this unauthenticated, unrate-limited path for them.
- [x] `AuthService.logout()` and `AuthService.refresh()`'s rejection branch both route through `securityUtil.terminateSession(req, res)` — one method is the sole implementation of "revoke + clear everything" for every caller that needs it.
- [x] `SecurityUtil.logout(HttpServletResponse)` no longer exists in the codebase.

**AC4 — Testing & Definition of Done**
- [x] New backend test coverage for: a locked-but-activated account rejected by `login()`; a locked-but-activated account rejected by `refresh()`, with the presented token's `used` flag verified `true` in the database after the request (not just that a repository method was invoked); a disabled (not activated) account still rejected by both (regression guard); a GDPR-erased user (`activated=false` + `locked=true` — `GdprErasureService.java:277-278`) still rejected by both (regression guard).
- [x] `JWTAuthorizationFilterTest`'s seven existing `verify(securityUtil).logout(response)` assertions (`:200, 226, 244, 270, 292, 314, 351`) split by what they actually test — **re-verify each test's thrown exception against live HEAD before editing, don't assume this list is still accurate once other edits land**:
  - `:200` (`testWhenNoToken…`, `MissingAuthenticationException`) and `:226` (`testWhenJWTExpiredException…`, `JWTExpiredException`) and `:351` (`testAuthorizationExceptionBubblesUp`, `AuthorizationException(MISSING_RIGHTS)`) → routine: change to `verify(loginTokenManager).deleteLoginToken(response)`, and add an explicit `verify(securityUtil, never()).terminateSession(any(), any())` to each so the routine/genuine split is actually tested, not just not-contradicted.
  - `:244` (`InvalidJWTDataException`), `:270` (`JWTTheftException`), `:292` (`DisabledException`), `:314` (`LockedException`) → genuine: change to `verify(securityUtil).terminateSession(request, response)`.
  - These four tests mock `daoAuthProvider`/`loginTokenManager` directly (not the real `DaoAuthProvider`), so they throw the raw Spring exception type, not the `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)`-wrapped form `DaoAuthProvider.authorize()` produces in production (`DaoAuthProvider.java:47-51`) — both forms must be covered; add one new test that mocks `daoAuthProvider.authorize(...)` to throw `new AuthorizationException(..., SecurityError.ACCOUNT_NOT_LOGIN_ABLE)` directly and asserts it also routes to `terminateSession`, so the wrapped-exception branch of `isGenuineDenial` isn't only exercised indirectly.
- [x] `AuthResourceIT`'s existing `logout_marksTokenUsedAndClearsCookies` (`:382-412`) and `refresh_*` tests (`:261-380`) still pass unmodified in their assertions — run them explicitly, don't assume.
- [x] New unit test for `RefreshTokenRepository.markUsedByTokenHash` (or covered via the `AuthResourceIT` cases above) confirming it commits even when called from a method that subsequently throws.
- [x] ESLint/Prettier: not applicable — no frontend file changes.
- [x] No local `mvn verify` (standing project convention — GitHub CI is the sole full-verification gate).

---

## Tasks / Subtasks

- [x] Task 1 (AC1, AC2): Add `AuthService.ensureAccountIsLive(User user)`; call from `login()` (replacing the existing `isActivated()` block) and from `refresh()` (new call, right after `userRepository.findById(...)`, before token creation).
- [x] Task 2 (AC2): Add `RefreshTokenRepository.markUsedByTokenHash(String tokenHash)` — `@Modifying @Transactional(REQUIRES_NEW)`, mirroring `markAllUsedByUserId`.
- [x] Task 3 (AC2, AC3): Add `SecurityUtil.terminateSession(HttpServletRequest req, HttpServletResponse res)` using `markUsedByTokenHash`, with a new `RefreshTokenRepository` constructor dependency (hand-written constructor — no Lombok on this class) and a local `sha256Hex` helper. Delete `SecurityUtil.logout(HttpServletResponse)`.
- [x] Task 4 (AC3): Add `JWTAuthorizationFilter.isGenuineDenial(Exception)` and branch the catch block between `securityUtil.terminateSession(req, res)` and `loginTokenManager.deleteLoginToken(res)` accordingly.
- [x] Task 5 (AC3): Update `AuthService.logout()` and `clearAuthCookies()` (renamed to accept `req`, all five internal call sites updated — re-count the live line numbers, don't trust the number cited above once Task 1 has already changed this file) to delegate to `securityUtil.terminateSession(req, res)`. Add `SecurityUtil` as a constructor dependency of `AuthService`.
- [x] Task 6 (AC4): Update `JWTAuthorizationFilterTest` per AC4's exact routine/genuine split above, plus the new wrapped-`ACCOUNT_NOT_LOGIN_ABLE` test case.
- [x] Task 7 (AC4): Extend `AuthResourceIT` — add locked-account cases for `login` and `refresh`, and a disabled+locked (GDPR-shape) regression case. `insertUser(...)` (`:449-465`) hardcodes `locked=false` in its raw SQL insert and has **three** existing call sites (`:94, 95, 96`) — add a `boolean locked` parameter (or overload) and update all three.
- [x] Task 8 (AC4): Add unit coverage for `SecurityUtil.terminateSession` (no existing `SecurityUtilTest` file — this creates the first one) and for `markUsedByTokenHash`'s commit-survives-caller-rollback behavior specifically (the one property this whole redesign exists to guarantee — don't let it go untested).
- [x] Task 9 (AC4): Run the full targeted backend suite (`AuthResourceIT`, `JWTAuthorizationFilterTest`, the new `SecurityUtilTest`, `JwtManagerImplTest`, `SecurityIT`) and confirm zero regressions. No local `mvn verify`.

### Review Findings

**Code review completed 2026-10-05** (`/bmad-code-review`, three parallel layers — Blind Hunter diff-only, Edge Case Hunter diff+project, Acceptance Auditor diff+spec — then independent re-verification of every finding against HEAD). Acceptance Auditor confirmed all four ACs implemented correctly, including both disclosed implementation-time deviations (reordering `ensureAccountIsLive` before token rotation; the new cookie-only `clearAuthCookies` for the optimistic-lock-loser branch). 16 raw findings → 2 patch, 8 defer, 6 dismissed as false positives (independently re-verified, not taken on trust).

- [x] [Review][Patch] **A locked/disabled account's *successor* refresh token is never revoked when denial is reached via the multi-tab grace-window path** [`AuthService.java:150-206`]
  `refresh()`'s reuse-detection branch (`:150-170`) can reassign the local `token` variable to a *successor* row (`:158-165`, when a recently-rotated token is replayed within the 30s grace window and a live successor exists) and fall through — past the expiry check — into the `findById` rejection (`:194-197`) and the `ensureAccountIsLive` rejection (`:199-206`). Both call `securityUtil.terminateSession(req, res)`, which derives the row to revoke from `CookieUtil.getCookieValue(request, REFRESH_TOKEN_COOKIE)` (`SecurityUtil.java:171`) — i.e. the *raw cookie on this request*, which is the stale, already-used original token, not the successor `token` now resolves to. The successor — the account's actual live, unused refresh credential — is left revoked-never. Latent today (`POST /api/auth/refresh` has no caller, Finding 3), but it is a real gap in exactly the mechanism AC2 exists to guarantee, and would matter the moment that endpoint is wired up. Fix: in both rejection branches, explicitly call `refreshTokenRepository.markUsedByTokenHash(token.getTokenHash())` (the resolved entity, immune to the reassignment) in addition to `terminateSession`'s cookie-clearing — `markUsedByTokenHash` is already idempotent/safe to call twice.

  **RESOLVED 2026-10-05.** Re-verified as a TRUE POSITIVE before fixing, not taken on trust — and proved empirically rather than by reading. New IT `refresh_accountLockedAndStaleCookieReplayedInGraceWindow_revokesTheSuccessorToken` (`AuthResourceIT`) reproduces it end-to-end: log in, refresh once (original → used + `rotatedAt`, successor minted unused), lock the account, then replay the *stale* original inside the 30 s grace window. Against the unfixed code the request correctly 401s with `security.accLocked` **but the successor row stays `used = false`** — the test failed on exactly that assertion and nothing else (`Expecting value to be true but was false`), confirming the mechanism precisely as described. Fixed by capturing `final String resolvedTokenHash = token.getTokenHash()` after the reuse/expiry blocks and calling `markUsedByTokenHash(resolvedTokenHash)` in both the `findById` and `ensureAccountIsLive` rejection branches. Two points verified beyond the review's own analysis: (1) **the expiry branch needs no equivalent fix** — the successor query filters on `ExpiresAtAfter(now)`, so a reassigned `token` provably cannot be the expired one, and an unreassigned `token` hashes to exactly the cookie value; (2) **the fix is deadlock-safe**, which was not obvious given this story's history — every path reaching that point has issued no write against `refresh_tokens` from the outer transaction (`findByTokenHash` and `findFirstByUserIdAndUsedFalseAndExpiresAtAfter...` are both SELECTs, and both `markAllUsedByUserId` sites throw immediately), so the `REQUIRES_NEW` revocation takes an uncontended lock. Empirically confirmed: the fixed test returns a prompt 401, not the 409-after-~5 s-stall signature that the earlier self-deadlock produced. Cost accepted and documented inline: one redundant idempotent `UPDATE` on the rejection-only path when the resolved hash equals the cookie hash.

- [x] [Review][Patch] **Only one of three "routine traffic" filter tests asserts the SecurityContext is cleared** [`JWTAuthorizationFilterTest.java`]
  The routine `else` branch (`JWTAuthorizationFilter.java:158-164`) calls `SecurityContextHolder.clearContext()` before `deleteLoginToken(res)` — a disclosed implementation-time deviation from the story's own sketch, guarded (per the sprint-status note) "by a new assertion." Only `testAuthorizationExceptionBubblesUp` (the `MISSING_RIGHTS` case) actually has that assertion (`assertNull(SecurityContextHolder.getContext().getAuthentication(), ...)`). `testWhenNoToken_ThrowMissingAuthenticationException` and `testWhenJWTExpiredException_LetExceptionBubbleUp` exercise the identical code line but have no such assertion — not a production defect (same branch, same behavior), but a real gap in the regression guard the deviation itself called for. Add the same `assertNull` to both.

  **RESOLVED 2026-10-05, with one correction to the finding's framing.** The factual claim is accurate — verified directly: only one `assertNull(SecurityContextHolder...)` existed, in the `MISSING_RIGHTS` test. Both assertions added. But the finding overstates their value, and the added comments say so rather than implying three equivalent guards: on these two paths the context is **never populated in the first place**, so the assertion is near-vacuous today. `attemptAuthorization` calls `getAuthentication(req)` (`JWTAuthorizationFilter.java:191`) *before* `SecurityContextHolder.getContext().setAuthentication(...)` (`:193`), and both tests throw out of that call — `testWhenNoToken...` because `extractPrincipal` returns `null`, `testWhenJWTExpiredException...` because it throws. `clearContext()` is therefore only genuinely load-bearing on the `MISSING_RIGHTS` path, where the context *is* populated before `checkAuthorities` throws — which is why the original assertion was placed there. The two new assertions are worth keeping as invariant checks against a future reordering that populates the context earlier, not as proof of current behaviour.

- [x] [Review][Defer] **`cause instanceof AccountStatusException` in `isGenuineDenial` is unreachable in production, by the predicate's own javadoc** [`JWTAuthorizationFilter.java:348-354`] — deferred, disclosed and deliberately defensive. `DaoAuthProvider.authorize()` (`:47-51`) always rewraps a caught `AccountStatusException` as `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)` before it reaches the filter — the javadoc says so directly. The four pre-existing tests that throw raw `DisabledException`/`LockedException` (mocking `daoAuthProvider` directly) exercise a shape production code cannot produce; the real path has its own dedicated test (`testWrappedAccountNotLoginAble_terminatesSession`). Harmless, and arguably reasonable defense-in-depth against a future caller that doesn't go through `DaoAuthProvider`, but worth a future look at whether to remove the dead disjunct or leave it as documented insurance.
- [x] [Review][Defer] **Reuse-detection branch writes the revocation twice** [`AuthService.java:162-163`, `:167-168`] — deferred, harmless. `refreshTokenRepository.markAllUsedByUserId(ownerId)` already revokes every token for the user, including the one `securityUtil.terminateSession(req, res)`'s own `markUsedByTokenHash` call redundantly re-marks a moment later via the raw cookie hash. Both calls are idempotent (`used` is monotonic), so the only cost is one extra `UPDATE` per reuse-detection event — not a correctness issue.
- [x] [Review][Defer] **`terminateSession`'s cookie-only comment doesn't account for `markUsedByTokenHash` as a second concurrent writer** [`AuthService.java:216-220`] — deferred, documentation-quality only. The optimistic-lock-loser branch's comment reasons "unnecessary — the winning request has already committed `used=true`," considering only a second concurrent *refresh* as the other writer. A concurrent forced-logout's `markUsedByTokenHash` is a second possible writer of the same row the comment doesn't name — the conclusion still holds (both writers only ever set `used=true`, monotonic), but the stated reasoning is incomplete.
- [x] [Review][Defer] **`AuthService.logout()`'s revocation went from conditional to unconditional, undisclosed** [`SecurityUtil.java:169-176` vs. the pre-diff `.filter(t -> !t.isUsed())`] — deferred, low-impact. The old code only wrote if the token wasn't already used; `markUsedByTokenHash`'s bulk `UPDATE` now runs unconditionally on every logout, bumping `version` even on an already-used row. Functionally equivalent end state, one extra write per logout of an already-dead token — a reasonable, if unstated, cost of moving to a bulk-update design to avoid the deadlock (see AC2's Dev Notes). Worth a one-line comment acknowledging the tradeoff.
- [x] [Review][Defer] **`isGenuineDenial(Exception cause)` is typed against the generic superclass rather than the caught union** [`JWTAuthorizationFilter.java:348`] — deferred, style nit. Declared as `Exception` rather than `AccountStatusException | AuthorizationException | AccessDeniedException` (the only types the single call site can pass). Harmless today; a future unrelated caller passing an arbitrary exception would silently get `false` rather than a compile error.
- [x] [Review][Defer] **`markUsedByTokenHash`/`markAllUsedByUserId` calls are unguarded against transient DB failures** [`SecurityUtil.java:173`, `AuthService.java:162,167`] — deferred, pre-existing pattern, not introduced by this story. A `DataAccessException` mid-call would propagate to `ApiAdvice`'s generic `Throwable` handler (500) rather than the intended 401/403. `markAllUsedByUserId` already carried this exact risk pre-diff with no bespoke handling anywhere in this codebase for transient DB errors on this class of call; not a new regression.
- [x] [Review][Defer] **`isGenuineDenial`'s `ACCOUNT_NOT_LOGIN_ABLE` branch has no per-client throttle on the `REQUIRES_NEW` write, unlike `maybePublishSecurityAlert`'s audit-event throttle for the same cause** [`JWTAuthorizationFilter.java:348-354` vs. `:312-319`+`alertThrottle`] — deferred, bounded. A client that ignores `Set-Cookie` and keeps replaying the same stale JWT for a since-locked account would trigger one `markUsedByTokenHash` write per request, indefinitely — the same write-amplification class `SecurityAlertThrottle` exists to bound for the audit-log side of this exact cause. `daoAuthProvider.authorize()`'s own DB read already happens unthrottled on every such retry pre-diff, so this adds one write to an existing read-amplifier rather than introducing amplification from zero. Worth reusing the throttle pattern in a follow-up.
- [x] [Review][Defer] **`isGenuineDenial` doesn't treat `AuthorizationException(USER_NOT_FOUND)`/`(UNKNOWN)` as genuine denials** [`JWTAuthorizationFilter.java:348-354` vs. `DaoAuthProvider.java:42-46,52-56`] — deferred, low impact, out of this story's stated scope. A user whose row is hard-deleted (`UserAdminService.deleteUserInformation`) while holding a valid JWT hits this path and gets only cookie-clearing, not revocation, on the filter's leg. `AuthService.refresh()`'s own `findById` rejection (`:194-197`) independently and fully revokes the same case via `terminateSession`, and `POST /api/auth/refresh` is dead today (Finding 3), so the practical gap is nil. The story's own design explicitly scoped `isGenuineDenial` to mirror `maybePublishSecurityAlert`'s existing three-clause shape plus the one disclosed `ACCOUNT_NOT_LOGIN_ABLE` correction — widening to every `AuthorizationException` variant is a reasonable follow-up, not a thing this story promised.

**Dismissed (6, recorded so they are not re-litigated):**
1. **Exception-contract "inconsistency": `refresh()`'s new rejection throws `DisabledException`/`LockedException` instead of `BadCredentialsException` like its sibling branches** — false positive relative to intent (raised by the diff-only layer, which had no access to AC1's explicit requirement). Deliberate: reusing Spring's own exception types routes through `ApiAdvice`'s existing `security.accNotEnabled`/`security.accLocked` mappings, giving a locked user an accurate message instead of a generic "bad credentials" one. Confirmed consistent with AC1 by the Acceptance Auditor layer, which had the spec.
2. **`insertUser`'s 6→7 parameter signature change risks uncompiled call sites elsewhere in the file** — refuted. Grepped every call site: exactly 6 total (`:108,109,110,113,114,115`), all already using the 7-arg form.
3. **`ChronoUnit.DAYS` used in a new test with no confirmed import** — refuted. `java.time.temporal.ChronoUnit` was already imported pre-diff (used at pre-existing lines `:259,371`); the new usage at `:620` compiles against the same import.
4. **`sha256Hex` duplicated across `AuthService` and `SecurityUtil` risks drift** — not a new finding; already a disclosed, deliberate design decision in this story's own Dev Notes ("this project has no shared crypto-helper home... does not justify inventing one"), reproduced verbatim in `SecurityUtil.java`'s own javadoc on the method.
5. **`markAllUsedByUserId`'s `REQUIRES_NEW` annotation is asserted but not shown in the diff, so the whole deadlock-avoidance design rests on an unverifiable claim** — artifact of the diff-only layer's lack of project access. Independently confirmed present and correct at `RefreshTokenRepository.java:30-33` by two other layers and by this triage's own direct read.
6. **`testAuthorizationExceptionBubblesUp`'s arrange section isn't visible in the diff, so there's no confirmation it exercises the `isGenuineDenial`-false path rather than some other exception shape** — refuted by direct read: the test throws `new AuthorizationException("Missing rights", SecurityError.MISSING_RIGHTS)`, confirmed distinct from `ACCOUNT_NOT_LOGIN_ABLE`, correctly exercising the routine branch.

---

## Dev Notes

- **Do not touch `JwtManagerImpl`.** It stays DB-free by design (see `JwtManagerImpl.java:90-102`). All DB-aware work lives in `AuthService`/`SecurityUtil`/`RefreshTokenRepository`.
- **Do not touch the `/authenticate` + OTP pipeline.** It already enforces account status correctly and is dead code from the live UI's perspective. Whether to delete or wire it up is a separate product decision.
- **Do not fold in the `skp`-cookie duplication / three-writer-sites / role-derivation-drift items** documented in `JwtManagerImpl.java:90-102` and the `skillars-deferred-142` story — both its Context section (the "three of the four paths" discussion) and its Review Findings DEFER section (the `URLEncoder.encode` item, which notes the duplication is "identical at both `AuthService` sites" as of when it was written, before `JwtManagerImpl` became a third writer). UI-consistency concern, not an auth bypass — server-side authorization runs off `@PreAuthorize` + JWT `ROLES`, not `skp`.
- **`UserAdminService.lockUserAccount()` is not wired to a controller and this story does not wire it up.** This story only ensures that *when* an account is locked, login/refresh respect it.
- **`isRefreshTokenRevoked()`** (`JWTAuthorizationFilter.java:214-227`) is unrelated and must not be modified.
- **Ordering in `refresh()` matters**: `ensureAccountIsLive` must run after the reuse/expiry checks (`:145-186`, unchanged) but before the new `RefreshToken` row is created (`:195-201`) and before `createLoginToken` (`:203`).
- **`isGenuineDenial`'s wrapped-exception branch is the one place this design intentionally goes beyond what `maybePublishSecurityAlert` already does** — re-read Finding 2 before "simplifying" the predicate to match that existing one; they look similar but the existing one has a known blind spot this story deliberately closes.
- **Every line-number citation in this file was verified against HEAD `3a62698c` at story-creation time, but Tasks 1-5 all edit the same files sequentially — re-verify any cited line number against the file's actual current state at the point you use it, not against this document.** This bit the story once already (round 1 cited `AuthResourceIT` ranges that had drifted by the time round 2 checked them against a hypothetical post-edit state); don't let it happen inside the implementation itself.

### Project Structure Notes

- Changes stay within `com.softropic.skillars.platform.security.service` (`AuthService`, `SecurityUtil`), `com.softropic.skillars.platform.security.repo` (`RefreshTokenRepository`), and `com.softropic.skillars.platform.security.infrastructure.jwt.filter` (`JWTAuthorizationFilter`) — consistent with `_bmad-output/project-context.md`'s module-layering rules.
- No new files except the new `SecurityUtilTest`. No DB migration (the new repository method is a derived query, not a schema change), no new REST endpoint, no DTO changes.

### References

- [Source: src/main/java/com/softropic/skillars/platform/security/service/AuthService.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/repo/User.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/service/DaoAuthProvider.java]
- [Source: src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java]
- [Source: src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java]
- [Source: src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java]
- [Source: src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java]
- [Source: src/frontend/src/plugins/sessionManager.js]
- [Source: src/frontend/src/api/session.api.js]
- [Source: src/frontend/src/api/auth.api.js]
- [Source: _bmad-output/planning-artifacts/architecture.md#Authentication & Security]

---

## Resolution of pre-implementation review (two rounds, `story-review.md`, 2026-10-05)

Every finding below was independently re-verified against HEAD before being accepted or rejected — none were applied on either review's say-so alone.

**Round 1** (citation/range drift only, no design issues found): corrected a `GdprErasureService` line-range (`:278-279` → `:277-278`), added `InvalidJWTDataException` to the exception list (confirmed it genuinely extends `AuthorizationException` and is caught), and corrected three `AuthResourceIT` line ranges. One round-1 finding was rejected as a **fabricated citation**: it claimed the story referenced a deferred-142 filename containing "video-page-redirect-fix", which a direct grep of the story file showed appears nowhere in it.

**Round 2** (design-level, not just citations) — three findings confirmed as real, load-bearing problems, independently re-traced through the actual annotations/exception hierarchy/config rather than taken on trust:
- **Transaction-boundary defect (round 2's "F1"):** the original Part B used a plain `find → setUsed(true) → save()` inside `terminateSession`. Traced `AuthService`'s class-level `@Transactional`, `AuthResource`'s absence of one, `open-in-view: false`, and the exception hierarchy (`LockedException` → `RuntimeException`) end-to-end and confirmed: calling that plain save from inside `AuthService.refresh()`'s own rejection branch would be silently discarded by the same rollback the rejection itself triggers — directly defeating AC2's purpose. Confirmed the codebase already has the fix pattern (`RefreshTokenRepository.markAllUsedByUserId`'s `@Transactional(REQUIRES_NEW)`) and adopted the identical shape as a new `markUsedByTokenHash` method. This also resolves the review's related point that `SecurityUtil` is `final` and therefore cannot itself be given `@Transactional` (now moot — the transactional method lives on the repository, not on `SecurityUtil`).
- **Over-broad teardown trigger (round 2's "F2"):** the original AC3 attached refresh-token revocation to *every* cause the filter's catch block handles, including `JWTExpiredException` and `MissingAuthenticationException` — both explicitly documented elsewhere in the same file as routine, unauthenticated-path traffic that a prior story (`skillars-deferred-90` AC5/F22) deliberately kept off the DB-write path for exactly this reason. Verified both Javadoc passages directly, and worked out that revoking `rtkn` on every idle-out would also make `POST /api/auth/refresh` permanently unusable the moment it's ever wired up — self-defeating against this story's own stated purpose. Redesigned around a `isGenuineDenial` predicate; independently confirmed it needs to handle `DaoAuthProvider.authorize()`'s `AccountStatusException → AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)` rewrapping, which the codebase's one existing similar predicate (`maybePublishSecurityAlert`'s `genuineDenial`) does not handle — checked `DaoAuthProvider.java:47-51` directly to confirm the wrapping is real, not assumed from the review's say-so.
- **False premise in Finding 3 (round 2's "F3"):** the original story claimed `sessionManager.js:265` calls `POST /api/auth/refresh` and built an active "forced-logout gets silently undone" exploit narrative on that claim. Traced the actual call through `session.api.js` and confirmed it is `GET /refresh` — a *secured* endpoint that already gets the DB re-auth check, not the dead `POST /api/auth/refresh` endpoint. Confirmed `POST /api/auth/refresh` has zero frontend callers (grep + the checked-in Vitest coverage report, cross-checked independently rather than trusting either source alone). Rewrote Finding 3 and the second User Story paragraph to state the honest, defence-in-depth framing rather than a dead-code-based exploit chain, and rebalanced Finding 1's severity language accordingly (new logins: unbounded, strongest case; existing sessions: already bounded to ~5 minutes by the live filter path, not "silently does nothing").

Also fixed, all independently re-confirmed against HEAD rather than taken on the review's word: `clearAuthCookies` has five call sites (`:162,167,173,184,189`), not three; `insertUser` has three call sites (`:94,95,96`), not two; the scope header undercounted `AuthService` methods touched (four, not two); the stale "no review has run yet" line (now two rounds have); and a self-inflicted misquotation from round 1's own fix (round 1 had added quotation marks around "all three `skp` writers" attributed to deferred-142, which doesn't contain that phrase — corrected to describe rather than misquote).

One round-2 finding was downgraded, not rejected outright: the `SecurityUtil`-vs-`AuthService` placement question (round 2's "F12") is real but was explicitly labelled by the review itself as a judgment call, not a defect. Decided explicitly in Part B above, with the reasoning recorded rather than silently picking one.

---

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`) via `/bmad-dev-story`, 2026-10-05.

### Debug Log References

Two empirical findings drove design changes during implementation. Both are recorded here because
neither was predictable from reading the code, and both would otherwise look like unexplained
deviations from the story's design.

**1. The story's prescribed ordering in `refresh()` self-deadlocks (design change, AC2).**

The story placed `ensureAccountIsLive` *after* `token.setUsed(true); saveAndFlush(token)`. Built
exactly as specified, all three `refresh_*` liveness ITs failed with **409 CONFLICT after a ~5.5 s
stall**, not the intended 401. Server-side cause, captured from the failing run:

```
SQL Error: 0, SQLState: 55P03
ERROR: canceling statement due to lock timeout
org.springframework.dao.PessimisticLockingFailureException: JDBC exception executing SQL
  [update main.refresh_tokens rt1_0 set used=true,version=(rt1_0.version+1) where rt1_0.token_hash=?]
Caused by: org.hibernate.PessimisticLockException ... Caused by: org.postgresql.util.PSQLException
```

The mechanism: `saveAndFlush` issues an `UPDATE` against the token row inside `refresh()`'s own
(outermost) transaction and holds that row lock until the transaction ends. The rejection teardown's
revocation then runs in a `REQUIRES_NEW` transaction that must update **the same row**. The inner
transaction waits on a lock only the outer can release, while the outer waits on the inner — a
self-deadlock, broken only by `lock_timeout`. `REQUIRES_NEW` was the right call for *durability*
(the story's round-2 review was correct about the rollback), but it is incompatible with revoking a
row the caller has already locked.

Fixed at the source rather than worked around: the user is now loaded and checked **before** the
token is rotated. On the rejection path the outer transaction has issued no write to
`refresh_tokens` at all, so the `REQUIRES_NEW` revocation takes an uncontended lock and commits.
This still satisfies AC2 verbatim ("immediately after the user is loaded and before any new refresh
token is issued") and is better behaviour independently: a locked account's refresh attempt no
longer consumes and rotates a token before being turned away.

One teardown necessarily remains *after* that write — the `ObjectOptimisticLockingFailureException`
branch — so it clears cookies only, via a new `SecurityUtil.clearAuthCookies(res)`. Revoking there
would hit the identical deadlock and is redundant anyway: losing the optimistic-lock race means the
winning request has already committed `used = true` on that exact row.

**2. Bare `jdbcTemplate` writes inside an IT method body never commit (test-harness finding).**

The first run of the new ITs failed for a reason unrelated to the production code. A diagnostic
probe made it unambiguous:

```
DIAG rowsUpdated=1 lockedNow=false tokenRows=0
DIAG insertedThenFound=0
```

An `UPDATE` reports one row affected, and the very next statement reads the old value;
an `INSERT` followed by a `SELECT` finds nothing. Cause: `spring.datasource.hikari.auto-commit` is
`false` (`application.yaml:183`, set so Hibernate can group statements into one transaction), so a
statement issued outside a transaction is rolled back when the connection is released. This is why
`AuthResourceIT.setUp()` already wraps all its seeding in `transactionTemplate`. Added a
`commitWrite(...)` helper and routed every new write through it, with the reason documented at the
helper.

**Pre-existing issue observed, deliberately not fixed:** `refresh_expiredToken_returns401`
(`:343`) seeds its expired-token row with a bare `jdbcTemplate.update`, so that row never commits.
The test passes, but on "refresh token not found" rather than "refresh token expired" — the raw
cookie value it sends (`fakeRaw`) does not hash to the seeded `token_hash` either way, so the
expiry branch it is named for is not actually exercised. Out of scope here: AC4 requires the
existing `refresh_*` assertions to pass **unmodified**, and correcting this changes what the test
proves. Flagged for the ledger.

### Completion Notes List

All 4 ACs and all 9 tasks complete. **97 targeted tests green, 0 failures, 0 errors** (66 unit via
surefire + 31 IT via failsafe). No local `mvn verify`, per the standing project convention that
GitHub CI is the sole full-verification gate.

**Red-green verified, not assumed.** The two headline ACs were written as failing tests first and
run against unmodified production code; both failed with `Expecting code to raise a throwable`,
empirically confirming that a locked-but-activated account could both log in *and* refresh
successfully before this change. That is the gap the story describes, reproduced rather than taken
on the story's word.

What landed:

- **AC1** — `AuthService.login()`'s standalone `isActivated()` block replaced by
  `ensureAccountIsLive(user)`, which rejects `locked = true` with `LockedException`. Zero new
  error-handling code: `ApiAdvice` already maps it to 401 `security.accLocked`, and all three
  locales already carry the copy (verified directly, not assumed).
- **AC2** — `AuthService.refresh()` gained the same gate, plus the new
  `RefreshTokenRepository.markUsedByTokenHash` (`@Modifying @Transactional(REQUIRES_NEW)`,
  mirroring `markAllUsedByUserId`). Durability is asserted by reading `used` **back out of the
  database after the request completes**, so a regression to a plain managed `save()` fails the
  test instead of passing silently. A separate IT forces the caller's transaction to roll back via
  `setRollbackOnly()` and proves the revocation survives it — the single property the whole
  `REQUIRES_NEW` design exists to guarantee.
- **AC3** — `SecurityUtil.terminateSession(req, res)` is now the one teardown path (filter's
  genuine denials, `AuthService.logout()`, and `refresh()`'s liveness rejection).
  `SecurityUtil.logout(HttpServletResponse)` is deleted. `JWTAuthorizationFilter` gained
  `isGenuineDenial`, including the wrapped-`ACCOUNT_NOT_LOGIN_ABLE` clause that
  `maybePublishSecurityAlert`'s otherwise-similar predicate misses. `JWTExpiredException`,
  `MissingAuthenticationException` and `AuthorizationException(MISSING_RIGHTS)` keep today's exact
  behaviour, with no DB read or write added on that unauthenticated, unrate-limited path.
- **AC4** — new `SecurityUtilTest` (6 tests, first test class for this type), 7 rewritten +
  2 new `JWTAuthorizationFilterTest` cases, 7 new `AuthResourceIT` cases. The pre-existing
  `logout_marksTokenUsedAndClearsCookies` and all `refresh_*` tests pass with assertions unmodified.

Deviations from the story's written design, all deliberate and all explained above or inline:

1. `refresh()` checks liveness **before** the token-rotation write, not after — the story's
   ordering self-deadlocks (see Debug Log 1).
2. Added `SecurityUtil.clearAuthCookies(HttpServletResponse)` (cookies only) for the one teardown
   that must run after that write. Not in the story's design; required by the same hazard.
3. The story's `AuthService.clearAuthCookies(req, res)` private wrapper was **not** retained. Once
   it became a one-line delegation to a full teardown, its name was actively misleading and
   collided with the new cookies-only `SecurityUtil.clearAuthCookies`. The five call sites call
   `securityUtil.terminateSession(req, res)` directly instead.
4. The filter's routine branch explicitly calls `SecurityContextHolder.clearContext()`. The story's
   sketch had it call `deleteLoginToken` only, which would have silently dropped the context
   clearing that the deleted `securityUtil.logout(res)` performed for **every** caught cause —
   contradicting AC3's own "no behavior change for these three". Guarded by a new assertion.

Not touched, per Dev Notes: `JwtManagerImpl`, the `/authenticate` + OTP pipeline,
`UserAdminService.lockUserAccount()`'s controller wiring, `isRefreshTokenRevoked()`, and the
`skp`-duplication / role-derivation-drift items. No schema migration, no frontend change, no new
REST endpoint — so ESLint/Prettier are not applicable.

### File List

Production (4 files, all modified):

- `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`

Tests (1 new, 2 modified):

- `src/test/java/com/softropic/skillars/platform/security/service/SecurityUtilTest.java` *(new)*
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java`

Tracking:

- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-143-account-lock-enforcement-and-forced-logout-session-termination.md`

### Change Log

| Date | Change |
| :--- | :--- |
| 2026-10-05 | Implemented all 4 ACs / 9 tasks via `/bmad-dev-story`. Account-liveness enforcement centralized in `AuthService.ensureAccountIsLive`; session teardown centralized in `SecurityUtil.terminateSession`; `SecurityUtil.logout(HttpServletResponse)` deleted; `RefreshTokenRepository.markUsedByTokenHash` added with `REQUIRES_NEW`; `JWTAuthorizationFilter.isGenuineDenial` splits genuine denials from routine traffic. 97 targeted tests green. |
| 2026-10-05 | Design correction found during implementation: the story's placement of the liveness check *after* `refresh()`'s token-rotation write self-deadlocks (`REQUIRES_NEW` revocation vs. the outer transaction's own row lock; `lock_timeout`/55P03 → 409). Check moved ahead of that write; the one remaining post-write teardown clears cookies only. |
| 2026-10-05 | Status → review. |
| 2026-10-05 | Code review (`/bmad-code-review`, 3 layers) triaged: 2 patch applied, 8 deferred, 6 dismissed. **Patch 1 was a real bug**, reproduced with a new failing IT before being fixed: the multi-tab grace-window branch reassigns `token` to a live *successor* row, but both rejection branches revoked only the raw cookie's (stale, already-used) row via `terminateSession`, leaving the successor `used = false` after a locked-account denial. Fixed by revoking the *resolved* token hash in both branches. Patch 2 (two missing `assertNull` SecurityContext guards in the routine filter tests) applied, with its framing corrected — near-vacuous on those paths, since the context is never populated before the throw. 128 targeted tests green. |
