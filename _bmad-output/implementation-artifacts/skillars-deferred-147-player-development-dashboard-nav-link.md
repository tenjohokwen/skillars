# Story Deferred-147: Player Development Dashboard — Missing Nav Link

Status: done

> **MANUAL TEST FINDING 2026-10-07 (round 1):** after deploying the AC1 fix to the local container, the owner logged in as a player through the real UI and saw neither Session Packs nor the new Development Dashboard link. Investigated rather than assumed: the backend returned 200 with a real `player_profiles` row (confirmed via direct Postgres query), so `selfPlayerId` should have resolved — the bug was not in the new code's condition logic. Root cause: `routes.js` wraps **every** route, including `/login`, in one `MainLayout` instance, which Vue Router mounts exactly once per SPA session; `LoginPage.vue`'s post-login navigation is a client-side `router.push`, not a reload, so `MainLayout` never remounts. The original `onMounted(() => { if (authStore.isPlayer) { ...fetchSelfPlayerId... } })` check therefore only ever ran once, at initial (pre-login, unauthenticated) mount — permanently skipping the fetch for every real login. This is a **pre-existing bug**, not introduced by this story: it silently broke the Session Packs link identically and had no test coverage (Session Packs' own `packsRoute` had zero prior spec cases). Fixed by replacing the one-shot check with `watch(() => authStore.isPlayer, ..., { immediate: true })`, which both covers the already-authenticated-on-load case (`immediate: true`) and re-fires the moment `authStore.login()` flips `role` to `PLAYER` post-login. Added a red/green-verified regression test that mounts unauthenticated, flips the role after mount (no remount), and asserts the link appears — this failed as expected against the unpatched `onMounted`-only code (`expected false to be true`) and passes against the fix. AC1/AC4 below updated to require the link survive a real post-mount login, not just a fresh authenticated mount.

> **MANUAL TEST FINDING 2026-10-07 (round 3):** with the ownership-guard fix deployed, the owner reported the *same* 403 shape on both `GET .../timeline` and `GET .../exposure` — on the page itself, both requesting a playerId of `893573203704173600`. The real `player_profiles.id` (re-confirmed via Postgres, unchanged) is `893573203704173564` — a **different number**, not a stale row. Reproduced directly: `node -e "console.log(893573203704173564)"` prints `893573203704173600`, exactly the corrupted id in both requests. Root cause: `PlayerDevelopmentDashboardPage.vue` read the route param via `const playerId = computed(() => Number(route.params.playerId))` (plus an identical `Number(newPlayerId)` in its player-switch watcher). Backend ids are Tsids (18-19 digits), past `Number.MAX_SAFE_INTEGER` — the backend deliberately quotes `Long` as a JSON string specifically so JS never has to represent one as a number (`CommonConfig.longToStringModule`, confirmed directly by probing the real `Jackson2ObjectMapperBuilder` bean: `{"id":"893573203704173564",...}`, correctly quoted), and this page threw that protection away immediately on read. **Every** API call this page makes (`fetchExposure`, `fetchSkillDefinitions`→n/a, `fetchTargets`, `fetchRadarEntries`, `fetchRadarDisplay`, `fetchRadarPreferences`, `fetchCorrelationInsights`, `fetchNarrative`, `saveTargets`, plus the `playerId` prop handed to `PerformanceReportsPanel`/`PlayerTimelinePanel`/`SkillsRadarAssessmentPanel`) was therefore targeting a player that does not exist — a 403 from the (now-correct) ownership guard is the expected, honest response to a wrong id, not a sign the round-2 fix was incomplete. Fixed by keeping `playerId` as the raw route-param string throughout (both the computed and the watcher), and widened `SkillsRadarAssessmentPanel.vue`'s `playerId` prop type from `Number` to `[Number, String]` to match its three sibling components, which already declared the dual type. Added `PlayerDevelopmentDashboardPageSpec.js` (2 cases, red/green-verified: both failed against the unpatched page with the exact corrupted id, `893573203704173600`, as the actual received value — not a hypothetical). **The identical `Number(route.params.playerId)` pattern exists in three sibling pages** (`ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, `PlayerSubscriptionPage.vue`) — found via the same grep sweep, not yet manually reproduced via the UI since they're a different persona/page than what this story's manual test exercised; flagged to the user as a likely-live, separate bug rather than silently fixed here (out of this story's tested surface) — recommend a small dedicated follow-up story applying the identical one-line fix to all three.

