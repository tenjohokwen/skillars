# Senior Dev Review — Story 1.7: Session Refresh Mechanism Fix

**Reviewed:** `_bmad-output/implementation-artifacts/1-7-session-refresh-mechanism-fix.md`
**Source doc also reviewed:** `SESSION_REFRESH_DRIFT_ANALYSIS.md`
**Date:** 2026-09-02
**Reviewer method:** every claim in the story was checked against the actual source on `frontend-session-mgt-fix`. Findings below are only those I could anchor to a file and line. Where I could not execute the system, I say so explicitly.

---

## Verdict

**Do not hand this story to a dev as written.** The documentation-drift half is accurate and useful. The behavioural half rests on a false model of how the backend session works, and **the central backend change it prescribes would remove the session warning dialog entirely.**

- 3 blocking issues (story as written causes a regression or is unimplementable)
- 4 major issues (real defects the story asserts already work, or misses)
- 6 minor issues (wrong paths, wrong constants, unverifiable ACs)
- 5 story claims verified correct — listed at the end so they aren't re-litigated

---

## BLOCKING

### B1. The prescribed `rint` change silently kills the session warning dialog

The story's headline AC is: make `rint` "reflect the actual time until JWT expiry (e.g., 900000 ms)". It does **not** ask for a matching change to the frontend formula. The frontend formula is:

```js
// src/frontend/src/plugins/sessionManager.js:57-60
const threshold = SESSION_TTL - rint;
if (threshold > 0 && threshold < SESSION_TTL) { warningThreshold.value = threshold; }
```

Work the arithmetic with `SESSION_TTL = 900000`:

