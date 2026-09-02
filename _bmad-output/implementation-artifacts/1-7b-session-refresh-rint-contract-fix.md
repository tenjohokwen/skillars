# Story 1.7b: Session Refresh `rint` Contract Fix (Deferred)

Status: done

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

- [x] Manual JWT expiry check (PREREQUISITE) — satisfied by code inspection + automated coverage, not a browser session: the 1.7a code review independently confirmed the bodyless `sendError(SC_UNAUTHORIZED)` at `JWTAuthorizationFilter` and that the only producer of `security.sessionExpired` is `ApiAdvice`, which a servlet filter never reaches. New `SecurityFilterChainIT` + `JWTAuthorizationFilterTest` assertions now lock in the fixed shape.
- [x] `rint` cookie contains absolute epoch milliseconds on every authenticated response — `JwtManagerImplTest.testCreateLoginToken_success` / `testRenewLoginToken_success` assert `rint ≈ claims.getExpiration()` (within the 1 s NumericDate truncation)
- [x] `rint` increases as JWT is re-issued — `JwtManagerImplTest.testExtendTtlOfToken_success`: `extendedRint > initialClaims.getExpiration()` after a 20 s clock advance
- [x] Frontend reads `rint` as absolute timestamp — `sessionManager.computeTimeUntilExpiry()` → `rint - Date.now()`
- [x] Warning shows 5 minutes before `rint` — `WARNING_THRESHOLD = 5 * 60 * 1000`; `tick()` sets `showWarning = timeUntilExpiry <= WARNING_THRESHOLD`
- [x] Idle background tab reads sibling's `rint` and doesn't force-logout — every `tick()` re-reads the cookie, so an idle tab sees the advanced value; documented trace in Completion Notes
- [x] 401 error path works — `JWTAuthorizationFilter.writeUnauthorized` emits `ErrorDto`; `SecurityFilterChainIT` asserts `errorKey":"security.unauthorized"` in the body, unit test asserts `security.sessionExpired` for `JWTExpiredException`
- [x] Logout button fully clears session — `useSession.handleLogout()` now clears the `user` cookie + `authStore.logout()` (clears `skp` + Pinia state)
- [x] `JwtManagerImplTest` assertions pass with new `rint` format — 32/32 green
- [x] Multi-tab session behavior — active tab advances `rint` on each response; idle tab's next tick reads it and resets (see Completion Notes trace)

## Tasks / Subtasks

_Derived from the ACs above and the confirmed implementation sequence in `1-7b-ARCHITECTURAL-DECISION.md`. The story spec had no Tasks section; this was added at dev-story start._

### Phase 1 — Backend: `rint` as absolute expiry timestamp (AC: Redesign `rint` as Absolute Expiry Timestamp; M4)

- [x] `JwtManagerImpl.createAndSetJwt` / `createLoginCookies` — `rint` = `ClockProvider.getClock().millis() + JWT_TTL.toMillis()` computed once in `createAndSetJwt` and passed down; `maxAge = (int) (JWT_TTL.toSeconds() + 60)`
- [x] Rewrote `SecurityConstants.SESSION_REFRESH_COUNTDOWN` JavaDoc for the absolute-timestamp contract
- [x] Rewrote the `JwtManagerImpl` inline `rint` comment
- [x] `JwtManagerImplTest` — 2 fixed-value assertions replaced with `isCloseTo(claims.getExpiration(), within(1_500L))` + `> now`; `testExtendTtlOfToken_success` gains `extendedRint > initialClaims.getExpiration()`. 32/32 green.

### Phase 2 — Backend: JWT-filter 401 emits `ErrorDto` (AC: Fix 401 Error Path; B3)

- [x] `JWTAuthorizationFilter` — new `writeUnauthorized(res, cause)` writes an `ErrorDto` JSON body at status 401 (`setStatus` + `objectMapper.writeValue`, not `sendError`): `security.sessionExpired` for `JWTExpiredException`, `security.unauthorized` otherwise; i18n via `MessageSource` (falls back to the default string — no `security.*` keys in the bundles, same as `ApiAdvice`). Status stays 401 for every caught type (deliberately does not defer to `@RestControllerAdvice`, which would remap some to 403).
- [x] `SecurityConfiguration#filterChain` — injected `MessageSource` + `ObjectMapper`, passed into `new JWTAuthorizationFilter(...)` (9-arg constructor)
- [x] `JWTAuthorizationFilterTest` — 7-arg → 9-arg constructor + writer/MessageSource stubs; 6 `verify(sendError)` → `verify(setStatus)`; 2 tests now also assert the `errorKey` in the written body. 10/10 green.
- [x] `SecurityFilterChainIT.testSecuredEndpointRequiresAuth` — asserts the no-token 401 body contains `"errorKey":"security.unauthorized"`. 4/4 green (+ `SecurityIT` 9/9, `AuthResourceIT` 9/9, `FamilyDataIsolationIT` 5/5).

