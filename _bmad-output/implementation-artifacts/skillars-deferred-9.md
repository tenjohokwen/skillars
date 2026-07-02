# Story Deferred-9: Frontend UX Polish

Status: done

## Story

As a coach or parent using the platform,
I want error states to be visible, player navigation to load fresh data, and batch booking to not carry stale state,
so that I always have accurate feedback and never see stale or missing content after navigating the app.

## Acceptance Criteria

1. **Given** a coach navigates between players on the development dashboard
   **When** the player route param changes (`route.params.playerId` watch fires)
   **Then** `useDevelopmentStore` state (`exposure`, `targets`, `narrative`, `neglectedSkills`) is cleared to null/empty before the new player's data is loaded — stale data from the previous player is not shown during the load
   **File**: `PlayerDevelopmentDashboardPage.vue`

2. **Given** a coach clicks Accept or Decline on a booking request
   **When** the API call fails (network error or 4xx/5xx)
   **Then** the page displays an error notification (Quasar `$q.notify` or equivalent) — the button spinner stops and an error message is shown; the coach can distinguish failure from success
   **File**: `CoachBookingRequestsPage.vue` — `handleAccept()` and `handleDecline()` error paths

3. **Given** a parent views their bookings page
   **When** the API call for `getParentBookings()` fails
   **Then** an error message is displayed in the template (the `bookingsError` ref is already captured but never rendered)
   **File**: `ParentBookingsPage.vue` — add an error alert to the template conditioned on `bookingsError`

4. **Given** a parent submits a booking request
   **When** the API call fails with 400, 403, or a network error
   **Then** a user-visible error notification is shown on `BookingRequestPage.vue` — the user is not left on the page with no feedback
   **File**: `BookingRequestPage.vue` — `submitBookingRequest()` error path (lines 112-128)

5. **Given** a coach is on the wrap-up sequence (step 1 or step 4) and `fetchSessionDna` fires
   **When** the request is in flight
   **Then** a loading indicator is shown over the DNA chart area
   **And** if the fetch fails, a placeholder message ("Unable to load session DNA") is shown rather than a silently absent chart
   **File**: `WrapUpSequence.vue` — add `dnaLoading` ref and `dnaError` ref

6. **Given** a parent or coach toggles batch mode on `BookingRequestPage.vue`
   **When** `toggleBatchMode()` sets `batchMode = true`
   **Then** `selectedSlot` is cleared to null so `canSubmit` is not immediately true from a prior single-slot selection
   **File**: `BookingRequestPage.vue:toggleBatchMode()`

7. **Given** the app displays development module text for German-locale users
   **When** `de/index.js` is loaded
   **Then** the development block contains German translations for all keys in the `en-US` development block (skills, radar, exposure, targets, narrative) — the current `de/index.js` development block contains English strings

## Tasks / Subtasks

- [x] **Task 1 — Clear development store on player navigation** (AC: 1)
  - [x] Read `PlayerDevelopmentDashboardPage.vue` — find the `watch` on `route.params.playerId` and the `onMounted` hook
  - [x] At the top of the watcher (before `loadPortal()` or equivalent): added `clearDevelopmentState()` + `loadPlayerData()` refactor
  - [x] Verify the exact store property names from `useDevelopmentStore()` / `development.store.js`
  - [x] Also apply the same clear in `onMounted` before the initial load (from 5.2 AD1 — stale error state between player switches)

- [x] **Task 2 — Booking accept/decline error notification** (AC: 2)
  - [x] Read `CoachBookingRequestsPage.vue:75-92` — find `handleAccept()` and `handleDecline()`
  - [x] Add error handling in each
  - [x] Same pattern for `handleDecline()`
  - [x] Component already uses Composition API with `useQuasar()` — reused existing `$q` instance

- [x] **Task 3 — Render `bookingsError` in `ParentBookingsPage`** (AC: 3)
  - [x] Read `ParentBookingsPage.vue` — find where `bookingsError` is set and the current template
  - [x] Add to the template, adjacent to the bookings list
  - [x] Used the project's existing `q-banner` error pattern (matches `PlayerDevelopmentDashboardPage.vue` and `store.error` conventions)

