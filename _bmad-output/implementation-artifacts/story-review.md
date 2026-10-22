# Story Review — skillars-12-8-timeline-enrichment-session-content-and-event-producers

- **Story:** `_bmad-output/implementation-artifacts/skillars-12-8-timeline-enrichment-session-content-and-event-producers.md` (status `ready-for-dev`)
- **Audit date:** 2026-10-23
- **Real HEAD:** `05f81d6d feat(booking): recap lifecycle - edit, resume, missing-recap nudge, what-went-well, report visibility (12.7) (#292)`
- Every citation was checked against this HEAD, obtained with `git rev-parse` at the start of the audit. The story's header ("Citations verified against master @ 05f81d6d") names the same SHA. Even so, several of its `TimelineEventListener` line ranges are stale: they look like the pre-12.7 / epic-era (`7121ace5`) numbers carried over (see §1).
- **Method:** four parallel read-only layers (citations, ledger/precedent, mechanistic, corner-case), followed by an adversarial re-read of every surviving finding. Paths below are relative to `src/main/java/com/softropic/skillars/platform/` unless they start with `src/`.

---

## 1. Citation verification

| # | Story citation | Verdict | Evidence / corrected location |
|---|---|---|---|
| 1 | `SluCalculationService.java:50-80` eligibility gate ("session exists AND SAVED/COMPLETED AND blocks non-empty") | MATCH (incomplete) | The gate is at `:57-77`. The story's list leaves out two conditions: `:52 if (!event.isPlayerAttended()) return;` (AC1 handles attendance separately) and `:111 if (!hasDrills) return;` (blocks exist but none has drills). `:80` is the SLU-specific idempotency guard and is not part of the gate. |
| 2 | `SluCalculationService.java:117` `findAllById` | MATCH | `:117 Map<UUID, Drill> drillMap = drillRepository.findAllById(drillIds)` |
| 3 | `SluCalculationService.java:50-117` reference impl | MATCH | The range also contains SLU-only code (`:80`, `:84-98`). Copy the gate, as the story already says. |
| 4 | `TimelineEventListener.java:46-54` Map.of guard comment | **DRIFTED** | `:46-48` is the `@Autowired @Lazy self` field. The guard comment is at **`:54-61`** and the conditional puts at **`:73-75`**. |
| 5 | `TimelineEventListener.java:42-73` `onBookingCompleted` | **DRIFTED** | Real body is **`:50-91`**. Everything the story says about it is correct: HashMap with `coachId` `:62-63`, SCD re-read `:69-72`, `coachIdForClock` `:79`, `self.writeTimelineEvent` `:80-87`. |
| 6 | `TimelineEventListener.java:63-69` referenceId/"booking" | **DRIFTED** | Real location **`:80-87`** (`:83 event.getBookingId()`, `:84 "booking"`). |
| 7 | `TimelineQueryService.java:76-90` / `:71-90` redaction denylist | MATCH | `:76 redactRecapIfNotAuthoringCoach`, `:78` null / `SESSION_COMPLETED`-only short-circuit, `:81-82` authoring-coach test, `:87-89` three `remove(...)` calls. |
| 8 | `PlayerTimelineRepository.java:~107-115` `updateSessionRecapMetadata` | DRIFTED (minor) | Real location `:102-110`. `:104 "SET metadata = (COALESCE(metadata, CAST('{}' AS jsonb)) - 'whatWeDid' - 'whatsNext' - 'whatWentWell') "`. The behaviour is as the story describes. |
| 9 | `PlayerTimelinePanel.vue:44-77` / `:46-52` | `:46-52` MATCH; `:44-77` approx. | The `SESSION_COMPLETED` `<dl>` spans `:46-73`. `:77` is the `v-else` no-events div. |
| 10 | `PlayerTimelinePanel.vue:128,139` (`:124-140`) | MATCH | `:128 HOMEWORK_ASSIGNED: 'assignment'`, `:139 HOMEWORK_ASSIGNED: 'blue'` |
| 11 | `en-US/index.js:921` | MATCH | `HOMEWORK_ASSIGNED: 'Homework Assigned'`. Also present at de-DE `:1196` and fr-FR `:917`. |
| 12 | `routes.js:187,323,341`, no booking-detail route | MATCH | Grepping `routes.js` for `:bookingId` returns only those three routes. |
| 13 | `HomeworkAssignmentService.java:~49-100` | MATCH (approx.) | Annotations `:53-54`, method body `:55-98`. |
| 14 | `CoachPlayerLastSessionResolver.java:31` | MATCH | `:30-31 findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(playerId, coachId, SESSION_COMPLETED)` |
| 15 | `PlayerTimelineEventType.java:3-12`, 8 values, "deferred — Story N" | MATCH | `:7-11` show the deferred comments ("Story 5.6+", "Story 6+", "Story 6", "Story 7", "Story 9"). |
| 16 | `PlayerTimelinePanel.vue` 184 lines | MATCH | `wc -l` returns 184. |
| 17 | Dev Notes: "idx on timeline is (player_id, occurred_at); no index on reference_id **or event_type**" | **PARTIALLY WRONG** | `V161__…coach_lookup_index.sql:12-13` creates `(player_id, coach_id, event_type, occurred_at DESC)`. `event_type` is indexed, but not as a leading column. No index includes `reference_id` (that half is correct). |
| 18 | Next free migration V168 | MATCH | The highest existing migration is V167 (`booking_bookings_live_started_at`). |
| 19 | Test classes `TimelineEventListenerTest`, `TimelineQueryServiceTest` (Task 1.5, Testing list) | **DO NOT EXIST** | Not found under `src/test`. The existing listener coverage is the IT `SessionCompletionResourceIT.java:436-497`. Found: `PlayerTimelineResourceIT`, `HomeworkAssignmentServiceTest`, `GdprErasureIT` (`admin/api`), `PlayerTimelinePanelSpec.js`, `localeKeyParitySpec.js`, `i18nTemplateKeyResolutionSpec.js`. |
| 20 | `docs/deployment/migration-conventions.md`, `_bmad-output/project-context.md` | MATCH | Both exist. |

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| Epic 12 Story 12.8, G14, G15, D4 | (a) epics `:3584`, G14 `:3503`, G15 `:3504`, D4 `:3481` (b) `BookingCompletedEvent.java:17-20` | CONFIRMED. The epic's "BookingCompletedEvent carries homeworkDrillIds + ratings" and "referenceId not linked" are both verbatim in G14. The epic's own line citation (`TimelineEventListener.java:54-57`) has since drifted. |
| "Owner decisions already made" (siloed recaps, lapsed computed, sweeper placeholder, roster cap) | (a) epics `:3471-3475` | CONFIRMED |
| Brainstorm Pattern #7 | (a) `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md:88-90`, mapped to 12-8 at `:287` | CONFIRMED |
| "G25-class bug" (denylist leak) | (a) epics G25 `:3514`; 12-7 story `:56` | CONFIRMED |
| 12.7: prefer SCD over event copy | (a) 12-7 review `:122` (b) `TimelineEventListener.java:64-68` comment (c) `git log -S` → `05f81d6d` | CONFIRMED |
| 12.7: native bulk UPDATE because `metadata` is `updatable=false`; redaction is a denylist | (a) 12-7 `:34,:143` (b) `PlayerTimelineEvent.java:48`, repo `:104` | CONFIRMED |
| 12.6: i18n specs fail on non-literal keys / missing locale | (a) 12-6 `:38,:206` (b) `i18nTemplateKeyResolutionSpec.js:25-49` | CONFIRMED with nuance: dynamic keys are allowed when listed in `DYNAMIC_KEY_CALL_SITES`. |
| 12.6: "five-state UI convention (loading/error/empty/data)" | (a) 12-6 (absent), 12-1 `:152`, 12-7 `:50,:125` | **MISATTRIBUTED.** The convention comes from 12.1 (and 12.7 repeats it). Its five states are loading / incomplete-profile-404 / error / empty / populated; the story lists four. Informational. |
| 12.5: recap block "triple-guarded" | (a) 12-5 `:40` (b) panel `:46-73` | CONFIRMED. 12.7 added a fourth inner guard, `whatWentWell`, but the semantics still hold. |
| 12.7 touched files / V165-V167 | (c) `git show --stat 05f81d6d` | CONFIRMED |
| V166 coach tombstone | (b) V166 migration header | CONFIRMED |
| "same stance `SessionCompletionDataRepository`'s own comment takes for ratings" | (b) `SessionCompletionDataRepository.java:50-51` | PARTIAL. The comment sits on `anonymizeByPlayerId` (player erasure). The coach method says only "Identical shape". Applying the comment to coach erasure is an inference, though a reasonable one. |
| Enum "deferred — Story N" comments | (b) enum `:7-11`; (a) `sprint-status.yaml` | CONFIRMED and stale: stories 5.6 and Epics 6/7/9 are all `done`, and none added a producer. That supports AC10's rewrite. |
| project-context § Communication "domain events **only**" | (a) `project-context.md:123` | WORDING. The source says "**Prefer** Domain Events". `development` → `session.repo` reads already exist, and AC1 relies on them. |
| Prettier gate in `.github/actions/` blocks build-and-push | `.github/actions/frontend-quality/action.yml:32-36`, `ci.yml:88-93` | CONFIRMED |
| Hikari auto-commit false; `chk_bkg_end_after_start` | `application.yaml:183`; `V138:245` | CONFIRMED |
| Five stub types, no producers | grep of `writeTimelineEvent` callers: `TimelineEventListener:80,:146`, `ReportGenerationService:280` | CONFIRMED |
| Open Q2: "parents already see homework in the locker room" | `routes.js:274-281`, `HomeworkResource.java:27-28` | CONFIRMED, with a caveat that survives as finding C9 below. |

