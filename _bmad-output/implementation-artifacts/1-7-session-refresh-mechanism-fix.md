# Story 1.7a: Session Refresh Documentation & Comments Fix

Status: done

## Story

As a developer maintaining session management code,
I want the session refresh mechanism documentation and inline comments to accurately describe what the code actually does,
so that future developers aren't misled by references to non-existent code paths and can understand the intended behavior.

## Executive Summary

The documentation (`docs/session-refresh-mechanism.md`) contains multiple references to non-existent code paths (`stores/session.js`, `useSessionStore()`, `useActivityTracking.js`, wrong component paths). Additionally, backend endpoint behaviors (`/refresh` vs `/api/auth/refresh`) are poorly documented. This story updates docs and inline comments to match the actual implementation. **Note:** The backend `rint` cookie contract fix is deferred to Story 1.7b (architectural decision needed first).

## Acceptance Criteria

### Documentation: Update `docs/session-refresh-mechanism.md` to Reflect Actual Implementation

**Given** a developer reads `docs/session-refresh-mechanism.md`
**When** they search for composables and stores referenced in the docs
**Then** all code paths reference actual files and are tested to exist:
  - Replace references to non-existent `stores/session.js` and `useSessionStore()` with actual `plugins/sessionManager.js` and `useSession()` composable (lines 89, 95, 282, 340, 471 in current doc)
  - Replace references to non-existent `composables/useActivityTracking.js` with explanation of what "activity" actually means (API requests only, not mouse/keyboard events) (line 393)
  - Correct path to `SessionWarningDialog.vue` from incorrect reference to `components/SessionWarningDialog.vue` → actual path `components/common/SessionWarningDialog.vue` (line 473)
**And** the documentation clearly states:
  - Session expiry is **sliding-window** (JWT re-issued with fresh full TTL on every authenticated request)
  - The warning threshold is a **client-side calculated constant** (5 minutes = 300,000 ms), not sent by the server
  - "Activity" means **API requests only** (axios interceptor), not user input monitoring
  - The `rint` cookie currently contains a fixed value (`JWT_TTL - 5 minutes`) that happens to work by coincidence
**And** all code examples are validated to match actual codebase patterns

### Backend Comments: Document `/refresh` vs `/api/auth/refresh` Distinction

**Given** a developer is reading backend security code
**When** they encounter `SessionRefreshFilter.java` or `JWTAuthorizationFilter.java`
**Then** inline JavaDoc comments clearly explain:
  - **`POST /api/auth/refresh`** (AuthResource): Performs full token rotation. Client presents refresh token → backend validates → issues new access token + refresh token pair. Body response contains new session state (LoginResponse).
  - **`GET /refresh`** (SessionRefreshFilter): Secured endpoint that ensures the JWT gets extended. Call this during an active session to keep it alive. Returns plain 200 status. Actual token extension happens in the filter chain before SessionRefreshFilter is reached.
  - Which to use when: Use `/api/auth/refresh` for login flows. Use `/GET /refresh` (via `sessionApi.refresh()`) to keep an active session alive without doing other work.
**And** comments reference the filter execution order in `SecurityConfiguration.java:211` to explain why `/refresh` is effective

### Backend Comments: Clarify `SecurityConstants.SESSION_REFRESH_COUNTDOWN` Usage

**Given** a developer reads `SecurityConstants.java:60`
**When** they see the comment `//Not used at the moment but Clients should actually use this...`
**Then** the comment is updated to:
  - "Used by `JwtManagerImpl` to set the `rint` cookie value (currently a fixed 600000 ms = 5 minutes before expiry)"
  - "Frontend reads this cookie to calculate session warning threshold"
  - "Note: This is a server-issued value for client reference; the warning threshold itself is calculated client-side"

### Frontend Code Quality (Minor): Fix Cookie Checks for Robustness

**Given** authentication state is checked
**When** code reads cookies with substring matching
**Then** fix two minor robustness issues (low priority, fold into code review comments):
  - `document.cookie.includes('user=')` at `App.vue:21` should use exact prefix check or read from parsed cookie object (current code would match `xuser=value`)
  - `config.url?.includes('/refresh')` at `axios.js:97` should use exact endpoint matching to avoid false positives (harmless today, but substring on user-influenced path is a code smell)

### Testing Checklist