### Phase 3 — Frontend: absolute-timestamp read, multi-tab sync, logout (AC: Frontend / Multi-Tab / M2)

- [x] `plugins/sessionManager.js` — rewritten: `readSessionExpiryFromCookie()` + `computeTimeUntilExpiry()` (`rint - Date.now()`, else `LEGACY_SESSION_TTL - elapsed`); single `tick()` shared by the 30 s monitor and the 1 s countdown; `WARNING_THRESHOLD = 5*60*1000` constant; `warningThresholdSeconds` export kept (now constant). Public API (`recordActivity`, `startSessionMonitoring`, `stopSessionMonitoring`, `refreshSession`, `cleanup`, exported refs) unchanged; `syncWarningThresholdFromCookie` was renamed to `refreshExpiryState` during code review (it no longer syncs any threshold) and now delegates to `tick()`.
- [x] Multi-tab (M1): every `tick()` re-reads the cookie → an idle tab sees an active sibling's advanced `rint` and resets instead of firing `session:expired`. No code beyond the recompute.
- [x] `composables/useSession.js` — `handleLogout()` now: `stopSessionMonitoring()`, clear `user` cookie, `authStore.logout()` (clears `skp` + Pinia state + best-effort backend), `playerStore.resetSelfPlayerId()`, `cleanup()`, `router.push('/login')`. Dropped the unused `authApi` import.
- [x] `docs/session-refresh-mechanism.md` — updated: `rint` contract, key-timeouts table, cookie table, `sessionManager.js` description, 401 gap → resolved, guard note, sequence diagrams, troubleshooting, "Known limitations".

### Verification

- [x] Targeted backend tests green: `JwtManagerImplTest` 32/32, `JWTAuthorizationFilterTest` 10/10, `SecurityFilterChainIT` 4/4, `SecurityIT` 9/9, `AuthResourceIT` 9/9, `FamilyDataIsolationIT` 5/5
- [x] `npx eslint` clean on `sessionManager.js`, `useSession.js`, `boot/axios.js`, `SessionWarningDialog.vue`, `App.vue`; `npx quasar build` succeeded
- [x] Multi-tab + logout flows traced in Completion Notes (no frontend unit-test infra in this repo)
- [ ] Full `mvn verify` — NOT run locally (per `docs/validation-strategy.md`); the auth-filter 401 change is cross-cutting, so CI must exercise the video/booking/payment `*IT`s that assert 401 (status is unchanged; only the body gained an `ErrorDto`, risk is any test asserting the old bare-401 body shape)

### Review Findings

_bmad-code-review, 2026-09-02. Layers: Blind Hunter (completed); Edge Case Hunter and Acceptance
Auditor both terminated on an API session limit, so their coverage was re-run inline by the
reviewer against the working tree. Every Blind Hunter claim below was independently verified
against real source before being kept; six were dismissed as false positives._