> **MANUAL TEST FINDING 2026-10-07 (round 2):** with the nav link now reachable, opening the Development Dashboard page itself produced a 403 on `GET /api/development/players/{id}/timeline` (`security.unauthorized`), despite a real `player_profiles` row existing for that exact id (re-confirmed via direct Postgres query — not a data problem). Traced the `@PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")` chain into `PlayerOwnershipGuard.check`: it unconditionally parsed the caller's `businessId` as a **parentId** and called `existsByIdAndParentId(playerId, parentId)` only — it never checked self-ownership (`player_profiles.user_id`). A self-registered adult player's profile has `parent_id = NULL` (`chk_pp_owner`), so this guard can **never** return true for that persona, on **any** of the 7 resources that share it (`PlayerTimelineResource`, `PerformanceReportResource`, `RadarDisplayResource`, `NeglectedSkillResource`, `SkillExposureResource`, `SubscriptionResource`, `HomeworkResource`) — a pre-existing, systemic backend bug, not introduced by this story, that simply had no prior self-registered-player test coverage anywhere in the codebase to catch it. Fixed by adding `PlayerProfileRepository.existsByIdAndUserId(id, userId)` and OR-ing it into the guard: `existsByIdAndParentId(playerId, callerId) || existsByIdAndUserId(playerId, callerId)` — `businessId` is the caller's own userId regardless of role, so the same id is legitimately checked both ways. Added a focused unit test (`PlayerOwnershipGuardTest`, 6 cases, red/green-verified — the self-owned case fails to even *compile* against the unpatched repository interface, which is as strong a red as an assertion failure) plus a full-stack IT regression (`PlayerTimelineResourceIT.getTimeline_asSelfRegisteredPlayer_returns200WithFullTimeline`, 11/11 green on the full class). This fix's blast radius is wider than this story's own page — it is the backend precondition for every development/session/payment endpoint a self-registered player reaches through their own UI, not just the timeline.

## Story

As a self-registered player,
I want a "Player Development" link in my nav drawer,
so that I can reach the development dashboard that is already fully built and routed for me, without knowing the URL by hand.

## Why This Story Exists

Sourced directly from a user-pasted issue report on 2026-10-07 (not `deferred-work.md`, not a code-review deferral). The report claimed:

- Route `/player//development/:playerId` configured in `routes.js`
- Page `PlayerDeevelopmentDashboardPage.vue` fully implemented with all components (Skills Radar, Session DNA Chart, Development Correlation Panel, etc.)
- No nav link in the player menu (`MainLayout.vue`), whose 5 links were listed as Dashboard / Marketplace / Bookings / Messaging / Session Packs
- Session Packs alone uses a dynamic `selfPlayerId` computed property fetched on mount

Per instruction, none of this was taken at face value — each claim was independently re-verified against the real source before any code was touched:

- **Route**: confirmed at `src/frontend/src/router/routes.js:250-254` — `path: 'player/development/:playerId'`, `name: 'player-development'`, component `pages/player/PlayerDevelopmentDashboardPage.vue` (the report's filename had a typo, "Deevelopment"; the real file and route are both spelled correctly, and the report's doubled-slash `/player//development/:playerId` does not match the real route string — a transcription artifact, not a real path).
- **Page**: `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` exists and wires in the Skills Radar, Session DNA/exposure charts, Development Correlation Panel, assessment history, performance reports, etc.
- **Missing link**: `src/frontend/src/layouts/MainLayout.vue`'s player (`authStore.isPlayer`) nav section had exactly the 5 links the report named, in that order. `packsRoute` was indeed the only one of the five built from a `selfPlayerId` ref resolved in `onMounted` via `playerStore.fetchSelfPlayerId()`.