- [x] All code paths referenced in updated `docs/session-refresh-mechanism.md` are verified to exist (16 paths file-checked)
- [x] Documentation examples are tested to match actual codebase patterns (examples lifted verbatim from `sessionManager.js`, `useSession.js`, `SessionWarningDialog.vue`, `boot/axios.js`, `App.vue`, `router/index.js`)
- [x] `/refresh` vs `/api/auth/refresh` distinction is clear in JavaDoc comments (`SessionRefreshFilter`, `AuthResource` class + method, `JWTAuthorizationFilter`)
- [x] `SESSION_REFRESH_COUNTDOWN` comment is updated and accurate (value corrected: 600000 ms = JWT_TTL − 5 min = 10 min; frontend derives a 5-min warning window from it)
- [x] New developer can understand session flow from docs without hitting non-existent paths (all `stores/session.js` / `useSessionStore` / `useActivityTracking.js` / wrong-path refs removed)

## Tasks / Subtasks

### Backend: Documentation & Comments

- [x] **Update `docs/session-refresh-mechanism.md`** (AC: Documentation: Update to Reflect Actual Implementation)
  - [x] Replace all references to non-existent `stores/session.js` and `useSessionStore()` with actual `plugins/sessionManager.js` — full rewrite; added a "Frontend implementation → Modules (actual paths)" table
  - [x] Replace references to non-existent `composables/useActivityTracking.js` with explanation that "activity" means API requests only (axios interceptor), not user input — see Overview + "boot/axios.js (interceptors)"
  - [x] Correct path to `components/common/SessionWarningDialog.vue` (currently wrong in docs)
  - [x] Add section explaining sliding-window JWT expiry (JWT re-issued with fresh TTL on every request) — Overview + lifecycle diagram + "Backend components" table
  - [x] Clarify that warning threshold is a **client-side constant**, not sent by server — "The `rint` cookie" section; documented 2-min fallback default + derived 5-min window
  - [x] Update any code examples to match actual file locations and API
  - [x] Test all code examples to ensure they match the codebase
  - [x] Sources to verify — the old line refs (89, 95, 282, 340, 471, 393, 473) were all in fabricated sections now deleted by the rewrite

- [x] **Add JavaDoc & Comments to Backend Security Files** (AC: Backend Comments: Document `/refresh` vs `/api/auth/refresh` Distinction)
  - [x] Updated `com.softropic.skillars.platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter` — added "Sliding-window session keep-alive" class-JavaDoc section explaining TTL extension, the 5-min DB re-auth, `rint`/`user`/`potc` cookie rewrites, and why `GET /refresh` works (filter order). *(Note: actual package is `platform.security…`, not `infrastructure.security.infrastructure…` as the story draft had.)*
  - [x] Updated `com.softropic.skillars.platform.security.infrastructure.filter.SessionRefreshFilter` — rewrote class JavaDoc: purpose (secured no-op), when to use, plain-200 return, relationship to `JWTAuthorizationFilter`, filter-ordering wiring reference (`SecurityConfiguration#filterChain`)
  - [x] Updated `com.softropic.skillars.platform.security.api.AuthResource` — added class JavaDoc contrasting the two refresh paths + method JavaDoc on `refresh()` (full rotation, `LoginResponse`, login-flow usage)

- [x] **Update `SecurityConstants.SESSION_REFRESH_COUNTDOWN` Comment** (AC: Backend Comments: Clarify Usage)
  - [x] File: `com.softropic.skillars.infrastructure.security.SecurityConstants.java:60`
  - [x] Replaced `//Not used at the moment but Clients should actually use this...` with a JavaDoc block: it **is** used by `JwtManagerImpl.createLoginCookies`; fixed value 600000 ms (`JWT_TTL − 5 min` = 10 min, **not** "5 minutes"); frontend derives the 5-min warning window as `JWT_TTL − rint`; server-issued reference only, threshold computed client-side; absolute-timestamp redesign deferred to 1.7b
  - [x] Also added an inline comment at the `rint` cookie write in `JwtManagerImpl.createLoginCookies` (in scope per story Dev Notes "Key Files to Update")

### Frontend: Code Quality (Low Priority)

- [x] **Fix Cookie Substring Checks** (AC: Frontend Code Quality)
  - [x] `src/frontend/src/App.vue` — `isAuthenticated()` now `document.cookie.split(';').some((c) => c.trim().startsWith('user='))` (mirrors existing `hasFingerprintCookie()` style in `boot/axios.js`); avoids matching `xuser=`
  - [x] `src/frontend/src/boot/axios.js` — replaced `config.url?.includes('/refresh')` with exact path match `(config.url || '').split('?')[0] !== '/refresh'`