- [x] [Review][Decision] **RESOLVED (option c — clamp/degrade):** **Client clock-skew tolerance for the absolute-`rint` contract** — The design is approved, but the claim attached to it is backwards. `timeUntilExpiry = rint - Date.now()` compares a *server* absolute instant to a *client* absolute instant, so the result carries the full wall-clock offset between the two machines. The code it replaced (`LEGACY_SESSION_TTL - (now - lastActivityTime)`) was a pure local delta and was immune to that offset. A client clock >5 min fast fires `session:expired` within 30 s of every login, permanently, and "Continue" cannot recover it (the refreshed `rint` is equally skewed). A client clock >5 min slow never warns and never expires client-side. Options: (a) accept as-is and correct only the wording — the 401 path still catches it server-side; (b) derive a one-time server/client offset from the `Date` response header and apply it in `computeTimeUntilExpiry()`; (c) clamp `timeUntilExpiry` to `[0, JWT_TTL + slack]` so gross skew degrades to the normal window instead of instant logout. [`src/frontend/src/plugins/sessionManager.js:computeTimeUntilExpiry`]
- [x] [Review][Decision] **RESOLVED (option a — accept the widening, no code change; disclosed below):** **Missing-token 401s now hard-redirect the whole SPA** — The filter's new `security.unauthorized` key is already matched by the axios gate at `boot/axios.js:146`, which responds with `window.location.href = '/login?...&expired=true'` — a full page navigation, not a router push. Before this change the filter emitted a bodyless 401, `errorKey` was `''`, and the gate did not fire. So the blast radius is wider than the AC's expired-token case: *any* `AccountStatusException | AuthorizationException | AccessDeniedException` caught by the filter — including a missing token on a background/polling call — now tears the app down to `/login`. The AC only asked for the expired-JWT path. Options: (a) accept — a session that fails filter auth should go to login; (b) restrict the hard redirect to `security.sessionExpired` and let `security.unauthorized` fall through to normal error handling. [`src/frontend/src/boot/axios.js:146`, `JWTAuthorizationFilter.java:232`]
- [x] [Review][Patch] Stale pre-1.7b `rint=600000` parses as a valid absolute timestamp and force-logs-out every already-signed-in user at deploy [`src/frontend/src/plugins/sessionManager.js:readSessionExpiryFromCookie`]
- [x] [Review][Patch] 1-second countdown interval leaks for the rest of the session after a manual "Continue" refresh [`src/frontend/src/plugins/sessionManager.js:refreshSession`]
- [x] [Review][Patch] `syncWarningThresholdFromCookie()` and `startSessionMonitoring()` assign `timeUntilExpiry` without evaluating it — up to a 30 s blind window at mount and after every response [`src/frontend/src/plugins/sessionManager.js:startSessionMonitoring`]
- [x] [Review][Patch] Four separate "resilient to client sleep / clock drift" claims are factually wrong and must be corrected [`SecurityConstants.java:SESSION_REFRESH_COUNTDOWN`, `JwtManagerImpl.java:createLoginCookies`, `sessionManager.js:computeTimeUntilExpiry`, `docs/session-refresh-mechanism.md`]
- [x] [Review][Patch] `handleLogout()` is still declared `async` but no longer awaits the backend logout, so callers get no revocation guarantee [`src/frontend/src/composables/useSession.js:handleLogout`]
- [x] [Review][Patch] Filter's `security.unauthorized` default message copies `changeDenialHandler`'s text instead of `ApiAdvice.handleAuthException`'s "Unauthorized Access." [`JWTAuthorizationFilter.java:writeUnauthorized`]
- [x] [Review][Patch] `tick()`'s `@returns` documents a contract no caller honours, and `syncWarningThresholdFromCookie` no longer syncs any threshold [`src/frontend/src/plugins/sessionManager.js`]
- [x] [Review][Defer] Legacy fallback fails open — `deleteLoginToken` clears `rint` on the 401 path, so the response interceptor recomputes ~15 min remaining for a session that is provably dead [`sessionManager.js:computeTimeUntilExpiry`, `JwtManagerImpl.java:150`] — deferred, defense-in-depth only (the `errorKey` gate now fires on that response, verified)
- [x] [Review][Defer] No `security.sessionExpired` / `security.unauthorized` keys in any i18n bundle, so DE/FR users get hardcoded English on session expiry [`src/main/resources/i18n/`] — deferred, pre-existing `ApiAdvice` pattern
- [x] [Review][Defer] `writeUnauthorized` sets no `Cache-Control: no-store` on the 401 JSON body [`JWTAuthorizationFilter.java:writeUnauthorized`] — deferred, pre-existing
- [x] [Review][Defer] A whole client-side state machine was rewritten with zero frontend tests [`src/frontend/src/plugins/sessionManager.js`] — deferred, no frontend test infra in this repo (disclosed in Completion Notes)