Confirmed accurate; implemented the obvious, narrow fix rather than re-deriving a different design.

## Acceptance Criteria

1. **Given** a self-registered PLAYER whose `selfPlayerId` has resolved
   **When** they open the nav drawer
   **Then** a "Player Development" link appears, after Session Packs, pointing at `/player/development/{selfPlayerId}`
   **And** this holds for a real post-login session too — `role` flipping to `PLAYER` after `MainLayout` is already mounted (the real path, since `MainLayout` mounts once for the whole SPA session and never remounts on a client-side login navigation) must trigger the fetch, not just an already-authenticated fresh page load

2. **Given** `selfPlayerId` has not yet resolved (still `null` — pending fetch, or a swallowed 404 for an unfinished profile)
   **Then** the link is absent, exactly like Session Packs' own `packsRoute` guard

3. **Given** a non-PLAYER role (PARENT/COACH/ADMIN)
   **Then** the link never appears, regardless of `selfPlayerId`

4. **Given** the link's label
   **Then** it reuses the existing `development.dashboardTitle` i18n key (already translated en-US/fr-FR/de-DE — no new copy needed)

## Tasks / Subtasks

- [x] Add `developmentRoute` computed in `MainLayout.vue`, mirroring `packsRoute`'s `selfPlayerId.value ? ... : null` guard (AC1, AC2)
- [x] Add a `q-item` nav entry gated on `developmentRoute`, placed immediately after the Session Packs item, using icon `insights` and label `development.dashboardTitle` (AC1, AC4)
- [x] Add `MainLayoutSpec.js` coverage (AC1, AC2, AC3):
  - link absent before `selfPlayerId` resolves
  - link present at `/player/development/{id}` once `fetchSelfPlayerId` resolves
  - link absent for a non-PLAYER role even when `selfPlayerId` would resolve
  - register `/player/development/:playerId` and `/parent/players/:playerId/packs` in the spec's test router (neither was there before; Session Packs' own link had no prior test coverage and had never needed them — exposed by mounting with a resolved `selfPlayerId` for the first time)
