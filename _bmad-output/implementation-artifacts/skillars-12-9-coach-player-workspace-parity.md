# Story 12.9: Coach Player Workspace Parity

Status: done

<!-- Source: _bmad-output/planning-artifacts/skillars-epics.md, Epic 12, Story 12.9 (gaps G16-G18). Citations verified against master @ 88363781 on 2026-10-23. Owner decisions D12.9-A/B/C below were CONFIRMED by the owner on 2026-10-23. Revised 2026-10-23 after `story-review.md`, see "Story-review response" in Dev Notes. -->

## Story

As a **coach**,
I want **one player page where I can see everything a parent sees and use every coach tool for that player**,
so that **clicking a player on my roster gives me the full relationship/history view the brainstorm promised (Converge #12)**.

## Owner decisions (confirmed by owner 2026-10-23 — do NOT re-litigate)

| # | Decision | Chosen (recommended) | Why / consequence |
|---|---|---|---|
| D12.9-A | G18: `/neglected-skills` — use it or remove it? | **Use it** on the coach page's neglected-skills card, showing each skill's `detectedAt` ("neglected since …"). | `detectedAt` is the start of the *current open* neglect streak (`NeglectedSkillProcessor.java:72-83` creates a flag only when none is open, resolves it when the skill recovers), so it is real coach value the `/exposure` codes don't carry. Zero backend change; removal would mean deleting a resource, a service method, a contract record and ITs (`SkillExposureResourceIT`, `SluTargetServiceTest`) and dev-docs. Cost: one extra GET per page load. |
| D12.9-B | After removing the dead `isCoach`/`isParent` branches from `PlayerDevelopmentDashboardPage.vue`, what does the PLAYER see? | **Every card already in its template renders real data:** radar (read-only, fetch `/radar/display`) and the reports list (read-only). Narrative stays off (it was `isParent`-only). | Today the player's radar card is permanently empty (no fetch on the PLAYER path) and the reports card never renders (`v-if="tierLoaded"`, but `tierLoaded` is only set inside the `isCoach` branch, `PlayerDevelopmentDashboardPage.vue:102,241-252`). Both endpoints already admit a self-owner via `@playerOwnershipGuard` (`PlayerOwnershipGuard.java:33-34`, `existsByIdAndUserId`). Alternative: delete those two cards from the player page — rejected, they were clearly intended. |
| D12.9-C | Ship as one story or split "read parity" / "coach tools"? | **One story**, frontend only. | No backend change under D12.9-A. Tasks 2 and 3 are the natural split line if the dev wants two PRs. |

## Acceptance Criteria

Gap IDs are from the Epic 12 "Gap → story map". Do not re-litigate the epic's "Owner decisions already made". Access-window / relationship-status consistency (G19-G25, incl. the unchecked `/radar/preferences`) is **12.10**, not here.

### G16 — read parity on `coach/players/:playerId`

1. **New sections.** `CoachPlayerDetailPage.vue` additionally renders, each through the existing endpoint (all already relationship-checked server-side via `CoachPlayerAuthorizationService` — table in Dev Notes):
   - **Skills radar** (`SkillsRadarChart`, data from `GET /radar/display`) as the hero card directly under the heading, with the "compare to baseline" toggle (same `hasBaseline` logic as `PlayerDevelopmentDashboardPage.vue:212-220`) and coach skill-subset selection persisted via `GET/PUT /radar/preferences` (debounced, flushed on player switch / unmount / `pagehide` — reuse the flushable debounce from `PlayerDevelopmentDashboardPage.vue:151-210,266-302`; see AC9 for where it lives and AC12 for the sync rules, which can NOT be ported verbatim).
   - **Narrative summary** (`SluNarrativeSummary`, `GET /narrative`) under the radar, inside a page-level card with its own heading and empty state: `SluNarrativeSummary` renders nothing at all when the list is empty (`SluNarrativeSummary.vue:2`, `v-if="narrative.length"`).
   - **Skill exposure bar chart** (`SkillExposureBarChart`, already-fetched `store.exposure.currentWeek` + `neglectedCodes`).
   - **Skill trend chart** (`SkillExposureTrendChart`, already-fetched `store.exposure.trend`). This is the "skill-trend chart" in the epic; the separate `/slu/skill-trends` direction endpoint stays out of scope (see Out of scope).
   - **Reports list** (`PerformanceReportsPanel`, `GET /reports`). The backend already filters a coach to *their own* reports (`ReportGenerationService.java:293-330`); the UI must not imply otherwise.
   Existing sections (neglected skills, contributions, timeline) stay. Suggested order: radar → narrative → neglected skills → exposure (+ coach actions) → trend → contributions → correlation → radar history → reports → timeline. Each card that is rendered has a heading and a translated empty state; none collapses to nothing. Tier-hidden cards (AC4) are not rendered at all, which is different from collapsing.
2. **Failure isolation.** A failure (incl. 403/FeatureGated) of any *secondary* section (radar, preferences, narrative, neglected skills, targets, radar entries, correlation, reports, coach tier) shows an inline translated message in that card (or, for the dialog-backed targets and the tier, in the card/area that hosts the button) and never blanks the page. The page-level body gate (`v-if="!loading && !pageError"`, `CoachPlayerDetailPage.vue:22`) keeps its current meaning: it trips only on skill-definitions / exposure / contributions failure. Today this is impossible as-is: `fetchSkillDefinitions`, `fetchExposure`, `fetchNarrative`, `fetchTargets`, `fetchRadarEntries`, `fetchRadarDisplay` all write *and clear* the one shared `store.error` (clears at `development.store.js:58,76,96,106,127,151`), so concurrent fetches both blank the page on an unrelated failure and can erase each other's error. (`fetchCorrelationInsights` writes but does not clear it; `fetchRadarPreferences` swallows every error into `{ selectedSkillCodes: [] }`; `fetchReports` already uses `reportsError`.) Every secondary card needs its own loading flag as well as its own error, so its empty state never shows while in flight or after a failure (deferred-152, `ParentDevelopmentPortalPage.vue:70-83`). See Task 1.
3. **No cross-player data.** Param-only navigation A → B (Vue Router reuses the instance) must never show A's radar, preferences, narrative, neglected skills, targets, radar entries, correlation or reports under B's heading — neither while B is in flight nor after B fails. Same class of bug as 12-4's HIGH finding (12-4 Task 4.0); same fix: clear-before-fetch + per-action sequence guard in the store (Task 1).

### G17 — coach tools reachable from the coach page

4. **Tools.** From `coach/players/:playerId` the coach can:
   - set SLU targets (`SluTargetEditor`, `GET/PUT /targets`), refreshing exposure after save (mirror `onSaveTargets`, `PlayerDevelopmentDashboardPage.vue:290-293`). **Data-loss guard (non-negotiable):** the "Set targets" button is disabled unless `GET /targets` for the *current* player has completed successfully (`!targetsLoading && !targetsError`, current seq). Reason: `SluTargetEditor.onSave` sends `weeklyTargetSlu: localTargets[code] ?? null` for **every** skill (`SluTargetEditor.vue:71-78`) and `SluTargetService.setTargets` **deletes** the row for every `null` (`SluTargetService.java:39-45`), so saving from an editor seeded with the cleared `[]` (still loading, or the GET failed) wipes all of the coach's real targets. If the GET failed, show the translated targets error next to the button. A failed *save* and a failed *post-save re-fetch* are reported differently (today `saveTargets` swallows both into `store.error` and the caller can't tell, `development.store.js:115-123`): save failure → negative notify, targets unchanged; re-fetch failure → targets error state, button disabled until a successful reload;
   - add a radar assessment (`SkillsRadarAssessmentPanel`, `POST /radar/entries`) and see the radar history (`RadarAssessmentHistoryList`, `GET /radar/entries`) — **only** when `authStore.coachTier` is `INSTRUCTOR` or `ACADEMY` (`RADAR_ALLOWED_TIERS`, server: `RadarAssessmentService.getMyEntries` throws `FeatureGatedException`→403 otherwise). For `SCOUT` the assessment button and the radar-history card are **not rendered** (no placeholder, same as the player dashboard did). After a successful submit, refresh radar display too so the chart reflects it when the composite has updated;
   - see the correlation panel (`DevelopmentCorrelationPanel`): real data for `ACADEMY`, the existing blurred teaser for others. Only call `GET /radar/correlation` for `ACADEMY` (it 403s for anyone else, `DevelopmentCorrelationService.java:53-55`);
   - generate a report (`PerformanceReportsPanel` with `:is-coach="true"`, which already gates the button on `coachTier != SCOUT`), passing `:player-name` so `GenerateReportDialog`'s title is not blank (no caller passes it today; `PerformanceReportsPanel.vue:57-62`).
   Tier-gated UI renders only after `authStore.fetchCoachTier()` settles (no flash of teaser/hidden buttons; mirror `tierLoaded`). **Tier failure is not "SCOUT":** `fetchCoachTier` sets `coachTier = null` on any error and has no dedupe (`auth.store.js:34-41`). On the coach page, `coachTier == null` after the fetch settled means "unknown": hide every tier-gated tool (assessment, history, correlation incl. teaser, report-generate button — `canGenerate` already treats `null` as false) and show one translated inline message in the tools area. Do not show the ACADEMY upsell teaser to a coach whose tier simply failed to load.