**Dismissed as false positives (6)** — each checked against real source:
`ErrorDto.fieldErrors` is initialised to `new ArrayList<>()`, so the JavaDoc's `fieldErrors: []` shape is accurate;
`RequestMetadataProvider.getClientInfo()` lazily creates and never returns null, and a null `chosenLang` is already handled by `StringUtils.isNotBlank`, so no NPE;
only two `new JWTAuthorizationFilter(...)` call sites exist and both are updated;
`browserSessionTtl` is still consumed by `B_COOKIE` and `JWT_SESSION_COOKIE`, so it is not unused;
`sendError` → `setStatus` losing container error-dispatch is the deliberate, disclosed consequence of the AC;
`maxAge` 960 s vs the spec's "e.g. 920 seconds" is within the stated "slightly larger than JWT_TTL" intent.

**Acceptance coverage verified inline** (Auditor layer did not complete): `rint` = absolute epoch ms shared with the JWT `exp`; advanced on every authed response via `createAndSetJwt`; `maxAge` (960 s) > `JWT_TTL` (900 s); `WARNING_THRESHOLD = 5*60*1000`; `timeUntilExpiry = rint - Date.now()`; `SESSION_TTL` retained as `LEGACY_SESSION_TTL`; 401 body shape genuinely matches what the axios gate reads (`data?.errorMsg?.errorKey` ← `ErrorDto.getErrorMsg()` → `ErrorMsg.errorKey`); `sessionExpired` default message matches `ApiAdvice.jwtExpirationHandler` verbatim; `handleLogout()` clears `authStore` + `skp` synchronously before `router.push`, so `requiresGuest` cannot bounce; both named `JwtManagerImplTest` assertions updated. **The story's flagged CI risk was checked and is unfounded** — no test in `src/test` asserts the old fixed `rint=600000`, and no IT asserts an empty 401 body (`VideoUploadResourceIT:58`'s `sendError` is its own test `AuthenticationEntryPoint`, a different code path).

#### Review resolution (2026-09-02)

Both decisions resolved by the project owner, and all 7 `[Review][Patch]` items applied plus one
code change from D1 (8 code edits in total, across 4 frontend files — `App.vue` included, see the
round-2 section below for why it was needed):

- **D1 → clamp/degrade.** `computeTimeUntilExpiry()` now range-checks the `rint`-derived remaining
  time against `[MIN_PLAUSIBLE_REMAINING, MAX_PLAUSIBLE_REMAINING]` and falls back to the
  skew-immune local-delta estimate when it lands outside. Rationale for the band: the cookie's own
  `maxAge` (`JWT_TTL + 60s`) is enforced by the browser against *its own* clock, so a `rint` that is
  still present bounds how much client-measured time can have elapsed since the server wrote it —
  a value far outside that band means the clock is wrong, not that the session ended.
- **D2 → accepted as designed.** A request that fails filter auth genuinely has no valid token, so
  routing to `/login` is correct. **Disclosed behaviour change beyond the AC:** the axios gate at
  `boot/axios.js:146` already matches `security.unauthorized`, so *every* auth failure caught by
  `JWTAuthorizationFilter` — not just an expired JWT — now triggers a full `window.location.href`
  navigation to `/login`. Before 1.7b the filter's bodyless 401 left `errorKey` empty and the gate
  never fired. No code change; recorded here so it is not rediscovered as a regression.
- **P1** stale-`rint` guard (`MIN_PLAUSIBLE_EPOCH_MS`) — closes the deploy-window force-logout.
- **P2/P3/P7** collapsed into one fix: `syncWarningThresholdFromCookie()` renamed to
  `refreshExpiryState()` and now delegates to `tick()`; `startSessionMonitoring()` evaluates
  immediately via `tick()` and honours its return value instead of arming a timer on a dead
  session; `refreshSession()` no longer assigns `showWarning = false` directly (that destroyed the
  warning edge and leaked the 1 s countdown interval for the rest of the session).
- **P4** the four "resilient to clock drift" claims corrected in `SecurityConstants`,
  `JwtManagerImpl`, `sessionManager.js` and `docs/session-refresh-mechanism.md` — sleep/timer
  suspension is genuinely survived; wall-clock offset is the weakness, and is now guarded.
