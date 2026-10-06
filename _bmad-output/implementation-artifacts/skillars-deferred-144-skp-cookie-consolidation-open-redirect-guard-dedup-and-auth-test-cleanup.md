# skillars-deferred-144: skp Cookie Consolidation, Open-Redirect Guard Dedup, and Auth Review/Test Cleanup

**Story ID:** deferred-144
**Epic:** Deferred-Work Cleanup Bundle
**Status:** done
**Scope Level:** Small-Medium (one new backend type + its test; three backend call sites migrated to it; one method changed in `JwtManagerImpl`, one line in `SecurityUtil` switched to delegate (not removed — see AC2); six small, independently-scoped findings in `AuthService`/`JWTAuthorizationFilter`/`RefreshTokenRepository`; one new frontend module + its test; a one-line `meta` tag in `routes.js`; two Vue pages updated; one pre-existing IT fixed to test what it claims to test; plus (added during implementation, Finding 7/AC8) removal of a per-request DB query from `JWTAuthorizationFilter`'s fast path, with a cascading constructor-signature cleanup in `SecurityConfiguration` and `JWTAuthorizationFilterTest`. No schema change, no new REST endpoint, no DTO/contract change visible to callers.)
**Date Created:** 2026-10-05
**Source:** Mined from `_bmad-output/implementation-artifacts/deferred-work.md`, sections "Deferred from: code review of skillars-deferred-142 (2026-10-05)", "Deferred from: manual review during skillars-deferred-143 (2026-10-05)", and "Deferred from: code review of skillars-deferred-143 (2026-10-05)". All citations below were re-verified directly against HEAD `a0d39f83` at story-creation time, not copied from the ledger — several had already drifted (e.g. `AuthService.java`'s `refresh()` skp-write line moved from the ledger's `:218` to `:270` once `skillars-deferred-143` inserted ~52 lines above it).

---

## User Story

As **the developer maintaining the auth/session pipeline**, I want the `skp` cookie's wire format owned by a single type instead of copy-pasted across three writers, and the asymmetry where it is written in three places but only reliably cleared in one, closed — so that **the next person who changes the cookie's shape (as already happened once, when a bare `id` corrupted `authStore.userId` via IEEE-754 rounding) only has one call site to get right**.

As **the developer maintaining the login/OTP pages**, I want the open-redirect guard de-duplicated into one tested module that also refuses to land a user on a dead route, and the OTP page's terminal navigation to stop leaving a spent page in browser history — so that **a future hardening of the guard (e.g. against `\`-prefixed or percent-encoded payloads) only needs to change one file, and a successful login never silently dead-ends on a typo'd `redirect` query value**.

As **the developer who will next touch `AuthResourceIT` or `JWTAuthorizationFilter`**, I want a pre-existing test that passes for the wrong reason fixed, and six small, already-triaged code-review findings closed, so that **the test suite's name-to-behavior mapping is trustworthy and the ledger's accumulated low-risk residue from `skillars-deferred-142`/`-143` stops growing**.

---

## Context: Current State (verified against HEAD `a0d39f83`, 2026-10-05)

### Finding 1 — the `skp` cookie's wire format is open-coded at three call sites, one of them already wrong

`AuthService.login()` (`:122-135`), `AuthService.refresh()` (`:260-273`), and `JwtManagerImpl.setSkillarsProfileCookie` (`:103-122`, added by `skillars-deferred-142`) each independently: derive a role string, build `"{\"id\":\"...\",\"role\":\"...\"}"` by hand, call `URLEncoder.encode(json, StandardCharsets.UTF_8)`, and write it via `CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue, false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax")`. The two `AuthService` sites are **byte-for-byte identical**, including the 9-line comment explaining why `id` must be quoted (a real production bug, found 2026-10-01: an unquoted `id` corrupted `authStore.userId` via IEEE-754 rounding and surfaced as a 403 on coach photo upload). `JwtManagerImpl`'s version differs only in how it derives `role`/`id` (from the `ROLES`/`BUS_ID` JWT claims, with an `ANONYMOUS` fallback, vs. `AuthService`'s `user.getSkillarsRole()` with an `"ADMIN"` fallback) — those differences are load-bearing (collapsing them would surface admin-only nav/UI to a non-admin, per `JwtManagerImpl.java:95-102`'s own comment) and must survive this refactor; everything downstream of having `(id, role)` in hand is identical and extractable.

**The encoding is also subtly wrong, latent only.** `URLEncoder.encode` is **form** encoding (space → `+`); the frontend's `auth.store.js` `hydrateFromCookie()` decodes with `decodeURIComponent`, which leaves a literal `+` alone — the two are not inverse functions. Today's payload (a numeric id, an enum name) can never contain a space, so this has never fired, but it is a trap for the next field added to this cookie.

### Finding 2 — `JwtManagerImpl.deleteLoginToken()` clears six cookies, not the `skp` it now also writes

`deleteLoginToken` (`JwtManagerImpl.java:182-190`) removes `JWT_COOKIE_NAME`, `B_COOKIE`, `USER_COOKIE`, `ADMIN_COOKIE`, `JWT_SESSION_COOKIE`, `SESSION_REFRESH_COUNTDOWN` — not `skp` (`SKILLARS_PROFILE_COOKIE`) and not `rtkn` (`REFRESH_TOKEN_COOKIE`). `rtkn` staying out is deliberate and must not change (revoking it on every idle-out would make `POST /api/auth/refresh` permanently useless the moment it is wired up — see `skillars-deferred-143`'s own Dev Notes). `skp` staying out is **not** a decision, it is a gap: `deleteLoginToken` predates `skp` entirely, and the one class that could have been extended to cover it (`JwtManagerImpl`) only became a `skp` *writer* on 2026-10-05 (`skillars-deferred-142`, `setSkillarsProfileCookie`).

**Where this is observable.** `deleteLoginToken` has exactly two callers, confirmed by reading both:
- `SecurityUtil.clearAuthCookies` (`SecurityUtil.java:187-191`) immediately follows it with its own explicit `CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, ...)` call (`:190`) — so that path already clears `skp` today, redundantly once this story lands.
- `JWTAuthorizationFilter`'s routine-denial branch (`JWTAuthorizationFilter.java:158-165`) calls `deleteLoginToken` **alone** — this is the "expected traffic, not a genuine denial" branch for `JWTExpiredException` and `MissingAuthenticationException` (`isGenuineDenial`, `:348-354`, deliberately excludes these two per `skillars-deferred-90` AC5/F22 and `skillars-deferred-143` AC3). **`skp` survives a routine 401 on this branch today.** `skp` is `httpOnly=false` and `auth.store.js`'s `hydrateFromCookie()` reads it on every page load, so the SPA can re-hydrate a stale `userId`/`role` after an idle-out. **Not an auth bypass** — server-side authorization runs off `@PreAuthorize` + the JWT `ROLES` claim, never `skp` — this is stale client-side UI state, not a privilege gap.

Fixing this is "free": `skp` removal is a bare `Set-Cookie: Max-Age=0` header, no DB read/write, unlike `rtkn`'s revocation. It is still a **disclosed behavior change** on a path `skillars-deferred-143` AC3 deliberately froze, so it gets its own AC and test below rather than being a drive-by inside Finding 1's refactor.

### Finding 3 — the open-redirect guard is duplicated verbatim, and doesn't check the target route exists

`OtpPage.vue:158-162` and `LoginPage.vue:170-174` are byte-identical:
```js
const redirect = route.query.redirect
const safePath =
  typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : routeForRole(authStore.role) // or response.role, in LoginPage
```
`skillars-deferred-142` AC3 required this exact verbatim copy for consistency between the two pages — a reasonable call at the time, but `roleRoutes.js` (`src/frontend/src/router/roleRoutes.js`) exists for precisely this failure shape (a role-routing map that used to be declared twice, "the failure mode if they ever drift is an infinite redirect loop" — see its own header comment, `skillars-deferred-92` AC16). An open-redirect guard duplicated the same way has the same risk the moment one copy is hardened and the other forgotten.

**A second, independent gap:** the guard checks shape only (`startsWith('/') && !startsWith('//')`), never resolvability. `?redirect=/typo` passes the guard and lands the just-authenticated user on `ErrorNotFound.vue` (the catch-all 404 route, `routes.js:352-355`, `path: '/:catchAll(.*)*'`) with no fallback to `routeForRole`. Confirmed this cannot leave the origin regardless (`quasar.config.js:40` sets `vueRouterMode: 'hash'`, so a `/`-prefixed path can never become a protocol-relative or absolute URL) — the risk here is a dead-ended login, not a security hole.

**The obvious fix for "does this path match a real route" does NOT work against this specific route table — verified by executing the installed `vue-router` (4.6.4), not by reasoning about it.** `router.resolve(path).matched.length > 0` is the standard idiom, but `routes.js`'s own catch-all (`/:catchAll(.*)*`) means EVERY `/`-prefixed path matches something — `matched.length` is 1 for `/typo` exactly as it is for a real route, because the catch-all itself is a match:
```
WITH catch-all     resolve('/typo')           matched.length=1  matchedPaths=['/:catchAll(.*)*']
WITHOUT catch-all  resolve('/typo')           matched.length=0  matchedPaths=[]
```
So `matched.length > 0` can never distinguish "real route" from "landed on the 404 page" — it is a no-op check against production's actual route table, and the bug it exists to catch would still happen. The fix is to give the catch-all an identity and exclude it, mirroring the idiom `router/index.js` already uses for route `meta` flags:
```js
// routes.js — tag the catch-all so it can be told apart from a real match
{ path: '/:catchAll(.*)*', component: () => import('pages/ErrorNotFound.vue'), meta: { notFound: true } },
```
See the corrected `safeRedirect.js` below.

### Finding 4 — `OtpPage.vue` uses `router.push` for its one-shot terminal redirect

`OtpPage.vue:163`: `router.push(safePath)` after `verifyOtp` has already consumed `loginInfoId` server-side. Back then returns the user to a dead OTP form; resubmitting errors against an already-consumed id. **Correction to the precedent cited during drafting:** `VideoManagementPage.vue:108`'s `router.replace` is NOT a terminal post-auth redirect — it is a 403-access-denied bounce inside `fetchVideos()`'s `catch` block, a different shape entirely. Repo-wide, `router.replace` is rare (9 call sites) and the real precedent for "a one-shot page that should not remain reachable via Back" is `PlayerHomeRedirectPage.vue:29/40/46`, a dedicated one-shot redirect page with the same shape `/otp` has. The fix itself is still correct for the reason stated (a consumed `loginInfoId` makes `/otp` genuinely non-returnable) — only the cited precedent was wrong. `LoginPage.vue:175` also uses `router.push` — **deliberately left alone here**: unlike `/otp`, `/login` is a page a user can legitimately want to return to (e.g. to sign in as someone else), so replacing its own history entry is not obviously correct and is out of this story's scope.

### Finding 5 — `AuthResourceIT.refresh_expiredToken_returns401` passes, but not for the reason its name claims

`AuthResourceIT.java:365-385`. The seed write (`:368-372`) is a bare `jdbcTemplate.update(...)` outside any transaction — but `spring.datasource.hikari.auto-commit=false` (`application.yaml:183`, so Hibernate can group statements into one transaction), so the INSERT is rolled back when the connection is released and the row never exists for the server under test to find. Every other write in this class IS committed — some via the file's own `commitWrite(String sql, Object... args)` helper (`:705-707`, added by `skillars-deferred-143` specifically to fix this class of bug), others (`setUp`'s fixture inserts, `insertUser`) via being called from inside an outer `transactionTemplate.execute(...)` block (`setUp`, `:96-136`) rather than through `commitWrite` itself — this one test is the sole exception, calling `jdbcTemplate.update` directly with no wrapping transaction at all. Independently, `fakeRaw` (`:373`, `"fake-expired-raw-token-value..."`) does not hash to the seeded `token_hash` either way, so even a correctly-committed row would not be found by this request's cookie. The test still asserts a 401 — correctly — but via "refresh token not found" (`BadCredentialsException`, `AuthService.java:148`), not "refresh token has expired" (`:175`), so the expiry branch the test is named for has never actually run.

**Explicitly excluded, companion ask from the same ledger item (`deferred-work.md:3757-3758`):** "Worth a wider grep at the same time: any other IT seeding state with a bare `jdbcTemplate` write in a test method body has the same silent no-op." Not scoped into this story — fixing `AuthResourceIT`'s one instance (AC5) does not imply auditing the other ~160 IT classes for the same class of bug, and that audit is a meaningfully different, open-ended piece of work. Left for a future story (or a standalone grep-only pass) to pick up explicitly, rather than silently treated as done by proxy.

### Finding 6 — six small, already-triaged findings from the `skillars-deferred-143` code review

All six are documented, independently-scoped DEFER items from that story's Review Findings section (`skillars-deferred-143-account-lock-enforcement-and-forced-logout-session-termination.md`, lines 230-237) — re-verified against current HEAD below, not re-derived:

1. `isGenuineDenial`'s `cause instanceof AccountStatusException` disjunct (`JWTAuthorizationFilter.java:351`) is unreachable in production — `DaoAuthProvider.authorize()` (`:47-51` of that file) always rewraps a caught `AccountStatusException` into this project's own `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)` before it reaches the filter, per the method's own javadoc (`:340-346`). The four tests that exercise it mock `daoAuthProvider` directly to throw the raw Spring exception type — a shape production code cannot produce. Harmless; arguably reasonable defense-in-depth against a future caller that bypasses `DaoAuthProvider`. **This is the one item in this AC that is a judgment call, not a mechanical fix** — see AC6 below.
2. `AuthService.refresh()`'s reuse-detection branch (`:161-163`, `:166-169`) calls both `refreshTokenRepository.markAllUsedByUserId(ownerId)` (revokes **every** token for the user) and then `securityUtil.terminateSession(req, res)`, whose own internal `markUsedByTokenHash` redundantly re-marks the one token derived from the request's raw cookie hash. Both writes are idempotent (`used` is monotonic) — harmless, but one avoidable extra `UPDATE` per reuse-detection event.
3. The optimistic-lock-loser branch's comment (`AuthService.java:239-241`, `"It is also unnecessary — losing the optimistic-lock race means the winning request has already committed used = true on this exact row"`) reasons about only one other possible writer of the row (a second concurrent refresh). A concurrent forced-logout's `markUsedByTokenHash` is a second writer the comment doesn't name. The conclusion still holds (both writers only ever set `used=true`); the stated reasoning is incomplete.
4. `AuthService.logout()`'s revocation moved from conditional to unconditional without being called out. Pre-`skillars-deferred-143`, the old logout path only wrote if the token wasn't already used; `SecurityUtil.terminateSession`'s `markUsedByTokenHash` (`SecurityUtil.java:171`) now runs unconditionally on every logout. Functionally equivalent end state, one extra write per logout of an already-dead token — a reasonable cost of the bulk-update redesign that avoids the self-deadlock described in `AuthService.refresh()`'s own comments (`:178-209`), but never stated as a tradeoff anywhere in code.
5. `isGenuineDenial(Exception cause)` (`JWTAuthorizationFilter.java:348`) is typed against `Exception`, the generic superclass, rather than the union its one call site (`:150`) can actually pass: `AccountStatusException | AuthorizationException | AccessDeniedException`. All three are unchecked (`RuntimeException` subtypes) — `Exception` is strictly wider than necessary. Harmless today; a future unrelated caller passing a checked `Exception` would silently get `false` rather than a compile error.
6. `isGenuineDenial`'s `ACCOUNT_NOT_LOGIN_ABLE` branch has no per-client throttle on its `REQUIRES_NEW` revocation write, unlike `maybePublishSecurityAlert`'s own `SecurityAlertThrottle` (`:378-403`) for the identical cause. A client that ignores `Set-Cookie` and keeps replaying the same stale JWT for a since-locked account triggers one `markUsedByTokenHash` write per request, indefinitely. `daoAuthProvider.authorize()`'s own DB read already happens unthrottled on every such retry today — this adds one write to an existing read-amplifier, not amplification from zero — but the exact write-amplification class `SecurityAlertThrottle` exists to bound on the audit-log side is left unbounded on the revocation side.

**Explicitly excluded from this bundle** (both from the same `skillars-deferred-143` review, both genuinely out of scope — re-confirmed, not silently dropped):
- `markUsedByTokenHash`/`markAllUsedByUserId` being unguarded against transient DB failures — pre-existing pattern repo-wide, the review itself says "only worth adding if this class of DB failure is ever observed in production." No action here.
- `isGenuineDenial` not treating `AuthorizationException(USER_NOT_FOUND)`/`(UNKNOWN)` as genuine denials — `AuthService.refresh()`'s own `findById` rejection (`:212-216`) already fully revokes that exact case independently, and `POST /api/auth/refresh` has no live caller today, so the practical gap is nil. The `skillars-deferred-143` review itself scoped this as "a reasonable follow-up, not a thing this story promised" — same reasoning applies here; leaving it for whichever future story next touches `isGenuineDenial` for an unrelated reason.

### Finding 7 — `JWTAuthorizationFilter`'s fast path issues a `refresh_tokens` DB query on every authenticated request, for a benefit it does not actually have over the account-lock case it already accepts

Added during implementation of this story (not sourced from `deferred-work.md`), per the user's own review of `attemptAuthorization` (`JWTAuthorizationFilter.java:193-230`).

**Current behavior.** On the "fast path" (`loginTokenManager.hasDbRefreshTokenExpired(req)` is `false` — i.e. the DB-refresh-token claim minted at the last DB re-auth is still within its `DB_REFRESH_TOKEN_INTERVAL` = 5-minute window), the filter calls `isRefreshTokenRevoked(req, principal)` (`:232-245`), which runs `refreshTokenRepository.findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(userId, now)` whenever an `rtkn` cookie is present. If it finds no live token, it forces the full DB re-auth path (`daoAuthProvider.authorize(...)`) early instead of the cheap `checkAuthorities`/`extendTtlOfToken` pair. This runs on **every** authenticated request carrying an `rtkn` cookie — i.e. nearly all of them — not gated by the 5-minute window at all.

**The only two things that make `isRefreshTokenRevoked` ever return `true`** (traced by re-reading every caller of `RefreshTokenRepository.markAllUsedByUserId`, the only method that revokes *every* token for a user at once — `markUsedByTokenHash`, the single-token sibling, revokes only the one token tied to the calling request and can never make this method see a live-token count of zero for a user with other active sessions):
1. `AuthService.refresh()`'s theft-detection branches (`:153`, `:160`) — an already-used token is re-presented with no live successor.
2. `GdprErasureService` (`:409`) — account erasure.

**The comparison that settles this.** `UserAdminService.lockUserAccount()` (`UserAdminService.java:116-121`) calls only `u.lock()` — it has **zero** `refresh_tokens` side effect. So an admin-locked account's other open sessions get caught **only** by the natural 5-minute `hasDbRefreshTokenExpired` cycle — there is no early-detection shortcut for locking at all, and this codebase (this very story bundle's own lineage, `skillars-deferred-143`) already accepts that bound as fine for the single most security-sensitive enforcement case it has. `isRefreshTokenRevoked` therefore buys *faster-than-5-minutes* detection only for erasure and theft-mass-revoke — a stronger guarantee than the codebase already accepts for locking, purchased at the cost of one DB round-trip on every authenticated request, for every user, regardless of whether revocation ever happens.

