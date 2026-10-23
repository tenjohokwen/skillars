# Story Review — skillars-12-10-coach-player-access-consistency-and-hardening

- **Story file:** `_bmad-output/implementation-artifacts/skillars-12-10-coach-player-access-consistency-and-hardening.md`
- **Audit date:** 2026-10-23
- **Real HEAD (checked by the reviewer):** `7f463eec` — *feat(development): coach/player workspace parity - radar, narrative, neglected skills, targets, reports on coach page (12.9) (#294)*
- Every citation was checked against this HEAD, not against the story's own "verified against master @ 7f463eec" note. In this case the two happen to be the same commit, but the reviewer checked HEAD independently.
- **Method:**
  - Four parallel read-only verification layers: citations, ledger/precedent, mechanistic, corner-case.
  - Then the reviewer adversarially re-read the deciding lines for every finding (Step 4b).
- **Path shorthand used below:**
  - `S/` = `src/main/java/com/softropic/skillars/platform/`
  - `dev/` = `S/development/service/`
  - `CPAS` = `dev/CoachPlayerAuthorizationService.java`

---

## 1. Citation verification

| # | Story citation | Verdict | Evidence / corrected location |
|---|---|---|---|
| 1 | `SluTargetService.setTargets:38` (narrow write) | MATCH | `:38 requireCoachPlayerRelationship(coachId, playerId)` → `CPAS:62-64` uses `ACTIVE_RELATIONSHIP_STATUSES` (`:23-24`, 4 values) |
| 2 | `RadarAssessmentService.submitAssessment:48` | MATCH | `:48 requireCoachPlayerRelationship(coachUserId, playerId)` (Long overload) |
| 3 | `ReportGenerationService.generateReport:167` | MATCH (but see F4) | `:167 requireCoachPlayerRelationship(coachUserId, playerId)`. The method also already has a window check at `:185-205`, which the story does not cite. |
| 4 | `RadarDisplayResource.java:43-61` | MATCH | `:44,:53 @PreAuthorize(HAS_COACH_ROLE)`. Display endpoint at `:36` is `hasRole('ROLE_COACH') or @playerOwnershipGuard...` |
| 5 | `getPreferences/savePreferences` take a UUID | MATCH | `RadarDisplayService:67` `getPreferences(UUID coachId, Long playerId)`. `:73-91` `@Transactional savePreferences(UUID, Long, …)` upserts via `orElseGet`. |
| 6 | `CoachRosterService.java:146` `hasUpcomingBooking` | Line MATCH, semantics WRONG (F1) | `:146 boolean hasUpcomingBooking = latestBookingTime.isAfter(now);`. `latestBookingTime = MAX(b.requestedStartTime)` (`BookingRepository:284`). This is the booking **start**, not its end. |
| 7 | `TimelineQueryService:50` treats null as expired | MATCH | `:50-51 expired = lastSession == null \|\| lastSession.isBefore(Instant.now().minus(expiryDays, DAYS))` |
| 8 | `CoachRosterService.java:81` 4-arg bounded helper | MATCH | `:81-85 configService.getBoundedLong(DEVELOPMENT_ROSTER_MAX_PLAYERS.key(), …default, …min, …max)` |
| 9 | `TimelineQueryService.java:34-42` "2-arg + catch + 90" | DRIFTED (arg count) | The lines are right, but the call is **3-arg**: `:38 getBoundedLong("development.timeline.coachAccessExpiryDays", 1L, 3650L)`, `:39 catch (Exception e)`, `:41 expiryDays = 90L`. |
| 10–17 | Funnel callers: `RadarDisplayService:40`, `SluDashboardService:44,103,133`, `SluContributionService:37`, `SluTargetService:56`, `ReportGenerationService:294`. ForRead callers: `SluTargetService:29`, `RadarAssessmentService:97`, `DevelopmentCorrelationService:52` | MATCH (all) | Each cited line contains the claimed call. A repo-wide grep finds **no other callers** of the three auth methods. |
| 18 | `CPAS:107-108` owner/parent arm, `:23-111` | MATCH | `:106-109` role check `&& !existsByIdAndParentId && !existsByIdAndUserId` → `requireCoachPlayerRelationshipForRead(...)`. The file is 112 lines. |
| 19 | `CoachPlayerLastSessionResolver.java:53-60` (`isRecapAttributable`), `:27-66` | MATCH (wording nuance, F14) | `:53-54 isRecapAttributable(timelineEvent, sluDate, …)`, `if (sluDate == null \|\| !timelineEvent.getOccurredAt().isBefore(sluDate))`. `:34 lastSessionDate = laterOf(sluDate, timelineDate)`. |
| 20 | `PlayerTimelinePanel.vue:19-26` | MATCH | `:19 v-else-if="store.timeline?.accessExpired"` … `:26 $t('development.timeline.accessExpired', …)` |
| 21 | `redactCoachPrivateFieldsIfNotAuthoringCoach :78-98` | MATCH | `:80 if (metadata == null \|\| type != SESSION_COMPLETED) return metadata;`. It removes 6 keys at `:89-96`, and the comment at `:74` says "DENYLIST". |
| 22 | `PlayerTimelinePanel.vue:60-99,185-196` | MATCH | Ratings `:60-68`, recap `:77-99`, drills `:185`. No other frontend code reads timeline metadata. |
| 23 | `ReportGenerationService:294-330` role-first comment; IT runs with no SecurityContext | MATCH | `:308` "The role check MUST come first", `:314` "…with no SecurityContext at all" |
| 24 | `TimelineQueryService.java:33-69` expired shape | MATCH | `:53 new TimelineResponse(true, expiryDays, List.of(), false)` |
| 25 | `CoachRosterService.java:62-175`; cap query uses an unsorted `PageRequest` | MATCH | `:62 RECENCY_FLOOR_DAYS = 730` (a hard-coded constant, not config). `:88-90 PageRequest.of(0, (int) maxPlayers)`. The query text itself has an `ORDER BY` (`BookingRepository:290-291`); only the `PageRequest` is unsorted. |
| 26 | `TimelineEventListener.java:230-242` stamps `Instant.now()` | DRIFTED | The stamp is at **`:249`** `evt.setOccurredAt(Instant.now())`. The 6-arg writer spans `:240-253`; `:230-234` is the delegating 5-arg overload. |
| 27 | `BookingRepository.java:255-285` (roster cap query) | DRIFTED | The comment block starts at `:238`. The `@Query` is at **`:283-292`** and the signature at `:293-298`. |
| 28 | `CoachPublicProfileQueryCountIT` (Hibernate `Statistics`), `QueryRecorderListener` | MATCH | `marketplace/api/CoachPublicProfileQueryCountIT.java:10,72`. `utils/sql/QueryRecorderListener.java` exists. |
| 29 | V139 seeds the key | MATCH | `V139__baseline_seed_data.sql:79 … 'development.timeline.coachAccessExpiryDays', '90'` |
| 30 | `ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS` 1..3650, in `HAS_CODE_DEFAULT` | MATCH | `ConfigBounds.java:188-190` (bounds 1..3650, default 90). Listed in `HAS_CODE_DEFAULT` at `:450`. |
| 31 | Referenced test/frontend/doc files exist | MATCH | `MyPlayersPage.vue:39 role="button"`, `:41 goToPlayerDetail`, `:59` neglected badge. All named ITs/specs, `docs/dev-docs/development/index.html` and the parity specs exist. `pr-build.yml:84 assert-context-count.sh build.log 45`. |
| 32 | `RosterEntryResponse` fields | MATCH (no `accessExpired` yet) | `contract/RosterEntryResponse.java:19-28`. The Javadoc at `:8` still calls `lastSessionDate` "SLU-recorded", which went stale after 12.3. |

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| `deferred-153` "aggregate rewrite the owner rejected" | **Ledger:** deferred-153 story `:221,:452`, and epics "Owner decisions already made". **Code:** `BookingRepository.java:279` (same phrase). **Git:** blame → `6b798c34`. | CONFIRMED |
| "exactly as skillars-12-3 ruled (a booking must not earn history access)" (AC5) | **Ledger:** 12.3 story AC1 / AC8 ("a non-attended wrap-up never earns coach timeline access"). **Code:** `TimelineEventListener:236-239`. **Git:** V160/V161. | **PARTIAL.** 12.3 ruled on *non-attended wrap-ups*. It never addressed open/upcoming bookings, and the `hasOpenBooking` arm is new in 12.10. The "timeline stays attended-only" asymmetry is a new decision, not a precedent (F11). |
| 12.8: drills visible to all, ratings author-only | **Ledger:** 12.8 story `:148,:177`. **Code:** `TimelineQueryService:92-96`, `TimelineEventListener:43`. **Git:** `88363781`. | CONFIRMED |
| 12.9: core-trio page gate; inline preferences error | **Ledger:** 12.9 AC2, AC12(c). **Code:** `CoachPlayerDetailPage.vue:360-371`, `CoachRadarCard.vue:23`, `development.store.js:242-260`. | CONFIRMED |
| Epic 12 gap map G19–G25, D5–D7 | **Ledger:** `skillars-epics.md:3482-3484` (D5–D7), `:3486` (map), `:3508-3514` (G19–G25), `:3628` (12.10). | CONFIRMED. Each gap description matches. The "Pattern #3 … every recently-lapsed player is findable" quote is a paraphrase of the D6 text. The epics G21 row cites `TimelineEventListener.java:111`, which is stale (now `:249`). |
| D5 values: key, default 90, bounds 1..3650; 730 floor; 150 cap | **Code:** `ConfigBounds:188-190,212-214`; `CoachRosterService:62`. **Migration:** V139 `:79`. | CONFIRMED. The 730 floor is a code constant, not config. |
| `chk_bkg_end_after_start` flake; `ReportGenerationServiceIT` has no SecurityContext | **Schema:** `V138:245`. **Ledger:** 12.7 story `:179`, deferred-145/157. **Code:** `CoachPlanNoteResourceIT:271`, `ReportGenerationService:307-316`. **Git:** `f789ce5b`. | CONFIRMED |
| Recent commits `7f463eec` / `88363781` / `05f81d6d` | **Git:** `git log`. | CONFIRMED hashes. "All on `platform.development`" is loose: 12.7 is mostly `booking`, and 12.9 is frontend-only. |
| `sprint-status.yaml` 12-10 entry | **Ledger:** `sprint-status.yaml:2,:408`. | CONFIRMED status. Cosmetic: the per-entry comment lacks the "CREATED 2026-10-23" prefix that the 12-8 and 12-9 entries carry. |

## 3. Mechanistic claims

| Claim | Evidence | Verdict |
|---|---|---|
| Two status lists; READ has 12 values and is shared with the roster by reference | `CPAS:35-38`: 12 values. `CoachRosterService:59-60 ROSTER_MEMBERSHIP_STATUSES = CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES;` | TRUE |
| All checks are status-only `existsByCoachIdAndPlayerIdAndStatusIn` with no time dimension | `CPAS:63-64,88-89`; `BookingRepository:213-224` has no time column | TRUE |
| `requireCoachPlayerRelationshipIfCoach` is the single funnel; role-first; UUID overload avoids a profile lookup | Caller grep (see §1 rows 10–17). The funnel itself calls the **Long** overload (`:109`), so it does one profile lookup per call. | TRUE |
| Without the open-booking arm, the coach could not "open the Session Prep Card" (AC2 rationale) | `SessionPrepService.getPrep:56-62`: `if (!Objects.equals(booking.getCoachId(), coachId)) throw` — authorization is by booking ownership and never reaches the funnel | **FALSE** (F10) |
| `isRecapAttributable` compares raw `occurredAt` vs `calculated_at` | `CoachPlayerLastSessionResolver:54`. It uses raw `sluDate`, **not** `lastSessionDate`, so changing `lastSessionDate` cannot affect the recap decision. | TRUE (the guardrail is already structurally satisfied; F14) |
| Resolver is "2 queries" (Dev Notes fan-out) | `:28` (SLU MAX) and `:29-31` (timeline top-1). Plus `:57 findLastSessionBookingId` when `occurredAt < sluDate`, which the comment at `:48` calls "the NORMAL ordering". | PARTIAL: usually **3** queries |
| `player_timeline_events.reference_id` = booking id; `player_skill_stats.session_id → sessions.booking_id`; no `completed_at` column | `TimelineEventListener:106-110`; `SluRepository:63-71`; `V138:2112` (`booking_id` NOT NULL UNIQUE). `completed_at` exists only on `admin.gdpr_requests` and `session.homework_completions`. | TRUE. Alternative instants exist (`bookings.live_started_at` V167, `session_completion_data.created_at`) — worth listing in open item 2. |
| `writeTimelineEvent` stamps `Instant.now()` | `TimelineEventListener:249`, inside an `@TransactionalEventListener(AFTER_COMMIT) @Async` listener. Timeline writes have **no retry** (failures are logged at `:114-116`). | TRUE (the citation drifted). The "delayed retry" in the AC4 IT can only be simulated by back-dating rows in SQL. |
| 4-arg helper is "the correct call"; Task 1.1 tests a "config clamp" | `ConfigService:115-123` (4-arg): out of range → **returns the default**, no clamp. `:133-146` (3-arg): clamps to the bound; a missing key throws. | **PARTIAL / mislabelled** (F7) |
| `ClockProvider`-consistent timestamps (AC6) | `ClockProvider` is a ThreadLocal. The code under test uses bare `Instant.now()`: `TimelineQueryService:51`, `ReportGenerationService:203`, `TimelineEventListener:249`. | Precondition not met (F9) |
| Metadata keys per event type (AC9 table) | SESSION_COMPLETED: `coachId` always; `drills` + 3 ratings when the player attended; recap keys when present (`TimelineEventListener:72-100`). HOMEWORK_ASSIGNED: `drills`, `coachId` (`:191-193`). RADAR_ASSESSMENT: `skillCodes` only (`:222`). PERFORMANCE_REPORT: `coachName`, `reportId` (`ReportGenerationService:280-283`), with no `coachId`. | TRUE. The "authoring coach" column is moot for RADAR and REPORT, since neither carries a `coachId`. |
| AC1: unrelated PUT creates no row | `savePreferences` is `@Transactional`. A check placed before `findBy…orElseGet` throws before `save`. | TRUE (feasible as written) |

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

### HIGH

**F1. `hasOpenBooking` ("end in the future") is not the roster's `hasUpcomingBooking` ("latest start in the future"). AC7's `accessExpired` and AC3's reads will disagree.**
- **Evidence:**
  - `CoachRosterService:146 hasUpcomingBooking = latestBookingTime.isAfter(now)`
  - `BookingRepository:284 MAX(b.requestedStartTime) AS latestBookingTime`
- **Effect:** during an in-progress or paused session with no in-window attended signal, the roster shows "Lapsed" (unclickable), yet every read returns 200.
- **Fix:**
  - Add `MAX(b.requestedEndTime)` to the cap projection. This costs no extra query.
  - Derive `accessExpired` from that new projection field.
  - Keep `hasUpcomingBooking` for the dormant/caption logic only.
  - Reword AC2's "the same signal" claim.

**F2. A brand-new player is locked out between the end of their first session and completion processing. This defeats AC2's own "non-negotiable" second arm.**
- **Evidence:**
  - The non-LIVE path publishes no `BookingCompletedEvent` at wrap-up (`BookingCompletionService:197-212`, the `else` branch).
  - The event comes later: on parent confirmation (`:283`/`:410`), or at the quick-complete timeout (`QuickCompleteTimeoutService:65`, default `booking.quick_complete_timeout_hours = 24`, `V139:59`).
  - The SLU and timeline listeners are `@Async AFTER_COMMIT`.
- **Effect:** in that gap, `requestedEndTime < now` and there is no attended signal, so every coach read and every D12.10-A write returns 403.
- **Fix:** define "open" as `requestedEndTime >= now - windowDays` for a READ-status booking, or explicitly include `IN_PROGRESS`/`COMPLETED_PENDING_CONFIRMATION`/`COMPLETED`. Add an IT for this gap.

**F3. AC4 would pick the latest-*processed* session, not the latest *session*.**
- **Evidence:** both lookups order by processing time:
  - `PlayerTimelineRepository:39 findTopBy…OrderByOccurredAtDesc`
  - `SluRepository:63-71 … ORDER BY p.calculated_at DESC LIMIT 1`
- **Effect:** mapping the top row to its booking end means a delayed write for an older session (AC4's own scenario) shadows a newer one. That understates session time and wrongly expires the coach.
- **Fix:**
  - Require `MAX(b.requested_end_time)` over **all** attended rows for both signals.
  - Join only through the attendance-gated `coach_id` columns.
  - Add a two-sessions-processed-out-of-order IT.

**F4. `generateReport` already enforces the window, and D12.10-A's premise is false for it.**
- **Evidence:** `ReportGenerationService:185-205`:
  - It resolves `lastSessionDate`.
  - `if (lastSession == null) throw new AccessDeniedException(...)`.
  - The same 3-arg + `catch (Exception)` + `90L` block appears at `:196-202`.
  - `if (lastSession.isBefore(Instant.now().minus(expiryDays, DAYS))) throw new AccessDeniedException(...)`.
  - The comment at `:189-194` explains why this was deliberately duplicated in deferred-153 AC7.
- **Effect:**
  - "A 2-year-old COMPLETED booking would still authorise" is wrong for reports.
  - Layering AC2 (with the open-booking arm) on top produces two checks with different semantics and exception types (`AccessDeniedException` vs `OperationNotAllowedException`).
  - It would also *loosen* today's null-session denial if this copy is replaced.
- **Fix:**
  - Have Task 1.3/2.4 replace `:185-205` with the shared rule.
  - Decide explicitly whether a booked-never-attended player may receive a report (today: no).
  - Update `ReportGenerationServiceTest:112-113,230`, which stub the **3-arg** `getBoundedLong`. Switching to the 4-arg helper breaks those stubs silently (Mockito returns `0L`).

**F5. Existing ITs will go red, and the story does not list them.**
- **Evidence:**
  - `SkillExposureResourceIT:128-139` seeds only a COMPLETED booking that ended an hour ago, with no `player_skill_stats` or timeline rows (grep shows none in that file).
  - After AC3, the coach-200 assertions become 403. The corner-case layer reports the same fixture shape in `CoachPlayerAuthorizationIT:208-219` and `RadarAssessmentResourceIT:109`. The reviewer re-confirmed only `SkillExposureResourceIT`; the other two are PLAUSIBLE.
  - The targets, correlation and report ITs are likely affected too.
- **Fix:** add a task to update fixtures: seed an attended SLU or timeline row (wrapped in `transactionTemplate`), or a future-ended booking. Forbid weakening the assertions.

### MED

**F6. Roster `lastSessionDate` has two more consumers that AC4 silently changes.**
- **Evidence:** `SessionPrepService:67,130,138` shows `lastSessionDate` and days-since on the prep card. `ReportGenerationService:185` uses it as the window input (see F4).
- **Effect:** AC4 changes what the prep card displays. That is probably desirable, but the story should state it and update `SessionPrepServiceTest`.

**F7. Switching the timeline read to the 4-arg helper changes behaviour for bad values.**
- **Evidence:**
  - The 3-arg form clamps (0 → 1, roughly "always expired").
  - The 4-arg form falls back to the default (0 → 90).
  - Both statements are false or stale after the change:
    - the note at `ConfigBounds.java:190`: "0/neg → coach development-timeline access reads as always expired"
    - the Javadoc at `:62-68`: "the one exception being TIMELINE_COACH_ACCESS_EXPIRY_DAYS"
- **Fix:** either accept the change and update the note and Javadoc, or have the helper clamp. Relabel Task 1.1's "config clamp" test.

**F8. AC8's "+10 players → exactly +10·k statements" cannot hold at N = cap, and `k` is not constant.**
- **Evidence:**
  - At N = cap, 10 extra players are cut by `PageRequest.of(0, maxPlayers)`.
  - The resolver's third query fires conditionally (`CoachPlayerLastSessionResolver:54-57`).
  - AC4 adds a per-player booking-end query.
- **Fix:**
  - Lower the cap in the IT (the precedent is `CoachRosterResourceIT`'s `MAX_PLAYERS_KEY` reset pattern).
  - Measure at cap−10 and at cap, and assert that cap+10 equals cap.
  - Seed every player identically.

**F9. The AC6 boundary ITs will be fragile.** Bare `Instant.now()` at `TimelineQueryService:51` and `ReportGenerationService:203`; `ClockProvider` is a ThreadLocal.
- **Fix:** the shared rule should take `now` from `ClockProvider.getClock()`. Use margins of minutes, not seconds, for "just inside/outside".

**F10. The AC2 rationale ("open the Session Prep Card") is false.** `SessionPrepService.getPrep:60` gates on booking ownership and never reaches the funnel. The open-booking arm still matters for radar, targets and the other reads, but the Prep Card is not why.

**F11. The AC5 "exactly as 12.3 ruled" precedent is overstated.** See §2. Under AC2 an open-booking coach can read radar, narrative and contributions, while the timeline says `accessExpired`.
- **Fix:** record this as a new owner decision.

**F12. The detail page cannot tell a 403 apart (AC7 / Task 3.2), and does not distinguish "no relationship" from "window closed".**
- **Evidence:** `development.store.js:103-110` stores only a message string (`err?.response?.data?.errorMsg?.message`), with no status. Both cases throw the same `OperationNotAllowedException`.
- **Fix:**
  - Add a per-section status to the store and list it in Task 3.2.
  - Consider a distinct error code for "window closed", so an unrelated coach's deep link does not show "access window ended".

**F13. Lapsed roster entries still ship data the coach can no longer read.** `RosterEntryResponse` keeps `openNeglectedSkillCount`, `dormant` and `attentionScore` (`CoachRosterService:166-174`); AC7 only hides them in the UI.
- **Fix:** zero or null them server-side for expired entries, which fits the story's "minimal" goal. Say whether their per-player queries are skipped (this feeds F8's formula).

### LOW

- **F14. AC4 guardrail wording.** "Do not rewrite what `lastSessionDate` feeds into the recap decision": `lastSessionDate` does not feed it; the recap decision uses raw `sluDate` (`:37,:54`). The guardrail is harmless but misleading. Reword it to "keep `isRecapAttributable` on raw `sluDate`/`occurredAt`".
- **F15. Session time can be in the future.** Completion is gated on *start*, not end (`BookingCompletionService:464-470 verifyNotBeforeStartTime`). An early wrap-up then yields a future `requestedEndTime`, which makes roster `daysSince` ≤ 0. Consider `min(requestedEndTime, processingTime)`.
- **F16. "Parents/players are never windowed" holds only for the funnel's arm.**
  - `PlayerTimelineResource:38` sets `isCoach` by role alone. A coach who is also the player's parent gets windowed and redacted on the timeline.
  - The `ForRead` overloads (`CPAS:75-93`) have no owner arm.
  - This is pre-existing behaviour; the story should just not over-claim.
- **F17. Open-booking statuses.**
  - `REQUESTED`/`DECLINED`/`CANCELLED*` are excluded from READ (`CPAS:35-38`), which is good.
  - An unpaid `ACCEPTED`/`PAYMENT_PENDING` booking months out does open reads. That is still a narrowing versus today's untimed check.
  - Worth one line in the story.
- **F18. The "Lapsed" group includes players who were never trained**, captioned "Booked, but no attended session yet" (`MyPlayersPage.vue:109-115`). The empty state when *all* players are lapsed (`:29`) needs copy and a decision.
- **F19. Housekeeping.**
  - D12.10-B says "See AC8"; the allowlist is **AC9**.
  - Fix the citations: `TimelineEventListener:240-253` (stamp at `:249`) and `BookingRepository:238-298`.
  - Dev Notes "2-arg" → "3-arg".
  - Resolver "2 queries" → "2–3".
  - The `RosterEntryResponse:8` Javadoc is stale.
  - Performance hint: evaluate the cheap open-booking exists-query before the 2–3-query resolver. The detail page fires about 10 windowed endpoints.

## 5. What did not survive re-verification

- **"AC9 allowlist regresses 12.7/12.8 recap visibility for parents" (raised as a risk to check):** killed. Recap and ratings are already stripped for every non-author today (`TimelineQueryService:88-96`). The only player/parent-visible loss is `coachId`, which no frontend reads (grep).
- **"Unlisted callers of the auth funnel (scheduled jobs, booking, homework, admin) would be broken by AC3":** killed. A repo-wide grep finds only the listed callers, all of them HTTP-only.
- **"`player_skill_stats.session_id` null breaks the AC4 join":** downgraded to non-issue. The column is nullable, but its only writer always sets it (`SluCalculationService:162`), and Quick-Complete writes no SLU rows at all.
- **"Recap edits (12.7) create a second SESSION_COMPLETED row and skew the clock":** killed. Edits update in place (`PlayerTimelineRepository:107-114`).
- **Sprint-status entry missing the "CREATED" prefix; "all recent commits on `platform.development`":** downgraded to cosmetic. They are noted in §2 and not counted as findings.
- **The Layer 1 claim that the AC4 guardrail is "wrong":** downgraded to wording only (F14). It does not cause a defect.

## 6. Recommendation

The story is **not ready to implement as written**. The direction is sound, and every citation resolves (3 drifted and are corrected above), but the access rule at its core has four substantive defects. Fix these before `/bmad-dev-story`:

1. **F1 + F2.** Redefine `hasOpenBooking`: base it on booking end, cover the completion-processing gap, and make the roster derive the same flag.
2. **F3.** Compute session time as `MAX(requested_end_time)` over attended rows, not from the latest-processed row.
3. **F4.** Fold `ReportGenerationService:185-205` into the shared rule, decide the never-attended report case, and fix the 3-arg Mockito stubs.
4. **F5.** Add an explicit fixture-update task for the existing ITs that will flip to 403.

Then the MEDs: F7 (config semantics plus ConfigBounds docs), F8 (a query-count IT design that can actually pass), F12 (store status for the 403 state), F13 (server-side minimisation), and the F10/F11 wording.

**Confidence by claim:**

| Findings | Confidence | Why |
|---|---|---|
| F1, F3, F4, F7, F10 | High | The reviewer re-read the deciding lines personally in Step 4b (quoted above). |
| F2 | High on mechanism, medium on user impact | The 24h default is confirmed, but how often non-LIVE wrap-ups occur was not measured. |
| F5 | High for `SkillExposureResourceIT` (re-checked), medium for the other two files | Those two were taken from the corner-case layer and not re-opened. |
| F8, F9, F12, F13 | Medium-high | Mechanism confirmed; the size of the impact depends on implementation choices. |
| LOWs | Medium | Mostly wording or pre-existing behaviour. |

**Not independently re-checked by the reviewer:**
- the i18n parity spec mechanics
- the `assert-context-count.sh` ceiling value's headroom
- `CoachPlayerAuthorizationIT`/`RadarAssessmentResourceIT` fixture line numbers
- whether `hibernate.generate_statistics` is enabled in the IT profile (the precedent IT uses it, so it presumably is)

**Owner decisions still open (from the story):** D12.10-A/B/C, plus two new ones:
- the F11 timeline/read asymmetry
- whether a booked-but-never-attended player gets a report (F4)