- **P5** `handleLogout()` awaits `authStore.logout()` (the guard is unaffected — `skp` and the
  Pinia state are cleared synchronously before that call's own first `await`).
- **P6** filter's `security.unauthorized` default message now matches
  `ApiAdvice.handleAuthException` ("Unauthorized Access.") rather than `changeDenialHandler`'s text.

**Validation after patching:** `npx eslint` clean on the 4 changed frontend files; `npx quasar build`
succeeded (also confirms the `refreshExpiryState` rename resolves across `boot/axios.js`). Java
changes are a JavaDoc/comment rewrite plus one default-message string literal that no test asserts
(verified) — no local `mvn verify` per `docs/validation-strategy.md`; CI remains the gate.

#### Round-2 review (2026-09-02)

The round-1 patches were themselves re-reviewed, at the project owner's request, by all three layers
(Blind Hunter, Edge Case Hunter, Acceptance Auditor — all three completed this time). **Three of the
round-1 fixes turned out to be wrong or incomplete.** Re-running was the right call: none of this
would have been caught by shipping on the first green.

**Regressions introduced by round 1, now fixed:**

- [x] [Review][Patch] **`session:expired` was dispatched before its listener existed.** P3 made
  `startSessionMonitoring()` evaluate the session synchronously, but `App.vue` registered the
  `session:expired` listener *after* calling it. An already-expired session at mount dropped the
  event, and the early return meant no interval was armed either — leaving the app with no session
  handling at all until the next API call 401'd. Listener now registers first. [`src/frontend/src/App.vue`]
- [x] [Review][Patch] **The D1 clock-skew band was wrong in both directions.** Its lower bound
  (`-2 min`) only rescued a clock fast by more than `JWT_TTL + 2 min`, so a clock fast by 15–17 min
  produced a small negative remaining, passed the guard, and force-logged-out on every login —
  precisely the failure D1 existed to prevent. Its upper bound (`LEGACY_SESSION_TTL + 2 min`)
  re-introduced the exact `JWT_TTL` client coupling the AC required removing: raising the backend TTL
  to 30 min would have silently disabled the whole absolute-`rint` path. Replaced with a
  cross-check that needs no magic numbers — the server timestamp may *extend* a session freely, but
  may only *end* one when the skew-immune local estimate agrees the session is idle
  (`if (remaining <= 0 && localEstimate > 0) return localEstimate;`), and there is no upper bound at
  all. [`src/frontend/src/plugins/sessionManager.js:computeTimeUntilExpiry`]
- [x] [Review][Patch] **A successful `GET /refresh` could fire `session:expired`.** P3 routed the
  axios response interceptor through `tick()`, and the request interceptor deliberately skips
  `recordActivity()` for `/refresh`, so on the no-`rint` fallback path a *successful* refresh was
  evaluated against a stale `lastActivityTime` and could log the user out on its own success.
  `recordActivity()` now runs before the call as well as after. [`sessionManager.js:refreshSession`]
- [x] [Review][Patch] **P5's `await` introduced an unbounded hang.** The axios instance sets no
  timeout, so a stalled logout request blocked `cleanup()` and `router.push()` indefinitely — leaving
  the persistent warning dialog open with a frozen countdown, since `stopSessionMonitoring()` had
  already killed both timers. The wait is now bounded by `LOGOUT_BACKEND_WAIT_MS` (3 s) via
  `Promise.race`, keeping P5's revocation guarantee without the hang. [`useSession.js:handleLogout`]

**Coverage gaps closed:**

- [x] [Review][Patch] Five `JWTAuthorizationFilterTest` cases verified `setStatus(401)` on a mock but
  asserted nothing about the body, so deleting `objectMapper.writeValue` would still have passed
  them. All five now pin `errorKey`. [`JWTAuthorizationFilterTest.java`]
- [x] [Review][Patch] `JwtManagerImplTest`'s `rint` assertions compared `rint` only against the JWT
  `exp` it is derived from, so a bug in the shared `now + JWT_TTL` expression moved both together and
  passed. Both now also pin the absolute magnitude against `now + JWT_TTL`. [`JwtManagerImplTest.java`]
- [x] [Review][Patch] Docs re-synced: the `computeTimeUntilExpiry()` snippet and function summary
  omitted both guards; the `startSessionMonitoring()` description omitted immediate evaluation and
  the no-interval-on-expiry return; a cookie-section line still claimed the client "tracks the
  countdown itself"; and `deferred-work.md` still used the pre-rename function name.
  [`docs/session-refresh-mechanism.md`, `deferred-work.md`]

**Deferred (6, in `deferred-work.md` under the round-2 heading):** sibling tab renders an
authenticated UI after another tab logs out; `refreshSession()` failure is invisible to the user;
`startSessionMonitoring()`'s early return leaves no timer armed if the expiry navigation is
swallowed (both available fixes have real costs — recorded rather than guessed); filter 401s emit
`helpCode: null` and skip `ApiAdvice`'s logging/alerting; three sibling docs still call `rint` a
15-minute countdown; Prettier still fails repo-wide and this story widened the surface.

**Dismissed in round 2 (verified false positives):** `authStore.logout()` cannot reject (its only
`await` is inside a `try/catch`), so no missing-catch bug; it *does* call the backend and clears
`skp` + Pinia state synchronously before that await; `CookieUtil` always sets `path("/")`, so the
`user`-cookie deletion matches; `ErrorDto.fieldErrors` is initialised to `new ArrayList<>()`;
`RequestMetadataProvider.getClientInfo()` lazily creates and never returns null.

**Validation:** `JWTAuthorizationFilterTest` 10/10 and `JwtManagerImplTest` 32/32 green **after** the
strengthened assertions (targeted `mvn test`, not `mvn verify`); `npx eslint` clean on all four
changed frontend files. The full suite remains CI's job.

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

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Completion Notes

Backend + frontend behavioural story. The story spec carried no `## Tasks / Subtasks`
section (its breakdown lived in `1-7b-ARCHITECTURAL-DECISION.md`) and a stale
`Status: blocked-pending-decision` header despite the decision doc and `sprint-status.yaml`
both marking it ready; both were reconciled at dev-story start.

**Implementation vs. the architectural-decision doc:**
- Followed the confirmed design (absolute-epoch-ms `rint`, `timeUntilExpiry = rint - Date.now()`,
  5-min client `WARNING_THRESHOLD`, backend `ErrorDto` 401, `authStore`-clearing logout).
- **`rint` / JWT `exp` skew:** `TokenCreatorImpl.generateTokenFromClaims` computes `exp` as
  `now + JWT_TTL` at token-build time. To keep `rint` and `exp` in lock-step I compute the
  epoch once in `createAndSetJwt` and pass it into `createLoginCookies`. The JWT `exp` claim
  is a NumericDate **truncated to whole seconds**, so `rint` (full ms) and `claims.getExpiration()`
  agree only to within ~1 s — the tests assert `within(1_500L)`, not equality.
- **401 status codes unchanged.** The catch block still forces **401** for every caught type
  (`AccountStatusException | AuthorizationException | AccessDeniedException`); it does *not*
  route through `handlerExceptionResolver`/`@RestControllerAdvice` (which would remap
  `InvalidJWTDataException`/`JWTTheftException` → 403, `AccessDeniedException` → 403). Only the
  body changed: bare `sendError` → `ErrorDto{errorMsg:{errorKey,message}}`.
- **`SESSION_TTL` kept as `LEGACY_SESSION_TTL`** — a fallback for a session whose `rint`
  cookie is missing, rather than deleted outright (AC allowed either). `recordActivity()` /
  `lastActivityTime` now feed only that fallback.
- **Logout** mirrors `App.vue`'s `handleSessionExpired` exactly (clear `user`,
  `authStore.logout()`, `playerStore.resetSelfPlayerId()`, `cleanup()`, route) instead of the
  old direct `authApi.logout()` call — one logout request, not two.