- [x] **Task 4 — Booking request submission error feedback** (AC: 4)
  - [x] Read `BookingRequestPage.vue` — `submitBookingRequest()` error path — **already implemented**: `submit()` already has a `catch` with `$q.notify` and resets `submitting` in `finally`. No code change required; verified against AC.

- [x] **Task 5 — WrapUpSequence DNA loading and error state** (AC: 5)
  - [x] Read `WrapUpSequence.vue` — find `fetchSessionDna()` and where the DNA chart is rendered
  - [x] Add refs: `dnaLoading`, `dnaError`
  - [x] Wrap the fetch with loading/error handling
  - [x] In the template, wrap the DNA chart component with `q-inner-loading` + error placeholder; also corrected `variant="compact"` → `variant="full"` per Dev Notes (4.4 W3)

- [x] **Task 6 — Clear `selectedSlot` on batch mode toggle** (AC: 6)
  - [x] Read `BookingRequestPage.vue` — find `toggleBatchMode()`
  - [x] Added `selectedSlot.value = null` when entering batch mode

- [x] **Task 7 — German translations for development module** (AC: 7)
  - [x] Read `src/frontend/src/i18n/en-US/index.js` (canonical reference; `en` mirrors it) — `development` block with all keys for skills, radar, exposure, targets, narrative
  - [x] Read `src/frontend/src/i18n/de/index.js` — found `development` block with English strings (radar/exposure/targets/narrative keys; `assessmentTypeLabel`, `report`, `timeline` sub-blocks were already German)
  - [x] Translated all previously-English keys to German (dashboardTitle, skillExposureTitle, currentWeekLabel, trendChartTitle, setTargetsLabel, neglectedSkillTag, neglectedSkillAlert, noExposureYet, saveTargets, targetLabel, narrative.*, radar.* including accessibleTable and correlation sub-blocks)
  - [x] Key set was already complete (not missing) — only values needed translation
  - [x] Machine-translated as a first pass; every changed string marked with `// TODO: native review`

### Review Findings

- [x] [Review][Defer] `booking.*` i18n keys are likely unreachable in the production locale [en/index.js, boot/i18n.js, MainLayout.vue] — deferred, pre-existing platform i18n architecture gap, scope exceeds this story. Target state per product owner: three selectable locales — `en-US`, `fr-FR`, `de-DE` — all at full parity. The bare `en` locale (889 lines, larger than `en-US`'s 474) was an experimental placeholder created to resolve American-vs-British English uncertainty; that's now resolved in favor of `en-US`, so `en`'s extra content (including `booking`, `video`, and other blocks `en-US` lacks) needs merging into `en-US` before `en` can be retired. `de` needs renaming to `de-DE` and adding to the language switcher (it isn't selectable today at all). Full parity requires translating `de`'s currently-empty `booking: {}` block and `fr-FR`'s missing `booking` block, not just the 4 keys this story added. This is a platform-wide i18n consolidation — recommend a dedicated follow-up story.

- [x] [Review][Patch] SluTargetEditor.vue targets-loaded watcher has no re-sync path after the new guard — `if (open.value) return` (line 53) silently drops `currentTargets` updates received while the dialog is open, with no re-sync when it later closes; also a possible stale-value flash if `onSave` closes the dialog before the parent's async save+refetch resolves [SluTargetEditor.vue:53] — fixed: added `watch(open, ...)` that re-syncs `localTargets` from `props.currentTargets` whenever the dialog closes

- [x] [Review][Patch] PlayerDevelopmentDashboardPage.vue player-switch watcher has no request-sequencing guard — rapid navigation between players can let a slow response for an earlier player overwrite a later player's freshly loaded state [PlayerDevelopmentDashboardPage.vue:213-219] — fixed: added a `loadRequestId` generation counter; a load whose response lands after a newer navigation self-corrects by reloading the current player

- [x] [Review][Patch] clearDevelopmentState() omits radar/correlation fields — only clears `exposure`, `targets`, `narrative`, `error`; `radarEntries`, `radarDisplay`, `radarPreferences`, `correlationInsights` are left stale during a coach's player switch, undermining the story's stale-data goal [PlayerDevelopmentDashboardPage.vue:180-185] — fixed: `clearDevelopmentState()` now also nulls `radarEntries`, `radarDisplay`, `radarPreferences`, `correlationInsights`