### Verification Tasks

- [x] **Verify Documentation Paths Exist**
  - [x] `src/frontend/src/plugins/sessionManager.js` exists
  - [x] `src/frontend/src/composables/useSession.js` exists
  - [x] `src/frontend/src/components/common/SessionWarningDialog.vue` exists
  - [x] All backend file paths use correct package structure — verified real paths are `com.softropic.skillars.platform.security.*` (+ `infrastructure.security.SecurityConstants`); doc "Backend components" table and all JavaDoc `{@link}`s use these

- [x] **Validate Code Examples in Documentation**
  - [x] Every code example in updated `docs/session-refresh-mechanism.md` traced to its source file
  - [x] Examples use correct import paths (`src/composables/useSession`, `src/plugins/sessionManager`, `src/api/session.api`) and API calls (`sessionApi.refresh()` → `GET /refresh`; `authApi.skillarsRefresh()` → `POST /api/auth/refresh`)

### Review Findings

_bmad-code-review, 2026-09-02. Layers: Blind Hunter (diff-only), Edge Case Hunter (diff + source verification), Acceptance Auditor (diff + spec). 3 decision-needed, 14 patch, 8 deferred, 5 dismissed as noise. AC coverage: all ACs MET or MET-with-justified-deviation; **zero scope creep** — no 1.7b behavioural work implemented._

- [x] [Review][Decision] `POST /api/auth/refresh` silently flipped from "not activity" to "activity" — The old `config.url?.includes('/refresh')` also matched `/api/auth/refresh` (`src/frontend/src/api/auth.api.js:45-47`, `authApi.skillarsRefresh()`), so token rotation did NOT reset `lastActivityTime`. The new exact match no longer skips it. Latent today (`grep -rn "skillarsRefresh" src/` finds only the definition — no callers), but the moment background rotation is wired up it defeats idle-timeout detection. Spec-sanctioned (AC F2 asked for exact matching) yet contradicts the "no behavioural change" claim. **RESOLVED (option a):** keep the exact match as-is and disclose the behaviour change in Completion Notes + `sprint-status.yaml`. → converted to patch.
- [x] [Review][Decision] `SESSION_REFRESH_DRIFT_ANALYSIS.md` committed at repo root carrying superseded, explicitly-rejected guidance — 248-line new file staged as `A`. Its headline "fix" (`String.valueOf(timeUntilExpiry)` where `timeUntilExpiry = JWT_TTL.minusMinutes(5).toMillis()`) is byte-identical to the code it condemns; its `maxAge = JWT_TTL.toSeconds()` recommendation was adjudicated wrong in `story-review.md` and replaced by the absolute-epoch design in `1-7b-ARCHITECTURAL-DECISION.md:14-26`; its claim that "App.vue might not mount on direct navigation" is impossible (App.vue is the SPA root). Still flagged `⚠️ DRIFT DETECTED` with line citations into content this commit deleted. **RESOLVED (option a):** do not commit it — unstage and delete the file. Its surviving content is superseded by `story-review.md` and `1-7b-ARCHITECTURAL-DECISION.md`. → converted to patch.
- [x] [Review][Decision] No `/refresh` dev-proxy rule — keep-alive never reaches Spring in development — `src/frontend/quasar.config.js:94-122` proxies `/v1/account`, `/v1/admin`, `/v1/api`, `/api/v1`, `/api`, `/authenticate`, `/manage` but **not** `/refresh`. Under `quasar dev`, `GET /refresh` hits the Vite SPA fallback and returns 200 + `index.html`; `refreshSession()` (`sessionManager.js:158-161`) sees a resolved promise, calls `recordActivity()`, clears the warning — and the JWT is never extended. Production is fine (Spring serves the SPA). The doc's keep-alive sequence diagram presents one behaviour for both. This is also why the axios change could not have been caught by manual dev testing. **RESOLVED (option a):** add the `/refresh` proxy rule to `quasar.config.js` now. Accepted as a deliberate widening of 1.7a beyond comments-only, since the doc actively claims a flow that does not work in dev. → converted to patch.

