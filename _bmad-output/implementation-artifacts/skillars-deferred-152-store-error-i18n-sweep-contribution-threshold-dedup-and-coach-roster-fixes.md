# Story: Store-Wide Error-Message i18n Sweep, Coach-Contribution Threshold Dedup, Report-Generation SLU-Clock Fix, and Coach-Roster Hygiene

**Status:** ready-for-dev

**Scope Level:** Medium (nine independently-verified, currently-open, mechanically-closable items; no schema change; no product decision beyond two small in-scope judgment calls noted in Dev Notes.)

---

## User Story

As **a coach using Skillars in a non-English locale, and the developer maintaining the `development`/`booking`/`payment` modules' error-handling and roster-scoring consistency**, I want nine independently-verified, currently-open defects and gaps fixed — a repo-wide sweep closing the remaining hardcoded-English fallback messages in `development.store.js`/`booking.store.js` (plus `errorHandler.js`'s own hardcoded status-code fallbacks, the shared helper the sweep routes through), a de-duplicated 30%-contribution-threshold between `CoachContributionSection.vue` and `CoachPlayerDetailPage.vue` with an unambiguous empty-state message, the third and final SLU-only coach-access gate (in `ReportGenerationService`) widened to the same merged signal the roster and timeline already use, an efficiency fix removing two needless full-entity hydrations that exist only to read `.size()`, a day-rounding/off-by-one fix on the roster's dormancy boundary, a null-safety guard on the roster's player-name lookup, and three small test/UI-completeness gaps in the coach-branding feature (no end-to-end test pinning its id-space fix, an unrendered fetched logo preview, and a stub assertion that never exercises what it claims to) — so that **a French- or German-speaking coach sees a translated error on every one of these eleven store call sites instead of an English literal; the 30% threshold that decides whether the contribution panel's empty state shows cannot silently drift between its two copies ever again; a Quick-Complete-only coach can finally generate a report for a player they've actually trained, the same fix already applied to that coach's timeline and roster; two hot paths stop materializing full JPA entity graphs to read a count; the dormant badge and the recency score agree at the boundary instead of disagreeing by up to a day; a missing player-profile row degrades gracefully instead of risking a `NullPointerException` deep in a `Map.of`-style response builder; and the coach-branding feature's test suite and UI are both honest about what they cover and render.**

---

## Source

All nine items are mined from `_bmad-output/implementation-artifacts/deferred-work.md`, each independently re-verified against real HEAD `8d537200` (not taken on the ledger's word — see the Dev Notes correction for each item where the ledger's own citation had drifted). Two items considered from the same sections were excluded after re-verification found them stale/already closed — see "Explicitly NOT picked up" below.

| AC | `deferred-work.md` section | Line(s) |
|----|------|---------|
| 1 | "Deferred from: code review of skillars-12-4-coach-facing-player-detail-page (2026-10-16)" — hardcoded-English error-message bullet | `:3810` |
| 2 | Same section — 30%-threshold duplication bullet + "no coach contribution data" message-ambiguity bullet | `:3811`, `:3813` |
| 3 | "Deferred from: pre-implementation story review of skillars-12-3-session-prep-card-and-timeline-access-clock-fix (2026-10-15)" — third SLU-only gate bullet | `:3802` |
| 4 | "Deferred from: code review of skillars-12-3-session-prep-card-and-timeline-access-clock-fix (2026-10-15)" — `.size()`-for-a-count bullet | `:3806` |
| 5 | "Deferred from: code review of skillars-12-1-coach-player-roster-and-attention-summary (2026-10-15)" — `Duration.toDays()` truncation bullet | `:3786` |
| 6 | Same section — `playerName` null bullet | `:3787` |
| 7 | "Deferred from: code review of skillars-deferred-151-coach-ui-gaps-handler-hygiene-null-roles-fix-time-gate-and-ci-ruleset-hardening (2026-10-14)" — no-E2E-IT bullet | `:3774` |
| 8 | Same section — `ProfilePage.vue` unrendered `logoSignedUrl` bullet | `:3775` |
| 9 | Same section — weak-spec-assertion bullet (the `EditCoachBrandingDialogSpec.js` half only — see Dev Notes) | `:3776` |

