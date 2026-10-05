# skillars-deferred-142: OTP skp-Cookie Gap, Video-Page Redirect Fix, and Stale-Env Ops Note

**Story ID:** deferred-142
**Epic:** Deferred-Work Cleanup Bundle
**Status:** done
**Scope Level:** Small (one backend cookie-issuance fix + its test coverage, two small frontend redirect fixes, one ops doc note; no schema changes)
**Date Created:** 2026-10-04
**Pre-Implementation Review:** `story-review.md` completed 2026-10-04 against HEAD `9abe7058`. Found 4 citation drifts that also carried a substantive error (the method the story called `AuthService.authenticate()` is actually `AuthService.login()`), two HIGH findings that changed AC2's design (a false "only caller" claim, and a latent claim-corruption hazard the original new-JWT-claim design would have introduced), one HIGH finding that the AC5 ledger-deletion target doesn't exist as a standalone open item, and several MEDIUM gaps (test fixture proves only the fallback path, AC1's "nothing reads them" claim is false, AC3 is vacuously satisfiable, a departure from the sourcing ledger bullet's own sequencing). All addressed below — see "Resolution of pre-implementation review" at the end of this file for the full disposition.
**Source:** `_bmad-output/implementation-artifacts/deferred-work.md` — a bundle of small, independently-verified items selected to be grouped into one cleanup story (in the spirit of prior bundle stories like `skillars-deferred-100`/`-102`/`-124`), plus a widened scope discovered while drafting this story (see AC2 below) and one ledger correction (see AC5).

---

## User Story

As **an operator maintaining the deployed server**, I want a documented note about which already-provisioned nodes may still carry stale, unused secrets in their live `.env` file, so that **a future operator doesn't waste time investigating a harmless leftover as if it were a live misconfiguration**.

As **a user who completes two-factor (OTP) login**, I want the app to actually know my role afterward and land me on my own role's page (not an admin-only page I can't see any navigation for), so that **2FA login works the same way password-only login already does**.

As **a PLAYER whose video list fails to load with a 403**, I want to be redirected to a page I can actually use, so that **I don't land on a dead end with no menu entry for my role**.

---

## Tasks/Subtasks

- [x] Task 1: AC1 — Ops note for stale `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` on already-provisioned nodes
  - [x] Add a new section to `docs/deployment/secrets-reference.md`, immediately after the existing `## Accepted credential-exposure surface` section (`:398-428`), documenting the stale-value gap and its **real** inertness mechanism (see Context below — not "nothing reads them")
  - [x] No script change; no behavior change
- [x] Task 2: AC2 — Backend: make OTP (2FA) completion actually set the `skp` cookie
  - [x] `JwtManagerImpl.refreshLoginToken()` (`:68-83`): after `createAndSetJwt(res, claims)`, derive the caller's `SkillarsRole` **from the already-present `ROLES` claim** (via the existing private `getAuthoritiesSilently(claims)` method, `:97-104`) — do **not** add a new JWT claim (see "Design decision: derive from `ROLES`, don't add a new claim" below)
  - [x] Map each returned authority's name, with the `"ROLE_"` prefix stripped, against `SkillarsRole.valueOf(...)` (catch `IllegalArgumentException` per candidate — `ROLE_LTD_ADMIN`/`ROLE_USER` do not match); take the first successful match
  - [x] If no authority maps to a `SkillarsRole`, fall back to a new `SkillarsRole.ANONYMOUS` constant (never a hardcoded string) — **not** `AuthService.login()`/`refresh()`'s `"ADMIN"` fallback: that fallback fires for a different population (a real admin with an unset DB role column), while this one fires for a non-admin authority with no `SkillarsRole` equivalent (e.g. `ROLE_LTD_ADMIN`/`ROLE_USER`) — labeling that caller `"ADMIN"` would surface admin-only nav/UI to them. **Never emit the Java string `"null"` into the cookie.**
  - [x] Build and set the `skp` cookie from `claims.get(BUS_ID)` + the resolved role string, replicating `AuthService.login()`'s exact JSON-building/URL-encoding (`{"id":"<id>","role":"<ROLE>"}`, quoted id, `:131` + `:133-134`) and `CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue, false, (int) REFRESH_TOKEN_TTL.toSeconds())`
  - [x] Do **not** touch `createLoginCookies`, `TokenCreatorImpl.toClaims`, `SecurityConstants`, or `ClaimsExtractorImpl` — this fix is self-contained inside `refreshLoginToken`
  - [x] Extend `SecurityIT.loginWith2FAWhenAccountEnabled()` (`src/test/java/.../security/SecurityIT.java:189-287`) to assert the OTP response's `Set-Cookie` headers also include `SKILLARS_PROFILE_COOKIE` ("skp") — **presence only**; this IT's fixture (`getUserData`, `:350-365`) never sets a `SkillarsRole`, so it only proves the cookie exists and carries the `ANONYMOUS` fallback, not the real-role mapping. **Mutation-check this**: confirm the new assertion genuinely fails against the unfixed code before adding the fix.
  - [x] Add a new case to `JwtManagerImplTest` using its existing `createPrincipal(authorities)` helper (`:169-182`) with a real role-shaped authority (e.g. `Set.of(new SimpleGrantedAuthority("ROLE_COACH"))`) to prove the actual `ROLES` → `SkillarsRole` mapping end-to-end — this is the one test in the suite that can prove the mapping without a full Spring Security IT round-trip
- [x] Task 3: AC3 — Frontend: `OtpPage.vue` role-aware redirect
  - [x] After `initSession()` in `handleSubmit()`, call `authStore.hydrateFromCookie()` (now meaningful once Task 2 ships — the `skp` cookie it reads will actually be freshly set)
  - [x] Change the `redirectPath` fallback from `route.query.redirect || '/dashboard'` to use `routeForRole(authStore.role)` as the fallback, importing `routeForRole` from `src/router/roleRoutes.js` (mirrors `LoginPage.vue:168-175`)
  - [x] Match `LoginPage.vue`'s open-redirect guard on `route.query.redirect` (`redirect.startsWith('/') && !redirect.startsWith('//')`) — `OtpPage.vue` currently has none at all on this query param
  - [x] Add a Vitest spec (`src/frontend/src/pages/auth/__tests__/OtpPageSpec.js` — no existing spec file for this page) covering: (1) redirect fallback resolves per role after a successful verify; (2) the unsafe/absolute `redirect` query value is rejected in favor of the role fallback; (3) **when `skp` is absent/unparseable (hydration yields no role), the fallback correctly lands on `/dashboard` via `routeForRole(null)` → `DEFAULT_ROUTE`** — assert this explicitly as the intended degraded behavior, not an accidental gap
- [x] Task 4: AC4 — Frontend: `VideoManagementPage.vue` 403 redirect target
  - [x] `VideoManagementPage.vue:108`: change `router.replace('/dashboard')` to `router.replace('/player/dashboard')` inside `fetchVideos()`'s 403 handler
  - [x] Add/extend a Vitest spec covering: a 403 from `getMyVideos()` redirects to `/player/dashboard`, not `/dashboard`
- [x] Task 5: AC5 — Testing & verification
  - [x] Backend: targeted run of `SecurityIT`, `JwtManagerImplTest`, `AuthServiceTest`/`AuthServiceIT` (unaffected paths — confirm no regression)
  - [x] Frontend: `cd src/frontend && npm run test:unit` — full suite green, plus the new/extended specs above
  - [x] ESLint/Prettier clean on all touched frontend files
  - [x] No local `mvn verify` — GitHub CI is the sole full-verification gate (standing project convention)
  - [x] Manual browser verification is **not meaningfully performable** for AC2/AC3: confirmed (see Context) that neither `/otp` nor its own trigger endpoint (`/authenticate`) is reachable from any live UI flow today. Verification rests on the IT/unit coverage in Task 2/3, not a browser walkthrough.

**Dropped from scope — see AC5 note under "Resolution of pre-implementation review":** the original Task 5/AC5 ("delete the stale `ses-1-7-documentation` ledger bullet") has been removed. The pre-implementation review found there is no standalone open-work bullet to delete — the matching text lives inside a historical `## Last audit:` narrative block, which this project's own ledger convention retains as permanent history rather than deleting. No ledger edit is needed.

