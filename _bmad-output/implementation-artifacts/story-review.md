# Story Review: skillars-12-9-coach-player-workspace-parity

- Audit date: 2026-10-23
- Real HEAD (checked via `git rev-parse` / `git log -1`): `88363781 feat(development): timeline enrichment - drills and ratings on SESSION_COMPLETED, HOMEWORK_ASSIGNED producer (12.8) (#293)`
- All citations were checked against this HEAD, not the SHA the story claims (it also says 88363781; it was still re-checked independently).
- Method: 4 parallel read-only layers, then my own adversarial re-read (Step 4b) of the highest-impact findings.

## 1. Citation verification (Layer 1)

All 18 citation groups: **MATCH** at HEAD, with only one-line offsets (e.g. the player-dashboard reports card `v-if="tierLoaded"` is line 102 per the story; the preceding comment is 101). Groups covered: NeglectedSkillProcessor:55-85, PlayerOwnershipGuard:33-34, PlayerDevelopmentDashboardPage (102, 151-220, 241-302), CoachPlayerDetailPage (22-24, 93-114, 140-149), development.store.js (error clears 58/76/96/106/127/151, fetchExposure 69-93, correlation 403 swallow 186-190), ParentDevelopmentPortalPage (70-83, 177-182), routes.js (163-173, 284-288), auth.store.js:16-18, PerformanceReportsPanel (53, 57-62), SkillsRadarAssessmentPanel:3, RadarAssessmentHistoryList:4, DevelopmentCorrelationPanel:47, CoachPlayerAuthorizationService:23-24, all service-level relationship-check lines in the authorization table, ApiAdvice:388-390, en-US narrative copy, api file contents, RADAR_ALLOWED_TIERS (exists both backend and in the player page).

## 2. Ledger & precedent attribution (Layer 2)

