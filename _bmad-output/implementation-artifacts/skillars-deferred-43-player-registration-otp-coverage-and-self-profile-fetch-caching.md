# Story Deferred-43: Player Registration OTP Test Coverage & Shared Self-Profile Fetch Caching

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want two independently-real, independently-small, decision-light hygiene gaps found by re-mining the
full `deferred-work.md` ledger closed in one pass — `PlayerRegistrationService`'s OTP-issuing flow gaining
the automated test coverage its `CoachRegistrationService`/`ParentRegistrationService` siblings already
have, and three pages' duplicated, uncached self-player-profile fetch consolidated into one cached,
logout-invalidated lookup on the existing `playerStore` —
so that these gaps stop accumulating as separate single-item stories, matching the bundling convention every
prior `skillars-deferred-*` pass has followed.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-42`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1607 lines as of this story's creation) was
re-mined following the file's own documented protocol (near its top): sections are **not** chronological,
so the date in each `## Deferred from:` heading was read individually; an item is non-actionable (skipped)
if it carries `[CLOSED by ...]`, `[PICKED UP by ...]`, `[STALE — ...]`, `[DISMISSED — ...]`,
`[WITHDRAWN — ...]`, `[SUPERSEDED — ...]`, `[MITIGATED — ...]`, `[AUDIT ...]`, or prose saying it was
"examined and deliberately left alone" / "recorded so a later reviewer does not read [it] as an oversight"
/ needs a product or design decision. The remaining untagged bullets across the whole file were triaged in
batches (by section, then by keyword), and every genuine candidate was re-verified against the live code —
not trusted from the ledger's own prose — before being used, including two items discovered during that
re-verification to already be resolved (bundled as this story's own hygiene AC, mirroring `deferred-42`
AC4's pattern).

**Two genuinely open, decision-light items were found and are bundled here:**

- **`PlayerRegistrationService.generateOtp()` has zero automated test coverage anywhere in this repo, while
  its `CoachRegistrationService`/`ParentRegistrationService` siblings are each covered by a dedicated
  `*ResourceIT`.** `## Deferred from: code review of
  skillars-deferred-42-otp-secure-random-reuse-session-pack-dead-query-and-duplicate-i18n-key-cleanup
  (2026-08-20)`: "`PlayerRegistrationService.generateOtp()`'s `SecureRandom` reuse fix has zero automated
  test coverage anywhere in this repo. `grep -rln \"PlayerRegistrationService\" src/test` returns zero
  hits ... Standing candidate for whenever backend test coverage for this service is added." Re-verified
  today: `grep -rln "PlayerRegistrationService" src/test` still returns zero hits.
  `CoachRegistrationResourceIT.java` and `ParentRegistrationResourceIT.java` both exist and both exercise
  their service's full register → verify-email → verify-phone flow end to end; no equivalent file exists
  for `PlayerRegistrationService`, and `PlayerRegistrationResource.java` (`/api/security/player/register`,
  `/verify-email`, `/verify-phone`, `/resend-verification`) has an identical shape to
  `CoachRegistrationResource`/`ParentRegistrationResource`, confirmed by direct read of all three resource
  classes. This codebase already has an established, directly-mirrorable pattern for exactly this:
  `CoachRegistrationResourceIT.registerCoach_validData_returns200AndUserIsUnverified` (registration
  succeeds, user lands `UNVERIFIED`) and
  `CoachRegistrationResourceIT.verifyEmail_validToken_setsEmailVerifiedAndReturnsUserId` (the one existing
  Coach test that actually drives `generateOtp()` through the real API, since `PlayerRegistrationService
  .verifyEmail()` — like its Coach/Parent siblings — is the method that calls `generateOtp()` and persists
  the resulting `phone_otp_tokens` row). **Candidate for this story (AC1).**

- **`CoachPublicProfilePage.vue`, `BookingRequestPage.vue`, and `PlayerHomeRedirectPage.vue` each
  independently call `playerRegistrationApi.getMyProfile()` on mount for the same logical self-booking-player
  session, with no shared cache.** `## Deferred from: code review of skillars-uat-5-player-self-booking
  (2026-08-12)`, D1: "Duplicated, uncached self-profile fetch across two pages. `CoachPublicProfilePage.vue`
  and `BookingRequestPage.vue` each independently call `playerRegistrationApi.getMyProfile()` on mount for the
  same logical player session, rather than sharing a composable/store — a minor redundant network
  round-trip ... a shared cache would be a clean follow-up." Re-verified today, and expanded beyond the
  ledger item's own two-page framing: `grep -rn "getMyProfile" src/frontend/src` returns **three** hits, not
  two — `CoachPublicProfilePage.vue:310`, `BookingRequestPage.vue:600`, and
  `PlayerHomeRedirectPage.vue:16` (this third page resolves the player's own profile id on every player
  login, to redirect to `/player/locker-room/<id>` or `/player/profile-builder` on a 404 — the exact same
  "resolve this self-booking player's own id" purpose as the other two, for the exact same session; its own
  code even names `PlayerHomeRedirectPage.vue` as the precedent it's following). `CoachPublicProfilePage
  .vue:309-311` and `BookingRequestPage.vue:599-601` each carry a near-identical `try { const profile =
  await playerRegistrationApi.getMyProfile(); selfPlayerId.value = profile.id } catch (profileErr) { ... }`
  block (each page's own `selfPlayerId` ref, declared independently at `CoachPublicProfilePage.vue:266` and
  `BookingRequestPage.vue:245`), confirmed byte-for-byte structurally identical by direct read of both
  files; `PlayerHomeRedirectPage.vue:14-22` follows the same shape with a redirect instead of a stored ref.
  Since `PlayerHomeRedirectPage.vue` runs on essentially every player login, it's typically the *first* of
  the three calls in a session — folding it into the same cache means the cache is usually already warm by
  the time the player reaches either of the other two pages. All three pages are folded into AC2's scope.
  **Candidate for this story (AC2).**

**Two additional items were found to be stale during this research — not story material, but corrected
here as a hygiene by-product (AC3):**

- **D7 (`## Deferred from: code review of
  skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`), never audited before
  now — found already fixed.** "`docker compose build` silently no-ops. `docker-compose.yml`/
  `docker-compose.local.yml` reference `image: ${APP_IMAGE}` ... with no `build:` key in either file ...
  Fix is either a `build:` block in `docker-compose.local.yml` or an explicit `docker build` step
  documented." Re-verified today: `docker-compose.local.yml:1-13` already carries a full `build: {context:
  ., dockerfile: Dockerfile}` block on the `app` service, with an in-file comment explaining exactly this
  hazard and citing the `deferred-17` dev's own time-cost — added by an earlier, unannotated story. Superseded
  by shipped code, not a gap.
- **D8 (`## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`),
  never audited before now — found already fixed.** "the batch reservation records the whole price under
  `stripeCharged`, before the wallet split is known ... an operator reading a stuck batch row in the
  runbook's Scenario 4 should know the figures are an upper bound on the Stripe leg, not the actual split."
  Re-verified today: `docs/deployment/runbook.md:321-324` already carries a blockquote directly above
  Scenario 4's detection query — "**The `credit_debited` / `stripe_charged` columns on a CAPTURE_PENDING
  row are a reconciliation hint, not an accounting record — do not reconcile against them as if they
  were.**" — covering exactly the single-vs-batch distinction and the "upper bound, not actual split"
  caveat D8 asked for. Superseded by shipped documentation, not a gap.

**Decision made during this story's creation — why these two and not others:** the ledger was triaged in
full (every section, not just the tail); the overwhelming majority of untagged bullets either (a) already
carry their own "examined and deliberately left alone" / "recorded so a later reviewer does not read this
as an oversight" / "by design" reasoning on record (e.g. `AdminBootstrapRunner`'s no-elevate-existing-account
behavior, `AvailabilityService.computeAvailableSlots`'s per-segment grid anchoring, the batch path's
deliberate absence of a create-time cross-booking overlap check), (b) explicitly need a product or design
decision before any fix is possible (e.g. the `canonical_timezone` dual-column reconciliation, `@IanaTimezone`
accepting fixed offsets, `ConfigService`'s 5-minute cache TTL, `RescheduleService`'s missing
availability-window check — every one of these names multiple candidate fixes and says a decision must come
first), or (c) are restatements of the standing, repeatedly-declined frontend-test-infrastructure investment
(every `skillars-deferred-*` pass since `-17` has left this alone for the same reason, and this pass leaves
it alone too). AC1 and AC2 are the only two items found that are simultaneously real, small, decision-light,
and directly mirror an already-shipped pattern in this codebase — bundled here purely because both clear
that bar and this pass was asked to bundle rather than defer them a further time. Unlike most prior
`skillars-deferred-*` passes, only two substantive items cleared the bar this time (the ledger is
increasingly thin on decision-light items after 42 prior passes); AC1 is a moderately-sized new test file
(not a one-line mirror), so this story is not "small" by task weight even though it bundles fewer items than
usual.

## Acceptance Criteria

1. **AC1 — `PlayerRegistrationService`'s OTP-issuing flow gains automated test coverage, mirroring
   `CoachRegistrationResourceIT`'s pattern narrowed to the two tests that matter for this gap.** Create
   `src/test/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResourceIT.java`, extending
   `AbstractIntegrationTest` and annotated `@Sql({SecurityIT.SEC_DATA_SQL_PATH})`, mirroring
   `CoachRegistrationResourceIT.java`'s structure (`HttpTestClient`, `JdbcTemplate`, `jsonHeaders()`,
   `baseUrl()`). `CoachRegistrationResourceIT` has a `@BeforeEach` that manually inserts `ROLE_COACH`, but
   that insert is actually redundant — `ROLE_COACH` is already seeded by `V21__skillars_security_extension
   .sql` and restored before every test by `DatabaseResetTestExecutionListener`'s reference-data mechanism,
   the same way `ROLE_PLAYER` (id 102) is seeded by `V84__player_self_registration.sql` and restored. This
   new IT simply doesn't copy that now-unnecessary pattern — no `@BeforeEach` authority seed is needed here
   either. Two tests, both required:
   - `registerPlayer_validData_returns200AndUserIsUnverified` — POSTs to `/api/security/player/register`
     with a valid `PlayerRegistrationRequest` body (`firstName`, `lastName`, `email`, `password` ≥8 chars,
     `phone` matching `\+?[\d\s\-().]{7,20}`, and a `dateOfBirth` far enough in the past to resolve
     `AgePolicyService.isMinor()` to `false`, e.g. `"1995-06-15"` — `PlayerRegistrationService.java:83-86`
     rejects a minor's own self-registration with `security.playerMustBeAdult` before any user row is
     written), asserts `200 OK`, then queries `main."user"` and asserts `skillars_role = 'PLAYER'`,
     `verification_status = 'UNVERIFIED'`, `activated = false` — mirrors
     `CoachRegistrationResourceIT.registerCoach_validData_returns200AndUserIsUnverified` exactly, adapted
     for the extra required `dateOfBirth` field.
   - `verifyEmail_validToken_issuesOtpAndSetsEmailVerified` — registers a player as above, reads the
     resulting `main.email_verification_tokens` row's token by joining on the registered email (same query
     shape as `CoachRegistrationResourceIT.verifyEmail_validToken_setsEmailVerifiedAndReturnsUserId`), GETs
     `/api/security/player/verify-email?token=<token>`, asserts `200 OK` and the response body's
     `nextStep == "verify-phone"`, then asserts **both** (a) `main."user".verification_status` is now
     `EMAIL_VERIFIED` for that email, **and** (b) a `main.phone_otp_tokens` row now exists for that user's
     id with `used = false` and a non-null `otp_hash`/`expires_at` — the second assertion is what actually
     proves `generateOtp()` (`PlayerRegistrationService.java:240-244`) ran and its result was persisted;
     the raw OTP value itself is never returned to the client in this flow (same as Coach/Parent), so
     asserting the token row's existence and shape is the correct, and only possible, proof point.

   **Full parity with `CoachRegistrationResourceIT`'s other 9 tests (duplicate-email, missing-field,
   expired-token, used-token, correct/wrong-OTP, resend-verification, langKey handling) is explicitly
   out of scope for this AC** — those paths are identical, already-shipped, shared-pattern code
   (`registerPlayer`/`verifyEmail`/`verifyPhone`/`resendVerificationEmail` all follow the exact same shape
   as their Coach/Parent siblings, which are already covered), and this AC's job is narrowly to close the
   "zero coverage" gap on the one method (`generateOtp()`) the source ledger item was actually about, not to
   build out full IT parity for `PlayerRegistrationService` in one pass. Recording the narrower-than-full
   scope explicitly (Dev Notes) rather than leaving it implicit, matching this ledger's own established
   precedent for accepted narrower-than-ideal scope decisions (e.g. `skillars-deferred-27`'s
   `coachId`-mismatch-left-unverified note).

