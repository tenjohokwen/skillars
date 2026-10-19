# Story Review: skillars-12-5-coach-recap-on-player-timeline

**Audit date:** 2026-10-19
**Real HEAD at audit time:** `d35d146d` — "feat(development,admin,booking,reviews): report-access scoping, coach-erasure recap cleanup, review-eligibility query fix, nav fallback (skillars-deferred-154) (#279)"

All citations below were checked against the files as they actually sit at this HEAD — not against any commit SHA, line number, or "current state" claim written inside the story itself. Four parallel read-only agents each covered one worklist bucket (citations; ledger/precedent attribution; mechanistic claims; corner cases). Their raw findings were then adversarially re-checked by the orchestrator (this pass) by re-reading the cited evidence directly, with particular attention paid to the corner-case findings, which are the ones most likely to survive on a confident-sounding first read without actually being load-bearing.

---

## 1. Citation verification

59 file:line citations were checked. **52 are exact matches.** 7 have minor issues — 5 are path/line drift with the *content* confirmed correct at the real location, 1 is a narrow-range miss, and 1 is a genuine mismatch between what's cited and what the citation is used to support.

| # | Citation | Verdict | Evidence |
|---|---|---|---|
| 1 | `PlayerTimelinePanel.vue:44` — `</template>` closing `#title` | MATCH | Confirmed exact |
| 2 | `:45` — `</q-timeline-entry>` | MATCH | Confirmed exact |
| 3 | `:34-36` — icon/color/subtitle as props | MATCH | `:icon=`, `:color=`, `:subtitle=` all present as props |
| 4 | `:30` — `q-timeline` wrapper | MATCH | `<q-timeline v-if="store.timeline.events.length" color="primary">` |
| 5 | `:88-90` — `defineProps` | MATCH | Exact: `{ playerId: { type: [Number, String], required: true } }` |
| 6 | `:94-114` — ICON_MAP/COLOUR_MAP, 8 entries each with `??` fallback | MATCH (range narrow) | Maps confirmed 8/8; the `?? 'circle'`/`?? 'grey'` fallback operators sit at ~:117/:121, just outside the cited range |
| 7 | Seven state-branch line citations (`:7,:9-16,:10,:18-27,:19,:30-46,:48-50,:52-59,:66-77`) | MATCH (all) | Every sub-citation confirmed, including the `:19` bare `v-else-if` with no loading guard |
| 8 | `ParentDevelopmentPortalPage.vue:122` | MATCH | `<PlayerTimelinePanel :player-id="playerId" :key="playerId" />` |
| 9 | `PlayerDevelopmentDashboardPage.vue:118` | MATCH | Same usage |
| 10 | Same file `:107` — `:is-coach="isCoach"` | MATCH | Confirmed |
| 11 | Same file `:193` — `isCoach` in scope | MATCH | `const isCoach = computed(() => authStore.isCoach)` |
| 12 | `CoachPlayerDetailPage.vue:68` | MATCH | Confirmed |
| 13 | Same file `:129` — `loading = ref(true)` | MATCH | Confirmed |
| 14 | Same file `:124-128` — double-mount comment | MATCH | Comment block confirmed, matches story's characterization verbatim |
| 15 | `V138__baseline_schema.sql:427` — nullable `metadata jsonb` | MATCH | Confirmed |
| 16 | `DrillCard.vue:34,39,53` | **DRIFTED (path)** | Real file is under `components/session/`, not `components/development/`; cited lines match once corrected |
| 17 | `DrillDetailPanel.vue:46,54,63` | **DRIFTED (path)** | Same directory correction; lines match once corrected |
| 18 | `WrapUpRequest.java:21-22` | **DRIFTED (package)** | Real package is `booking.contract`, not `booking.dto`; `@NotBlank @Size(max=500)` on both fields confirmed at the corrected path |
| 19 | `BookingCompletionService.java:190,:267,:292` — pairing | MATCH | All three confirmed |
| 20 | Same file `:265-268` — force-complete `(..., List.of(), null, null)` | MATCH | Confirmed exact |
| 21 | `QuickCompleteTimeoutService.java:69` — "null recap fields" | **MISMATCH** | Line 69 forwards `scd.getWhatWeDid()/getWhatsNext()` — real, non-null values read from an already-validated row, not nulls. See Corner Case Finding 1 below; this citation is accurate for the *pairing* claim in AC3 but does not support AC4's separate "Quick-Completes with null recap fields" claim |
| 22 | `TimelineEventListener.java:54-57` | MATCH | Confirmed exact: coachId unconditional, whatWeDid/whatsNext conditional |
| 23 | Same file `:84` — RADAR_ASSESSMENT shape | MATCH | `Map.of("skillCodes", ...)` |
| 24 | Same file `:29-31` — denylist comment | MATCH | Verbatim |
| 25 | Same file `:61` — `coachIdForClock` ternary | MATCH | Confirmed exact |
| 26 | `TimelineQueryService.java:76-90` — `redactRecapIfNotAuthoringCoach` | MATCH | Confirmed exact method bounds |
| 27 | Same file `:86-88`/`:87-88` — removes two keys | MATCH | Confirmed |
| 28 | Same file `:53` — accessExpired, `events: []` | MATCH (nuance) | Empty-list return confirmed; the "HTTP 200" wrapping is applied one layer up in `PlayerTimelineResource.java:43`'s `ResponseEntity.ok(...)`, not in this line itself |
| 29 | Same file `:66` — every response passes through redactor | MATCH | Confirmed, sole construction site |
| 30 | `PlayerTimelineResource.java:43` — sole production caller of `getTimeline` | MATCH | Confirmed via repo-wide grep, independently re-confirmed by the mechanistic-claims layer |
| 31 | `ReportGenerationService.java:245-249` — PERFORMANCE_REPORT shape | MATCH | `Map.of("coachName", ..., "reportId", ...)` |
| 32 | `WrapUpSequence.vue:229,240` — `type="textarea"` | **DRIFTED (off-by-one)** | Cited lines are the `v-model` lines; `type="textarea"` sits one line below each (230/241) |
| 33 | `V159__booking_session_completion_data_recap.sql:9-10` — 500-char cap | MATCH | Confirmed, correct filename too |
| 34 | `SessionPrepCard.vue:20-32` — markup idiom | MATCH | Confirmed |
| 35 | Same file `:25,29` — renders the two fields | MATCH | Confirmed |
| 36 | Same file `:84` — `hasRecap` OR-guard precedent | MATCH | `computed(() => !!(prep.value?.lastWhatWeDid || prep.value?.lastWhatsNext))` |
| 37 | `SessionPrepService.java:59-67` | MATCH | Confirmed, reads the same `METADATA_KEY_*` constants |
| 38-41 | i18n citations across en-US/de-DE/fr-FR (`:889/:891`, `:257-258`, `:1132/:1134`, `:288-289`, `:884/:885`, `:21-22`) | MATCH (all 6) | Every key, position, and copied-verbatim value confirmed exact in all three locale files |
| 42 | `PlayerTimelinePanelSpec.js:10-31` — `mountPanel` shape | MATCH | Confirmed exact, including `stubActions` default and plugin list |
| 43 | Same file `:87-88` — `$te` falsy comment | MATCH | Verbatim |
| 44 | `setup-file.js:10,:18-27,:34-36` | MATCH | Confirmed |
| 45 | `vitest.config.mjs` — `globals: false` | MATCH | Confirmed |
| 46 | `CoachPlayerDetailPageSpec.js:60` | **DRIFTED (path)** | Real file is under `pages/coach/__tests__/`, not `components/development/__tests__/`; content at the corrected path matches |
| 47 | Same file `:141,:230` — `props('playerId')` only | MATCH | Confirmed at the corrected path |
| 48-50 | `PlayerTimelineResourceIT.java` — authoring-coach, second-coach, parent tests | MATCH (all) | All three tests and their specific assertion lines confirmed exact |
| 51 | `development.store.js:252-255` | MATCH | Confirmed, no per-event transform |
| 52 | `development.api.js:39-40` | MATCH | `size = 50` default confirmed |
| 53-54 | `PerformanceReportsPanel.vue:7,76` | MATCH | Both confirmed |
| 55 | `routes.js:283-288` | MATCH | Confirmed exact |
| 56 | `router/index.js:79-82` | MATCH | Confirmed exact |
| 57 | `auth.store.js:16-18` | MATCH | Confirmed (role as single string, mutually-exclusive computed getters) |
| 58 | `SessionCompletionDataRepository` `:43,:50` | MATCH | Confirmed, scoped to `session_completion_data` only |
| 59 | `GdprErasureService.java:1217` | MATCH | Confirmed exact |