---

## Review Findings

**Code review completed 2026-10-05** (`/bmad-code-review`, Opus 5). Three parallel layers — Blind
Hunter (diff-only), Edge Case Hunter (diff + project), Acceptance Auditor (diff + spec + context) —
then orchestrator triage with independent re-verification of every load-bearing claim against HEAD.

**This review supersedes the earlier `mto-code-review` pass (Haiku 4.5).** All three of that pass's
items were re-adjudicated from source: **two were false positives** (see Dismissed, items 1 and 12)
and one was confirmed and found to understate the problem (now P6). Raw totals: 32 findings raised
across the three layers → 2 decision-needed, 8 patch, 9 defer, 15 dismissed.

**All 5 ACs independently verified TRUE**, including a character-by-character comparison of the new
`skp` cookie against both existing `AuthService` sites and a full re-count of every numeric test
claim in the Completion Notes (`JwtManagerImplTest` 35, `SecurityIT` 9, Vitest 208/208 across 30
files, ESLint/Prettier clean — all re-run and confirmed exact).

### DECISION NEEDED (resolved 2026-10-05 by Mbah)

- [x] [Review][Decision] **AC2 newly exposes an empty account label in the header for the whole post-OTP session** — `src/frontend/src/pages/auth/OtpPage.vue:150` → `src/frontend/src/layouts/MainLayout.vue:73`
  `isAuthenticated` is `computed(() => !!userId.value)` (`auth.store.js:15`). Before AC2, `skp` was
  never set on the OTP path, so `hydrateFromCookie()` set nothing, `userId` stayed `null`, and
  `MainLayout.vue:69`'s authenticated block never rendered. **AC2 makes `isAuthenticated` true on
  this path for the first time** — and `hydrateFromCookie` sets only `userId` and `role`, never
  `displayName` (its own note, `auth.store.js:80`: *"displayName not in skp — will be populated on
  next login response"*). On the OTP path there is no next login response: `/otp` returns a bare
  `Success` (`SecondFactorLoginFilter.java:96-105`), so `setUser()` is never called. `MainLayout.vue:73`
  binds `:label="authStore.displayName"` with no fallback → the account dropdown renders icon-only
  with an empty label. The remedy already exists and is unused here: the same `/otp` response sets the
  `user` cookie (`JwtManagerImpl.createLoginCookies`, incl. `BLANK_DISPLAY_NAME_SENTINEL`), and
  `readUserDisplayName()` (`utils/sessionCookies.js:52`) exists for exactly this, used with a
  `?? t('dashboard.defaultUser')` fallback at `DashboardPage.vue:51`.
  **Decision:** three valid fix sites with different blast radius — (a) hydrate `displayName` in
  `OtpPage.handleSubmit` only (contained, no shared code touched); (b) extend
  `auth.store.hydrateFromCookie()` to read the `user` cookie (fixes every caller incl. app boot, but
  changes shared store behaviour); (c) add the `??` fallback in `MainLayout.vue:73` (fixes the symptom
  for all routes, touches a shared layout). Or accept as-is and defer.
  **RESOLVED → (a), fix in `OtpPage` only.** Contained to the story's existing File List, touches no
  shared store or layout code, zero blast radius on the live login path. Promoted to P9 below.

- [x] [Review][Decision] **`.dockerignore` is undisclosed scope, and incomplete on its own terms** — `.dockerignore` (new, 69 lines, untracked)
  Present in the working tree but named in **no AC, task, Dev Note, Change Log entry, or the File
  List**; the story's Scope Level says "one backend cookie-issuance fix + its test coverage, two small
  frontend redirect fixes, one ops doc note". `git log -- .dockerignore` is empty, so it is not a
  restored tracked file. Its content is sound where it acts — the `Dockerfile` really does consume only
  `pom.xml`, `src/`, `.git/`, so the `mvnw`/`deploy/`/`docs/`/`dist/` exclusions are safe (verified
  against `Dockerfile` and `pom.xml`) — but it **misses the single largest remaining item**:
  `src/frontend/node/` (125 MB; `src/frontend/node/node` is a 113 MB `Mach-O x86_64` host binary) is
  not matched by `**/node_modules/`, and `src/frontend/coverage/` (9.3 MB) is also unexcluded. After
  the file's own exclusions, ~73% of the remaining ~183 MB context is precisely the host-only build
  residue the header says it exists to remove — the same host/arch-shadowing class it claims to have
  closed. This project's original spec for the file
  (`deploy-2-1-automated-ci-build-pipeline.md:41`) explicitly required excluding `src/frontend/node/`.
  **Decision:** (a) declare it in this story (add AC + File List + the two missing patterns);
  (b) split it into its own story and revert it from this branch; or (c) keep as-is, undeclared.
  **RESOLVED → (a), declare it in this story.** Add AC6, a File List entry and a Change Log line, and
  add the two missing exclusions (`src/frontend/node/`, `src/frontend/coverage/`). Promoted to P10 below.

### PATCH (fix before merge)

- [x] [Review][Patch] **`hydrateFromCookie()` has zero test coverage — mutation-proven** [`src/frontend/src/pages/auth/__tests__/OtpPageSpec.js`]
  Deleting line 150 from `OtpPage.vue` leaves all 4 specs green (verified by actually running the
  mutation). `createTestingPinia` stubs the action and every case seeds `initialState.auth.role`
  directly, so nothing asserts the call happened. This line is the **only** thing connecting AC2's new
  backend cookie to AC3's redirect — the AC2↔AC3 seam is untested end to end. The Debug Log's
  mutation check reverts the whole file at once, so it never isolates this line. Add
  `expect(authStore.hydrateFromCookie).toHaveBeenCalled()`.

- [x] [Review][Patch] **AC3's "an absolute … redirect value is rejected" has no spec case** [`src/frontend/src/pages/auth/__tests__/OtpPageSpec.js:75`]
  The only rejection case passes `'//evil.example.com'` — protocol-relative, caught by the
  `!startsWith('//')` half. No case passes an absolute URL (`https://evil.example.com`), which is
  caught by the `startsWith('/')` half, so the two halves of the guard are not independently covered.
  The guard itself is correct; only the claimed coverage is partial.

- [x] [Review][Patch] **`SecurityIT`'s new `skp` assertion is an unanchored substring match that can false-pass** [`src/test/java/com/softropic/skillars/platform/security/SecurityIT.java:291`]
  `anyMatch(c -> c.contains(SKILLARS_PROFILE_COOKIE))` tests for `"skp"` **anywhere** in **any**
  `Set-Cookie` header — and the same response carries `potc=<~400-char base64url JWT>`. A JWT whose
  base64 happens to contain the trigram `skp` satisfies the assertion with
  `setSkillarsProfileCookie` deleted (~0.15%/response — a flake that passes CI for months). Separately,
  the comment claims the cookie *"carries the `"ADMIN"` fallback"*, which the assertion never checks
  (no value is decoded). Anchor on `SKILLARS_PROFILE_COOKIE + "="`, the shape
  `JwtManagerImplTest.extractCookie:351` already uses. (Flagged independently by all three layers.)

- [x] [Review][Patch] **`VideoManagementPageSpec`'s notify assertion cannot discriminate the branch it tests** [`src/frontend/src/pages/__tests__/VideoManagementPageSpec.js:61`]
  Both arms of `fetchVideos()`'s catch emit `type: 'negative'` (`VideoManagementPage.vue:107` and
  `:110`), so `expect.objectContaining({ type: 'negative' })` is satisfied either way and would
  survive deleting the status check entirely. The discriminating field is `message` — assert
  `video.management.accessDenied`.

- [x] [Review][Patch] **The load-bearing "`id` is quoted deliberately" rationale is not carried to the new third `skp` site** [`src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:107`]
  Both existing sites carry an identical 9-line comment (`AuthService.java:122-130` and `:208-216`)
  explaining that `id` must stay quoted or `hydrateFromCookie()` silently corrupts `authStore.userId`
  via IEEE-754 rounding — a real 403 found in manual testing on 2026-10-01. The new site reproduces
  the quoting correctly but carries no such warning, so a future editor "cleaning up" the quotes here
  has no in-place signal. Add a one-line cross-reference.