### Explicitly NOT picked up (re-verified, excluded — do not re-add to this story)

- **`CoachSessionPackTiersPageSpec.js:72`'s weak `not.toContain('deactivate')` assertion** — the bullet at `deferred-work.md:3776` names two files. Re-checked against real HEAD: this file no longer contains that assertion at all. Its "shows the current active offer, a single withdraw control, and a read-only history list" test (`:69-81`) now asserts on real `payment.myTiers.*` i18n keys and counts exactly one `withdrawOffer` button — a different, already-fixed test than the one the ledger describes. **Stale citation — already closed, not by a story that documented it.** Only the `EditCoachBrandingDialogSpec.js` half of that bullet is still open (AC9 below).
- **`CoachRosterService`'s unbounded per-player fan-out and roster-membership-width mismatch** (`deferred-work.md:3784-3785`) — both explicitly re-affirmed as deliberately still-open in their own text ("Revisit when either a real roster exceeds ~100 players…", "the roster-membership-width half is still open for a future story") and both need a product/architecture decision (a pagination contract change; which of 12 roster-membership statuses to narrow), not a mechanical fix. Left alone per their own stated disposition.
- **`dormantAfterDays` doubling as the recency normalizer** (`deferred-work.md:3788`) — speculative ("Consider decoupling… if the knob is ever exposed in an admin UI"), not a concrete defect; no admin UI exposing this knob exists today. Left alone.
- **Abandoned wrap-up step 5 stranding a booking in `COMPLETED_PENDING_CONFIRMATION`** and **recap/`voice_note_text` surviving GDPR erasure** (`deferred-work.md:3796-3797`, code review of skillars-12-2) — both real, but both need a product decision this story should not make silently: the first between "admin force-complete" vs. "sweeper auto-confirms after timeout" (different data consequences for what the completion record ends up containing), the second on how `voice_note_text` — a pre-existing gap on the same table — should be handled, since the ledger itself says to "settle alongside" it rather than fix the new columns in isolation. Left open for a future story once those decisions are made.
- **Orphaned `SessionBuilderPage.vue`** (`deferred-work.md:3780`, "coach UI CRUD audit") — **re-verified and found already closed, not yet annotated in the ledger.** `SessionPrepCard.vue:59` now renders a `RouterLink` to `{ name: 'coach-session-builder', params: { bookingId: props.bookingId } }`, and `SessionPrepCard` is itself rendered from `CoachCommandCenterPage.vue` (grep-confirmed, both reachable, no `__tests__` false positive). This was a side effect of `skillars-12-3`'s own AC2 ("Quick note field for this session's plan" interpreted as a link to this exact route — see that story's Dev Notes), not something `skillars-12-3`'s own ledger annotation called out. Annotated in `deferred-work.md` as closed by this story's drafting pass rather than left for a future pass to re-discover.
- **`requireCoachPlayerRelationshipIfCoach`'s missing self-owner arm, now in five copies** (`deferred-work.md:3812`) — the bullet's own text confirms it is "not currently reachable" (no production path grants a user both `ROLE_COACH` and a self-owned `PlayerProfile`) and explicitly scopes the fix to "if a dual-role path is ever introduced." Not picked up; still speculative.
- **`playerName` traveling in the URL query string** and **no param constraint on `coach/players/:playerId`** (`deferred-work.md:3814-3815`) — both explicitly described as deliberate-design-decision-recorded-not-reopened (the first) or low-value-alone pending-a-repo-wide-pass (the second). Left alone per their own text.
- **Three orphaned, any-role (not coach-specific) backend features** from the "bmad-quick-dev coach UI-gap scan" section (`deferred-work.md:3766-3769`: disputes raise/view, review-flag/messaging-report endpoints) — explicitly out of scope for that pass and not coach-specific; building UI for them is a product decision (should disputes/flagging/reporting exist at all in the current UI, and for which roles) this story should not make as a drive-by.