- [x] **(Found during manual testing, AC1's post-login clause)** Replace the one-shot `onMounted(() => { if (authStore.isPlayer) {...} })` check with `watch(() => authStore.isPlayer, async (isPlayer) => {...}, { immediate: true })` in `MainLayout.vue`, so the fetch re-fires when `role` flips post-mount instead of only running once at initial (usually pre-login) mount
- [x] Add the post-mount-login regression case to `MainLayoutSpec.js`: mount unauthenticated, flip `authStore.role` to `PLAYER` on the live pinia instance without remounting, assert the link appears. Verified red against the unpatched `onMounted`-only code (`expected false to be true`) before confirming green against the fix

## File List

- `src/frontend/src/layouts/MainLayout.vue` (modified) — `developmentRoute` computed + nav item; `onMounted` one-shot check replaced with a `watch(authStore.isPlayer, ..., {immediate:true})`
- `src/frontend/src/layouts/__tests__/MainLayoutSpec.js` (modified) — 4 new test cases (3 original + the post-mount-login regression) + 2 router routes
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java` (modified) — self-owned branch added (round 2 finding)
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java` (modified) — new `existsByIdAndUserId`
- `src/test/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuardTest.java` (added) — 6 unit cases
- `src/test/java/com/softropic/skillars/platform/development/api/PlayerTimelineResourceIT.java` (modified) — 1 new IT case (self-registered player, 200)
- `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (modified, round 3) — `playerId` kept as the raw route-param string instead of `Number(...)`-converting it
- `src/frontend/src/components/development/SkillsRadarAssessmentPanel.vue` (modified, round 3) — `playerId` prop type widened `Number` → `[Number, String]`, matching its 3 siblings
- `src/frontend/src/pages/player/__tests__/PlayerDevelopmentDashboardPageSpec.js` (added, round 3) — 2 cases, red/green-verified

## Dev Notes

No backend or schema changes. No new i18n copy. No local `mvn verify` run — not applicable, this is a frontend-only change; `npx vitest run` and `npx eslint` were run directly instead (see Completion Notes).

## Completion Notes

- `npx vitest run src/layouts/__tests__/MainLayoutSpec.js`: 19/19 green (was 16/16 before the first 3 new cases), no router warnings once the two test-router routes were added.
- `npx vitest run` (full frontend suite): 227/227 green.
- **Manual test on the local container surfaced a real pre-existing bug** (see the box above AC1): neither Session Packs nor the new Development link appeared after a real login, despite the backend genuinely returning the player's profile (verified via `docker exec skillars-postgres-1 psql ... select * from main.player_profiles where user_id=...` — a real row existed). Root-caused to `MainLayout`'s one-shot `onMounted` check racing the SPA's client-side post-login navigation. Fixed with a `watch(..., { immediate: true })`; added a red/green-verified regression test (`MainLayoutSpec.js`, "resolves selfPlayerId and shows the link when role flips to PLAYER post-mount").
- Final: `npx vitest run src/layouts/__tests__/MainLayoutSpec.js` 20/20 green; `npx vitest run` (full suite) 228/228 green; `npx eslint src/layouts/MainLayout.vue src/layouts/__tests__/MainLayoutSpec.js` clean.
- **Round 2 (backend, `PlayerOwnershipGuard`):** `mvn -o test -Dtest=PlayerOwnershipGuardTest` 6/6 green; red confirmed by stashing the fix — compile failure (`cannot find symbol: existsByIdAndUserId`), as strong a red as an assertion failure. `mvn -o test -Dtest=PlayerTimelineResourceIT` 11/11 green against real Testcontainers Postgres (160s), including the new self-registered-player case. No `mvn verify` run locally per standing convention — these are isolated class runs (`-Dtest=<Class>`), not the full suite.
- **CI finding (PR #254, GitHub Actions `build` job):** the round-2 `PlayerOwnershipGuard` fix broke a pre-existing `@WebMvcTest` slice test, `PlayerSubscriptionOwnershipIT.getPlayerSubscription_ownPlayer_returns200` (200 expected, got 403) — 1334/1335 passed, this was the sole failure. Not a logic bug: the guard now calls `existsByIdAndParentId`/`existsByIdAndUserId`, but the test's `playerProfileRepository` Mockito mock only stubbed the old `findByIdAndParentId` method, so the new calls silently returned the mock default (`false`). Fixed the test's `stubPlayerOwnedBy` helper to stub the methods the guard actually calls (dropping the now-unused `PlayerProfile`/`Optional` imports), and added a self-registered-player case to this file too (same pattern as `PlayerTimelineResourceIT`'s), since `SubscriptionResource` shares the same guard. `mvn -o test -Dtest=PlayerOwnershipGuardTest,PlayerTimelineResourceIT,PlayerSubscriptionOwnershipIT` 22/22 green locally before re-pushing.
- **Round 3 (frontend, `PlayerDevelopmentDashboardPage.vue`):** `npx vitest run src/pages/player/__tests__/PlayerDevelopmentDashboardPageSpec.js` 2/2 green; red confirmed by stashing the fix — both cases failed against the unpatched page with the actual corrupted id (`893573203704173600`) as the received value, matching the user's real reported URL exactly, not a synthetic approximation. Full suite `npx vitest run` 230/230 green; `npx eslint` clean on all touched files. **Deferred, not fixed here:** the identical `Number(route.params.playerId)` pattern also exists in `ParentDevelopmentPortalPage.vue`, `ParentPlayerPortalPage.vue`, and `PlayerSubscriptionPage.vue` — found by grep, not manually reproduced (different persona/page than this story's test surface) — flagged to the user, recommend a small dedicated follow-up story.
- Rebuilt the local Docker image (`docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local build app`) and recreated the app container with each round's fix for the next round of manual testing.
- Implemented and verified directly in this session; no separate `/bmad-code-review` pass run.