- [x] [Review][Patch] **`review-execution-log.md` describes two tests that do not exist** [`_bmad-output/implementation-artifacts/review-execution-log.md:68-82`]
  `:78-81` describes OtpPageSpec test #4 as *"hydrateFromCookie called after initSession — spy on
  `authStore.hydrateFromCookie` — assertion: called exactly once"*. No such test exists; the real #4
  (`OtpPageSpec.js:93-100`) is the no-role → `/dashboard` case, and nothing in the file spies on that
  action. The prior review caught this one but stopped there: `:68-71` describes test #2 with
  `redirect = '/admin/health'` asserting `/coach/command-center`, while the real #2 (`:75-82`) uses
  `role: 'PARENT'`, `redirect: '//evil.example.com'`, asserting `/parent/dashboard`. The logged setup
  would have **inverted** the test's meaning (`/admin/health` passes the guard, so the real code would
  honour it, not fall back). These two fabrications are what make P1 and P2 non-obvious on a casual
  read of the record — the log claims coverage for exactly the two things that turn out to be uncovered.

- [x] [Review][Patch] **Story Context mis-attributes the "one role per user" premise to `V139:51-55`** [story file `:115`]
  `V139__baseline_seed_data.sql:51-55` inserts five rows into `main.authority` — the authority
  *catalogue*, with no user reference at all. It establishes which authority names exist, not how many
  a user holds. The premise is nevertheless **true**, but the real evidence is elsewhere:
  `CoachRegistrationService.java:101`, `ParentRegistrationService.java:101`,
  `PlayerRegistrationService.java:116` and `AdminBootstrapRunner.java:204` each call
  `setAuthorities(Set.of(<one authority>))`. Correct the citation.

- [x] [Review][Patch] **AC1's ops note advises leaving a possibly-live infrastructure credential in place** [`docs/deployment/secrets-reference.md:432+`]
  Every mechanism claim in the new section was re-verified and is accurate (`provision.sh:591`,
  `:597-600`, `env-guard.sh:27`'s bare `.` with no `set -a`, `apply-firewall.sh:3`'s local-machine
  scoping — all confirmed verbatim). But the remediation line says a stale value is *"harmless on its
  own… safe to delete the two lines by hand, or leave them."* `HCLOUD_TOKEN` is a Hetzner Cloud API
  token with infrastructure-level privilege and no expiry; the doc's analysis answers "can
  `provision.sh` misbehave?" and never asks "is this token still live?". Add a sentence directing the
  operator to revoke it at the provider, then delete the line. (The sharper version of this claim —
  that compose injects it into the container env via `--env-file` — was **refuted**: see Dismissed 9.)

### DEFER (pre-existing or out of scope — logged, not actioned)

