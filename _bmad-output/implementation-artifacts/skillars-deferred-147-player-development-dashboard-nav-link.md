# Story Deferred-147: Player Development Dashboard — Missing Nav Link

Status: done

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

## File List

- `src/frontend/src/layouts/MainLayout.vue` (modified) — `developmentRoute` computed + nav item
- `src/frontend/src/layouts/__tests__/MainLayoutSpec.js` (modified) — 3 new test cases + 2 router routes

## Dev Notes

No backend or schema changes. No new i18n copy. No local `mvn verify` run — not applicable, this is a frontend-only change; `npx vitest run` and `npx eslint` were run directly instead (see Completion Notes).

## Completion Notes

- `npx vitest run src/layouts/__tests__/MainLayoutSpec.js`: 19/19 green (was 16/16 before the 3 new cases), no router warnings once the two test-router routes were added.
- `npx vitest run` (full frontend suite): 227/227 green.
- `npx eslint src/layouts/MainLayout.vue src/layouts/__tests__/MainLayoutSpec.js`: clean.
- Implemented and verified directly in this session; no separate `/bmad-code-review` pass run — single-file-plus-tests change, mechanical in nature, same shape as the existing `packsRoute` pattern it mirrors.