**Manual traces (no frontend unit-test infra in this repo):**
- *Multi-tab (M1):* Tab A makes a request → response sets `rint = T2 > T1`. Tab B is idle;
  its next `tick()` (≤30 s later) calls `computeTimeUntilExpiry()` → reads the cookie → `T2 - now`
  is large → `showWarning=false`, no `session:expired`. Previously Tab B counted down its own
  `SESSION_TTL - elapsed` and fired at 15 min regardless of Tab A.
- *Logout (M2):* `handleLogout()` → `authStore.logout()` sets `document.cookie = 'skp=; Max-Age=0'`
  and `clearUser()` (so `userId=null`) synchronously, before `router.push('/login')`. The
  `requiresGuest` guard reads `authStore.isAuthenticated` (`!!userId`) → false → no bounce.
- *Expired-token 401:* token expires → next API call → `JWTAuthorizationFilter` catches
  `JWTExpiredException` → `writeUnauthorized` → body `{"errorMsg":{"errorKey":"security.sessionExpired",…}}`
  → axios interceptor gate `errorKey === 'security.sessionExpired'` fires → teardown + redirect.

**Validation:** targeted backend suites green (`JwtManagerImplTest` 32, `JWTAuthorizationFilterTest` 10,
`SecurityFilterChainIT` 4, `SecurityIT` 9, `AuthResourceIT` 9, `FamilyDataIsolationIT` 5);
`npx eslint` clean on the 5 changed/related frontend files; `npx quasar build` succeeded.
No local `mvn verify` (per `docs/validation-strategy.md`) — CI must run the full suite because
the auth-filter 401 body change is cross-cutting.