---

## Acceptance Criteria

1. **Store-wide hardcoded-English error-message sweep.** `errorHandler.js`'s `parseApiError` (`:86-100`) routes its five status-code-fallback messages (`'Bad request'`, `'Unauthorized'`, `'Forbidden'`, `'Not found'`, `'Server error'`/`'An error occurred'`) through `i18n.global.t(...)` using the existing `defaultErrorKeyByStatus` map (`:28-33`) and `error.unknown`, mirroring the `error.network` branch immediately above (`:58`) which already does this — not introduced new keys where an existing bundle key already covers the status. `development.store.js`'s ten remaining `err?.response?.data?.message ?? '<hardcoded English>'` call sites (`fetchNarrative:93`, `fetchTargets:103`, `saveTargets:113`, `fetchRadarEntries:124`, `submitAssessment:136`, `fetchRadarDisplay:148`, `savePreferences:168`, `fetchCorrelationInsights:180`, `fetchReports:195`, `generateReport:208`) and `booking.store.js:293`'s `packsError` fallback are all replaced with `getErrorMessage(err)` from `src/utils/errorHandler.js` (new import in both files) — none of these eleven sites reads `err?.response?.data?.message` directly again, since that field never exists on the real `ErrorDto` shape (confirmed: `ErrorDto.java` has no top-level `message` field, only `errorMsg.message`).

2. **30%-contribution-threshold de-duplication and unambiguous empty state.** `CoachContributionSection.vue` exports the `30` threshold (or a `hasTopAttributions(contributions)` predicate) so `CoachPlayerDetailPage.vue`'s own `hasTopContributions` computed (`:101-111`) imports and reuses it instead of re-implementing the identical group-by-skill-then-`>=30` logic a second time. A new boundary test asserts both the component's `topAttributions` and the page's `hasTopContributions` agree at exactly 30%. `coach.playerDetailNoContributions` (shown whenever every per-skill top contributor is under 30%, even when real contribution data exists below that line — `CoachPlayerDetailPageSpec.js:150-165` already demonstrates this exact case with a genuine 10% contribution) is split into two distinct, correctly-scoped messages in all three locale bundles: one for `coachContributions.length === 0` (genuinely no data) and one for "data exists but none cleared the 30% display threshold" — the page selects between them instead of showing the same string for both cases.

3. **Third and final SLU-only coach-access gate fixed.** `ReportGenerationService`'s report-generation guard (`:145-148`, currently `sluRepository.findLastSessionDate(playerId, coachId)`) is replaced with a call to the existing `CoachPlayerLastSessionResolver.resolve(playerId, coachId).lastSessionDate()` (`CoachPlayerLastSessionResolver.java:27`, already introduced by `skillars-12-3` and already consumed by `CoachRosterService`/`TimelineQueryService`/`SessionPrepService`) — the identical merged SLU-row+coach-scoped-timeline-event signal those three surfaces already use. A Quick-Complete-only coach who can already read their own timeline, roster entry, and Session Prep Card for a player can now also generate a report for that same player, closing the last of the three independently-discovered instances of this same narrow signal.

4. **`.size()`-for-a-count efficiency fix.** A new `countByPlayerIdAndResolvedAtIsNull(Long playerId)` method is added to `NeglectedSkillFlagRepository` (alongside the existing `findByPlayerIdAndResolvedAtIsNull`, not replacing it — other callers, if any, keep the entity-returning method). Both existing call sites that discard the hydrated entities to read only `.size()` — `CoachRosterService.java:131` and `SessionPrepService.java:80` — are switched to the new count query.

