# Session Refresh Mechanism for Frontend Integration

This document explains how session keep-alive, expiry detection and the session-timeout
warning actually work in this codebase (Vue 3 + Quasar 2 frontend, Spring Security backend).

> **Accuracy note.** Story 1.7a rewrote this document to match the code (an earlier
> version referenced a `stores/session.js` Pinia store, a `useSessionStore()` and a
> `composables/useActivityTracking.js` — **none of which exist**). Story 1.7b then
> changed the `rint` contract to an **absolute expiry timestamp** and made the
> JWT-filter 401 emit an `ErrorDto`, and two rounds of code review added the clock-skew
> cross-check, the stale-`rint` floor and the `App.vue` listener-ordering requirement.
> This document reflects the code as it stands after all of that. Remaining rough edges
> are in [Known limitations](#known-limitations); items 4–9 there are tracked in
> `_bmad-output/implementation-artifacts/deferred-work.md`.
>
> This file is the **authority** for session-refresh behaviour.
> `docs/security-api-endpoints.md`, `docs/frontend-integration-guide.md` and
> `docs/frontend-implementation-spec.md` were realigned with it on 2026-09-02 and now point
> here rather than restating the contract. If you change the `rint` contract or the 401 shape,
> update this file first, then check those three for cookie tables that repeat it.

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

- **Expiry is read from the `rint` cookie, an absolute timestamp.** Since Story 1.7b the
  client computes `timeUntilExpiry = rint - Date.now()` on every monitor tick, where
  `rint` is the JWT's absolute expiry in epoch ms (rewritten by the backend on every
  authenticated response). The primary path needs no copy of `JWT_TTL`, it is multi-tab safe (an
  idle tab sees a sibling's advanced value), and it survives timer suspension across a laptop
  sleep. Its one weakness is client wall-clock skew, which is guarded — see
  [The `rint` cookie](#the-rint-cookie).
- **The warning window is a fixed client-side constant.** `WARNING_THRESHOLD = 5 min`;
  the dialog shows once `timeUntilExpiry <= WARNING_THRESHOLD`. The server does not send it.
- **"Activity" (legacy fallback only) means API requests only.** `recordActivity()` — called
  by the Axios request interceptor — now only feeds the fallback path used when the `rint`
  cookie is absent. There is still no mouse/keyboard/scroll tracking.

## Key timeouts

| Constant | Value | Where | Meaning |
|---|---|---|---|
| `JWT_TTL` | 15 min | `SecurityConstants` | Access-token (`potc` cookie) lifetime; reset on every authed request |
| `DB_REFRESH_TOKEN_INTERVAL` | 5 min | `SecurityConstants` | How often `JWTAuthorizationFilter` does a full DB re-authorisation (`daoAuthProvider.authorize`) instead of a TTL-only bump. The TTL-only path is **not** DB-free: it still runs one `refresh_tokens` revocation lookup whenever an `rtkn` cookie is present. |
| `REFRESH_TOKEN_TTL` | 7 days | `SecurityConstants` | `rtkn` and `skp` cookie lifetime |
| `rint` cookie value | absolute epoch ms (`now + JWT_TTL`) | `JwtManagerImpl` | The JWT's absolute expiry; rewritten (advanced) on every authenticated response |
| `WARNING_THRESHOLD` | 5 min (300000 ms) | `plugins/sessionManager.js` | Fixed client-side constant; warning shows when `timeUntilExpiry` drops below it |
| `LEGACY_SESSION_TTL` | 15 min (900000 ms) | `plugins/sessionManager.js` | Skew-immune elapsed-time fallback, used when `rint` is absent, stale-format, or contradicted by a fast client clock. Keep loosely in sync with `JWT_TTL` — it is a *fallback* bound only, never used in the primary arithmetic |
| `MIN_PLAUSIBLE_EPOCH_MS` | 1e12 (2001-09-09) | `plugins/sessionManager.js` | Floor separating an absolute epoch-ms `rint` from a stale pre-1.7b fixed-delta value (e.g. `600000`). Below it, `rint` is treated as absent |
| `LOGOUT_BACKEND_WAIT_MS` | 3 s | `composables/useSession.js` | Upper bound on how long logout blocks on the backend revocation call before proceeding to cleanup and redirect |
| `SESSION_CHECK_INTERVAL` | 30 s | `plugins/sessionManager.js` | How often the monitor recomputes `timeUntilExpiry` from `rint` |
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
| `rint` | `SESSION_REFRESH_COUNTDOWN` | no | `JWT_TTL + 60s` (~16 min) | The JWT's **absolute expiry** as epoch ms; advanced on every authenticated response. See [below](#the-rint-cookie). |
| `rtkn` | `REFRESH_TOKEN_COOKIE` | **yes** | 7 days (`REFRESH_TOKEN_TTL`) | Opaque refresh token. Issued only by `POST /api/auth/login` and `POST /api/auth/refresh`. |
| `skp` | `SKILLARS_PROFILE_COOKIE` | no | 7 days | URL-encoded JSON `{"id":<Long>,"role":"COACH"}`. Read by `authStore.hydrateFromCookie()` to restore auth state on page load. |
| `bcookie` | `B_COOKIE` | **yes** | browser-session | Client id. |
| `ION` | `JWT_SESSION_COOKIE` | **yes** | browser-session | Session id. |
| `fcookie` | `F_COOKIE` | no | 1 year | Browser fingerprint for fraud checks. Set **client-side** in `src/boot/axios.js` (`setBrowserFingerprint()`), not by the backend (so its flags are whatever that code sets, not `CookieUtil`'s). |

Because `potc` is HttpOnly, the client cannot inspect the real token itself. Its **expiry** is
published separately in the JS-readable `rint` cookie (Story 1.7b), which is what the countdown
reads; `user` (presence) and `skp` (identity) carry the remaining client-side session hints.

## The `rint` cookie

Since Story 1.7b `rint` carries the **JWT's absolute expiry time, in epoch milliseconds**.
`JwtManagerImpl.createAndSetJwt()` computes `now + JWT_TTL` once, shares it with the JWT's
own `exp` claim, and `createLoginCookies()` writes it:

```java
// jwtExpiryEpochMs = ClockProvider.getClock().millis() + JWT_TTL.toMillis()
CookieUtil.addCookie(res,
        SESSION_REFRESH_COUNTDOWN,
        String.valueOf(jwtExpiryEpochMs),
        false,                          // HttpOnly = false → JS can read it
        (int) (JWT_TTL.toSeconds() + 60)); // maxAge slightly > JWT_TTL: client can still
                                           // read "expired at T" for a short grace window
```

It is rewritten on **every authenticated response**, so under the sliding window the value
**advances** each time the TTL resets (`exp₀` → `exp₀ + Δ` → …). The JWT `exp` claim is a
NumericDate truncated to whole seconds, so `rint` and `exp` agree to within ~1 s.

The frontend (`plugins/sessionManager.js`) reads it as an absolute timestamp:

```js
// computeTimeUntilExpiry(), called every monitor tick
const localEstimate = LEGACY_SESSION_TTL - (Date.now() - lastActivityTime.value);
const expiresAt = readSessionExpiryFromCookie();   // Number(rint), epoch ms, or null when the
                                                   // cookie is absent OR below
                                                   // MIN_PLAUSIBLE_EPOCH_MS (a stale pre-1.7b
                                                   // fixed-delta value such as 600000)
if (expiresAt === null) return localEstimate;
const remaining = expiresAt - Date.now();
// Clock-skew cross-check: only let the server timestamp END a session when the skew-immune
// local estimate agrees. No upper bound — see the clock-drift note above.
if (remaining <= 0 && localEstimate > 0) return localEstimate;
return remaining;
```

The warning fires when `timeUntilExpiry <= WARNING_THRESHOLD` (a fixed **5-minute** client
constant). Benefits of the absolute-timestamp contract:

- **No `JWT_TTL` copy in the primary path** — a backend TTL change needs no frontend change.
  (`LEGACY_SESSION_TTL` is a loose 15-min copy, but it bounds only the fallback; nothing in the
  `rint` arithmetic or the guard is derived from it, so raising the backend TTL cannot silently
  disable the contract.)
- **Multi-tab safe** — an idle tab re-reads `rint` each tick and sees a sibling tab's
  advanced value, so it resets instead of force-firing `session:expired`.
- **Survives sleep** — the deadline is a stored instant, not an accumulating countdown, so a
  suspended timer cannot drift it.
- **Clock drift is the one weakness, and it is guarded.** `rint - Date.now()` subtracts a
  *client* instant from a *server* instant, so the result carries the full wall-clock offset
  between the two machines — the legacy elapsed-time estimate is the skew-immune path, not this
  one. A client clock fast by more than `JWT_TTL` reports a small negative remaining that is
  indistinguishable from a real expiry, and would fire `session:expired` within 30 s of every
  login, unrecoverably (each fresh login writes an equally-skewed `rint`).
  The guard is a **cross-check, not a magic range**: the server timestamp may *extend* a session
  freely (that is what makes it multi-tab safe), but it may only *end* one when the skew-immune
  local estimate agrees the session has actually been idle —
  `if (remaining <= 0 && localEstimate > 0) return localEstimate;`.
  There is deliberately **no upper bound** on `remaining`: bounding it against
  `LEGACY_SESSION_TTL` would silently disable this whole path the moment the backend `JWT_TTL`
  rose above the frontend's loose copy, which is exactly the coupling this contract promises does
  not exist. A *slow* client clock therefore over-reports the time left and the client-side
  warning simply never fires; the server's 401 is the backstop in that direction.

`LEGACY_SESSION_TTL` (15 min) is retained purely as a fallback for a session whose `rint`
cookie is missing (e.g. issued before this contract, or stripped by a proxy).

### Deploying the contract change (one-time)

Pre-1.7b builds wrote `rint` as a **fixed delta** (`600000`) in a *browser-session* cookie, which
survives until the browser session ends — so at deploy time a signed-in user can still be holding
one. Read as an absolute timestamp, `600000` is 1970, giving a remaining time of roughly
−1.77e12 ms, which would expire the session on the next tick.

`readSessionExpiryFromCookie()` guards this: any value below `MIN_PLAUSIBLE_EPOCH_MS` (1e12) is
treated as "no cookie", so those sessions fall through to the elapsed-time estimate and keep
working until the next authenticated response rewrites `rint` in the new format. **No action is
required at deploy**, but if you change the `rint` encoding again, add a discriminator rather than
relying on magnitude.

## Frontend implementation

### Modules (actual paths)

| File | Role |
|---|---|
| `src/plugins/sessionManager.js` | Standalone module holding ref-based session state and the monitor timers. **Not a Pinia store.** |
| `src/composables/useSession.js` | Thin composable wrapper: re-exports the refs as `computed`, plus `handleRefresh` / `handleLogout` / `initSession` / `destroySession`. |
| `src/components/common/SessionWarningDialog.vue` | The warning dialog. Rendered once, in `App.vue`. |
| `src/boot/axios.js` | Request interceptor calls `recordActivity()`; response interceptor calls `refreshExpiryState()` and handles 401. |
| `src/App.vue` | Starts session monitoring on mount if a `user` cookie is present; listens for the `session:expired` event. |
| `src/stores/auth.store.js` | Pinia store for identity (`userId`, `role`, `displayName`); `hydrateFromCookie()` reads `skp`. |
| `src/api/session.api.js` | `sessionApi.refresh()` → `GET /refresh`. |
| `src/api/auth.api.js` | `authApi.skillarsRefresh()` → `POST /api/auth/refresh`. Note there are **two** logout helpers: `authApi.logout()` → `POST /api/logout` and `authApi.skillarsLogout()` → `POST /api/auth/logout`. Since Story 1.7b **neither is called directly by `useSession.handleLogout()`** — it goes through `authStore.logout()`, which calls `authApi.skillarsLogout()` (`POST /api/auth/logout`). `authApi.logout()` now has no caller on this path. |

### `plugins/sessionManager.js` (the monitor)

Key exported functions:

- `computeTimeUntilExpiry()` (internal) — the primary path returns `rint - Date.now()`. It falls
  back to `LEGACY_SESSION_TTL - (Date.now() - lastActivityTime)` in three cases: the `rint` cookie
  is absent; its value is below `MIN_PLAUSIBLE_EPOCH_MS` (a stale pre-1.7b fixed-delta value); or
  the computed remaining time is `<= 0` while the local estimate says the session is still active
  (a fast client clock — see the clock-drift note above).
- `recordActivity()` — sets `lastActivityTime = Date.now()`. Only feeds the fallback above.
  Called by the Axios request interceptor for every request **except** `GET /refresh`.
- `startSessionMonitoring()` — clears old timers, then **evaluates the session immediately** via
  `tick()` (not merely priming state, so a tab resumed from sleep or an app loaded already inside
  the warning band is handled at once rather than up to 30 s later). If that first `tick()`
  reports the session already expired it has already dispatched `session:expired` and cleaned up,
  and monitoring returns **without arming the interval**. Because that dispatch is synchronous,
  `App.vue` must register its `session:expired` listener *before* calling this.
  For a live session it then arms a `setInterval` (every `SESSION_CHECK_INTERVAL` = 30 s) whose
  `tick()`:
  - recomputes `timeUntilExpiry` via `computeTimeUntilExpiry()`;
  - dispatches `window` event `session:expired` and calls `cleanup()` when `timeUntilExpiry <= 0`;
  - otherwise sets `showWarning = timeUntilExpiry <= WARNING_THRESHOLD` (fixed 5 min);
  - starts/stops the 1-second countdown timer as the warning toggles.
- `refreshExpiryState()` — re-evaluates the session from the current cookie by delegating to
  `tick()` (so the warning toggle, the countdown timer and the expiry event all stay consistent).
  Called by the Axios response interceptor after every response — success *and* error — so an
  in-flight extension is reflected immediately. Renamed from `syncWarningThresholdFromCookie()`,
  which no longer described what it did once the threshold became a constant.
- `refreshSession()` — dynamically imports `src/api/session.api` (to avoid the
  `axios.js → sessionManager.js → session.api.js → axios.js` import cycle), calls
  `sessionApi.refresh()`, then re-syncs from the new `rint`; `tick()` clears the warning and stops
  the 1 s countdown via its warning edge (assigning `showWarning = false` here directly would
  destroy that edge and leak the interval).
  It calls `recordActivity()` **both before and after** the request. The "before" is load-bearing:
  the request interceptor deliberately skips `recordActivity()` for `/refresh`, and the response
  interceptor runs a full `tick()`, so on the no-`rint` fallback path a *successful* refresh would
  otherwise be judged against a stale `lastActivityTime` and could fire `session:expired` on its
  own success.
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
- `handleLogout()` → `stopSessionMonitoring()`, clear the `user` cookie, **`await`**
  `authStore.logout()` (clears the `skp` cookie + Pinia `userId`/`role`/`displayName`, then
  `POST /api/auth/logout`), `playerStore.resetSelfPlayerId()`, `cleanup()`, then
  `router.push('/login')`. Since Story 1.7b this mirrors `App.vue`'s `handleSessionExpired`, so
  the `requiresGuest` guard no longer bounces the user back in (was M2).
  The `await` is **bounded** by `Promise.race` against `LOGOUT_BACKEND_WAIT_MS` (3 s): the axios
  instance sets no timeout, and an unbounded wait would strand the user in the persistent warning
  dialog with a frozen countdown, because `cleanup()` and the redirect both sit behind it.
  `authStore.logout()` swallows its own errors, so the race guards only against a hang.
  Note the guard is safe regardless of the wait: `authStore.logout()` clears `skp` and the Pinia
  state *synchronously*, before its own first `await`.
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
// `rint` before we got here, so re-evaluate. On a 401 the filter has already CLEARED `rint`,
// so this normally takes the legacy elapsed-time estimate and the gate below owns the teardown.
// Not a guarantee: if that estimate is itself already past zero, refreshExpiryState() fires
// 'session:expired' here too. Both paths are idempotent, but can produce two navigations.
refreshExpiryState();

// Error, status 401:
const errorKey = data?.errorMsg?.errorKey || '';
if (status === 401 && (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized')) {
  stopSessionMonitoring();
  cleanup();
  // clear `user` and `skp`, then hard-redirect to /login?...&expired=true
}
```

> **Resolved in Story 1.7b (was B3).** `JWTAuthorizationFilter` now writes an `ErrorDto`
> body (status still 401): `errorKey = "security.sessionExpired"` for an expired JWT,
> `"security.unauthorized"` for any other caught auth failure. The gate above now fires,
> so an expired token cleanly redirects to `/login` instead of the request silently failing.

### The 401 contract

`JWTAuthorizationFilter.writeUnauthorized()` emits exactly this, with `Content-Type:
application/json; charset=UTF-8`:

```json
{
  "helpCode": null,
  "errorMsg": {
    "errorKey": "security.sessionExpired",
    "message": "Your session is no longer valid. You need to sign-in again"
  },
  "fieldErrors": []
}
```

| Caught exception | `errorKey` | Status |
|---|---|---|
| `JWTExpiredException` | `security.sessionExpired` | 401 |
| everything else (`InvalidJWTDataException`, `JWTTheftException`, `MissingAuthenticationException`, `AccountStatusException`, `AccessDeniedException`, …) | `security.unauthorized` | 401 |

Things worth knowing before you integrate against it:

- **The status is always 401**, deliberately. The filter uses `setStatus` + `objectMapper.writeValue`
  rather than `sendError`, and does **not** defer to `@RestControllerAdvice` — which would remap
  some of these to 403. That also means the container's error-page dispatch does not run for these.
- **`helpCode` is always `null`** and no error log line or `SecurityAlertEvent` is produced.
  Every `ApiAdvice` path routes through `toErrorDTO`, which generates a help code and logs; the
  filter constructs the `ErrorDto` directly. Field names and nesting still match `ApiAdvice`, and
  no frontend consumer reads `helpCode` on a 401 — but do not rely on one being present.
- **`message` is always English.** `messageSource.getMessage(errorKey, null, defaultMsg, locale)`
  is called, but no `security.sessionExpired` / `security.unauthorized` key exists in any bundle,
  so it always falls through to the hardcoded default. Same pre-existing gap as `ApiAdvice`.
  Gate on `errorKey`, never on `message`.
- **⚠️ `security.unauthorized` also triggers the SPA's hard redirect.** The interceptor gate matches
  *both* keys, so **every** auth failure caught by this filter — not just an expired JWT — now
  performs a full `window.location.href` navigation to `/login`, discarding unsaved page state.
  Before 1.7b the filter's bodyless 401 left `errorKey` empty and the gate never fired, so this is
  a deliberate widening beyond the original story scope. If you add a background/polling call
  against a secured endpoint, know that a 401 from it will tear down the whole app.

### `App.vue`

```js
function isAuthenticated() {
  // Exact cookie-name match (a bare includes('user=') would also match `xuser=...`).
  return document.cookie.split(';').some((c) => c.trim().startsWith('user='));
}

onMounted(() => {
  // Order matters: startSessionMonitoring() evaluates the session immediately and can dispatch
  // 'session:expired' SYNCHRONOUSLY from inside that call, so the listener must exist first.
  window.addEventListener('session:expired', handleSessionExpired);
  if (isAuthenticated()) startSessionMonitoring();
});
```

> **Do not reorder these two lines.** With the listener registered second, a session already past
> its `rint` deadline at mount dispatches into the void — and because the monitor also declines to
> arm its interval for an expired session, the app is left with no session handling at all until
> the next API call 401s.

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

Note `skp` has a 7-day TTL while `potc` is ~15 minutes: the guard can consider a user
"authenticated" (valid `skp`) after the actual JWT session has expired. The first API
call then returns a 401 carrying `errorKey = "security.sessionExpired"` (Story 1.7b), so
the interceptor tears down and redirects to `/login`. Independently, the monitor fires
`session:expired` once `rint - Date.now() <= 0`.

## Backend components

| File (`com.softropic.skillars…`) | Role |
|---|---|
| `platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter` | On every authed request: re-issues the JWT with a fresh 15 min TTL and rewrites `potc` / `user` / `rint` (the last now = the new absolute expiry). Fast path = `checkAuthorities` + `extendTtlOfToken` (plus a `refresh_tokens` revocation lookup when `rtkn` is present); every ~5 min (or on revocation) it instead runs a full `daoAuthProvider.authorize` DB re-auth. On an auth failure it writes an `ErrorDto` 401 body (`writeUnauthorized`). This is where "sliding window" happens. |
| `platform.security.infrastructure.filter.SessionRefreshFilter` | Terminal handler for `/refresh` (matcher binds no HTTP verb — any method short-circuits here; frontend uses `GET`); returns an empty `200`. Does not refresh anything itself. |
| `platform.security.api.AuthResource` | `POST /api/auth/login` \| `/refresh` \| `/logout`. `/refresh` here = full token rotation, returns `LoginResponse`. |
| `platform.security.service.AuthService` | Refresh-token persistence/rotation, `skp` cookie, reuse detection, multi-tab grace window. |
| `platform.security.infrastructure.jwt.JwtManagerImpl` | Builds and sets all JWT-related cookies (`createLoginCookies`), including `rint` = the JWT's absolute expiry epoch ms (computed once in `createAndSetJwt`, `maxAge = JWT_TTL + 60s`). |
| `platform.security.config.SecurityConfiguration` | Filter-chain wiring; `SessionRefreshFilter` is added right after `JWTAuthorizationFilter`. |
| `infrastructure.security.SecurityConstants` | `JWT_TTL`, `DB_REFRESH_TOKEN_INTERVAL`, `REFRESH_TOKEN_TTL`, all cookie-name constants. |

## Sequence diagrams

### Keep-alive from the warning dialog

```
User                Frontend                                 Backend
 │                     │                                        │
 │  (~10 min idle)     │                                        │
 │                     │ tick: rint - Date.now() <= 5 min       │
 │                     │ (rint unchanged since the last call;   │
 │                     │  ±30 s check-tick granularity)         │
 │◄── warning dialog ──│                                        │
 │                     │                                        │
 │── Continue ────────►│ sessionApi.refresh()                   │
 │                     │──── GET /refresh (potc cookie) ───────►│
 │                     │                       JWTAuthorizationFilter
 │                     │                        re-issues JWT (fresh 15 min)
 │                     │                        rewrites potc/user/rint (rint advances)
 │                     │                       SessionRefreshFilter → 200
 │                     │◄──────────── 200 (empty) ──────────────│
 │                     │ re-sync from new rint; showWarning=false│
 │◄── dialog closes ───│                                        │
```

### Expiry

```
User                Frontend                                 Backend
 │  (no API call extended rint; rint reached)                  │
 │                     │ tick: rint - Date.now() <= 0          │
 │                     │ window 'session:expired' → App.vue    │
 │                     │ clear `user`, authStore.logout(),     │
 │                     │ cleanup()                              │
 │◄── /login?expired=true ─┤                                    │
 │                     │ (or: next API call → 401 errorKey     │
 │                     │  security.sessionExpired → same teardown) │
```

## Troubleshooting

**Warning dialog never appears.** The warning fires when `rint - Date.now() <= 5 min`, so
"no dialog" points at the monitor not running or the `rint` cookie missing, not at
inactivity. Check that `startSessionMonitoring()` ran (it only does if a `user` cookie was
present at `App.vue` mount — direct navigation while unauthenticated, then SPA-routing in
after login without a full reload, can skip it) and that a `rint` cookie is present
(otherwise the legacy elapsed-time fallback is used).

**Warning window is wrong (not ~5 min).** `WARNING_THRESHOLD` in `plugins/sessionManager.js`
is the only knob — it is independent of `JWT_TTL`. If `rint` itself looks wrong, check
`JwtManagerImpl.createAndSetJwt` (should be `now + JWT_TTL` epoch ms).

**User is logged out immediately and repeatedly, on every login.** Almost always a **fast client
clock**. Compare `new Date(Number(document.cookie.match(/rint=(\d+)/)[1]))` against the server's
`Date` response header. A clock fast by more than `JWT_TTL` makes `rint - Date.now()` negative the
instant it is written. The cross-check in `computeTimeUntilExpiry()` should catch this and fall
back to the elapsed-time estimate — if it does not, that guard has regressed.

**Warning never appears but the session does expire server-side.** The mirror case: a **slow**
client clock over-reports the remaining time, so `timeUntilExpiry` never crosses
`WARNING_THRESHOLD`. Unguarded by design (see Known limitations).

**A background/polling call logged the whole app out.** Expected since 1.7b: the interceptor gate
matches `security.unauthorized` as well as `security.sessionExpired`, so any 401 from
`JWTAuthorizationFilter` hard-redirects to `/login`. See [The 401 contract](#the-401-contract).

**Two navigations / a login flash then a full reload.** Both teardown paths ran: the response
interceptor's `refreshExpiryState()` fired `session:expired` (App.vue `router.push`) and the 401
gate then did `window.location.href`. Reachable when the fallback is already past zero as the 401
arrives — mainly a 401 on `/refresh`, the one URL excluded from `recordActivity()`.

**Expired token doesn't redirect to /login.** The filter now emits
`errorKey = "security.sessionExpired"` on a 401. If the redirect still doesn't happen,
confirm the response body actually reaches the interceptor (CORS / proxy stripping the body).

**Logout bounces back into the app.** `useSession.handleLogout()` now clears `authStore`
and the `skp` cookie (mirrors `App.vue`'s `handleSessionExpired`). If it still bounces,
check that `authStore.logout()` ran and `skp` is gone.

## Known limitations

Story 1.7b closed the `rint`-contract cluster (absolute-timestamp `rint`, multi-tab sync,
`ErrorDto` 401, full logout teardown). Remaining minor points:

1. **±30 s granularity.** `SESSION_CHECK_INTERVAL` = 30 s, so the warning and the
   client-side expiry event can fire up to 30 s late. Any test asserting an exact figure
   is flaky by construction.
2. **`user=` with an empty value still counts as "a session".** `App.vue`'s
   `isAuthenticated()` only checks the cookie *name*; a principal with a null display name
   yields `user=` on the wire. Pre-existing, harmless (the guard uses `skp`, not `user`).
3. **`LEGACY_SESSION_TTL` fallback** is a coarse elapsed-time estimate; a session missing
   its `rint` cookie loses the multi-tab / sleep-resilience guarantees until the next
   authenticated response re-sets `rint`. It is also entered deliberately in two guard cases:
   a stale pre-1.7b fixed-delta `rint` (below `MIN_PLAUSIBLE_EPOCH_MS`), and a `rint`-derived
   remaining time that has gone `<= 0` while the local estimate says the session is still
   active (a fast client clock).
4. **The fallback fails open.** `deleteLoginToken` clears `rint` before the filter writes its
   401, and the request interceptor resets `lastActivityTime` on every outgoing request — so the
   fallback re-arms a full ~15 min for a session that is already dead. Harmless on the 401 path
   itself (the `errorKey` gate tears the session down on that same response), but it means the
   elapsed-time monitor is no longer a second line of defence if the body is ever stripped.
5. **A sibling tab does not notice another tab's logout.** `App.vue` evaluates `isAuthenticated()`
   only at mount and nothing in `tick()` re-checks that the session cookies still exist. When one
   tab logs out, the server clears `rint`/`user`/`potc`/`skp` for the whole browser; the other tab
   falls into the fallback and keeps rendering an authenticated UI until a click 401s into a hard
   reload. Same root cause as (4); a cookie-presence re-check in `tick()` would close both.
6. **A failed "Continue session" is silent.** `refreshSession()`'s `catch` only calls
   `console.error`; the dialog has no error surface and `isRefreshing` returns to `false`, so the
   button re-enables as if the extension succeeded while the countdown keeps running down.
7. **If the expiry navigation is swallowed, nothing re-arms monitoring.** When the first `tick()`
   finds an expired session, `startSessionMonitoring()` returns without arming the interval and
   relies on `App.vue`'s `router.push()`. That push is not awaited or `.catch()`ed and Vue Router
   rejects on an aborted navigation; if it is aborted, `cleanup()` has already reset state to look
   healthy and monitoring is only restarted by an `App.vue` remount or `initSession()`.
8. **Clock skew in the *slow* direction is unguarded by design.** A client clock slow by more than
   `WARNING_THRESHOLD` over-reports the time left, so the warning dialog never appears and the
   client-side expiry never fires; the server's 401 is the only backstop. Guarding it would require
   bounding `remaining` against a client-side TTL copy, which is the coupling this contract exists
   to remove.
9. **There is no frontend test coverage for any of this.** `src/frontend/package.json`'s `test`
   script is a no-op and the only spec file in the tree is unrelated. Every behaviour described in
   this document is verified by reading, by the backend tests, or by hand — never in CI.

Items 4–9 are tracked in `_bmad-output/implementation-artifacts/deferred-work.md`.

## Summary

| Piece | Reality |
|---|---|
| `GET /refresh` | Secured no-op; TTL extension happens upstream in `JWTAuthorizationFilter`. |
| `POST /api/auth/refresh` | Full refresh-token rotation; returns `LoginResponse`. |
| `rint` cookie | The JWT's **absolute expiry** (epoch ms), advanced every authed response, `maxAge = JWT_TTL + 60s`, JS-readable. Client: `timeUntilExpiry = rint - Date.now()`. |
| Clock-skew guard | The server timestamp may *extend* a session freely, but may only *end* one when the skew-immune local estimate agrees. No upper bound, so raising the backend `JWT_TTL` stays safe. |
| Stale-`rint` guard | Values below `MIN_PLAUSIBLE_EPOCH_MS` (pre-1.7b fixed deltas) are treated as "no cookie". |
| 401 on auth failure | `JWTAuthorizationFilter` writes an `ErrorDto` body: `security.sessionExpired` (expired JWT) or `security.unauthorized`. Always 401, `helpCode: null`, message always English. **Both** keys hard-redirect the SPA. |
| Logout | `useSession.handleLogout()` → `authStore.logout()` (`POST /api/auth/logout`), awaited but bounded at `LOGOUT_BACKEND_WAIT_MS` (3 s). |
| Session state | `plugins/sessionManager.js` (refs + timers) + `composables/useSession.js` (wrapper). No Pinia session store. |
| Identity state | `stores/auth.store.js`, hydrated from the `skp` cookie. |
| "Activity" (legacy fallback only) | API requests only (Axios request interceptor). No input tracking. |
| Warning dialog | `components/common/SessionWarningDialog.vue`, mounted once in `App.vue`. |
