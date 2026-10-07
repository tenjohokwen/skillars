# skillars-deferred-148: Player-ID Corruption Fixes, Dispute Cross-ID-Space Bug, and Router Role-Gate Hardening

**Story ID:** deferred-148
**Epic:** Deferred-Work Cleanup Bundle
**Status:** done
**Scope Level:** Small-Medium (four one-line-shaped frontend id-corruption fixes across four pages, three new spec files + one extended existing spec; one backend service method gains a repository-backed lookup + two new unit tests; one frontend router guard line changed; seven route `meta` blocks gain or correct a role gate; one backend service method has two guard blocks reordered + new unit/IT coverage; one test-infrastructure file gets a `finally` wrap, a null-tolerant guard, and a visibility widening + new unit test; one `.dockerignore` pattern fix. No schema change, no new REST endpoint, no DTO/contract change visible to callers.)
**Date Created:** 2026-10-07
**Source:** Mined from `_bmad-output/implementation-artifacts/deferred-work.md`, sections "Deferred from: manual testing of skillars-deferred-147 (2026-10-07)", "Deferred from: code review of skillars-deferred-145 (2026-10-06)" (one item), "Deferred from: code review of skillars-deferred-145, round 2 (2026-10-06)" (one item), "Deferred from: code review of skillars-deferred-144 (2026-10-06)" (two of three items), and "Deferred from: code review of skillars-deferred-146 (2026-10-07)" (two of four items). All citations below were independently re-verified directly against HEAD `ef2daac9` (2026-10-07, clean working tree) at story-creation time, not copied from the ledger.

**Pre-implementation review (`story-review.md`, 2026-10-07, against this same HEAD):** independently re-verified every finding before applying — not trusted blindly. Of the review's 12 candidate findings, all 12 held up on re-check (its own adversarial pass had already killed the false positives before this story incorporated it). Two required a scope/design decision rather than a pure correction: **C1** (the AC4 `player/locker-room/:playerId` gate was going to break a live, backend-authorized parent affordance — fixed by using the dual-role `roles: [...]` mechanism instead of a singular `role: 'PLAYER'`) and **C2** (a fourth Tsid-corruption site, `BookingRequestPage.vue:247`, missed by the original grep and worse than the three in scope because it corrupts an id on a *write* path reachable from a linked marketplace CTA — folded into AC1 rather than left to a follow-up). The rest were citation drift, an internally-contradicted claim (a sentence said "no child component receives it as a prop," confirmed false three sentences later by the Design section itself — M2), an inherited ledger error restated without re-derivation (Finding 6 attributed a visibility change to the wrong method — M1/C3), an undercounted route enumeration (C4), and missing test-list/References entries (C5, C11). All incorporated below; see each Finding/Design/AC for the specific fix.

**Round 2 review (same file, same HEAD):** re-checked the fix for each of the 12 findings, not just that something changed. Found one new, self-introduced defect: the first-pass fix for C2 deleted `BookingRequestPage.vue`'s `Number`/`isFinite`/`> 0` check outright on the stated premise that a garbage value "ends up the same either way" — false, by execution: the inner `if` is the branch's only `return`, so a value that fails it falls through to the safe `playerStore.activePlayerId` fallback today, and deleting the check meant a hand-edited or malformed `?playerId=` query value (`abc`, `0`, a duplicate-param array) would instead flow straight into `canSubmit`/`submitBookingRequest` unguarded — trading a read-path id-rounding bug for a write-path validation hole on this one site. Fixed by replacing the numeric parse with an equivalent shape check (`/^[1-9]\d*$/` against the stringified value) that preserves the fall-through for anything that isn't a bare positive-integer string, rather than removing the gate — see Design A's `BookingRequestPage.vue` entry and its new "not the same shape as the other three" note.

---

## User Story

As **the developer maintaining the parent-facing player pages**, I want the pages that still read a Tsid player-id route/query param via `Number(...)` fixed — the identical bug `skillars-deferred-147` already fixed once in `PlayerDevelopmentDashboardPage.vue`, explicitly recommended as a follow-up for three named siblings, plus a fourth found on this story's own second pass — so that **every one of these pages targets the real player id instead of a silently-rounded one that 403s (or, on the write path, submits against a nonexistent player) on every API call**.

As **the developer maintaining dispute eligibility**, I want `DisputeService.raiseDispute`'s player-ownership check fixed to compare against the right ID space, so that **a self-registered adult player can actually raise a dispute on their own booking**, which the current code makes structurally impossible.

As **the developer maintaining the frontend router guard**, I want the profile-builder completeness gate to stop being bypassable by a non-canonical URL spelling, and a set of routes the guard currently lets any authenticated role reach to get the same `meta.role`/`meta.roles` treatment every other role-specific route already has — each gated to match what the **backend** actually authorizes for it, not just a plausible-looking persona label — so that **a coach can't skip profile completion by capitalizing a URL, and a parent/player/coach can't land on a broken-looking or wrong-persona page via a crafted post-login redirect, without breaking a page the backend legitimately allows a second persona to reach**.

As **the developer maintaining review eligibility**, I want the cooldown and moderation-status re-checks in `ReviewSubmissionService.updateReview` reordered so the more actionable error always wins a race, so that **an author whose edit collides with a concurrent moderation block gets told the review is blocked, not that they edited too recently**.

As **the developer maintaining the test harness and the Docker build**, I want two small, already-identified hygiene gaps closed — a reset-cost counter that silently stops recording on the one path most worth measuring, a quiesce-check that can throw on an executor bean that never gets to that state in production but is reachable in principle, and a `.dockerignore` pattern class that ships nested copies of files it's supposed to exclude — so that **the test-infrastructure cost counter and the build-context exclusions actually do what their own comments say they do**.

---

## Context: Current State (verified against HEAD `ef2daac9`, 2026-10-07)

### Finding 1 — four pages corrupt a Tsid player-id param (three named by deferred-147's own recommended follow-up, a fourth found on this story's second pass)

`skillars-deferred-147` fixed `PlayerDevelopmentDashboardPage.vue`, which read `:playerId` via `Number(route.params.playerId)`. Backend ids are Tsids (18-19 digits), past `Number.MAX_SAFE_INTEGER`; the backend deliberately quotes `Long` as a JSON string (`CommonConfig.longToStringModule`) specifically so JS never represents one as a number. Converting it back with `Number(...)` silently rounds it (e.g. `893573203704173564` → `893573203704173600`, reproduced directly via `node -e`), and every API call made with the corrupted id then 403s against a player that doesn't exist.

That story's own manual-testing notes flagged three siblings carrying the **identical** pattern, found by grep but not manually reproduced (different persona/page than the story's tested surface), and explicitly recommended "a small dedicated follow-up story applying the identical one-line fix to all three." Re-verified directly against HEAD, all three still present exactly as described:

- `src/frontend/src/pages/parent/ParentDevelopmentPortalPage.vue:124` — `const id = Number(route.params.playerId); return isNaN(id) ? null : id` inside the `playerId` computed (`:123-126`). The same file's `activePlayerId` watcher (`:185-192`) compares `newId !== playerId.value`, where `newId` comes from `playerStore.activePlayerId` (a string, per the backend's own convention) and `playerId.value` is the corrupted `Number`. Fixing `playerId` to stay a string also fixes a **second, latent bug** in that same comparison: today it is `string !== number`, which JS's strict `!==` never coerces, so the guard's own comment ("Kept narrow: only fires when newId !== current route param") is not actually narrow — it is unconditionally true whenever `newId` is truthy.
- `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue:76` — `const playerId = Number(route.params.playerId)`, a plain (non-reactive) const, passed straight into `loadForPlayer(id)` → `bookingStore.loadParentSchedule(id)` / `loadPlayerPacks(id)`. Same `newId !== playerId` latent type-mismatch bug at `:88`, same fix.
- `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue:189` — `const playerId = computed(() => Number(route.params.playerId))`, read at four call sites (`:215`, `:257`, `:260`, `:280`) feeding `paymentStore` actions (`fetchPlayerSubscription`, `changePlayerTier`, `subscribePlayer`, `cancelPlayerSubscription`).

**A fourth site exists on `route.query`, not `route.params`, which is why both the original grep and deferred-147's own sweep missed it** (both searched for the `route.params.playerId` shape specifically): `src/frontend/src/pages/parent/BookingRequestPage.vue:247` —
```js
const playerId = computed(() => {
  if (route.query.playerId && !authStore.isPlayer) {
    const parsed = Number(route.query.playerId)
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  ...
})
```
The `Number.isFinite(parsed) && parsed > 0` guard does **not** catch a rounded Tsid — `893573203704173600` (the corrupted form of the example id above) is finite and positive, so it passes straight through. **This one is worse than the other three: it corrupts an id on a write path, and the entry point is fully linked, not a dormant route.** `CoachPublicProfilePage.vue`'s `handleCta` (the marketplace coach-profile page's primary call-to-action, `coaches/:coachId`, a public route) builds `router.push(\`/parent/coaches/${coachId}/request-booking?playerId=${playerStore.activePlayerId}\`)` with the real, uncorrupted Tsid string — `BookingRequestPage.vue` then rounds it on arrival and uses the corrupted value for: the single booking-request submit (`:491`, `playerId: playerId.value`), the batch submit (`:558`, `bookingStore.submitBatch(coachId, playerId.value, 0)`), a forwarded navigation (`:481`, `purchase-sessions?playerId=${playerId.value}`), and a packs load (`:652`). `BookingRequestPage.vue` already has a spec (`pages/parent/__tests__/BookingRequestPageSpec.js`) — this needs an added case, not a new file.

None of the four passes `playerId` as a prop to a child component **except** `ParentDevelopmentPortalPage.vue`, which passes it to two: `PerformanceReportsPanel.vue:87` (`:player-id="playerId" :is-coach="false"`) and `PlayerTimelinePanel.vue:97` (`:player-id="playerId"`). Both already declare `playerId: { type: [Number, String], required: true }` (confirmed by read), both guard with `if (!props.playerId) return` before using it, and both only forward it into store fetches — so **no prop-type change is needed**, same conclusion as for the other three pages, just reached by confirming the existing prop type is already dual-typed rather than by there being no prop at all.