- [x] [Review][Defer] **`findFirst()` picks an arbitrary role when two authorities both map to a `SkillarsRole`** [`JwtManagerImpl.java:105`] — deferred, latent. Order traces back to `User.authorities` = `new HashSet<>()` (`User.java:199`) with `Authority.hashCode()` = `name.hashCode()`, so it is hash-derived, not business-meaningful. The module's own sibling for this exact operation, `MessagingResource.resolveRole` (`:231-246`), hard-codes `COACH > PARENT > PLAYER` precedence and names the dual-role case in a comment. Not currently triggerable — no `src/main` path creates a multi-authority user — but this is the one place the new code departs from the established shape, and the departure is invisible until such a user exists.
- [x] [Review][Defer] **`.dockerignore` single-segment patterns match the context root only** [`.dockerignore:24-26,58-62,67-69`] — deferred, out of scope. `.dockerignore` patterns are anchored full-path matches, so `*.jar`, `.DS_Store`, `*.iml`, `*.log` apply at the root only; nested copies still ship. `.DS_Store` matters most on this macOS host — Finder rewrites it on directory browse, changing its mtime and busting the `COPY src/ src/` cache layer the file exists to optimise. Prefix with `**/`.
- [x] [Review][Defer] **`.dockerignore` exclusions make `git.dirty=true` permanent in every image** [`.dockerignore:36-64` × `Dockerfile:11`] — deferred, no consumers. `.git/` is copied deliberately for `git-commit-id-maven-plugin` (`pom.xml:733-748`), but the exclusions remove git-*tracked* paths (`docs/`, `deploy/`, `.github/`, `Dockerfile`, `mvnw`, `.gitignore`, …) from the builder's working tree, so JGit reports deletions and stamps `git.dirty=true` / `<sha>-dirty` into `git.properties` on every build of a clean commit. A grep across `src/ deploy/ .github/` found zero consumers, so impact is limited to `/manage/info` provenance.
- [x] [Review][Defer] **`.git/` in the build context ships full repo history into the builder layer** [`.dockerignore:11-13`] — deferred, deliberate tradeoff. Any credential ever committed and later removed is still in that history. Bounded by the multi-stage build, unless builder cache is pushed to a registry (`--cache-to type=registry`). The comment justifies inclusion on size only ("~30MB") and should state the exposure tradeoff; the hygienic alternative is passing the SHA as a build arg.
- [x] [Review][Defer] **`URLEncoder.encode` is form-encoding, not URI-component encoding** [`JwtManagerImpl.java:108`] — deferred, pre-existing and shared. `URLEncoder` emits `+` for space; the frontend decodes with `decodeURIComponent`, which leaves `+` literal. Latent only — today's payload is a numeric id plus an enum name, neither of which can contain a space. Identical at both `AuthService` sites, so fixing it here alone would create the divergence AC2 set out to avoid.
- [x] [Review][Defer] **A `redirect` value that passes the guard but matches no route lands the user on the 404 page** [`OtpPage.vue:151-156`] — deferred, inherited verbatim. The guard validates shape only (`startsWith('/') && !startsWith('//')`), never resolvability, so `?redirect=/typo` ends a successful OTP verification on `ErrorNotFound.vue` (`routes.js:352-355`) with no fallback to `routeForRole`. Byte-identical to `LoginPage.vue:170-175`, so it is a project-wide shape, not a new divergence. Confirmed it cannot leave the origin: `quasar.config.js:40` sets `vueRouterMode: 'hash'`, and history mode prepends the origin explicitly.
- [x] [Review][Defer] **The open-redirect guard now exists as two textual copies** [`OtpPage.vue:151-155`, `LoginPage.vue:170-174`] — deferred, AC3 mandated the verbatim copy. Open-redirect guards get revised (`/\` variants, encoded forms, tab/newline injection); when one copy is hardened the other will not be, and nothing links them. Extract a shared, once-tested `isSafeRedirect(path)`.
- [x] [Review][Defer] **`router.push` leaves the spent `/otp` page in history** [`OtpPage.vue:156`] — deferred, pre-existing. After verification the `loginInfoId` is consumed server-side, so Back returns the authenticated user to a dead OTP form; re-submitting errors against a consumed id. `VideoManagementPage.vue:108` in this same diff correctly uses `replace` for its terminal redirect.
- [x] [Review][Defer] **Neither new `skp` unit test asserts a cookie attribute** [`JwtManagerImplTest.java:622-654`] — deferred, low. Both assert only the decoded JSON. The whole feature depends on the unnamed `false` (httpOnly) that lets JS read the cookie; flip it and both tests stay green while the feature is silently dead in the browser. Verified correct today and byte-identical to `AuthService`'s call, so this is coverage strength, not a defect.

### DISMISSED (15 — recorded so they are not re-litigated)

Each was traced to its origin in real source, not reasoned about abstractly.

1. **`BUS_ID` null → literal `"null"` in the cookie** (the prior `mto-code-review`'s only must-fix) — **FALSE POSITIVE.** The concatenation mechanic is real, the premise is not. `TokenCreatorImpl:60` puts `principal.getBusinessId()`; `Principal.instanceFrom:155` sets it to `String.valueOf(user.getId())` on a persisted user. The sole producer of the token reaching `refreshLoginToken` is `TwoFactorLoginService.processLogin`, which executes `Long.parseLong(principal.getBusinessId())` at `:61` — **before** the token is ever stored for redemption. A null or non-numeric business id aborts the OTP flow there. The proposed remedy (early `return` on null) would also be actively harmful: it would skip the cookie *after* `createAndSetJwt` already wrote a valid JWT, producing an authenticated-but-role-less session — reintroducing the exact dead end AC3 exists to fix, for a case that cannot occur.
2. **JSON injection via `BUS_ID`** — refuted. `role` comes from `Enum::name` (fixed alphabet); `BUS_ID` is `String.valueOf(user.getId())`, numeric. No quote or brace can reach the literal.
3. **The new `skp` payload is narrower than login's, truncating it on refresh** — refuted by character-by-character comparison. `AuthService.java:131-134` is `{"id":"…","role":"…"}` — the same two fields, same `URLEncoder`, same `httpOnly=false`, same `(int) REFRESH_TOKEN_TTL.toSeconds()`, same `"Lax"`.
4. **The fix fires on every token refresh for every user** — refuted. `refreshLoginToken` has exactly **one** caller: `SecondFactorLoginFilter.java:75`. The sliding-window keep-alive uses `extendTtlOfToken`, which this story does not touch.
5. **`getAuthoritiesSilently` may return null → NPE on `.stream()`** — refuted. `JwtManagerImpl.java:124-132` returns `List.of()` on `JsonProcessingException` and never null.
6. **`"ADMIN"` as the parse-failure default is an unsafe privilege-upward fallback** — originally dismissed as a resolved design decision ("Known, accepted fragility", mirroring `AuthService.java:121`/`:207`). **Superseded 2026-10-05**, post-review, by direct reviewer instruction (Mbah): the "mirrors `AuthService`" framing was shape-only, not meaning-only — `AuthService`'s `null -> "ADMIN"` fires for a real admin with an unset DB role column, while this fallback fires for a non-admin authority with no `SkillarsRole` equivalent (`ROLE_LTD_ADMIN`/`ROLE_USER`); the two are different populations, and the privilege-upward concern was legitimate. Fixed by adding `SkillarsRole.ANONYMOUS` and falling back to it instead — see "Fallback redesign" below.
7. **Sending a null-role user to `/dashboard` recreates the dead end AC4 fixes** — resolved design decision; AC3 explicitly reworded to state `/dashboard` via `DEFAULT_ROUTE` is a correct outcome when no role is known, with a spec case added for it.
8. **`.dockerignore` breaks the build by excluding `mvnw` / `deploy/` / `src/frontend/dist/`** — refuted. The `Dockerfile` runs `mvn package` from the `maven:3.9-eclipse-temurin-17` base (its own `mvn`), never `COPY`s `.mvn`, and consumes only `pom.xml`, `src/`, `.git/`. `dist/` is *generated inside* the builder by `frontend-maven-plugin` at `generate-resources` (`pom.xml:577-645`) before `maven-resources-plugin` copies it at `process-resources` — excluding it is both safe and necessary.
9. **A stale `HCLOUD_TOKEN` is loaded into the application container's environment on every deploy** — refuted, and this was the most serious claim raised. `docker-compose.yml:20-22` documents in-place that there is **no** `env_file:` on the service and that "putting a variable in /opt/skillars/.env does nothing on its own — it only feeds `${VAR}` placeholders written literally in this file." No service references `${HCLOUD_TOKEN}` or `${HETZNER_VOLUME_ID}`. `--env-file` supplies interpolation variables, not container environment.
10. **`StringUtils.removeStart` is Spring's `StringUtils`, which lacks the method** — refuted. `JwtManagerImpl.java:18` imports `org.apache.commons.lang3.StringUtils`, already used elsewhere in the file.
11. **`hydrateFromCookie()` may be async, so `authStore.role` is read too early** — refuted. `auth.store.js:72-86` is a plain synchronous `document.cookie` regex + `JSON.parse`. (The *coverage* half of this claim survived as P1.)
12. **`initSession()` exception skips `hydrateFromCookie()`** (the prior `mto-code-review`'s optional patch) — **FALSE POSITIVE as described.** The control-flow observation is literally true but it is not unhandled: lines 148-156 sit inside `handleSubmit`'s `try`, whose `catch` at `:157-160` calls `setError(err)`, clears the digits and refocuses, with `isSubmitting` reset in `finally`. The structure is identical to `LoginPage.vue:168-175`, so a defensive inner try/catch would *diverge* from the precedent AC3 requires the page to match.
13. **No test covers an empty or absent `ROLES` claim** — covered. The `ROLE_LTD_ADMIN` case exercises the `findFirst().orElse("ADMIN")` arm. A genuinely null-authority entry is unreachable: `SimpleGrantedAuthorityMixin`'s `@JsonCreator` delegates to Spring's constructor, whose `Assert.hasText` rejects it during deserialization as a `JsonProcessingException` → caught → `List.of()`.
14. **`createTestingPinia`'s `initialState.auth.role` would not apply if `role` were a getter** — refuted; the tests pass for the right reason. `auth.store.js:10` is `const role = ref(null)` — real state, returned at `:90`. The computed members are `isAuthenticated`/`isCoach`/`isParent`/`isPlayer`/`isAdmin`, none of which the specs set.
15. **The specs validate against hand-rolled route tables, so route drift is invisible** — dismissed. The drift risk was checked directly rather than assumed: `/player/dashboard` really does exist at `routes.js:262-268` with `meta: { requiresAuth: true, role: 'PLAYER' }`, and `OtpPageSpec` leaves `routeForRole` unmocked so it exercises the real mapping. Local test routers are the established pattern across this project's specs.

**Also checked and cleared** (not findings, recorded to close them out): the hardcoded `'/player/dashboard'` does **not** violate the "never hardcode" rule and `routeForRole` would have been *wrong* here — `roleRoutes.js:16` maps `PLAYER → '/player/home'` (a profile-completeness redirect gate) and `routes.js:262-263` carries the explicit comment "Nav-menu destination only — NOT ROLE_ROUTES.PLAYER"; `/player/dashboard` is the PLAYER nav entry and the only bounce target with a menu entry. AC2's four "untouched" files (`createLoginCookies`, `TokenCreatorImpl.toClaims`, `SecurityConstants`, `ClaimsExtractorImpl`) are confirmed absent from the diff. `skp` is never cleared by `deleteLoginToken`, but that gap is pre-existing and symmetric with the live login path, so parity — AC2's stated goal — is achieved. All project-context rules verified compliant on the changed code.

---

## Context: Current State (verified against HEAD, 2026-10-04; re-verified by `story-review.md`, same HEAD)

### AC1 — Stale ops-note item
`skillars-uat-6-coach-subscription-and-volume-backup` (2026-08-13, AC8) removed `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from `.env.example` and `docs/deployment/secrets-reference.md`. Confirmed at HEAD: neither string appears anywhere in either file today (commit `6a8a3bd4`). The gap: a node that was `provision.sh`'d **before** that change still has a live `/opt/skillars/.env` carrying the old values.

**The real inertness mechanism (corrected during pre-implementation review — the original "nothing reads them" framing was false):**
- `deploy/provision.sh:591` does check `HETZNER_VOLUME_ID`: `if [ -n "${HETZNER_VOLUME_ID:-}" ]; then` — and if it's set to a value that doesn't resolve to an attached Volume, it hard-fails at `:597-600` (added later by `skillars-deferred-88`, after uat-6 removed the var from the template — which is exactly why the "harmless" framing went stale).
- **But this is a plain environment-variable check, not a file read.** `provision.sh` never sources `/opt/skillars/.env` wholesale, and never reads `HETZNER_VOLUME_ID` from it directly either — it only reacts to whatever is already an **exported** variable in its own process environment. A stale value sitting inertly inside the `.env` file text is invisible to a plain `./provision.sh` re-run.
- The one real trigger: an operator who manually `export`s `HETZNER_VOLUME_ID` (or runs `set -a; . .env`) into their **own interactive shell** before re-running `provision.sh` in that same shell. (`deploy/backup/env-guard.sh`'s `require_env_vars` helper, used by the backup/restore scripts, does `. "$env_file"` but **without** `set -a` — per the existing `skillars-deferred-107` AC6 decision — so it holds credentials as *unexported* shell variables that do not escape that script's own process. It is **not** a vector for this specific risk, contrary to what a first read might suggest.)
- `deploy/firewall/apply-firewall.sh:18-19` also requires `HCLOUD_TOKEN`, but its own header scopes it explicitly to the **operator's local machine** — irrelevant to the server's `.env` file. (This exact local/server distinction was already raised and dismissed as out-of-scope by `skillars-uat-6`'s own code review; not new.)

The note should state the mechanism above, not an unqualified "nothing reads them."

### AC2 — The `skp` cookie gap on OTP completion (widened from the original ledger ask, redesigned after pre-implementation review)

The original `deferred-work.md` bullet (filed against `skillars-deferred-141`'s code review) only flagged a **frontend** inconsistency: `OtpPage.vue:96`'s `redirectPath` fallback is a hardcoded `'/dashboard'` instead of `routeForRole(...)`, unlike `LoginPage.vue:174`. Investigating that bullet surfaced a **deeper, previously-unfiled backend gap**, which the pre-implementation review then found a materially safer way to close.

**There are two entirely separate, parallel login subsystems in this codebase, and `OtpPage.vue` belongs to the one the live UI does not use:**
- **Live path:** `LoginPage.vue` → `authApi.skillarsLogin()` → `POST /api/auth/login` → `AuthResource.login()` → `AuthService.login()` (`:61-137`). This path has **no OTP/2FA branch at all**.
- **Legacy, OTP-capable path:** `authApi.login()` → `POST /authenticate` → `JWTAuthenticationFilter.successfulAuthentication()` — which, if `principal.isOtpEnabled()` (its own in-code comment: *"At the moment the flow will always enter here"*), triggers `TwoFactorLoginService.processLogin()` and expects completion via `authApi.verifyOtp()` → `POST /otp` → `SecondFactorLoginFilter` → `JwtManagerImpl.refreshLoginToken()`. **`authApi.login()` has zero callers anywhere in `src/frontend/src`** (confirmed by exhaustive grep) — so not only does nothing navigate to the `OtpPage.vue` *page* (already known), the backend endpoint that would *trigger* the OTP flow in the first place is equally unreachable from the live UI today. This story closes a real gap in dormant infrastructure, not a live bug — stated plainly so a future reader doesn't mistake AC2 for an active-incident fix.

**The two login paths set `skp` very differently.** `AuthService.login()` (`:61-137`, skp block `:121`+`:131-134`) and `AuthService.refresh()` (`:139-223`, skp block `:207`+`:217-220`) each manually build and set the `skp` cookie themselves, *after* calling `loginTokenManager.createLoginToken(...)`:
```java
String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
String json = "{\"id\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}";
String skpValue = URLEncoder.encode(json, StandardCharsets.UTF_8);
CookieUtil.addCookie(res, SKILLARS_PROFILE_COOKIE, skpValue, false, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");
```
Both have direct DB access to the `User` entity, so `user.getSkillarsRole()` is right there. **`JwtManagerImpl.refreshLoginToken()`** (`:68-83`, called exclusively from `SecondFactorLoginFilter:75`) calls `createAndSetJwt(res, claims)` → `createLoginCookies(res, claims, jwtExpiryEpochMs)` (`:203-211`, `:213-249`), which sets `bcookie` (`B_COOKIE`), `user`, `ION`, optionally `admin`, and `rint` — **but never `skp`**, and nothing else in this path sets it either.

**Caller inventory — corrected.** The pre-implementation review found the original draft's scoping rationale ("`createLoginToken`/`renewLoginToken`, and their only caller `AuthService`, are untouched") was factually wrong. The real inventory (`LoginTokenManager` has exactly one implementation, so this is exhaustive):
```
createLoginToken:  AuthService.java:117  (login)    → skp set at :133  ✓
                   AuthService.java:203  (refresh)  → skp set at :219  ✓
                   JWTAuthenticationFilter.java:100  (non-OTP /authenticate branch) → NO skp
renewLoginToken:   JWTAuthorizationFilter.java:193, :202 (sliding-window session keep-alive) → NO skp
```
`AuthService` never calls `renewLoginToken` at all. **Three of the four paths into the shared `createLoginCookies` helper have no `skp` today — not just the one this story fixes.** The other two (`JWTAuthenticationFilter`'s non-OTP branch, `JWTAuthorizationFilter`'s keep-alive) belong to the same legacy, frontend-unreachable subsystem described above (`JWTAuthorizationFilter` is the global per-request JWT-authorization filter, but it only ever re-mints tokens that were minted by one of the two paths above — a session started via the **live** `AuthService.login()` path never needs `renewLoginToken`/`extendTtlOfToken` to carry a *new* `skp`, since `skp` was already set correctly at login and nothing re-derives it from the JWT on renewal). Fixing those two is real, separate, broader work in dead code — explicitly out of scope here. This story's scope is exactly what the sourcing ledger bullet named: the OTP-completion path.

### Design decision: derive the role from the existing `ROLES` claim, don't add a new one

The original draft of this story proposed adding a new `skillarsRole` claim to `TokenCreatorImpl.toClaims(...)` so `refreshLoginToken` could read it off the decoded pre-OTP token. The pre-implementation review found this would plant a **live, system-wide latent bug**: `toClaims` is also the claims-builder behind `JwtManagerImpl.extendTtlOfToken()` (`:128-133`), called by `JWTAuthorizationFilter:209` on the **dominant, sliding-window keep-alive path for every authenticated request in the whole application** (both login subsystems). That path rebuilds its `Principal` via `ClaimsExtractorImpl.extractPrincipal()` (`:70-85`), whose builder has no `.skillarsRole(...)` call — so the reconstructed Principal's role is always `null`, and a new claim fed back through `toClaims` would be **silently re-stamped to `"ADMIN"` for every user, starting on their second authenticated request of any session**. Nothing reads the claim *today*, so nothing breaks *right now* — but it would be a correctness landmine for whoever reads it next, and fixing it properly would mean also touching `ClaimsExtractorImpl` (and `JwtManagerImpl.authentication(claims)`, `:87-93`, which has the identical omission) — a much bigger, security-sensitive change for a small bundle story.

**Resolution: derive the role locally, inside `refreshLoginToken`, from the `ROLES` claim that already exists on every token** (used by `createLoginCookies` itself one method away, `:229-230`, to decide the `admin` cookie). `ROLES` holds a JSON list of Spring authorities (e.g. `[{"authority":"ROLE_COACH"}]`), not fine-grained permissions as an earlier draft of this story assumed — `V139__baseline_seed_data.sql:51-55` seeds exactly the five authority names `ROLE_COACH`/`ROLE_PARENT`/`ROLE_PLAYER`/`ROLE_ADMIN`/`ROLE_LTD_ADMIN` into the authority *catalogue* (no user reference), matching `SkillarsRole {COACH, PARENT, PLAYER, ADMIN}` 1:1 for the four mapped roles. The one-authority-per-user premise itself is established elsewhere: `CoachRegistrationService.java:101`, `ParentRegistrationService.java:101`, `PlayerRegistrationService.java:116`, and `AdminBootstrapRunner.java:204` each call `setAuthorities(Set.of(<one authority>))`. `JwtManagerImpl` already has a private `getAuthoritiesSilently(claims)` method (`:97-104`) that parses this claim defensively (returns `List.of()` on any JSON error, never throws) — reuse it. This approach:
- Touches **only** `JwtManagerImpl.refreshLoginToken()`. No new claim, no `SecurityConstants` change, no `TokenCreatorImpl` change, no `ClaimsExtractorImpl` change.
- Has **no deploy-window hazard** — unlike a new claim, `ROLES` has existed on every token this system has ever minted, so there is no "token minted before the deploy, redeemed after" gap to handle.
- Cannot literally emit `role:"null"` — `getAuthoritiesSilently` never returns null, and the fallback (below) is always `SkillarsRole.ANONYMOUS.name()`.

**Fallback redesign (2026-10-05, post-review, direct reviewer instruction):** `ROLE_LTD_ADMIN`/`ROLE_USER` (seeded/used by paths with no `SkillarsRole` equivalent) will not match any `SkillarsRole.valueOf(...)` candidate. The original implementation fell through to `"ADMIN"` here, reasoning it was "identical in shape to `AuthService`'s own existing `null → "ADMIN"` fallback" — true in shape, false in meaning: `AuthService`'s fallback fires when `user.getSkillarsRole()` is unset in the DB, which per `AdminBootstrapRunner`'s own Javadoc is the deliberate convention for *real* admins; this fallback instead fires for a non-admin authority with no `SkillarsRole` mapping, so reusing `"ADMIN"` mislabeled a non-admin as one, and `authStore.isAdmin` on the frontend reads this value directly — a real, if low-blast-radius (dormant legacy OTP path), privilege-upward UI bug. Fixed by adding a new `SkillarsRole.ANONYMOUS` constant (`SkillarsRole.java`) and falling back to it instead of a hardcoded string. Verified safe on the frontend: `routeForRole('ANONYMOUS')` uses an `Object.hasOwn` lookup against `ROLE_ROUTES` that falls through to `DEFAULT_ROUTE` ('/dashboard') for any unrecognized role, and `isAdmin`/`isCoach`/`isParent`/`isPlayer` are plain `===` checks that are all simply `false` for it — same safe-fallback shape already exercised by `OtpPageSpec`'s existing "no role" test case. `SkillarsRole.ANONYMOUS` is documented on the enum itself as cookie-fallback-only, never to be persisted via `user.setSkillarsRole(...)`: `User.skillarsRole` is Envers-audited into `main.user_aud`, whose `skillars_role` column carries a CHECK constraint restricted to `('COACH','PARENT','PLAYER','ADMIN')` (`V145__user_aud_role_verification_status.sql`) — persisting `ANONYMOUS` would fail that constraint, and would also be silently misclassified as PLAYER by `GdprErasureService`'s role branching (`:439-459`), which has no `ANONYMOUS` case. Neither risk is triggered by this fix, since `setSkillarsProfileCookie` never writes to the `User` entity — the constant exists purely as a cookie value.

### AC3 — `OtpPage.vue` frontend fix
`src/frontend/src/pages/auth/OtpPage.vue:96`: `const redirectPath = computed(() => route.query.redirect || '/dashboard')`. Compare `LoginPage.vue:168-175`:
```js
authStore.setUser(response)
initSession()
const redirect = route.query.redirect
const safePath =
  typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : routeForRole(response.role)
router.push(safePath)
```
`OtpPage.vue` has **neither** the role-based fallback **nor** the open-redirect guard. Unlike `LoginPage.vue`, `OtpPage.vue`'s `verifyOtp()` response is a bare `Success` message (`SecondFactorLoginFilter.successResponse()`, `:96-105`) — it carries no `role` field, so the fix must read the role from `authStore` (via `hydrateFromCookie()`, now meaningful once AC2 ships) rather than from the verify-OTP response body.

**AC3's acceptance text needs one precision note.** `roleRoutes.js:21` defines `DEFAULT_ROUTE = '/dashboard'`, so `routeForRole(null)` **legitimately returns `/dashboard`** when no role is known (e.g. `skp` genuinely absent or unparseable). That is correct, intended behavior, not a bug — the acceptance criterion is about the *mechanism* (`routeForRole(authStore.role)`, not a hardcoded string) being used, not about `/dashboard` being categorically forbidden as an outcome. Task 3 adds an explicit spec case for this degraded path so it's a stated, tested outcome rather than an untested one that happens to pass.

**Departure from the sourcing ledger bullet's own sequencing, disclosed.** `deferred-work.md:3617-3619` (the ledger bullet this AC sources from) says: *"When picked up: change the fallback to `routeForRole(authStore.role)` **at the same time the OTP login flow is actually wired into navigation**."* This story does the former without the latter — deliberately: wiring the whole legacy OTP flow into live navigation is large, separate, out-of-theme scope for a small cleanup bundle, and (per AC2/AC3's own disclosed constraint) cannot be browser-verified in this execution context regardless. Recorded here so this is a stated decision, not a silently-dropped precondition.

### AC4 — `VideoManagementPage.vue` fix
`src/frontend/src/pages/VideoManagementPage.vue:101-115`, `fetchVideos()`'s 403 handler does `router.replace('/dashboard')` (`:108`). The route `player/videos` (`routes.js:255-260`, `path: 'player/videos'` at `:256`) is `meta: { requiresAuth: true, role: 'PLAYER' }` — the only role that can reach this page at all is PLAYER. Since `skillars-deferred-141`, the generic `/dashboard` nav entry is admin-only (gated `v-if="authStore.isAdmin"`, `MainLayout.vue:126`), so a PLAYER bounced there has no menu entry for their role — a dead end. `src/frontend/src/pages/player/PlayerDashboardPage.vue` already exists at `/player/dashboard` (`routes.js:261-268`, `path: 'player/dashboard'` at `:264`, created by `skillars-deferred-141` AC4.2 for exactly this persona) as the correct retarget.

---

## Acceptance Criteria

**AC1 — Ops note for stale provisioning secrets**
- [x] `docs/deployment/secrets-reference.md` gains a new section documenting that a node provisioned before `skillars-uat-6` AC8 may still carry stale `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` values in its live `/opt/skillars/.env`, **the accurate mechanism for why it's inert** (`provision.sh` checks an exported env var, not the file; nothing sources `.env` wholesale), and the one real conditional trigger (an operator manually exporting the stale value before re-running `provision.sh`).
- [x] No script, code, or behavior change.

**AC2 — OTP completion sets a correct, fresh `skp` cookie**
- [x] After a successful `/otp` verification, the response's `Set-Cookie` headers include `SKILLARS_PROFILE_COOKIE` ("skp") with the caller's real `id`/role, matching the exact JSON shape (`{"id":"<id>","role":"<ROLE>"}`, quoted id, URL-encoded) that `AuthService.login()`/`refresh()` already produce.
- [x] The role is derived from the existing `ROLES` claim inside `refreshLoginToken` — **no new JWT claim is added anywhere**.
- [x] The fallback (no matching authority) is `SkillarsRole.ANONYMOUS`, not `AuthService`'s `"ADMIN"` convention — the two fallbacks fire for different populations (see "Fallback redesign" above) — never the literal string `"null"`.
- [x] `createLoginCookies`, `TokenCreatorImpl.toClaims`, `SecurityConstants`, and `ClaimsExtractorImpl` are **untouched**.
- [x] `SecurityIT.loginWith2FAWhenAccountEnabled()` asserts the new cookie is present (presence-only — this fixture has no real role); confirmed (via temporary revert) to fail before the fix.
- [x] A `JwtManagerImplTest` case, using a `Principal` fixture built with a real role-shaped authority, proves the actual `ROLES` → `SkillarsRole` mapping — not just the fallback.

**AC3 — `OtpPage.vue` redirects by role, safely**
- [x] After a successful OTP submit, the fallback landing page is computed via `routeForRole(authStore.role)` (hydrated from the now-correct `skp` cookie), not a hardcoded `/dashboard` string.
- [x] An absolute or protocol-relative (`//...`) `redirect` query value is rejected in favor of the role fallback, matching `LoginPage.vue`'s existing guard.
- [x] New Vitest coverage for: the role-fallback behavior, the redirect-guard behavior, **and** the `skp`-absent/unparseable degraded case landing on `/dashboard` via `DEFAULT_ROUTE`.

**AC4 — `VideoManagementPage.vue` 403 redirects to a usable page**
- [x] A 403 from `getMyVideos()` redirects to `/player/dashboard`, not `/dashboard`.
- [x] New/extended Vitest coverage asserting the new target.

**AC5 — Testing & Definition of Done**
- [x] All new/extended backend and frontend tests pass; full targeted backend suite (`SecurityIT`, `JwtManagerImplTest`, auth-adjacent tests) and full frontend Vitest suite green with no regressions.
- [x] ESLint/Prettier clean.
- [x] No local `mvn verify` (standing project convention — GitHub CI is the sole full-verification gate).
- [x] Manual browser verification is not applicable for AC2/AC3 (confirmed: neither `/otp` nor its trigger endpoint `/authenticate` is reachable from any live UI flow); this is explicitly disclosed rather than silently skipped.

**AC6 — `.dockerignore` excludes the full build-context residue it exists to remove**
- [x] New root `.dockerignore` excludes `target/`, `src/frontend/node_modules/`, `src/frontend/dist/`, `src/frontend/.quasar/`, `.git`-untracked secrets (`.env*`), docs/planning/agent-tooling directories, and compose/deploy/CI definitions — keeping the build context to the three paths the `Dockerfile` actually consumes (`pom.xml`, `src/`, `.git/`).
- [x] Also excludes `src/frontend/node/` (frontend-maven-plugin's own locally-downloaded, host-arch node distribution — 125 MB, not matched by `**/node_modules/`) and `src/frontend/coverage/` (generated Vitest output, 9.3 MB) — found still shipping into the context by the code review (2026-10-05), the same host/arch-shadowing class the file otherwise exists to close.
- [x] Single-segment patterns (`*.jar`, `.DS_Store`, `*.iml`, `*.log`) and `git.dirty=true`-on-every-build are known, disclosed, deferred gaps (see Review Findings below) — not actioned in this story.

---

## Dev Notes

- **Do not refactor `AuthService.login()`/`refresh()`** to also use the `ROLES`-derivation helper instead of `user.getSkillarsRole()` directly. They are not broken; leave them exactly as-is. This story only adds the missing behavior to `refreshLoginToken()`.
- **Do not attempt to also fix the two other missing-`skp` paths** (`JWTAuthenticationFilter`'s non-OTP `/authenticate` branch, `JWTAuthorizationFilter`'s `renewLoginToken` keep-alive calls) found while scoping AC2. They are real, pre-existing gaps in the same legacy, frontend-unreachable subsystem — broader, separate work, not this bundle's scope.
- **`JwtManagerImpl.authentication(claims)`** (`:87-93`, the `Authentication`/`Principal` published in the `SUCCESSFUL_2FA` `AuthEvent`) also has no `.skillarsRole(...)` populated. This is pre-existing and unrelated to this story's fix (which derives the role independently, straight from the raw `ROLES` claim string, never through this `Principal`) — no listener currently reads the field, so it is noted but explicitly out of scope, not silently ignored.
- Per this project's standing convention (see `_bmad-output/implementation-artifacts/deferred-work.md`'s own house style), every citation above was re-verified directly against HEAD during story creation and again during the pre-implementation review — including the discovery that AC2's real scope (a backend gap) is wider than the ledger bullet that sourced it, that the originally-proposed fix design had a latent correctness hazard, and that AC5's original ledger-deletion target doesn't exist as a standalone item.

---

## Resolution of pre-implementation review (`story-review.md`, 2026-10-04)

Every finding below was independently re-verified against HEAD before being accepted — none were applied on the review's say-so alone.

- **H1 (new claim gets clobbered by `extendTtlOfToken`'s reconstruction) — RESOLVED by design change.** Confirmed `ClaimsExtractorImpl.extractPrincipal` (`:76-85`) omits `.skillarsRole(...)` and that `JWTAuthorizationFilter:209` calls `extendTtlOfToken` on the dominant per-request path. Fix: dropped the new-claim design entirely in favor of deriving the role from the existing `ROLES` claim, locally, inside `refreshLoginToken` only (see "Design decision" above). This also fully resolves M5 (see below) and removes H3's deploy-window hazard (no new claim exists to be missing from an old token).
- **H2 (false "only caller" claim) — CONFIRMED, rewritten.** Independently re-grepped every call site of `createLoginToken`/`renewLoginToken`/`refreshLoginToken`/`extendTtlOfToken`; confirmed `AuthService` never calls `renewLoginToken`, and `JWTAuthenticationFilter`/`JWTAuthorizationFilter` are real, additional callers with no `skp` today. AC2's caller inventory and scoping rationale rewritten with the true inventory and the true reason for the narrow scope (ledger-sourced, not "already safe elsewhere").
- **H3 (literal `role:"null"` on a stale pre-deploy token) — MOOT after the H1 design change.** No new claim is added, so there is no "old token missing the new claim" case. The remaining structural question (what if `ROLES` itself is unparseable) is handled: `getAuthoritiesSilently` never returns null, and the fallback is always `SkillarsRole.ANONYMOUS.name()`, never Java `null`.
- **H4 (AC5 delete target doesn't exist / is audit history) — CONFIRMED, AC5 dropped.** Re-grepped `deferred-work.md` for a standalone `## Deferred from: ... ses-1-7-documentation` section: none exists. The matching text (`:2079`) sits inside the `## Last audit: 2026-09-14 (post-merge prune after skillars-deferred-110)` narrative block, confirmed via `git log -S` (the real open-work section was added by `1c40d6bf` and removed by `6fdc6b90`, three weeks before this story was drafted). Deleting it would destroy audit history the file's own convention retains. Task/AC removed from scope entirely rather than rewritten as a no-op.
- **M1 (IT fixture proves only the fallback) — CONFIRMED, addressed.** `SecurityIT.getUserData` (`:350-365`) never sets a role. The IT assertion is now explicitly scoped as presence-only; a new `JwtManagerImplTest` case (using the existing `createPrincipal` helper with a real role authority) proves the actual mapping instead.
- **M2 (AC1 "nothing reads them" is false) — CONFIRMED, AC1 rewritten with the accurate mechanism** (see Context above) — including the refinement, found during my own re-verification, that `env-guard.sh`'s bare (non-`set -a`) sourcing is *not* actually a vector for leaking the stale value into a separately-invoked `provision.sh` process, narrowing the real trigger to a deliberate operator export.
- **M3 (post-2FA `Authentication`'s Principal also lacks the role) — noted, correctly out of scope.** True and pre-existing, but orthogonal to this story's fix (which never routes through that `Principal`). Recorded in Dev Notes rather than actioned.
- **M5 (`ROLES` claim premise was wrong) — CONFIRMED, and resolved by adopting the cheaper design.** `ROLES` does carry role-equivalent authority names (`V139` seed data + registration services verified); the H1 design change above adopts exactly this derivation rather than a new claim, so the "explicit, stated decision" the review asked for is the design itself.
- **M6 (AC3 vacuously satisfiable) — CONFIRMED, addressed.** Added an explicit spec case for the `skp`-absent degraded path, and reworded AC3 to state that `/dashboard` is a correct outcome when no role is known, not a forbidden one.
- **M7 (silent departure from the ledger's sequencing) — CONFIRMED, disclosed** in the AC3 Context section above.
- **L1/L2 (citation drift, incl. `AuthService.authenticate()` not existing) — CONFIRMED and fixed throughout** this revision. The method is `AuthService.login()`.
- **§6 of the review (findings that did not survive its own adversarial pass)** were re-spot-checked directly (e.g. the `/otp` route object really is `routes.js:24-28`, not `:22-26`) and found accurate; not revisited further here.

---

## Dev Agent Record

### Debug Log

- **Task 1 (AC1):** Documentation-only; no code path to exercise.
- **Task 2 (AC2):** Implemented `JwtManagerImpl.setSkillarsProfileCookie(...)`, called from `refreshLoginToken()` right after `createAndSetJwt(res, claims)`. Derives the role by mapping each `getAuthoritiesSilently(claims)` authority (stripping `"ROLE_"`) against `SkillarsRole.valueOf(...)`, falling back to `"ADMIN"` when none match. Mutation-checked both new tests (`JwtManagerImplTest.testRefreshLoginToken_setsSkillarsProfileCookieFromRolesClaim`/`_fallsBackToAdminWhenNoAuthorityMapsToSkillarsRole`) and the extended `SecurityIT.loginWith2FAWhenAccountEnabled()` assertion by reverting `JwtManagerImpl.java` to its pre-fix HEAD content and re-running — the two unit tests failed with `RuntimeException: Cookie not found: skp` and the IT assertion failed with `Expecting value to be true but was false`, then all three passed again after restoring the fix.
- **Task 3 (AC3):** `OtpPage.vue` now calls `authStore.hydrateFromCookie()` after `initSession()`, then applies `LoginPage.vue`'s exact open-redirect guard with `routeForRole(authStore.role)` as the fallback. New spec file `OtpPageSpec.js` (no prior spec existed for this page) seeds `authStore.role` via `createTestingPinia`'s `initialState` rather than a real cookie round-trip, since `createTestingPinia` stubs actions (including `hydrateFromCookie`) by default — the thing under test is the redirect mechanism, not the store's own cookie parsing (already covered by the store's own behavior). Mutation-checked by reverting to pre-fix HEAD content: 2 of the 4 new cases (role-fallback, unsafe-redirect-rejected) went red as expected; the other 2 (safe-redirect-honored, no-role-default) stayed green under both versions since the old code also behaved correctly for those two inputs — confirms the assertions are testing the specific behavior each targets, not a tautology.
- **Task 4 (AC4):** One-line change in `VideoManagementPage.vue`'s 403 handler. New spec file `VideoManagementPageSpec.js` (no prior spec existed for this page). Mutation-checked by reverting to pre-fix HEAD content — the test's one assertion went red (`expected '/dashboard' to be '/player/dashboard'`), then green again restored.
- **Task 5 (AC5):** `AuthServiceTest`/`AuthServiceIT` named in the story's own Task 5 text do not exist in the repo (confirmed via `find`) — `AuthService` itself is untouched by this story, so there was nothing to regress-test there. Ran the real adjacent suite instead: `JwtManagerImplTest`, `SecurityIT`, `AuthResourceIT`, `JWTAuthenticationFilterTest`, `JWTAuthorizationFilterTest`, `SessionCookieContractTest` — all green, see Completion Notes for counts. No local `mvn verify` (standing project convention).

### Completion Notes

- **AC1:** `docs/deployment/secrets-reference.md` gained a new "Stale `HCLOUD_TOKEN` / `HETZNER_VOLUME_ID`..." section, documenting the real inertness mechanism (plain exported-env-var check in `provision.sh`, not a file read) and the one genuine trigger (an operator manually exporting the stale value). No code/behavior change.
- **AC2:** `JwtManagerImpl.refreshLoginToken()` now sets the `skp` cookie, deriving the role from the existing `ROLES` claim — no new JWT claim added, `createLoginCookies`/`TokenCreatorImpl.toClaims`/`SecurityConstants`/`ClaimsExtractorImpl` untouched. Backend tests: `JwtManagerImplTest` 35/35 passed (2 new cases: real-role mapping via `ROLE_COACH`, and the `ROLE_LTD_ADMIN`→`ANONYMOUS` fallback, added post-review per the "Fallback redesign" above); `SecurityIT` 1/1 (the extended `loginWith2FAWhenAccountEnabled` presence-only assertion). Both mutation-checked genuinely red/green.
- **AC3:** `OtpPage.vue`'s redirect fallback is now `routeForRole(authStore.role)` with `LoginPage.vue`'s exact open-redirect guard. New `OtpPageSpec.js`, 4/4 passed (role-fallback, unsafe-redirect-rejected — both mutation-checked red/green; safe-redirect-honored and no-role-default-to-/dashboard as the explicitly-intended degraded case).
- **AC4:** `VideoManagementPage.vue`'s 403 handler now redirects to `/player/dashboard`. New `VideoManagementPageSpec.js`, 1/1 passed, mutation-checked red/green.
- **AC5:** Full frontend Vitest suite: 208/208 passed across 30 files (`npx vitest run` from `src/frontend`), including both new spec files. ESLint and Prettier both clean on every touched frontend file. Backend targeted suite: `JwtManagerImplTest` 35/35, `SecurityIT` 1/1 (targeted) then re-confirmed together with the rest at 9/9 (full class), `AuthResourceIT` 9/9, `JWTAuthenticationFilterTest` 11/11, `JWTAuthorizationFilterTest` 16/16, `SessionCookieContractTest` 1/1 — zero failures, zero errors across all. No local `mvn verify` run (standing convention — GitHub CI is the sole full-verification gate). Manual browser verification not performed for AC2/AC3, as disclosed in the story itself (neither `/otp` nor its trigger endpoint `/authenticate` is reachable from the live UI) — not a gap, a stated scope boundary.
- **Net result:** all 5 ACs implemented and independently mutation-checked where a mutation check was feasible (every new/extended test, both backend and frontend). No regressions found in any adjacent suite.

### File List

- `docs/deployment/secrets-reference.md` (modified — AC1)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` (modified — AC2)
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java` (modified — AC2, 2 new test cases)
- `src/test/java/com/softropic/skillars/platform/security/SecurityIT.java` (modified — AC2, 1 new assertion)
- `src/frontend/src/pages/auth/OtpPage.vue` (modified — AC3)
- `src/frontend/src/pages/auth/__tests__/OtpPageSpec.js` (new — AC3)
- `src/frontend/src/pages/VideoManagementPage.vue` (modified — AC4)
- `src/frontend/src/pages/__tests__/VideoManagementPageSpec.js` (new — AC4)
- `.dockerignore` (new — AC6)
- `src/main/java/com/softropic/skillars/platform/security/contract/SkillarsRole.java` (modified — post-review fallback redesign)

### Change Log

- 2026-10-04: Implemented all 5 ACs via `/bmad-dev-story`. AC1 ops note added; AC2 backend `skp`-cookie fix on the OTP-completion path (role derived from existing `ROLES` claim, 2 new/extended backend tests, both mutation-checked); AC3 `OtpPage.vue` role-aware redirect + open-redirect guard (new spec, 4 cases, mutation-checked); AC4 `VideoManagementPage.vue` 403 redirect retarget (new spec, mutation-checked); AC5 full targeted backend suite + full frontend Vitest suite green, ESLint/Prettier clean. Status: ready-for-dev → review.
- 2026-10-05: Code review (Opus 5, `/bmad-code-review`) found `.dockerignore` present but undeclared, and missing `src/frontend/node/` + `src/frontend/coverage/` from its own exclusions. Resolved: added AC6 declaring the file in scope, added the two missing exclusion patterns, and fixed 8 further review-confirmed patch items (OtpPage displayName hydration on the post-OTP session; `hydrateFromCookie()` call coverage; absolute-redirect guard coverage; anchored `SecurityIT` skp assertion; discriminating `VideoManagementPageSpec` notify assertion; quoted-id cross-reference comment on the new `skp` site; `review-execution-log.md` test-description corrections; AC1 ops note now directs revoking `HCLOUD_TOKEN` at the provider; Context section's `V139` citation corrected). Status: review → done.
- 2026-10-05 (later, direct reviewer instruction, Mbah): the earlier review had dismissed the `setSkillarsProfileCookie` fallback's `"ADMIN"` string as an accepted, `AuthService`-mirroring design decision (see Dismissed item 6). On a closer read of the diff, the reviewer judged this wrong — re-investigation confirmed the "mirrors `AuthService`" framing was shape-only: `AuthService`'s `null -> "ADMIN"` fires for real admins with an unset DB role column, while this fallback fires for a non-admin authority with no `SkillarsRole` mapping (`ROLE_LTD_ADMIN`/`ROLE_USER`), so the two populations differ and the original fallback mislabeled a non-admin as one. Added `SkillarsRole.ANONYMOUS` (documented as cookie-fallback-only, never to be persisted — `User.skillarsRole`'s Envers audit table has a CHECK constraint that doesn't include it, and `GdprErasureService`'s role branching has no case for it) and switched `setSkillarsProfileCookie` to resolve a `SkillarsRole` value throughout the stream, calling `.name()` once at the end instead of hardcoding `"ADMIN"` as a literal. Updated the one backend test asserting the old fallback value, and the `SecurityIT` comment describing it. Full frontend suite re-run green (209/209, unaffected — the fallback value is backend-only and frontend specs seed `authStore.role` directly). See "Fallback redesign" in the AC2 Context section for the full detail. Status unchanged at done.