| Claim | Verdict |
|---|---|
| 12-4 HIGH finding / Task 4.0 / AC4 exclusion / AC6 / loading-starts-true / page-level contributions empty state / errorMsg.message | CONFIRMED |
| deferred-156 (name cache), deferred-152 (empty state while in flight), deferred-147/-148 (Tsid as string) | CONFIRMED |
| Epic 12.9 / G16-G18 / G19-G25 -> 12.10 / Converge #12 | CONFIRMED. Story is a strict superset of the epic's 12.9 ACs; nothing dropped. |
| Prettier gate in `.github/actions/`, blocks build-and-push | CONFIRMED |
| Removing `/neglected-skills` touches resource, service, contract, ITs | CONFIRMED (also touches `SessionPrepResource`) |
| "12.6 five-state convention" | **WRONG attribution.** Not in the 12-6 story; it comes from 12-1 (`CoachReviewsPage.vue`) and 12-7. (12.8's story repeats the same error.) |
| Git intelligence: 12.7/12.8 touched "comments only" on the frontend | **WRONG.** 12.8 added real logic to `PlayerTimelinePanel.vue` (+46) and only a comment to `development.store.js`; 12.7 did not touch the store but touched `GenerateReportDialog.vue` (which 12.9 uses), `PlayerTimelinePanel.vue`, etc. No file conflict with 12.9's list, but the description is inaccurate. |
| Dev-docs contain "no frontend caller" statements | Not found (the phrase is only in the epic). AC7 says "if present", so harmless. |

## 3. Mechanistic claims (Layer 3, spot-verified by me)

- **AC2 / Task 1 premise "every store fetch writes and clears the one shared `store.error`": PARTIAL.** `fetchSkillDefinitions`, `fetchNarrative`, `fetchTargets`, `fetchRadarEntries`, `fetchRadarDisplay`, `fetchExposure` do. `fetchCorrelationInsights` writes but does not clear; `fetchRadarPreferences` neither clears nor writes (swallows into `{selectedSkillCodes: []}`); `fetchReports` already uses `reportsError`. Conclusion (need per-section errors) still holds. The story's line list (58,76,96,106,127,151) is fine as clear sites.
- Seq guards exist only on exposure/timeline/coach-contributions: CORRECT. `fetchSkillDefinitions` has no guard.
- Player dashboard radar permanently empty, reports card never renders (`tierLoaded` only set in coach branch): CORRECT. Route is PLAYER-only; only link is `MainLayout.vue:366`.
- `/radar/display`, `/reports` GET, `/narrative`, `/exposure`, `/neglected-skills`, contributions, timeline admit owner via `playerOwnershipGuard` (parent **or** self-player); `/targets`, `/radar/entries`, `/radar/preferences`, `/radar/correlation`, POST `/reports` are coach-only: CORRECT; authorization table matches.
- Narrow (4) vs wide (12) status lists; both throw `MISSING_RIGHTS` -> 403: CORRECT.
- Coach reports filtered to own: CORRECT. Preferences keyed (coach, player): CORRECT.
- `/neglected-skills`: returns only `skillCode` + `detectedAt` (Instant), unresolved flags, **no ordering**: story's "oldest first" is implementable but needs a client sort; names must come from skill definitions.
- Correlation 403 swallow and null-as-error panel behaviour: CORRECT.

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

1. **HIGH - Target-editor save can wipe all targets.** `SluTargetService.setTargets` deletes a target whenever `weeklyTargetSlu == null` (`SluTargetService.java:39-47`); `SluTargetEditor.onSave` sends `localTargets[code] ?? null` for every skill, and the editor does not re-sync while its dialog is open. The story makes targets a non-awaited secondary fetch cleared to `[]` (Tasks 1.2/2.2). Opening the editor before targets arrive, or after the GET failed, then saving, deletes the coach's real targets. Also `saveTargets` swallows a failed re-fetch (`development.store.js:115-123`) and reports success. Story needs: "Set targets" disabled unless targets loaded successfully (needs `targetsLoading`/`targetsError`). AC2 also has no card to host a targets error message since the editor is a dialog.
2. **HIGH - AC5 is wrong about write-failure messages.** `GenerateReportDialog.vue:82` notifies `store.error ?? generic`, but `generateReport` sets `reportsError`, never `error` (`development.store.js:209-218`) -> generic/stale message. `SkillsRadarAssessmentPanel.vue:172-177` maps *every* 403 to "requires Instructor tier" (`submitErrorFeatureGated`) and reads `data.message` not `errorMsg.message`, so AC5's own DISPUTED-booking scenario shows an upgrade message. Neither dialog is in the AC10 fix list. Additionally `PerformanceReportsPanel.vue:18-27` replaces the reports list with a banner whenever `reportsError` is set, so a failed *generate* hides existing reports.
3. **HIGH - Radar preference sync vs non-awaited fetch.** Player page syncs `localSelectedSkillCodes` once after `await Promise.all` (`PlayerDevelopmentDashboardPage.vue:263`, comment 199-201 deliberately avoids a watch). With preferences non-blocking (Task 2.2) a "port verbatim" card has no sync point; a watch risks a late response overwriting a fresh click. And `saveRadarPreferences` writes `radarPreferences` unconditionally on success (`development.store.js:171-175`), outside Task 1.2's guard, so a flushed player-A save resolving after B's fetch overwrites B's prefs. Story should have `fetchRadarPreferences` return the value and guard/remove the store write.
4. **HIGH - Task 5.2 / AC6 test is vacuous as written.** `PlayerDevelopmentDashboardPageSpec.js` uses `createTestingPinia` with stubbed actions and `shallow: true`, so no axios call occurs; asserting on URLs cannot fail. Must assert on action spies or use `stubActions: false`.
5. **MEDIUM-HIGH - Missing loading/error state for several AC2 cards.** `fetchRadarPreferences` swallows errors and Task 1.1 adds no `preferencesError`; no loading flags exist for narrative/targets/preferences, so empty states would flash during fetch (the deferred-152 bug the story cites). `SluNarrativeSummary` renders nothing when empty (`v-if="narrative.length"`), contradicting "none collapses to nothing".
6. **MEDIUM-HIGH - AC7 "locale-formatted via Quasar date" gives English months in de/fr.** Quasar `lang` is commented out (`quasar.config.js:138`); suggest numeric format or `Intl.DateTimeFormat` with the i18n locale.
7. **MEDIUM - Tier fetch fragility.** `fetchCoachTier` has no cache/dedupe and sets `coachTier = null` on error (`auth.store.js:34-40`), so a failed fetch turns an ACADEMY coach into a teaser/hidden-tools view with no error. Task 3.1's "once" and "re-run on switch" don't say to share one awaited promise; a switch during the in-flight tier fetch skips B's gated fetches permanently.
8. **MEDIUM - Existing coach spec fixture will break.** `mockApiGetByUrl` resolves unmatched URLs to `[]` (`CoachPlayerDetailPageSpec.js:90-96`); `radarEntries = []` makes `RadarAssessmentHistoryList` (`radarEntries.entries.length`, line 4) throw. `developmentStoreSpec.js` `vi.mock` lacks `getNeglectedSkills`.
9. **MEDIUM - Neglected-skills semantics.** Sort on parsed dates, not ISO strings; duplicate open flags per skill are possible pre-V49 (use a composite key); `detectedAt` is the weekly job's run time; flags use the max target across all coaches, so a coach may see a skill neglected against another coach's target.
10. **LOW-MEDIUM - AC1 vs AC4 SCOUT contradiction** (radar history in the order list vs hidden for SCOUT; needs a stated placeholder).
11. **LOW (informational)** - Param-only A->B navigation is only reachable by editing the URL (sole link: `MyPlayersPage.vue:126`); AC3 machinery is mostly defensive; unmount flush is the likelier real trigger.

## 5. What did not survive / was downgraded

- Layer 2's "dev-docs 'no frontend caller' false" - downgraded to harmless because AC7/Task 6.1 say "if present".
- Layer 3's M12 (parent portal depends on `store.error`) - not a defect: the story explicitly keeps legacy writes.
- Layer 4 finding 11 (unreachable param switch) - kept only as informational, not a defect.
- No layer-raised finding was killed outright. Not independently re-read by me: findings 5 (tier race), 9 (duplicate flags, max-target), 10; findings 1, 2, 4, 6 and the store details in 3 were re-read in source by me (SluTargetService, SluTargetEditor, store save/generate functions, GenerateReportDialog, SkillsRadarAssessmentPanel, quasar.config, auth.store, both specs' fixtures). `PlayerDevelopmentDashboardPage.vue:263` and the `ParentDevelopmentPortalPage` details rest on the layer agents' reads.

## 6. Recommendation

- Citations and authorization table: high confidence, independently re-verified by one agent each; usable as-is.
- The story is **not ready as written**: fix items 1-4 before dev (targets-wipe guard, AC5 error plumbing incl. the two dialogs, radar-preference sync design, vacuous player-spec assertion). Items 5-9 should be folded into ACs/tasks. Also correct the "12.6 five-state" and git-intelligence notes.
- Confidence differs by claim: items 1, 2, 4, 6 are confirmed from source; 3 is confirmed for the store write and inferred for the page flow; 5, 7, 9 rest on one agent's read.