- [x] [Review][Patch] JavaDoc "(no DB hit)" is false — `isRefreshTokenRevoked` queries the repo on every request when an `rtkn` cookie is present [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java:61-62]
- [x] [Review][Patch] JavaDoc attributes DB re-authorisation to `renewLoginToken`; it is actually `daoAuthProvider.authorize` at :145-149 — `renewLoginToken` (`JwtManagerImpl.java:58-65`) touches no repository [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java:63-65]
- [x] [Review][Patch] Same "no DB hit / DB re-auth instead of TTL bump" error propagated into the doc [docs/session-refresh-mechanism.md:62, :315]
- [x] [Review][Patch] "dies when the tab closes" is wrong — a `maxAge=-1` session cookie is browser-scoped, survives tab close, is shared across tabs, and survives restart under session-restore [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:228-229, docs/session-refresh-mechanism.md:137]
- [x] [Review][Patch] Sequence diagram says "(13 min idle)" — leftover from the removed 2-minute threshold; the dialog fires at 10 min idle under the 5-minute window [docs/session-refresh-mechanism.md:330]
- [x] [Review][Patch] Doc contradicts itself on 401 recovery — ":309 says the interceptor/`session:expired` path takes over"; :388 says that `errorKey` gate never fires for expired tokens (confirmed: `boot/axios.js:143-144` gates on `errorKey`, `JWTAuthorizationFilter.java:118` sends a bodyless `sendError`) [docs/session-refresh-mechanism.md:309]
- [x] [Review][Patch] "Response (success and error): the auth filter refreshed `rint` before we got here" — false on the 401/expired path, where cookies are cleared rather than refreshed [docs/session-refresh-mechanism.md:260]
- [x] [Review][Patch] Module table omits `authApi.logout()` → `POST /api/logout` (`auth.api.js:29-30`), which is what `handleLogout()` at :214 actually calls — table lists only `skillarsLogout()` → `POST /api/auth/logout` [docs/session-refresh-mechanism.md:175]
- [x] [Review][Patch] Cookie table claims to show "what the code actually sets" but omits `Secure` entirely and labels `SameSite=Lax` on only two rows — `CookieUtil.java:22-23` applies `.secure(...isHttps())` (off over plain HTTP) and `.sameSite("Lax")` to every cookie [docs/session-refresh-mechanism.md:~969 block]
- [x] [Review][Patch] Troubleshooting entry self-contradicts in consecutive clauses ("must be making API calls for the monitor to run meaningfully" vs "a genuinely idle tab *will* warn and then expire") [docs/session-refresh-mechanism.md:359]
- [x] [Review][Patch] JavaDoc over-specifies "GET /refresh" — `PathPatternRequestMatcher.withDefaults().matcher(endpoint)` binds no `HttpMethod` (`SessionRefreshFilter.java:45`), so POST/PUT/DELETE `/refresh` short-circuit to 200 identically [src/main/java/com/softropic/skillars/platform/security/infrastructure/filter/SessionRefreshFilter.java:13, src/main/java/com/softropic/skillars/platform/security/api/AuthResource.java:29]
- [x] [Review][Patch] axios comment's rationale ("so a background `/refresh` doesn't register as user activity") is defeated by `sessionManager.js:158-161`, which calls `recordActivity()` unconditionally after the same call succeeds — and no background poller exists [src/frontend/src/boot/axios.js:96-98]
- [x] [Review][Patch] `{@link ...JwtManagerImpl#createLoginCookies}` targets a `private` cross-package method (`JwtManagerImpl.java:209`) — renders no hyperlink; use `{@code}` as done elsewhere in this change. No build gate (`pom.xml` has no `maven-javadoc-plugin`/doclint) [src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java:62-63]
- [x] [Review][Patch] File List misdescribes two changed files as "Reference (pre-existing, not modified)" — `SESSION_REFRESH_DRIFT_ANALYSIS.md` is a new file (+248) and `story-review.md` was rewritten (422 lines); Change Log omits both [_bmad-output/implementation-artifacts/1-7-session-refresh-mechanism-fix.md:244, :266]
- [x] [Review][Patch] Completion Notes and `sprint-status.yaml` both claim "no behavioural change" while two live predicates changed; the axios change is a real (if latent) behaviour flip [_bmad-output/implementation-artifacts/1-7-session-refresh-mechanism-fix.md:212, _bmad-output/implementation-artifacts/sprint-status.yaml:38]