2. **AC2 — `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`, and `PlayerHomeRedirectPage.vue`'s
   duplicated self-player-profile fetch is consolidated into one cached lookup on `playerStore.js`, with the
   cache reset on logout.** Add to `src/frontend/src/stores/playerStore.js`:
   - A `selfPlayerId = ref(null)` state field.
   - An `async function fetchSelfPlayerId()` that: returns `selfPlayerId.value` immediately if already
     non-null (cache hit — no network call); otherwise calls `playerRegistrationApi.getMyProfile()`,
     sets `selfPlayerId.value = profile.id` on success and returns it; **does not catch or swallow a
     rejection** — the underlying promise (including a 404 for "no profile yet") propagates to the caller
     unchanged, so each page's own existing catch block (which differ slightly in their explanatory
     comments but both currently do the same `if (profileErr.response?.status !== 404) { $q.notify(...) }`,
     and `PlayerHomeRedirectPage.vue`'s bare `catch { router.replace('/player/profile-builder') }`) is left
     completely untouched. A **failed** lookup must not be cached — `selfPlayerId.value` stays
     `null` on a 404, so a later call (e.g. after the player finishes the profile-builder) retries the
     fetch rather than being stuck with a cached miss; only a **successful** lookup is cached, since a
     player's own `id` cannot legitimately change mid-session.
   - A `function resetSelfPlayerId()` that sets `selfPlayerId.value = null` — this is the logout-invalidation
     hook described below. It resets only `selfPlayerId`; `players`/`activePlayerId` are a pre-existing,
     separate staleness case (the parent-side children list already survives a same-tab logout/login
     unreset) and are explicitly out of scope for this AC — do not touch them.
   - Export `selfPlayerId`, `fetchSelfPlayerId`, and `resetSelfPlayerId` alongside the store's existing
     exports.

   In `CoachPublicProfilePage.vue:309-311` and `BookingRequestPage.vue:599-601`, replace `const
   profile = await playerRegistrationApi.getMyProfile(); selfPlayerId.value = profile.id` with
   `selfPlayerId.value = await playerStore.fetchSelfPlayerId()` — both pages already have `playerStore`
   in scope via their existing `usePlayerStore()` import, so no new import is needed there. Each page's own
   local `selfPlayerId` ref (declared independently at `CoachPublicProfilePage.vue:266` and
   `BookingRequestPage.vue:245`) is left in place unchanged — only the fetch call inside each `try` block
   changes; the surrounding `try`/`catch`/404-silent/`$q.notify` shape, and every other read of each page's
   own `selfPlayerId.value` (e.g. `CoachPublicProfilePage.vue:338`, `BookingRequestPage.vue:248`), is
   untouched. In `PlayerHomeRedirectPage.vue`, add a `usePlayerStore()` import (it has none today), replace
   `const profile = await playerRegistrationApi.getMyProfile(); router.replace(...)` with `const id = await
   playerStore.fetchSelfPlayerId(); router.replace(`/player/locker-room/${id}`)`, and leave the surrounding
   `try`/`catch`/redirect-to-profile-builder shape untouched. `getMyProfile()` is confirmed (via grep) to be
   the only use of `playerRegistrationApi` in all three files, so once the call site is replaced, the import
   becomes genuinely unused — drop the now-dead `playerRegistrationApi` import in all three files rather than
   leaving it (an unused import binding is an `eslint:recommended` `no-unused-vars` error under this
   project's `eslint.config.js`, so keeping it would fail Task 2.6's lint check).

   **Logout invalidation:** `MainLayout.vue`'s `handleLogout()` (`src/frontend/src/layouts/MainLayout.vue:
   297-301`) navigates via `router.push('/login')` — an in-SPA route change, not a full reload — and neither
   it nor `authStore.logout()` resets any Pinia store, so `playerStore` survives a logout/login cycle in the
   same browser tab today. Because `fetchSelfPlayerId()` deliberately skips its network call once
   `selfPlayerId.value` is set, a stale cached id would otherwise carry over from one logged-out player to
   the next player who logs in on the same tab — and `BookingRequestPage.vue` feeds this value directly into
   the `playerId` used to submit a booking request, so a cross-account cache hit here would misattribute a
   real booking, not just show stale UI. Add a `usePlayerStore()` import to `MainLayout.vue` and call
   `playerStore.resetSelfPlayerId()` inside `handleLogout()`, after `authStore.logout()`.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the `## Deferred from: code review of
     skillars-deferred-42-otp-secure-random-reuse-session-pack-dead-query-and-duplicate-i18n-key-cleanup
     (2026-08-20)` `PlayerRegistrationService.generateOtp()` test-coverage item with
     `` `[PICKED UP by skillars-deferred-43 AC1]` ``.
   - Tag the `## Deferred from: code review of skillars-uat-5-player-self-booking (2026-08-12)` D1 item
     (duplicated self-profile fetch) with `` `[PICKED UP by skillars-deferred-43 AC2]` ``.
   - Tag D7 (`## Deferred from: code review of
     skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`) with
     `` `[STALE — verified against current code by skillars-deferred-43 story creation, 2026-08-20: already
     fixed. docker-compose.local.yml:1-13's app service already carries a build: {context: .,
     dockerfile: Dockerfile} block, with an in-file comment explaining the exact hazard this item describes.
     Added by an earlier story, unannotated in this ledger.]` `` — do not delete the item, per this file's
     own "delete only once genuinely implemented, not once merely annotated" convention; the tag is enough
     for future audits to skip it.
   - Tag D8 (`## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`)
     with `` `[STALE — verified against current code by skillars-deferred-43 story creation, 2026-08-20:
     already fixed. docs/deployment/runbook.md:321-324 already carries a blockquote directly above Scenario
     4's detection query stating the credit_debited/stripe_charged columns on a CAPTURE_PENDING row are a
     reconciliation hint, not an accounting record, covering exactly the single-vs-batch distinction and the
     upper-bound-not-actual-split caveat this item asked for. Added by an earlier story, unannotated in this
     ledger.]` ``.