- [x] [Review][Patch] `Number(newPlayerId)` has no NaN guard in the new route watcher — a malformed route param silently produces NaN and gets passed into fetch calls [PlayerDevelopmentDashboardPage.vue:218] — fixed: watcher now bails via `Number.isFinite()` guard before loading

- [x] [Review][Patch] WrapUpSequence.vue `q-inner-loading` is not scoped to the DNA chart area — it overlays the entire full-screen wrap-up modal because its nearest positioned ancestor is `.wrap-up` (`position: fixed`), not a chart-local container, contradicting AC5's "over the DNA chart area" wording [WrapUpSequence.vue:161] — fixed: wrapped the chart/loading/error markup in a new `.wrap-up__dna-chart-area` (`position: relative`) container so the overlay is scoped locally

- [x] [Review][Patch] CoachBookingRequestsPage.vue error catch doesn't refresh the request list — if accept/decline fails because the booking was already resolved concurrently, the stale actionable item remains in the UI, letting the coach retry an action that will fail identically forever [CoachBookingRequestsPage.vue:151-171] — fixed: catch blocks now call `bookingStore.loadCoachBookingRequests()` to resync the list after a failure

- [x] [Review][Patch] ParentBookingsPage.vue error banner and empty-state block can render simultaneously — the pre-existing "no bookings yet" empty-state CTA isn't gated on the absence of `bookingsError` [ParentBookingsPage.vue:7-20] — fixed: empty-state condition now also requires `!bookingStore.bookingsError`

- [x] [Review][Patch] Missing German translations for 4 new booking i18n keys — `booking.requests.acceptError`, `declineError`, `bookingsLoadError`, `booking.wrapUp.step4DnaError` were added only to `en/index.js`, with no `de/index.js` counterparts [de/index.js] — fixed: added German translations for these 4 keys (`de.booking` was previously an empty object; rest of the `booking` block remains pending per deferred i18n consolidation, D2)

- [ ] [Review][Patch] ~~BookingRequestPage.vue `toggleBatchMode()` doesn't clear `notes` when entering batch mode~~ [BookingRequestPage.vue:233-247] — investigated, no fix applied: verified `notes` is only used by the single-slot `submit()` flow and is never included in `submitBatchRequest()`'s payload, so there is no actual leakage into batch submissions. Original finding was a false positive; clearing `notes` here would have discarded the user's typed note for no benefit.