## 3. Mechanistic claims

| Claim | Evidence | Verdict |
|---|---|---|
| `HomeworkAssignmentService.handleBookingCompleted` has no `@Transactional`; each `saveAndFlush` commits on its own | `:53-55` only `@TransactionalEventListener(AFTER_COMMIT) @Async("taskExecutor")`; class `:36-39` has no `@Transactional`. Spring Data default per-call transactions apply. | TRUE |
| Publishing from a non-transactional context never fires an AFTER_COMMIT listener without `fallbackExecution = true` | Spring `TransactionalApplicationListenerMethodAdapter` (spring-tx 6.2.19): no active transaction and `fallbackExecution=false` → event dropped. The codebase has documented this exact bug before at `NotificationOutboxSupport.java:36-39` and `VideoModerationEmailListener.java:33-36`. No `fallbackExecution = true` exists anywhere. No custom multicaster, no Spring Modulith. | TRUE |
| "OR the publish is moved inside a small `@Transactional` boundary" | `HomeworkAssignmentService` has no self-proxy (field list `:41-49`), so a same-class `@Transactional` helper is bypassed. Since Spring 6.1, `@Transactional` on a `@TransactionalEventListener` method is rejected at startup unless it is `REQUIRES_NEW`/`NOT_SUPPORTED`. Wrapping the whole loop in one transaction would break the `DataIntegrityViolationException` catch (`:93`), because the aborted transaction leads to `UnexpectedRollbackException`. | **TRUE as a trap, but the story's alternative is under-specified.** See C6. |
| `BookingCompletionService` publishes inside a transaction | `confirmCompletion` `:393-414`, `submitWrapUp` `:146-209` (LIVE only), `forceComplete…` `:257-285`. All are `@Transactional`; the publisher is injected as `eventPublisher`. | TRUE |
| `@Autowired @Lazy self`; 5-arg `writeTimelineEvent` leaves `coach_id` NULL; both overloads REQUIRES_NEW | `TimelineEventListener.java:46-48`, `:158-163` (5-arg delegates with null), `:169-182` | TRUE |
| `onBookingCompleted` / `onRadarEntrySubmitted` are async, log and never throw | `:50-91`, `:142-156`, catch blocks `:88-90`, `:153-155` | TRUE |
| `SessionRepository.findByBookingId` | `Optional<Session> findByBookingId(UUID)` `SessionRepository.java:23`. `uq_sessions_booking_id` (V138 `:4030`) guarantees at most one session per booking. | TRUE |
| `SessionDrillRef(drillId, order)` | `record(UUID drillId, int order)` `session/contract/SessionDrillRef.java:5` | TRUE |
| Redaction keyed on `metadata.coachId == caller coach`, `SESSION_COMPLETED` only; parent/player always redacted | `TimelineQueryService.java:78-90`; `PlayerTimelineResource.java:38-42` sets `isCoach` from `ROLE_COACH` | TRUE. The method is private with one call site (`:66`). Its name also appears in comments at `PlayerTimelineRepository.java:119` and `src/frontend/src/stores/development.store.js:263` (see C10). |
| `metadata` `updatable=false`; recap edit preserves other keys | `PlayerTimelineEvent.java:48`; repo `:104` jsonb `-` on three keys then `|| :patch` | TRUE. `drills` and the ratings survive (AC4 holds). |
| `anonymizeMetadataByCoachId` strips only recap keys; `deleteByPlayerId` is used by player erasure | repo `:132-139`; `GdprErasureService.java:1468` | TRUE |
| The coach-access clock and 12-x readers ignore non-`SESSION_COMPLETED` rows | resolver `:29-31`; `findSessionNotesForReport` `:66-73`, `findLatestWhatsNextSuggestion` `:84-92` both filter `event_type = 'SESSION_COMPLETED'`; roster uses the resolver (`CoachRosterService.java:137`) | TRUE. AC12 is safe by construction; its tests are regression guards. |
| `BookingCompletedEvent` types match the new record (`UUID bookingId, Long playerId, UUID coachId`) | `booking/contract/BookingCompletedEvent.java:9-23`; `boolean playerAttended`, `Integer` ratings | TRUE |
| "development already imports session.repo.*; no session → development import" | `SluCalculationService:10-13`, `SessionPrepService:15-16`. However, `session/service/DrillSuggestionService.java:4-5` already imports `development.repo`. | PARTIALLY TRUE. The direction is already bidirectional, so "do not add" is right for this story's files but the premise that the direction is one-way is false. Informational. |
| `DrillRepository.findAllById`; renamed or archived drills | `session/repo/DrillRepository.java:15` (JpaRepository); archiving is a status value with no `@SQLRestriction`, so archived drills still resolve; no hard-delete path | TRUE. The "unresolved id → WARN" branch is reachable only through manual DB edits. |
| No DB CHECK on `event_type` | `V138:423` `varchar(50) NOT NULL`, no CHECK in V138-V167 | TRUE |
| `taskExecutor` | `infrastructure/config/AsyncConfig.java:34-53` (core 4, max 16, queue 100, CallerRuns) | TRUE |