| `rint` | `threshold = 900000 - rint` | Result |
|---|---|---|
| `600000` (today) | `300000` | Warning at 5 min. Works. |
| `900000` (story's AC) | `0` | Guard rejects → threshold stays at the **2-minute** default |
| `899998` (realistic, clock skew) | `2` | Guard accepts → **warning threshold = 2 ms → dialog never opens** |

Both outcomes are regressions, and the second is silent — no error, no log, the dialog simply stops appearing. This is the single most important finding in the review.

Compounding it, the story states a **third, different** formula in the AC text: `warningThreshold = rint - SAFE_MARGIN` (SAFE_MARGIN = 300000). With `rint = 900000` that yields a threshold of `600000`, i.e. the dialog opens when 10 minutes remain and stays open for two thirds of the session.

So the story contains three mutually incompatible formulas: the one in the code (`SESSION_TTL - rint`), the one in the drift analysis (same), and the one in the AC (`rint - SAFE_MARGIN`). A dev cannot implement this without guessing.

**Required:** decide the `rint` contract first, then specify *both* sides in the same AC. See the recommendation in §Recommended rewrite.

---

### B2. "`rint` decreases over time (900000 → 800000 → 700000)" is impossible — the session is sliding, not fixed

The AC asserts:

> **Then** the `rint` value decreases over time as the JWT approaches expiry (e.g., 900000 ms → 800000 ms → 700000 ms)

This cannot happen, because the backend re-issues the JWT with a **fresh full TTL on every authenticated request**:

- `JWTAuthorizationFilter.java:143` → `loginTokenManager.extendTtlOfToken(req, res)` on the normal path
- `JwtManagerImpl.java:134` → `createAndSetJwt(res, claims)`
- `TokenCreatorImpl.java:37` → `final Date exp = new Date(ClockProvider.getClock().millis() + JWT_TTL.toMillis());`

So `exp - now` measured at response time is **always ≈ 900000**, on request 1 and on request 50. A sequence of decreasing `rint` values is not achievable under a sliding-expiry design; the only way to produce one is to abandon sliding expiry, which is a far larger change than this story scopes and would degrade UX for active users.

The story's whole framing — "fixed value instead of a dynamic countdown" — misdiagnoses the design. `rint` is fixed because *the remaining time is genuinely constant at every response*. The countdown lives on the client, measured from the last request, which is exactly the right place for it given sliding expiry.

**Required:** drop this AC, or replace it with the absolute-timestamp contract (§Recommended rewrite), which *is* implementable and actually solves a real problem (B4).

---

### B3. The 401 path the story depends on does not fire

Three ACs and one task assume the axios interceptor clears cookies and redirects on 401 (Multi-Tab AC; "Verify `axios.js` interceptor handles 401 responses by clearing cookies"). The interceptor is gated on an error key:

```js
// src/frontend/src/boot/axios.js:140-141
const errorKey = data?.errorMsg?.errorKey || '';
if (status === 401) {
  if (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized') { ... }
```

But the expired-session 401 is emitted from inside a servlet filter, which never reaches `@ControllerAdvice`:

```java
// JWTAuthorizationFilter.java:93-97
catch (AccountStatusException | AuthorizationException | AccessDeniedException e) {
    securityUtil.logout(res);
    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```

`JWTExpiredException extends AuthorizationException`, so it is caught here rather than by `ApiAdvice.jwtExpirationHandler` (which is what produces `security.sessionExpired`). `res.sendError` triggers an ERROR dispatch handled by Spring Boot's default `BasicErrorController` — I confirmed there is no custom `ErrorController` / `ErrorAttributes` anywhere in `src/main`. The body is `{timestamp, status, error, path}`, with **no `errorMsg` object**.

Therefore `errorKey` is `''`, neither branch matches, and the interceptor does **not** clear cookies and does **not** redirect. The user's request just fails.

*Confidence:* confirmed by code reading of the full path; I did not run the app. A 5-minute manual check (expire a token, watch the network tab) or one integration test asserting the 401 body shape would settle it definitively — worth doing before the dev starts, because the fix location differs (fix the filter to emit an `ErrorDto`, vs. relax the frontend gate to `status === 401`).

---

## MAJOR

### M1. An idle background tab force-logs-out the active tab (missed flow)

The Multi-Tab AC only covers one direction: session expires → both tabs get 401. The opposite direction is a real, reproducible defect and is not mentioned anywhere in the story:

1. Tab A is in use; every request extends the shared backend JWT (`JWTAuthorizationFilter.java:143`).
2. Tab B is open but idle. `sessionManager` state is per-tab module state, and `lastActivityTime` is only updated by **that tab's own** axios requests (`axios.js:97-98`). Tab B never learns about Tab A's activity.
3. 15 minutes later Tab B's timer hits zero and dispatches `session:expired` (`sessionManager.js:129-133`).
4. `App.vue:24-37` handles it by calling `authStore.logout()`, which does `POST /api/auth/logout` (`auth.store.js:41-52`).
5. `AuthService.logout` marks the refresh token used and clears `potc`/`bcookie`/`user`/`admin`/`ION`/`rint`/`rtkn`/`skp` (`AuthService.java:206-222`). Cookies are per-origin, not per-tab.
6. **Tab A, which was actively in use and had a perfectly valid session, is now logged out** — and because of B3 it doesn't even redirect cleanly; it just starts failing.

This is the strongest argument for making `rint` an **absolute expiry timestamp**: it is a shared, cookie-borne value that every tab can read, so an idle tab can discover that a sibling tab extended the session. That is the version of "fix `rint`" that is worth doing.

### M2. The session-warning dialog's Logout button bounces the user back into the app

The story says "Verify 'Logout' button clears cookies and redirects properly". It does not. Compare the two logout paths:

```js
// src/frontend/src/composables/useSession.js:58-76  — used by SessionWarningDialog
stopSessionMonitoring();
await authApi.logout();      // POST /api/logout  (Spring's logout filter)
playerStore.resetSelfPlayerId();
cleanup();
router.push('/login');
```

```js
// src/frontend/src/layouts/MainLayout.vue:315-321  — the correct version
await authStore.logout();    // clears skp cookie + Pinia state, POST /api/auth/logout
playerStore.resetSelfPlayerId();
destroySession();
deleteUserCookie();
router.push('/login');
```

`useSession.handleLogout()` never clears the Pinia store and never clears `skp`. Spring's logout filter only deletes `JSESSIONID, potc, bcookie, user, admin` (`SecurityConfiguration.java:183-187`) — **not `skp`, not `rtkn`**. So after clicking Logout in the dialog:

- `authStore.userId` is still set → `authStore.isAuthenticated` is still `true`
- `router.push('/login')` hits the `requiresGuest` guard (`router/index.js:71-74`) → user is redirected straight back into the app
- `skp` (7-day TTL, see M3) and `rtkn` (7-day) survive, so the session is not really terminated

This is inside the story's stated scope but the story assumes it works.

### M3. The AC about `skp` vs `user` is backwards

> **And** the check `isAuthenticated()` uses the `skp` cookie (decrypted state from auth store), not a stale `user=` cookie

Two errors:

1. **`skp` is the stale one.** `skp` is issued with `REFRESH_TOKEN_TTL` = **7 days** and is only re-issued on login and `POST /api/auth/refresh` (`AuthService.java:124-125`, `201-202`). `user` is issued with `JWT_TTL` = **15 minutes** and is re-issued on *every* authenticated request (`JwtManagerImpl.java:216`). `user` tracks the live session; `skp` outlives it by up to a week. Switching the gate to `skp` makes the problem worse, not better.
2. **`skp` is not encrypted.** It is URL-encoded plain JSON: `{"id":<Long>,"role":"COACH"}` (`AuthService.java:122-124`, read at `auth.store.js:66-78`). "decrypted state" is wrong and would mislead an implementer.

There *is* a real bug adjacent to this — the 7-day `skp` combined with a 15-minute session produces a broken state: reload the tab 20 minutes after the last request and `user` is gone (monitoring never starts, `App.vue:20-22`) but `skp` is still there, so `hydrateFromCookie` reports authenticated, the router lets you into a protected page, and the first API call 401s into the dead-end from B3. Worth an AC. But the AC must be "`skp` lifetime must not exceed the access-session lifetime, or must be cleared on 401", not "prefer `skp` over `user`".

### M4. Changing `rint` breaks two existing backend tests, which the story doesn't mention

`JwtManagerImplTest` asserts the exact current value twice:

```java
// src/test/java/.../jwt/JwtManagerImplTest.java:291-292 and 504-505
final String sessionTimeoutWarningInterval = extractCookie(mockResponse, SESSION_REFRESH_COUNTDOWN);
assertThat(sessionTimeoutWarningInterval).isEqualTo(String.valueOf(JWT_TTL.minusMinutes(5).toMillis()));
```

Any change to the `rint` value fails the build. The story's task list says "add tests" but never "update the two existing assertions". Add it explicitly so CI failure isn't the first anyone hears of it.

---

## MINOR / ACCURACY

### N1. Every backend file path in the story is wrong

| Story says | Actual |
|---|---|
| `com/skillars/platform/security/service/JwtManagerImpl.java` | `com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` |
| `.../security/filter/JWTAuthorizationFilter.java` | `.../security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` |
| `.../security/resource/SessionRefreshFilter.java` | `.../security/infrastructure/filter/SessionRefreshFilter.java` |
| `.../security/resource/AuthResource.java` | `.../security/api/AuthResource.java` |
| `platform.security.SecurityConstants` | `com.softropic.skillars.infrastructure.security.SecurityConstants` |

The root package `com.skillars` does not exist. A story whose stated purpose is fixing docs that "reference non-existent code paths" should not itself reference non-existent code paths.

### N2. The stated frontend fallback constant is wrong

The AC says invalid `rint` "falls back to the hardcoded 5-minute default". The actual default is **2 minutes**:

```js
// sessionManager.js:4
const DEFAULT_WARNING_THRESHOLD = 2 * 60 * 1000;
```

Also, the code does not "fall back" at all — the guard at `sessionManager.js:58` simply skips the assignment, so an invalid `rint` leaves whatever value was synced previously. There is no reset-to-default path. If a reset is wanted, that is new behaviour and needs its own AC.

### N3. Two `rint` validations the story asks for already exist

`readRintCookie` (`sessionManager.js:43-48`) already rejects `NaN`, `Infinity`, and `<= 0` via `Number.isFinite(ms) && ms > 0`, and `syncWarningThresholdFromCookie` already bounds the result to `(0, SESSION_TTL)`. The task "Add validation: if `rint` is invalid (negative, > SESSION_TTL, or NaN)" is largely already done. Keep the debug-logging sub-task, drop the rest, and re-scope to whatever the new `rint` contract needs.

### N4. The `beforeunload` cross-tab task is the wrong mechanism

> Add `beforeunload` listener to notify other tabs if session is cleared (localStorage-based cross-tab communication)

`beforeunload` fires when a tab is **closed or navigated away**, which has nothing to do with the session being cleared. Implemented literally, closing one tab would broadcast a session-cleared message and log out every other tab. The problem that actually needs cross-tab communication is M1 (sharing *activity*, not clearing), and the right mechanisms are `BroadcastChannel` / a `storage` event listener / a shared cookie carrying absolute expiry — not `beforeunload`.

### N5. Several ACs are not testable as written

- "Session warning appears **exactly** 5 minutes before expiry" — the check loop runs every 30 s (`SESSION_CHECK_INTERVAL`, `sessionManager.js:5`), so the warning can be up to 30 s late by design. Either state a tolerance (`5 min ± 30 s`) or change the interval.
- "if the dialog is not dismissed within 5 minutes, the user is automatically logged out" — there is no separate 5-minute dialog timer. Expiry fires when `timeUntilExpiry <= 0` (`sessionManager.js:129`), i.e. the dialog's visible lifetime *equals* the warning threshold. If the threshold changes, this "5 minutes" changes with it. The AC conflates two independent numbers.
- The redirect half of that AC (`/login?redirect=<path>`) is **already implemented** at `App.vue:32-36`, including an `expired=true` flag. Mark it as verify-only, not build.

### N6. Two factual errors in Dev Notes

- "All session cookies are `HttpOnly; Secure; SameSite=Lax`" — false. `user`, `admin`, `rint`, and `skp` are all set with `httpOnly = false` (`JwtManagerImpl.java:216`, `223`, `228`; `AuthService.java:125`). And `Secure` is conditional: `.secure(RequestMetadataProvider.getClientInfo().isHttps())` (`CookieUtil.java:22`), so it is off over plain HTTP.
- The story asks to change the `rint` TTL from browser-session to 900 s without noting the trade-off: with `maxAge=900` the cookie *disappears* once the user is idle past the TTL, so `readRintCookie()` returns `null` and the frontend loses its only server-side timing signal at exactly the moment it needs it. Under an absolute-timestamp contract this is the wrong direction — you want the cookie to outlive the JWT slightly so the client can read "expired at T" and act on it.

---

## MISSED CONTEXT (not defects, but the dev will hit these)

- **There is no frontend test runner.** `src/frontend/package.json:12` is `"test": "echo \"No test specified\" && exit 0"`, and there are zero `*.spec.js` / `*.test.js` files under `src/frontend`. The story specifies `sessionManager.test.js`, `sessionMonitoring.spec.js`, `sessionWarning.spec.js`, plus E2E — that means standing up Vitest + Vue Test Utils (+ Playwright) from scratch. That is a separate story's worth of work, unbudgeted here.
- **"Activity" means API activity, not user activity.** `recordActivity()` has exactly two callers: the axios request interceptor (`axios.js:98`) and `refreshSession()` (`sessionManager.js:160`). Nothing listens to mouse/keyboard events — the `useActivityTracking` composable in the docs was never built. This is *consistent* with the backend (which also only extends on requests), so it is not a bug, but the doc rewrite must say so plainly or the next reader will file the same drift report.
- **`SecurityConstants.java:60`** still carries the comment `//Not used at the moment but Clients should actually use this...` on `SESSION_REFRESH_COUNTDOWN`. It *is* used. Fold this into the doc-comment task.
- **`config.url?.includes('/refresh')`** (`axios.js:97`) matches both `/refresh` and `/api/auth/refresh`. Harmless today, but it is a substring match on a user-influenced path and should be an exact comparison.
- **`document.cookie.includes('user=')`** (`App.vue:21`) is a substring test — it would also match a cookie named `xuser` or any cookie whose *value* contains `user=`. Low risk, trivial fix, worth folding in since the story touches this line anyway.

---

## STORY CLAIMS I VERIFIED AS CORRECT

So these don't get re-argued:

1. **`rint` is a fixed value.** `String.valueOf(JWT_TTL.minusMinutes(5).toMillis())` = `600000`, always (`JwtManagerImpl.java:225-229`). Confirmed.
2. **`rint` has `maxAge = -1`.** Confirmed (`JwtManagerImpl.java:210`, `229`).
3. **The 5-minute warning works by coincidence.** `900000 - 600000 = 300000`. The backend happens to encode the exact warning window the frontend expects. Confirmed — this is a genuine latent fragility.
4. **The documentation is fiction.** `docs/session-refresh-mechanism.md` references `stores/session.js` / `useSessionStore` (lines 89, 95, 282, 340, 471), `composables/useActivityTracking.js` (line 393), and `components/SessionWarningDialog.vue` at the wrong path (line 473). None of the first two exist. The doc-rewrite ACs are the strongest part of this story and should be kept as-is.
5. **`rint` *is* already refreshed on every authenticated request.** Via `extendTtlOfToken` → `createAndSetJwt` → `createLoginCookies` (`JwtManagerImpl.java:133-134`, `203-207`, `209`). The task "Verify `JWTAuthorizationFilter` refreshes `rint`" is verify-only; **no change to `JWTAuthorizationFilter` is needed** for this, contrary to the Key Files list.

The `/refresh` vs `/api/auth/refresh` documentation AC is also sound and low-risk: `SessionRefreshFilter` is registered after `JWTAuthorizationFilter` (`SecurityConfiguration.java:211`), `/refresh` is a secured endpoint (`AppEndpoints.java:57`), so the TTL extension happens in the filter and `SessionRefreshFilter` only stamps a 200. Documenting that is worth doing.

---

## RECOMMENDED REWRITE

Split the story. The two halves have completely different risk profiles.

**Story 1.7a — Documentation & comments (low risk, ship it).** Keep the doc ACs and the `/refresh` vs `/api/auth/refresh` ACs verbatim. Fix the package paths (N1). Add: document that "activity" means API activity, and that expiry is sliding.

**Story 1.7b — `rint` contract (needs a design decision first).** Replace the "dynamic countdown" framing with an absolute timestamp, which is implementable under sliding expiry *and* fixes M1:

```
rint = epoch milliseconds at which the current JWT expires   (e.g. 1772668800000)
       set on every authenticated response, alongside the JWT
       maxAge = JWT_TTL + small grace, so the client can still read "expired at T"
```

Frontend, specified in the same AC:

```js
const expiresAt = readRintCookie();                        // absolute epoch ms
timeUntilExpiry.value = expiresAt - Date.now();            // replaces SESSION_TTL - elapsed
// warningThreshold stays a client-side constant (5 min) — it is a UX choice, not a server fact
```

This gives you, for free:
- an idle tab reading a sibling tab's extension → **M1 fixed**
- no dependence on a hardcoded frontend `SESSION_TTL` → the "Backend TTL Sync" optional task becomes unnecessary, delete it
- a client that survives laptop sleep and clock drift between requests

Then add ACs for the defects the current story assumes away: **B3** (make the filter's 401 carry an `ErrorDto`, or loosen the frontend gate to bare `status === 401`), **M2** (make `useSession.handleLogout` match `MainLayout.handleLogout`), **M3** (cap `skp`'s lifetime, or clear it on 401), and **M4** (update the two `JwtManagerImplTest` assertions).

**Before either story starts,** run the one manual check that de-risks the most: log in, let the JWT expire, fire one API call, and look at the 401 response body. That single observation confirms or kills B3, and B3 determines where three other ACs point.
