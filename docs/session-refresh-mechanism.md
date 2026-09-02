# Session Refresh Mechanism for Frontend Integration

This document explains how session keep-alive, expiry detection and the session-timeout
warning actually work in this codebase (Vue 3 + Quasar 2 frontend, Spring Security backend).

> **Accuracy note (Story 1.7a).** An earlier version of this document described an
> idealised design that was never fully implemented — it referenced a `stores/session.js`
> Pinia store, a `useSessionStore()`, a `composables/useActivityTracking.js` and a
> `rint` cookie that counts down. **None of those exist.** This version describes the
> code as it is on `master`. Known rough edges are called out in
> [Known limitations](#known-limitations); their fixes are tracked in Story 1.7b.

## Overview

Authentication is JWT-based. The JWT lives in an **HttpOnly cookie** (`potc`) and is
**re-issued with a fresh full 15-minute TTL on every authenticated request** — a
"sliding window", not a countdown from login. A short-lived DB re-check runs on top of
that so revoked/locked accounts are caught within ~5 minutes.

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Session Lifecycle                             │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Login ──► JWT issued (15 min TTL) + refresh-token cookie (7 days)    │
│                              │                                        │
│                              ▼                                        │
│              Every authenticated request passes through              │
│              JWTAuthorizationFilter, which:                          │
│                • re-issues the JWT with a fresh 15 min TTL           │
│                  (potc, user, rint cookies rewritten)                │
│                • every ~5 min, re-authorises against the DB          │
│                  (catches locked / deactivated / force-logged-out)   │
│                              │                                        │
│                              ▼                                        │
│           No authenticated request for 15 min?                       │
│                    │                    │                             │
│                   Yes                  No ──► session stays alive     │
│                    │                                                  │
│                    ▼                                                  │
│        JWT in potc cookie expires; next request → 401                │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

Two consequences that trip people up:

- **"Activity" means API requests only.** There is no mouse/keyboard/scroll tracking.
  The Axios request interceptor calls `recordActivity()`; nothing else does. An idle
  tab that makes no HTTP calls will show the warning and then fire `session:expired`
  even though the user is looking at it.
- **The warning threshold is computed on the client.** The server does not tell the
  client "you have N seconds left". The client counts elapsed time since the last API
  request and compares against a threshold it derives locally (see
  [The `rint` cookie](#the-rint-cookie)).

## Key timeouts

| Constant | Value | Where | Meaning |
|---|---|---|---|
| `JWT_TTL` | 15 min | `SecurityConstants` | Access-token (`potc` cookie) lifetime; reset on every authed request |
| `DB_REFRESH_TOKEN_INTERVAL` | 5 min | `SecurityConstants` | How often `JWTAuthorizationFilter` does a full DB re-authorisation (`daoAuthProvider.authorize`) instead of a TTL-only bump. The TTL-only path is **not** DB-free: it still runs one `refresh_tokens` revocation lookup whenever an `rtkn` cookie is present. |
| `REFRESH_TOKEN_TTL` | 7 days | `SecurityConstants` | `rtkn` and `skp` cookie lifetime |
| `SESSION_TTL` | 15 min (900000 ms) | `plugins/sessionManager.js` | Frontend's copy of `JWT_TTL` — **hardcoded**, must be kept in sync manually |
| `DEFAULT_WARNING_THRESHOLD` | 2 min (120000 ms) | `plugins/sessionManager.js` | Warning window used **only until** a `rint` cookie has been seen |
| `rint` cookie value | 10 min (600000 ms) | `JwtManagerImpl` | Fixed; frontend derives its real warning window from it (see below) |
| `SESSION_CHECK_INTERVAL` | 30 s | `plugins/sessionManager.js` | How often the monitor recomputes time-until-expiry |
| `COUNTDOWN_INTERVAL` | 1 s | `plugins/sessionManager.js` | Per-second countdown updates while the warning dialog is visible |

## The two "refresh" endpoints

There are two distinct endpoints and it matters which you call.

### `GET /refresh` — keep an active session alive

- Handled by
  `com.softropic.skillars.platform.security.infrastructure.filter.SessionRefreshFilter`.
- It is a **secured, no-op endpoint**. Because `/refresh` is registered in
  `AppEndpoints.SECURED_MAPPINGS`, the request first flows through
  `JWTAuthorizationFilter`, which extends the JWT TTL and rewrites the `potc` / `user`
  / `rint` cookies. Only then does `SessionRefreshFilter` run — and all it does is
  `response.setStatus(200)` with an empty body. It does not chain the request any
  further.
- Filter ordering is wired in `SecurityConfiguration#filterChain`
  (`.addFilterAfter(new SessionRefreshFilter(AppEndpoints.REFRESH), JWTAuthorizationFilter.class)`).
- It does **not** rotate the refresh token (`rtkn`) or `skp`.
- The matcher (`PathPatternRequestMatcher`) binds no HTTP verb, so any method to
  `/refresh` short-circuits here; the frontend uses `GET`.
- Frontend call: `sessionApi.refresh()` in `src/api/session.api.js` → `api.get('/refresh')`.
- **Dev only:** `/refresh` needs a proxy entry in `src/frontend/quasar.config.js`
  (added alongside `/api`, `/authenticate`, …). Without it, `quasar dev` answers
  `GET /refresh` with the SPA fallback (`200` + `index.html`), `refreshSession()` sees a
  resolved promise and clears the warning, but the JWT is never extended. Production is
  unaffected (Spring serves the SPA).

Use this to keep a session alive without doing other work — e.g. the "Continue session"
button in the warning dialog.

### `POST /api/auth/refresh` — full token rotation

- Handled by `com.softropic.skillars.platform.security.api.AuthResource#refresh` →
  `AuthService#refresh`.
- The client presents its `rtkn` refresh-token cookie; the backend validates it,
  marks it used, issues a **new access token + new refresh token pair**, and rewrites
  `rtkn` and `skp`. Includes multi-tab race handling (a short grace window) and
  token-reuse detection (revoke-all).
- Response body is a `LoginResponse` (`{ userId, role, displayName }`).
- Frontend call: `authApi.skillarsRefresh()` in `src/api/auth.api.js` →
  `api.post('/api/auth/refresh')`.

Use this for login flows and explicit re-authentication, not for routine keep-alive.

## Session cookies

Set by `JwtManagerImpl` (on login / TTL extension / DB re-auth) and by `AuthService`
(refresh-token and profile cookies). Attributes below are what the code actually sets.

**Applies to every cookie in this table:** `CookieUtil` writes them all with
`Path=/`, `SameSite=Lax`, and `Secure` — where `Secure` is emitted only when the
current request arrived over HTTPS (`RequestMetadataProvider.getClientInfo().isHttps()`),
so over plain HTTP in local dev the cookies are **not** `Secure`. The per-row column
below is `HttpOnly` and lifetime only.

"Browser-session" lifetime = `Max-Age=-1`, i.e. a session cookie with no `Expires`/`Max-Age`
attribute. It is **not** per-tab: it is shared across all tabs/windows of the browser and
can survive a browser restart when session-restore is enabled; it is dropped only when the
browser session genuinely ends.

| Cookie | Constant | HttpOnly | Lifetime | Contents / purpose |
|---|---|---|---|---|
| `potc` | `JWT_COOKIE_NAME` | **yes** | 15 min (`JWT_TTL`) | The JWT itself. Not readable from JS. |
| `user` | `USER_COOKIE` | no | 15 min | The user's **display name** (plain string — *not* the JWT, *not* JSON). Used only as a cheap "is a session present" hint on the client. |
| `admin` | `ADMIN_COOKIE` | no | 15 min | Literal `"admin"`, set only when the roles claim contains `ADMIN`. |
| `rint` | `SESSION_REFRESH_COUNTDOWN` | no | browser-session | Fixed value `600000` (ms). See [below](#the-rint-cookie). |
| `rtkn` | `REFRESH_TOKEN_COOKIE` | **yes** | 7 days (`REFRESH_TOKEN_TTL`) | Opaque refresh token. Issued only by `POST /api/auth/login` and `POST /api/auth/refresh`. |
| `skp` | `SKILLARS_PROFILE_COOKIE` | no | 7 days | URL-encoded JSON `{"id":<Long>,"role":"COACH"}`. Read by `authStore.hydrateFromCookie()` to restore auth state on page load. |
| `bcookie` | `B_COOKIE` | **yes** | browser-session | Client id. |
| `ION` | `JWT_SESSION_COOKIE` | **yes** | browser-session | Session id. |
| `fcookie` | `F_COOKIE` | no | 1 year | Browser fingerprint for fraud checks. Set **client-side** in `src/boot/axios.js` (`setBrowserFingerprint()`), not by the backend (so its flags are whatever that code sets, not `CookieUtil`'s). |

Because `potc` is HttpOnly, the client cannot inspect the real token or its expiry.
That is why the frontend relies on `user` (presence) and `skp` (identity), and tracks
the countdown itself.

## The `rint` cookie

`JwtManagerImpl.createLoginCookies()` writes `rint` on every authenticated request:

```java
// FIXED value: JWT_TTL - 5 min = 10 min = 600000 ms. Not a countdown.
CookieUtil.addCookie(res,
        SESSION_REFRESH_COUNTDOWN,
        String.valueOf(JWT_TTL.minusMinutes(5).toMillis()),
        false,               // HttpOnly = false → JS can read it
        browserSessionTtl);  // -1 → session cookie: shared across tabs, survives tab close,
                             //      cleared only when the browser session ends
```

The value never changes during a session — under the sliding window the JWT is always
re-issued with a fresh full TTL, so `JWT_TTL - 5min` is a constant.

The frontend (`plugins/sessionManager.js → syncWarningThresholdFromCookie()`) reads it
and derives the warning window:

```js
const threshold = SESSION_TTL - rint;      // 900000 - 600000 = 300000 ms = 5 minutes
if (threshold > 0 && threshold < SESSION_TTL) {
  warningThreshold.value = threshold;       // warn 5 minutes before expiry
}
```

So the effective behaviour is: **warn 5 minutes before expiry** — but only because the
backend happens to hardcode `JWT_TTL - 5min` and the frontend happens to hardcode
`SESSION_TTL = JWT_TTL`. If either constant changes without the other, the warning
window silently drifts. Until the first `rint` cookie is observed, the frontend falls
back to `DEFAULT_WARNING_THRESHOLD` (2 minutes).

Redesigning `rint` into an absolute expiry timestamp (so the client no longer needs a
hardcoded `SESSION_TTL`) is tracked in **Story 1.7b**.

## Frontend implementation

### Modules (actual paths)

| File | Role |
|---|---|
| `src/plugins/sessionManager.js` | Standalone module holding ref-based session state and the monitor timers. **Not a Pinia store.** |
| `src/composables/useSession.js` | Thin composable wrapper: re-exports the refs as `computed`, plus `handleRefresh` / `handleLogout` / `initSession` / `destroySession`. |
| `src/components/common/SessionWarningDialog.vue` | The warning dialog. Rendered once, in `App.vue`. |
| `src/boot/axios.js` | Request interceptor calls `recordActivity()`; response interceptor calls `syncWarningThresholdFromCookie()` and handles 401. |
| `src/App.vue` | Starts session monitoring on mount if a `user` cookie is present; listens for the `session:expired` event. |
| `src/stores/auth.store.js` | Pinia store for identity (`userId`, `role`, `displayName`); `hydrateFromCookie()` reads `skp`. |
| `src/api/session.api.js` | `sessionApi.refresh()` → `GET /refresh`. |
| `src/api/auth.api.js` | `authApi.skillarsRefresh()` → `POST /api/auth/refresh`. Note there are **two** logout helpers: `authApi.logout()` → `POST /api/logout` (the one `useSession.handleLogout()` actually calls) and `authApi.skillarsLogout()` → `POST /api/auth/logout`. |

### `plugins/sessionManager.js` (the monitor)

Key exported functions:

- `recordActivity()` — sets `lastActivityTime = Date.now()`. Called by the Axios
  request interceptor for every request **except** `GET /refresh`.
- `startSessionMonitoring()` — clears old timers, records activity, syncs the threshold
  from any existing `rint` cookie, then starts a `setInterval` (every
  `SESSION_CHECK_INTERVAL` = 30 s) that:
  - recomputes `timeUntilExpiry = SESSION_TTL - (Date.now() - lastActivityTime)`;
  - sets `showWarning` when `0 < timeUntilExpiry < warningThreshold`;
  - starts a 1-second countdown timer while the warning is visible;
  - dispatches `window` event `session:expired` and calls `cleanup()` when
    `timeUntilExpiry <= 0`.
- `syncWarningThresholdFromCookie()` — see [The `rint` cookie](#the-rint-cookie). Called
  by the Axios response interceptor after every response (success and error).
- `refreshSession()` — dynamically imports `src/api/session.api` (to avoid the
  `axios.js → sessionManager.js → session.api.js → axios.js` import cycle), calls
  `sessionApi.refresh()`, then `recordActivity()` and clears the warning.
- `stopSessionMonitoring()` / `cleanup()` — tear down timers and reset state.

Exported refs (consume via `useSession()`): `showWarning`, `timeUntilExpiry`,
`secondsRemaining`, `minutesRemaining`, `isRefreshing`, `warningThresholdSeconds`.

### `composables/useSession.js`

```js
import { useSession } from 'src/composables/useSession';

const {
  showWarning, secondsRemaining, minutesRemaining, isRefreshing,
  warningThresholdSeconds, handleRefresh, handleLogout,
  initSession, destroySession,
} = useSession();
```

- `handleRefresh()` → `refreshSession()` from the plugin.
- `handleLogout()` → `stopSessionMonitoring()`, `authApi.logout()` (errors ignored),
  `playerStore.resetSelfPlayerId()`, `cleanup()`, then `router.push('/login')`.
  > **Known gap:** this path does not clear the Pinia `authStore` / `skp` cookie, so
  > the router's `requiresGuest` guard can bounce the user back in. Tracked in 1.7b.
- `initSession()` → `startSessionMonitoring()`.
- `destroySession()` → `stopSessionMonitoring()` + `cleanup()`.

### `components/common/SessionWarningDialog.vue`

```vue
<script setup>
import { ref, watch, computed } from 'vue';
import { useSession } from 'src/composables/useSession';

const {
  showWarning, secondsRemaining, minutesRemaining,
  isRefreshing, warningThresholdSeconds, handleRefresh, handleLogout,
} = useSession();

const dialogVisible = ref(showWarning.value);
watch(showWarning, (v) => { dialogVisible.value = v; });

const formattedCountdown = computed(() => {
  const s = secondsRemaining.value;
  return `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, '0')}`;
});
const progressValue = computed(() => {
  const max = warningThresholdSeconds.value || 1;
  return Math.max(0, Math.min(1, secondsRemaining.value / max));
});
</script>
```

The dialog is `persistent`, shows an `MM:SS` countdown, and offers **Logout** and
**Continue session** (calls `handleRefresh`). All user-facing strings go through
`vue-i18n` (`session.*`, `auth.logout`).

### `boot/axios.js` (interceptors)

```js
// Request: count everything as activity except the keep-alive call itself.
const requestPath = (config.url || '').split('?')[0];
if (requestPath !== '/refresh') {
  recordActivity();
}

// Response (success, and non-401 errors): the auth filter extended the JWT and rewrote
// `rint` before we got here, so re-sync the threshold. On a 401/expired response the
// filter clears cookies instead of refreshing them, and the block below runs.
syncWarningThresholdFromCookie();

// Error, status 401:
const errorKey = data?.errorMsg?.errorKey || '';
if (status === 401 && (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized')) {
  stopSessionMonitoring();
  cleanup();
  // clear `user` and `skp`, then hard-redirect to /login?...&expired=true
}
```

> **Known gap:** `JWTAuthorizationFilter` emits a bare `401` (`res.sendError(...)`)
> with no `ErrorDto` body, so `errorKey` is `''` and the block above does not fire on a
> genuinely expired token — the request just fails. Tracked as 1.7b B3.

### `App.vue`

```js
function isAuthenticated() {
  // Exact cookie-name match (a bare includes('user=') would also match `xuser=...`).
  return document.cookie.split(';').some((c) => c.trim().startsWith('user='));
}

onMounted(() => {
  if (isAuthenticated()) startSessionMonitoring();
  window.addEventListener('session:expired', handleSessionExpired);
});
```

`handleSessionExpired()` clears the `user` cookie, calls `authStore.logout()`
(best-effort), `playerStore.resetSelfPlayerId()`, `cleanup()`, then routes to
`/login?redirect=...&expired=true`.

### Route guard (`router/index.js`)

Auth state for guards comes from the Pinia `authStore`, hydrated once from the `skp`
cookie:

```js
if (!hydrated) { authStore.hydrateFromCookie(); hydrated = true; }
const isAuthenticated = authStore.isAuthenticated;   // !!userId, from skp

if (requiresAuth  && !isAuthenticated) next({ path: '/login', query: { redirect: to.fullPath } });
if (requiresGuest &&  isAuthenticated) next(ROLE_ROUTES[authStore.role] || '/dashboard');
```

Note `skp` has a 7-day TTL while `potc` is 15 minutes: the guard can consider a user
"authenticated" (valid `skp`) after the actual JWT session has expired. The first API
call then returns 401 — but see the **Known gap** above: because `JWTAuthorizationFilter`
sends a bodyless 401, the interceptor's `errorKey` gate does not fire, so today that
request simply fails rather than cleanly redirecting to `/login`. The
elapsed-time monitor's `session:expired` event is the path that does reliably fire
(after 15 min of no successful API calls). Both are tightened in 1.7b.

## Backend components

| File (`com.softropic.skillars…`) | Role |
|---|---|
| `platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter` | On every authed request: re-issues the JWT with a fresh 15 min TTL and rewrites `potc` / `user` / `rint`. Fast path = `checkAuthorities` + `extendTtlOfToken` (plus a `refresh_tokens` revocation lookup when `rtkn` is present); every ~5 min (or on revocation) it instead runs a full `daoAuthProvider.authorize` DB re-auth. This is where "sliding window" happens. |
| `platform.security.infrastructure.filter.SessionRefreshFilter` | Terminal handler for `/refresh` (matcher binds no HTTP verb — any method short-circuits here; frontend uses `GET`); returns an empty `200`. Does not refresh anything itself. |
| `platform.security.api.AuthResource` | `POST /api/auth/login` \| `/refresh` \| `/logout`. `/refresh` here = full token rotation, returns `LoginResponse`. |
| `platform.security.service.AuthService` | Refresh-token persistence/rotation, `skp` cookie, reuse detection, multi-tab grace window. |
| `platform.security.infrastructure.jwt.JwtManagerImpl` | Builds and sets all JWT-related cookies (`createLoginCookies`), including the fixed-value `rint`. |
| `platform.security.config.SecurityConfiguration` | Filter-chain wiring; `SessionRefreshFilter` is added right after `JWTAuthorizationFilter`. |
| `infrastructure.security.SecurityConstants` | `JWT_TTL`, `DB_REFRESH_TOKEN_INTERVAL`, `REFRESH_TOKEN_TTL`, all cookie-name constants. |

## Sequence diagrams

### Keep-alive from the warning dialog

```
User                Frontend                                 Backend
 │                     │                                        │
 │  (~10 min idle)     │                                        │
 │                     │ monitor: timeUntilExpiry < 5 min       │
 │                     │ (i.e. ≥10 min since last API call,      │
 │                     │  under the 15 min TTL; ±30s check tick) │
 │◄── warning dialog ──│                                        │
 │                     │                                        │
 │── Continue ────────►│ sessionApi.refresh()                   │
 │                     │──── GET /refresh (potc cookie) ───────►│
 │                     │                       JWTAuthorizationFilter
 │                     │                        re-issues JWT (fresh 15 min)
 │                     │                        rewrites potc/user/rint
 │                     │                       SessionRefreshFilter → 200
 │                     │◄──────────── 200 (empty) ──────────────│
 │                     │ recordActivity(); showWarning = false  │
 │◄── dialog closes ───│                                        │
```

### Expiry

```
User                Frontend                                 Backend
 │  (15 min, no API calls)                                     │
 │                     │ monitor: timeUntilExpiry <= 0         │
 │                     │ window 'session:expired' → App.vue    │
 │                     │ clear `user`, authStore.logout(),     │
 │                     │ cleanup()                              │
 │◄── /login?expired=true ─┤                                    │
```

## Troubleshooting

**Warning dialog never appears.** The warning is driven purely by *elapsed time since
the last successful API call* — an idle tab still warns and expires on schedule, so
"no dialog" points at the monitor not running, not at inactivity. Check that
`startSessionMonitoring()` ran (it only does if a `user` cookie was present at
`App.vue` mount — direct navigation to a page while unauthenticated, then logging in
via SPA routing without a full reload, can skip it) and that a `rint` cookie exists so
the threshold is the derived 5 min rather than the 2 min fallback.

**Warning window is wrong (not ~5 min).** `SESSION_TTL` in `plugins/sessionManager.js`
must equal the backend `JWT_TTL`, and backend `rint` must equal `JWT_TTL - 5min`. If
someone changed one side only, they drift. (1.7b removes this coupling.)

**Expired token doesn't redirect to /login.** Known issue: the JWT filter returns a
bodyless 401, so the interceptor's `errorKey` check misses. Tracked as 1.7b B3.

**Logout bounces back into the app.** Known issue in `useSession.handleLogout()` — it
doesn't clear `authStore` / `skp`. Use `App.vue`'s `handleSessionExpired()` path or
`authStore.logout()` (which clears `skp`) as the reference. Tracked in 1.7b.

## Known limitations

These are real defects, deferred to **Story 1.7b** because they need an architectural
decision on the `rint` contract first:

1. **`rint` is a fixed value, not a countdown / absolute timestamp.** The warning
   window works only by coincidence of two independently-hardcoded constants.
2. **Idle background tab can force-log-out an active tab.** Each tab runs its own
   elapsed-time timer and never sees another tab's API activity.
3. **`useSession.handleLogout()` doesn't fully clear session state** (`skp` survives),
   so the guard can bounce the user back in.
4. **Expired-token 401 has no `ErrorDto` body**, so the frontend's
   `errorKey === 'security.sessionExpired'` gate never fires for that case.
5. **`SESSION_TTL` is hardcoded on the frontend** and must be manually kept in sync
   with the backend `JWT_TTL`.

## Summary

| Piece | Reality |
|---|---|
| `GET /refresh` | Secured no-op; TTL extension happens upstream in `JWTAuthorizationFilter`. |
| `POST /api/auth/refresh` | Full refresh-token rotation; returns `LoginResponse`. |
| `rint` cookie | Fixed `600000` ms, browser-session TTL, JS-readable; client derives a 5-min warning window from it. |
| Session state | `plugins/sessionManager.js` (refs + timers) + `composables/useSession.js` (wrapper). No Pinia session store. |
| Identity state | `stores/auth.store.js`, hydrated from the `skp` cookie. |
| "Activity" | API requests only (Axios request interceptor). No input tracking. |
| Warning dialog | `components/common/SessionWarningDialog.vue`, mounted once in `App.vue`. |