## 4. Corner cases / false assumptions / missed flows (survived re-verification)

**C1 — AC1, MED: the DRAFT → COMPLETED status race makes `drills` nondeterministic.**
- A new session starts as `DRAFT` (`SessionPlanService.java:103`), and a coach can leave it there.
- On completion, `SessionPlanService.handleBookingCompleted` (`:166-176`, async, AFTER_COMMIT, `REQUIRES_NEW`) flips any non-COMPLETED session to `COMPLETED`.
- `TimelineEventListener.onBookingCompleted` runs off the same commit on the same `taskExecutor` pool. So a DRAFT plan gets `drills` only if the status flip commits first.
- Because `metadata` is never rewritten, the outcome is frozen.
- Copying the SLU gate "exactly", as the story says, copies this race. SLU already has it (`SluCalculationService.java:66-67`).
- Fix: either accept `DRAFT` too (and decide what a DRAFT plan's content means as "what was trained"), or record the nondeterminism as accepted. Add a DRAFT-session test either way.

**C2 — AC1/AC7, MED, needs an owner decision: private drill names reach other coaches.**
- Coach libraries are private: `DrillRepository.java:19` filters `ownerCoachId … libraryType = 'PRIVATE'`.
- A second coach with active access already reads the timeline (`PlayerTimelineResourceIT.java:304 getTimeline_asSecondCoachWithActiveAccess_excludesRecapFieldsButIncludesEvent`).
- AC1 and AC7 make `drills` visible to every viewer, so coach B would see coach A's private drill names (session plan and homework).
- With `coachId` omitted from HOMEWORK metadata (AC7), per-coach redaction cannot be added later without a backfill.
- The story's reason for omitting `coachId` ("stay out of `anonymizeMetadataByCoachId`") does not hold. That query (`PlayerTimelineRepository.java:133-137`) only touches rows that also contain a recap key, so a HOMEWORK row carrying `coachId` would never match.
- Fix: decide whether cross-coach exposure of drill names is acceptable. If not, snapshot the owner or library type per entry and redact like the ratings. Cheapest hedge regardless: store `coachId` in HOMEWORK metadata.

**C3 — AC1/AC2, MED: an enrichment failure would drop the whole `SESSION_COMPLETED` row.**
- `onBookingCompleted` has a single outer `try … catch (Exception) { log.error }` (`TimelineEventListener.java:53-90`).
- New session or drill reads placed before `self.writeTimelineEvent` that throw (a DB error, or a JSONB deserialisation error) would skip the write.
- That row feeds the coach-access clock, the prep card and reports.
- Fix: `resolveSessionDrills` gets its own try/catch → WARN → empty, and the event is always written.

**C4 — AC9, MED: the replay test as written can pass vacuously.**
- Every consumer is `@TransactionalEventListener(AFTER_COMMIT)` without `fallbackExecution` (`TimelineEventListener.java:50`, `HomeworkAssignmentService.java:53`, `SessionPlanService.java:166`).
- A test that calls `publishEvent(sameBookingCompletedEvent)` from the IT body (no transaction) is silently dropped by all of them, so "still exactly one row" passes without testing anything.
- A correctly-published replay is still stopped upstream (`existsByBookingIdAndDrillId`, `:80`) and never reaches AC7's new `exists…` guard. It also writes a second `SESSION_COMPLETED`, because `onBookingCompleted` is not idempotent.
- Fix: publish the replay inside `transactionTemplate.executeWithoutResult`. Add a separate test that delivers the same `HomeworkAssignedEvent` twice to exercise AC7's guard.
- Also: `submitWrapUp` in QUICK mode publishes no `BookingCompletedEvent`. Use LIVE mode, or QUICK followed by `confirm-completion` (pattern at `SessionCompletionResourceIT.java:443-497`).

**C5 — AC6/AC9, MED: QUICK-complete auto-confirm on timeout never assigns homework (missed flow).**
- `QuickCompleteTimeoutService.java:65-69` publishes `BookingCompletedEvent(..., scd.getTechniqueRating(), List.of(), ...)`.
- The parent-confirm path (`BookingCompletionService.java:409`) uses `deserializeHomeworkDrillIds(scd.getHomeworkDrillIds())`.
- A QUICK wrap-up with homework that the parent never confirms gets no homework assignment and no `HOMEWORK_ASSIGNED` entry.
- This bug already exists today; the story should either fix it (share the deserializer) or scope it out explicitly.

**C6 — AC6, LOW-MED: the "small `@Transactional` boundary" alternative is a trap as phrased.**
- `HomeworkAssignmentService` has no self-proxy, so a same-class `@Transactional` helper never opens a transaction, and the AFTER_COMMIT consumer is silently skipped.
- Putting `@Transactional` on the listener method itself is rejected at startup unless it is `REQUIRES_NEW` (precedent: `SessionPlanService.java:168`).
- Enclosing the save loop breaks the per-drill duplicate catch.
- Fix: prefer `fallbackExecution = true` on the consumer (the first option in the story), or require a `TransactionTemplate` wrapping only the publish.

**C7 — AC6, LOW: a mid-loop non-DIV failure skips the publish permanently.**
- The loop catches only `DataIntegrityViolationException` (`:91-96`).
- Any other exception on drill 2 exits after drill 1 has committed. The "after the loop" publish never runs, and a replay skips drill 1 as already existing.
- Fix: collect the persisted ids and publish them in a `finally` (or catch per drill).

**C8 — AC2, LOW, decision: ratings are not attendance-gated, but drills are.**
- The wrap-up UI sends ratings regardless of attendance (`WrapUpSequence.vue` `handleSubmitWrapUp`: `effortRating: effortRating.value || null` next to `playerAttended`).
- AC1 suppresses `drills` for a no-show ("nothing was trained"), while AC2 writes ratings unconditionally.
- Likewise, `handleBookingCompleted` assigns homework with no attendance check, so a no-show can produce a `HOMEWORK_ASSIGNED` row.
- State the intended behaviour for both.

**C9 — Open Q2, LOW: the timeline would show homework that the locker room hides.**
- `getLockerRoomDrills` (`HomeworkAssignmentService.java:111-116`) filters to coaches with an active pack (`hasActivePackForAnyOf`).
- The `HOMEWORK_ASSIGNED` timeline entries have no such filter.
- So "parents already see homework in the locker room" is only partly true, and the two views can disagree.
- Probably acceptable, since the timeline is history, but the story should say so.

**C10 — AC2 rename, LOW.** `redactRecapIfNotAuthoringCoach` is also named in comments at `PlayerTimelineRepository.java:119` and `src/frontend/src/stores/development.store.js:263`. "Every caller/test reference" should explicitly include these. No test references the name.

**C11 — Task 1.5 / Testing, LOW.** `TimelineEventListenerTest` and `TimelineQueryServiceTest` do not exist; mark them as new. Also correct the stale `TimelineEventListener` line numbers (§1 #4-6) and the "no index on event_type" note (§1 #17).

## 5. What did not survive re-verification

- **"Drills are never sorted by `SessionDrillRef.order`; AC1 needs an explicit sort the reference lacks"** (Layer 3). Killed as a defect. `SessionPlanService.mapBlocksFromRequest` (`:258-263`) sorts refs by `order` before persisting, and it is the only writer of blocks on create and update (`:88`, `:149`). Templates copy already-persisted blocks (`SessionTemplateService.java:67,155`). The list order is therefore already `order` order. An explicit sort is harmless but not required.
- **AC9 replay severity HIGH** (Layer 4). Downgraded to MED (C4). It is a test-design trap that a reviewer of the IT would catch, not a production defect.
- **Concurrent duplicate `HomeworkAssignedEvent` TOCTOU on AC7's check-then-insert** (Layer 3). Dropped. The upstream `UNIQUE(booking_id, drill_id)` plus the booking state machine make concurrent double-publish of the same newly-persisted ids unrealistic. The remaining case (two replays each saving a different drill, giving two one-drill events) needs two in-flight replays of one completion, and nothing in the code produces that.
- **"Domain events only" contradicted by project-context** (Layer 2). Kept as wording only, not a finding. The story's actual guidance (session publishes, development consumes; development reads `session.repo`) is consistent with the code.
- **Five-state convention misattributed to 12.6** (Layer 2). Informational only; it does not affect any AC of this story (no new page).
- **GDPR async-write-after-erasure race widened by the extra HOMEWORK hop** (Layer 4). Not a new defect. The same window exists for `SESSION_COMPLETED` today, and AC11's scope ("verify, don't change") is correct. Noted for a GDPR follow-up if one is opened.
- **`homework_assignments` not deleted on player erasure** (Layer 4). Out of this story's scope (an existing table not touched here). Not carried as a finding.

## 6. Recommendation

The story is fundamentally sound: the D4 decision, module direction, id spaces, the redaction trap, recap-edit survival, and AC12's non-regression all check out against the code. It should be amended before dev starts on the items below.

| Item | Confidence | Basis |
|---|---|---|
| C1 DRAFT race | High | Read both listeners' full bodies and the status-flip code myself. |
| C2 private drill names, plus the wrong rationale for omitting `coachId` | High on the mechanism; **owner decision** on whether it matters | Re-read the `DrillRepository` query, the second-coach IT, and the `anonymizeMetadataByCoachId` WHERE clause. |
| C3 enrichment failure drops the row | High | Single outer try at `:53-90`, re-read. |
| C4 vacuous replay test | High on the mechanism | Re-read the annotations. Spring semantics confirmed by Layer 3 bytecode check and in-repo comments. |
| C5 timeout path drops homework | High | `QuickCompleteTimeoutService.java:68` `List.of()`, re-read. |
| C6 transactional-boundary trap | Medium-high | No self field confirmed. The Spring 6.1 startup restriction is from Layer 3 and framework knowledge; not executed. |
| C7-C11 | Medium / informational | C7-C10 re-read in code. C11 is a documentation fix. |
| Citation drifts (§1) | High | Re-read `TimelineEventListener.java:50-91` and the V161 index myself. |

**Not independently re-checked by me (relied on a single layer):** the Spring 6.1 `RestrictedTransactionalEventListenerFactory` behaviour and the spring-tx version; `GdprErasureService` line numbers; i18n line numbers in de-DE and fr-FR; the epic and brainstorm line numbers. Nothing was executed (no tests run); this audit is read-only.