5. **Dormancy-boundary day-rounding fix.** `RosterAttentionCalculator.compute`'s `dormant` flag (`:58`, currently `signals.daysSinceLastActivity() > signals.dormantAfterDays()`) and its recency-score saturation (`:64-65`, `clampedDays`/`recencyNorm`) are reconciled so a player at exactly the configured `dormantAfterDays` threshold is flagged dormant at the same point their recency score saturates to its maximum, rather than one day later. `CoachRosterService.java:129`'s `Duration.between(...).toDays()` truncation-toward-zero is the root cause; fix at whichever of the two call sites (the day calculation or the comparison operator) is more correct without changing the documented "more than N days ago" semantics for any value away from the exact boundary.

6. **Roster `playerName` null-safety.** `CoachRosterService.java:146`'s `namesByPlayerId.get(playerId)` (fed by `findAllById`, which silently returns fewer rows than requested for any player-profile row that's gone missing, per `:80-83`) no longer risks surfacing a `null` `playerName` into `RosterEntryResponse` unguarded — either filter such entries out of the roster response or substitute a safe fallback display value, whichever this project's existing precedent for a missing-but-still-relevant row favors (check `MyPlayersPage.vue`/`ParentDevelopmentPortalPage.vue` for an existing "unknown player" convention before inventing a new one).

7. **Coach-branding id-space end-to-end test.** A new integration test exercises the real `/api/storage/sign/upload` → confirm → `PUT /api/development/coaches/me/branding` flow end-to-end (not a hand-built `FileStorageObject` fixture with `.ownerId(...)` set directly, which is what the existing `ReportGenerationServiceTest` branding coverage does today), pinning that all four call sites this project's `coach_branding` ownership fix depends on — `CoachBrandingResource`'s `getCurrentCoachUserId()` thread-through, `ReportGenerationService.saveBranding`'s `assertOwnership` call, `StorageResource`'s `coach_branding` entry in `OWNER_BOUND_ENTITIES`, and the frontend's `entityId: String(authStore.userId)` — actually agree end-to-end, not just that each one's own unit test passes in isolation.

8. **`ProfilePage.vue` renders its own fetched logo.** The branding section (`:270-291`) — which already fetches `coachBranding.logoSignedUrl` via `getCoachBranding()` (`:554`) — renders that logo (an `<img>` or `q-avatar`, matching whatever visual precedent `EditCoachPhotoDialog.vue`/the existing player-photo-preview convention elsewhere in this codebase uses) in the unlocked, uploaded state, instead of showing only the hex `brandColour` swatch (`:290`) and leaving the logo preview reachable only from inside `EditCoachBrandingDialog`.

9. **`EditCoachBrandingDialogSpec.js`'s dead fetch-response assertion removed or made meaningful.** `EditCoachBrandingDialog.vue:186`'s `await fetch(uploadUrl, {...})` never reads `.ok` (or any other field) off the response, so `global.fetch = vi.fn().mockResolvedValue({ ok: true })` (`EditCoachBrandingDialogSpec.js:21`) tests nothing about response handling. Either the component gains a real failed-upload branch (checking `.ok`, surfacing an error) with a corresponding test asserting the failure path, or the stub is simplified to not imply response-shape coverage that doesn't exist (e.g. `mockResolvedValue({})`, with a comment noting the component doesn't inspect the response). Prefer the former if it can be scoped without widening into a redesign of the dialog's error handling; otherwise the latter, documented honestly.