## 2. Ledger & precedent attribution

All 12 checked claims are **CONFIRMED** — nothing fabricated, nothing wrongly attributed. Checked against the actual referenced story files, `deferred-work.md`, the brainstorming document, `docs/validation-strategy.md`, `pom.xml`/`package.json`, and `_bmad-output/project-context.md` (not just one source each, per the skill's requirement):

- `skillars-12-2` AC4/AC8 wording quoted correctly (the AC8 quote correctly elides an AC4-policy clause with "…", not a misquote).
- `skillars-12-4` AC4's "unchanged… exactly three pieces" and its Converge #12 attribution are both verbatim-correct; the brainstorming doc really does contain `PlayerTimelinePanel.vue` (unchanged)`` under Converge #12.
- `skillars-12-3`'s SessionPrepCard rendering claim confirmed at the real (longer) story filename.
- `skillars-12-1` AC10's trend-field deferral confirmed verbatim.
- `ReportGenerationService` genuinely has zero `SessionCompletionDataRepository`/recap references — Converge #16 is genuinely still unimplemented.
- Brainstorming doc's Priority 2 success indicator, Converge #12/#15/#16, Deferred #17, and Decision #8 all confirmed close to verbatim (one minor rhetorical amplification: the doc says Deferred #17 is "parked," the story says "permanently parked" — not a fabrication).
- `skillars-deferred-154` AC2's "owner decision of 2026-10-17" confirmed verbatim in that story's own Dev Notes; this is genuinely a residual, unfixed gap, not something the cited HEAD commit (which is deferred-154's own merge, but for a *different* sub-fix — anonymizing `booking.session_completion_data`, not the timeline metadata copy) already closed.
- `docs/validation-strategy.md`'s "don't run `mvn verify` locally" convention confirmed verbatim.
- The Maven-invoked `npm test` no-op-stub claim confirmed via `package.json`'s `"test": "echo ... && exit 0"` and `pom.xml`'s `frontend-maven-plugin` wiring.
- The brainstorming doc really is annotated in the current working tree (visible as the uncommitted `M` in `git status`) crediting this story under Converge #15.
- `project-context.md` confirmed to cover i18n externalization, Prettier-mandatory, `<script setup>`, and frontend test conventions.

## 3. Mechanistic claims

All 10 checked claims are **CONFIRMED**, each verified by reading the full method body (not a snippet), with one nuance:

- `redactRecapIfNotAuthoringCoach` is confirmed as a denylist (removes exactly the two recap keys, leaves `coachId` and everything else) and the sole gate every `TimelineEventResponse` passes through.
- `PlayerTimelineResource` confirmed as the sole production caller of `getTimeline` repo-wide.
- `TimelineEventListener`'s metadata-write logic confirmed exactly as described: `coachId` unconditional, `whatWeDid`/`whatsNext` conditional-and-absent-when-null (not null-valued).
- The four `BookingCompletedEvent` publish sites and their recap-pairing behavior all confirmed exactly.
- `WrapUpRequest`'s `@NotBlank @Size(max=500)` validation confirmed to line up with the `V159` migration's `varchar(500)` columns.
- The "both-or-neither" invariant is confirmed to hold for every current production path, **but** it is an application-layer contract (immutable `BookingCompletedEvent` constructor + `WrapUpRequest` validation), not a database-enforced constraint — a future caller that constructs the event asymmetrically could silently break it. Worth noting as an implicit-contract risk, not a defect in this story.
- The timeline write is confirmed genuinely non-atomic with the booking-completion write (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`), running in its own `REQUIRES_NEW` transaction on a separate thread, with any failure silently swallowed by a catch-and-log. This cannot produce a partially-written metadata map (the map is built in memory before the single INSERT), but it does mean the row can simply not exist for a time, or ever, if the async write fails — this directly underpins Corner Case Finding 3 below.
- `auth.store.js`'s single-string `role` field and the router guard's redirect-away-from-player-routes behavior both confirmed by reading the full guard function, including a check for a confusable neighboring route (`locker-room`, which genuinely does use a plural `roles` array) that does not undermine the claim.
- `CoachPlayerDetailPage.vue:129`'s `loading = ref(true)` comment confirmed to genuinely document a double-mount-avoidance fix, not a stale/misattributed comment.
- `development.store.js`'s untouched-metadata-passthrough confirmed by reading the full `fetchTimeline` action.
- The GDPR erasure asymmetry (player-side covered by full row deletion, coach-side not covered by `anonymizeByCoachId`, which only touches `session_completion_data`) confirmed by reading `GdprErasureService`'s full coach-erasure branch — no call to `playerTimelineRepository` exists there.

## 4. Corner cases, false assumptions, missed flows

### Finding 1 — AC4's "four distinct real sources" of no-recap entries double-counts; the real count is three

**Independently re-verified by the orchestrator, not just relayed.** AC4 lists four sources of a recap-less `SESSION_COMPLETED` timeline entry: (1) pre-V159 legacy sessions, (2) "the coach's own force-completed/abandoned sessions" (citing `forceCompleteWithPlaceholderData`), (3) "Quick-Completes with null recap fields," (4) other-coach redaction.

Tracing every real row-creating call site directly:
- `submitWrapUp` (`BookingCompletionService.java:130-163`) is the only method that ever calls a validated `@Valid WrapUpRequest` whose `recapWhatWeDid`/`recapWhatsNext` fields are `@NotBlank` (`WrapUpRequest.java:21-22`). It unconditionally sets both fields on the `SessionCompletionData` row (`:162-163`) before transitioning the booking via `BookingEvent.QUICK_COMPLETE` (LIVE mode) or leaving it pending for parent confirmation (DEFERRED mode).
- `QuickCompleteTimeoutService.processExpiredQuickCompletes` (`:50-79`) only ever *reads* rows returned by `findPendingQuickCompletes`, which filters on `Booking.status = COMPLETED_PENDING_CONFIRMATION` — the exact status `submitWrapUp`'s row creation requires as a precondition. Every row this scheduler reads was therefore already populated by `submitWrapUp` with real, non-null recap text (confirmed directly: `SessionCompletionDataRepository.java:28-33`).
- `forceCompleteWithPlaceholderData` (`:240-269`) is the **only** method that ever creates a `SessionCompletionData` row with null `whatWeDid`/`whatsNext` — and it requires the booking to be in that same `COMPLETED_PENDING_CONFIRMATION` status and transitions it *away* from that status in the same call, so it and the timeout scheduler's query are mutually exclusive on any given booking.

So sources (2) and (3) in AC4 are not two distinct real scenarios — they are the same underlying code path (`forceCompleteWithPlaceholderData`) producing the same metadata shape (`{coachId}` only). "Quick-Completes with null recap fields," taken literally, does not describe a reachable production scenario: a genuine Quick-Complete (auto-confirmed by timeout or manually confirmed by the parent) always carries real recap text, because it can only originate from a validated `submitWrapUp` call. The real count of distinct no-recap metadata shapes is **three**, not four.

**Severity: benign — does not break any AC or test.** The proposed UI guard (`event.eventType === 'SESSION_COMPLETED' && (event.metadata?.whatWeDid || event.metadata?.whatsNext)`) is agnostic to *why* both fields are absent. AC8's 6 test cases are unaffected — a "Quick-Complete-null" fixture would just duplicate AC8 case 3's `{coachId}`-only shape. Worth a one-line AC4 prose correction before/during implementation so a future reader doesn't go looking for a non-existent fourth scenario; changes nothing in Tasks 1-4.

### Finding 2 — Minor actor misattribution in AC4 (cosmetic)

AC4 calls source (2) "**the coach's own** force-completed/abandoned sessions." Independently confirmed: `forceCompleteWithPlaceholderData` has exactly two callers in the entire codebase — `StuckCompletionSweeperService` (`ActorRole.SYSTEM`, a scheduled sweep) and `AdminBookingCompletionService` (`ActorRole.ADMIN`, explicitly documented as accepting "any bookingId straight from a support ticket"). It is never coach-initiated. The practical scenario AC4 describes (a coach sees a recap-less session on their own roster and may mistake it for data loss) still holds — only the word "own" is misleading about *who performs the completing action*, not about whose roster the session belongs to. **Severity: cosmetic prose only** — no guard, test, or Task is affected.

### Finding 3 — Async listener timing creates a transient "entry entirely absent" window, not enumerated by AC4 (lower confidence; informational only)

`TimelineEventListener.onBookingCompleted` is `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("taskExecutor")` (confirmed directly in §3). The timeline row is written after the coach's wrap-up transaction commits, on a separate thread — so a coach who submits a wrap-up and immediately opens that player's detail page could, for a brief window, see no entry at all for that session (not a recap-less entry — the row hasn't landed yet). This is a different failure mode than AC4's three real sources (which are all durable, permanent states); this one is transient and self-resolving on refresh/Load-more. It does not break any AC, is not specific to recap data (applies to any `SESSION_COMPLETED` visibility, independent of this story), and AC9 forbids touching the listener anyway. Included as a lower-confidence, optional Dev Notes candidate, not a defect.

### Checked and found clean (no action needed)

- **CSS/layout (AC1/AC5):** Quasar's `QTimeline` defaults to `layout: 'dense'`, which doesn't clip overflow; `PlayerTimelinePanel.vue:30` never overrides `layout`. A 500-char, many-newline recap simply makes one entry taller — no breakage of adjacent entries or the connector rail.
- **AC8 test-case distinguishability:** "whatWeDid present without coachId" is confirmed genuinely impossible given the write order at `TimelineEventListener.java:55-57` — its absence from the 6 cases is correct, not a gap. `metadata: {}` vs. `metadata` omitted are behaviorally identical for this template.
- **i18n key-order parity (AC7):** all three locale files have identical key order/nesting at the insertion point today — no pre-existing drift for Task 1.4's manual check to catch.
- **Cross-page component identity (AC2):** all three pages (`CoachPlayerDetailPage`, `ParentDevelopmentPortalPage`, `PlayerDevelopmentDashboardPage`) import the literal same `PlayerTimelinePanel.vue` — no duplicate/variant component in play.
- **Pagination completeness (AC9/AC10):** no artificial page-count ceiling beyond the backend's own `hasNext()` signal; the one real completeness caveat (the 90-day coach-access-expiry clock) is pre-existing and already documented in the story's own Dev Notes, not a new gap.
- **The `SessionPrepCard.vue`/`PerformanceReportsPanel.vue` precedents cited in Dev Notes** were independently re-verified against their own current source (not accepted as this story's paraphrase) — both confirmed exactly as characterized.

## 5. What did not survive re-verification

- **A hypothesis that `BookingCompletionService.java:267`'s citation was itself a substantive error** — distinct from Finding 1 above. On re-read, AC3's claim there is "every publisher passes them as a pair… both present or both absent," and line 267's `null, null` is exactly the "both absent" half of that claim, not a mis-citation. **Dropped as a false positive**; the real issue is AC4's separate four-vs-three source-count claim (Finding 1), not this citation.
- **A hypothesis that Quasar's timeline layout CSS could clip long recap text.** Killed once confirmed the component defaults to `dense`, not the `overflow:hidden` `loose` variant, and the panel never overrides it.
- **A hypothesis that "Quick-Completes with null recap fields" might be separately reachable via a legacy row stuck in `COMPLETED_PENDING_CONFIRMATION` across the V159 migration boundary.** Considered but unresolvable from current source alone (a purely historical-timing question), and immaterial to frontend behavior regardless (the `v-if` guards handle a null field identically no matter why it's null). Not elevated beyond Finding 1.
- **Elevating Finding 3 (async listener timing) to the same confidence tier as Findings 1-2.** Kept as a lower-confidence, informational note only — it's real and code-backed, but arguably outside AC4's literal scope (AC4 discusses recap-less *rendered* entries, not not-yet-rendered ones), and not specific to this story's change.

## 6. Recommendation

- **ACs 1, 2, 5, 6, 7, 9, 10:** HIGH confidence, no issues. Every citation and mechanistic claim underpinning these was independently confirmed by direct reads of full method bodies, not relayed summary.
- **AC3 (triple guard):** HIGH confidence. The guard is correctly agnostic to *why* a recap field is absent — which is exactly what makes Finding 1 benign rather than AC-breaking.
- **AC4 (four exhaustive no-recap sources):** MEDIUM-HIGH confidence, with one real, independently-reverified finding: the "four distinct sources" are actually three distinct metadata-producing code paths (Finding 1), and the "coach's own" framing of the force-complete case is inaccurate about the actor (Finding 2). Neither changes what Task 2 implements or what AC8 tests; both warrant a one-line prose correction before/during dev.
- **AC8 (test cases):** HIGH confidence — the 6 cases are genuinely distinguishable under the real `$t`/`$te` mock behavior, and the "missing case" hypotheses this review specifically hunted for (empty-object metadata; whatWeDid-without-coachId) are both confirmed non-issues by tracing the actual write path.
- **Finding 3 (async listener timing):** LOWER confidence as a standalone finding — real and code-backed, but arguably out of AC4's literal scope. Worth at most a one-line Dev Notes mention; does not block implementation.
- **Citations:** 52/59 exact; 5 path/line drift with correct content; 1 narrow-range miss; 1 genuine mismatch (`QuickCompleteTimeoutService.java:69` doesn't support the specific "null recap fields" sub-claim it's attached to in AC4 — see item 21 and Finding 1).
- **Ledger/precedent attributions:** HIGH confidence — nothing fabricated or misattributed, including the `skillars-deferred-154` "owner decision of 2026-10-17" claim, which most resembled the kind of citation a less careful pass might wrongly flag as invented.

**Overall:** this is a well-researched, tightly-scoped story. Of everything the four verification layers raised, two items survive as real, actionable (but non-blocking) findings — AC4's source-count overstatement and its actor misattribution — plus one lower-confidence informational note about async listener timing, plus one citation that doesn't actually support the specific sub-claim it's attached to. Five cosmetic path/line citation drifts are listed for completeness and require no action. Nothing found here blocks implementation as written; Tasks 1-4 do not need to change.