- [x] [Review][Defer] Three sibling docs still assert `rint` TTL = 15 min [docs/frontend-integration-guide.md:1164, docs/security-api-endpoints.md:252, docs/frontend-implementation-spec.md:243] — deferred, pre-existing (outside the AC's named file; `frontend-integration-guide.md:3684` links to the rewritten doc, so a reader lands on two contradictory TTLs)
- [x] [Review][Defer] `docs/dev-docs/index.html:152` still summarises the doc as "deep dive on the JWT refresh-token rotation flow" — deferred, pre-existing (no longer what the doc covers)
- [x] [Review][Defer] `user=` with an empty value counts as authenticated — `JwtManagerImpl.java:215-216` writes `USER_COOKIE` from the `displayName` claim with no null guard; `ResponseCookie.from(name, null)` coerces to `""` [src/frontend/src/App.vue:22] — deferred, pre-existing (the changed line did not introduce it)
- [x] [Review][Defer] Prettier fails on `App.vue` and `boot/axios.js` at `HEAD` as well as in the working tree, violating the repo-wide mandatory-Prettier rule [_bmad-output/project-context.md:69] — deferred, pre-existing
- [x] [Review][Defer] `SESSION_CHECK_INTERVAL` = 30 s means the "5 minutes before expiry" figure is ±30 s, but doc and JavaDoc both state it as exact [docs/session-refresh-mechanism.md:67] — deferred, pre-existing (any test written against the exact figure is flaky by construction)
- [x] [Review][Defer] 1.7b promoted to `ready-for-dev` while its own stated prerequisite ("Manual JWT expiry check — run before 1.7b starts") is unclosed [_bmad-output/implementation-artifacts/1-7-REVIEW-UPDATES.md:165] — deferred, pre-existing (B3's diagnosis independently confirmed correct at `JWTAuthorizationFilter.java:115-119`, so the premise holds; only the checklist item is open)
- [x] [Review][Defer] Story file and all three 1.7b artifacts are untracked while `sprint-status.yaml` already references them [_bmad-output/implementation-artifacts/] — deferred, pre-existing (staging is the author's call, not a review patch)
- [x] [Review][Defer] `story-review.md` is a rotating single file — this commit discards the 358-line `skillars-deferred-89` senior-dev audit [_bmad-output/implementation-artifacts/story-review.md] — deferred, pre-existing (follows established convention; per-story review files would be the process fix)

## Dev Notes

### Session Architecture (Important Context)

**Sliding-Window JWT Expiry:** The JWT is re-issued with a **fresh full TTL on every authenticated request** (not a countdown). This means:
- `Claims.getExpiration()` is always ~15 minutes in the future at response time
- The `rint` cookie always contains approximately the same value (~600000 ms) at each response
- The "countdown" to expiry happens on the **client side** based on time elapsed since last request, not from the server's `rint` value
- This is intentional design — it keeps active users logged in and only warns idle users

**Session Cookies:**
- `user=` (HttpOnly: false): JWT token, 15-min TTL, re-issued on every authenticated request
- `rtkn`: Refresh token, HttpOnly: true, 7-day TTL, only issued on login/refresh endpoints
- `skp`: Session summary for hydration (HttpOnly: false), URL-encoded JSON `{"id":..., "role":...}`, 7-day TTL
- `rint`: Session refresh countdown reference (HttpOnly: false), fixed value of ~600000 ms, browser-session TTL (expires when tab closes)
- Note: Not all are `HttpOnly; Secure` as previously stated. See review findings N6.

**Frontend Session Monitoring:**
- Plugin: `src/frontend/src/plugins/sessionManager.js` — standalone module with ref-based state
- Composable: `src/frontend/src/composables/useSession.js` — wrapper for accessing session state
- Check interval: `SESSION_CHECK_INTERVAL = 30000` (30 seconds)
- Warning threshold: `DEFAULT_WARNING_THRESHOLD = 2 * 60 * 1000` (2 minutes, not 5 as docs claim)
- Activity is tracked **only via API requests**, not user input (mouse/keyboard)

### Key Files to Update (Corrected Paths)

**Backend:**
- `src/main/java/com/softropic/skillars/infrastructure/security/infrastructure/jwt/JwtManagerImpl.java` — Add comment explaining current `rint` behavior
- `src/main/java/com/softropic/skillars/infrastructure/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` — Add comment about JWT extension
- `src/main/java/com/softropic/skillars/infrastructure/security/infrastructure/filter/SessionRefreshFilter.java` — Add JavaDoc about endpoint purpose
- `src/main/java/com/softropic/skillars/infrastructure/security/api/AuthResource.java` — Add JavaDoc for `/api/auth/refresh`
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` — Update comment on `SESSION_REFRESH_COUNTDOWN`

**Frontend:**
- `src/frontend/src/plugins/sessionManager.js` — No changes in this story (monitored for future fix)
- `src/frontend/src/composables/useSession.js` — No changes in this story
- `src/frontend/src/App.vue` — No changes in this story
- `src/frontend/src/boot/axios.js` — Minor: fix cookie substring check
- `docs/session-refresh-mechanism.md` — Complete update with correct paths and architecture explanation

### Testing & Verification

- **No code changes require tests** in this story (documentation-only)
- **Manual verification needed:**
  - After updating docs, validate that every code example path exists and matches current codebase
  - Verify package structure is `com.softropic.skillars...` not `com.skillars...`

### Unresolved Issues (Deferred to Story 1.7b)

The review identified several architectural issues that require design decisions and should NOT be addressed in this story:

1. **`rint` contract** — Currently a fixed value that "works by coincidence". Future story should decide: keep fixed value, or change to absolute expiry timestamp?
2. **Idle background tabs force-logging-out active tabs** (M1 in review) — Affects multi-tab scenarios
3. **Session warning dialog logout button doesn't fully clear session** (M2 in review) — `skp` cookie survives logout
4. **401 error path mismatch** (B3 in review) — Filter emits plain 401, frontend expects `ErrorDto`

These are real defects but require architectural decisions first. Story 1.7b will address them.

### Previous Story Intelligence

**Story 1.5 (Authentication & JWT Security)** established JWT-based auth with refresh tokens. This story documents the existing session warning mechanism without changing its behavior. The mechanism "works by luck" because the backend happens to hardcode the exact 5-minute warning threshold the frontend expects.

### Source References & Review Notes

- **Original Analysis:** `SESSION_REFRESH_DRIFT_ANALYSIS.md` — drift analysis that seeded this story; **deleted during code-review follow-up** (review decision D2: its concrete recommendations were superseded/adjudicated-wrong by `story-review.md` and `1-7b-ARCHITECTURAL-DECISION.md`). It was staged but never committed, so recovery is only via `git fsck` until gc.
- **Senior Dev Review:** `story-review.md` — Identified 3 blocking issues, 4 major issues, 6 minor issues
- **Story Split Decision:** This story (1.7a) focuses on safe documentation/comments fixes. Backend behavioral changes deferred to Story 1.7b pending architectural decision on `rint` contract
- **Code Files Referenced:**
  - `src/frontend/src/plugins/sessionManager.js` — Session state management
  - `src/frontend/src/composables/useSession.js` — Session composable wrapper
  - `src/frontend/src/components/common/SessionWarningDialog.vue` — Warning UI
  - `src/frontend/src/boot/axios.js` — HTTP interceptor
  - `docs/session-refresh-mechanism.md` — Documentation to update (currently has non-existent paths)

## Dev Agent Record

### Review Updates Applied

Based on senior dev review feedback:

**False Positives Fixed:**
- N1: Corrected all backend package paths from `com.skillars` → `com.softropic.skillars`
- N2: Corrected default warning threshold from "5 minutes" → "2 minutes" (as per DEFAULT_WARNING_THRESHOLD)
- N3: Acknowledged that `rint` validations already partially exist
- N4: Removed incorrect `beforeunload` task; cross-tab communication deferred to 1.7b
- N5: Removed untestable ACs (marked existing redirect as verify-only)
- N6: Fixed factual errors in Dev Notes about cookie security attributes

**Blocking Issues Addressed by Story Split:**
- B1-B3: Deferred to Story 1.7b which will redesign `rint` contract as absolute timestamp
- M1-M4: Deferred to Story 1.7b (architectural decisions needed first)

**This Story (1.7a) Scope:**
- ✓ Update documentation to reference actual code paths
- ✓ Add JavaDoc/comments to backend endpoints
- ✓ Fix package path errors in docs
- ✓ Clarify sliding-window JWT expiry architecture
- ✓ Document what "activity" actually means (API requests only)

### Agent Model Used

Claude Haiku 4.5 (story drafting) · Claude Sonnet 5 (implementation)

### Completion Notes

Primarily a documentation-and-comments story, plus two small frontend robustness fixes and
(after code review) one dev-only config addition. **Behavioural surface:** the `boot/axios.js`
change flips one predicate — `POST /api/auth/refresh` (via `authApi.skillarsRefresh()`) now
resets the idle timer where the old substring test skipped it. That helper currently has no
callers (`grep -rn "skillarsRefresh" src/` → definition only), so the effect is latent, but
it is a real change and is no longer described as "no behavioural change". No new tests
(none exist for these frontend files; Java changes are comment-only). Implemented by first
reading every referenced source file so the doc and comments describe the code as it
actually is on `master`.

**Corrections made to the story's own assumptions (all verified against source):**
- Real backend package is `com.softropic.skillars.platform.security.*` (the story draft's
  "corrected paths" still said `infrastructure.security.infrastructure.*`). Only
  `SecurityConstants` lives under `infrastructure.security`.
- `rint` cookie value is `JWT_TTL.minusMinutes(5).toMillis()` = **600000 ms = 10 minutes**
  (`JWT_TTL − 5 min`), not "5 minutes" as several ACs worded it. The **5-minute** figure is
  the *warning window the frontend derives* as `SESSION_TTL − rint` (900000 − 600000). Comments
  and doc state both numbers precisely.
- The `user` cookie holds the **display name**, not the JWT. The JWT is the HttpOnly `potc`
  cookie. Doc's cookie table corrected accordingly.
- `rtkn` / `skp` cookies are issued by `AuthService` (`POST /api/auth/login` and
  `POST /api/auth/refresh`), 7-day TTL, `SameSite=Lax` — not by the JWT filter. `GET /refresh`
  does **not** rotate them.
- Frontend fallback `DEFAULT_WARNING_THRESHOLD` is 2 min and applies only until the first
  `rint` cookie is read; after that the threshold becomes the derived 5 min.

**Validation:**
- `npx eslint src/App.vue src/boot/axios.js` → clean (exit 0).
- `npx prettier --check` on those two files reports the **pre-existing** repo-wide
  semicolon/no-semicolon disagreement (both files use semicolons throughout; current prettier
  config wants none). Not introduced by this story and out of scope; added lines follow the
  files' established local style. Confirmed pre-existing by re-checking against `HEAD` (stash).
  (Also logged as a review [Defer] item.)
- Java changes are comment/JavaDoc-only; block-comment delimiters verified balanced. No
  `mvn verify` (per `docs/validation-strategy.md`; no Java behavioural change).
- `quasar.config.js` proxy addition is dev-server config only — not exercised by the build.
- All file paths referenced in the rewritten doc verified to exist on disk.

### Review Follow-ups Applied

bmad-code-review (2026-09-02) raised 3 decision-needed + 14 patch items; all 17 applied
(8 [Defer] items acknowledged as pre-existing/out-of-AC, 5 dismissed as noise).

**Decisions (all resolved → applied):**
- D1 — kept the axios exact-match as spec'd (AC F2); disclosed the resulting behaviour flip
  in Completion Notes, Change Log and `sprint-status.yaml` (removed the blanket "no
  behavioural change" claim).
- D2 — deleted `SESSION_REFRESH_DRIFT_ANALYSIS.md` (`git rm`). It was staged-but-never-committed,
  so there is no commit history for it; the staged blob is still recoverable via
  `git fsck --lost-found` until gc, and its substantive content is preserved in
  `story-review.md` and `1-7b-ARCHITECTURAL-DECISION.md` (and quoted in review finding D2).
  **Note:** the original file was authored before this dev-story run, not by it — deletion
  was done on the code review's explicit instruction, not unilaterally.
- D3 — added a `/refresh` proxy rule to `src/frontend/quasar.config.js` so `quasar dev`
  actually forwards the keep-alive call to Spring; documented the dev-vs-prod gap.

**Patches (JavaDoc/comment/doc accuracy):**
- P1/P2/P3 — corrected the "fast path = no DB hit / `renewLoginToken` re-authorises"
  claim in `JWTAuthorizationFilter` JavaDoc + doc: fast path still runs an
  `isRefreshTokenRevoked` lookup when `rtkn` is present; the DB re-auth is
  `daoAuthProvider.authorize`, not `renewLoginToken`.
- P4 — `rint`/session-cookie semantics: `Max-Age=-1` is shared across tabs and survives
  tab close (not "dies when the tab closes"); fixed in `JwtManagerImpl` comment + doc.
- P5 — sequence diagram idle time `13 min` → `~10 min` (warning = 5 min before the 15-min TTL).
- P6 — doc no longer contradicts itself on 401 recovery; the guard-note now points at the
  bodyless-401 gap.
- P7 — "auth filter refreshed `rint` before we got here" scoped to success/non-401 (the
  401 path clears cookies).
- P8 — doc module table now lists `authApi.logout()` → `POST /api/logout` (what
  `handleLogout()` actually calls), alongside `skillarsLogout()`.
- P9 — cookie table: added the universal `SameSite=Lax` + conditional `Secure`
  (`CookieUtil` applies both to every cookie).
- P10 — reworded the self-contradicting "Warning dialog never appears" troubleshooting entry.
- P11 — `/refresh` matcher binds no HTTP verb; JavaDoc + doc no longer imply `GET`-only.
- P12 — axios comment rationale corrected (`refreshSession()` records activity explicitly
  on success; no background poller exists).
- P13 — `{@link …JwtManagerImpl#createLoginCookies}` (private, cross-package) → `{@code}`.
- P14 — File List below corrected (see categories); Change Log updated.
- P15 — see D1 (behavioural-change disclosure).

### File List

**Modified — Java, comments / JavaDoc only (no behaviour change):**
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` — `SESSION_REFRESH_COUNTDOWN` JavaDoc (+ P13)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` — inline comment at the `rint` cookie write (+ P4)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` — class-JavaDoc "Sliding-window session keep-alive" section (+ P1/P2)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/filter/SessionRefreshFilter.java` — rewritten class JavaDoc (+ P11)
- `src/main/java/com/softropic/skillars/platform/security/api/AuthResource.java` — class JavaDoc + `refresh()` method JavaDoc (+ P11)

**Modified — frontend:**
- `src/frontend/src/App.vue` — `isAuthenticated()` exact cookie-name match
- `src/frontend/src/boot/axios.js` — `/refresh` exact path match instead of substring (latent behaviour change — see Completion Notes); comment reworded (P12)
- `src/frontend/quasar.config.js` — added `/refresh` dev-server proxy rule (review D3)

**Modified — documentation:**
- `docs/session-refresh-mechanism.md` — full rewrite to match the actual implementation, plus review patches P3–P11

**Deleted:**
- `SESSION_REFRESH_DRIFT_ANALYSIS.md` — superseded drift analysis (review D2). Was staged, never committed; recoverable via `git fsck` until gc.

**Story artifacts modified:**
- `_bmad-output/implementation-artifacts/1-7-session-refresh-mechanism-fix.md` — this file (checkboxes, Dev Agent Record, File List, Change Log, Status)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — status + `last_updated`

**Reference (not modified by this story's implementation):**
- `_bmad-output/implementation-artifacts/story-review.md` — senior-dev review; already modified in the working tree before this story began (rotating single-file convention — logged as a review [Defer])
- `_bmad-output/implementation-artifacts/1-7-REVIEW-UPDATES.md` — story-split summary
- `_bmad-output/implementation-artifacts/1-7b-session-refresh-rint-contract-fix.md` — deferred behavioural changes

### Change Log

| Date | Change |
|---|---|
| 2026-09-02 | Story 1.7a implemented: `docs/session-refresh-mechanism.md` rewritten to match actual code; JavaDoc/comments added to `SecurityConstants`, `JwtManagerImpl`, `JWTAuthorizationFilter`, `SessionRefreshFilter`, `AuthResource`; cookie-substring checks tightened in `App.vue` and `boot/axios.js`. Status → review. |
| 2026-09-02 | Code-review follow-ups applied — 3 decisions + 14 patches. Deleted `SESSION_REFRESH_DRIFT_ANALYSIS.md` (D2); added `/refresh` dev proxy to `quasar.config.js` (D3); corrected JavaDoc/doc accuracy (fast-path DB lookup, session-cookie scope, 401 recovery, cookie flags, `/refresh` verb, module table). Disclosed the latent `boot/axios.js` behaviour flip (`POST /api/auth/refresh` now counts as activity); dropped the blanket "no behavioural change" wording. Status stays review. |