10. **Testing & regression.** Full targeted backend suite for every touched Java class passes (0 failures), including `ReportGenerationServiceTest` (AC3/AC7), `CoachRosterServiceTest`/`RosterAttentionCalculatorTest` (AC4/AC5/AC6), and `SessionPrepResourceIT` (AC4, via scoped failsafe run per this project's IT convention). Full targeted frontend suite (Vitest) for every touched/new file passes, including `CoachContributionSectionSpec.js`/`CoachPlayerDetailPageSpec.js` (AC2), `development.store.js`'s and `booking.store.js`'s own spec files (AC1), `ProfilePageSpec.js` (AC8), and `EditCoachBrandingDialogSpec.js` (AC9) — apply the `frontend-tests` PR label (or dispatch the job manually) so this is actually checked by CI, matching this project's standing convention for the opt-in Vitest job. No `mvn verify` run locally. After implementation, delete or annotate the nine resolved bullets (plus the already-closed `SessionBuilderPage` bullet found during this story's drafting) in `deferred-work.md` in the same commit.

---

## Tasks / Subtasks

- [ ] Task 1: Store-wide error-message i18n sweep (AC: 1)
  - [ ] Translate `errorHandler.js`'s five hardcoded status-code fallback strings via `i18n.global.t(...)`, reusing `defaultErrorKeyByStatus`/`error.unknown`
  - [ ] Replace all ten `development.store.js` hardcoded-fallback sites with `getErrorMessage(err)`
  - [ ] Replace `booking.store.js:293`'s hardcoded fallback with `getErrorMessage(e)`
  - [ ] Update/add spec coverage asserting a translated (not hardcoded-English) message on a rejected fetch for at least one representative site per file

- [ ] Task 2: 30%-threshold dedup + empty-state message split (AC: 2)
  - [ ] Export the `30` threshold or a `hasTopAttributions` predicate from `CoachContributionSection.vue`
  - [ ] Switch `CoachPlayerDetailPage.vue`'s `hasTopContributions` to import and use it
  - [ ] Add a boundary test (exactly 30%) pinning both call sites agree
  - [ ] Split `coach.playerDetailNoContributions` into a genuinely-empty-data message and a below-threshold message, in all three locale bundles; update the page to select between them

- [ ] Task 3: Report-generation SLU-clock fix (AC: 3)
  - [ ] Replace `ReportGenerationService`'s direct `sluRepository.findLastSessionDate` call with `CoachPlayerLastSessionResolver.resolve(...).lastSessionDate()`
  - [ ] Update `ReportGenerationServiceTest` to cover the Quick-Complete-only-coach case now succeeding

- [ ] Task 4: `.size()`-for-a-count fix (AC: 4)
  - [ ] Add `countByPlayerIdAndResolvedAtIsNull` to `NeglectedSkillFlagRepository`
  - [ ] Switch `CoachRosterService.java:131` and `SessionPrepService.java:80` to the new count query
  - [ ] Confirm existing `CoachRosterServiceTest` assertions on `neglectedCount` and `SessionPrepResourceIT` assertions on `openNeglectedSkillCount` still pass unchanged

- [ ] Task 5: Dormancy-boundary rounding fix (AC: 5)
  - [ ] Fix the day-count/comparison mismatch in `RosterAttentionCalculator`/`CoachRosterService` so `dormant` and recency saturation agree at the exact threshold
  - [ ] Add a boundary test at exactly `dormantAfterDays` days since last activity

- [ ] Task 6: Roster `playerName` null-safety (AC: 6)
  - [ ] Check existing missing-player-row display precedent in the frontend before choosing filter-vs-fallback
  - [ ] Apply the chosen guard in `CoachRosterService.buildEntry`/`getRoster`
  - [ ] Add a test case with a roster-qualifying booking whose `player_profiles` row is missing

- [ ] Task 7: Coach-branding end-to-end IT (AC: 7)
  - [ ] Add a new IT (or extend an existing `@SpringBootTest` IT class in the `development`/`storage` area) exercising sign-upload → confirm → `PUT /branding` for a real coach user
  - [ ] Assert the final `GET /branding` reflects the uploaded logo, pinning all four id-space call sites together

- [ ] Task 8: `ProfilePage.vue` logo preview (AC: 8)
  - [ ] Render the fetched `coachBranding.logoSignedUrl` in the unlocked branding section
  - [ ] Add/extend a `ProfilePageSpec.js` case asserting the logo renders when present and the section degrades gracefully when absent

- [ ] Task 9: `EditCoachBrandingDialogSpec.js` fetch-stub fix (AC: 9)
  - [ ] Decide (per AC9) whether to add a real failed-upload branch or simplify the stub; implement the chosen path
  - [ ] Update/add the corresponding test

- [ ] Task 10: Regression and ledger cleanup (AC: 10)
  - [ ] Run full targeted backend test classes for every touched Java file
  - [ ] Run full targeted frontend Vitest suite for every touched/new file
  - [ ] Delete/annotate the nine resolved `deferred-work.md` bullets plus the already-closed `SessionBuilderPage` bullet in the same commit

---

## Dev Notes

### Why the `errorHandler.js` fallbacks are in scope, not just the two store files

`getErrorMessage`'s only real content is whatever `parseApiError` computes for `message` (`errorHandler.js:166-169`) — routing the two stores through it without first fixing `parseApiError`'s own hardcoded status-code fallbacks (`:86-100`) would just move the same English-literal-to-non-English-user bug one layer deeper, not fix it. The `error.network` branch two lines above (`:58`) already demonstrates the correct pattern (`i18n.global.t('error.network')`) for exactly this file — apply the same pattern to the status-code switch.

### Why `booking.store.js:293` is bundled with the `development.store.js` sweep

The ledger's own text (`deferred-work.md:3810`) explicitly says "`booking.store.js:293` has the same bug and should be included" — same root cause (a nonexistent `data.message` field), same fix (`getErrorMessage`), trivially small addition to an already-in-scope sweep.

### Why AC2's empty-state split, not a reworded single string

The ledger (`deferred-work.md:3813`) offers two options: reword the existing string to be threshold-aware, or distinguish the two cases outright. Distinguishing the cases is the more correct fix — a coach who has real, measured contribution data that simply didn't clear 30% is in a meaningfully different situation from a coach for whom no contribution data exists at all, and conflating them (as today) can read as "no coach has worked with this player," which is false in the first case. This is a small in-scope wording/scoping judgment call, not a product decision requiring sign-off — no existing design doc specifies this copy's two cases separately, so there is nothing to contradict.

### Why AC3 is a one-line call-site swap, not a redesign

`CoachPlayerLastSessionResolver` (`CoachPlayerLastSessionResolver.java:22-68`) already exists, already merges the SLU-row signal with the coach-scoped `player_timeline_events` signal, and is already injected and consumed by `CoachRosterService`, `TimelineQueryService` (via `SessionPrepService`'s own dependency), and `SessionPrepService` itself — all added by `skillars-12-3`. `ReportGenerationService` is the third and final consumer of the old, narrower `sluRepository.findLastSessionDate` signal that `skillars-12-3`'s own pre-implementation story review (`deferred-work.md:3802`) flagged as deliberately out of that story's scope. This story closes it by injecting the existing resolver — no new class, no schema change.

### Why AC4's fix adds a new repository method rather than changing the existing one's return type

`NeglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull` (`:11`) returns `List<NeglectedSkillFlag>` and may have callers elsewhere in the codebase beyond the two named call sites that only need a count — changing its return type would be a breaking signature change for any such caller. Adding a sibling `countByPlayerIdAndResolvedAtIsNull` (Spring Data's standard count-derivation naming) is additive and lets each of the two call sites choose the shape it actually needs.

### Why AC5's fix location is left to the implementer's judgment between two call sites

The off-by-one is jointly produced by `Duration.toDays()` truncating toward zero (`CoachRosterService.java:129`) and a strict `>` comparison against the same (now effectively floor-rounded) value (`RosterAttentionCalculator.java:58`). Fixing only one side (e.g. rounding the day count instead of truncating, or changing `>` to `>=`) resolves the boundary disagreement either way; which one is more correct depends on whether "more than N days" should mean "N+1 full days have elapsed" (favors fixing the comparison) or "closer to N+1 than N elapsed" (favors fixing the rounding) — a judgment call cheap enough to make during implementation without re-opening the original AC4 design, which only specified "more than a configurable threshold number of days ago" and did not anticipate this exact interaction.

### Why AC6 asks the implementer to check existing precedent before choosing filter-vs-fallback

This codebase already has at least one established "entity went missing but the parent record didn't" display convention (worth checking `MyPlayersPage.vue`/`ParentDevelopmentPortalPage.vue`/`CoachPlayerDetailPage.vue`'s own `unknown player name` fallback added by `skillars-12-4`, `coach.playerDetailUnknownPlayerName`) — reusing that convention keeps the roster consistent with the rest of the coach-facing UI rather than inventing a second one. This gap is latent, not reachable today (`deferred-work.md:3787` confirms no hard-delete path exists for `player_profiles` currently), so there is no urgency pressure pushing toward one specific choice — pick whichever existing convention fits best.

### Why AC7 doesn't just add more unit-test coverage to `ReportGenerationServiceTest`

`ReportGenerationServiceTest` mocks `FileStorageService` away entirely — this was the exact blind spot that let the original ownership-id-space bug (fixed by `skillars-deferred-151` AC3) ship unnoticed in the first place, per that story's own Finding 3 #1. Adding a hand-built `FileStorageObject` fixture (as the one existing test that does exercise the real service does, per `deferred-work.md:3774`) strengthens confidence in the fix's correctness but still doesn't prove the four call sites agree about *which* identifier flows through the real upload pipeline end-to-end — only a real sign-upload→confirm→save IT does that.

### Why AC9 offers a choice instead of mandating the fuller fix

`EditCoachBrandingDialog.vue:186`'s upload `await fetch(...)` call currently has no error-handling branch at all for a failed upload (a non-2xx response, or a network failure at the PUT step specifically, as opposed to the surrounding presign/confirm/save calls which do have `security.featureGated`-style catches). Adding one is the more complete fix and directly addresses why the stub exists in the first place, but if it turns out to need meaningfully more scope than this story budgets (e.g. a new i18n key, a new notify branch, deciding what the dialog should do with partial upload state), the honest fallback — simplifying the stub so it doesn't imply coverage that doesn't exist — is an acceptable, smaller substitute. Either resolves the "test asserts nothing" problem; only the former also closes a real (if minor) UX gap.

### Correction found during this story's drafting, not in the ledger: `SessionBuilderPage.vue` is no longer orphaned

The "coach UI CRUD audit (2026-10-14)" ledger bullet (`deferred-work.md:3780`) says no page links to `coach/session-builder/:bookingId`. Re-verified against real HEAD: `SessionPrepCard.vue:59` now does (`:to="{ name: 'coach-session-builder', params: { bookingId: props.bookingId } }"`), and `SessionPrepCard` is rendered from `CoachCommandCenterPage.vue` — both grep-confirmed, excluding `__tests__` files. This was a side effect of `skillars-12-3`'s AC2 (that story's own Dev Notes interpret the Prep Card's "quick note field" as a link to this exact pre-existing orphaned route) but was never flagged back to the ledger. Annotated in `deferred-work.md` as closed by this story's drafting pass, per this project's standing convention of recording side-effect closures rather than letting a future mining pass re-discover and re-verify the same thing.

### References

- `deferred-work.md` lines cited throughout — the Source table above maps each AC to its exact section/line
- `skillars-deferred-151-coach-ui-gaps-...md` — precedent for this story's structure and for the coach-branding feature's own Finding 3 (the id-space bug AC7 adds missing coverage for)
- `skillars-12-1-coach-player-roster-and-attention-summary.md`, `skillars-12-3-session-prep-card-and-timeline-access-clock-fix.md`, `skillars-12-4-coach-facing-player-detail-page.md` — the three stories whose code reviews sourced ACs 2–6
- `src/frontend/src/utils/errorHandler.js` — the shared helper AC1 routes both stores through
- `CoachPlayerLastSessionResolver.java` — the existing resolver AC3 wires in

---

## File List

**Backend — expected to be modified:**
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/CoachRosterService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SessionPrepService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RosterAttentionCalculator.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/NeglectedSkillFlagRepository.java`

**Backend tests — expected to be modified/new:**
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/CoachRosterServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RosterAttentionCalculatorTest.java`
- `src/test/java/com/softropic/skillars/platform/development/api/SessionPrepResourceIT.java` (no `SessionPrepServiceTest.java` unit test exists today — confirmed via `find`; `SessionPrepService` is covered only at this IT's resource level, so AC4's `SessionPrepService.java:80` change should get regression coverage here)
- A new IT (name TBD by implementer — e.g. `CoachBrandingResourceIT.java`, or a new test class if no existing IT class fits the `storage`/`development` cross-cutting shape)

**Frontend — expected to be modified:**
- `src/frontend/src/utils/errorHandler.js`
- `src/frontend/src/stores/development.store.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/components/development/CoachContributionSection.vue`
- `src/frontend/src/pages/coach/CoachPlayerDetailPage.vue`
- `src/frontend/src/pages/ProfilePage.vue`
- `src/frontend/src/components/profile/EditCoachBrandingDialog.vue`
- `src/frontend/src/i18n/en-US/index.js`, `src/frontend/src/i18n/de-DE/index.js`, `src/frontend/src/i18n/fr-FR/index.js`

**Frontend tests — expected to be modified/new:**
- `src/frontend/src/stores/__tests__/bookingStoreSpec.js` (existing — add a case for the `packsError` message fix)
- A new spec file for `development.store.js` (no existing spec file covers this store today — confirmed via `find src/frontend/src/stores/__tests__`, which lists only `bookingStoreSpec.js`/`playerStoreSpec.js`/`paymentStoreSpec.js`; add at least a focused case per touched action, not full-store coverage, unless the implementer judges the gap worth closing fully)
- A new spec file for `CoachContributionSection.vue` (no existing spec file covers this component today — confirmed via `find`; add at minimum the AC2 boundary-at-30% case, since that is the one new behavior this story introduces to the component)
- `src/frontend/src/pages/coach/__tests__/CoachPlayerDetailPageSpec.js`
- `src/frontend/src/pages/__tests__/ProfilePageSpec.js`
- `src/frontend/src/components/profile/__tests__/EditCoachBrandingDialogSpec.js`

**Tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (nine bullets annotated/removed, plus the already-closed `SessionBuilderPage` bullet)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status: backlog → ready-for-dev, this creation pass)

---

## Change Log

| Date | Change |
|------|--------|
| 2026-10-16 | Story created via `/bmad-create-story`, mined from `_bmad-output/implementation-artifacts/deferred-work.md`. Bundles nine independently-verified, currently-open, mechanically-closable items spanning four recent code-review sections (skillars-deferred-151, skillars-12-1, skillars-12-3 ×2, skillars-12-4) — a store-wide hardcoded-English error-message sweep (AC1), a 30%-contribution-threshold dedup with a split empty-state message (AC2), the third and final SLU-only report-generation access gate (AC3), an efficiency fix removing two needless full-entity-hydration-for-a-count calls (AC4), a roster dormancy-boundary rounding fix (AC5), a roster `playerName` null-safety guard (AC6), and three coach-branding completeness gaps — a missing end-to-end id-space test (AC7), an unrendered `ProfilePage.vue` logo preview (AC8), and a dead test-stub assertion (AC9). All citations independently re-verified against real HEAD `8d537200` before inclusion; one candidate item (`CoachSessionPackTiersPageSpec.js`'s weak assertion) was found already stale/fixed and excluded; one closure was found and annotated as a side effect of other work not yet reflected in the ledger (`SessionBuilderPage.vue` no longer orphaned, closed by `skillars-12-3`). Several other candidate items (roster pagination, roster-membership-width mismatch, abandoned-wrap-up stranding, GDPR-erasure gap on recap text, the five-copy relationship-check self-owner gap, orphaned non-coach-specific endpoints) were considered and excluded because each needs a product/architecture decision this story should not make as a drive-by — see "Explicitly NOT picked up" above. Status set to `ready-for-dev`. |
