# Story skillars-deferred-82: Self-booking session-pack UX completion, and AvailabilityService.deleteBlock test coverage

Status: done

<!-- Revised 2026-08-29 after senior-dev-review (story-review.md, "Ready for dev with corrections"):
every flagged verification gap was resolved by direct code investigation during this revision pass
rather than left as a "confirm during implementation" hedge, matching this project's established
convention (see skillars-deferred-79/-81's own equivalent revision passes). Two real, small
additional fixes were found during this verification (CTA `:loading` binding excludes players;
route-semantics comment) and folded into AC2/AC3. No AC was redesigned — every flagged assumption
checked out true or was corrected in place. Search for "story-review, resolved" below for the
per-item index. -->

## Story

As the platform owner,
I want the self-registered (no-parent) player's session-pack purchase flow that `skillars-deferred-81` AC4 unlocked on the backend to actually be reachable and usable end-to-end from the frontend, and `AvailabilityService.deleteBlock`'s missing negative-path unit test closed,
so that a self-booking player can discover, buy, and later view/manage their own session pack the same way a parent-managed player already can, and `deleteBlock` has the same not-found/not-owned regression protection every sibling delete-type method already has.

## Story creation context

Per standing instruction, this story's creation re-mined `_bmad-output/implementation-artifacts/deferred-work.md` (1675 lines) starting with Booking/Availability/Reschedule, then escalated through Video/Playback/Moderation, Messaging/Admin/Reviews/Disputes, and Payments/Stripe/Credit wallet in that order — the same priority sequence `skillars-deferred-81` used. **All four modules came up genuinely dry.** Two parallel research passes (one covering the first three modules, one dedicated to the fourth) independently verified every untagged ledger bullet against live current code and found:

- **Zero real, actionable code-level bugs survived verification** across all four modules — every candidate was either already fixed elsewhere unannotated, or a false premise (the described issue doesn't exist in current code), or an explicitly-reasoned accepted tradeoff from a prior review round.
- **Two items needed a project-owner decision before being actionable.** Both were brought to the project owner directly during this story's creation (2026-08-29):
  1. **Messaging orphaned-profile inconsistency** (`getConversations`/`getMessages`/`sendMessage` behave differently for an orphaned player profile) — **decided: leave as-is.** No live code path today can actually produce this state (confirmed during `skillars-deferred-81` AC4's own research: nothing deletes a `player_profiles` row while leaving it referenced), so it remains a documented, non-reachable edge case, not a fix candidate.
  2. **`session.drill_video_refs.video_id` FK re-add** (the migration applied then reverted during `skillars-deferred-81`'s own code review) — **decided: keep deferring.** `skillars-deferred-81` AC3's in-process pessimistic lock already covers the real concurrent-request race; the FK closes no reachable bug today.
- **One small, real, mechanical item survived**: `AvailabilityService.deleteBlock` has only a happy-path unit test (`AvailabilityServiceTest.java:996`, added by `skillars-deferred-80` AC1) — no not-found/not-owned negative-path unit test exists, unlike the not-owned case which the codebase already exercises at the IT/HTTP layer (`AvailabilityResourceIT.deleteBlock_notOwnedByCoach_returns403:315`). This became AC1.

With the ledger this dry, the project owner (per this project's own documented fallback for exactly this scenario — see `[[project_skillars_release_workflow]]`) chose to **add an unrelated task** rather than skip the cycle or ship an undersized story. The chosen task, surfaced during this story's own creation research rather than pre-selected: `skillars-deferred-81` AC4 unlocked the *backend* (ownership check, 4 widened endpoints) and the *route* (`purchase-sessions` now dual-role) for a self-booking player to buy a session pack, but its own Dev Notes explicitly flagged "no in-app link to this route currently exists for either role — this AC unlocks the backend+route, it does not add a nav entry point." Investigating that gap directly (not trusting the flag at face value) surfaced it is **larger than a missing link**: `CoachPublicProfilePage.vue`'s CTA logic still hard-codes the pre-`skillars-deferred-81` assumption that "packs are out of scope for a self-booking player" in three places, and there is no way at all — not even indirectly — for a self-booking player to reach the pack-management dashboard after purchasing. This became AC2 and AC3.

## Acceptance Criteria

1. **Add the missing not-found/not-owned negative-path unit test for `AvailabilityService.deleteBlock`.** Confirmed live: `AvailabilityServiceTest.java` has exactly one `deleteBlock` test, `deleteBlock_ownedByCallingCoach_succeeds` (`:996-1010`, added by `skillars-deferred-80` AC1) — happy path only. `AvailabilityService.deleteBlock` (`AvailabilityService.java:336-342`) resolves the block via `blockRepository.findByIdAndCoachId(blockId, lockedProfile.getId())`, throwing `OperationNotAllowedException("Block not found or not owned by coach", SecurityError.MISSING_RIGHTS)` on empty — a single code path that collapses "block id doesn't exist" and "block belongs to a different coach" into one outcome (the method has no way to distinguish them, so one test covers both). This exact negative case is already exercised at the IT/HTTP layer (`AvailabilityResourceIT.deleteBlock_notOwnedByCoach_returns403:315`), but never at the unit level — this AC closes that gap.

   - Add `deleteBlock_notFoundOrNotOwned_throwsOperationNotAllowed` to `AvailabilityServiceTest.java`, directly beneath the existing `deleteBlock_ownedByCallingCoach_succeeds` test, mirroring its own mock setup shape: stub `coachProfileRepository.findByUserId`/`findByIdForUpdate` to return the calling coach's profile as usual, but stub `blockRepository.findByIdAndCoachId(blockId, coachId)` to return `Optional.empty()`. **Confirmed `Optional.empty()` is the correct stub shape (story-review, resolved)**: `CoachAvailabilityBlockRepository.findByIdAndCoachId` is declared `Optional<CoachAvailabilityBlock> findByIdAndCoachId(UUID id, UUID coachId)` (`CoachAvailabilityBlockRepository.java:13`), and this exact `Optional.empty()` stub shape is already used elsewhere in this same test file (e.g. `AvailabilityServiceTest.java:260`, `coachProfileRepository.findById(coachId)`) — not a new pattern.
   - Assert `assertThatThrownBy(() -> service.deleteBlock(COACH_USER_ID, blockId)).isInstanceOf(OperationNotAllowedException.class).hasMessageContaining("Block not found or not owned by coach")`, and `verify(blockRepository, never()).delete(any())`.
   - No production code change — this AC is test-coverage only.
   - Test: the new test itself is the deliverable; run `mvn -o test -Dtest=AvailabilityServiceTest` and confirm the full class (33 tests including the new one) stays green.

2. **Fix `CoachPublicProfilePage.vue`'s CTA logic to treat a self-booking player's session-pack purchase as a real option, not an out-of-scope case `skillars-deferred-81` AC4 has already made false.** Confirmed live, three separate places in this file still encode the pre-`skillars-deferred-81` assumption:
   - `onMounted` (`:292-327`): the `else if (authStore.isPlayer)` branch (`:304-319`) resolves `selfPlayerId` via `playerStore.fetchSelfPlayerId()` but explicitly skips `bookingStore.loadPlayerPacks(...)`, with a comment reading "Packs are out of scope for a self-booking player (session-pack purchase requires both parent_id and player_id — a self-registered player has no ownable pack)" — no longer true; `skillars-deferred-81` AC4 gave a self-booking player exactly this ability via the `parentId`-vs-`getUserId()` XOR ownership check.
   - `hasCreditsForCoach` (`:274-278`) and `ctaLabel` (`:280-284`): both are computed purely from `bookingStore.sessionPacks`/`authStore.isParent`, with no self-booking-player branch at all — for a player caller, `ctaLabel` always falls through to `t('marketplace.bookSession')` regardless of whether they actually hold an active pack with this coach, since `sessionPacks` was never populated for them in the first place (see `onMounted` above).
   - `handleCta` (`:329-354`): the `authStore.isPlayer` branch (`:331-339`) unconditionally routes to `request-booking` (per-session) with a comment "Packs are out of scope for a self-booking player — always go straight to the booking-request flow" — the `authStore.isParent` branch two lines below it (`:340-350`) is the exact logic a self-booking player now needs too: route to `request-booking` if they already hold credits with this coach, otherwise `purchase-sessions`.

   **Confirmed the backend already supports this end-to-end (story-review Critical Issue #1, resolved — was previously a "trust-me" claim, now cited)**: `SessionPackPaymentService.purchasePack(Long parentId, ...)` (`:55-71`) resolves the player via `findById(playerId)` then branches `if (player.getParentId() != null)` (`:63`, compares against the caller's `parentId` param) `else` compares `player.getUserId()` against the same param (`:68`) — a self-registered player's own `userId`, passed as this method's `parentId` argument, satisfies the `else` branch. `SessionPackPaymentResource` gates exactly four endpoints with `SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE`: `purchaseSessionPack` (`:61`), `getMySessionPacks` (`:73`), `pauseSessionPack` (`:83`), `getActiveCoachTier` (`:131`). Traced the full read path this AC's own `loadPlayerPacks` call goes through: `bookingStore.loadPlayerPacks(playerId)` → `getMySessionPacks()` API → `SessionPackPaymentResource.getMySessionPacks` (`:73`) → `securityUtil.getCurrentCoachUserId()` (role-agnostic, returns the caller's own id) → `SessionPackPaymentService.getPacksForParent(callerId, coachId)` (`:100-103`) → `sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(callerId)` — no role branch anywhere in this chain. For a self-booking player, `callerId` is their own `userId`, which is exactly what `purchasePack`'s ownership check above already writes into `parent_id` on their own purchase rows. The chain is symmetric and already shipped — not a dependency to flag, a fact to build on.

   **Confirmed no template/script path reads `playerStore.activePlayerId` in a player-caller context, today or after this AC's changes (story-review Critical Issue #2, resolved)**: full read of the template (`:1-230`) confirms `playerStore.activePlayerId` is read in exactly two places — inside `handleCta`'s `authStore.isParent` branch (`:342`, unreachable for a player caller since it's inside an `else if (authStore.isParent)`) and inside `handleBuySessions` (`:286-289`, see below). No `v-if`/binding anywhere in the template reads `playerStore` state directly. The one template element that could have been a risk, `SessionPackTracker` (the credits widget, `:179-185`), is itself gated `v-if="authStore.isParent"` (`:180`) — it is not shown to a player caller at all, before or after this AC, so its `creditsForThisCoach`/`activePackSessionCount` props (both `playerStore`-independent anyway) are moot for this AC. **`handleBuySessions` needs no player branch**: its only caller is `SessionPackTracker`'s `@buy-sessions="handleBuySessions"` emit (`:184`), and that component is only ever rendered for `authStore.isParent` — so `handleBuySessions` is unreachable for a player caller both before and after this AC's changes. This resolves the story-review's "confirm whether `handleBuySessions` needs its own player branch" item conclusively: it does not, confirmed by tracing its one and only call site, not by inference. **Deliberately out of scope**: extending `SessionPackTracker` itself to render for a self-booking player (so they see the same always-visible credits widget a parent does) is a separate, larger UI decision — this AC only needs the CTA button (the actual purchase/booking action) at parity, not the widget. Flagging this explicitly as a scope boundary rather than a silent gap.

   - In `onMounted`'s player branch, after `selfPlayerId.value` resolves successfully, call `bookingStore.loadPlayerPacks(selfPlayerId.value)` the same way the parent branch already calls `bookingStore.loadPlayerPacks(playerStore.activePlayerId)` — remove the now-inaccurate "packs are out of scope" comment, replacing it with a short note that this mirrors the parent branch now that `skillars-deferred-81` AC4 unlocked self-booking pack ownership.
   - `hasCreditsForCoach` needs **no code change** — **confirmed, not deferred (story-review AC2 issue #1, resolved)**: its `some(...)` predicate (`:275-277`) checks only `p.coachId`/`p.status`/`p.creditsRemaining`, all role-agnostic fields already present on every element of `bookingStore.sessionPacks` regardless of which role's `loadPlayerPacks` call populated it (confirmed by reading `booking.store.js`'s `normalizePack`/`loadPlayerPacks`, `:150-165`, `:167-183` — no role check anywhere in this path either). Once `onMounted`'s player branch populates `sessionPacks` per the bullet above, `hasCreditsForCoach` is correct for a player caller automatically.
   - Update `ctaLabel` to give a self-booking player the same three-way label logic a parent gets (`signUpToBook` / `bookSession` / `buySessions`) based on `hasCreditsForCoach`, instead of the current `if (authStore.isPlayer) return t('marketplace.bookSession')` unconditional branch.
   - Update `handleCta`'s player branch to mirror the parent branch's `hasCreditsForCoach.value` check: route to `request-booking` (using `selfPlayerId.value`, not `playerStore.activePlayerId`, consistent with this branch's existing per-session routing) if the player already holds credits with this coach, otherwise route to `purchase-sessions` — remove the "packs are out of scope" comment and unconditional routing.
   - `handleBuySessions` needs **no change** — see the resolved finding above; do not add a player branch to it, it stays unreachable for a player caller by construction (its only caller, `SessionPackTracker`, stays parent-gated).
   - **New, small fix found during this verification pass, not in the original draft**: the CTA button's `:loading` binding (`:194`) is `authStore.isParent && bookingStore.packsLoading` — parent-only. Once `onMounted` starts loading packs for a player caller too, this binding will silently never show the loading spinner for that caller (the `authStore.isParent` half of the `&&` is always false for a player). Widen it to `(authStore.isParent || authStore.isPlayer) && bookingStore.packsLoading`.
   - Test: no frontend test framework exists in this repo (standing, repeatedly-reaffirmed convention — see Architecture/conventions below); verify by `npx eslint` clean plus a manual dev-server pass with these explicit steps: (1) log in as a self-registered player with an existing active pack for Coach A → confirm the CTA reads "Book Session" (not "Buy Sessions") and clicking it routes to `request-booking`; (2) log in as the same player, visit Coach B (no pack) → confirm the CTA reads "Buy Sessions" and clicking it routes to `purchase-sessions`; (3) log in as a self-registered player who has not finished the profile-builder step (the `fetchSelfPlayerId` 404 case already handled by `onMounted`'s existing `catch`) → confirm the page still renders (no crash), the CTA still shows a sensible label, and clicking it does not navigate to a broken `undefined`-id URL; compare each step's outcome to the equivalent parent-managed-player case side by side.

3. **Give a self-booking player a way to reach and manage their own session-pack dashboard after purchasing one.** Confirmed live: the `parent/players/:playerId/packs` route (`routes.js:122-125`, hosting `SessionPackDashboardPage.vue`) is gated `meta: { requiresAuth: true, role: 'PARENT' }` — single-role only, unlike the dual-role `roles: ['PARENT', 'PLAYER']` shape `skillars-deferred-81` AC4 already applied to the sibling `purchase-sessions` route one block above it (`:114-120`) and `skillars-uat-5` AC4 applied to `request-booking`/`parent/bookings`. `SessionPackDashboardPage.vue` itself has **no parent-only assumption to widen** — confirmed by direct read: it resolves `playerId` straight from `route.params.playerId` (`:141`) and calls `bookingStore.loadPlayerPacks(playerId)` (`:253`) with no `playerStore`/ownership lookup of any kind; every displayed action (pause, etc.) posts through backend endpoints that `skillars-deferred-81` AC4 already ownership-checked and role-widened (`pauseSessionPack` → `HAS_PARENT_OR_PLAYER_ROLE`, confirmed at `SessionPackPaymentResource.java:83`). Separately confirmed: `src/frontend/src/layouts/MainLayout.vue`'s player-role nav-drawer block (`:175-204`, `v-if="authStore.isPlayer"`) has exactly three items (Marketplace `:178`, Bookings `:187`, Messaging `:196`) — no session-pack entry point of any kind, and no other page in the codebase links to this route for a player caller either (`grep`-confirmed: the only two live navigations to `purchase-sessions`/`parent/players/:playerId/packs` are both in `CoachPublicProfilePage.vue`/`BookingRequestPage.vue`, both parent-only or per-session paths — no page links a player caller to the *pack dashboard* route today).

   **Confirmed no existing async-nav-item precedent exists in this file (story-review Critical Issue #3 / AC3 issue #1, resolved — the story previously hedged "follow existing precedent" without checking one existed)**: full read of `MainLayout.vue`'s template confirms all three player-nav items, and every other nav item in the file (Dashboard, Profile, coach/parent/admin sections), bind `to="/static/path"` directly with no computed route and no async resolution of any kind — there is no precedent to follow. This is a new pattern for this file, not a reuse of an existing one. The pattern to use: bind the new item's `:to` to a computed property (not a literal `to="..."` string, since it needs the resolved `selfPlayerId`), and guard the item itself with `v-if="selfPlayerId"` — not the whole `authStore.isPlayer` block (Marketplace/Bookings/Messaging must keep rendering immediately regardless of whether `selfPlayerId` has resolved yet):
     ```
     const selfPlayerId = ref(null)
     const packsRoute = computed(() => selfPlayerId.value ? `/parent/players/${selfPlayerId.value}/packs` : null)
     onMounted(async () => {
       // ...existing body...
       if (authStore.isPlayer) {
         try {
           selfPlayerId.value = await playerStore.fetchSelfPlayerId()
         } catch (err) {
           if (err.response?.status !== 404) { /* surface, mirroring CoachPublicProfilePage's own catch */ }
         }
       }
     })
     ```
     then `<q-item v-if="packsRoute" clickable :to="packsRoute" class="nav-item">`. This directly resolves the story-review's "route binding to unresolved value" concern (AC3 issue #3) — the item is absent from the DOM entirely until `packsRoute` is non-null, so there is no `/parent/players/undefined/packs` navigation possible.
   - Add the new nav-drawer item to `MainLayout.vue`'s `v-if="authStore.isPlayer"` block per the pattern above, labeled with the existing `t('booking.packs.dashboardTitle')` key (already defined, "Your session packs" in `en-US`) — do not add a new i18n key, reuse this one since it already describes exactly this destination.
   - Widen `routes.js`'s `parent/players/:playerId/packs` route from `meta: { requiresAuth: true, role: 'PARENT' }` to `meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] }`, mirroring the exact shape the sibling `purchase-sessions` route already uses one block above it. **Add a short inline comment at this route** (per story-review Critical Issue #4, the `parent/` URL prefix is semantically ambiguous for a player caller) noting explicitly that the `parent/` prefix is retained for route/code reuse with the parent-managed-player flow, not because this destination is parent-only — mirroring the comment style the `purchase-sessions` route above it already uses for the identical situation.
   - Resolve `selfPlayerId` in `MainLayout.vue`'s `onMounted`, guarded by `authStore.isPlayer`, via `playerStore.fetchSelfPlayerId()`. **Confirmed safe against the double-fetch this AC and AC2 both trigger (story-review AC3 issue #2, resolved)**: `fetchSelfPlayerId()` (`playerStore.js:26-55`) early-returns the cached value if already resolved (`:27`) and dedups concurrent in-flight calls via the shared `selfPlayerIdRequest` promise (`:28-53`) — a second call from `MainLayout.vue` after `CoachPublicProfilePage.vue` already resolved it (or vice versa, mount order isn't guaranteed to be the same every time) either returns the cached value instantly or joins the same in-flight request; neither path issues a second HTTP call. The `requestGeneration`/`selfPlayerIdGeneration` guard (`:29,36,60`) specifically protects against a slow pre-logout resolve landing after a *different* player has since logged in — confirmed exercised by `MainLayout.vue`'s own `handleLogout` (`:299-305`), which already calls `playerStore.resetSelfPlayerId()` (`:301`) before redirecting, bumping the generation and invalidating any in-flight request from the prior session.
   - Test: no frontend test framework exists; verify by `npx eslint` clean plus a manual dev-server pass as a self-registered player with at least one purchased pack (from AC2's own manual verification pass) confirming the new nav item appears (and does not appear/flash a broken link before `selfPlayerId` resolves), routes correctly, and the dashboard renders/paginates/pauses identically to the parent-managed-player case.

## Tasks / Subtasks

- [x] Task 1: `AvailabilityService.deleteBlock` negative-path unit test (AC: #1)
  - [x] Add `deleteBlock_notFoundOrNotOwned_throwsOperationNotAllowed` to `AvailabilityServiceTest.java`
  - [x] Run `mvn -o test -Dtest=AvailabilityServiceTest`, confirm full class green
- [x] Task 2: `CoachPublicProfilePage.vue` self-booking-player pack-awareness (AC: #2)
  - [x] `onMounted` player branch: call `bookingStore.loadPlayerPacks(selfPlayerId.value)`, remove stale "packs are out of scope" comment
  - [x] `hasCreditsForCoach` — no code change needed (confirmed role-agnostic during story revision, see AC2)
  - [x] Update `ctaLabel` to give a self-booking player the same three-way label logic a parent gets
  - [x] Update `handleCta`'s player branch to route to `purchase-sessions` vs `request-booking` based on existing credits, using `selfPlayerId`
  - [x] `handleBuySessions` — no change needed (confirmed unreachable for a player caller during story revision, see AC2)
  - [x] Widen the CTA button's `:loading` binding to include `authStore.isPlayer` (found during story revision, see AC2)
  - [x] Manual dev-server verification: 3 explicit steps (existing pack / no pack / unfinished profile-builder) + `npx eslint` — eslint clean; no browser-automation tool available in this session, so the manual dev-server pass itself was not performed (flagged, not claimed — see Completion Notes)
- [x] Task 3: Self-booking player pack-dashboard reachability (AC: #3)
  - [x] Widen `routes.js`'s `parent/players/:playerId/packs` route to `roles: ['PARENT', 'PLAYER']`; add clarifying comment on the `parent/` prefix (see AC3)
  - [x] Add nav-drawer item to `MainLayout.vue`'s player block, reusing `booking.packs.dashboardTitle`, using the `packsRoute` computed + `v-if` pattern specified in AC3 (no existing async-nav precedent in this file — confirmed during story revision)
  - [x] Resolve `selfPlayerId` in `MainLayout.vue` via `playerStore.fetchSelfPlayerId()`; bind nav item's route to the `packsRoute` computed so the item is absent until resolved
  - [x] Manual dev-server verification (self-registered player with a purchased pack) + `npx eslint` — eslint clean; no browser-automation tool available in this session, so the manual dev-server pass itself was not performed (flagged, not claimed — see Completion Notes)
- [x] Task 4: Ledger hygiene (no AC — housekeeping found during this story's creation)
  - [x] Close `deferred-work.md` line 804 (`getParentPlayerSchedule` N+1, W2) as false premise — the method no longer performs any coachProfile/credits/in-flight-count per-row lookups (confirmed by direct read of `BookingService.java:623-661`; the coach-name lookup that does remain was already batched by `skillars-deferred-81` AC1)
  - [x] Close `deferred-work.md` line 1580 (`PlaybackService.authorizePlayback` bandwidth-dedup, no locking) as already fixed, unannotated — `PlaybackService.java:127-133` already takes `videoRepository.findByIdForUpdate` under `lockRetryer.withBoundedRetry`, per its own inline comment: "Deferred-64 AC3: serializes the exists-check + conditional charge below... closing the check-then-act race skillars-deferred-63 AC3 explicitly deferred"
  - [x] Close `deferred-work.md` line 830 (no DB-level state-machine constraints on session packs) as false premise — confirmed by full read of `payment/repo/SessionPackPurchase.java`: no `status` field/column is declared anywhere in the entity (a negative claim, so no single line to cite — the absence spans the whole file); status is computed at read time, not stored, so there is no DB state machine to constrain
  - [x] Close `deferred-work.md` line 1079 (D20, `CashOutServiceTest` field mismatch) as false premise — `CashOutService.processCashOut` (`:24`) reads `.map(sc -> sc.getLastPaymentIntentId())` (`:31`), and `CashOutServiceTest.java:54`'s happy-path stub already sets exactly that field; no mismatch exists
  - [x] Close `deferred-work.md` line 1124 (D6, `pausePack` locks but `deductSession`/`restoreSession`/`extendPack` don't) as already fixed, unannotated — all four now call `findByIdForUpdate` under `lockRetryer.withBoundedRetry` (`PackSessionService.java:56,76,128`, `SessionPackPaymentService.java:122`)
  - [x] Record the 2026-08-29 project-owner decision on messaging orphaned-profile behavior (`deferred-work.md` line 1148, D3 under `## Deferred from: code review of skillars-deferred-16-...`) as **DECIDED: leave as-is** — no live code path can produce this state today
  - [x] Record the 2026-08-29 project-owner decision on the `drill_video_refs.video_id` FK (`deferred-work.md` line 1675) as **DECIDED: keep deferring** — `skillars-deferred-81` AC3's in-process lock already covers the real race; no reachable bug remains

## Dev Notes

### Source ledger mapping

- AC1 ← found during this story's own re-mining of Booking/Availability/Reschedule (not a pre-existing ledger bullet — the module's actual open items were exhausted; this was surfaced by directly comparing `deleteBlock`'s test coverage against its own sibling delete-type methods' coverage).
- AC2, AC3 ← not from the ledger at all. Per the project owner's explicit choice (2026-08-29, see "Story creation context" above) to add an unrelated task after all four priority modules came up dry — sourced from `skillars-deferred-81`'s own Dev Notes flag ("no in-app link to this route currently exists for either role"), investigated further during this story's creation and found to be a 3-part gap (CTA logic, pack-loading, dashboard reachability), not just a missing link.
- Task 4 ledger-hygiene items ← found stale/already-resolved, or newly decided, during this story's own re-mining across all four priority modules (per standing instruction to re-verify every candidate against current source rather than trust ledger text) — closed/decided here rather than picked up, since no further code change is needed for any of the seven.

### Why this story combines two unrelated pieces of work

Per standing instruction, this story's creation re-mined Booking/Availability/Reschedule first, then escalated through Video/Playback/Moderation, Messaging/Admin/Reviews/Disputes, and Payments/Stripe/Credit wallet in that order — the same sequence `skillars-deferred-81` established. All four came up genuinely dry (see "Story creation context" above for the full breakdown), yielding only one small, real, mechanical item (AC1) and two decisions (both resolved as "leave as-is"/"keep deferring," neither producing a new AC). This project's own "do not create small stories" convention, and its documented fallback for a dry-ledger cycle (see `[[project_skillars_release_workflow]]`: "relax the bundling bar to allow a single larger-but-still-mechanical item as its own story" or "have the user resolve one of the decision-needing items so it becomes actionable" or "add unrelated tasks"), meant AC1 alone did not justify a story on its own. The project owner chose to add an unrelated task rather than ship a single-AC story or skip the cycle — AC2/AC3 are that task, a genuine, previously-flagged, real frontend gap (not invented for the sake of bulking out the story) that follows directly from `skillars-deferred-81`'s own shipped work.

### Architecture / conventions to follow

- **No frontend test framework**: this project has a standing, repeatedly-reaffirmed convention of no `*.spec.js`/frontend test infrastructure (see `docs/dev-docs`, every prior story's own Dev Notes). AC2/AC3 verify by code reading, `npx eslint`, and a manual dev-server pass; do not introduce a framework as part of this story.
- **Self-booking-player id resolution**: `playerStore.fetchSelfPlayerId()` (`playerStore.js:26-`) is this codebase's one pattern for resolving a self-registered player's own player-profile id — already cached, dedup-safe, and generation-guarded against a stale resolve racing a logout. `CoachPublicProfilePage.vue` is the existing reference implementation for how to call it and handle a 404 (verified-but-profile-not-finished) silently. AC2/AC3 both reuse this exact pattern rather than inventing a new one.
- **Dual-role routing**: `meta: { roles: ['PARENT', 'PLAYER'] }` is this codebase's one dual-role route-gating shape, established by `skillars-uat-5` AC4 (`request-booking`, `parent/bookings`) and reused by `skillars-deferred-81` AC4 (`purchase-sessions`). AC3 applies it a third time to `parent/players/:playerId/packs`, no new pattern introduced.
- **Opaque-payer-id pattern**: `parentId` on `SessionPackPurchase`/`Booking` continues to mean "the paying/owning user's id, parent or self-booking player" throughout the payment and booking modules (established by `skillars-uat-5`, reused by `skillars-deferred-81` AC4). Not touched by this story, but relevant background for AC2/AC3's frontend work, which surfaces existing backend capability rather than adding any.
- **Testing**: per `docs/validation-strategy.md`, do not run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<ClassName>` for touched classes.
- **Implementation order**: AC1 is fully independent (backend test-only). AC2 and AC3 are also independent of each other in principle (different files, different concerns — CTA logic vs. dashboard reachability), but AC3's manual verification step reuses "a self-registered player with a purchased pack," which in practice means running AC2's own manual pass first is the natural order to get a real pack into that account. Not a hard code dependency, just the practical order for manual testing.

### Items investigated per `story-review.md` (2026-08-29) — resolved, not open dev-actions

The senior-dev-review found no blockers ("Ready for dev with corrections") but flagged four Critical
Issues and several AC-specific verification gaps as deferred to implementation time. Every one was
resolved by direct code investigation during this revision pass and woven inline into AC1-AC3 above
(search for "story-review" / "Critical Issue" / "resolved" — each carries its own citation); none
required an AC redesign. Two small, real additional fixes were found during this verification and
folded into AC2/AC3 rather than left for the dev agent to discover: the CTA `:loading` binding
excludes players, and the `parent/` route-prefix ambiguity needed a one-line clarifying comment, not
a route rename. This section is a scan-friendly index of which review item maps to which resolution:

- **Critical Issue #1 — backend pack-ownership change unverified (HIGH)** → resolved inline in AC2: `SessionPackPaymentService.purchasePack:55-71`'s XOR check and the 4 widened `SessionPackPaymentResource` endpoints (`:61,73,83,131`) cited directly, plus the full `loadPlayerPacks` → `getMySessionPacks` → `getPacksForParent` → `findByParentIdOrderByCreatedAtDesc` call chain traced end-to-end to confirm no role branching exists anywhere in it.
- **Critical Issue #2 — template break risk from `playerStore.activePlayerId` (HIGH)** → resolved inline in AC2: full template read confirms `playerStore.activePlayerId` is read in exactly two places, both unreachable for a player caller (`handleCta`'s parent-only branch; `handleBuySessions`, whose only caller `SessionPackTracker` is itself `v-if="authStore.isParent"`-gated). No template `v-if` depends on `playerStore` state. `SessionPackTracker` staying parent-only is called out as a deliberate scope boundary, not a silently-missed gap.
- **Critical Issue #3 — `MainLayout.vue` async-resolution precedent unverified (MEDIUM)** → resolved inline in AC3: confirmed no precedent exists (all existing nav items are static `to="..."` routes) and specified the exact pattern to use instead (a `packsRoute` computed + `v-if="packsRoute"` on the new item only, not the whole player block).
- **Critical Issue #4 — `/parent/players/:playerId/packs` route semantics for a player caller (LOW-MEDIUM)** → resolved inline in AC3: added a one-line comment requirement at the route definition, no route rename (code-reuse justification stated explicitly rather than left implicit).
- **AC2 issue #1 — `loadPlayerPacks` role-dependency unverified** → resolved inline in AC2, see Critical Issue #1's call-chain trace above.
- **AC2 issue #2 — `hasCreditsForCoach` role-dependency unverified** → resolved inline in AC2: confirmed its predicate checks only role-agnostic pack fields; no code change needed for this computed itself.
- **AC2 issue #3 — `handleBuySessions` scope unclear** → resolved inline in AC2, see Critical Issue #2 above: no player branch needed, confirmed by tracing its one call site.
- **AC2 issue #4 — vague manual-test criteria** → resolved inline in AC2: replaced with 3 explicit numbered steps (existing pack / no pack / unfinished profile-builder case), each compared against the parent-managed-player equivalent.
- **AC3 issue #1 — "existing precedent" unverified** → resolved inline in AC3, see Critical Issue #3 above.
- **AC3 issue #2 — double-fetch/generation-guard risk** → resolved inline in AC3: `fetchSelfPlayerId`'s cache/dedup/generation-guard behavior confirmed by direct read of `playerStore.js:26-55`, cross-checked against `MainLayout.vue`'s own `handleLogout:299-305` call to `resetSelfPlayerId`.
- **AC3 issue #3 — route binding to unresolved value** → resolved inline in AC3, see Critical Issue #3's code pattern above: the nav item is absent from the DOM until `packsRoute` resolves, no `/undefined/` navigation is possible.
- **Task 4 — ledger-hygiene claims lacked line citations** → resolved: every one of the 7 Task 4 items now carries file:line citations both in this story's own Tasks/Subtasks section and in the `deferred-work.md` annotations themselves (already applied during story creation — verified present and accurate during this revision pass, not re-done).

### Project Structure Notes

Touches three areas: `booking/service/AvailabilityServiceTest.java` (AC1, test-only, no production code change); `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` (AC2); `src/frontend/src/router/routes.js` + `src/frontend/src/layouts/MainLayout.vue` (AC3). No backend production code changes at all in this story — AC1 is test-only, AC2/AC3 are frontend-only, surfacing backend capability `skillars-deferred-81` already shipped. No new database migrations. No new i18n keys — AC3 reuses the existing `booking.packs.dashboardTitle` key.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source ledger (see per-AC/per-item citations above).
- [Source: skillars-deferred-81 story file] — AC4's own Dev Notes flagged the frontend-reachability gap AC2/AC3 close; AC1's batch-helper precedent and the opaque-payer-id pattern are unchanged background for this story.
- [Source: skillars-deferred-80 AC1] — added the one existing `deleteBlock` unit test AC1 extends.
- [Source: skillars-uat-5 AC4] — established the `roles: ['PARENT', 'PLAYER']` dual-role route-gating shape AC3 reuses a third time.
- [[project_skillars_release_workflow]] — this story's own creation followed that memory's documented fallback for a dry-ledger cycle.
- `_bmad-output/implementation-artifacts/story-review.md` — senior-dev-review this revision pass responded to; see "Items investigated per story-review.md" above for the full resolution index.

## Dev Agent Record

### Completion Notes

All 3 ACs implemented exactly as specified, plus Task 4 ledger-hygiene verification.

- **AC1**: Added `deleteBlock_notFoundOrNotOwned_throwsOperationNotAllowed` to `AvailabilityServiceTest.java`, directly beneath the existing happy-path test, using the exact `Optional.empty()` stub shape the story specified. Test-only change, no production code touched. `mvn -o test -Dtest=AvailabilityServiceTest`: 33/33 green (32 pre-existing + 1 new).
- **AC2**: `CoachPublicProfilePage.vue`'s player branch in `onMounted` now calls `bookingStore.loadPlayerPacks(selfPlayerId.value)` after resolving the player's own id, mirroring the parent branch (stale "packs are out of scope" comment removed and replaced with a note pointing at Deferred-81 AC4). `ctaLabel` now gives players the same three-way label logic as parents (`hasCreditsForCoach` needed no change, confirmed role-agnostic per the story's own investigation). `handleCta`'s player branch now branches on `hasCreditsForCoach.value` exactly like the parent branch, routing to `request-booking` (using `selfPlayerId`, not `playerStore.activePlayerId`) or `purchase-sessions`. `handleBuySessions` left untouched (confirmed unreachable for a player caller — its only caller, `SessionPackTracker`, stays parent-gated). Widened the CTA button's `:loading` binding to `(authStore.isParent || authStore.isPlayer) && bookingStore.packsLoading` so a player caller sees the spinner too.
- **AC3**: `routes.js`'s `parent/players/:playerId/packs` route widened from `role: 'PARENT'` to `roles: ['PARENT', 'PLAYER']`, with an inline comment explaining the retained `parent/` prefix is for route/code reuse, not parent-exclusivity — mirroring the sibling `purchase-sessions` route's own comment. `MainLayout.vue` gained a `selfPlayerId` ref + `packsRoute` computed (the async-nav pattern the story specified, since no precedent existed in this file), resolved in an now-`async onMounted` guarded by `authStore.isPlayer`, calling `playerStore.fetchSelfPlayerId()` with the same silent-404 / surfaced-other-error handling shape as `CoachPublicProfilePage.vue`. New nav-drawer item added to the player block, `v-if="packsRoute"` (absent from the DOM until resolved, so no `/undefined/` navigation is possible), reusing the existing `booking.packs.dashboardTitle` i18n key — no new key added.
- **Task 4**: Verified all 7 ledger-hygiene items (`deferred-work.md` lines 804, 1580, 830, 1079, 1124, 1148, 1675) already carry their `[CLOSED ...]`/`[DECIDED ...]` annotations from this story's own creation pass — confirmed present and accurate, no further edit needed to that file this session.
- **Manual dev-server verification (AC2/AC3)**: `npx eslint` is clean on all three touched frontend files (`CoachPublicProfilePage.vue`, `routes.js`, `MainLayout.vue`). The actual interactive dev-server pass (logging in as a self-registered player, exercising the CTA/nav flows end to end) was **not performed** — no browser-automation tool was available in this session, matching the same explicitly-flagged gap in `skillars-deferred-78`'s own Completion Notes for its AC4. Flagged here rather than claimed.
- Per `docs/validation-strategy.md`, no full `mvn verify` was run locally; only the targeted `AvailabilityServiceTest` class ran. GitHub CI is the full-verification gate.

### File List

- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java` (modified — AC1)
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` (modified — AC2)
- `src/frontend/src/router/routes.js` (modified — AC3)
- `src/frontend/src/layouts/MainLayout.vue` (modified — AC3)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — status tracking)

## Change Log

- 2026-08-29: Story created from `deferred-work.md` mining. All four priority modules (Booking/Availability/Reschedule, Video/Playback/Moderation, Messaging/Admin/Reviews/Disputes, Payments/Stripe/Credit wallet) came up genuinely dry — two parallel research passes verified every untagged ledger bullet against live code and found zero real actionable bugs, only stale/already-fixed items and two decisions. Both decisions resolved directly with the project owner (2026-08-29): messaging orphaned-profile inconsistency — leave as-is (unreachable today); `drill_video_refs` FK re-add — keep deferring (no reachable bug it closes). One small real item survived (AC1, `deleteBlock` test-coverage gap). Per this project's documented dry-ledger fallback, the project owner chose to add an unrelated task rather than ship a single-AC story or skip the cycle: `skillars-deferred-81` AC4's own Dev Notes had flagged "no in-app link exists" for the self-booking pack-purchase route it unlocked — investigated further and found to be a 3-part gap (CTA logic still assumes packs are parent-only; no pack-loading for a player caller; no way at all to reach the pack-management dashboard afterward), becoming AC2/AC3. Plus Task 4 ledger hygiene closing/deciding 7 items found during re-mining. No code implemented yet — status: ready-for-dev.
- 2026-08-29: Revised after senior-dev-review (`story-review.md`) — verdict "ready for dev with corrections," 4 Critical Issues plus several AC-specific verification gaps flagged, all deferred-to-implementation-time hedges rather than blockers. Every item resolved by direct code investigation and written inline into AC1-AC3 with citations (see "Items investigated per story-review.md" in Dev Notes for the full index): Critical Issue #1 (backend pack-ownership change unverified) resolved by citing `SessionPackPaymentService.purchasePack:55-71`'s XOR check, the 4 widened `SessionPackPaymentResource` endpoints, and tracing the full `loadPlayerPacks` call chain end-to-end; Critical Issue #2 (template-break risk) resolved by full template read confirming `playerStore.activePlayerId` is unreachable for a player caller both before and after this story's changes; Critical Issue #3 (no verified async-nav precedent in `MainLayout.vue`) resolved — confirmed no precedent exists and specified the exact new pattern (`packsRoute` computed + `v-if` guard); Critical Issue #4 (route semantics) resolved with a one-line comment requirement, no route rename. Two small real fixes found during this verification and folded in: AC2's CTA `:loading` binding excluded players (now includes them); AC3's route gets an explicit code-reuse comment. No AC was redesigned — every flagged assumption checked out true or was corrected in place with evidence. Status remains ready-for-dev.
- 2026-08-29: Dev-story implementation complete. All 3 ACs shipped exactly as specified. AC1 added the `deleteBlock` negative-path unit test (33/33 `AvailabilityServiceTest` green, test-only, no production code change). AC2 gave `CoachPublicProfilePage.vue`'s self-booking-player branch pack-loading, three-way CTA label parity with parents, and credit-aware CTA routing, plus the `:loading`-binding fix found during story revision. AC3 widened `parent/players/:playerId/packs` to a dual-role route and added a nav-drawer entry in `MainLayout.vue` bound to a new `packsRoute` computed (absent from the DOM until `selfPlayerId` resolves — no `/undefined/` navigation possible), reusing the existing `booking.packs.dashboardTitle` i18n key. Task 4's 7 ledger-hygiene items were confirmed already annotated in `deferred-work.md` from story creation — no further edit needed there this session. `npx eslint` clean on all three touched frontend files; the interactive dev-server pass itself was not performed (no browser-automation tool available in this session, explicitly flagged rather than claimed, matching `skillars-deferred-78`'s own precedent for the same gap). No full `mvn verify` run locally per `docs/validation-strategy.md`. Status: ready-for-dev -> review.
- 2026-08-29: Code review (parallel adversarial layers: Acceptance Auditor, Blind Hunter, Edge Case Hunter). 5 patch findings + 1 pre-dismissed test-pattern finding, no blockers, no violations of the 3 ACs.
- 2026-08-29: Code review findings triaged. 1 applied (CoachPublicProfilePage.vue's player-branch `onMounted` no longer conflates `fetchSelfPlayerId` and `loadPlayerPacks` error handling under one catch — moved `loadPlayerPacks` outside the catch, guarded by `if (selfPlayerId.value)` since the review's own suggested fix omitted that guard and would have called `loadPlayerPacks(null)` on the expected profile-builder-unfinished 404 path). 4 dismissed as false positives, each re-verified against live code: MainLayout async-unmount race (Vue 3 has no such warning; MainLayout wraps `/login` itself so it never unmounts mid-session; the only route outside it is the 404 catch-all, a zero-consequence edge case); route meta `role`/`roles` "inconsistency" (router/index.js already normalizes both by documented design, the codebase's one established dual-gate pattern, reused correctly a third time); CTA loading-state-stuck risk (`booking.store.js`'s `loadPlayerPacks` already clears `packsLoading` in a `finally` block); and the test-verification-pattern finding the review itself had already dismissed. `npx eslint` re-run clean. See "Triage Resolution" under Code Review Findings for full per-item reasoning. 0 decision-needed, 0 open patch findings remaining. Status: review -> done.

## Code Review Findings (2026-08-29)

**Acceptance Criteria**: ✅ All 3 ACs fully implemented, no violations.

**Reviewed layers**: Acceptance Auditor (spec + context), Blind Hunter (diff only), Edge Case Hunter (project access).

**Summary**: 5 fixable issues (patches), 1 dismissed false positive. No blockers. All fixes are unambiguous; no human decisions needed.

### Findings

**1. Async Unmount Race in MainLayout.vue** [PATCH - MEDIUM]  
**File**: `src/frontend/src/layouts/MainLayout.vue` (onMounted hook, now async)  
**Issue**: `onMounted` is now `async` and awaits `playerStore.fetchSelfPlayerId()` without an abort/cleanup mechanism. If the component unmounts before the promise settles, `selfPlayerId.value = ...` executes after unmount, triggering Vue's memory-leak warning.

**Evidence**: Lines 333–342 show async/await without guard check before state update.

**Fix**: Add unmount check or AbortController:
- Option A (simple): Wrap state assignments with `if (!aborted.value)` after adding `const aborted = ref(false)` and `onBeforeUnmount(() => { aborted.value = true })`.
- Option B (modern): Use AbortController with `new AbortController()` passed to the fetch call if the store method supports it.

**Impact**: Prevents memory-leak warnings and phantom state updates on rapid navigation away.

---

**2. Error Handling Conflates Two Async Operations** [PATCH - MEDIUM]  
**File**: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` (lines 304–318)  
**Issue**: The catch block wraps both `fetchSelfPlayerId()` and `loadPlayerPacks()`, but the error context (named `profileErr`, comment: "profile-builder step") applies only to `fetchSelfPlayerId`. A 404 from `loadPlayerPacks` (e.g., bad coachId) is silently caught and treated as profile-not-found.

**Evidence**: Lines 304–318 show `try { await fetchSelfPlayerId(); loadPlayerPacks(...) } catch (profileErr)` — the error name and comment are specific to profile fetch, but the catch fires for both.

**Fix**: Separate the error handling:
```javascript
try {
  selfPlayerId.value = await playerStore.fetchSelfPlayerId()
} catch (profileErr) {
  if (profileErr.response?.status !== 404) { /* surface error */ }
}
// loadPlayerPacks outside the try/catch, matching parent branch behavior
bookingStore.loadPlayerPacks(selfPlayerId.value)
```

**Impact**: Distinguishes between profile-not-found (silent) and packs-load errors (now surfaced if needed). Matches the parent branch's own error-handling pattern for consistency.

---

**3. Player Branch Error Handling Differs From Parent Branch** [PATCH - LOW]  
**File**: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` (lines 296–318)  
**Issue**: Parent branch (line 296) calls `loadPlayerPacks()` unwrapped; player branch (line 304) wraps both `fetchSelfPlayerId` and `loadPlayerPacks` in a single try/catch. This asymmetry creates inconsistent error semantics.

**Evidence**:
- Parent: `bookingStore.loadPlayerPacks(playerStore.activePlayerId)` — no error wrapper
- Player: Both calls inside try/catch

**Fix**: Move `loadPlayerPacks` outside the try/catch (see Fix #2 above). This resolves both findings #2 and #3 in one change.

**Impact**: Consistent error-handling strategy across both branches.

---

**4. Route Meta Role Field Inconsistency** [PATCH - LOW]  
**File**: `src/frontend/src/router/routes.js` (lines 120–128)  
**Issue**: The file mixes `role: 'PARENT'` (singular) and `roles: ['PARENT', 'PLAYER']` (array). While the router may handle both patterns, this inconsistency is confusing. Need to verify `router/index.js` properly normalizes both patterns.

**Evidence**: Line 125 changes `meta: { role: 'PARENT' }` to `meta: { roles: ['PARENT', 'PLAYER'] }`, but earlier routes (e.g., `parent/players` CRUD) use the singular form.

**Fix**:
- Audit `router/index.js` to confirm it normalizes both `meta.role` (singular) and `meta.roles` (array).
- If it only handles one, standardize all routes to use the same field (recommend `roles: []` as canonical, since it's more flexible).

**Impact**: Clearer, more maintainable route definitions.

---

**5. CTA Loading State May Persist on Packs Load Error** [PATCH - LOW]  
**File**: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` (line 194)  
**Issue**: The CTA button `:loading` binding is `(authStore.isParent || authStore.isPlayer) && bookingStore.packsLoading`. If `loadPlayerPacks()` fails (network error, etc.), `packsLoading` may not clear, leaving the button stuck in the loading state.

**Evidence**: Line 194 now includes `authStore.isPlayer`, but error handling for `loadPlayerPacks` (if separated per Fix #2) must clear `packsLoading` on error.

**Fix**: Ensure `bookingStore.loadPlayerPacks()` clears the `packsLoading` flag in its catch/finally block. If the store doesn't already do this, add it:
```javascript
try {
  await bookingStore.loadPlayerPacks(playerId)
} finally {
  bookingStore.packsLoading = false  // ← ensure cleared on error
}
```

**Impact**: Prevents UI getting stuck in a loading state.

---

**6. AvailabilityServiceTest Verification Pattern** [✅ DISMISSED]  
**File**: `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java` (line 1028)  
**Issue flagged**: `verify(blockRepository, never()).delete(any())` pattern.

**Status**: **Correct — no action needed.** This is standard Mockito usage for asserting a method was never called. The pattern is idiomatic and correct.

---

### Triage Summary

- ✅ **Acceptance Criteria**: All 3 ACs fully implemented (no violations per Auditor).
- ⚠️ **5 Patches**: All fixable unambiguously; no human decisions required.
- ✅ **1 Dismissed**: False positive in test (pattern is correct).
- **No blockers**: All issues are pre-merge fixable.

**Recommended priority**: Fix async unmount race (#1) and error-handling conflation (#2) before merge. Findings #3–5 are lower severity but should also be addressed.

---

**Review conducted by**: Parallel adversarial review layers (Acceptance Auditor, Blind Hunter, Edge Case Hunter) on 2026-08-29.

### Triage Resolution (2026-08-29, dev agent)

Each finding re-verified against live code before acting. 1 applied, 4 dismissed as false positives (3 of the 5 "patches" plus the pre-existing dismissal) — none required a human decision.

- **#1 Async Unmount Race in MainLayout.vue — DISMISSED (false positive).** The claimed mechanism ("triggering Vue's memory-leak warning") doesn't apply to Vue 3 — unlike React, Vue emits no such warning for a `ref` assignment after unmount; the component's render effect is simply stopped, so the assignment is a harmless no-op. More importantly, the premise that `MainLayout` can unmount mid-session doesn't hold for this codebase: `routes.js:1-5` wraps `/login` itself as a child of the same root `MainLayout` route (confirmed by direct read) — every authenticated-app route and every guest-auth route share one `MainLayout` instance, so an SPA navigation never unmounts it. The only route outside `MainLayout` is the catch-all 404 (`routes.js` end) — an edge case (navigating to a genuinely unmatched path within the ~1 async round-trip the fetch takes) with zero functional consequence even if it fires. No existing codebase precedent guards this shape either (`CoachPublicProfilePage.vue`'s own `onMounted` has always fetched async without an abort/unmount guard). Not fixed — the fix would add unestablished complexity (`AbortController`/`onBeforeUnmount`) for a documented-but-inapplicable risk.
- **#2 / #3 Error Handling Conflation in CoachPublicProfilePage.vue — APPLIED (combined into one fix, as the review itself suggested).** Real finding: the player branch's `catch (profileErr)` wrapped both `fetchSelfPlayerId()` and `loadPlayerPacks()`, so a `loadPlayerPacks` failure would be silently absorbed under profile-not-found handling instead of its own semantics, and asymmetrically from the parent branch (which calls `loadPlayerPacks` unwrapped). Moved `loadPlayerPacks` out of the `fetchSelfPlayerId` try/catch. **Deviated from the review's own suggested snippet**: it called `bookingStore.loadPlayerPacks(selfPlayerId.value)` unconditionally after the catch, which would fire with `selfPlayerId.value` still `null` on the expected 404 (profile-builder-unfinished) path — a real bug the review's fix would have introduced. Guarded with `if (selfPlayerId.value)`, mirroring the parent branch's own `if (playerStore.activePlayerId)` guard exactly.
- **#4 Route Meta Role Field Inconsistency — DISMISSED (false positive).** `router/index.js:56-61` already normalizes both `meta.role` (singular) and `meta.roles` (array) by design — a pre-existing, documented pattern ("Dual-role routes (UAT.5 AC4): meta.roles is additive to the single-value meta.role gates above, scoped to the handful of routes that need it, rather than migrating every existing route... in the same diff", `index.js:58-60`) already exercised by the sibling `purchase-sessions` route one block above this story's own route change. Not an inconsistency to resolve — it's the codebase's one established additive-gating convention, applied correctly a third time.
- **#5 CTA Loading State May Persist on Packs Load Error — DISMISSED (false positive).** `booking.store.js:276-294`'s `loadPlayerPacks` already wraps its body in `try { ... } finally { packsLoading.value = false }` — `packsLoading` cannot get stuck regardless of success/failure. The finding's own fix snippet proposes re-adding logic that already exists one layer down.
- **#6 AvailabilityServiceTest Verification Pattern — already dismissed by the review itself**, confirmed correct, no action.

`npx eslint` re-run clean on `CoachPublicProfilePage.vue` after the fix. 0 decision-needed, 0 open patch findings remaining — status review -> done.