- [x] [Review][Defer] AC7 `portal` sub-block left untranslated in de/index.js [de/index.js] — deferred, pre-existing (already English in both `en` and `de` before this story; explicitly out of this story's scope per the Dev Agent Record)

## Dev Notes

### Quasar `$q.notify` vs `useQuasar()`

If components use Options API, `this.$q.notify(...)` is available. If they use Composition API `<script setup>`, use:
```javascript
import { useQuasar } from 'quasar';
const $q = useQuasar();
```
Check the component's script style before choosing which pattern to apply.

### Store property names — verify before clearing

The exact property names in `useDevelopmentStore()` may differ from `exposure`, `targets`, `narrative`, `neglectedSkills`. Read `development.store.js` (or the relevant Pinia store file) to get the exact state property names before Task 1.

### `SluTargetEditor` race (5.2 D5)

While working on Task 1, also check `SluTargetEditor.vue:51` — a targets-loaded watcher can discard user input if `fetchTargets` resolves while the dialog is open. Consider adding a guard: `if (dialogOpen.value) return;` in the watcher. This is a low-probability UX issue but simple to fix while in the component.

### German translation scope

Task 7 is a content translation task, not a code change. Focus on getting the key structure correct in `de/index.js` so Vue-i18n lookups succeed — exact wording can be refined in a follow-up native-speaker review. Do not leave any key missing (missing key falls back to `en-US` which is English — defeat the purpose of the German locale).

### `variant="compact"` in WrapUpSequence (4.4 W3)

Story 4.4 noted `WrapUpSequence` uses `variant="compact"` instead of `variant="full"` for the DNA chart — while in this file (Task 5), also correct this to `variant="full"` as specified in the spec.

### References — Files to Read Before Implementing

- `PlayerDevelopmentDashboardPage.vue` — `route.params.playerId` watcher and `onMounted`
- `development.store.js` — state property names for Task 1 clear
- `CoachBookingRequestsPage.vue:75-92` — `handleAccept()` and `handleDecline()` error paths
- `ParentBookingsPage.vue` — `bookingsError` ref and template structure
- `BookingRequestPage.vue:112-128` — `submitBookingRequest()` and `toggleBatchMode()`
- `WrapUpSequence.vue:309` — `fetchSessionDna()` and DNA chart rendering
- `src/frontend/src/i18n/de/index.js` — current German development block
- `src/frontend/src/i18n/en/index.js` (or `en-US`) — reference keys for translation

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- No automated frontend test harness exists in this repo (no vitest/jest config; `npm test` in `src/frontend/package.json` is a no-op placeholder). None of the 7 tasks in this story call for adding one, so per workflow rules (no new dependencies without approval) automated tests were not added. Verification was done via `eslint` (project-wide, zero errors), `node --check` on both edited i18n files, and manual trace of each acceptance criterion against the implemented code paths.
- Actual file paths differed from the story's assumed paths (`src/frontend/src/pages/player/...`, `src/frontend/src/pages/coach/...`, `src/frontend/src/pages/parent/...`, `src/frontend/src/components/booking/...`, `src/frontend/src/components/development/...`) — resolved via `find`.
- `PlayerDevelopmentDashboardPage.vue` had **no existing `watch` on `route.params.playerId`** — only `onMounted`. This is a real bug beyond what the story assumed (player switches wouldn't reload data at all if Vue Router reuses the component instance). Fixed by extracting the load logic into `loadPlayerData()` and adding a new watcher, per the AC.
- `BookingRequestPage.vue` `submitBookingRequest()` (Task 4 / AC 4) was already correctly implemented (catch + notify + `finally` reset) — the story's referenced line numbers (112-128) pointed at the template, not the script. No change made; verified only.
- `booking.*` i18n keys exist only in `src/frontend/src/i18n/en/index.js` (not `en-US`), matching the pre-existing pattern used elsewhere in this codebase for the booking module — new keys were added there for consistency. This existing en-US/en split is a pre-existing condition unrelated to this story and was left as-is.
- Per AC7, only the `development` block was translated in `de/index.js`; the `portal` sub-block (already English in both `en` and `de`) was left untouched as it's outside the story's AC scope.

### Completion Notes List

- Task 1: Added `clearDevelopmentState()` and refactored data loading into `loadPlayerData(id)`, called from both `onMounted` and a new `watch(() => route.params.playerId, ...)`. Also fixed the `SluTargetEditor.vue` targets-loaded watcher race noted in Dev Notes (5.2 D5) by guarding with `if (open.value) return`.
- Task 2: Added `catch` blocks with `$q.notify({ type: 'negative', ... })` to `handleAccept`/`handleDecline` in `CoachBookingRequestsPage.vue`; added `booking.requests.acceptError` / `declineError` i18n keys.
- Task 3: Added a `q-banner` bound to `bookingStore.bookingsError` in `ParentBookingsPage.vue`; added `booking.requests.bookingsLoadError` i18n key.
- Task 4: Verified only — no code change needed, existing implementation already satisfies AC4.
- Task 5: Added `dnaLoading`/`dnaError` refs and loading/error handling around `fetchSessionDna()` in `WrapUpSequence.vue`; added `q-inner-loading` and error placeholder in the template; corrected `variant="compact"` to `variant="full"` per Dev Notes (4.4 W3); added `booking.wrapUp.step4DnaError` i18n key.
- Task 6: `toggleBatchMode()` in `BookingRequestPage.vue` now clears `selectedSlot` when entering batch mode (previously only cleared on exit).
- Task 7: Translated all previously-English `development` block values in `de/index.js` to German (machine-translation first pass, each marked `// TODO: native review`), matching the key structure already present.
- Validation: `eslint` run project-wide (zero errors/warnings introduced), `node --check` passed on both edited i18n files. Pre-existing Prettier formatting warnings on 7 of the touched files were confirmed (via `git stash`) to predate this story's changes and were left untouched to avoid unrelated reformatting noise.

### File List

**Modified Files:**
- `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue`
- `src/frontend/src/components/development/SluTargetEditor.vue`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/components/booking/WrapUpSequence.vue`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/en/index.js`

## Change Log

- 2026-07-02: Implemented all 7 tasks (AC 1–7). Status moved backlog → review.