## Tasks / Subtasks

- [x] Task 1: `PlayerRegistrationService` OTP flow test coverage (AC: #1)
  - [x] 1.1 Create `PlayerRegistrationResourceIT.java`, mirroring `CoachRegistrationResourceIT.java`'s
    class shape (imports, `@Sql`, `HttpTestClient`/`JdbcTemplate` autowiring, `jsonHeaders()`/`baseUrl()`
    helpers). No `@BeforeEach` authority seed needed — `ROLE_PLAYER` is already seeded by `V84`.
  - [x] 1.2 Add `registerPlayer_validData_returns200AndUserIsUnverified`, adapted from
    `CoachRegistrationResourceIT`'s equivalent test with the extra required `dateOfBirth` field (use an
    adult date, e.g. `"1995-06-15"`).
  - [x] 1.3 Add `verifyEmail_validToken_issuesOtpAndSetsEmailVerified`, driving the real `verifyEmail`
    endpoint and asserting both the `EMAIL_VERIFIED` status transition and the resulting
    `main.phone_otp_tokens` row's existence/shape (proves `generateOtp()` ran).
  - [x] 1.4 Run the new `PlayerRegistrationResourceIT` and confirm both tests green.
- [x] Task 2: Shared, cached self-player-profile fetch (AC: #2)
  - [x] 2.1 Add `selfPlayerId` state, `fetchSelfPlayerId()`, and `resetSelfPlayerId()` actions to
    `playerStore.js`. Do not catch/swallow the underlying rejection inside `fetchSelfPlayerId()` — let it
    propagate. `resetSelfPlayerId()` only clears `selfPlayerId`; do not touch `players`/`activePlayerId`.
  - [x] 2.2 In `CoachPublicProfilePage.vue`, replace the direct `playerRegistrationApi.getMyProfile()` call
    inside the existing `try` block with `await playerStore.fetchSelfPlayerId()`. Leave the surrounding
    `try`/`catch`, the 404-silent branch, and every other line untouched. Drop the now-dead
    `playerRegistrationApi` import.
  - [x] 2.3 Apply the identical change to `BookingRequestPage.vue` (drop its now-dead
    `playerRegistrationApi` import too).
  - [x] 2.4 In `PlayerHomeRedirectPage.vue`, add a `usePlayerStore()` import, replace the
    `playerRegistrationApi.getMyProfile()` call with `await playerStore.fetchSelfPlayerId()` feeding the
    same redirect, leave the `try`/`catch`/redirect-to-profile-builder shape untouched, and drop the now-dead
    `playerRegistrationApi` import.
  - [x] 2.5 In `MainLayout.vue`, add a `usePlayerStore()` import and call `playerStore.resetSelfPlayerId()`
    inside `handleLogout()`, after `authStore.logout()`.
  - [x] 2.6 Run `npx eslint` on all five touched frontend files and confirm clean.
- [x] Task 3: Ledger hygiene (AC: #3) — apply the two `[PICKED UP]` tags and two `[STALE]` annotations
  specified in AC3 above.

### Review Findings

- [x] [Review][Patch] Self-profile cache isn't reset on all logout paths — AC2's own stated rationale for `resetSelfPlayerId()` is a cross-account booking-misattribution risk, but two live in-SPA logout paths never called it: `App.vue`'s `handleSessionExpired()` (idle/session-timeout auto-logout, `src/frontend/src/App.vue:22-34`) and `useSession.js`'s `handleLogout()` (the "Logout" button inside `SessionWarningDialog.vue`, `src/frontend/src/composables/useSession.js:56-72`) — both do an in-SPA `router.push('/login')`, not a reload, exactly like `MainLayout.vue`'s patched path. Fixed: wired `playerStore.resetSelfPlayerId()` into both, mirroring `MainLayout.vue`'s call.
- [x] [Review][Patch] `fetchSelfPlayerId()` has no in-flight-request deduplication — the cache-hit guard (`src/frontend/src/stores/playerStore.js:25`) only short-circuits once `selfPlayerId.value` is already set; two callers invoked before the first request settles (plausible across `PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue` during navigation) each fire an independent `getMyProfile()` call, and — combined with the logout-path gap above — allow whichever call resolves last to silently win the cache regardless of which session it belongs to. Fixed: added a shared in-flight-promise guard plus a generation counter (bumped by `resetSelfPlayerId()`) so a pre-logout request resolving after a reset no longer writes into the cache.
- [x] [Review][Patch] `fetchSelfPlayerId()` permanently caches an `undefined` id and never retries if the profile response is malformed [src/frontend/src/stores/playerStore.js:25] — Fixed: the cache write now only happens when `profile?.id != null`, so a malformed response leaves `selfPlayerId` unset and the next call retries.
- [x] [Review][Defer] No automated test coverage added for `playerStore.js`'s new caching/reset logic or `MainLayout.vue`'s reset call [src/frontend/src/stores/playerStore.js:24-33] — deferred, pre-existing (standing repo-wide frontend test-infrastructure gap, repeatedly accepted across prior `skillars-deferred-*` stories)
- [x] [Review][Defer] `PlayerHomeRedirectPage.vue`'s bare `catch` treats any error (500, network failure, not just a 404) as "no profile yet" and silently redirects to the profile-builder [src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:19-22] — deferred, pre-existing (shape explicitly left untouched by this story's own AC2 instruction)

## Dev Notes

- **This story bundles two unrelated fixes across two areas (backend security-module test coverage,
  frontend marketplace/parent-module caching) by explicit instruction — do not look for a unifying theme
  beyond "small, real, decision-light, and this pass was asked to bundle."**
- **AC1 is deliberately narrower than full IT parity with `CoachRegistrationResourceIT`'s other 9 tests.**
  Do not add duplicate-email, missing-field, expired/used-token, correct/wrong-OTP, resend-verification, or
  langKey tests in this pass — those paths are identical, already-shipped, shared-pattern code; expanding
  scope beyond the two tests specified needs its own sign-off, not a unilateral expansion. If implementation
  finds the two specified tests insufficient to actually exercise `generateOtp()` (e.g. a mocked dependency
  short-circuits the real code path), stop and re-scope rather than silently expanding test count.
- **AC1's second test is the load-bearing one.** `registerPlayer_validData_...` alone would prove
  registration works but never touches `generateOtp()` — only `verifyEmail_validToken_...` does, since
  `verifyEmail()` (not `registerPlayer()`) is the method that calls `generateOtp()` and persists the OTP
  token row (`PlayerRegistrationService.java:152-160`). Do not skip or weaken this second test.
- **AC2's store change must not swallow errors.** `fetchSelfPlayerId()` exists to fetch-and-cache, not to
  own the 404-silent/notify UX decision — that stays exactly where it already lives, in each page's own
  `catch` block. If a future page needs different error handling for the same lookup, it can still catch
  the store method's rejection itself; centralizing the *fetch*, not the *error UX*, is the intentionally
  narrow scope here.
- **AC2's cache must not persist a failure.** Only set `selfPlayerId.value` on success. A player who has not
  yet finished the profile-builder (404 today) and later completes it mid-session must not be stuck with a
  cached `null` forever — the next call to `fetchSelfPlayerId()` after a prior 404 must re-attempt the
  network call, exactly as today's uncached per-page calls already do implicitly.
- **AC2's cache must be reset on logout.** This app's logout (`MainLayout.vue`'s `handleLogout()`) is an
  in-SPA `router.push('/login')`, not a full reload, so `playerStore` is a singleton that survives a
  logout/login cycle in the same browser tab. Because `fetchSelfPlayerId()` skips the network call once
  cached, an unreset cache would let a second player who logs in on the same tab inherit the first player's
  `selfPlayerId` — and `BookingRequestPage.vue` feeds that value straight into the booking request's
  `playerId`, so this is a real misattribution risk, not just stale UI. `resetSelfPlayerId()` must be called
  from `handleLogout()` after `authStore.logout()`.
- **AC2's cache-reset scope is deliberately narrow: `selfPlayerId` only.** `players`/`activePlayerId` have
  the same unreset-on-logout gap today, but it's lower-stakes (a parent's stale child-selection UI, not a
  wrong-player booking) and pre-existing rather than newly introduced by this story — leave it alone.
  Expanding the reset to cover it needs its own sign-off, not a unilateral bundling into this AC.
- **AC3's ledger hygiene (Task 3) was already applied in this story's own creation commit** — all two
  `[PICKED UP by skillars-deferred-43 AC1-2]` tags and both `[STALE — ...]` annotations (D7 under
  `skillars-deferred-17`, D8 under `skillars-uat-3`) are already present in `deferred-work.md` as committed
  alongside this story file. This deviates from this ledger's normal after-the-fact `[CLOSED by ...]`
  convention (tagging is usually applied once `dev-story` ships the fix, not at story-creation time) — noted
  here so `dev-story` does not waste a pass trying to re-apply tags that already exist. Confirm the two
  `[PICKED UP]` tags and two `[STALE]` annotations are still present verbatim in `deferred-work.md` before
  marking Task 3 complete; do not re-run the edits.
- Per `docs/validation-strategy.md`, run targeted tests only (the new `PlayerRegistrationResourceIT` for
  AC1, `npx eslint` on the five touched frontend files for AC2) — do not run `mvn verify` unless targeted
  tests prove insufficient or this is final pre-PR validation.

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResourceIT.java` — new file
  (AC1).
- `src/frontend/src/stores/playerStore.js` — `selfPlayerId` state + `fetchSelfPlayerId()` +
  `resetSelfPlayerId()` actions added (AC2).
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` — fetch call swapped inside the existing
  `try` block; dead `playerRegistrationApi` import dropped (AC2).
- `src/frontend/src/pages/parent/BookingRequestPage.vue` — same change (AC2).
- `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` — fetch call swapped, `usePlayerStore()` import
  added, dead `playerRegistrationApi` import dropped (AC2).
- `src/frontend/src/layouts/MainLayout.vue` — `usePlayerStore()` import added, `playerStore.resetSelfPlayerId()`
  called from `handleLogout()` (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `[PICKED UP]` tags + two `[STALE]`
  corrections (AC3).
- No new backend production files (AC1 is test-only). No new frontend files — AC2 extends an existing
  store, does not create a new composable. No changes to `CoachRegistrationResourceIT.java`,
  `ParentRegistrationResourceIT.java`, `PlayerRegistrationService.java`, `PlayerRegistrationResource.java`,
  `docker-compose.local.yml`, `docs/deployment/runbook.md`, or `src/frontend/src/stores/auth.store.js` — all
  are read-only precedents, or (for the docker-compose/runbook pair) already-correct content this story does
  not modify.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-42-...` — AC1's source]
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/PlayerRegistrationService.java`
  lines 82-121 (`registerPlayer`), 123-165 (`verifyEmail`), 240-244 (`generateOtp`) — AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResource.java` —
  AC1's endpoint shape]
- [Source: `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java`
  lines 73-178, 402-418 — AC1's mirrored pattern]
- [Source: `src/main/resources/db/migration/V84__player_self_registration.sql` — confirms `ROLE_PLAYER`
  (id 102) is pre-seeded]
- [Source: `src/main/resources/db/migration/V21__skillars_security_extension.sql` lines 35-39 — confirms
  `ROLE_COACH` is also pre-seeded, making `CoachRegistrationResourceIT`'s `@BeforeEach` insert redundant]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-uat-5-player-self-booking (2026-08-12)` D1 — AC2's source]
- [Source: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` lines 266, 309-320 — AC2's first
  target]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue` lines 245-248, 597-610 — AC2's second
  target]
- [Source: `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` lines 1-22 — AC2's third target]
- [Source: `src/frontend/src/layouts/MainLayout.vue` lines 297-301 (`handleLogout`) — AC2's logout-invalidation
  target]
- [Source: `src/frontend/src/stores/playerStore.js` — AC2's existing `players`/`fetchPlayers()` shape (note:
  `fetchPlayers()` unconditionally re-fetches on every call — it is not itself a fetch-once-cache precedent;
  `fetchSelfPlayerId()` is the first cache-hit-checking function on this store)]
- [Source: `docker-compose.local.yml` lines 1-13 — AC3's D7 stale-item verification]
- [Source: `docs/deployment/runbook.md` lines 321-324 — AC3's D8 stale-item verification]

## Dev Agent Record

### Completion Notes

- **AC1**: Created `PlayerRegistrationResourceIT.java`, mirroring `CoachRegistrationResourceIT.java`'s
  class shape without the redundant `@BeforeEach` authority seed (per Dev Notes, `ROLE_PLAYER` is
  already seeded by `V84__player_self_registration.sql` and restored by
  `DatabaseResetTestExecutionListener`). Two tests added exactly as specified, no more:
  `registerPlayer_validData_returns200AndUserIsUnverified` and
  `verifyEmail_validToken_issuesOtpAndSetsEmailVerified` (the latter also asserts the
  `main.phone_otp_tokens` row's `used`/`otp_hash`/`expires_at`, proving `generateOtp()` ran and
  persisted). `mvn -o test -Dtest=PlayerRegistrationResourceIT`: **2/2 green**.
- **AC2**: Added `selfPlayerId` state + `fetchSelfPlayerId()` (cache-hit short-circuit, no
  catch/swallow) + `resetSelfPlayerId()` actions to `playerStore.js`. Swapped the direct
  `playerRegistrationApi.getMyProfile()` call for `playerStore.fetchSelfPlayerId()` in all three
  target pages (`CoachPublicProfilePage.vue`, `BookingRequestPage.vue`,
  `PlayerHomeRedirectPage.vue`), dropping the now-dead `playerRegistrationApi` import from each — each
  page's own `try`/`catch`/404-silent/redirect shape left untouched. Added `usePlayerStore()` +
  `playerStore.resetSelfPlayerId()` to `MainLayout.vue`'s `handleLogout()`, after `authStore.logout()`,
  scoped narrowly to `selfPlayerId` only. `npx eslint` on all five touched frontend files: **clean**
  (only pre-existing unrelated npm config warnings, no lint errors).
- **AC3**: Verified (not re-applied, per Dev Notes) that both `[PICKED UP by skillars-deferred-43
  AC1/AC2]` tags and both `[STALE — ...]` D7/D8 annotations are already present verbatim in
  `deferred-work.md` from this story's own creation commit — confirmed via grep, no edit needed, file
  not in this story's diff.
- Per `docs/validation-strategy.md`, only targeted tests were run (`PlayerRegistrationResourceIT` for
  AC1, `npx eslint` for AC2) — `mvn verify` not run.

### File List

- `src/test/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResourceIT.java` — new
  (AC1).
- `src/frontend/src/stores/playerStore.js` — `selfPlayerId` state + `fetchSelfPlayerId()` +
  `resetSelfPlayerId()` actions added (AC2).
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` — fetch call swapped to
  `playerStore.fetchSelfPlayerId()`, dead `playerRegistrationApi` import dropped (AC2).
- `src/frontend/src/pages/parent/BookingRequestPage.vue` — same change (AC2).
- `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` — fetch call swapped, `usePlayerStore()`
  import added, dead `playerRegistrationApi` import dropped (AC2).
- `src/frontend/src/layouts/MainLayout.vue` — `usePlayerStore()` import added,
  `playerStore.resetSelfPlayerId()` called from `handleLogout()` (AC2).

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 2-item story per explicit instruction not to create another small story. Re-mined `deferred-work.md` end to end (1607 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1 closes `PlayerRegistrationService.generateOtp()`'s zero-test-coverage gap (flagged by `skillars-deferred-42`'s own code review) by mirroring `CoachRegistrationResourceIT`'s pattern, narrowed to the two tests that actually matter for the gap — registration success and the `verifyEmail` flow that drives `generateOtp()` for real. AC2 consolidates `CoachPublicProfilePage.vue`/`BookingRequestPage.vue`'s duplicated, uncached self-player-profile fetch into a single cached `fetchSelfPlayerId()` action on the already-shared `playerStore`, mirroring that store's existing `players`/`fetchPlayers()` fetch-once-cache-in-store shape. AC3 additionally closes 2 stale ledger items (a `docker compose build` no-op fix and a `stripeCharged` reconciliation-hint runbook note, both `skillars-deferred-17`/`skillars-uat-3`-era items found already resolved by earlier, unannotated stories) as a research by-product of the full re-mine. This pass found the ledger thinner than most prior passes — after 42 prior `skillars-deferred-*` stories, only two items cleared the "real, small, decision-light, directly-mirrorable" bar; every other untagged candidate either already carries its own accepted-tradeoff reasoning or needs a product/design decision before any fix is possible. |
| 2026-08-20 | Applied senior-dev-review findings (`_bmad-output/implementation-artifacts/story-review.md`, 5 findings, all confirmed) before dev-story: (1) AC2 undercounted the duplication it claims to fix — a third page, `PlayerHomeRedirectPage.vue`, independently calls the same `getMyProfile()` on every player login and was left untouched; folded it into AC2's scope as a third target (Task 2.4). (2) AC2's cache had no logout invalidation, and this app's logout is an in-SPA route change, not a reload — a second player logging in on the same tab could inherit the first player's cached `selfPlayerId` and have a booking misattributed to the wrong player; added `resetSelfPlayerId()` to `playerStore.js` and a call to it from `MainLayout.vue`'s `handleLogout()` (Task 2.5), scoped narrowly to `selfPlayerId` only (pre-existing `players`/`activePlayerId` staleness left alone, called out explicitly in Dev Notes). (3) Task 2.4 (now 2.6)'s "confirm eslint clean" directly conflicted with AC2's original instruction to leave the now-dead `playerRegistrationApi` import in place once its sole call site is removed; resolved by dropping the dead import in all three touched pages instead of keeping it. (4) AC1's rationale ("unlike Coach, which needs a `@BeforeEach` seed") was factually wrong — `ROLE_COACH` is also pre-seeded by a Flyway migration (`V21`) and restored the same way `ROLE_PLAYER` is by `V84`, so Coach's `@BeforeEach` insert is itself redundant, not load-bearing; corrected the parenthetical (no code/task change — AC1's actual instruction to skip `@BeforeEach` was already correct). (5) AC2's cited precedent — "`fetchPlayers()`'s fetch-once-cache-in-store shape" — doesn't exist: `fetchPlayers()` unconditionally re-fetches and overwrites `players.value` on every call, with no cache-hit branch; dropped the overstated "already established... exactly this" framing since AC2's explicit behavior spec stands on its own without it. |
| 2026-08-20 | `dev-story` implementation complete. AC1: new `PlayerRegistrationResourceIT.java` with the two specified tests, 2/2 green. AC2: `playerStore.js` gained `selfPlayerId`/`fetchSelfPlayerId()`/`resetSelfPlayerId()`; all three target pages (`CoachPublicProfilePage.vue`, `BookingRequestPage.vue`, `PlayerHomeRedirectPage.vue`) swapped to the cached store call and dropped their now-dead `playerRegistrationApi` import; `MainLayout.vue`'s `handleLogout()` now resets the cache; `npx eslint` clean on all five touched files. AC3: verified the `[PICKED UP]`/`[STALE]` ledger tags were already present from story creation — no edit needed. Status set to review. |
| 2026-08-20 | Code review complete (`bmad-code-review`). Blind Hunter (15 raw findings, no project context) + Edge Case Hunter (5 findings, full repo access) + Acceptance Auditor (0 AC violations, AC1-3 independently re-verified against live code and schema). 15 of 20 findings dismissed as noise after verification: several matched `CoachRegistrationResourceIT.java`'s own established, explicitly-mirrored conventions (unused `@LocalServerPort` field, `HttpStatus.OK` for a POST, `@SuppressWarnings("unchecked")`, hardcoded schema-qualified table literals); the "only happy-path coverage" and "no negative API-key test" findings restate this story's own explicit, deliberate scope narrowing (Dev Notes); the shared `TEST_EMAIL` reuse-across-tests concern and the "logout reset gated behind a fallible awaited call" concern were both independently disproven by reading the actual code (`DatabaseResetTestExecutionListener` resets the DB before every `@Test`; `authStore.logout()` already catches its own errors internally and cannot reject). 2 findings deferred to `deferred-work.md` as pre-existing, untouched by this diff (no automated test coverage for the new caching/reset logic — standing repo-wide frontend-test-infrastructure gap; `PlayerHomeRedirectPage.vue`'s bare `catch` treating any error as "no profile yet", shape explicitly left untouched per AC2's own instruction). 2 decision-needed findings — Edge Case Hunter independently found the self-profile cache's logout invalidation was incomplete: two other live in-SPA logout paths (`App.vue`'s idle-timeout `handleSessionExpired()`, `useSession.js`'s session-warning-dialog `handleLogout()`) never called `resetSelfPlayerId()`, and `fetchSelfPlayerId()` had no in-flight-request deduplication, so a stale pre-logout fetch could resolve after a new player logged in and silently repopulate the cache with the wrong player's id (a real risk since `BookingRequestPage.vue` feeds this id straight into a booking's `playerId`) — presented to the user, who chose to patch both immediately rather than defer. 3 patches applied and verified (`npx eslint` clean): (1) `App.vue`/`useSession.js` gained `usePlayerStore()` imports and a `playerStore.resetSelfPlayerId()` call in their respective logout handlers, mirroring `MainLayout.vue`'s existing call; (2) `playerStore.js`'s `fetchSelfPlayerId()` gained a shared in-flight-promise guard plus a generation counter (bumped by `resetSelfPlayerId()`) so duplicate concurrent calls share one request and a pre-reset request resolving late no longer overwrites a post-reset cache; (3) the cache write is now gated on `profile?.id != null`, so a malformed response no longer permanently caches `undefined` and blocks all future retries. Status → done. |