**What the DB re-auth path (`daoAuthProvider.authorize(...)`) actually checks that the fast path does not.** It reloads the `User` entity via the `UserDetailsService` and runs Spring Security's `DefaultPreAuthenticationChecks` (`isAccountNonLocked()`/`isEnabled()`), throwing `AccountStatusException` (rewrapped by `DaoAuthProvider` into this project's `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)`) for a locked or deactivated account. This is the **only** place account liveness is re-checked against the DB in this filter; `checkAuthorities` on the fast path only checks role authorities already embedded in the JWT claims, never liveness.

**The fix: delete the early-detection shortcut, don't relocate it.** Checking `isRefreshTokenRevoked` once `hasDbRefreshTokenExpired` is already `true` would be a no-op — `daoAuthProvider.authorize(...)` already runs unconditionally on that path and does not need to know *why* a re-check was forced. The correct change is to remove the `isRefreshTokenRevoked` branch (and the method, and the now-unused `RefreshTokenRepository` constructor dependency it was the sole user of) from the fast path entirely, letting erasure/theft-revoke ride the same `DB_REFRESH_TOKEN_INTERVAL` bound that locking already rides. Net effect: removes one DB round-trip from nearly every authenticated request; worst-case detection latency for erasure/theft-mass-revoke changes from "next request" to "within `DB_REFRESH_TOKEN_INTERVAL` (5 min) of the user's last DB re-auth" — the same bound `lockUserAccount()` already has today, not a new one.

**Judgment call, recorded, not silently accepted:** GDPR erasure is the one case here where "next request" vs. "within ≤5 min" could matter to a compliance reviewer. The team's call for this story is that a bounded ≤5-minute window is acceptable, on the grounds that it is already the accepted bound for the more security-sensitive account-lock case. If a future auditor disagrees, the fix is narrower than reverting this story: re-add a liveness-only check (not a full `refresh_tokens` lookup) at a point that fires only for erasure, not on every request.

---

## Design

### A. `SkillarsProfileCookie` — the single owner of the `skp` wire format and both of its operations

New type, `com.softropic.skillars.platform.security.contract.SkillarsProfileCookie`.