5. **Write-relationship mismatch is handled, not hidden.** Writes use the *narrow* `ACTIVE_RELATIONSHIP_STATUSES` (`CoachPlayerAuthorizationService.java:23-24`; `SluTargetService.setTargets:38`, `RadarAssessmentService.submitAssessment:48`, `ReportGenerationService.generateReport:167`), reads the *wide* 12-status list. A coach whose only booking is e.g. `DISPUTED` can open the page but a write 403s. Each write failure shows a translated negative `$q.notify` using the store's existing `getErrorMessage(err)` (`utils/errorHandler`, translates from `errorMsg.errorKey`), keeps the dialog's input where the component already does, and does not blank the page. Do not try to predict the status client-side. **Two existing dialogs get this wrong today and are in scope:**
   - `GenerateReportDialog.vue:82` notifies `store.error ?? generic`, but `generateReport` writes `reportsError`, never `error` (`development.store.js:209-220`), so the coach sees a generic or a *stale unrelated* message. Use the thrown error. Also, `generateReport` setting `reportsError` makes `PerformanceReportsPanel.vue:18-27` replace the existing reports list with a banner: a failed *generate* must not hide the already-loaded list — stop `generateReport` from writing `reportsError` (the dialog's notify is the feedback); `reportsError` stays for the list GET only.
   - `SkillsRadarAssessmentPanel.vue:172-177` maps **every** 403 to `submitErrorFeatureGated` ("requires Instructor tier") and reads `data.message` (no such field; it is `data.errorMsg.message`). In AC5's own DISPUTED scenario an INSTRUCTOR coach would be told to upgrade. Discriminate on `err.response.data.errorMsg.errorKey === 'security.featureGated'` (`ApiAdvice.java:388-392` → `ErrorDto(helpCode, ErrorMsg(errorKey, message))`); any other error → `getErrorMessage(err)`. **Not** `data.helpCode`: `helpCode` is a minted log reference (`ApiAdvice.java:705-719`, `ErrorLog.logError`), not the message key.
6. **Player dashboard cleanup.** `PlayerDevelopmentDashboardPage.vue` (route PLAYER-only, `routes.js:284-288`; `authStore.role` is single-valued, `auth.store.js:16-18`, so `isCoach`/`isParent` are always false there) loses every `isCoach`/`isParent` branch, the coach fetches, the tier load, the radar-preference debounce, `SluTargetEditor`, `SkillsRadarAssessmentPanel`, the correlation and radar-history cards. Per D12.9-B, the PLAYER path fetches `/radar/display` (radar renders, read-only, no baseline toggle unless a baseline exists — keep `hasBaseline`) and the reports card renders unconditionally with `:is-coach="false"`. Exposure, trend chart and timeline unchanged. No coach-only API is called from this page any more (assert in spec).

### G18 — `/neglected-skills`

7. Per D12.9-A the coach page's neglected-skills card is fed by `GET /api/development/players/{id}/neglected-skills` (new `getNeglectedSkills` in `development.api.js`, new store action/state with seq guard + own error), listing translated skill display name (from `store.skillDefinitions`, code as fallback; the endpoint returns only `skillCode` + `detectedAt`) + "since {date}", date-only, formatted with `new Date(detectedAt).toLocaleDateString(locale.value)` from `useI18n()` (precedent `SessionPrepCard.vue:135,160`). **Not** Quasar `date.formatDate` with month names: no Quasar lang pack is configured (`quasar.config.js:138` commented out), so de/fr would show English months. Order oldest streak first, sorting on parsed `Date` values (the endpoint returns flags unordered). Copy must not say "below *your* target": the flag is computed against the **max target across all coaches** (`NeglectedSkillProcessor.java:41,64`, `findMaxTargetPerSkill`), so it may be another coach's target; "neglected since {date}" is accurate. `SkillExposureBarChart`'s highlight keeps using `store.neglectedCodes` from `/exposure` (do not change the parent portal or prep card). The comment at `CoachPlayerDetailPage.vue:23-24` ("rather than a call to /neglected-skills") is updated. Update the stale "no frontend caller" statements in `docs/dev-docs/development/index.html` if present.

### Cross-cutting

8. **i18n** for every new string in en-US, de-DE, fr-FR (`localeKeyParitySpec` / `i18nTemplateKeyResolutionSpec`; literal keys only). Reuse existing `development.radar.*`, `development.skillExposureTitle`, `development.trendChartTitle`, `development.setTargetsLabel` etc. where the text fits; new coach-page keys go under `coach.playerDetail*`.
9. **Structure.** The coach page must stay readable: extract the radar card + preference persistence into a new `src/frontend/src/components/development/CoachRadarCard.vue` (owns `localSelectedSkillCodes`, baseline toggle, debounce/flush, `pagehide` listener) or move the flushable debounce into `src/frontend/src/composables/` — the player dashboard no longer needs it after AC6, so it must be *moved*, not duplicated. `<script setup>`, `async/await`, Prettier.
10. **Small shared fixes this story depends on:** (a) `PerformanceReportsPanel.vue:53` empty-state `!store.error` → `!store.reportsError` (an unrelated failed fetch currently hides "no reports"); (b) `PerformanceReportsPanel` mounted with `:key="playerId"` on the coach page (it fetches only in `onMounted`); (c) `SkillsRadarAssessmentPanel.vue:3` `min-width: 600px` overrides `max-width: 90vw` on phones — use `width: 600px; max-width: 90vw` (coaches use this from mobile now).
11. **No backend change.** If the dev finds one is needed, stop and ask; G19 (preferences relationship check) is 12.10.
12. **Radar preference sync (cannot be ported verbatim).** The player dashboard synced `localSelectedSkillCodes` exactly once, after its `await Promise.all`, and deliberately avoided a watch (`PlayerDevelopmentDashboardPage.vue:199-201,262-263`). On the coach page the preferences fetch is non-blocking, so that sync point no longer exists. Rules: (a) `fetchRadarPreferences` returns the loaded value (or `null` if superseded by a newer seq); the radar card applies it to `localSelectedSkillCodes` only if it is still for the current player **and** the coach has not changed the selection since that fetch started (a "dirty since load" flag), so a late response never overwrites a fresh click; (b) `saveRadarPreferences` must **not** write `store.radarPreferences` (today it does unconditionally on success, `development.store.js:171-175`): a flushed save for player A resolving after B's fetch would overwrite B's preferences. Local state is the source of truth for the selection; the store ref is only the last loaded value. The only other reader was the player dashboard, which loses it under AC6. (c) A failed preferences GET shows a small inline message in the radar card (needs `radarPreferencesError`; today the store swallows it) and the chart falls back to "all skills"; a failed PUT notifies.

## Tasks / Subtasks

- [x] Task 1: Store hardening for multi-section pages (AC: #2, #3, #4, #5, #7, #12)
  - [x] 1.1 In `development.store.js`, give each of `fetchSkillDefinitions`, `fetchExposure`, `fetchNarrative`, `fetchTargets`, `fetchRadarEntries`, `fetchRadarDisplay`, `fetchRadarPreferences`, `fetchCorrelationInsights`, `fetchReports` its own error ref (`skillDefinitionsError`, `exposureError`, `narrativeError`, `targetsError`, `radarEntriesError`, `radarDisplayError`, `radarPreferencesError`, `correlationError`; `reportsError` exists) and a loading flag where none exists (`narrativeLoading`, `targetsLoading`, `radarPreferencesLoading`; `radarLoading`, `radarDisplayLoading`, `correlationLoading`, `reportsLoading` exist). **Keep** the legacy `store.error` writes exactly where they are today (and keep `fetchRadarPreferences`'s `{ selectedSkillCodes: [] }` fallback) so `ParentDevelopmentPortalPage.vue:177-182`, `PlayerDevelopmentDashboardPage.vue` and the existing `developmentStoreSpec.js:60-120` assertions on `store.error` behave exactly as today; the coach page stops reading `store.error`.
  - [x] 1.2 Add clear-before-fetch + `_xxxSeq` guard (pattern: `fetchExposure`, `:69-93`) to `fetchNarrative`, `fetchTargets`, `fetchRadarEntries`, `fetchRadarDisplay`, `fetchRadarPreferences`, `fetchCorrelationInsights`, `fetchReports`. Clear to the *empty* value the consumers already tolerate (`narrative=[]`, `targets=[]`, `reports=[]`, the rest `null`; `radarPreferences` → `null`, and keep the existing `{ selectedSkillCodes: [] }` fallback on error). Check every consumer of `radarEntries`/`radarDisplay`/`correlationInsights` for `null` handling (`RadarAssessmentHistoryList.vue:4` ok; `DevelopmentCorrelationPanel.vue:47` treats `null` as *error* — so it must only render after `correlationLoading` is false, which it already does via `v-else-if`).
  - [x] 1.3 `saveRadarPreferences`, `saveTargets`, `submitRadarAssessment`: surface failure to the caller (return `false` / rethrow; `submitRadarAssessment` already rethrows) so the page can notify (AC5). `saveTargets` distinguishes save failure from re-fetch failure (AC4). `saveRadarPreferences` stops writing `radarPreferences` (AC12b). `fetchRadarPreferences` returns the loaded value or `null` when superseded (AC12a). `generateReport` stops writing `reportsError` (AC5). After AC6 the only remaining callers are the coach page and the two dialogs; grep to confirm.
  - [x] 1.4 New `getNeglectedSkills(playerId)` in `development.api.js`; store `neglectedSkills` (`[]`), `neglectedSkillsLoading`, `neglectedSkillsError`, `fetchNeglectedSkills` with seq guard.
  - [x] 1.5 Extend `stores/__tests__/developmentStoreSpec.js` (its `vi.mock('src/api/development.api', …)` factory at `:12-40` must gain `getNeglectedSkills`, or the store import throws): per action, out-of-order responses for two players keep only the latest; data is cleared at fetch start; a failure sets only its own error ref (+ legacy `error` where it is written today); `saveRadarPreferences` success leaves `radarPreferences` untouched; `generateReport` failure leaves `reportsError` and `reports` untouched.
- [x] Task 2: Coach page read parity (AC: #1, #2, #3, #7, #8, #9)
  - [x] 2.1 `CoachRadarCard.vue` (or composable) per AC9: move the flushable debounce + flush-on-switch/unmount/`pagehide` logic from the player dashboard, capturing `playerId` at selection time (`:299-301` comment); implement the sync rules of AC12 (they replace the dashboard's single post-`Promise.all` sync).
  - [x] 2.2 `CoachPlayerDetailPage.vue`: `loadPlayerDetail` keeps the core trio inside the gated `Promise.all`; secondary fetches (radar display, preferences, narrative, neglected skills, targets, reports via panel) start in parallel but are **not** awaited by the core `loading` flag, each card showing its own loading/error/empty/data state (five-state convention from 12-1 `CoachReviewsPage.vue` / 12-7). Keep `loadGeneration`, the `lastKnownPlayerNames` cache and `:key="playerId"` on `PlayerTimelinePanel`.
  - [x] 2.3 Neglected-skills card from `store.neglectedSkills` (AC7).
- [x] Task 3: Coach tools (AC: #4, #5, #10)
  - [x] 3.1 Start `authStore.fetchCoachTier()` once on mount and keep that **single promise** in the page. Every load (mount and each player switch) does `await tierPromise`, then checks its own `loadGeneration` is still current, then runs the tier-gated fetches for *its* player (`fetchRadarEntries` for INSTRUCTOR/ACADEMY, `fetchCorrelationInsights` for ACADEMY). This way a switch during the in-flight tier fetch still gets B's gated fetches, and A's never land after the switch. Don't re-fetch the tier on switch. Tier failure per AC4.
  - [x] 3.2 Mount `SluTargetEditor` (button guarded per AC4), `SkillsRadarAssessmentPanel`, `DevelopmentCorrelationPanel`, `RadarAssessmentHistoryList`, `PerformanceReportsPanel` (`:is-coach="true"`, `:player-name`, `:key="playerId"`).
  - [x] 3.3 Write-failure notifications (AC5), incl. the `GenerateReportDialog.vue:82` and `SkillsRadarAssessmentPanel.vue:172-177` fixes; on successful assessment submit refresh entries (already) and radar display.
  - [x] 3.4 Shared fixes AC10 (a)-(c).
- [x] Task 4: Player dashboard cleanup (AC: #6)
  - [x] 4.1 Remove the dead branches/components/fetches listed in AC6; fetch `/radar/display` on the PLAYER path; reports card unconditional, `:is-coach="false"`; keep the `clearDevelopmentState` + `loadRequestId` race handling for the fetches that remain.
  - [x] 4.2 Remove now-unused imports/i18n only if no other caller uses them (grep first; `development.radar.correlationTitle`, `historyTitle`, `addAssessmentLabel` move to the coach page, they are not orphaned).
- [x] Task 5: Tests (AC: all) — Vitest only, `cd src/frontend && npm run test:unit -- <Spec>`
  - [x] 5.1 `CoachPlayerDetailPageSpec.js` (extend; keep the existing axios-mock + `stubActions: false` + Tsid fixture setup). **First fix the fixture:** `mockApiGetByUrl` resolves any unmatched URL to `[]` (`CoachPlayerDetailPageSpec.js:90-96`); with the new fetches that makes `radarEntries = []` and `RadarAssessmentHistoryList.vue:4` (`radarEntries.entries.length`) throws, and `radarDisplay = []` etc. Give the helper realistic per-endpoint defaults (`/radar/entries` → `{ entries: [], … }`, `/radar/display` → `{ skills: [] }`, `/radar/preferences` → `{ selectedSkillCodes: [] }`, `/marketplace/coaches/me/tier` → `{ tier: 'INSTRUCTOR' }`, list endpoints → `[]`) and keep every existing test green. Then cover: all new cards render with data; each secondary endpoint rejecting (incl. a 403 `FeatureGated` body) shows only that card's message while the rest of the page and the heading stay; SCOUT → no assessment button / no history / no `/radar/entries` or `/radar/correlation` call; INSTRUCTOR → assessment + history, correlation teaser, no correlation call; ACADEMY → correlation call; A→B param switch with B's radar/narrative/reports delayed then rejected never renders A's values; a pending preference save is flushed with **A's** id on switch and its success does not change B's selection; a late preferences response does not overwrite a click made after the fetch started; write 403 → notify, page intact; relationship 403 on assessment submit shows the server message, not "requires Instructor tier", while an `errorKey: 'security.featureGated'` 403 does; generate-report failure → notify with the real message and the existing reports list still visible; "Set targets" disabled while `/targets` is pending and after it fails, enabled after success (and a save never sends all-`null` payloads in those states); tier fetch failure → tools area message, no correlation teaser; tier fetch still pending when switching A→B → B's `/radar/entries` is called, A's is not applied; neglected card shows name + since-date, oldest first by parsed date, from `/neglected-skills`; narrative empty → card with empty-state text.
  - [x] 5.2 `PlayerDevelopmentDashboardPageSpec.js`: keep both Tsid tests. This spec uses `createTestingPinia` with **stubbed actions** and `shallow: true` (`:30-45`), so axios is never called and URL assertions would pass vacuously. Assert on the action spies instead: `store.fetchRadarDisplay` called with the exact Tsid; `fetchTargets`, `fetchRadarEntries`, `fetchRadarPreferences`, `fetchCorrelationInsights`, `fetchNarrative` and `authStore.fetchCoachTier` **not** called; `wrapper.findComponent(PerformanceReportsPanel).exists()` is true (shallow stubs still register the component) with `isCoach` prop `false`.
  - [x] 5.3 `PerformanceReportsPanel` empty state with an unrelated `store.error` set still shows "no reports" (new small spec or add to an existing one). `GenerateReportDialogSpec.js`: failure notifies with the thrown error's message, not `store.error`.
  - [x] 5.4 Mutation sanity at least once: remove the seq guard from `fetchRadarDisplay` → the A→B spec goes red.
  - [x] 5.5 Run the full Vitest suite once (it is fast, ~480 tests) plus `localeKeyParitySpec`/`i18nTemplateKeyResolutionSpec`; `npx prettier --check` on touched files. Do **not** run `mvn verify`.
- [x] Task 6: Docs (AC: #7)
  - [x] 6.1 `docs/dev-docs/development/index.html`: coach player page now hosts the coach tools; player dashboard is player-only; `/neglected-skills` has a caller. Keep it short.

### Review Findings

Code review 2026-10-23 (/bmad-code-review: Blind Hunter, Edge Case Hunter, Acceptance Auditor). Every finding re-checked against the working tree.

- [x] [Review][Patch] (Decision resolved 2026-10-23: (a) drop the refresh; deviates from AC4's "refreshing exposure after save" wording on purpose) Post-save exposure refresh can blank the page and does nothing useful — `onSaveTargets` re-runs `fetchExposure` (AC4 "refreshing exposure after save"). A failed refresh sets `exposureError`, which trips the core `pageError` gate and unmounts the whole page right after a "saved" toast; a successful one still nulls `exposure` first, so heading/charts flash to "unknown player"/empty. Exposure does not depend on targets (`SkillExposureResponse` = SLU snapshots + open flags + name), and neglected flags are recomputed only by the weekly job (`NeglectedSkillDetectionService.java:46`), so the refresh never shows new data. Options: (a) drop the refresh (recommended); (b) keep it as a non-clearing refresh whose failure only notifies.
- [x] [Review][Patch] (Decision resolved 2026-10-23: (a) accept the sequential load, correct the comments) Radar card and reports panel load after the core trio — both sit inside `v-if="!loading && !pageError"`, so `/radar/display`, `/radar/preferences`, `/reports` start only after skill-definitions/exposure/contributions settle, and they remount on every switch (Task 2.2 says "not awaited by the core loading flag"). `CoachRadarCard`'s `props.playerId` watcher never fires in practice. Options: (a) accept the sequential load and correct the comments (recommended, simplest; switch safety comes from remount + unmount flush); (b) move both out of the gate and give them their own placement while the core is loading/failed.
- [x] [Review][Patch] Write follow-up re-fetch can load player A's data onto player B (HIGH: targets can then be saved onto B, nulls delete B's rows) [src/frontend/src/stores/development.store.js:164-173, 197-205, 307-315]
- [x] [Review][Patch] Report-generate button not gated on this page's tier settle (reads `authStore.coachTier`, which `ProfilePage` may have filled) [src/frontend/src/pages/coach/CoachPlayerDetailPage.vue (PerformanceReportsPanel :is-coach)]
- [x] [Review][Patch] Trend card has no empty state (`SkillExposureTrendChart` is a bare `v-chart`) — AC1 [src/frontend/src/pages/coach/CoachPlayerDetailPage.vue]
- [x] [Review][Patch] Radar-preference save failure shows a fixed string, not `getErrorMessage(err)` — AC5 [src/frontend/src/components/development/CoachRadarCard.vue, development.store.js saveRadarPreferences]
- [x] [Review][Patch] German copy uses "du/Deine" in five new `coach.playerDetail*` keys; the coach section uses "Sie/Ihre" [src/frontend/src/i18n/de-DE/index.js:329-340]
- [x] [Review][Patch] Missing Task 5.1 tests: correlation failure isolation, no all-null targets payload while pending/failed, reports list stays visible after a failed generate [src/frontend/src/pages/coach/__tests__/CoachPlayerDetailPageSpec.js]
- [x] [Review][Patch] Navigating away mid-load does not cancel the tier-gated fetches (`loadPlayerDetail(undefined)` returns without bumping `loadGeneration`; no unmount bump) [src/frontend/src/pages/coach/CoachPlayerDetailPage.vue:381-385]
- [x] [Review][Patch] Player dashboard radar card has no loading state for the new `/radar/display` fetch (renders an empty chart until it lands) [src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue]
- [x] [Review][Patch] Stale comments in ProfilePage say `PlayerDevelopmentDashboardPage` is the other `fetchCoachTier` caller [src/frontend/src/pages/ProfilePage.vue:266-268, 582-584]
- [x] [Review][Defer] Debounced radar-preference flush on `pagehide` uses a normal axios PUT the browser may cancel [src/frontend/src/components/development/CoachRadarCard.vue] — deferred, pre-existing (carried over from the player dashboard)
- [x] [Review][Defer] Two radar-preference PUTs can complete out of order (last-writer not guaranteed) [src/frontend/src/components/development/CoachRadarCard.vue] — deferred, pre-existing pattern
- [x] [Review][Defer] A coach-tier request that never settles leaves tier-gated tools hidden with no message (no request timeout) [src/frontend/src/stores/auth.store.js:34-41] — deferred, pre-existing (no axios timeout app-wide)

## Dev Notes

### Current state of files to change (read before editing — verified at 88363781)

- **`pages/coach/CoachPlayerDetailPage.vue`** (180 lines): back button, heading from `store.exposure.playerName` with per-player cache (`:93-114`, deferred-156), body gated by `!loading && !pageError` where `pageError = store.error || store.coachContributionsError` (`:140-142`). Three cards: neglected skills (from `store.neglectedCodes`), contributions (wrapped `CoachContributionSection`, empty-state key by `contributionEmptyStateKind`), timeline (`:key="playerId"`). `loading` starts `true` deliberately (`:144-149`, prevents a double timeline fetch). `loadGeneration` guard; watcher on `route.params.playerId`. **Preserve all of that.**
- **`pages/player/PlayerDevelopmentDashboardPage.vue`** (303 lines): PLAYER-only route. Coach branches at `:18-22,29-31,36-47,61-76,92-99,119-131,188-196,204-210,241-252,295-302`; parent branches `:12,189,253-255`. Reports card `v-if="tierLoaded"` (`:102`) is never true for a player — live bug fixed by AC6.
- **`pages/parent/ParentDevelopmentPortalPage.vue`** (229 lines): the parity reference — radar (readonly), narrative, packs, contributions, exposure bar, neglected banner, reports (`is-coach=false`), timeline. Reads `store.error` in `pageError`; must keep working unchanged (Task 1.1 keeps legacy writes).
- **`stores/development.store.js`** (366 lines): only `fetchExposure`, `fetchTimeline`, `fetchCoachContributions` have seq guards + clear-before-fetch; the rest share `error` and clear it at start.
- **`components/development/PerformanceReportsPanel.vue`**: fetches in `onMounted` only; `canGenerate` = `coachTier != null && != 'SCOUT'`; empty state wrongly checks `store.error`; never passes `playerName` to the dialog.
- **`components/development/SkillsRadarAssessmentPanel.vue`**: calls `developmentStore.submitRadarAssessment`; `min-width: 600px`; every 403 shown as the tier-upsell message (AC5).
- **`components/development/GenerateReportDialog.vue`**: notifies `store.error` on failure, which `generateReport` never sets (AC5).
- **`components/development/SluTargetEditor.vue`**: sends every skill, `null` for blanks; the server deletes on `null` (AC4 data-loss guard).
- **`api/development.api.js`**: all needed calls exist except `getNeglectedSkills`. `getSkillTrends` exists with no caller (out of scope).

### Endpoint authorization (verified — nothing new to secure)

| Endpoint | `@PreAuthorize` | Service-level relationship check |
|---|---|---|
| `/radar/display` | coach or owner | `RadarDisplayService.java:40` `IfCoach` (wide) |
| `/narrative`, `/exposure`, `/slu/skill-trends` | coach or owner | `SluDashboardService.java:44,103,133` (wide) |
| `/neglected-skills` | coach or owner | `SluTargetService.java:56` (wide) |
| `/slu/coach-contributions` | coach or owner | `SluContributionService.java:37` (wide) |
| `/reports` GET | coach or owner | `ReportGenerationService.java:294` (wide) + own-reports filter |
| `/reports` POST | coach | `:167` **narrow** + tier ≠ SCOUT |
| `/targets` GET / PUT | coach | `SluTargetService.java:29` wide / `:38` **narrow** |
| `/radar/entries` GET / POST | coach | `RadarAssessmentService.java:97` wide + tier / `:48` **narrow** |
| `/radar/correlation` | coach | `DevelopmentCorrelationService.java:52` wide + ACADEMY |
| `/radar/preferences` GET / PUT | coach | **none** — G19, fixed in 12.10. Preferences are keyed per (coach, player) so no cross-coach data leaks; acceptable to wire now. |

### Facts that make this non-obvious

- `authStore.role` is a single string; a user is never both COACH and PLAYER/PARENT in the UI (`auth.store.js:16-18`). That is why the player dashboard's coach branches are dead, not "rarely used".
- `FeatureGatedException` maps to **403** (`ApiAdvice.java:388-390`), indistinguishable by status from a relationship 403; `fetchCorrelationInsights` silently swallows 403 (`development.store.js:186-190`). Don't call tier-gated endpoints for the wrong tier instead of relying on that.
- The 12-4 story *deliberately excluded* radar/exposure/reports from this page as scope creep (12-4 AC4). 12.9 reverses that on the epic's authority (G16); don't be confused by 12-4's comments.
- Narrative copy is neutral ("{skill} exposure increased …", `en-US/index.js:834-837`), fine for a coach.
- Expect 12.10 (D5) to introduce one coach-access window across these reads; build sections so an "access expired"/403 on any of them degrades per AC2, which is what 12.10 will rely on.
- **Telling 403s apart:** `ErrorDto` is `{ helpCode, errorMsg: { errorKey, message } }`. `errorKey` is the message key (`security.featureGated` for tier gates, the `SecurityError` code / `security.opForbidden` for relationship denials); `helpCode` is a per-error log reference minted by `ErrorLog.logError`. Pre-existing, out of scope but noted for a deferred item: `sessionBuilder.store.js:151-154` tests `data.helpCode === 'security.featureGated'`, which looks like it can never match a real response (its spec mocks the body in that shape).
- **How reachable is AC3's A→B switch?** The only link into this route is the roster (`MyPlayersPage.vue:126`), a different route, so a real param-only switch needs a URL edit. AC3 is still required: it is cheap, the same store fixes protect the parent portal's child switcher (a real param-only path), and unmount/remount reuses the same global store state.
- Neglected flags: at most one open flag per (player, skill) is guaranteed by the partial unique index `idx_neglected_flags_open` (`V138__baseline_schema.sql:3286`), so `skillCode` is a safe `:key`. `detectedAt` is the processing run's time, not a session time.

### Architecture compliance

- Frontend only. `<script setup>`, `async/await` (no `.then()`), all API calls in `src/api/*.api.js`, shared state in Pinia, all text via vue-i18n in three locales, Prettier mandatory (the gate lives in `.github/actions/` and blocks `build-and-push`). Player ids stay raw route-param **strings** (Tsids > `MAX_SAFE_INTEGER`; deferred-147/-148).
- Never render server text with `v-html`.

### Testing requirements

- Vitest + `@vue/test-utils`, `happy-dom`, explicit `vitest` imports, specs in `__tests__/` beside the code. Coach-page spec precedent: `vi.mock('src/boot/axios')` + `createTestingPinia({ stubActions: false })` + real memory router + Tsid `'893573203704173564'` (see the spec's header comment for why not `vi.mock('development.api.js')`).
- Route the axios mock by URL (one `api.get.mockImplementation((url) => …)`) — the page now issues ~10 GETs; order-based `mockResolvedValueOnce` chains will be brittle.
- No backend tests (no backend change). Do not run `mvn verify` (CI is the gate).

### Previous-story intelligence (12.8, 12.7, 12.6, 12.4)

- 12.4: stale-player data and the clear-before-fetch fix; `loading` must start `true`; contributions empty state must be page-level; error text must be translated, read from `errorMsg.message`.
- 12-1 (`CoachReviewsPage.vue`) / 12-7: five-state UI convention. 12.6/12.8: locale parity specs fail on non-literal `t()` keys; keep templates free of bare strings (eslint `vue/no-bare-strings-in-template`).
- deferred-152 code review: an empty state must not render while a fetch is in flight or failed (`ParentDevelopmentPortalPage.vue:70-83`) — apply to every new card.

### Git intelligence

Recent: `88363781` (12.8): frontend added real drills/ratings rendering to `PlayerTimelinePanel.vue` (+46) with its spec and i18n, and a one-line comment change in `development.store.js`. `05f81d6d` (12.7): new `SessionRecapDialog.vue`, changes to `WrapUpSequence.vue`, `SessionPrepCard.vue`, `CoachCommandCenterPage.vue`, `PlayerTimelinePanel.vue`, and `GenerateReportDialog.vue` (+4, the recap-sharing notice — this story edits the same file's `submit()` catch, AC5; keep the notice). No overlap with this story's other files. No migration involved.

### Story-review response (2026-10-23)

Each finding in `story-review.md` was re-checked against HEAD `88363781` before acting.
- Applied: #1 targets-wipe guard (AC4, confirmed `SluTargetService.java:39-45` + `SluTargetEditor.vue:71-78`); #2 dialog error plumbing (AC5; confirmed both dialogs, and added the `errorKey` vs `helpCode` discriminator after checking `ApiAdvice`/`ErrorDto`); #3 preference sync (AC12); #4 vacuous player spec (Task 5.2); #5 loading/error flags + narrative wrapper (AC1, AC2, Task 1.1); #6 date formatting (AC7, confirmed `quasar.config.js:138`); #7 tier promise + tier failure (AC4, Task 3.1); #8 spec fixtures (Task 5.1, 1.5); #10 SCOUT rendering (AC4); the "12.6 five-state" attribution and git-intelligence text.
- Partly rejected: #9 "duplicate open flags per skill possible pre-V49" is a **false positive** — `V138__baseline_schema.sql:3286` has the partial unique index on open flags. Its other points (sort on parsed dates, max-target semantics) were applied to AC7.
- Kept as information, not a defect: #11 (A→B reachability), see Facts.

### Out of scope (do not implement)

- `/slu/skill-trends` direction badges (no caller; a separate feature, not a parity gap).
- Relationship check on `/radar/preferences` (G19), access-window unification (G20/G21/G24), lapsed roster (G22) — all 12.10.
- Session-pack tracker from the parent portal (parent-only, and coaches already see their own packs elsewhere).
- Changing `CoachContributionSection`, `SkillsRadarChart`, `PlayerTimelinePanel` internals.

### Project Structure Notes

Touch: `src/frontend/src/pages/coach/CoachPlayerDetailPage.vue`, `pages/player/PlayerDevelopmentDashboardPage.vue`, `stores/development.store.js`, `api/development.api.js`, `components/development/{PerformanceReportsPanel,SkillsRadarAssessmentPanel,GenerateReportDialog}.vue` (+ `GenerateReportDialogSpec.js`), new `components/development/CoachRadarCard.vue` (or `composables/useFlushableDebounce.js`), `i18n/{en-US,de-DE,fr-FR}/index.js`, the three specs, `docs/dev-docs/development/index.html`. No conflicts with the unified structure.

### References

- [Source: _bmad-output/planning-artifacts/skillars-epics.md#Story 12.9] and Epic 12 "Gap → story map" G16-G18
- [Source: src/frontend/src/pages/coach/CoachPlayerDetailPage.vue:1-180]
- [Source: src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue:1-303]
- [Source: src/frontend/src/pages/parent/ParentDevelopmentPortalPage.vue:1-229]
- [Source: src/frontend/src/stores/development.store.js:57-320]
- [Source: src/frontend/src/router/routes.js:163-173,284-288]
- [Source: src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java:23-111]
- [Source: src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillProcessor.java:55-85]
- [Source: _bmad-output/implementation-artifacts/skillars-12-4-coach-facing-player-detail-page.md AC4, AC6, Task 4.0]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5.5 (claude-sonnet-5-5)

### Debug Log References

- Full Vitest suite: 60 files / 554 tests green; `npx prettier --check src` and `npx eslint src` clean. No backend change, so no Maven run (CI is the full gate).
- Mutation sanity (Task 5.4): removing the seq guards from `fetchRadarDisplay` and `fetchNarrative` turns the new "late A response never overwrites B" spec red (1 failure), restored afterwards.
- `i18nTemplateKeyResolutionSpec` keeps a line-number exception list; the page's `$t(noContributionsKey)` moved from `:59` to `:162` and the entry was updated.

### Implementation Plan

- Store: per-section error refs (+ loading flags) and clear-before-fetch + per-action seq guards on every player-scoped fetch the coach page issues; legacy `store.error` writes kept so the parent portal and old specs behave as before. `saveTargets`/`submitRadarAssessment` rethrow a failed *write* (a failed post-save targets re-fetch lands in `targetsError`); `saveRadarPreferences` returns a boolean and no longer writes `radarPreferences`; `fetchRadarPreferences` returns the loaded value (`null` when superseded); `generateReport` no longer writes `reportsError`; new `fetchNeglectedSkills`.
- `CoachRadarCard.vue` owns the radar + preference fetches, the flushable debounce (moved out of the player dashboard, not copied), the baseline toggle, the `pagehide` listener and the AC12 "dirty since load" sync rule.
- `CoachPlayerDetailPage.vue`: core trio stays in the gated `Promise.all`; secondary fetches start in parallel without feeding the core `loading` flag; one `fetchCoachTier()` promise awaited by every load, with tier-gated fetches started in the same synchronous block that marks the tier settled (and skipped for a superseded load). Tier `null` after settling = "unknown": all tier-gated tools hidden, one message.
- Player dashboard stripped to the PLAYER path (radar read-only via `/radar/display`, reports card unconditional with `:is-coach="false"`).

### Completion Notes List

- All 12 ACs implemented, frontend only (no file under `src/main/java`, `src/main/resources`, `src/test/java` touched).
- Test additions: `developmentStoreSpec` (+25 cases: seq guards per action, clear-at-start, own-error isolation, preferences/targets semantics), `CoachPlayerDetailPageSpec` (11 -> 39 cases covering every item in Task 5.1), `PlayerDevelopmentDashboardPageSpec` (action-spy assertions, not vacuous URL ones), new `PerformanceReportsPanelSpec`, new `SkillsRadarAssessmentPanelSpec`, `GenerateReportDialogSpec` (+1).
- Two pre-existing store assertions were changed deliberately because the story changes the behaviour they pinned: `saveTargets` now rethrows a failed save, and `generateReport` no longer writes `reportsError`.
- Not verified: no live-browser run of the coach page against a real backend; coverage is Vitest with a mocked axios. The radar/bar/trend charts are stubbed in the coach-page spec (Chart.js needs a canvas that happy-dom lacks).
- Observed, not changed: `development.radar.submitError` and `common.errorGeneric` are no longer referenced by the touched components; left in the locale bundles.
- If `fetchExposure` fails after a successful targets save, the page-level gate trips (exposure is core data by AC2's definition).

### File List

- src/frontend/src/api/development.api.js
- src/frontend/src/stores/development.store.js
- src/frontend/src/stores/__tests__/developmentStoreSpec.js
- src/frontend/src/components/development/CoachRadarCard.vue (new)
- src/frontend/src/components/development/GenerateReportDialog.vue
- src/frontend/src/components/development/PerformanceReportsPanel.vue
- src/frontend/src/components/development/SkillsRadarAssessmentPanel.vue
- src/frontend/src/components/development/__tests__/GenerateReportDialogSpec.js
- src/frontend/src/components/development/__tests__/PerformanceReportsPanelSpec.js (new)
- src/frontend/src/components/development/__tests__/SkillsRadarAssessmentPanelSpec.js (new)
- src/frontend/src/pages/coach/CoachPlayerDetailPage.vue
- src/frontend/src/pages/coach/__tests__/CoachPlayerDetailPageSpec.js
- src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue
- src/frontend/src/pages/player/__tests__/PlayerDevelopmentDashboardPageSpec.js
- src/frontend/src/i18n/en-US/index.js
- src/frontend/src/i18n/de-DE/index.js
- src/frontend/src/i18n/fr-FR/index.js
- src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js
- docs/dev-docs/development/index.html
- _bmad-output/implementation-artifacts/sprint-status.yaml
- _bmad-output/implementation-artifacts/skillars-12-9-coach-player-workspace-parity.md

### Change Log

- 2026-10-23: Implemented story 12.9 (coach player workspace parity, G16-G18). Status in-progress -> review.