No existing spec files exist for `ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, or `PlayerSubscriptionPage.vue` (confirmed by `find src/frontend/src/pages/parent/__tests__`) — those three need new spec files. `BookingRequestPage.vue` is the exception (see above).

### Finding 2 — `DisputeService.raiseDispute`'s player-ownership check compares the wrong ID space

`DisputeService.java:91` — `boolean ownerEligible = raisedBy.equals(booking.getParentId()) || raisedBy.equals(booking.getPlayerId());`. `raisedBy` is the caller's **User** id. `booking.getParentId()` (`Booking.java:31`, `Long`) is also a User id — correct, that disjunct is fine. `booking.getPlayerId()` (`Booking.java:34`, `Long`) is a **`PlayerProfile` primary key, not a User id** — the exact same cross-ID-space defect class already tracked in project memory (`Booking.playerId` is a `PlayerProfile` PK, resolved via `PlayerProfile.userId`/`parentId`) and already fixed once in this codebase, in `ReviewSubmissionService.checkEligibility` (`:253-264`, `skillars-deferred-145`).

**Practical consequence, verified against the real test fixtures:** `DisputeServiceTest.buildBooking()` (`:89-97`) sets `booking.setPlayerId(2L)` as a bare literal with no `PlayerProfile` behind it, and none of the three existing `raiseDispute` tests (`:110-173`) exercises the player-ownership disjunct at all — all three test the coach-eligibility branch (`raisedByRole = "COACH"`). **A self-registered adult player calling `raiseDispute` on their own booking is therefore currently rejected as `NOT_ELIGIBLE` in all but an astronomically unlikely id collision**, since their own `userId` will essentially never equal their own `PlayerProfile`'s PK (a different app-side `@Tsid` sequence). The parent-raising-a-dispute-on-their-child's-booking path is unaffected — `booking.getParentId()` is correctly a User id already.

`PlayerProfileRepository` (confirmed via `find` + read) already has everything needed: `findById(Long id): Optional<PlayerProfile>`, and `PlayerProfile.getUserId()`/`getParentId()` (both nullable, `chk_pp_owner` guarantees exactly one is set per profile). `DisputeService` is `@RequiredArgsConstructor` (`:52-54`) and does not currently depend on `PlayerProfileRepository`.

Note the fix below deliberately checks only `PlayerProfile.getUserId()`, not also `getParentId()` — a narrower mapping than `ReviewSubmissionService.checkEligibility`'s `authorId.equals(player.getUserId()) || authorId.equals(player.getParentId())` (`:264`), which checks both. That is a considered difference, not an oversight: `DisputeService`'s existing first disjunct, `raisedBy.equals(booking.getParentId())`, already covers the parent case using the **booking's own** `parentId` snapshot. Adding a second, `PlayerProfile`-derived parent check would be redundant in the common case and could only diverge from it if a child were reassigned to a different parent after the booking was made — a scenario this fix does not need to resolve, since the booking-level check already decides parent eligibility correctly today.

### Finding 3 — a non-canonical spelling of `/coach/command-center` bypasses the profile-builder completeness gate

`router/index.js:89` — `if (to.path === '/coach/command-center' && authStore.isCoach) { ... }` — an exact string compare. vue-router 4.6.4's matcher defaults to `strict: false, sensitive: false` (confirmed: this project's `createRouter` call, `router/index.js:29-37`, passes neither option), so `/coach/command-center/` (trailing slash) and `/COACH/COMMAND-CENTER` both resolve to the real route (`to.matched.length === 2`, no `meta.notFound`) while leaving the literal, non-canonical spelling in `to.path`. The `pbStore.loadStatus()` / `if (!pbStore.isComplete) next('/coach/profile-builder')` branch therefore never runs for either spelling, landing a coach with an incomplete profile straight on `CoachCommandCenterPage`. `requiresCoach` still gates correctly (it reads `to.matched.some(...)`, not `to.path`), so this is a flow-gate bypass, not an authorization one. Not a regression — `skillars-deferred-144`'s predecessor inline guard accepted the same strings — but `skillars-deferred-144`'s own code review flagged it as the natural place to close now that resolvability is this guard's whole theme, and deferred it rather than fixing it inline.

No spec anywhere under `src/frontend/src` exercises a trailing-slash or case-variant path against this gate (re-confirmed: `grep -rn "command-center/\|COMMAND-CENTER" src/frontend/src --include="*.js"` → no matches).

### Finding 4 — routes with `meta: { requiresAuth: true }` and no working role gate accept any authenticated role as a redirect target; the persona label for one of them is itself wrong

`router/routes.js`, confirmed by direct read of the full route table — **eleven** routes carry `requiresAuth: true` with no *working* role gate, not six. The ledger item this finding is drawn from names four of them (plus the admin pair as its lead example); the other five were found independently and are triaged below rather than silently left out of the count:

| Route | Lines | Current `meta` | Persona(s) actually authorized | In this story's AC4? |
|---|---|---|---|---|
| `parent/create-player` | `:110-112` | `{ requiresAuth: true }` | PARENT (onboarding step, reached right after parent registration) | No — see "Deliberately not touched" below |
| `parent/dashboard` | `:115-117` | `{ requiresAuth: true }` | PARENT (it's `ROLE_ROUTES.PARENT`, `roleRoutes.js:15`) | Yes |
| `player/profile-builder` | `:232-234` | `{ requiresAuth: true }` | PLAYER (onboarding step, reached right after player registration) | No — see "Deliberately not touched" below |
| `player/home` | `:239-241` | `{ requiresAuth: true }` | PLAYER (it's `ROLE_ROUTES.PLAYER`, `roleRoutes.js:16`) | Yes |
| `player/locker-room/:playerId` | `:244-247` | `{ requiresAuth: true }` | **Both PLAYER and PARENT** — see correction below | Yes (corrected gate) |
| `player/development/:playerId` | `:250-253` | `{ requiresAuth: true }` | PLAYER | Yes |
| `parent/player/:playerId/subscription` | `:205-208` | `{ requiresAuth: true, requiresParent: true }` | PARENT — but the `requiresParent: true` key is **inert**, see correction below | Yes (corrected gate) |
| `messaging` | `:301-304` | `{ requiresAuth: true }` | Shared, legitimately — no change needed | No |
| `dashboard` | `:325-327` | `{ requiresAuth: true }` | Shared, legitimately — it's `DEFAULT_ROUTE` itself | No |
| `profile` | `:330-333` | `{ requiresAuth: true }` | Shared, legitimately — account settings for any role | No |
| `admin` (parent) | `:338-341` | `{ requiresAuth: true }` | ADMIN | Yes |
| `admin/health-dashboard` (child) | `:342-344` | `{ requiresAuth: true }` | ADMIN (it's `ROLE_ROUTES.ADMIN`, `roleRoutes.js:17`) | Yes |

`router/index.js`'s `beforeEach` (`:47-56`) only derives `requiresCoach` (`meta.requiresCoach`), `requiresParent`/`requiresPlayer` (`meta.role === 'PARENT'`/`'PLAYER'`), and the generic `rolesMeta`/`requiresOneOfRoles` (`meta.roles`, an array, already used for the dual-role `['PARENT','PLAYER']` routes at `:124`, `:132`, `:151`, `:160`). **There is no `meta.role === 'ADMIN'` check anywhere in the guard** — so even adding `role: 'ADMIN'` to the two admin routes' `meta` would silently do nothing; the generic `meta.roles` array mechanism is the one that actually works for a role the singular-`meta.role` idiom doesn't cover, and is already a proven, used pattern.

A planted `?redirect=/admin/health-dashboard` (or any of the routes marked "Yes" above) opened by any authenticated PARENT/PLAYER/COACH passes `isSafeRedirect` today (verified: real `matched.length` ≥ 2, no `meta.notFound`) and renders that page's shell before every API call on it 403s server-side (`/manage/**` is ADMIN-only via `AppEndpoints.SECURED_MAPPINGS`; the player/parent routes are gated equivalently by their own resources' `@PreAuthorize`). Not a data-exposure bug — a broken-looking-page bug, exactly as `skillars-deferred-144`'s own code review classified it when it first surfaced this and deferred it.

**Correction — `player/locker-room/:playerId` is not PLAYER-only; the backend deliberately authorizes PARENT too, and a live parent-only button depends on it.** `PlayerOwnershipGuard.check` — the guard gating every player-scoped resource this page reads, including `HomeworkResource` — is `existsByIdAndParentId(playerId, callerId) || existsByIdAndUserId(playerId, callerId)`: both a parent on a parent-owned profile **and** a self-registered player on their own are authorized by design (the `existsByIdAndUserId` half was added by `skillars-deferred-147` precisely because a self-registered player was previously, wrongly, denied). `ParentPlayerPortalPage.vue:10-15` renders a `q-btn` with `:to="{ name: 'player-locker-room', params: { playerId: route.params.playerId } }"` and no `v-if` guard; that page's own route (`parent/players/:playerId/sessions`, `:135-138`) is `role: 'PARENT'`-gated, so **only a PARENT can ever see this button** — it is live code with a real i18n key (`player.viewLockerRoom`, confirmed present in all three locale bundles), not dead code. Gating `player/locker-room/:playerId` to `role: 'PLAYER'` alone would bounce that parent to `/parent/dashboard` the moment they click it, putting the router in direct conflict with the authorization model `skillars-deferred-147` just finished fixing. **Blast radius today is small, not zero:** the button's host route has no `name` and no in-app navigation anywhere targets it (confirmed by grep — the parent dashboard's player cards are plain `<div>`s, not links), so it is reachable only by direct URL or bookmark today. That does not make the persona label in the table above correct, and a future page that links to this route (the natural next step for a portal page with a "View Locker Room" button) would hit it immediately. Fix: gate this route `roles: ['PLAYER', 'PARENT']` (the existing, already-proven dual-role mechanism), not `role: 'PLAYER'` — see Design D.

**Correction — a fourth, independently-found inert role-gate spelling exists, on a route this story already touches for Finding 1.** `parent/player/:playerId/subscription` (`:205-208`, backing `PlayerSubscriptionPage.vue` — one of Finding 1's four pages) carries `meta: { requiresAuth: true, requiresParent: true }`. The guard reads `meta.role === 'PARENT'` (`index.js:50`), never `meta.requiresParent` — so this key does nothing, and `PlayerSubscriptionPage.vue` is reachable by any authenticated role today, the same symptom as the routes listed "Yes" in the table above. This is the same inert-spelling class as `coach/revenue`/`coach/bookings/:bookingId/receipt`'s `role: 'COACH'` below, just a different literal key. Folded into this story's AC4 since the file is already open for Finding 1 — see Design D.

**Sibling routes already gated the right way, as the precedent to follow:** `player/videos` (`:256-259`, `role: 'PLAYER'`), `player/dashboard` (`:264-267`, `role: 'PLAYER'`), `parent/approvals`/`parent/credit-wallet`/`parent/credit-statement`/`parent/bookings/:bookingId/receipt` (all `role: 'PARENT'`), `parent/coaches/:coachId/purchase-sessions`/`parent/players/:playerId/packs`/`parent/coaches/:coachId/request-booking`/`parent/bookings` (all `roles: ['PARENT', 'PLAYER']`).

**Deliberately not touched, left deferred again:**
- `coach/revenue` (`:295-298`) and `coach/bookings/:bookingId/receipt` (`:307-310`) both carry `meta: { requiresAuth: true, role: 'COACH' }` — inert for the same reason as the subscription route above (the guard has no `meta.role === 'COACH'` check; only `meta.requiresCoach` is honoured for coach gating). Pre-existing, not named in the sourcing ledger item, and widening the guard to honour `meta.role === 'COACH'` (or migrating these to `requiresCoach: true`) is a related but distinct cleanup from this story's scope — see Dev Notes.
- `parent/create-player` and `player/profile-builder` are the same defect class (persona-specific, `requiresAuth`-only) but are one-time onboarding steps reached immediately after registration, before this story's own re-verification covered their actual inbound-navigation shape — not named in the sourcing ledger item either. Left out rather than silently fixed alongside the named four, to avoid scope creep into routes nobody has yet checked the navigation context of; a natural, cheap follow-up.

### Finding 5 — `ReviewSubmissionService.updateReview` checks cooldown before moderation-status at both its guard sites, so a concurrent block reports the less actionable error

`ReviewSubmissionService.java:129-141` (unlocked pre-check) and `:181-194` (locked re-check, added by `skillars-deferred-145`'s code review to close a different race) both evaluate the cooldown guard (`UPDATE_TOO_SOON`) before the moderation-status guard (`EDIT_NOT_PERMITTED`). `AdminReviewService.blockReview` (`:143,145`, confirmed by read — `:144` in between is the unrelated `setHeldReason(null)`) sets both `moderationStatus = BLOCKED` and `lastModifiedAt = now` in the same write, after a guard at `:139-141` that throws `ALREADY_BLOCKED` if the row is already `BLOCKED` (so a test seeding this race must start the row at `APPROVED`/`PENDING`, not `BLOCKED`). The method also runs `reviewFlagRepository.resolveAllOpenFlags(...)` and, when the previous status was `APPROVED`, `coachRatingService.recompute(...)` — more side effects than just the two field writes, worth knowing if a new test's fixture happens to interact with either. A concurrent `blockReview` racing an author's `updateReview` therefore makes the author's cooldown re-check trip first — they receive `reviews.updateTooSoon` ("you edited too recently"), when the actual, actionable reason is `reviews.editNotPermitted` ("this review is blocked"). The unlocked pre-check had this ordering before `skillars-deferred-145` touched this method at all; the locked re-check is a faithful mirror of it, not a new regression — but both need reordering together so the two paths stay mirrored, per the ledger item's own "When picked up" note.

Confirmed no existing test *result* changes under the reorder, across **three** files, not two: `ReviewSubmissionServiceTest`'s one `updateReview` test (`updateReview_readsUpdateCooldownConfigWithTheDocumentedKeyAndBoundsLiterally`, `:99-...`) only sets `lastModifiedAt`, leaving `moderationStatus` at its default (not `BLOCKED`/`UNDER_REVIEW`), so the status guard never fires in that test either way. `ReviewSubmissionServiceConcurrencyIT`'s `concurrentUpdateReview_secondCallerCannotBypassCooldownViaStaleRefresh` (`:289-...`) keeps `moderationStatus = 'APPROVED'` throughout — status never trips there either. **`ReviewUpdateIT` (`platform.reviews.api`) asserts on both error codes directly** (`reviews.updateTooSoon` at `:156`/`:270`, `reviews.editNotPermitted` at `:436`) and was not checked by the original sweep — re-verified here: `updateReview_blockedStatus_returns403` (`:417-438`) only runs `UPDATE ... SET moderation_status = 'BLOCKED'` against a fixture whose `last_modified_at` stays 400 days old (`:119-131`), far outside the 30-day cooldown, so the cooldown guard never trips there either; both `updateTooSoon` cases leave `moderation_status = 'APPROVED'`, so the status guard never trips in them. Reordering is safe against the existing suite, including this file; none of the three has ever exercised a fixture where *both* guards would trip, which is exactly the scenario this finding describes and the gap this story's new test closes. `ReviewUpdateIT` must still be added to AC8's targeted re-run list, since it is the only existing API-level coverage of both codes for `updateReview` and should be watched, not just reasoned about.

### Finding 6 — `DatabaseResetTestExecutionListener`: a reset-cost counter that goes blind on the one path worth measuring, and an unreachable-today `NullPointerException`-adjacent guard gap

Two independent, small items from the same file, both from `skillars-deferred-146`'s own code review, both explicitly left out of that story because neither is a regression it introduced:

- **`recordQuiesceCost` is not wrapped in a `finally`** (`beforeTestMethod`, `:114-116`): `quiesceAsyncExecutors(ctx)` runs, then `recordQuiesceCost(System.nanoTime() - quiesceStartNanos)` runs after it — if `quiesceAsyncExecutors` throws, the cost of that invocation never reaches the counter, which is precisely the pathological, most-worth-logging case. Trivial to fix with a `try`/`finally`.
- **`isQuiesced` (`:351-353`) calls `executor.getThreadPoolExecutor()` unguarded against the bean not yet being initialized.** `ThreadPoolTaskExecutor.getActiveCount()` returns `0` when its delegate is `null` ("not initialized yet: assume no active threads") rather than throwing, so the `&&` short-circuit in `executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty()` does **not** protect the second operand — `getThreadPoolExecutor()` itself `Assert.state`-throws `IllegalStateException` if called before `afterPropertiesSet()`/`initialize()` has run. Unreachable today (confirmed: `quiesceAsyncExecutors` enumerates via `ctx.getBeansOfType(ThreadPoolTaskExecutor.class)`, which eagerly initializes any bean it returns, and all six production pools are `@Bean`-declared `GracefulShutdownTaskExecutor`s initialized that way before any test method runs). **Correction:** `isQuiesced` is `private static` and was introduced fresh by `skillars-deferred-146` (`git show b27ce6f1` — no prior state to have been widened from); the method that commit actually widened from `private` to package-private for its own new unit test is the *sibling* `quiesceAsyncExecutors`. `isQuiesced` has no existing test touching it today — `DatabaseResetTestExecutionListenerQuiesceTest`'s two cases both call `quiesceAsyncExecutors` only. This fix widens `isQuiesced` to package-private too (the same visibility bump its sibling already got, for the same reason: a direct unit test), per Design F.

### Finding 7 — `.dockerignore`'s single-segment patterns only match the build-context root, shipping nested copies

`.dockerignore:31-32` (`*.jar`, `*.war`) and `:66-67,73-74` (`*.iml`, `.DS_Store`, `*.log`, `*.diff`) are anchored full-path matches — Docker's ignore-pattern semantics treat an unprefixed `*.ext` as matching only at the context root, not recursively. The file already uses the correct `**/` prefix for `**/node_modules/` (`:29`) and `**/.vite/` (`:30`). `.DS_Store` matters most on this macOS dev host: Finder rewrites it on directory browse, changing its mtime, which can bust the `COPY src/ src/` cache layer the file's own header says it exists to optimise. `Thumbs.db` (`:68`) is the identical single-segment pattern for the Windows equivalent, not named in the sourcing ledger item's two cited examples but the same defect class — included in this fix for consistency rather than left half-done.

---

## Design

### A. Four pages (Finding 1) — keep the id param as a string, exactly matching `skillars-deferred-147`'s own real fix (bare assignment, no extra null-coalescing)

- `ParentDevelopmentPortalPage.vue:123-126`: change the `playerId` computed from
  ```js
  const playerId = computed(() => {
    const id = Number(route.params.playerId)
    return isNaN(id) ? null : id
  })
  ```
  to
  ```js
  const playerId = computed(() => route.params.playerId)
  ```
  **Use the bare form, not `route.params.playerId ?? null`** — the real `skillars-deferred-147` fix for the precedent page is `const playerId = computed(() => route.params.playerId)`, verbatim (`git show ef2daac9`). This also fixes the `activePlayerId` watcher's `newId !== playerId.value` comparison (`:188`) for free — both sides are now strings. **Disclosed behavior change:** the pre-fix code's `isNaN(id) ? null : id` meant a non-numeric garbage route param (e.g. a hand-edited `/parent/players/abc/development`) resolved to `null`, which `loadPortal`'s `if (!id) return` (`:152`) used to short-circuit into an empty page with no API calls. After this fix, the same garbage param is a truthy non-empty string, so `loadPortal('abc')` fires its six API calls and the backend 400s/403s instead. Low risk — the param is normally supplied by the router from a real Tsid — but it is a real, disclosed trade-off, not a silent one, and matches the precedent `skillars-deferred-147` already accepted for the same shape of input.
- `ParentPlayerPortalPage.vue:76`: `const playerId = Number(route.params.playerId)` → `const playerId = route.params.playerId`. Fixes the identical `newId !== playerId` comparison at `:88` for free.
- `PlayerSubscriptionPage.vue:189`: `const playerId = computed(() => Number(route.params.playerId))` → `const playerId = computed(() => route.params.playerId)`.
- `BookingRequestPage.vue:245-251`: change
  ```js
  const playerId = computed(() => {
    if (route.query.playerId && !authStore.isPlayer) {
      const parsed = Number(route.query.playerId)
      if (Number.isFinite(parsed) && parsed > 0) return parsed
    }
    if (authStore.isPlayer) return selfPlayerId.value
    return playerStore.activePlayerId
  })
  ```
  to
  ```js
  const playerId = computed(() => {
    if (route.query.playerId && !authStore.isPlayer) {
      const raw = String(route.query.playerId)
      if (/^[1-9]\d*$/.test(raw)) return raw
    }
    if (authStore.isPlayer) return selfPlayerId.value
    return playerStore.activePlayerId
  })
  ```
  **Keep the validation gate — do not simply drop the `Number`/`isFinite`/`> 0` check.** The inner `if` is the *only* `return` inside the outer block; when it doesn't fire, execution falls through to the safe fallback (`playerStore.activePlayerId`), not to a corrupted value. That fallback is load-bearing: `route.query.playerId` is a raw, user-controllable query string (unlike the other three sites' router-validated `:playerId` params), the page's own `canSubmit` comment (`:295-296`) names "a malformed query string" as a case that "must not be able to submit with an undefined playerId," and this computed feeds a **write** path (`submitBookingRequest`, `submitBatch`). Replacing the numeric parse with a shape check instead of deleting it preserves exactly this: `?playerId=abc`/`0`/`-5` all still fail the check and fall through to `activePlayerId`, same as today; a duplicate query param (`?playerId=1&playerId=2`, which vue-router parses to the array `['1','2']`) stringifies to `'1,2'` via `String(...)`, which also fails the regex and falls through — without the `String(...)` wrapper, the regex would reject the array object outright anyway, but stringifying first keeps the failure mode legible. `^[1-9]\d*$` preserves the original `parsed > 0` semantics (no leading zero, no decimal, no sign) for the one shape that matters here: a real Tsid is always a bare positive-integer string. The two other branches (`authStore.isPlayer` → `selfPlayerId.value`, fallback → `playerStore.activePlayerId`) are already strings and need no change.
- **This file is not the same one-line shape as the other three.** `ParentDevelopmentPortalPage.vue`/`ParentPlayerPortalPage.vue`/`PlayerSubscriptionPage.vue` are a mechanical `Number(...)` → bare-param swap with no gate to preserve. `BookingRequestPage.vue` is a three-branch computed with a validation gate whose failure path is a real fallback, not a `null`/corruption no-op — applying the other three sites' "just drop the wrapper" transformation here mechanically would silently remove that gate (this is exactly what happened in this story's own first draft, caught on review). Treat it as its own case, not a copy-paste of the other three.
- No prop-type changes needed anywhere: `ParentDevelopmentPortalPage.vue` is the only one of the four that passes `playerId` as a prop (to `PerformanceReportsPanel.vue`/`PlayerTimelinePanel.vue`), and both already declare `playerId: { type: [Number, String], required: true }` — confirmed, no change needed. `BookingRequestPage.vue` does not pass `playerId` as a prop either.

### B. `DisputeService.raiseDispute` (Finding 2) — resolve the real `PlayerProfile`, mirroring `ReviewSubmissionService.checkEligibility`'s already-shipped fix for the same defect class

Add `PlayerProfileRepository playerProfileRepository` as a new field **at the end of the field list** (after `eventPublisher`, `:74`) so Lombok's `@RequiredArgsConstructor`-generated constructor appends it as the last parameter — every existing `new DisputeService(...)` call site (production `@Service` wiring is automatic; `DisputeServiceTest.setUp()` is the only explicit call) needs exactly one argument appended at the end, not reordered.

Change `:91` from:
```java
boolean ownerEligible = raisedBy.equals(booking.getParentId()) || raisedBy.equals(booking.getPlayerId());
```
to:
```java
boolean ownerEligible = raisedBy.equals(booking.getParentId());
if (!ownerEligible) {
    ownerEligible = playerProfileRepository.findById(booking.getPlayerId())
        .map(PlayerProfile::getUserId)
        .map(raisedBy::equals)
        .orElse(false);
}
```
This is the same shape `ReviewSubmissionService.java:253-264` already uses for the same `Booking.playerId`-is-a-`PlayerProfile`-PK problem (narrowed to only the `getUserId()` half — see Finding 2's note on why `getParentId()` is deliberately not duplicated here), with one extra `findById` lookup only on the non-parent path, the same lazy-only-when-needed discipline the existing coach lookup six lines further down (`:97`, inside the same `if (!ownerEligible)` block this new code opens) already follows. `booking.getParentId()` is left untouched (it is already correctly a User id).

**The real code already has an `if (!ownerEligible)` block immediately following this line** (the suspended-coach check, `:92-102`, carrying a four-line Deferred-63 comment). Inserting the new block above produces two **consecutive** `if (!ownerEligible)` statements — correct, since the second only runs when the first left it `false`, so there is no overwrite — but preserve the existing block and its comment as-is; do not merge the two into one `if` or drop the Deferred-63 comment while editing nearby. One minor side effect worth knowing: every coach-raised dispute now also pays one extra `playerProfileRepository.findById` call before reaching the coach lookup (the new block runs first and returns `false` for a coach, falling through to the existing block) — negligible, but means the lookup is not *only* paid by the player-ownership path, just mostly.

**Uses `findById`, not `findByIdAndParentId`,** despite `PlayerProfileRepository.java:35`'s own javadoc instruction ("Always use this instead of `findById` — `parentId` enforces family isolation"). This is not a violation of that guidance in spirit: `findByIdAndParentId` cannot express this query (it needs `userId`, not `parentId`), and — like the `ReviewSubmissionService` precedent this mirrors, which does the same thing — the lookup here resolves an id the caller already supplied via a trusted `Booking` row in order to decide ownership, not to fetch and expose a profile's data on the caller's say-so alone.

### C. `router/index.js`'s profile-builder gate (Finding 3)

Change `:89` from:
```js
if (to.path === '/coach/command-center' && authStore.isCoach) {
```
to:
```js
if (to.matched.some((r) => r.path === '/coach/command-center') && authStore.isCoach) {
```
`to.matched[i].path` carries the route record's resolved pattern, matching the idiom every other gate in this same guard already uses (`requiresAuth`/`requiresGuest`/`requiresCoach`/`requiresParent`/`requiresPlayer` are all `to.matched.some(...)`). The alternative named in the ledger item (`createRouter({ strict: true, sensitive: true })`) is **not** used here — it would change matching behavior for every route in the app, not just this one gate, and is a much larger blast radius for the same fix.

### D. Routes with a missing or inert role gate (Finding 4)

- `routes.js:115-117` (`parent/dashboard`): `meta: { requiresAuth: true }` → `meta: { requiresAuth: true, role: 'PARENT' }` (matches the sibling `parent/*` routes' idiom).
- `routes.js:239-241` (`player/home`), `:250-253` (`player/development/:playerId`): each `meta: { requiresAuth: true }` → `meta: { requiresAuth: true, role: 'PLAYER' }` (matches `player/videos`/`player/dashboard`'s existing idiom).
- `routes.js:244-247` (`player/locker-room/:playerId`): `meta: { requiresAuth: true }` → `meta: { requiresAuth: true, roles: ['PLAYER', 'PARENT'] }` — **not** singular `role: 'PLAYER'`. Finding 4's correction established this route is dual-persona by design (`PlayerOwnershipGuard` authorizes both), so use the same `roles` array mechanism already proven on `parent/coaches/:coachId/purchase-sessions`/`parent/players/:playerId/packs`/etc.
- `routes.js:205-208` (`parent/player/:playerId/subscription`): `meta: { requiresAuth: true, requiresParent: true }` → `meta: { requiresAuth: true, role: 'PARENT' }`. Replaces the inert `requiresParent: true` key (the guard never reads it) with the working `role: 'PARENT'` idiom every other PARENT-only route already uses. This route backs `PlayerSubscriptionPage.vue`, one of Finding 1's four pages — the file is already open for that fix.
- `routes.js:338-341` (`admin` parent route): `meta: { requiresAuth: true }` → `meta: { requiresAuth: true, roles: ['ADMIN'] }`. Use the **`roles` array** form, not `role` — the guard has no singular `meta.role === 'ADMIN'` check (Finding 4), and `roles` is the existing, already-working generic mechanism (`requiresOneOfRoles`/`rolesMeta`, `index.js:55-56,84-87`). Setting it on the **parent** route is sufficient: `to.matched` includes every matched route record in the hierarchy, and `rolesMeta = to.matched.flatMap((r) => r.meta.roles || [])` collects from all of them, so the child `health-dashboard` route inherits the gate without its own `meta` needing to repeat it. (Still fine to also set it on the child route explicitly if that reads clearer to the dev agent at implementation time — functionally equivalent either way, since `flatMap` unions both.)
- **Do not touch** `coach/revenue`/`coach/bookings/:bookingId/receipt`'s pre-existing inert `role: 'COACH'` meta, or `parent/create-player`/`player/profile-builder`'s missing gate — see Finding 4's own "Deliberately not touched" note. A future story's job.

### E. `ReviewSubmissionService.updateReview` guard ordering (Finding 5)

Swap the two guard blocks at **both** sites so the status check runs first:

Unlocked pre-check (`:129-141`) — status block moves ahead of the cooldown block:
```java
ReviewModerationStatus status = review.getModerationStatus();
if (status == ReviewModerationStatus.BLOCKED || status == ReviewModerationStatus.UNDER_REVIEW) {
    throw new OperationNotAllowedException(
        "Review cannot be edited in its current moderation status",
        ReviewErrorCode.EDIT_NOT_PERMITTED);
}
int cooldownDays = configService.getBoundedInt(
    ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key(), 30, 1, 365);
if (review.getLastModifiedAt().isAfter(Instant.now().minus(cooldownDays, ChronoUnit.DAYS))) {
    throw new OperationNotAllowedException(
        "Review was modified within the cooldown window",
        ReviewErrorCode.UPDATE_TOO_SOON);
}
```
Locked re-check (`:181-194`) — identical reordering, same two blocks, operating on `locked`/`lockedCooldownDays` instead of `review`/`cooldownDays`. Pure reordering — no new reads, no behavior change beyond which error wins when both conditions are independently true.

**The new concurrency IT this reorder needs (AC5) only works via the existing test's exact hold-open mechanism — a naive two-independent-callers race does not work.** `AdminReviewService.blockReview` is plain `@Transactional` (REQUIRED propagation, no `REQUIRES_NEW`), so wrapping caller A's `blockReview` call in `transactionTemplate.execute(...)` (substituting it for `updateReview` as the thing caller A holds open, mirroring `ReviewSubmissionServiceConcurrencyIT.concurrentUpdateReview_secondCallerCannotBypassCooldownViaStaleRefresh`'s existing shape exactly) makes A's write **join** that outer transaction, so its row lock stays held until the wrapper's lambda returns — which is the only reason caller B's `findByIdForUpdateNoWait` has something to retry against via `lockRetryer.withBoundedRetry` instead of either succeeding immediately (wrong — the race would never engage) or hitting a lock-conflict error (flaky — B's bounded retry would need to outlast A's hold). Mirror the existing test's `Thread.sleep(300)` release delay so B's retry window is realistic. Seed the fixture at `APPROVED` or `PENDING`, not `BLOCKED` — `blockReview` throws `ALREADY_BLOCKED` otherwise (`:139-141`).

### F. `DatabaseResetTestExecutionListener` (Finding 6)

`beforeTestMethod` (`:114-116`): wrap the existing two lines in `try`/`finally`:
```java
long quiesceStartNanos = System.nanoTime();
try {
    quiesceAsyncExecutors(ctx);
} finally {
    recordQuiesceCost(System.nanoTime() - quiesceStartNanos);
}
```

`isQuiesced` (`:351-353`): widen from `private` to package-private (same visibility bump `skillars-deferred-146` already gave its sibling `quiesceAsyncExecutors`, for the same reason — a direct unit test) and guard the second operand against the not-yet-initialized case:
```java
static boolean isQuiesced(ThreadPoolTaskExecutor executor) {
    if (executor.getActiveCount() != 0) {
        return false;
    }
    try {
        return executor.getThreadPoolExecutor().getQueue().isEmpty();
    } catch (IllegalStateException notInitializedYet) {
        return true;
    }
}
```
No production executor reaches this branch today (Finding 6) — this is defensive, not a reproduction of a live failure, so no new production test is expected to exercise the `catch` via the normal `beforeTestMethod` → `quiesceAsyncExecutors` path. The widened visibility is what makes it directly testable: a unit test in `DatabaseResetTestExecutionListenerQuiesceTest` constructs a fresh `ThreadPoolTaskExecutor` that has never had `initialize()` called and calls `isQuiesced(executor)` directly, asserting it returns `true` rather than throwing. This is a one-line visibility change beyond what Finding 6 originally proposed (it mistakenly described `isQuiesced` as already having been widened) — authorized here explicitly because the alternative (testing only through `quiesceAsyncExecutors` with a fake uninitialized bean registered in the Spring context) is far more awkward to construct and does not actually target the specific branch being verified.

### G. `.dockerignore` (Finding 7)

Prefix the single-segment OS/build-artifact patterns with `**/`:
```
*.jar       →  **/*.jar
*.war       →  **/*.war
*.iml       →  **/*.iml
.DS_Store   →  **/.DS_Store
Thumbs.db   →  **/Thumbs.db
*.log       →  **/*.log
*.diff      →  **/*.diff
```
Leave every other pattern in the file untouched — `docker-compose*.yml`, `Dockerfile`, `.dockerignore`, `mvnw`/`mvnw.cmd`, `.gitignore`/`.gitattributes`, `diff.txt`, `skills-lock.json` are all genuinely root-only files by design, not the same defect class. Do **not** touch the `.git/` inclusion or add anything about `git.dirty` — both are separate, decision-needed items this story deliberately does not pick up (see Dev Notes).

---

## Acceptance Criteria

**AC1 — All four pages read the Tsid player-id route/query param as a string, not a corrupted `Number`**
- [x] `ParentDevelopmentPortalPage.vue`'s `playerId` computed returns the raw route-param string, per Design A (bare form, no `?? null` — see Design A's note on the disclosed garbage-param behavior change). The `activePlayerId` watcher's comparison at `:188` now compares two strings.
- [x] `ParentPlayerPortalPage.vue`'s `playerId` const is the raw route-param string. The `activePlayerId` watcher's comparison at `:88` now compares two strings.
- [x] `PlayerSubscriptionPage.vue`'s `playerId` computed returns the raw route-param string.
- [x] `BookingRequestPage.vue`'s `playerId` computed's first branch returns the raw `route.query.playerId` string instead of a `Number`-parsed/`isFinite`-checked value, per Design A.
- [x] New spec file for `ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, and `PlayerSubscriptionPage.vue` (none exist today), each asserting the store action the page calls on mount receives the *exact* large Tsid string, not a `Number`-corrupted one — mirror `PlayerDevelopmentDashboardPageSpec.js`'s pattern: a real large id literal (e.g. `'893573203704173564'`), assert `typeof` the received arg is `'string'` and that it is **not** equal to the Number-corrupted value (`'893573203704173600'`).
- [x] `BookingRequestPage.vue`'s **existing** `BookingRequestPageSpec.js` gains two added cases (not a new file): (1) mount with `route.query.playerId` set to a large Tsid string and `authStore.isPlayer` false, assert the booking-submit store call receives the exact string, not a corrupted `Number`; (2) mount with `route.query.playerId` set to a garbage value (`'abc'`) and `authStore.isPlayer` false, assert `playerId.value` falls back to `playerStore.activePlayerId` rather than becoming `'abc'` — this is the regression guard for Design A's validation gate, which a happy-path-only case cannot catch.
- [x] `ParentDevelopmentPortalPage.vue`'s new spec additionally covers the `activePlayerId`-watcher comparison fix: set `playerStore.activePlayerId` to a string equal to the current route param and assert `router.push` is **not** called (pre-fix, the type-mismatched `!==` would call it unconditionally whenever `activePlayerId` is truthy).

**AC2 — A self-registered adult player can raise a dispute on their own booking**
- [x] `DisputeService` gains a `PlayerProfileRepository` field (added last in the field list, per Design B, so the generated constructor's new parameter appends rather than reorders).
- [x] `raiseDispute`'s `ownerEligible` check resolves `booking.getPlayerId()` through `PlayerProfileRepository` and compares `raisedBy` against the resolved `PlayerProfile.getUserId()`, per Design B. `booking.getParentId()`'s own disjunct is unchanged.
- [x] `DisputeServiceTest` gains a new test (mirroring the existing `raiseDispute_coachOwnsBooking_isEligible` structure): a `PlayerProfile` with `userId` equal to `raisedBy` and `parentId = null` makes `raiseDispute` succeed when called with that same id and `raisedByRole = "PLAYER"`.
- [x] A second new test confirms a *different* self-registered player's id (one that does not match the resolved `PlayerProfile.getUserId()`, and is not the coach either) is still rejected as `NOT_ELIGIBLE` — proves the fix doesn't accidentally widen eligibility to any player, not just the booking's actual one.
- [x] `DisputeServiceTest.setUp()`'s `new DisputeService(...)` call and its `@Mock` field list both updated for the new constructor parameter.

**AC3 — A non-canonical spelling of `/coach/command-center` no longer bypasses the profile-builder gate**
- [x] `router/index.js:89`'s `to.path === '/coach/command-center'` check becomes `to.matched.some((r) => r.path === '/coach/command-center')`, per Design C.
- [x] New test coverage proving the fix — see Dev Notes for how to actually exercise `router/index.js`'s `beforeEach` in a spec (this file's default export, after `defineRouter`, is a plain identity-wrapped factory function in this project's pinned `@quasar/app-vite` version, directly callable in a test — see the precedent `axiosSpec.js` already established for `defineBoot`). At minimum: a coach with an incomplete profile is redirected to `/coach/profile-builder` when navigating to `/coach/command-center/` (trailing slash) and to `/COACH/COMMAND-CENTER` (case variant), not just the canonical spelling.

**AC4 — The previously-unguarded or inert-gated routes are gated to their actual, backend-authorized persona**
- [x] `routes.js`'s `parent/dashboard`, `player/home`, and `player/development/:playerId` each gain the `role: 'PARENT'`/`role: 'PLAYER'` meta per Design D.
- [x] `routes.js`'s `player/locker-room/:playerId` gains `roles: ['PLAYER', 'PARENT']` meta per Design D — **not** singular `role: 'PLAYER'`. This is load-bearing, not a style choice: `PlayerOwnershipGuard` authorizes both personas for this resource, and a live (if currently unlinked) parent-only button at `ParentPlayerPortalPage.vue:10-15` depends on a PARENT being able to reach it.
- [x] `routes.js`'s `parent/player/:playerId/subscription` (backing `PlayerSubscriptionPage.vue`) has its inert `requiresParent: true` meta key replaced with the working `role: 'PARENT'`, per Design D.
- [x] `routes.js`'s `admin` route gains `roles: ['ADMIN']` meta per Design D (the `roles` array mechanism, not the inert singular `role`).
- [x] New or extended router-guard test coverage (same test vehicle as AC3) proving: a PARENT/PLAYER/COACH navigating directly to `/admin/health-dashboard` is redirected via `routeForRole(authStore.role)`, not shown the admin page; a non-PARENT/PLAYER authenticated role navigating to `/parent/dashboard` is likewise redirected (one representative case for the `player/home`/`player/development/:playerId` pair is sufficient — both share the identical gate shape); and a PARENT navigating to `/player/locker-room/:playerId` is **not** redirected (the regression this AC exists to prevent), alongside a non-PLAYER/PARENT role that is.
- [x] `coach/revenue`, `coach/bookings/:bookingId/receipt`, `parent/create-player`, and `player/profile-builder` are explicitly **not** touched by this AC (confirm via diff review before considering this done) — see Design D's "Do not touch" note and Finding 4's "Deliberately not touched" note.

**AC5 — `ReviewSubmissionService.updateReview`'s moderation-status guard is checked before its cooldown guard, at both the unlocked pre-check and the locked re-check**
- [x] Both guard blocks reordered per Design E, at both `:129-141` and `:181-194`.
- [x] New unit test in `ReviewSubmissionServiceTest`: a review with `moderationStatus = BLOCKED` **and** `lastModifiedAt` inside the cooldown window throws `OperationNotAllowedException` with `ReviewErrorCode.EDIT_NOT_PERMITTED`, not `UPDATE_TOO_SOON`.
- [x] New or extended concurrency IT case in `ReviewSubmissionServiceConcurrencyIT`, built exactly per Design E's mechanism note (mirroring `concurrentUpdateReview_secondCallerCannotBypassCooldownViaStaleRefresh`'s hold-open-via-`transactionTemplate` technique, with `AdminReviewService.blockReview` in the role caller A holds open instead of that test's own `updateReview`, releasing A after the same ~300ms delay, fixture seeded `APPROVED`/`PENDING` not `BLOCKED`): caller A's `blockReview` commits and bumps `lastModifiedAt` while caller B's `updateReview` is held at the lock; B's refreshed re-check must reject with `EDIT_NOT_PERMITTED`, not `UPDATE_TOO_SOON`.
- [x] Existing `ReviewSubmissionServiceTest`/`ReviewSubmissionServiceConcurrencyIT`/`ReviewUpdateIT` tests pass unchanged (Finding 5 confirms none of the three currently trips the status guard in a way the reorder would change, so none should need rewriting — a passing-unchanged result is expected, not merely tolerated).

**AC6 — `DatabaseResetTestExecutionListener`'s reset-cost counter records even when quiescing throws, and `isQuiesced` never throws on an uninitialized executor**
- [x] `beforeTestMethod`'s `quiesceAsyncExecutors(ctx)` / `recordQuiesceCost(...)` pair wrapped in `try`/`finally` per Design F.
- [x] `isQuiesced` widened from `private` to package-private (matching its sibling `quiesceAsyncExecutors`'s existing visibility) and guarded against `IllegalStateException` per Design F, returning `true` in that case.
- [x] New test in `DatabaseResetTestExecutionListenerQuiesceTest`: a `ThreadPoolTaskExecutor` that has never had `initialize()` called is passed directly to the now-package-private `isQuiesced(executor)`, and the call returns `true` without throwing.
- [x] No change to `quiesceAsyncExecutors`'s own re-sweep/pass-ceiling logic, `MAX_QUIESCE_PASSES`, or the `ConditionTimeoutException` handling `skillars-deferred-146` just finished stabilizing — confirm the diff touches only `beforeTestMethod`'s `try`/`finally` wrap and `isQuiesced`'s signature/body (the one-line visibility change is part of this AC, not scope creep beyond it).

**AC7 — `.dockerignore`'s single-segment patterns are anchored with `**/` so nested copies are excluded too**
- [x] `*.jar`, `*.war`, `*.iml`, `.DS_Store`, `Thumbs.db`, `*.log`, `*.diff` all gain the `**/` prefix per Design G.
- [x] No other line in `.dockerignore` changed — in particular, `.git/` stays included (its own header comment explains why) and no `git.dirty`-related change is made (explicitly out of scope — see Dev Notes).
- [x] Sanity-checked (not necessarily by an automated test — this is Docker-level, not application-level behavior): a nested `*.log` file placed at e.g. `src/some-dir/x.log` is excluded from the build context after the fix. A manual `docker build . --progress=plain 2>&1 | grep -i "transferring context"` size comparison, or simply reasoning from Docker's own documented `**/` semantics, is sufficient — this AC does not require a new automated test.

**AC8 — Testing & Definition of Done**
- [x] Full targeted backend suite re-run: `DisputeServiceTest`, `ReviewSubmissionServiceTest`, `ReviewSubmissionServiceConcurrencyIT`, `ReviewUpdateIT`, `DatabaseResetTestExecutionListenerQuiesceTest`. Zero regressions. (Also re-ran, as extra diligence beyond this AC's named list: `ReviewSubmissionIT`, `ReviewFlagServiceConcurrencyIT`, `DisputeSubmissionIT`, `CoachPayoutOutboxIT`, `PessimisticLockRetryerCallSiteAuditTest` — every test touching either changed method. See Completion Notes for the one real regression this surfaced and fixed.)
- [x] Full frontend Vitest suite re-run (all files, not just the four touched pages and the router guard) — zero regressions. ESLint/Prettier clean on every touched `.vue`/`.js` file.
- [x] The frontend suite's CI run is via `.github/workflows/frontend-unit-tests.yml`, which auto-triggers on any PR touching `src/frontend/**` but is explicitly **not** a required status check and does not block a merge (confirmed by its own header comment) — the dev agent must read that job's actual result rather than treating a non-failing merge as proof the new frontend tests passed.
- [x] No local `mvn verify` (standing project convention — GitHub CI is the sole full-verification gate; isolated `-Dtest=<Class>` runs are fine and expected).

---

## Tasks / Subtasks

- [x] Task 1 (AC1): Fix the `playerId` read in `ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, and `PlayerSubscriptionPage.vue` per Design A — all three are a mechanical `Number(...)` → bare-param swap, no gate to preserve. Separately, fix `BookingRequestPage.vue`'s `route.query.playerId` read per Design A's own entry for that file — **do not apply the same mechanical swap there**: it has a validation gate whose failure path is a real fallback (`playerStore.activePlayerId`), not a no-op, and the fix replaces the numeric check with an equivalent string-shape check rather than deleting it.
- [x] Task 2 (AC1): Create new spec files for `ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, and `PlayerSubscriptionPage.vue`, per AC1's bullets and the `PlayerDevelopmentDashboardPageSpec.js` precedent. Add two new cases to the existing `BookingRequestPageSpec.js` (happy path + the garbage-value fallback regression guard).
- [x] Task 3 (AC2): Add `PlayerProfileRepository` to `DisputeService`'s field list (last position) and rewrite `raiseDispute`'s `ownerEligible` check per Design B.
- [x] Task 4 (AC2): Add the two new `DisputeServiceTest` cases and update `setUp()`'s constructor call/mock list.
- [x] Task 5 (AC3): Fix `router/index.js:89` per Design C.
- [x] Task 6 (AC4): Add/correct the role/roles `meta` on the routes named in Design D: `parent/dashboard`, `player/home`, `player/development/:playerId` (singular `role`), `player/locker-room/:playerId` (`roles` array — not singular, per Finding 4's correction), `parent/player/:playerId/subscription` (replace the inert `requiresParent: true`), and `admin` (`roles` array).
- [x] Task 7 (AC3, AC4): Build the router-guard test vehicle (see Dev Notes on `defineRouter`'s identity-wrapper behavior) and add the test cases both ACs require, including the `player/locker-room/:playerId` dual-persona case. One new spec file is expected (no existing spec exercises `router/index.js`'s `beforeEach` today — confirmed by `find`).
- [x] Task 8 (AC5): Reorder the two guard blocks in `ReviewSubmissionService.updateReview` at both sites, per Design E.
- [x] Task 9 (AC5): Add the new `ReviewSubmissionServiceTest` case and the new/extended `ReviewSubmissionServiceConcurrencyIT` case, built per Design E's hold-open mechanism note.
- [x] Task 10 (AC6): Apply the `try`/`finally` wrap, widen `isQuiesced` to package-private, and add its `IllegalStateException` guard in `DatabaseResetTestExecutionListener.java`, per Design F.
- [x] Task 11 (AC6): Add the new `DatabaseResetTestExecutionListenerQuiesceTest` case calling the now-package-private `isQuiesced` directly.
- [x] Task 12 (AC7): Apply the seven `**/`-prefix edits to `.dockerignore`, per Design G.
- [x] Task 13 (AC8): Run the full targeted backend suite (including `ReviewUpdateIT`) and full frontend suite; read the frontend CI job's actual result rather than assuming it gates the merge. Fix any regression surfaced by Tasks 1-12 before considering this done. No local `mvn verify`.

### Review Findings

**Post-implementation code review (`/bmad-code-review`, 2026-10-07, working tree vs HEAD `ef2daac9`).** Three parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the ~1,168-line diff, then triage with deduplication. 9 findings survived; 19 candidates were dismissed as false positives or already-resolved design decisions. The headline finding was independently derived by all three layers AND confirmed by execution (revert-and-rerun), and the fix all three layers proposed for it was proven wrong by that same execution.

#### Decision needed

- [x] [Review][Decision] **RESOLVED by owner 2026-10-07: keep PARENT-only, file a follow-up.** `parent/player/:playerId/subscription` is now PARENT-only, but its own read endpoint authorizes PLAYER — AC4 replaced the inert `requiresParent: true` with `role: 'PARENT'` (`routes.js:205-209`), which is a genuine tightening (the old meta was dead — the guard only ever reads `meta.role`). But `SubscriptionResource.java:91-92` guards the page's read with `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")`, which authorizes a self-registered PLAYER viewing their own subscription; only the writes (`:100`, `:110`, `:118`) are `HAS_PARENT_ROLE`. All four sibling dual-purpose `parent/`-prefixed routes use `roles: ['PARENT','PLAYER']` for exactly this reason (`routes.js:124,132,151,160`). This is the SAME defect class as C1 (the locker-room persona label) recurring on a different route. **Note:** this is NOT the "subscription-route in-app-link-breakage" concern round 2 already refuted — nothing in-app links to this route (re-confirmed), so there is no live break. The open question is the backend-authorization mismatch, which round 2 did not examine. Options: (a) widen to `roles: ['PARENT','PLAYER']` to match the endpoint and the sibling precedent; (b) keep PARENT-only as a deliberate product decision and add a one-line comment saying the read endpoint is intentionally more permissive than the route.
  - **Dev agent re-verification (2026-10-07):** the `@PreAuthorize` claim is confirmed exactly as stated. But there's a deeper wrinkle the review didn't examine: `SubscriptionResource.getMyPlayerSubscription` calls `subscriptionService.getPlayerSubscription(currentParentId(), playerId)`, and `SubscriptionService.getPlayerSubscription`/`subscribePlayer`/`changePlayerTier`/`cancelPlayerSubscription` **all** call `assertPlayerOwnership(parentUserId, playerId)` → `parentPlayerLinkRepository.existsByParentIdAndPlayerId(parentUserId, playerId)` (`SubscriptionService.java:899-904`). That repository checks the `main.parent_player_links` table — a parent-child link only — and has **no** self-registered-player branch (unlike `PlayerOwnershipGuard.check`, which checks both `existsByIdAndParentId` and `existsByIdAndUserId`). So even though the controller's `@PreAuthorize` lets a self-registered PLAYER's request through, the service call directly below it would still throw `payment.subscription.playerOwnership` for that same caller — `assertPlayerOwnership` was apparently never updated for self-registered players the way `PlayerOwnershipGuard` was in `skillars-deferred-147`. **Net effect: widening the route's `meta` to `roles: ['PARENT','PLAYER']` would not actually make the page work for a self-registered player today** — they'd reach the page past the router guard only to get a 4xx from the API.
  - **Owner decision (2026-10-07): keep `role: 'PARENT'` as shipped — no code change.** This correctly reflects what the backend actually supports end-to-end today; widening the route alone (option a) would be an incomplete fix without the larger `SubscriptionService.assertPlayerOwnership` change. The self-registered-player gap in `SubscriptionService` (4 methods: `getPlayerSubscription`, `subscribePlayer`, `changePlayerTier`, `cancelPlayerSubscription`) is filed as its own deferred-work item — see `deferred-work.md`, "Deferred from: code review of skillars-deferred-148 (2026-10-07)".
- [x] [Review][Decision] **RESOLVED by owner 2026-10-07: accept as-is, no code change.** `BookingRequestPage`'s new regex is stricter than the check it replaced, and rejected spellings silently retarget a different child — `/^[1-9]\d*$/` on `String(route.query.playerId)` (`BookingRequestPage.vue:245-252`) now rejects `'05'`, `' 5'`, `'+5'`, `'5.0'`, `'1e3'`, `'0x10'`, all of which the old `Number.isFinite(parsed) && parsed > 0` accepted. Each falls through to `playerStore.activePlayerId` — i.e. a URL naming child A can silently book for child B, with no indication the id was discarded (submit `:491`, batch `:558`, packs `:652`, forwarded purchase URL `:481`). It also newly ACCEPTS arbitrarily long digit strings (400 nines) that `isFinite` rejected. Severity low: no in-app producer emits those spellings (verified by grepping every `?playerId=` construction site), so it needs a hand-edited or pasted URL. Options: (a) accept as-is — the Tsid shape is always bare digits (recommended); (b) normalize before testing (`raw.trim()`, strip leading zeros); (c) surface a notify on discard instead of silently falling back.
  - **Dev agent re-verification (2026-10-07):** confirmed by direct execution (`node -e`) — `'05'`, `'+5'`, `'5.0'`, `'1e3'`, `'0x10'`, `' 5'` all pass the old `Number.isFinite(...) && > 0` check and all fail the new regex; a 400-digit string fails the old check (`Number(...)` overflows to `Infinity`) and passes the new regex. Both halves of the claim verified, not just plausible. Agrees this is a real, disclosed-but-not-fully-enumerated behavior change, and recommends option (a) for the stated reason (real Tsids are always bare digit strings; no in-app producer emits any other spelling).
- [x] [Review][Decision] **RESOLVED by owner 2026-10-07: accept and document.** `DisputeSubmissionIT.java` was modified without spec authorization — the spec's Project Structure Notes enumerate exactly four changed test files and do not include it; it appears only in AC8 as a suite to *re-run*. The change itself is necessary and verified sound (the fixture set `booking.player_id` to a User id with no backing `PlayerProfile`, which only passed because the pre-fix code compared against it directly; column list matches every `NOT NULL` on `main.player_profiles` per `V138:896-912`, `parent_id` omitted + `user_id` set satisfies `chk_pp_owner`, cleanup order deletes `player_profiles` before `main."user"` and the FK is `ON DELETE CASCADE` anyway, and `PLAYER_ID` is referenced nowhere else as a booking id). It is disclosed in the Dev Agent Record but not authorized in the spec body. Options: (a) accept the scope expansion and add it to Project Structure Notes (recommended — it was a prerequisite for AC2, not scope creep); (b) split it into its own story.
  - **Dev agent re-verification (2026-10-07):** independently re-confirmed every technical claim against the real schema/FK graph — all correct. Agrees with option (a): this fixture fix was a necessary prerequisite for AC2's own test coverage to mean anything at the API layer (without it, `DisputeSubmissionIT` would silently exercise the pre-fix bug's own shortcut), not an unrelated scope expansion. Project Structure Notes updated accordingly once this decision is confirmed.

#### Patch

- [x] [Review][Patch] AC5's new unit test is vacuous — it passes with the production reorder reverted, and site 1 has no red-on-revert coverage at all [`src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceTest.java:124-139`] — **CONFIRMED BY EXECUTION**, not deduction: reverting the site-1 reorder (cooldown back above status) and re-running gives `tests="3" failures="0"`. Cause: the test never stubs `configService.getBoundedInt(...)`, which returns primitive `int` (`ConfigService.java:148`), so Mockito yields `0`; the cooldown guard then evaluates `(now-1d).isAfter(now.minus(0, DAYS))` → `false`, so the test's own premise ("BOTH blocked AND inside its cooldown window") is never established and the status guard throws `EDIT_NOT_PERMITTED` in either ordering. Compounding: site 1 (the unlocked pre-check, `ReviewSubmissionService.java:129-144`) has NO red-on-revert coverage anywhere — the new concurrency IT's caller B passes both site-1 guards (APPROVED + 40-day-old `lastModifiedAt`) and therefore only exercises site 2. Recorded negative search: grepped `src/main/java src/test/java src/frontend/src` for `UPDATE_TOO_SOON|updateTooSoon|EDIT_NOT_PERMITTED|editNotPermitted`; the only other candidates (`ReviewUpdateIT:156,270,436`) are order-insensitive by fixture. **The obvious fix does not work** — adding a plain `when(configService.getBoundedInt(...)).thenReturn(30)` fails with `UnnecessaryStubbingException` under `STRICT_STUBS`, because post-fix the status guard throws before the config read (verified by execution). Verified working fix: `lenient().when(configService.getBoundedInt(anyString(), any(Integer.class), any(Integer.class), any(Integer.class))).thenReturn(30);` plus `import static org.mockito.Mockito.lenient;` — confirmed green with the correct order and red on revert with `expected: EDIT_NOT_PERMITTED but was: UPDATE_TOO_SOON`.
- [x] [Review][Patch] AC4's enumerated COACH case against `/admin/health-dashboard` is missing [`src/frontend/src/router/__tests__/routerGuardSpec.js:83-99`] — AC4 requires "a PARENT/PLAYER/COACH navigating directly to `/admin/health-dashboard` is redirected via `routeForRole(authStore.role)`"; only PARENT and PLAYER are covered. Add a COACH case (needs `profileBuilder: { status: { profileComplete: true } }` so the command-center gate does not chain the redirect, as the existing `/parent/dashboard` COACH case already does).
- [x] [Review][Patch] AC2's negative test asserts only the exception type, not the `NOT_ELIGIBLE` code [`src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java:208-227`] — AC2 says "still rejected as `NOT_ELIGIBLE`". Use `.extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(DisputeError.NOT_ELIGIBLE)`, matching the stronger shape the sibling `ReviewSubmissionServiceTest` addition uses. (`NOT_ELIGIBLE` is in fact the only reachable `OperationNotAllowedException` at that point — the window check is downstream — so this strengthens the assertion rather than fixing a wrong pass.) Also drop the dead `booking.setUpdatedAt(Instant.now())` on line 213: the throw happens before the submission-window check, so it misleads about which path the test exercises.
- [x] [Review][Patch] No test pins the orphaned-`PlayerProfile` fall-through [`src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java`] — Design B's `.orElse(false)` correctly lets a booking whose `player_id` has no `player_profiles` row fall through to the coach-eligibility branch (there is no FK on `bookings.player_id`, so this is reachable), but nothing asserts it. Add a case stubbing `playerProfileRepository.findById(...)` → `Optional.empty()` and asserting the coach disjunct still decides the outcome.
- [x] [Review][Patch] `DisputeService`'s pre-existing "looked up lazily" comment now misdescribes the code [`src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:109-110`] — that comment says the coach lookup is "looked up lazily, only when the caller isn't already the parent/player, to avoid an unconditional DB round-trip on the common (non-coach) path". After AC2's change, a coach raising a dispute now performs two profile lookups (the new `playerProfileRepository.findById` runs first on every non-parent path). Correctness-neutral, but the comment should be refreshed to describe the two-hop reality.
- [x] [Review][Patch] Spec-internal inconsistency on the `BookingRequestPageSpec` case count — Project Structure Notes say "one new case added" while AC1 says "two added cases"; the implementation correctly shipped two. The round-2 revision was not propagated into Project Structure Notes.

#### Dismissed (recorded so they are not re-raised)

19 candidates were dismissed. The substantive ones, with the evidence that killed them: `**/*.jar` breaking the Docker build (Moby compiles a leading `**/` to `(.*[/])?` so root-level files still match — strictly a broadening; `git ls-files` and a `find src` sweep return no jar/war/log/diff/iml anywhere; the Dockerfile uses the Maven base image's own `mvn`, there is no `.mvn` directory, and the runtime jar comes from `COPY --from=builder`, which `.dockerignore` does not affect); `findById(null)` throwing (`booking.player_id` is `NOT NULL`, `V138:230` + `Booking.java:33`); the `requiresParent` swap removing an ownership check (a recorded grep shows `meta.requiresParent` was never read by the guard — it was dead meta); `player/development/:playerId` needing dual-role (its only inbound navigation is `MainLayout.vue:330` inside `v-if="authStore.isPlayer"`, and parents have their own `parent/players/:playerId/development` route); `to.matched.some(...)` over-firing on descendants (that record has no children, alias, or redirect); `isQuiesced`'s catch being too broad (verified against `spring-context-6.2.19-sources`: `getThreadPoolExecutor()` is a single `Assert.state`, nothing else in the chain throws `IllegalStateException`, and shutdown never nulls the delegate); the concurrency IT flaking on its retry budget (`PessimisticLockRetryer:90-100` gives 8 attempts over ~2-3.2s against a 300ms hold, and `review_moderation_log.admin_id` has no FK so the synthetic `adminId` cannot fail); a redirect loop for an unknown role (`routeForRole` uses `Object.hasOwn` → `DEFAULT_ROUTE='/dashboard'`, auth-only meta); the shared `insertBooking` helper breaking sibling tests (`PLAYER_ID` is used only for user creation and cleanup); the latch/InterruptedException patterns in the new IT (both mirror the pre-existing sibling test at `:290` exactly — no divergence from the established shape); and the three route-param pages dropping their `isNaN` guard (explicitly disclosed and accepted in Design A, with the principled asymmetry that those three have no fallback to retarget to, unlike `BookingRequestPage`). Also dismissed: the `.not.toBe(CORRUPTED_PLAYER_ID)` assertions are indeed decorative (number-vs-string under `Object.is` on revert), but the sibling `toBe(LARGE_PLAYER_ID)` and `typeof === 'string'` assertions in the same tests are genuinely red on revert, so the specs work as regression guards.

#### Independently re-verified green by execution during this review

Full frontend Vitest suite: **247/247 across 37 files**, confirming all four new spec files run and pass (vitest's `src/**/__tests__/**` include glob picks them up). `ReviewSubmissionServiceTest`: 3/3 as shipped. The Edge Case Hunter's one open question — whether `routerGuardSpec.js` passes end-to-end under happy-dom — is answered by that full-suite run. Working tree was restored byte-identically after the revert experiments (verified by `git diff --stat`).

#### Resolution Summary (dev agent, 2026-10-07)

All 3 `decision-needed` and all 6 `patch` findings resolved; 0 unresolved HIGH/MEDIUM issues remain.

**Decisions** (owner confirmed live, see each bullet above for the resolution and rationale):
1. `parent/player/:playerId/subscription` stays `role: 'PARENT'`-only — re-verification found an additional backend gap (`SubscriptionService.assertPlayerOwnership` has no self-registered-player branch, so widening the route alone would not have worked end-to-end anyway). Filed as its own deferred-work item.
2. `BookingRequestPage`'s stricter regex accepted as-is — no code change; both halves of the claim (newly-rejected old spellings, newly-accepted long-digit strings) independently confirmed by execution.
3. `DisputeSubmissionIT.java`'s scope accepted and documented — added to Project Structure Notes above.

**Patches** (all applied and independently re-verified by execution, not just read):
1. **AC5's unit test was vacuous — CONFIRMED by independently re-running the revert experiment**: reverting the production guard order and re-running `updateReview_blockedAndWithinCooldown_throwsEditNotPermittedNotUpdateTooSoon` alone still went green (`Tests run: 1, Failures: 0`). Applied the review's verified fix (`lenient()` stub on `configService.getBoundedInt`); re-ran the mutation check myself — green with the fix, red (`expected: EDIT_NOT_PERMITTED but was: UPDATE_TOO_SOON`) with the bug reintroduced. Full class re-run: 3/3.
2. Added the missing COACH case to `routerGuardSpec.js` for `/admin/health-dashboard`. Full file re-run: 12/12 (was 11).
3. Strengthened AC2's negative test to assert `DisputeError.NOT_ELIGIBLE`, not just the exception type; dropped the dead `booking.setUpdatedAt(...)` call (confirmed the throw happens before the submission-window check that line was meant to set up for).
4. Added a new test pinning the orphaned-`PlayerProfile` fall-through (coach disjunct still decides when the player lookup is empty). `DisputeServiceTest` full re-run: 11/11 (was 10).
5. Refreshed the now-stale "looked up lazily" comment in `DisputeService.java` to describe the two-hop reality post-AC2.
6. Fixed the Project Structure Notes case-count inconsistency for `BookingRequestPageSpec.js` (two cases, not one).

**Final full regression re-run after all patches:** `DisputeServiceTest` 11/11, `ReviewSubmissionServiceTest` 3/3, full frontend Vitest suite 248/248 across 37 files, ESLint/Prettier clean on every touched file.

---

## Dev Notes

- **Re-verify every line number in this file against the file's actual current state before using it.** All citations were taken against HEAD `ef2daac9` on a clean tree at story-creation time; this story touches `routes.js` and `router/index.js` in the same session (Tasks 5, 6) and `ReviewSubmissionService.java` twice in the same method (Task 8) — a citation accurate at drafting time can drift once an earlier task in this same implementation session has run.
- **`defineRouter` (and `defineBoot`) are plain identity wrappers in this project's pinned `@quasar/app-vite` version** (`export const defineRouter = wrapper` where `wrapper = callback => callback`) — confirmed precedent at `src/frontend/src/boot/__tests__/axiosSpec.js:16-20` for the identical mechanism applied to `defineBoot`. This means `router/index.js`'s default export, after unwrapping, is directly callable in a test: `import createRouterFactory from 'src/router'` (or via dynamic import, matching however the dev agent confirms the module resolves in this Vite/Vitest setup) then `const router = createRouterFactory({})` returns the **real** `Router` instance with the real `beforeEach` installed — no mocking of `#q-app/wrappers` needed, the same way `axiosSpec.js` needs none for `defineBoot`. `useAuthStore()`/`useProfileBuilderStore()` are called directly inside the guard (not injected), so the test needs an active Pinia instance before calling the factory — `setActivePinia(createTestingPinia(...))` (or a plain `createPinia()` plus `setActivePinia`) before invoking the factory, mirroring how every other store-consuming spec in this codebase sets up Pinia. This is the non-obvious fact Tasks 7's test vehicle depends on — confirm it holds before assuming a heavier mocking approach is needed.
- **Do not widen the router guard to add a `meta.role === 'COACH'` check, or migrate `coach/revenue`/`coach/bookings/:bookingId/receipt` to `requiresCoach: true`, and do not add a role gate to `parent/create-player` or `player/profile-builder`, as part of this story.** Finding 4 documents all four as the same defect class but either not named by the sourcing ledger item (`coach/*`) or not yet checked for their actual inbound-navigation shape (the two onboarding routes) — picking any of them up here would silently expand AC4's scope beyond what was verified and selected. A future story's job.
- **`player/locker-room/:playerId` is the one route in this story where "what persona does the route *look* like it's for" and "what does the backend actually authorize" diverge — trust the backend.** `PlayerOwnershipGuard` is the source of truth here, not the route's name or `ROLE_ROUTES`'s redirect chain. If a future edit touches this route again, re-check `PlayerOwnershipGuard.check` before assuming a single-role gate is correct.
- **`ParentPlayerPortalPage.vue`'s `playerId` stays a plain, non-reactive `const` after this story's fix (Design A) — it does not gain a `watch(() => route.params.playerId, ...)` the way `ParentDevelopmentPortalPage.vue` already has one.** This means param-only navigation between two different players on this specific page still would not reload without a full remount. Pre-existing, out of scope for this story (the route is currently unreachable via any in-app navigation, so every real arrival today is a fresh mount) — not fixed here, and AC1 should not be read as claiming this file is now fully equivalent to its siblings in reactivity, only that its `playerId` value is no longer corrupted.
- **Do not pick up the `.dockerignore` `git.dirty`/`.git/`-in-build-context items** (separate, decision-needed deferred-work.md entries under "code review of skillars-deferred-142") — those require a product/ops decision about whether build provenance is used for anything, which this story's AC7 deliberately does not make. Only the `**/`-prefix mechanical fix (Finding 7) is in scope.
- **Do not pick up the theft-driven mass-refresh-token-revocation item** from "code review of skillars-deferred-144" (`AuthService.refresh()`'s reuse-detection branches never terminate a live JWT session) — this is a decision-needed design question (whether "token reuse detected" should end live sessions at all, and if so, which of the `skillars-deferred-143`-style mechanisms to reuse), explicitly left for the owner to decide in a dedicated pass, not folded into this mechanical-fixes bundle.
- **Do not pick up `isGenuineDenial` widening to `USER_NOT_FOUND`/`UNKNOWN`, the unguarded-against-transient-DB-failures item, the `sinceAfter`-not-re-derived-after-refresh item, the `minSessionAgeDays`/`updateCooldownDays` strict-ordering-vs-minimum-gap item, the native-speaker fr/de translation review, or the AC4.3 manual browser walkthrough.** All are deliberately left deferred again — each is either human-only, decision-needed (what bound/translation to pick), or confirmed-latent-and-not-reachable-today in the ledger's own words. None is silently dropped; each is still in `deferred-work.md` under its original section after this story merges, same as before.
- **`ReviewSubmissionService.java`'s two guard blocks (Design E) are reordered, not merged or deduplicated.** Resist the temptation to extract a shared `checkCooldownAndStatus(...)` helper as part of this story — the unlocked and locked sites read from different instances (`review` vs. `locked`) with different config-read variable names (`cooldownDays` vs. `lockedCooldownDays`), and a refactor here is a larger, separately-reviewable change than the mechanical reorder this story's AC5 asks for.
- **The `DisputeService` constructor-parameter-ordering note in Design B matters for minimizing the `DisputeServiceTest` diff** — Lombok's `@RequiredArgsConstructor` generates parameters in field-declaration order; adding `playerProfileRepository` anywhere but last would shift every subsequent mock's position in the existing test's constructor call, touching lines that have nothing to do with this fix.

### Project Structure Notes

- Changed frontend files: `src/frontend/src/pages/parent/ParentDevelopmentPortalPage.vue`, `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue`, `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`, `src/frontend/src/pages/parent/BookingRequestPage.vue`, `src/frontend/src/router/index.js`, `src/frontend/src/router/routes.js`. All six already exist in their current locations; no new directories.
- New frontend test files: `src/frontend/src/pages/parent/__tests__/ParentDevelopmentPortalPageSpec.js`, `src/frontend/src/pages/parent/__tests__/ParentPlayerPortalPageSpec.js`, `src/frontend/src/pages/parent/__tests__/PlayerSubscriptionPageSpec.js` (the `__tests__` directory already exists under this path, confirmed by `find` — holds `BookingRequestPageSpec.js`/`ParentBookingsPageSpec.js` today, these are new files in an existing directory). One new router-guard spec file, exact name/location left to the dev agent (no `__tests__` precedent exists for testing `router/index.js` itself today — `src/frontend/src/router/__tests__/safeRedirectSpec.js` tests `safeRedirect.js`, a sibling module, not `index.js`'s guard).
- Changed frontend test file: `src/frontend/src/pages/parent/__tests__/BookingRequestPageSpec.js` (existing file, two new cases added — not a new file).
- Changed backend files: `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java`, `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`, `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java`. All inside their existing modules, consistent with `project-context.md`'s module-layering rules — `DisputeService` gains a dependency on `PlayerProfileRepository` (`platform.security.repo`), the same cross-module repository dependency `ReviewSubmissionService` already has for the identical reason.
- Changed test files: `src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java`, `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceTest.java`, `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java`, and whichever existing class in `src/test/java/com/softropic/skillars/config/` already covers `DatabaseResetTestExecutionListener` (`DatabaseResetTestExecutionListenerQuiesceTest`, confirmed to exist from `skillars-deferred-146` — re-verify its exact path at implementation time).
- **Code review 2026-10-07 (Decision resolved: accept and document):** `src/test/java/com/softropic/skillars/platform/admin/api/DisputeSubmissionIT.java` is also a changed test file, not listed above at story-creation time. Its fixture set `booking.player_id` directly to a User id with no backing `player_profiles` row — the exact cross-ID-space shape AC2 fixes — which only ever passed because the pre-fix `raiseDispute` compared `raisedBy` against `booking.getPlayerId()` directly too. Fixed by inserting a real `main.player_profiles` row (self-registered: `user_id` set, `parent_id` null) and pointing the booking's `player_id` at its PK. A necessary prerequisite for AC2's own IT-level coverage to mean anything, not scope creep.
- Changed infra file: `.dockerignore` (repo root).
- No DB migration, no new REST endpoint, no DTO/response-shape change visible to any caller.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: manual testing of skillars-deferred-147 (2026-10-07)"] — Finding 1 (all three sibling pages).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-145 (2026-10-06)"] — Finding 2 (the `DisputeService.java:91` cross-ID-space item; the `findQualifyingCompletedBookings` LIMIT/ordering item from this same section is deliberately NOT picked up here, see Dev Notes).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-145, round 2 (2026-10-06)"] — Finding 5 (the locked-cooldown-precedes-status-guard item; the other two items in this section — `sinceAfter` staleness and the strict-ordering-vs-minimum-gap config invariant — are deliberately NOT picked up here, see Dev Notes).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-144 (2026-10-06)"] — Findings 3 and 4 (two of this section's three items; the theft-driven-mass-revocation item is deliberately NOT picked up here, see Dev Notes).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-142 (2026-10-05)"] — Finding 7 (the `.dockerignore` single-segment pattern item).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-146 (2026-10-07)"] — Finding 6 (two of this section's four items; the reset-mean-variance item is unexamined/likely-noise and the `docker-image`-as-required-check item is decision-needed merge-policy, neither picked up here).
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:253-264`] — the existing `Booking.playerId`-is-a-`PlayerProfile`-PK fix this story's Design B mirrors for `DisputeService`.
- [Source: `src/frontend/src/boot/__tests__/axiosSpec.js:16-20`] — the `defineBoot`/`defineRouter`-is-an-identity-wrapper precedent Design C/D's test strategy depends on.
- [Source: `src/frontend/src/router/roleRoutes.js:15-17`] — `ROLE_ROUTES`/`routeForRole`, confirming `/admin/health-dashboard` is already `ROLE_ROUTES.ADMIN`'s own landing route.
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`] — the authoritative source for which persona(s) `player/locker-room/:playerId` must stay reachable by (Finding 4's correction, AC4).
- [Source: `_bmad-output/implementation-artifacts/story-review.md` (2026-10-07)] — pre-implementation review this story's Context/Design/AC sections were corrected against; see the Source section's summary above.

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

None — no HALT conditions hit. All targeted test runs are recorded under Completion Notes below with pass counts.

### Completion Notes List

- **AC1** (4 pages): Applied Design A exactly as specified — mechanical `Number(...)`→bare-string swap for `ParentDevelopmentPortalPage.vue`/`ParentPlayerPortalPage.vue`/`PlayerSubscriptionPage.vue`, and the shape-check replacement (not a gate removal) for `BookingRequestPage.vue`'s `route.query.playerId` branch. Added 3 new spec files + 2 new cases in the existing `BookingRequestPageSpec.js`. One pre-existing assertion in that file (`expect(payload.playerId).toBe(5)`) necessarily changed to `toBe('5')` — the type change from `Number` to `String` is this AC's whole point, so the old assertion was testing the bug's own symptom. 11/11 new+updated frontend tests green.
- **AC2** (DisputeService): Applied Design B exactly — `PlayerProfileRepository` added last in the field list, `ownerEligible` resolves `booking.getPlayerId()` through it. 2 new `DisputeServiceTest` cases added; all 10 tests in that class green. **Found and fixed a real pre-existing bug this AC's own fix surfaced**: `DisputeSubmissionIT` (from `skillars-deferred-63`) had a fixture that set `booking.player_id` directly to a User id with no backing `PlayerProfile` row at all — the exact cross-ID-space shape this story's AC2 fixes. It only ever passed because the *old*, buggy `raiseDispute` compared `raisedBy` directly against `booking.getPlayerId()`, so the bug and the fixture's shortcut canceled each other out. Fixed by inserting a real `main.player_profiles` row (self-registered: `user_id` set, `parent_id` null) and pointing the booking's `player_id` at its PK instead of the raw User id. All 12 tests in that IT now pass, including `raiseDispute_player_eligible_returns201WithPlayerRole` and `getDispute_playerOwned_returns200`, which are the exact end-to-end proof of this AC's user story ("a self-registered adult player can raise a dispute on their own booking") at the real HTTP/DB layer — this is a stronger signal than the unit test alone. Also re-ran `CoachPayoutOutboxIT` and `PessimisticLockRetryerCallSiteAuditTest` (both reference `DisputeService`) — unaffected, green.
- **AC3/AC4** (router): Applied Design C/D exactly. Built the router-guard test vehicle per Dev Notes' `defineRouter`-is-an-identity-wrapper finding — confirmed it holds, no heavier mocking needed. New file `src/frontend/src/router/__tests__/routerGuardSpec.js`, 11 cases covering both ACs against the **real, unmodified `routes.js`/`index.js`** (no route-table stand-in), including the `player/locker-room/:playerId` dual-persona regression guard. All 11 green. Confirmed via diff review that `coach/revenue`, `coach/bookings/:bookingId/receipt`, `parent/create-player`, `player/profile-builder` were not touched.
- **AC5** (ReviewSubmissionService): Applied Design E's pure reorder at both guard sites. Added the new `ReviewSubmissionServiceTest` case (BLOCKED + in-cooldown → `EDIT_NOT_PERMITTED`) and a new `ReviewSubmissionServiceConcurrencyIT` case built exactly per Design E's mechanism note (`AdminReviewService.blockReview` held open via `transactionTemplate`, caller B's `updateReview` blocks on the row lock and re-checks post-commit). Re-ran `ReviewSubmissionServiceTest` (3/3), `ReviewSubmissionServiceConcurrencyIT` (4/4, including the 2 pre-existing cases), `ReviewUpdateIT` (11/11) — all green, confirming Finding 5's prediction that no existing test's result changes under the reorder. Also re-ran `ReviewSubmissionIT` (16/16) and `ReviewFlagServiceConcurrencyIT` (6/6) as extra diligence since both also call `updateReview` — unaffected.
- **AC6** (DatabaseResetTestExecutionListener): Applied Design F exactly — `try`/`finally` wrap, `isQuiesced` widened to package-private with the `IllegalStateException` guard. New test calls the now-package-private method directly on a never-`initialize()`d executor. `DatabaseResetTestExecutionListenerQuiesceTest` 3/3 green (2 pre-existing + 1 new).
- **AC7** (.dockerignore): Applied Design G's exact 7-line `**/`-prefix edit, nothing else touched (confirmed via diff). **Empirically verified** (not just reasoned about) with an isolated two-build comparison in a scratch directory: a nested `src/nested/nested.log` leaked into the build context under the old `*.log` pattern and was correctly excluded under `**/*.log`. Scratch images/dir cleaned up after.
- **AC8**: No local `mvn verify` run, per standing project convention (`docs/validation-strategy.md`). Full frontend Vitest suite: 247/247 tests across 37 files green, including every new/modified spec. ESLint and Prettier both clean on every touched `.vue`/`.js` file (one new file needed a `prettier --write` pass after creation; re-verified clean and still green after).

### File List

**Frontend — changed:**
- `src/frontend/src/pages/parent/ParentDevelopmentPortalPage.vue`
- `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue`
- `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/router/index.js`
- `src/frontend/src/router/routes.js`

**Frontend — new:**
- `src/frontend/src/pages/parent/__tests__/ParentDevelopmentPortalPageSpec.js`
- `src/frontend/src/pages/parent/__tests__/ParentPlayerPortalPageSpec.js`
- `src/frontend/src/pages/parent/__tests__/PlayerSubscriptionPageSpec.js`
- `src/frontend/src/router/__tests__/routerGuardSpec.js`

**Frontend — changed test:**
- `src/frontend/src/pages/parent/__tests__/BookingRequestPageSpec.js`

**Backend — changed:**
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java`

**Backend — changed test:**
- `src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListenerQuiesceTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/DisputeSubmissionIT.java` (not in the story's original Project Structure Notes — fixed a pre-existing fixture bug this story's AC2 surfaced; see Completion Notes)

**Infra — changed:**
- `.dockerignore`

## Change Log

| Date | Version | Change | Author |
| :--- | :--- | :--- | :--- |
| 2026-10-07 | 1.0 | Implemented via `/bmad-dev-story`. All 13 tasks complete; status ready-for-dev → in-progress → review. Found and fixed one pre-existing test-fixture bug (`DisputeSubmissionIT`) that AC2's own fix surfaced — see Completion Notes. | Claude Sonnet 5 |
| 2026-10-07 | 1.1 | `/bmad-code-review` (three parallel layers + triage): 9 findings, 0 false positives after independent re-verification. 3 decision-needed resolved live by the owner (subscription route stays PARENT-only pending a separate `SubscriptionService.assertPlayerOwnership` fix, filed to `deferred-work.md`; `BookingRequestPage` regex accepted as-is; `DisputeSubmissionIT` scope documented). 6 patches applied, each independently re-verified by execution (not just read) — most notably AC5's new unit test was **confirmed vacuous by re-running the revert experiment myself**: it passed even with the production guard-order bug reintroduced, because the unstubbed `configService.getBoundedInt` mock defaulted to `0`, masking the cooldown guard entirely; fixed with a `lenient()` stub, re-confirmed green with the fix and red (`expected: EDIT_NOT_PERMITTED but was: UPDATE_TOO_SOON`) on revert. Also added a missing COACH case to the router-guard spec, strengthened an AC2 test's assertion to check the error code, added a test for the orphaned-`PlayerProfile` fall-through, refreshed a stale code comment, and fixed a doc-count inconsistency. Full regression re-run after all patches: `DisputeServiceTest` 11/11 (+1), `ReviewSubmissionServiceTest` 3/3, `ReviewSubmissionServiceConcurrencyIT` 4/4, `ReviewUpdateIT` 11/11, `DisputeSubmissionIT` 12/12, frontend Vitest 248/248 across 37 files (+1), ESLint/Prettier clean. Status review → done. | Claude Sonnet 5 |