**`[DECIDED]` package placement — record this, don't re-litigate it:** `project-context.md:115` defines `contract` narrowly as "Public API of the module: DTO records, Events, Exceptions" — a cookie-writing record with `jakarta.servlet.http.HttpServletResponse` side effects is none of those, and (verified by grep) no file anywhere in the top-level `platform/security/contract/` package today imports `jakarta.servlet` or `infrastructure.security` — only the `contract/exception/` subpackage does. So `project-context.md` does NOT settle this placement the way earlier drafting claimed; it only rules OUT `infrastructure.security` (which must stay business-agnostic and already knows nothing of `SkillarsRole`). The structurally cleaner alternative — `platform.security.infrastructure`, which already holds `JwtManagerImpl` (one of the three writers this type replaces) and follows this module's own "interface in `service`, implementation in `infrastructure`" pattern (`LoginTokenManager` ↔ `JwtManagerImpl`) — was considered and NOT taken: it would make `AuthService` (in `platform.security.service`) the first service-layer file in this module to import from `platform.security.infrastructure.*` (verified by grep — zero service-layer files do this today; only the `@Configuration` class `SecurityConfiguration` does, which is a different, expected pattern for wiring concrete beans). Introducing a new cross-package import direction is a bigger architectural move than this story's narrow scope warrants. **Decision: keep it in `contract`, as a deliberate, narrow exception to that package's literal definition** — justified by `SkillarsRole` already living there (the type's own dependency) and by both writers (`AuthService`, `JwtManagerImpl`) already being able to import it without a new direction. If a second servlet-side-effecting type is ever added to this module, revisit whether `contract` is still the right generalization or whether it is time to introduce the `infrastructure` alternative properly.

```java
package com.softropic.skillars.platform.security.contract;

import com.softropic.skillars.infrastructure.security.CookieUtil;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_TTL;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.SKILLARS_PROFILE_COOKIE;

/**
 * The single owner of the {@code skp} cookie's wire format: a quoted-id JSON payload
 * ({@code {"id":"...","role":"..."}}), percent-encoded for a non-HttpOnly cookie the frontend
 * decodes with {@code decodeURIComponent}. Previously open-coded at three call sites
 * ({@code AuthService.login()}, {@code AuthService.refresh()}, {@code JwtManagerImpl
 * .setSkillarsProfileCookie}) — two of them byte-for-byte identical. See
 * skillars-deferred-144 for the consolidation and skillars-deferred-142/the quoted-id
 * comment this type now owns for why {@code id} must be quoted: an unquoted id silently
 * corrupts the frontend's {@code authStore.userId} via IEEE-754 double rounding.
 */
public record SkillarsProfileCookie(String id, String role) {

    public void writeTo(HttpServletResponse res) {
        String json = "{\"id\":\"" + id + "\",\"role\":\"" + role + "\"}";
        // java.net.URLEncoder is FORM encoding (space -> '+'); the frontend decodes with
        // decodeURIComponent, which leaves '+' literal. The two are not inverse functions.
        // Today's payload (a numeric id, an enum name) can never contain a space, so this has
        // never fired — but the next field added here must not reintroduce the mismatch.
        String encoded = URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20");
        CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, encoded, false,
            (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");
    }

    public static void removeFrom(HttpServletResponse res) {
        CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, res, false, "Lax");
    }
}
```

Call-site deltas (role/id derivation — the one genuinely different part per site — is untouched):
- `AuthService.login()` (`:132-135`): replace the 4 lines building `json`/`skpValue`/`addCookie` with `new SkillarsProfileCookie(String.valueOf(user.getId()), role).writeTo(res);`.
- `AuthService.refresh()` (`:270-273`): identical replacement.
- `JwtManagerImpl.setSkillarsProfileCookie` (`:119-121`): replace with `new SkillarsProfileCookie(String.valueOf(claims.get(BUS_ID)), role).writeTo(res);`.
- Drop the now-unused `java.net.URLEncoder`/`java.nio.charset.StandardCharsets` imports from `AuthService.java` and `JwtManagerImpl.java` if nothing else in either file still needs them (`AuthService` still needs `StandardCharsets` for `sha256Hex` — keep that one; `JwtManagerImpl` does not use `StandardCharsets` elsewhere — drop it).

### B. Close the `deleteLoginToken` asymmetry

- `JwtManagerImpl.deleteLoginToken` (`:182-190`) gains one line: `SkillarsProfileCookie.removeFrom(response);` — alongside the six existing `CookieUtil.removeCookie` calls, not replacing any of them. `rtkn` stays untouched here, deliberately (Finding 2).
- `SecurityUtil.clearAuthCookies` (`:187-191`) **keeps** its explicit `skp`-removal line, but switches it from the raw `CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, response, false, "Lax")` call to `SkillarsProfileCookie.removeFrom(response);` — i.e. delegate to the new type rather than deleting the line. **Do not delete it**, even though it becomes redundant with `deleteLoginToken`'s own new call (both run on this path, so `skp` gets a harmless duplicate `Set-Cookie: skp=; Max-Age=0` removal header): `SecurityUtilTest.terminateSession_clearsRefreshTokenAndProfileCookies` (`SecurityUtilTest.java:81-100`) mocks `loginTokenManager` (`:46`), so calling the mock's `deleteLoginToken` emits no real header at all in that test — only `clearAuthCookies`'s OWN direct cookie-removal calls produce the `Set-Cookie` headers this test asserts on (`:96-99`). Deleting the line instead of delegating would break that test for a reason that has nothing to do with this story's actual intent (`clearAuthCookies`'s own documented postcondition — "drops `rtkn` and `skp`" — stays literally true either way). `clearAuthCookies` keeps its own `rtkn` removal (`:189`), which has no equivalent in `deleteLoginToken` by design.

### C. Shared, resolvability-checked open-redirect guard

New file `src/frontend/src/router/safeRedirect.js`, sibling to `roleRoutes.js` and following its documentation style:

```js
/**
 * Decides whether a `?redirect=` query value is safe to navigate to after login/OTP —
 * previously duplicated verbatim in LoginPage.vue and OtpPage.vue (skillars-deferred-142 AC3's
 * own explicit requirement, to keep the two pages byte-identical). See roleRoutes.js's own
 * header for why a duplicated routing concern is dangerous even when the two copies are
 * currently identical: the failure mode is silent on introduction and only appears once one
 * copy is changed and the other isn't.
 *
 * Two checks: shape (must be an app-relative path, never protocol-relative or absolute — this
 * is the open-redirect guard itself) and resolvability (must match a real route, otherwise even
 * a shape-safe value would dead-end the just-authenticated user on the 404 page instead of
 * their role's dashboard).
 *
 * `matched.length > 0` alone is NOT sufficient here, unlike in a route table with no catch-all:
 * routes.js registers `/:catchAll(.*)*` -> ErrorNotFound.vue, which matches every `/`-prefixed
 * path, so an unresolvable redirect would still report matched.length === 1 (verified by
 * executing vue-router 4.6.4 against this exact route shape). The catch-all is tagged
 * `meta: { notFound: true }` (routes.js) specifically so this guard can exclude it.
 */
export function isSafeRedirect(path, router) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) {
    return false
  }
  const resolved = router.resolve(path)
  return resolved.matched.length > 0 && !resolved.matched.some((r) => r.meta?.notFound)
}
```

`LoginPage.vue`/`OtpPage.vue` both change from the inline guard to:
```js
const redirect = route.query.redirect
const safePath = isSafeRedirect(redirect, router) ? redirect : routeForRole(/* response.role | authStore.role */)
router.push(safePath)   // OtpPage.vue: router.replace(safePath) — see Finding 4
```

### D. The six `skillars-deferred-143` review items — fixes, in the order listed in Finding 6

1. **Decision point, not a mechanical fix** — see AC6.1.
2. `AuthService.refresh()`'s two reuse-detection branches (`:161-163`, `:166-169`): since `markAllUsedByUserId(ownerId)` has already revoked every token for this user, replace the subsequent `securityUtil.terminateSession(req, res)` call in **both** branches with `securityUtil.clearAuthCookies(res)` — same cookie-clearing outcome, without the redundant per-token `markUsedByTokenHash` write `terminateSession` would otherwise also issue.
3. Extend the optimistic-lock-loser comment (`AuthService.java:239-241`) to name `markUsedByTokenHash`/a concurrent forced-logout as the second possible writer of the row, alongside the already-named concurrent refresh. Comment-only.
4. Add a one-line comment at `SecurityUtil.terminateSession`'s `markUsedByTokenHash` call (`:171`) acknowledging the conditional-to-unconditional tradeoff from `AuthService.logout()`'s perspective. Comment-only.
5. Narrow `isGenuineDenial`'s parameter from `Exception` to `RuntimeException` (`JWTAuthorizationFilter.java:348`) — strictly narrower, still covers all three types the one call site passes, compiles unchanged.
6. **Not a throttle — narrow the revocation query itself, so a repeat write costs zero rows instead of needing to be gated at all.** A per-client throttle was the original idea, but it does not survive scrutiny against this codebase's real collision-prone client-identifier chain (see AC6.6 below for why) and it would also silently drop `JWTAuthorizationFilter`'s `SecurityContextHolder.clearContext()` on the throttled branch (`terminateSession` clears it, `clearAuthCookies` does not, and the filter's own context is genuinely populated on this exact path — unlike the unauthenticated routine-denial branch). The actual goal — bound repeat-write cost for a client that keeps replaying the same stale JWT — is better met by making the write itself a no-op once already done: narrow `RefreshTokenRepository.markUsedByTokenHash`'s `@Query` from `WHERE r.tokenHash = :tokenHash` to `WHERE r.tokenHash = :tokenHash AND r.used = false`. `used` is a monotonic terminal flag (the method's own javadoc, `RefreshTokenRepository.java:23-28`), so this changes nothing about correctness — a row already `used = true` now simply isn't rewritten, at zero cost, with no new state, no repurposed audit-volume control, and no `clearContext` gap (`terminateSession` is called unconditionally, unchanged). **Caveat to record, not silently accept:** this also means the `version` bump is skipped on an already-revoked row; the existing javadoc leans on that bump to fail a concurrent stale write touching OTHER columns (`rotatedAt`, `expiresAt`) on the same row. Since `used` itself can never move back to `false`, the residual risk is cosmetic (no code path re-reads `version` to gate on it today) — but it is a real, deliberate delta from the method's documented optimistic-locking contract, and the javadoc must say so explicitly, not just show the new `WHERE` clause.

### E. Remove `JWTAuthorizationFilter`'s per-request `isRefreshTokenRevoked` fast-path check (Finding 7)

`attemptAuthorization`'s `else` branch (`hasDbRefreshTokenExpired(req)` is `false`) currently has an inner `if (isRefreshTokenRevoked(req, principal)) { ... } else { checkAuthorities + extendTtlOfToken }`. Collapse this to just the `else` body, unconditionally:

```java
} else {
    final Principal principal = loginTokenManager.extractPrincipal(req);
    var authorities = CollectionUtils.emptyIfNull(principal == null ? null : principal.getAuthorities());
    daoAuthProvider.checkAuthorities(httpEndpointGuard.requiredAuthorities(req), authorities);
    loginTokenManager.extendTtlOfToken(req, res);
}
```

Delete `isRefreshTokenRevoked` itself (`:232-245`). This makes `RefreshTokenRepository` (the field, its constructor parameter, and its one assignment) entirely unused in this file — remove the field, the constructor parameter, the `RefreshTokenRepository` import, the now-dead `CookieUtil` import (its only other use in this file was inside the deleted method), the now-dead `java.time.Instant` import (same — no other use in this file), and the `REFRESH_TOKEN_COOKIE` static import (same).

**Cascades to update:**
- `SecurityConfiguration.filterChain(...)` (`:149-216`) — remove the `RefreshTokenRepository refreshTokenRepository` method parameter (`:162`) and the corresponding constructor argument at the `new JWTAuthorizationFilter(...)` call site (`:206`). Remove the now-unused `RefreshTokenRepository` import if nothing else in the class needs it (verified: nothing else does).
- `JWTAuthorizationFilterTest.java` — remove the `@Mock RefreshTokenRepository refreshTokenRepository` field and the corresponding constructor argument in `setUp()`. No test method stubs or verifies anything on this mock today (verified by grep), so no test behavior changes — this is a pure constructor-signature cleanup.
- `JWTAuthorizationFilter`'s own class-level javadoc ("Sliding-window session keep-alive" section) — the "Fast path" bullet's "but it still issues one `refresh_tokens` lookup (`isRefreshTokenRevoked`) whenever an `rtkn` cookie is present" clause is no longer true; reword to state the fast path issues no DB query at all. The "DB re-auth path" bullet's "or all refresh tokens for the user have been revoked" clause is also no longer a trigger for entering that path from the fast-path side — reword to note that a revoked-but-not-yet-expired-DB-refresh-token account is now caught only once `DB_REFRESH_TOKEN_INTERVAL` naturally elapses, same as a locked account always was.
- `RefreshTokenRepository.java`'s class-level javadoc and `findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc`'s own method javadoc (both edited by this same story's Design section earlier, documenting "two callers, two purposes") — `JWTAuthorizationFilter` is no longer a caller. Reword both to name the sole remaining caller, `AuthService.refresh()`'s multi-tab grace-window lookup, without the now-false "two callers" framing.
- Add a short comment at the collapsed `else` branch itself, not just in this story file, explaining *why* the revoked-check was removed and the bound this leaves in place (mirrors the Finding 7 text above, condensed) — so a future reader of the filter alone, without this story file, understands the tradeoff without re-deriving it.

**Not touched:** `DaoAuthProvider.authorize(...)` and everything on the `hasDbRefreshTokenExpired(req) == true` branch are unchanged — that path already unconditionally re-checks account liveness against the DB and needs no help from `isRefreshTokenRevoked`'s result. `AuthCleanupService`, `GdprErasureService`, and `AuthService.refresh()`'s own theft-detection/grace-window logic are unchanged; this is purely about deleting an early-detection shortcut on the filter's fast path.

---

## Acceptance Criteria

**AC1 — `SkillarsProfileCookie` is the single owner of the `skp` wire format**
- [x] New `com.softropic.skillars.platform.security.contract.SkillarsProfileCookie` record with `writeTo(HttpServletResponse)` and `static removeFrom(HttpServletResponse)`, per the Design section above.
- [x] `AuthService.login()`, `AuthService.refresh()`, and `JwtManagerImpl.setSkillarsProfileCookie` all delegate to it; no call site still hand-builds the JSON string or calls `URLEncoder.encode` directly.
- [x] The encoding fix (form-encoding `+` replaced with `%20`) lives in `SkillarsProfileCookie.writeTo` only, applied uniformly to all three former call sites by construction.
- [x] Unused `URLEncoder`/`StandardCharsets` imports dropped from `JwtManagerImpl.java` (keep `StandardCharsets` in `AuthService.java` — still used by `sha256Hex`).

**AC2 — `skp` is cleared everywhere the other JWT-session cookies are cleared**
- [x] `JwtManagerImpl.deleteLoginToken` also calls `SkillarsProfileCookie.removeFrom(response)`. `rtkn` is explicitly NOT added here (would reintroduce the write-amplification `skillars-deferred-143` AC3 avoided).
- [x] `SecurityUtil.clearAuthCookies`'s explicit `skp`-removal line is **switched to delegate** to `SkillarsProfileCookie.removeFrom(response)` — **NOT deleted**. Deleting it breaks `SecurityUtilTest.terminateSession_clearsRefreshTokenAndProfileCookies` (`:81-100`), which mocks `loginTokenManager` and therefore cannot observe `deleteLoginToken`'s own new removal; only `clearAuthCookies`'s own direct call produces the real `Set-Cookie` header that test's assertions (`:96-99`) check. The resulting duplicate removal header on this one path is harmless and already anticipated by the source ledger (`deferred-work.md:3689-3690`).
- [x] **Four comment/javadoc passages this AC makes false must be updated, not left to drift** (this story spends two whole ACs, 6.3/6.4, on exactly this standard elsewhere — hold AC2 to it too):
  - `JWTAuthorizationFilter.java:159-162` — `"No DB read or write, and no rtkn/skp clearing"` — no longer true for `skp` after this AC; reword.
  - `JWTAuthorizationFilter.java:152-155` — `"Routine, expected traffic keeps exactly its previous behaviour"` — no longer exactly true; reword.
  - `SecurityUtil.java:177-179` — `"drops the six cookies deleteLoginToken owns plus rtkn and skp"` — `deleteLoginToken` owns seven now, and `skp` is cleared by both methods, not uniquely by this one; reword.
  - `SecurityUtilTest.java:81` `@DisplayName` (`"...on top of the six cookies deleteLoginToken handles"`) and its `:87-88` comment — same staleness; reword. The test's ASSERTIONS do not change (see the delegate-not-delete note above), only its prose.
- [x] This is a disclosed behavior change on `JWTAuthorizationFilter`'s routine-denial branch — call it out explicitly in the Dev Agent Record, don't let it read as a silent side effect of AC1's refactor. **The disclosed cause list must be the complete one, not just the two most obvious:** the routine/`else` branch (`:158-165`) is reached by `JWTExpiredException`, `MissingAuthenticationException`, AND every `AuthorizationException` whose error code is NOT `ACCOUNT_NOT_LOGIN_ABLE` — concretely `MISSING_RIGHTS` (`DaoAuthProvider.checkAuthorities`), `USER_NOT_FOUND`, `UNKNOWN`, `MISSING_USERNAME` (all three from `DaoAuthProvider.authorize()`/`determineUsername`) — AND `AccessDeniedException`. **`JWT_PARSE_ERROR` is NOT in this list** — it is thrown as `InvalidJWTDataException` (`ClaimsExtractorImpl.java:45`, `JwtManagerImpl.java:87`), which extends `AuthorizationException` but is caught by `isGenuineDenial`'s own earlier, type-based disjunct (`cause instanceof InvalidJWTDataException`, `:349`) regardless of error code — so it is already a genuine denial today, unaffected by this AC.
- [x] **A test at the level where the behavior actually changes, not just a unit test of `deleteLoginToken` in isolation.** `JwtManagerImplTest.testDeleteLoginToken_success` (AC7) proves the cookie list changed; it does not prove the filter's routine-denial 401 response now expires `skp`. Add a new case — `SecurityIT` is the natural home — that drives a real request through the filter with a genuinely expired JWT (construct via `ClockProvider.setClock(...)`, the same mechanism `JwtManagerImplTest`/`LoginInfoServiceIT` already use to fast-forward past a token's expiry) and asserts the 401 response's `Set-Cookie` headers include `skp=;...Max-Age=0`.

**AC3 — the open-redirect guard is de-duplicated and route-resolvability-checked**
- [x] `routes.js`'s catch-all route (`:352-355`, `/:catchAll(.*)*`) gains `meta: { notFound: true }`.
- [x] New `src/frontend/src/router/safeRedirect.js` exporting `isSafeRedirect(path, router)`, per the Design section — `matched.length > 0` ALONE is wrong (see Design section C); it must also exclude a match that is only the tagged catch-all.
- [x] `LoginPage.vue` and `OtpPage.vue` both call it instead of their own inline copy of the guard.
- [x] A shape-safe `redirect` value that matches no real route (e.g. `/typo`) falls back to `routeForRole(...)`, not the 404 page, on both pages — see AC7 for the two-level test strategy this requires (a mock route list alone, with no catch-all, CANNOT catch this class of defect — confirmed: the existing `OtpPageSpec.js` mock route list has no catch-all today).

**AC4 — `OtpPage.vue`'s terminal redirect uses `router.replace`**
- [x] `OtpPage.vue:163`'s `router.push(safePath)` becomes `router.replace(safePath)` (precedent: `PlayerHomeRedirectPage.vue`'s one-shot-redirect shape, NOT `VideoManagementPage.vue:108` — see Finding 4's correction). `LoginPage.vue:175` is explicitly unchanged (Finding 4) — do not "fix" it to match.

**AC5 — `AuthResourceIT.refresh_expiredToken_returns401` actually exercises the expiry branch it is named for**
- [x] The seed insert (`:368-372`) is routed through the file's own `commitWrite(...)` helper instead of a bare `jdbcTemplate.update`.
- [x] **Direction matters — pick the raw value FIRST, then derive its hash; do not try to invert a hash.** The seeded `token_hash` (`:367`, `expiredHash = "deadbeef0123..."`) is an arbitrary hardcoded hex literal with no known preimage — there is no raw value whose `sha256Hex(...)` equals it, short of a preimage attack. The fix is: pick a new raw token string (e.g. `"expired-refresh-token-raw-value"`), compute `expiredHash = sha256Hex(rawToken)` with the class's own existing helper (`:739-747`) at seed time, insert THAT hash, and send `cookieHeaders(rawToken)` — so the seeded row and the request's cookie now genuinely refer to the same token.
- [x] The test still asserts 401 — now provably via the expiry branch (`AuthService.java:173-176`), not the not-found branch. The two branches are observably distinguishable at no extra cost: both throw `BadCredentialsException` with the same HTTP status, but `usedFlagOf(expiredHash)` (the class's existing helper, `:710-713`) returns `true` only on the expiry branch, because `AuthService.java:174`'s `terminateSession` call revokes the token in a `REQUIRES_NEW` transaction that commits independently. Add that assertion — it is the real proof, not an afterthought.
- [x] The seeded row's hardcoded `id = 990001` (`:370`) must not collide with any other live `refresh_tokens` row in this class — `tearDown` (`:139-152`, confirmed `DELETE FROM main.refresh_tokens`) already clears the table per test, so this is a sanity check, not a new mechanism to build.

**AC6 — the six `skillars-deferred-143` review residuals are closed**
- [x] AC6.1 — **Decision (recommended: keep)**: the dead `cause instanceof AccountStatusException` disjunct in `isGenuineDenial` (`JWTAuthorizationFilter.java:351`). If kept, add a one-line comment at the disjunct itself (not just the class-level javadoc) stating it is unreachable via `DaoAuthProvider.authorize()` today and is deliberate defense-in-depth against a future caller that bypasses it. If removed, the four tests mocking `daoAuthProvider` to throw the raw Spring exception type must be removed or re-targeted — re-verify `JWTAuthorizationFilterTest` doesn't silently lose coverage of a real path by deleting them.
- [x] AC6.2 — `AuthService.refresh()`'s two reuse-detection branches (`:161-163`, `:166-169`) call `securityUtil.clearAuthCookies(res)` instead of `securityUtil.terminateSession(req, res)`, since `markAllUsedByUserId` has already revoked every token for the user in both branches. This adds two more callers of `clearAuthCookies`, so `SecurityUtil.java:181-185`'s javadoc — `"Exists for the one caller that must not revoke ... Prefer terminateSession everywhere else"` — becomes false and must be reworded to name all current callers and their reasons, not just the original optimistic-lock-loser one.
- [x] AC6.3 — the optimistic-lock-loser comment (`AuthService.java:239-241`) names a concurrent forced-logout's `markUsedByTokenHash` as a second possible writer of the row.
- [x] AC6.4 — a one-line comment at `SecurityUtil.terminateSession`'s revocation call (`:171`) acknowledges the conditional-to-unconditional tradeoff versus pre-`skillars-deferred-143` `logout()` behavior.
- [x] AC6.5 — `isGenuineDenial(Exception cause)`'s parameter narrows to `RuntimeException` (`JWTAuthorizationFilter.java:348`).
- [x] AC6.6 — **reusing `SecurityAlertThrottle` for this was rejected during drafting; do not implement the original per-client-throttle design described in the sourcing ledger.** It has two real defects, both confirmed against this codebase's actual code, not hypothesized: (1) `SecurityAlertThrottle`'s own javadoc states it is "volume control, not a security decision" — gating a revocation write with it turns an audit-log dedup into a security-relevant decision, which is a different thing than what it was built and reviewed for; (2) its client-identifier key (`RequestMetadata.getClientIdentifier()`, falling through apiKey → `bcookie` → `fcookie` → IP → `"unknown"`) is exactly the kind of key `LoginAttemptsService.java`'s own comment warns "may not exist for a browser (or else many users will share the same key)" — and the adversary this AC is defending against (a client that ignores `Set-Cookie` and keeps replaying a stale JWT) is precisely the client with no `bcookie`/`fcookie`, so it degrades to the IP; `AuthorizationException` is one Java class for every `SecurityError`, so two different locked accounts behind one shared/NAT'd IP within the same 60s window would collide into one throttle bucket — the first revoked, the second silently not, a real security regression versus today's unconditional revocation. Implement instead: narrow `RefreshTokenRepository.markUsedByTokenHash`'s `@Query` to also require `r.used = false`, per Design D.6. `JWTAuthorizationFilter`'s catch block is UNCHANGED by this AC — it still calls `securityUtil.terminateSession(req, res)` unconditionally for every genuine denial; the bounded-cost property comes from the now-idempotent-at-the-SQL-level write, not from a new conditional in the filter.
- [x] AC6.6's query change updates `RefreshTokenRepository.markUsedByTokenHash`'s own javadoc (`:35-55`, the `@Query` itself at `:58`) to disclose the `version`-bump-skipped-on-already-used-rows delta explicitly — in particular its closing sentence ("the `version` bump fails any concurrent stale write touching another column instead of letting it silently succeed," `:52-54`) stops being true for a row that is already `used = true`, and must say so (see Design D.6's caveat). Do not let this ship as a silent behavior change to a method with multiple other call sites depending on its documented contract.
- [x] The two explicitly-excluded items from Finding 6 (transient-DB-failure handling, `USER_NOT_FOUND`/`UNKNOWN` not treated as genuine denials) are NOT touched — confirm no task below accidentally does so.

**AC8 — `JWTAuthorizationFilter`'s fast path issues no DB query (Finding 7)**
- [x] `attemptAuthorization`'s `isRefreshTokenRevoked` branch is removed; the fast path (`hasDbRefreshTokenExpired(req) == false`) unconditionally runs `checkAuthorities` + `extendTtlOfToken`, per Design section E.
- [x] `isRefreshTokenRevoked` itself, the `RefreshTokenRepository` field/constructor-parameter/import, the `CookieUtil` import, the `java.time.Instant` import, and the `REFRESH_TOKEN_COOKIE` static import are all removed from `JWTAuthorizationFilter.java` — confirm none has a surviving use elsewhere in the file before deleting (verified in Design section E; re-verify at implementation time since citations drift).
- [x] `SecurityConfiguration.filterChain(...)`'s `RefreshTokenRepository` parameter and the corresponding `new JWTAuthorizationFilter(...)` constructor argument are removed; the now-unused `RefreshTokenRepository` import is dropped if nothing else in the class needs it.
- [x] `JWTAuthorizationFilterTest.java`'s `RefreshTokenRepository` mock field and constructor argument are removed. Confirmed in Design section E that no test method stubs or verifies this mock today, so this is a pure signature cleanup with zero test-behavior change — re-confirm at implementation time rather than trusting the earlier grep blindly.
- [x] `JWTAuthorizationFilter`'s class-level "Sliding-window session keep-alive" javadoc and `RefreshTokenRepository`'s own class/method javadoc (both touched earlier in this same story) are reworded to stop describing `isRefreshTokenRevoked`/`JWTAuthorizationFilter` as a caller — per Design section E's specific rewording instructions.
- [x] A short comment at the collapsed fast-path `else` branch records why the check was removed and the resulting bound (≤`DB_REFRESH_TOKEN_INTERVAL`, same as the pre-existing account-lock case), so the tradeoff is legible from the filter alone.
- [x] No change to `DaoAuthProvider.authorize(...)`, the `hasDbRefreshTokenExpired(req) == true` branch, `AuthCleanupService`, `GdprErasureService`, or `AuthService.refresh()`'s theft-detection/grace-window logic — confirm no task accidentally touches these.

**AC7 — Testing & Definition of Done**
- [x] New `SkillarsProfileCookieTest` (first test class for this type): `writeTo` produces a quoted-id JSON payload with the correct `httpOnly=false`/`maxAge=REFRESH_TOKEN_TTL.toSeconds()`/`sameSite=Lax`/`path=/` cookie attributes; a role or id value containing a space encodes to `%20`, not `+`, when run through `writeTo` (proves the Finding 1 encoding fix, since today's real payloads never contain a space); `removeFrom` produces a `Max-Age=0` removal for `skp` specifically. This is the "one focused unit test pinning the wire format" the ledger's own umbrella proposal called for — it supersedes patching `JwtManagerImplTest`'s two existing `skp`-cookie-value assertions (`:622-636`, `:641-655`) individually; those stay as-is (they test role-derivation logic, not the wire format).
- [x] `JwtManagerImplTest.testDeleteLoginToken_success` (`:483-505`) updated: `SKILLARS_PROFILE_COOKIE` added to `loginCookieNames`, `hasSize(loginCookieNames.size())` now expects 7, not 6 — this is the unit-level regression test for AC2's cookie list, and it is mutation-checkable (revert AC2's one-line addition and confirm this test alone catches it). It does NOT prove the filter-level behavior change — see AC2's own new `SecurityIT` case for that.
- [x] A new `AuthResourceIT` or `SecurityUtilTest` case for AC6.2: with `markAllUsedByUserId` already revoked (seed a used token for the user, or drive the reuse-detection path directly), the branch's cookie-clearing-only replacement behaves correctly. AC6.2 is a functional change (not comment-only like 6.1/6.3/6.4, not compile-only like 6.5) and currently has no test anywhere in this AC7 list beyond "re-run the suite, zero regressions," which cannot catch behavior that doesn't exist yet.
- [x] A new repository-level test for AC6.6: seed a `refresh_tokens` row, call `markUsedByTokenHash` twice on the same hash, and confirm the second call is a true no-op — `used` stays `true` and `version` does NOT increment a second time (proves the `AND r.used = false` predicate actually narrows the write, not just that the method still returns without error).
- [x] **Two-level test strategy for AC3's resolvability fix — a mock route list with no catch-all cannot catch the Finding-1-class defect, so one level alone is not enough:**
  - New `safeRedirectSpec.js` (`src/frontend/src/router/__tests__/`, no existing spec for `roleRoutes.js` either — this establishes the pattern for the directory) unit-tests `isSafeRedirect` against the REAL production route table (`import routes from '../routes'`, built into a real `createRouter`/`createMemoryHistory` instance — `router.resolve()` only matches path/meta, it does not trigger any of the routes' lazy `component: () => import(...)` loaders, so this stays cheap): protocol-relative rejected, absolute rejected, a real registered path (e.g. `/dashboard`) accepted, and — the case that actually proves the fix — an unresolvable path (e.g. `/typo`) rejected specifically BECAUSE it only matches the tagged catch-all, not merely because `matched.length` happens to be 0.
  - New `OtpPageSpec.js`/new `LoginPageSpec.js` (no prior spec file existed for `LoginPage.vue` — confirmed by `find`) cases: add the catch-all stub (`{ path: '/:catchAll(.*)*', component: STUB, meta: { notFound: true } }`) to each spec's own small mock route list, then assert a shape-safe-but-unresolvable `redirect` (e.g. `/typo`) falls back to the role route, not the 404 stub — this proves the two pages wire the fixed guard correctly, at page-mount level, without needing the full real route tree's async page components in a page-level spec. `OtpPageSpec.js` additionally asserts `router.replace` was called for the terminal redirect (spy on the router instance before mount), not `router.push`.
- [x] `AuthResourceIT.refresh_expiredToken_returns401` passes for the corrected reason (AC5) — run it explicitly and confirm via its own assertions/comments, not by assuming green means right.
- [x] AC8 regression: **Correction (Review Findings, 2026-10-06) — the original wording here described coverage that did not exist.** All five pre-existing `hasDbRefreshTokenExpired` stubs in `JWTAuthorizationFilterTest` are `true`; the one genuine fast-path test, `testDoFilterInternal_ValidToken_UserAuthenticated`, relied on Mockito's implicit `false` default rather than an explicit stub, and nothing asserted the branch's new unconditionality. Fixed: that test now explicitly stubs `hasDbRefreshTokenExpired(request)` as `false` and verifies `daoAuthProvider.checkAuthorities(...)` + `loginTokenManager.extendTtlOfToken(...)` both ran while `daoAuthProvider.authorize(...)` never did — a real assertion of the collapsed `else` branch's unconditionality, not just "no mock wired in, so it must be fine."
- [x] Full targeted backend suite re-run: `AuthResourceIT`, `JWTAuthorizationFilterTest`, `JwtManagerImplTest`, `SecurityUtilTest`, `SecurityIT`, plus the new `SkillarsProfileCookieTest`. Zero regressions.
- [x] Full frontend Vitest suite re-run (all files, not just the two touched pages) — zero regressions. ESLint/Prettier clean on every touched `.vue`/`.js` file.
- [x] No local `mvn verify` (standing project convention — GitHub CI is the sole full-verification gate).

---

## Tasks / Subtasks

- [x] Task 1 (AC1): Create `SkillarsProfileCookie.java` per the Design section. Migrate `AuthService.login()`/`refresh()` and `JwtManagerImpl.setSkillarsProfileCookie` to it. Drop now-unused imports.
- [x] Task 2 (AC1, AC7): Create `SkillarsProfileCookieTest.java`.
- [x] Task 3 (AC2): Add the `removeFrom` call to `JwtManagerImpl.deleteLoginToken`; switch (not delete) `SecurityUtil.clearAuthCookies`'s existing explicit `skp` removal to delegate to `SkillarsProfileCookie.removeFrom(response)` — deleting it breaks `SecurityUtilTest:81-100`, which cannot observe the mocked `loginTokenManager`'s new behavior. Update the four stale comments/javadoc/`@DisplayName` AC2 lists.
- [x] Task 4 (AC2, AC7): Update `JwtManagerImplTest.testDeleteLoginToken_success`'s cookie-name list and size assertion.
- [x] Task 5 (AC3): Tag `routes.js`'s catch-all with `meta: { notFound: true }`. Create `src/frontend/src/router/safeRedirect.js` with the corrected (catch-all-excluding) `isSafeRedirect`. Migrate `LoginPage.vue` and `OtpPage.vue` to import and call it, deleting their inline guard copies.
- [x] Task 6 (AC4): Change `OtpPage.vue`'s terminal `router.push` to `router.replace`.
- [x] Task 7 (AC3, AC4, AC7): Create `src/frontend/src/router/__tests__/safeRedirectSpec.js`, importing the real `routes.js`. Extend `OtpPageSpec.js`'s mock route list with the tagged catch-all stub, then add the unresolvable-redirect and `router.replace` cases. Create `LoginPageSpec.js` (new file) with at least the equivalent redirect-guard coverage `OtpPageSpec.js` already has for its own page (including the same tagged catch-all stub), plus the new unresolvable-redirect case.
- [x] Task 8 (AC5): Fix `AuthResourceIT.refresh_expiredToken_returns401` per the Design section — route the seed through `commitWrite`; pick a raw token value and DERIVE its hash via `sha256Hex` for the seed (not the reverse — the seeded hash is an arbitrary literal with no known preimage); add the `usedFlagOf(...)` assertion proving the expiry branch, specifically, was reached.
- [x] Task 9 (AC6.1): Resolve the dead-disjunct decision (default: keep + comment). Document the decision taken in the Dev Agent Record.
- [x] Task 10 (AC6.2): Change both `AuthService.refresh()` reuse-detection branches from `terminateSession` to `clearAuthCookies`. Reword `SecurityUtil.java:181-185`'s now-stale "one caller" javadoc.
- [x] Task 11 (AC6.3, AC6.4): The two comment-only fixes.
- [x] Task 12 (AC6.5): Narrow `isGenuineDenial`'s parameter type.
- [x] Task 13 (AC6.6): Narrow `RefreshTokenRepository.markUsedByTokenHash`'s `@Query` to add `AND r.used = false`; update its javadoc to disclose the version-bump-skip delta. `JWTAuthorizationFilter` itself is NOT touched by this task — no new throttle, no new conditional in the catch block.
- [x] Task 14 (AC7): Run the full targeted backend suite and full frontend suite; fix any regression surfaced by Tasks 1-13 before considering this done. No local `mvn verify`.
- [x] Task 15 (AC8): Remove `isRefreshTokenRevoked` and its branch from `JWTAuthorizationFilter.attemptAuthorization`; delete the now-unused `RefreshTokenRepository` field/constructor-param/imports from the filter. Update `SecurityConfiguration.filterChain(...)`'s matching parameter/constructor-arg and `JWTAuthorizationFilterTest`'s matching mock/constructor-arg. Reword the two javadoc blocks named in Design section E. Re-run the full targeted backend suite.


### Review Findings

`/bmad-code-review` 2026-10-06 — four layers (Blind Hunter diff-only, Edge Case Hunter, Acceptance Auditor, plus orchestrator re-verification). Every finding below was independently re-verified against on-disk source before being recorded; 9 layer findings were dismissed as refuted (see Dismissed, below).

**Decision needed**

- [x] [Review][Decision — RESOLVED 2026-10-06: owner chose (b), keep AC8 and amend the IT to the ≤5 min guarantee, accepting the weaker bound explicitly. Converted to a Patch item below.] **BLOCKER — AC8's fast-path removal breaks an existing passing IT and removes a real security guarantee** — `GdprErasureIT.erase_deactivatesUser_oldSessionRejected` now FAILS. Reproduced by execution, not inference: `mvn failsafe:integration-test -Dit.test='GdprErasureIT#erase_deactivatesUser_oldSessionRejected'` → `Tests run: 1, Failures: 1` / `java.lang.AssertionError: Expecting code to raise a throwable.` at `GdprErasureIT.java:373`. Mechanism, traced end-to-end: the test logs in via `/api/auth/login` (`GdprErasureIT.java:1933-1943` forwards EVERY login cookie, so `rtkn` + `potc` both travel), POSTs erasure, then re-requests with the same cookies expecting 401. At HEAD the deleted `isRefreshTokenRevoked` check saw zero live tokens (erasure's `GdprErasureService.java:409` `markAllUsedByUserId`), forced `daoAuthProvider.authorize`, which failed `retrieveUser(oldEmail)` against the anonymised login → `USER_NOT_FOUND` → 401. Post-AC8 the fast path runs unconditionally; `hasDbRefreshTokenExpired` compares the `DB_REFRESH_TOKEN` claim minted seconds earlier (`JwtManagerImpl.java:55` = now + 5 min) against now → `false`, so no DB read happens at all and the erased user's session is honoured. `SecurityUtil.requireCurrentUserId()` reads the JWT principal, never the DB. Detection slips from immediate to ≤5 min (`SecurityConstants.java:120`). AC7/AC8's regression sweep was scoped to `platform.security.**`; `GdprErasureIT` lives in `platform.admin.api` and was structurally outside it. Options: (a) revert AC8 and keep the per-request query; (b) keep AC8, amend the IT to the ≤5 min guarantee, and accept the weaker bound explicitly; (c) keep AC8 and have erasure terminate sessions by another mechanism.
- [x] [Review][Decision — RESOLVED 2026-10-06: owner chose to keep as documented. No code change; dismissed.] **Duplicate `skp` removal retained in production to satisfy a mock-based test** — `SecurityUtil.clearAuthCookies` (`SecurityUtil.java:207-211`) calls `deleteLoginToken` (which now removes `skp`, `JwtManagerImpl.java:190`) and then `SkillarsProfileCookie.removeFrom` again, emitting two byte-identical `Set-Cookie: skp=; Max-Age=0` headers on every logout/teardown. Harmless to browsers (last-wins) and deliberately documented, but the stated reason is that `SecurityUtilTest` mocks `loginTokenManager` — a reason to change the test, not to keep a redundant production write. Note this diff's own new `soleSkpCookie(...)` helper asserts `hasSize(1)` and would fail if ever pointed at a logout response. Options: (a) keep as documented; (b) drop the redundant line and assert `verify(loginTokenManager).deleteLoginToken(response)` instead (already present at `SecurityUtilTest.java:91`).
- [x] [Review][Decision — RESOLVED 2026-10-06: owner chose (c), build the payload with Jackson. Converted to a Patch item below.] **`SkillarsProfileCookie` hand-builds JSON with no escaping, as the public type explicitly positioned as the extension point** — `SkillarsProfileCookie.java:24`. `new SkillarsProfileCookie("1", "X\",\"id\":\"999")` yields `{"id":"1","role":"X","id":"999"}`; `URLEncoder` escapes the quote to `%22`, the frontend's `decodeURIComponent` restores it, and `JSON.parse` applies last-key-wins, so `authStore.userId` becomes an id no caller supplied. Not reachable today — all three call sites pass `String.valueOf(user.getId())`/`String.valueOf(claims.get(BUS_ID))` and an enum `name()` — but the javadoc invites "the next field added here". Options: (a) accept, documented as caller-trust; (b) escape both fields; (c) build the payload with Jackson.

**Patch**

- [x] [Review][Patch] **DONE 2026-10-06** — renamed to `erase_deactivatesUser_oldSessionHonouredUntilDbRefreshIntervalThenRejected`, implemented exactly as Option A specifies (replay flipped to a 2xx assertion; in-process `daoAuthProvider.authorize(...)` assertion added, no new HTTP call/JWT mint/clock offset). Re-run by execution: `Tests run: 1, Failures: 0`. Full `GdprErasureIT` class re-run after: 34/34 pass. **(from Decision 1b, Option A — owner decision 2026-10-06: no added IT runtime)** Amend `GdprErasureIT.erase_deactivatesUser_oldSessionRejected` to the ≤5 min guarantee, at zero marginal cost [`src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java:362-378`] — the test currently asserts an erased user's live session is rejected on the NEXT request, which AC8 no longer delivers (reproduced: `Tests run: 1, Failures: 1`, `java.lang.AssertionError: Expecting code to raise a throwable.` at `:373`; the method itself costs 12.6 s, the 271 s test-set figure is one-off Spring-context + container startup shared across all 34 methods and the whole suite).
  **Do NOT use `AuthResourceIT.java:375-387`'s stale-claim JWT mint here** — it would add an HTTP round-trip. Instead, inside the existing method and with no new request:
    1. Flip the existing replay assertion from `assertThatThrownBy(...)` 401 to asserting a 2xx. Same HTTP call that already runs; this records the accepted weaker bound explicitly rather than leaving it implied — after AC8 the fast path issues no DB query, so a still-fresh JWT (`DB_REFRESH_TOKEN` = mint time + 5 min, `JwtManagerImpl.java:55`) is honoured until that claim elapses.
    2. Add an in-process assertion that the mechanism the ≤5 min bound depends on really does reject the erased identity: `assertThatThrownBy(() -> daoAuthProvider.authorize(new UsernamePasswordAuthenticationToken(PARENT_EMAIL, null), List.of()))`.isInstanceOf(AuthorizationException.class)`. Costs one in-JVM call (~0 ms), no HTTP, no JWT mint, no clock manipulation (`ClockProvider` is a ThreadLocal and the server runs on its own worker thread, so a test-thread offset would be a no-op anyway). Requires `@Autowired DaoAuthProvider` — it is a `@Bean` at `SecurityConfiguration.java:84`, and `authorize()` only needs `getName()`/`getPrincipal()` non-null (`DaoAuthProvider.java:77-83`).
  Assert on `AuthorizationException` broadly, NOT on `SecurityError.USER_NOT_FOUND`: erasure both renames the login and sets `locked=true`/`activated=false` (`GdprErasureService.java:269-278`), so either `retrieveUser` or `getPreAuthenticationChecks().check(user)` may fire first — `authorize` rewraps both (`DaoAuthProvider.java:42-51`) and the filter translates either to the same 401. Rename the test to say what it now proves (e.g. `erase_deactivatesUser_oldSessionHonouredUntilDbRefreshIntervalThenRejected`).
- [x] [Review][Patch] **DONE 2026-10-06** — `writeTo` now builds via `ObjectMapper().createObjectNode()` + `put("id", ...)`/`put("role", ...)`, preserving field order. Quote-injection case added to `SkillarsProfileCookieTest` (`writeTo_roleContainingQuote_doesNotInjectAnExtraJsonKey`); 4/4 tests pass. **(from Decision 3c)** Build the `skp` payload with Jackson instead of string concatenation [`src/main/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookie.java:24`] — use an `ObjectNode` (`put("id", ...)` then `put("role", ...)`) so field order, and therefore the existing exact-value assertion at `SkillarsProfileCookieTest.java:31`, is preserved; `ObjectNode.toString()` escapes quotes and backslashes and throws no checked exception. Keep `.replace("+", "%20")`. Add the quote-injection case to `SkillarsProfileCookieTest` so the escaping is pinned.
- [x] [Review][Patch] **DONE 2026-10-06** — reworded both the class javadoc's "DB re-auth path" bullet and the collapsed `else` branch's inline comment to stop claiming `daoAuthProvider.authorize` catches "refresh-token-revoked"/"force-logged-out" users; now states explicitly that erasure's ≤5 min bound works only because `GdprErasureService` also sets `locked = true`, and that theft-driven mass-revocation is NOT caught by this path at all (pre-existing gap, tracked separately). Filter javadoc asserts a guarantee that has never existed: the DB re-auth path does NOT catch refresh-token-revoked users [`src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java:79`] — all three review layers found this independently. `DaoAuthProvider.authorize` (`DaoAuthProvider.java:28-59`) does `retrieveUser` + `getPreAuthenticationChecks().check(user)` and never reads `refresh_tokens`. What the ≤5 min bound actually catches is the account-status change that accompanies erasure (`GdprErasureService.java:278` sets `locked=true`), which the story's reasoning never names. Same defect at `:82-84` and in the inline comment at `:210-218`. "force-logged-out" also has no referent — `grep -rn "forceLogout|forcedLogout" src/main/java` returns nothing.
- [x] [Review][Patch] **DONE 2026-10-06** — reworded to say the repeat write now costs nothing (matches zero rows, per AC6.6's `AND r.used = false`) rather than "one extra UPDATE". AC6.4 comment contradicts the javadoc written for AC6.6 in the same change [`src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java:171-175`] — it calls a repeat revocation "one extra UPDATE per logout of an already-dead token — an accepted cost", but `RefreshTokenRepository.java:105-117` (this story's own AC6.6 javadoc) states the `AND r.used = false` predicate makes a repeat call match **zero rows**. The repository is correct; this comment is wrong.
- [x] [Review][Patch] **DONE 2026-10-06** — added `assertThat(requestCookies).anyMatch(c -> c.equals("potc=" + expiredJwt))` right after the substitution. Full `AuthResourceIT` class re-run: 18/18 pass. `routineDenial_expiredJwt_alsoClearsSkillarsProfileCookie` never asserts its own precondition held [`src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java:393-397`] — the `.map(c -> c.startsWith("potc=") ? "potc=" + expiredJwt : c)` substitution is unverified. If `potc` is ever renamed the list passes through untouched, the request carries no JWT, the filter throws `AccessDeniedException` instead of `JWTExpiredException`, the SAME routine branch runs, `skp` is still expired, and the test stays green while no longer testing the expired-JWT path it is named for. Add `assertThat(requestCookies).anyMatch(c -> c.equals("potc=" + expiredJwt))`. This is the same "passes for the wrong reason" defect class AC5 exists to fix.
- [x] [Review][Patch] **DONE 2026-10-06** — added the mirror `verify(refreshTokenRepository).findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(...)` to the grace-window test, and split the sibling test into two: `refresh_reuseNeverRotated...` (the existing `rotatedAt = null` case) plus a new `refresh_reuseRotatedLongAgo...` exercising a real past `Instant` well outside `REFRESH_GRACE_WINDOW`. `AuthServiceTest`: 3/3 pass. Grace-window reuse test cannot fail if the grace-window branch is deleted [`src/test/java/com/softropic/skillars/platform/security/service/AuthServiceTest.java:70-87`] — all four assertions (`markAllUsedByUserId`, `clearAuthCookies`, `never() terminateSession`, `BadCredentialsException`) hold identically in the `else` branch, and `MockitoAnnotations.openMocks` is lenient so the unused stub raises nothing. Add the mirror assertion the sibling test has: `verify(refreshTokenRepository).findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any())`. Related: `:94`'s `setRotatedAt(null)` with the comment "never rotated, or rotated long ago" covers only the degenerate value — a real past `Instant` is untested.
- [x] [Review][Patch] **DONE 2026-10-06** — pinned `/\evil.example.com` and `/%2F%2Fevil.example.com` as explicit `safeRedirectSpec.js` cases (both pass: rejected via the resolvability/catch-all exclusion, not the shape check). Softened `safeRedirect.js`'s header comment to say the shape check is incidental protection today, not "the open-redirect guard itself," and to name the `quasar.config.js` hash-mode setting as the actual reason this can never become off-origin. Full frontend suite: 224/224 pass (was 222; +2 new cases). `safeRedirect.js`'s rejection of backslash and percent-encoded payloads is correct but incidental and unpinned [`src/frontend/src/router/__tests__/safeRedirectSpec.js:17-41`] — I probed 20 payloads against the real route table and every one is rejected today (`/\evil.com`, `/%2F%2Fevil.com`, `/%5Cevil.com`, `/..//evil.com`, `/@evil.com`, tab/newline, and `router.resolve` throws on none, including `/%` and `/%zz`). But the shape check alone would pass `/\evil.com`; only the catch-all exclusion stops it. Add a permissive top-level route (a `/:slug` CMS route is the ordinary way) and it becomes an off-origin navigation with no failing test. Pin `/\evil.example.com` and `/%2F%2Fevil.example.com` as explicit cases, and soften the file header's claim that the shape check is "the open-redirect guard itself".
- [x] [Review][Patch] **DONE 2026-10-06** — added `terminateSession` itself as the first bullet in the "Callers" list, with its own reason (it has already revoked above; this call is cookie-clearing only). `clearAuthCookies`'s new "Callers" list omits its primary caller [`src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java:192-205`] — `terminateSession` ends with `clearAuthCookies(response)` (`:178`). An exhaustive-sounding list that omits it will mislead the next impact analysis: someone adding `clearContext()` here "because all listed callers want it" would silently change `terminateSession` too.
- [x] [Review][Patch] **DONE 2026-10-06** — reworded to past tense ("handled at the time") and explicitly notes `skp` is a seventh, added by this story's own AC2. A fifth stale "six cookies" passage was missed [`src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java:153-155`] — AC2 named four and all four were fixed; this one, eight lines above a javadoc this story rewrote for exactly this reason, still reads "the six cookies `LoginTokenManager#deleteLoginToken` handles". It is now seven.
- [x] [Review][Patch] **DONE 2026-10-06** — added a `decodeAsFrontendWould` helper (escapes literal `+` to `%2B` before `URLDecoder.decode`, so it never treats `+` as space, unlike form decoding) and switched the round-trip assertion to use it. The round-trip assertion for the encoder fix uses the wrong decoder [`src/test/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookieTest.java:32-33`] — `java.net.URLDecoder` is form decoding, the exact inverse of `URLEncoder` including `+` → space. Revert `.replace("+", "%20")` and this assertion still passes for any payload: it proves the Java↔Java round trip, not the Java↔`decodeURIComponent` one that the production comment at `SkillarsProfileCookie.java:25-28` says is the actual hazard. Coverage survives via `:43-54`, but the assertion labelled as the round-trip proof proves the wrong contract — which is how the original bug got in.
- [x] [Review][Patch] **DONE 2026-10-06** — documented (not code-changed, per the review's own "either document it or add clearContext()" options): added a comment at both `AuthService.refresh()` reuse-detection branches explaining the dropped `clearContext()` and why it's harmless here (unlike the filter's own context, `/api/auth/refresh`'s context is nilpotent past this throw, and `SecurityContextHolderFilter` clears the thread-local per request regardless). AC6.2 silently drops `SecurityContextHolder.clearContext()` and does not disclose it [`src/main/java/com/softropic/skillars/platform/security/service/AuthService.java:154`, `:161`] — `terminateSession` clears the context (`SecurityUtil.java:168`); `clearAuthCookies` does not (`:207-211`). This DOES set a real context: `/api/auth/refresh` is unrestricted (`AppEndpoints.java:44`, `:69-71`), and the filter's unrestricted branch calls `getAuthentication(req)`, which extracts the real JWT principal (`JWTAuthorizationFilter.java:228-239`) despite its "Put anonymous authentication token" comment. Impact today is nil — nothing audited is written after the throw and `SecurityContextHolderFilter` clears the thread-local per request — but AC6.6's own text cites this exact delta as a reason to reject a `clearAuthCookies` swap in the filter, so AC6.2 making the same swap unremarked is the story's own standard unmet. Either document it or add `clearContext()`.
- [x] [Review][Patch] **DONE 2026-10-06** — (a) added `[CLOSED by skillars-deferred-144 ACn ...]` tags to every bullet this story closes across the three cited sections of `deferred-work.md` (the two explicitly-excluded Finding 6 items and the AuthResourceIT "wider grep" companion-ask deliberately left open, as the story itself specifies); (b) `sprint-status.yaml` already carried an accurate in-progress/review note by the time this item was checked — no further change needed there; (c) the ~190-line unrelated `deferred-work.md` diff (already-`[CLOSED]`-tagged section deletions + two re-attributions) is pre-existing ledger hygiene from the same session, now disclosed in a new Dev Agent Record note rather than left undisclosed; (d) File List/Project Structure Notes corrected — the three ledger files added to File List, and the self-contradictory "`SecurityUtilTest.java` needs NO change" line corrected. Story bookkeeping is incomplete and carries undisclosed scope — (a) `grep -n "deferred-144" _bmad-output/implementation-artifacts/deferred-work.md` returns zero hits: every item this story closed is still listed open at `deferred-work.md:3471`, `:3572`, `:3649`, against the story's own stated goal that the ledger "stops growing"; (b) `sprint-status.yaml:2` still carries the pre-implementation-review note ending "Status unchanged at ready-for-dev" and records nothing about the implementation or AC8's mid-flight addition; (c) `git diff` on `deferred-work.md` is ~192 lines of edits unrelated to deferred-144 (deletion of the coach profile-builder manual-testing section, three already-CLOSED deferred-139 items, re-attribution of two deferred-141 items) with no AC, no Task, and no File List entry; (d) File List omits `deferred-work.md`, `sprint-status.yaml`, `story-review.md`, and Project Structure Notes omits the two new backend test files while still asserting "`SecurityUtilTest.java` needs NO change" that AC2 contradicts.
- [x] [Review][Patch] **DONE 2026-10-06** — corrected the AC7 bullet's wording (see AC7 above) and strengthened `testDoFilterInternal_ValidToken_UserAuthenticated` with an explicit `hasDbRefreshTokenExpired(request) = false` stub plus real assertions of the branch's unconditionality (`checkAuthorities`/`extendTtlOfToken` both ran, `authorize` never did). `JWTAuthorizationFilterTest`: 17/17 pass. AC7's AC8-regression clause describes coverage that does not exist — it cites "tests that stub `hasDbRefreshTokenExpired` as `false`", but all five stubs in `JWTAuthorizationFilterTest.java` (`:306`, `:330`, `:356`, `:413`, `:487`) are `true`. The one genuine fast-path test, `testDoFilterInternal_ValidToken_UserAuthenticated` (`:163-183`), never stubs it and relies on Mockito's default. The AC's substance holds; its stated guard is one implicit test, and nothing asserts the branch's new unconditionality.

**Deferred (pre-existing, not caused by this change)**

- [x] [Review][Defer] Non-canonical spellings of a real route bypass the profile-builder completeness gate [`src/frontend/src/router/index.js:89`] — deferred, pre-existing
- [x] [Review][Defer] Auth-only-but-role-unguarded routes are accepted as redirect targets for any role [`src/frontend/src/router/routes.js:337-345`] — deferred, pre-existing
- [x] [Review][Defer] Theft-driven `markAllUsedByUserId` has never terminated a live JWT session [`src/main/java/com/softropic/skillars/platform/security/service/AuthService.java:153`, `:160`] — deferred, pre-existing

**Dismissed as refuted (9)** — recorded so they are not re-raised: `deleteLoginToken` blast radius (exactly two callers, `SecurityUtil.java:208` + `JWTAuthorizationFilter.java:165`; no re-issue path); "no real-table spec for `safeRedirect.js`" and "routes.js coupling unasserted" (both withdrawn by the layer once the dropped `safeRedirectSpec.js` hunk was supplied — it imports the real `routes.js` and `:40` fails if `meta.notFound` is removed); `removeFrom` test missing path assertion (`CookieUtil.removeCookie` hard-codes `.path("/")` at `CookieUtil.java:48`, shared by every removal); `RefreshTokenRepositoryIT` fixture leakage (`AbstractIntegrationTest:79` registers `DatabaseResetTestExecutionListener`, which clears tables in `beforeTestMethod`); "four tests cover an unreachable shape" (`JWTAuthorizationFilterTest.java:402-421` covers the production-reachable `ACCOUNT_NOT_LOGIN_ABLE` disjunct, so breaking it does fail a test); `openMocks` AutoCloseable discarded (established module pattern — `SecurityUtilTest:57`, `JWTAuthorizationFilterTest:114`); "erased session never ends" as an unbounded hole (erasure locks the account, so the ≤5 min bound does apply); AC6.1's dead `AccountStatusException` disjunct (the story's own documented keep-decision).

**Verified sound** — worth recording because each was a specific risk in this change: the refactor preserved the load-bearing divergence between `JwtManagerImpl`'s `ANONYMOUS` fallback (`JwtManagerImpl.java:102-116`) and `AuthService`'s `"ADMIN"` fallback; `rtkn` correctly stayed out of `deleteLoginToken`; `SecurityUtil`'s `skp` line was delegated rather than deleted, as the pre-implementation review required; `.replace("+", "%20")` is correct and non-destructive (a literal `+` is already `%2B`); `isGenuineDenial`'s `Exception` → `RuntimeException` narrowing compiles (multi-catch LUB is `RuntimeException`); both filter construction sites were updated; and all 29 frontend tests pass, including the 5 new `safeRedirectSpec.js` cases.


---

## Dev Notes

- **Re-verify every line number in this file against the file's actual current state before using it** — Tasks 1, 3, 10, 11, 12, 13 all edit `AuthService.java`/`JwtManagerImpl.java`/`SecurityUtil.java`/`JWTAuthorizationFilter.java` sequentially within this same story; a citation that was accurate when this story was drafted can drift after an earlier task in the same implementation session. This exact failure bit `skillars-deferred-143` once already (see its own Dev Notes).
- **Do not touch** `AuthService.refresh()`'s liveness-check ordering, the `REQUIRES_NEW`/self-deadlock design, or anything else `skillars-deferred-143` just finished stabilizing, beyond the two narrow edits AC6.2 and AC6.3 specify. That story's own Dev Notes ("ordering in `refresh()` matters") still apply.
- **`JwtManagerImpl` must stay DB-free.** `SkillarsProfileCookie.writeTo`/`removeFrom` are pure cookie I/O (no repository access), so this constraint is preserved by construction — don't let a future edit to this type add one.
- **The `rtkn`/`skp` asymmetry is deliberate for `rtkn`, not for `skp`.** Do not add `rtkn` removal to `deleteLoginToken` — re-read Finding 2 and `skillars-deferred-143`'s own Dev Notes on why revoking `rtkn` on every idle-out defeats its purpose. This distinction is the crux of AC2; getting it backwards (e.g. "simplifying" by also revoking `rtkn` here) would reintroduce exactly the write-amplification `skillars-deferred-143` AC3 removed.
- **`quasar.config.js` sets `vueRouterMode: 'hash'`** (`:40` per the ledger's own citation, not independently re-verified at this depth — spot-check if the open-redirect reasoning in Finding 3/AC3 is load-bearing for your specific edit) — this is why a `/`-prefixed path can never become an open redirect regardless of what follows it; `isSafeRedirect`'s resolvability check is about avoiding a dead-ended 404, not closing a security hole that doesn't exist in hash mode.
- **`router.resolve(path).matched.length > 0` ALONE is wrong against this app's real route table — do not simplify `isSafeRedirect` back to it.** `routes.js`'s catch-all (`/:catchAll(.*)*`) matches every `/`-prefixed path, so `matched.length` is 1 for a typo'd path exactly as it is for a real one; verified by executing the installed vue-router (4.6.4) against this app's own route shape. The fix is the catch-all's `meta: { notFound: true }` tag plus excluding it in `isSafeRedirect` — see Design section C. If `routes.js`'s catch-all is ever restructured, re-verify this exclusion still works.
- **AC6.6 does NOT touch `JWTAuthorizationFilter` or `SecurityAlertThrottle` at all — this was a deliberate change from the original ledger-sourced design, made during drafting, not an oversight.** The ledger's own proposal (reuse `SecurityAlertThrottle` to gate the `ACCOUNT_NOT_LOGIN_ABLE` branch's revocation) was tried and rejected: `SecurityAlertThrottle` is documented as volume control, not a security decision, and its client-identifier key collapses to a shared IP for exactly the client this AC is meant to defend against — see AC6.6's own text for the full reasoning. If a future story reopens this, re-derive from scratch rather than resurrecting the throttle idea; the `markUsedByTokenHash` query-narrowing in Design D.6 is the adopted fix.
- **Do not fold in anything from `deferred-work.md` not explicitly listed in this story's Context/Design sections above.** In particular, leave `AuthService.refresh()`'s `findById` rejection and the `USER_NOT_FOUND`/`UNKNOWN` genuine-denial gap alone (explicitly excluded, Finding 6) — a future story's job, not this one's.

### Project Structure Notes

- New backend file: `src/main/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookie.java`. New backend test: `src/test/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookieTest.java` — the `contract` test package already exists (`SecurityPropertiesValidationTest.java` lives there today, confirmed by `find`), so this is a new file in an existing directory, not a new directory.
- Changed backend files: `AuthService.java`, `JwtManagerImpl.java`, `SecurityUtil.java`, `JWTAuthorizationFilter.java`, `RefreshTokenRepository.java`, `SecurityConfiguration.java`, `AuthResourceIT.java`, `JwtManagerImplTest.java`, `JWTAuthorizationFilterTest.java`, `SecurityUtilTest.java` — all already inside `platform.security.*`, consistent with `project-context.md`'s module-layering rules. No new module, no new layer. **Correction (Review Findings, 2026-10-06): `SecurityUtilTest.java` DOES need a change**, contrary to what this note originally said — AC2's own bullet list requires updating its `@DisplayName`/comment at `:81`/`:87-88` (stale "six cookies" wording), even though delegating `clearAuthCookies`'s `skp` removal to `SkillarsProfileCookie.removeFrom` (rather than deleting the line) keeps its ASSERTIONS passing for the right reason. Prose changes, not assertion changes — but a change nonetheless.
- New frontend file: `src/frontend/src/router/safeRedirect.js`, sibling to the existing `roleRoutes.js`. New frontend test: `src/frontend/src/router/__tests__/safeRedirectSpec.js` — confirmed no `__tests__` directory exists under `src/frontend/src/router/` today (that directory holds only `index.js`, `routes.js`, `roleRoutes.js`), so this task creates the directory, the first of its kind for this folder.
- Changed frontend files: `LoginPage.vue`, `OtpPage.vue`, `OtpPageSpec.js`, **`routes.js`** (the catch-all's new `meta: { notFound: true }` — easy to forget since it lives nowhere near `LoginPage.vue`/`OtpPage.vue`, but AC3 is incomplete without it; see Design section C / Finding 1). New frontend test file: `LoginPageSpec.js` — confirmed no such file exists today (only `OtpPageSpec.js` exists under `src/pages/auth/__tests__/`).
- No DB migration, no new REST endpoint, no DTO/response-shape change visible to any caller.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-142 (2026-10-05)"] — Findings 1, 3, 4 and part of the AC7 cookie-attribute-test ask.
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: manual review during skillars-deferred-143 (2026-10-05)"] — Findings 1 (umbrella), 2, 5.
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-143 (2026-10-05)"] — Finding 6 (all eight original bullets; two explicitly excluded, see Finding 6's own note).
- [Source: `skillars-deferred-143-account-lock-enforcement-and-forced-logout-session-termination.md`, Review Findings section, lines 230-237] — the six DEFER items Finding 6 closes, cross-checked against this file's own wording.
- [Source: `src/frontend/src/router/roleRoutes.js`] — the precedent this story's own `safeRedirect.js` (AC3) follows for "a duplicated routing concern is dangerous even when currently identical."
- [Source: `_bmad-output/project-context.md`, "Architecture & Module Design (DDD)", `:104` and `:115`] — rules OUT `infrastructure.security` for `SkillarsProfileCookie` (business-agnostic constraint); does NOT itself settle `contract` vs. `platform.security.infrastructure` — `:115`'s own `contract` definition argues against it. See Design section A's `[DECIDED]` note for the actual reasoning and the alternative considered.

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via the `bmad-dev-story` workflow.

### Debug Log References

None — no HALT conditions triggered. Two implementation detours worth recording as they are not
visible from the diff alone:

1. **AC2's new `SecurityIT`-style filter-level test was moved to `AuthResourceIT`.** The story's
   own Dev Notes suggested `SecurityIT`, but `SecurityIT`'s `/authenticate` login path
   (`JWTAuthenticationFilter` → `JwtManagerImpl.createLoginToken`) never sets `skp` at all — only
   `AuthService.login()`/`refresh()`/`refreshLoginToken` do (confirmed by reading
   `JwtManagerImpl.java:90-102`'s own comment and by the absence of any `skp` assertion on
   `SecurityIT`'s existing `/authenticate`-based tests). `AuthResourceIT`'s `/api/auth/login`
   (`AuthService.login()`) does set it and is already proven to in
   `login_validCoachCredentials_returns200WithRoleAndSetsTokenCookies`, so the new test lives there
   instead.
2. **The new test cannot offset `ClockProvider` around the login HTTP call.** `ClockProvider`
   (`infrastructure/util/ClockProvider.java`) is a `ThreadLocal`, and `@SpringBootTest(webEnvironment
   = RANDOM_PORT)` serves the login request on the embedded server's own worker thread — a clock
   offset set on the JUnit thread has no effect on the thread that actually mints the JWT. Worked
   around by minting an independent, already-expired JWT directly via the autowired `TokenCreator`
   bean on the test thread itself (where the `ThreadLocal` offset *does* apply), with a hand-built
   claims map rather than `TokenCreator.toClaims(...)` — that method reads
   `RequestMetadataProvider.getClientInfo()`, which is populated only inside a live HTTP request and
   throws `MissingClientIdException` when called from a bare test-thread context. The minted token
   is swapped in for the real login response's `potc` cookie value before the protected-resource
   request; every other cookie (`skp`, `rtkn`, ...) is the real one from the real login.

### Completion Notes List

- **AC1/AC2 (`SkillarsProfileCookie`):** New record in `platform.security.contract` now owns the
  `skp` wire format and both its operations (`writeTo`/`removeFrom`). All three former call sites
  (`AuthService.login()`, `AuthService.refresh()`, `JwtManagerImpl.setSkillarsProfileCookie`)
  delegate to it. The encoding fix (`URLEncoder`'s form-encoding `+` replaced with `%20`) lives
  only in `writeTo`, verified by `SkillarsProfileCookieTest`'s space-in-role-value case.
  `JwtManagerImpl.deleteLoginToken` gained a `SkillarsProfileCookie.removeFrom` call;
  `SecurityUtil.clearAuthCookies`'s existing explicit removal was switched to delegate (not
  deleted, per the story's own explicit instruction, to keep
  `SecurityUtilTest.terminateSession_clearsRefreshTokenAndProfileCookies` passing for the right
  reason). Unused `URLEncoder`/`StandardCharsets` imports dropped from `JwtManagerImpl.java`;
  `AuthService.java` keeps `StandardCharsets` (still used by `sha256Hex`).
- **AC2 — disclosed behavior change, in full:** `JWTAuthorizationFilter`'s routine-denial branch
  (`doFilterInternal`'s `else` arm, reached by every cause `isGenuineDenial` returns `false` for)
  now also expires `skp` on a routine 401, via `deleteLoginToken`'s new call. The complete set of
  causes that take this branch: `JWTExpiredException` (every 15-min idle-out),
  `MissingAuthenticationException` (every tokenless request to a secured URL), `AccessDeniedException`,
  and every `AuthorizationException` whose error code is NOT `ACCOUNT_NOT_LOGIN_ABLE` — concretely
  `MISSING_RIGHTS` (`DaoAuthProvider.checkAuthorities`), `USER_NOT_FOUND`, `UNKNOWN`, and
  `MISSING_USERNAME` (all three from `DaoAuthProvider.authorize()`/`determineUsername`).
  `JWT_PARSE_ERROR` is explicitly NOT in this list: it is thrown as `InvalidJWTDataException`,
  which extends `AuthorizationException` but is caught by `isGenuineDenial`'s own earlier,
  type-based `instanceof InvalidJWTDataException` disjunct regardless of error code, so it was
  already a genuine denial before this story and is unaffected by it. Proven at the filter level
  (not just `JwtManagerImplTest`'s unit-level cookie-list check) by the new
  `AuthResourceIT.routineDenial_expiredJwt_alsoClearsSkillarsProfileCookie`. The four comment/javadoc
  passages this made stale (`JWTAuthorizationFilter.java` ×2, `SecurityUtil.java`'s
  `clearAuthCookies` javadoc, `SecurityUtilTest.java`'s `@DisplayName`/comment) were reworded;
  none of the test's assertions changed.
- **AC3/AC4 (frontend open-redirect guard):** `routes.js`'s catch-all route tagged
  `meta: { notFound: true }`. New `src/router/safeRedirect.js` exports `isSafeRedirect(path, router)`
  — shape check plus a resolvability check that explicitly excludes a match that is only the tagged
  catch-all (`matched.length > 0` alone is a no-op against this route table, verified by the new
  `safeRedirectSpec.js`'s `/typo` case). `LoginPage.vue` and `OtpPage.vue` both migrated to it,
  deleting their inline duplicate. `OtpPage.vue`'s terminal redirect switched `router.push` →
  `router.replace` (AC4); `LoginPage.vue`'s own `router.push` is deliberately unchanged
  (Finding 4 — `/login` is a page a user can legitimately want to return to).
- **AC5 (`AuthResourceIT.refresh_expiredToken_returns401`):** Seed insert now routed through the
  class's own `commitWrite` helper (the bare `jdbcTemplate.update` was silently rolled back by
  `auto-commit=false`, so the row never existed for the server to find). The raw token value is
  now chosen first and its hash derived via `sha256Hex` (the previous hardcoded hex literal had no
  known preimage). Added the `usedFlagOf(...)` assertion that proves the expiry branch specifically
  was reached, not the not-found branch (both throw the same exception/status).
- **AC6 (six `skillars-deferred-143` review residuals):**
  - **AC6.1 — decision taken: KEEP** the `cause instanceof AccountStatusException` disjunct in
    `isGenuineDenial`, with a comment at the disjunct itself (not just the class javadoc) stating
    it is unreachable via `DaoAuthProvider.authorize()` today and is deliberate defense-in-depth.
    The four tests mocking `daoAuthProvider` to throw the raw type were left untouched (nothing to
    re-target since the disjunct wasn't removed).
  - AC6.2: `AuthService.refresh()`'s two reuse-detection branches now call
    `securityUtil.clearAuthCookies(res)` instead of `terminateSession(req, res)`. New
    `AuthServiceTest` (first unit test for this class) proves both branches call `clearAuthCookies`
    and never `terminateSession`. `SecurityUtil.java`'s `clearAuthCookies` javadoc reworded to name
    all current callers and their reasons, not just the original optimistic-lock-loser one.
  - AC6.3/AC6.4: comment-only — the optimistic-lock-loser comment now names a concurrent
    forced-logout as a second possible writer; `terminateSession`'s revocation call now has a
    one-line note on the conditional-to-unconditional tradeoff versus pre-deferred-143 `logout()`.
  - AC6.5: `isGenuineDenial`'s parameter narrowed from `Exception` to `RuntimeException` — compiles
    unchanged, all three exception types the one call site passes are `RuntimeException` subtypes
    (verified by reading `AuthorizationException`'s/`ApplicationException`'s own class
    declarations).
  - AC6.6: `RefreshTokenRepository.markUsedByTokenHash`'s `@Query` narrowed to also require
    `r.used = false`. `JWTAuthorizationFilter`/`SecurityAlertThrottle` untouched, per the story's
    explicit instruction not to resurrect the original per-client-throttle design. New
    `RefreshTokenRepositoryIT` (first test for this repository) proves a second call against an
    already-used row is a true no-op (`used` stays `true`, `version` does not increment again).
    The javadoc now discloses the version-bump-skip delta on an already-used row explicitly.
  - Confirmed neither of Finding 6's two explicitly-excluded items (transient-DB-failure handling;
    `USER_NOT_FOUND`/`UNKNOWN` not treated as genuine denials) was touched by any of the above.
- **AC7 (testing):** All new/updated tests listed in the File List below. Targeted backend suite
  (`AuthResourceIT`, `JWTAuthorizationFilterTest`, `JwtManagerImplTest`, `SecurityUtilTest`,
  `SecurityIT`, `SkillarsProfileCookieTest`, `AuthServiceTest`, `RefreshTokenRepositoryIT`): 91/91
  pass. Full `com.softropic.skillars.platform.security.**` package (regression sweep beyond the
  AC7 list): 392/392 pass. Full frontend Vitest suite: 222/222 pass across all 32 spec files, zero
  regressions. ESLint and Prettier clean on every touched `.vue`/`.js` file. No local `mvn verify`
  run, per standing project convention.
- **AC8 (Finding 7 — `JWTAuthorizationFilter` fast-path DB-query removal):** Removed
  `isRefreshTokenRevoked` and its branch from `attemptAuthorization`; the fast path now
  unconditionally runs `checkAuthorities` + `extendTtlOfToken`, with no DB access at all. Deleted
  the now-fully-unused `RefreshTokenRepository` field/constructor-param and the `CookieUtil`/
  `java.time.Instant`/`REFRESH_TOKEN_COOKIE` imports from the filter — none had any other use in
  the file, re-verified directly (not assumed from the earlier survey) before deleting. Cascaded
  to `SecurityConfiguration.filterChain(...)` (removed the matching parameter and constructor
  argument, and the now-unused `RefreshTokenRepository` import) and `JWTAuthorizationFilterTest`
  (removed the matching mock field and constructor argument — confirmed beforehand that no test
  method stubbed or verified anything on that mock, so this is a pure signature cleanup with zero
  test-behavior change). Reworded `JWTAuthorizationFilter`'s own "Sliding-window session
  keep-alive" class javadoc and `RefreshTokenRepository`'s class-level/method javadoc (both
  written earlier in this same story) to stop naming `JWTAuthorizationFilter`/`isRefreshTokenRevoked`
  as a caller. Added an inline comment at the collapsed fast-path branch recording the tradeoff:
  GDPR-erasure/theft-mass-revocation detection moves from "next request" to "within
  `DB_REFRESH_TOKEN_INTERVAL` (5 min)" — the same bound an admin-locked account already had, since
  `UserAdminService.lockUserAccount()` never touched `refresh_tokens` and so never had an
  early-detection shortcut either. `DaoAuthProvider.authorize(...)` and everything on the
  `hasDbRefreshTokenExpired(req) == true` branch are unchanged. Full targeted backend suite
  (91/91) and the full `com.softropic.skillars.platform.security.**` package (392/392) re-run
  clean after this change.

**Note on `deferred-work.md` scope (Review Findings, 2026-10-06):** that file's diff also carries
~190 lines unrelated to this story's own findings — deletion of the already-fully-`[CLOSED by ...]`-
tagged "manual testing of coach profile-builder" and "code review of skillars-deferred-139" sections
(per the file's own stated delete-outright convention for fully-closed sections), and re-attributing
two `deferred-141`-area items from an incorrect "still open" state to `[CLOSED by skillars-deferred-142
...]`. Pre-existing ledger hygiene from the same working session, not reverted and not expanded here —
disclosed explicitly per the review's ask, rather than left as undisclosed scope.

### File List

**New:**
- `src/main/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookie.java`
- `src/test/java/com/softropic/skillars/platform/security/contract/SkillarsProfileCookieTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/AuthServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepositoryIT.java`
- `src/frontend/src/router/safeRedirect.js`
- `src/frontend/src/router/__tests__/safeRedirectSpec.js`
- `src/frontend/src/pages/auth/__tests__/LoginPageSpec.js`

**Changed:**
- `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java`
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/config/SecurityConfiguration.java`
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/SecurityUtilTest.java`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/pages/auth/LoginPage.vue`
- `src/frontend/src/pages/auth/OtpPage.vue`
- `src/frontend/src/pages/auth/__tests__/OtpPageSpec.js`
- `_bmad-output/implementation-artifacts/deferred-work.md` (closure tags for the items this story
  closes, under "Deferred from: code review of skillars-deferred-142", "...code review of
  skillars-deferred-143", and "...manual review during skillars-deferred-143"; plus a new "Deferred
  from: code review of skillars-deferred-144" section for this story's own three pre-existing,
  out-of-scope findings)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status/note updates for this story)
- `_bmad-output/implementation-artifacts/story-review.md` (the `/bmad-code-review` run recorded
  there in addition to this file's own Review Findings section)

### Change Log

| Date | Change |
| :--- | :--- |
| 2026-10-06 | Implemented all 14 tasks (AC1-AC7). `SkillarsProfileCookie` consolidates the `skp` wire format and fixes the latent form-encoding mismatch; `deleteLoginToken` now also clears `skp`, closing the routine-401 gap (disclosed, with the complete affected-cause list). Frontend open-redirect guard de-duplicated into `safeRedirect.js` with a route-resolvability check; `OtpPage.vue` terminal redirect switched to `router.replace`. `AuthResourceIT.refresh_expiredToken_returns401` fixed to exercise the expiry branch it is named for. Six `skillars-deferred-143` review residuals closed (AC6.1 decision: keep the dead disjunct with a comment; AC6.2 reuse-detection branches switched to `clearAuthCookies`; AC6.3/6.4 comment-only; AC6.5 parameter narrowed to `RuntimeException`; AC6.6 `markUsedByTokenHash` query narrowed instead of adding a throttle). Two implementation detours from the story's own Dev Notes, recorded under Debug Log References: the new AC2 filter-level test moved from `SecurityIT` to `AuthResourceIT` (only `AuthService.login()` sets `skp`, not the plain `/authenticate` path), and built via a hand-minted JWT on the test thread rather than a `ClockProvider` offset around the login HTTP call (`ClockProvider` is a `ThreadLocal`, and the embedded server handles that call on its own worker thread). |
| 2026-10-06 | Added Finding 7/Design E/AC8/Task 15 (user-proposed during review of this same story, not sourced from `deferred-work.md`) and implemented it: removed `JWTAuthorizationFilter`'s per-request `isRefreshTokenRevoked` fast-path DB query. Deleted the now-fully-unused `RefreshTokenRepository` dependency and three now-dead imports from the filter; cascaded the constructor-signature change through `SecurityConfiguration.filterChain(...)` and `JWTAuthorizationFilterTest`; reworded the two javadoc blocks (`JWTAuthorizationFilter` class javadoc, `RefreshTokenRepository` class/method javadoc) that described the removed caller. GDPR-erasure/theft-mass-revocation detection latency changes from "next request" to "within `DB_REFRESH_TOKEN_INTERVAL` (5 min)" — the same bound an admin-locked account already had. Targeted backend suite (91/91) and full security-module suite (392/392) re-run clean. |
| 2026-10-06 | `/bmad-code-review` (four layers) ran against the above; all 13 Patch items and the three decision-needed items closed in this same session — see Review Findings above (each item now carries its own **DONE** note) and `sprint-status.yaml`'s `last_updated` note for the consolidated list. Highlights: `GdprErasureIT` amended to the owner-accepted ≤5 min bound per Decision 1b/Option A, reproduced failing pre-fix and passing post-fix by actually executing the IT (not inferred); `SkillarsProfileCookie` rebuilt on Jackson per Decision 3c with a quote-injection test pinning the escaping; two javadoc passages that overclaimed what the DB re-auth path actually checks were corrected; three test-strength gaps (`AuthResourceIT`, `AuthServiceTest`, `JWTAuthorizationFilterTest`) were closed with real assertions rather than implicit defaults; `safeRedirect.js`'s incidental-but-unpinned backslash/percent-encoded rejection was pinned and its header softened; and the story's own ledger bookkeeping (`deferred-work.md` closure tags, File List/Project Structure Notes corrections) was completed. Full re-verification by execution: backend security-module unit suite 394/394, `SecurityIT` 9/9, `RefreshTokenRepositoryIT` 1/1, `AuthResourceIT` 18/18, full `GdprErasureIT` 34/34, frontend Vitest 224/224, ESLint/Prettier clean. Status: in-progress → done. |