### File List

**Backend — behaviour:**
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` — `rint` = absolute JWT-expiry epoch ms; `maxAge = JWT_TTL + 60s`; `createLoginCookies` takes the expiry as a param
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` — `writeUnauthorized()` emits an `ErrorDto` 401 body; constructor gains `MessageSource` + `ObjectMapper`; class JavaDoc updated
- `src/main/java/com/softropic/skillars/platform/security/config/SecurityConfiguration.java` — `filterChain` injects + passes `MessageSource` + `ObjectMapper`
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` — `SESSION_REFRESH_COUNTDOWN` JavaDoc rewritten for the absolute-timestamp contract

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java` — 2 `rint` assertions → epoch-range; `testExtendTtlOfToken_success` asserts `rint` advances
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java` — 9-arg ctor + stubs; `sendError` → `setStatus` verifications; 2 body-`errorKey` assertions
- `src/test/java/com/softropic/skillars/platform/security/SecurityFilterChainIT.java` — no-token 401 body carries `errorKey":"security.unauthorized"`

**Frontend:**
- `src/frontend/src/plugins/sessionManager.js` — absolute-timestamp `rint` read, `WARNING_THRESHOLD` constant, unified `tick()`, `LEGACY_SESSION_TTL` fallback
- `src/frontend/src/composables/useSession.js` — `handleLogout()` clears `authStore` + `skp`; `authApi` import → `useAuthStore`
- `src/frontend/src/boot/axios.js` — response-interceptor `rint`-sync comments updated for the new contract; `syncWarningThresholdFromCookie` import renamed to `refreshExpiryState` (code review)
- `src/frontend/src/App.vue` — **added during code review (round 2):** `session:expired` listener now registers before `startSessionMonitoring()`, which can dispatch that event synchronously

**Docs:**
- `docs/session-refresh-mechanism.md` — updated end-to-end for the 1.7b contract

**Story artifacts:**
- `_bmad-output/implementation-artifacts/1-7b-session-refresh-rint-contract-fix.md` — Tasks/Subtasks added, checkboxes, this record, Status
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — status + `last_updated`
- `_bmad-output/implementation-artifacts/deferred-work.md` — **added during code review:** 4 round-1 + 6 round-2 deferred entries

### Change Log

| Date | Change |
|---|---|
| 2026-09-02 | Story 1.7b implemented. Backend: `rint` cookie is now the JWT's absolute expiry (epoch ms), advanced every authed response, `maxAge = JWT_TTL + 60s`; `JWTAuthorizationFilter` writes an `ErrorDto` 401 body (`security.sessionExpired` / `security.unauthorized`) instead of a bare `sendError`. Frontend: `sessionManager.js` reads `rint` as an absolute timestamp (`timeUntilExpiry = rint - Date.now()`), fixed 5-min `WARNING_THRESHOLD`, multi-tab sync falls out of the per-tick cookie re-read; `useSession.handleLogout()` clears `authStore` + `skp` (M2). Tests updated (M4). Docs updated. Header status `blocked-pending-decision` → `in-progress` → `review`; Tasks/Subtasks section synthesised from ACs + `1-7b-ARCHITECTURAL-DECISION.md`. |

---

## Files

- `1-7-session-refresh-mechanism-fix.md` (1.7a — documentation-only, ready-for-dev)
- `1-7-REVIEW-UPDATES.md` (summary of story split and false positive corrections)
- `1-7b-session-refresh-rint-contract-fix.md` (this file — behavioral fix, deferred)
- `1-7b-ARCHITECTURAL-DECISION.md` (confirmed design input for this story)
- `story-review.md` (senior dev review with full analysis)
