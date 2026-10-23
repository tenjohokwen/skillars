# Story 12.10: Coach↔Player Access Consistency and Hardening

Status: done

<!-- Source: _bmad-output/planning-artifacts/skillars-epics.md, Epic 12, Story 12.10 (gaps G19-G25, decisions D5-D7). Citations verified against master @ 7f463eec on 2026-10-23. D12.10-D5/D6/D7 below were CONFIRMED by the owner on 2026-10-23; D12.10-A/B/C/D were CONFIRMED by the owner on 2026-10-23. -->

## Story

As a **platform engineer**,
I want **one coherent coach-access rule across the roster, the timeline and every per-player read, and no endpoint without a relationship check**,
so that **what a coach can see about a player is predictable, minimal and secure**.

## Owner decisions (CONFIRMED 2026-10-23 — do NOT re-litigate)

| # | Decision | Chosen | Consequence |
|---|---|---|---|
| D5 | One coach-access window | **90 days everywhere**, from the existing key `development.timeline.coachAccessExpiryDays` (default 90, bounds 1..3650, `ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS`), measured from the **actual session time** (the booking's end), not processing time (G21). *Interpretation (story-review F1/F2, see D12.10-D):* the timeline and report generation keep the attended-session clock; the other reads use the booking-end clock. | Reads that are unbounded today (exposure, narrative, trends, contributions, neglected skills, radar, reports, targets, correlation, radar entries) and the roster's detail access gain the window. The 730-day roster floor stays for *membership* only (D6). |
| D6 | Lapsed players | **"Lapsed" group on the roster, name + days-since only, no detail link.** Players past the D5 window stay listed (within the existing 730-day floor and 150-player cap) with `accessExpired=true`. | Pattern #3 ("every recently-lapsed player is findable") met without re-opening data access. Players past 730 days or past the cap stay invisible (G22 closed by this recorded decision for that tail). |
| D7 | Roster N+1 | **Keep the cap; add a query-count regression test at the cap.** | No batching rewrite (the `deferred-153` "aggregate rewrite the owner rejected" note stands). Test locks the bound. |

### Author defaults — CONFIRMED by owner 2026-10-23 as written (do NOT re-litigate)

- **D12.10-A (window applies to the other two writes).** `SluTargetService.setTargets:38` and `RadarAssessmentService.submitAssessment:48` get the window check, measured over their own `ACTIVE_RELATIONSHIP_STATUSES` (code review 2026-10-23, owner decision: one `MAX(requestedEndTime)` aggregate over the write statuses replaces the old `exists` query, so a recent `DISPUTED`/`NO_SHOW_COACH`/`REFUND_PENDING`/`PAYMENT_PENDING` booking cannot re-open writes). Without it a coach could set targets/assess for a player whose reads 403, and a 2-year-old `COMPLETED` booking would still authorise those writes. `ReportGenerationService.generateReport` is **already** windowed (`:185-205`, `deferred-153` AC7) and is refactored onto the shared helper (Task 1.4); because it calls the same write overload it also passes the write-status pre-check first, which can only refuse where its own attended-session check would also refuse (an attended session's booking is always a write status) -- accepted, both are 403. Reverting = drop Task 2.4.
- **D12.10-D (two clocks, new decision — story-review F11).** Reads use *booking end* (`MAX(requestedEndTime)` over `READ_RELATIONSHIP_STATUSES` bookings): this also covers an in-progress session and the gap between wrap-up and completion processing (a non-LIVE wrap-up publishes `BookingCompletedEvent` only on parent confirmation or the 24h quick-complete timeout, `BookingCompletionService:283,410`). Timeline and report generation keep the **attended-session** clock (12.3's rule that a non-attended wrap-up never earns history/report access). Consequence: a coach with a recent but never-attended booking can read radar/exposure but sees `accessExpired` on the timeline and cannot generate a report (today's behaviour for both). Also: any READ-status booking ending in the future (incl. unpaid `ACCEPTED`/`PAYMENT_PENDING`) opens reads; that is still narrower than today's untimed check.
- **D12.10-B (metadata allowlist contents).** See AC9. Strip `coachId` from every event for every non-authoring viewer; `PERFORMANCE_REPORT` `coachName`/`reportId` are visible to the player/parent (they were emailed the report) and hidden from every coach viewer. Reverting = edit the allowlist table only.
- **D12.10-C (PUT preferences check).** `PUT /radar/preferences` uses the same windowed *read* predicate as GET (it stores a display preference, not clinical data), not the narrow write list.

## Acceptance Criteria

### G19 — preferences endpoints

1. `GET` and `PUT /api/development/players/{playerId}/radar/preferences` (`RadarDisplayResource.java:43-61`) return **403** for a coach with no qualifying relationship to the player (D12.10-C predicate). Check lives in `RadarDisplayService.getPreferences/savePreferences` (they already receive `coachId` as a `UUID`, so use the `UUID` overload of the authorization service — no extra profile lookup). ITs: no relationship → 403 on GET and PUT; related coach → 200/204; PUT by an unrelated coach must **not** create a `coach_radar_preferences` row (assert row count).

### G20 / G21 / G24 — one window

2. **Single definition.** A pure, unit-testable rule `isOpen(clockInstant, now, windowDays)` = `clockInstant != null && !clockInstant.isBefore(now - windowDays)`, with `now` taken from `ClockProvider.getClock()` (not bare `Instant.now()`). For **reads** `clockInstant` = `MAX(b.requestedEndTime)` over the pair's `READ_RELATIONSHIP_STATUSES` bookings (one aggregate query, evaluated *before* any resolver call); a future end therefore counts as open. **This is NOT `CoachRosterService`'s `hasUpcomingBooking`** (`:146`, based on `MAX(requestedStartTime)`, `BookingRepository:284`): add `MAX(b.requestedEndTime) AS latestBookingEndTime` to `RosterPlayerActivityProjection`/the cap query (no extra query) and derive roster `accessExpired` from it, keeping `hasUpcomingBooking` for the dormant/caption logic only. **Why booking end and not only attended sessions:** a brand-new player (booked, nothing attended) has `lastSession == null`, and between wrap-up and completion processing there is no attended signal at all — without this the coach would 403 on radar/targets/exposure for a player they are actively training. (The Session Prep Card is *not* affected: `SessionPrepService.getPrep:60` authorises by booking ownership and never reaches this funnel.) Timeline and report generation use the same `isOpen` with the **attended-session** instant (AC4); `null` → closed. The window length comes from one helper (4-arg `getBoundedLong(key, default, min, max)` with `ConfigBounds`, as `CoachRosterService.java:81` does) used by roster, timeline, authorization and report generation; delete the 3-arg + `catch (Exception)` + hard-coded 90 fallback at `TimelineQueryService.java:34-42` **and** its duplicate at `ReportGenerationService.java:196-202`. **Behaviour change to accept and document:** the 3-arg form *clamps* an out-of-range value (0 → 1), the 4-arg form *falls back to the default* (0 → 90, `ConfigService:115-123`). Update the stale note at `ConfigBounds.java:190` ("0/neg → … always expired") and the Javadoc at `:62-68` that names this key as the exception.
3. **Every coach read applies it.** `CoachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach` (the single funnel for `RadarDisplayService:40`, `SluDashboardService:44,103,133`, `SluContributionService:37`, `SluTargetService:56` neglected-skills, `ReportGenerationService:294` list) and `requireCoachPlayerRelationshipForRead` (`SluTargetService:29` targets, `RadarAssessmentService:97` entries, `DevelopmentCorrelationService:52`, plus AC1) additionally enforce AC2 → 403 (`OperationNotAllowedException`, same as today) when the window is closed. Existing `READ_RELATIONSHIP_STATUSES` membership check stays first. The self-owner/parent arm of `requireCoachPlayerRelationshipIfCoach` (`:107-108`) is untouched, so parents/players reaching that funnel are never windowed (the `ForRead` overloads have no owner arm, and `PlayerTimelineResource:38` decides `isCoach` by role alone — pre-existing, not changed here). Perf: the detail page fires ~10 windowed endpoints; run the single booking-end query first and only then anything heavier.
4. **Session time, not processing time (G21).** The attended-session instant (timeline, report generation, roster `lastSessionDate`/`daysSinceLastSession`) is the **latest booking `requestedEndTime` over ALL attended rows**, for both signals (`player_timeline_events.reference_id` = booking id for coach-attributed `SESSION_COMPLETED`; `player_skill_stats.session_id → session.sessions.booking_id`). It must be `MAX(b.requested_end_time)` joined through the attendance-gated `coach_id` columns — **not** "take the latest-processed row (`ORDER BY occurred_at/calculated_at DESC LIMIT 1`, as `PlayerTimelineRepository:39` / `SluRepository:63-71` do today) and map it to its booking end": a delayed write for an older session would shadow a newer one. Cap each value at `now` (`LEAST(end, now)`) because wrap-up is gated on *start*, not end (`BookingCompletionService:464-470`), so an early wrap-up has a future end. Fall back to the processing timestamp only if the booking cannot be resolved. `CoachPlayerLastSessionResolver.resolve` returns it as `lastSessionDate`. **Guardrail:** `isRecapAttributable` (`:53-60`) already uses the raw `sluDate` and `occurredAt`, not `lastSessionDate`; keep it on those raw signals. Side effect to state and test: `SessionPrepService` (`:67,130,138`) displays this `lastSessionDate`, so the prep card's last-session date/days-since now reflect session time — update `SessionPrepServiceTest`. IT: two sessions processed out of order (older one written last) → the newer session's end wins. `PlayerTimelineEvent.occurredAt` (feed ordering) is **not** changed and no migration/backfill is needed. ITs: a session whose timeline row and SLU row were written 100 days after the booking's end (simulated by back-dating the rows in SQL — timeline writes have no retry) is *outside* the window; the same row written seconds after is inside.
5. **Timeline.** Contract unchanged: an expired coach still gets `200 {accessExpired:true, accessExpiryDays}` (not 403) — `PlayerTimelinePanel.vue:19-26` depends on it. The timeline does **not** get the `hasOpenBooking` arm — it stays attended-session-only. This extends `skillars-12-3` (which ruled only that a *non-attended wrap-up* never earns access) to open bookings and is recorded as D12.10-D, not a pre-existing precedent. Document the one deliberate asymmetry with AC2 in the Javadoc of the shared rule (timeline calls it with `hasOpenBooking=false`).
6. **Boundary ITs (G20/G24)** for a player lapsed just inside / just outside the window (use `ClockProvider`-consistent timestamps, not two `Instant.now()` calls — see the booking-fixture CHECK flake), asserted on **each** surface: timeline, exposure, narrative, trends, contributions, neglected-skills, radar display, radar entries, targets GET, correlation, reports list, preferences, **and** roster `accessExpired`. Plus the three writes (D12.10-A) → 403 outside the window.

### G22 — lapsed roster group (D6)

7. `RosterEntryResponse` gains `boolean accessExpired`, computed in `CoachRosterService.buildEntry` from the **same** AC2 rule using the already-resolved `lastSessionDate` and `hasUpcomingBooking` (no extra queries; `accessExpired` uses the AC2 booking-end clock). For an expired entry the server **nulls/zeroes** `openNeglectedSkillCount`, `attentionScore` and `dormant` and skips its neglected-count and weekly-snapshot queries (data the coach can no longer read; also lowers the AC8 per-player query count). Sorting: non-expired by `attentionScore` desc (unchanged), then expired by last-session date desc (null last). Frontend `MyPlayersPage.vue` renders expired entries in a separate "Lapsed" section: name and the existing last-trained caption only, **not** clickable (no `goToPlayerDetail`, no `role="button"`), no neglected-skill badge. A never-trained lapsed row keeps the existing caption "Booked, but no attended session yet". If **every** player is lapsed, show the normal header plus the Lapsed group and a translated hint (not the `myPlayersEmpty` state). `CoachPlayerDetailPage.vue`: if a core fetch 403s (deep link / bookmark / window closed mid-session) show a translated neutral "you no longer have access to this player's development data" state instead of the generic page error (the server throws the same `OperationNotAllowedException` for no-relationship and window-closed, so the copy must not claim which). The store keeps only a message string today (`development.store.js:103-110`); add a per-section HTTP status (or `forbidden` flag) for the three core fetches. i18n en-US/de-DE/fr-FR (literal keys; parity specs). Roster IT: a player 100 days lapsed appears with `accessExpired=true`; 731 days lapsed is absent (floor); `CoachRosterServiceTest` covers ordering.

### G23 — roster query bound (D7)

8. New IT (precedent: `CoachPublicProfileQueryCountIT` — Hibernate `Statistics`; `QueryRecorderListener` exists for datasource-proxy) that **lowers `development.roster.maxPlayers`** to a small cap C (reset pattern from `CoachRosterResourceIT`'s `MAX_PLAYERS_KEY`), seeds every player identically, and asserts: statements at C−10 and at C players follow the documented `k·N + c` (measure first, pin with small headroom; `k` is not strictly constant because the resolver's third query is conditional), and **C+10 players costs the same as C** (the cap bounds the fan-out). Cover one expired entry (fewer statements, AC7). No production batching change.

### G25 — timeline metadata allowlist

9. Replace `TimelineQueryService.redactCoachPrivateFieldsIfNotAuthoringCoach` (`:78-98`, a denylist limited to `SESSION_COMPLETED`) with a per-event-type **allowlist** applied to every non-authoring viewer (other coach, player, parent):

| Event type | Authoring coach | Other coach | Player / parent |
|---|---|---|---|
| `SESSION_COMPLETED` | all keys (incl. `coachId`) | `drills` | `drills` |
| `HOMEWORK_ASSIGNED` | all keys | `drills` | `drills` |
| `RADAR_ASSESSMENT` | `skillCodes` | `skillCodes` | `skillCodes` |
| `PERFORMANCE_REPORT` | none | none | `coachName`, `reportId` |
| any other / unknown type | none | none | none |

   Authorship = `metadata.coachId` equals the viewer's coach id (as today); events without `coachId` have no authoring coach. Unknown keys are dropped by default, so a future free-text key cannot leak. `metadata == null` stays null-safe (`PlayerTimelineResourceIT` seeds none). The frontend reads only `drills`, the three ratings and `whatWeDid/whatsNext/whatWentWell` (`PlayerTimelinePanel.vue:60-99,185-196`) — no frontend change expected; verify with grep. Tests: `TimelineQueryServiceTest` per cell of the table, plus a mutation-style test that adds an unknown key (`"secretNote"`) to each event type and asserts it is dropped for every viewer except the authoring coach's own `SESSION_COMPLETED`/`HOMEWORK_ASSIGNED`.

### Cross-cutting

10. **Docs:** update `docs/dev-docs/development/index.html` (access rules, roster `accessExpired`) and the Epic 12 "Gap → story map" rows G19-G25 → done; `skillars-epics.md` Story 12.10 gets a "Resolved" note with D5-D7.
11. **No new Flyway migration** (no schema change; if the dev finds one is needed, stop and ask). Config key unchanged.

## Tasks / Subtasks

- [x] Task 1: Shared window rule (AC: #2, #4, #5)
  - [x] 1.1 New small class in `platform.development.service` (e.g. `CoachAccessWindow`) with the pure rule + a `windowDays()` reading config via the 4-arg bounded helper. Unit tests: boundary (== cutoff is open), null last session with/without open booking, out-of-range config falls back to the code default (4-arg helper, no clamp).
  - [x] 1.2 Repository queries: attended-session time = `MAX(requested_end_time)` over all attended rows for both signals (AC4); booking-end clock for reads (AC2). `CoachPlayerLastSessionResolver` returns session time while `isRecapAttributable` keeps raw signals. Update `CoachPlayerLastSessionResolverTest`, `SessionPrepServiceTest`.
  - [x] 1.3 `TimelineQueryService`: use the shared helper; remove the hard-coded fallback. Update the `ConfigBounds` note/Javadoc (AC2).
  - [x] 1.4 `ReportGenerationService:185-205`: replace the duplicated window block with the shared helper, **keeping** its semantics (null attended session → denied, `AccessDeniedException`). Fix `ReportGenerationServiceTest:112-113,230`, which stub the **3-arg** `getBoundedLong` (a missed stub silently returns `0L`).
- [x] Task 2: Enforce on every read/write (AC: #1, #3, #6)
  - [x] 2.1 `CoachPlayerAuthorizationService`: add window enforcement to both read overloads and `requireCoachPlayerRelationshipIfCoach`; resolve `hasOpenBooking` with one `BookingRepository` exists-query (future-dated end, `READ_RELATIONSHIP_STATUSES`).
  - [x] 2.2 `RadarDisplayService.getPreferences/savePreferences` relationship check (G19).
  - [x] 2.3 Boundary ITs on every surface (AC6), 403 ITs for preferences (AC1). Extend `CoachPlayerAuthorizationServiceTest`; the HTTP boundary cases live in the new `CoachAccessWindowIT` (`CoachPlayerAuthorizationIT` unchanged, still green).
  - [x] 2.4 (D12.10-A) Window on `setTargets` and `submitAssessment`.
  - [x] 2.5 Audit existing ITs for coach-200 assertions on fixtures with old bookings or relying on the untimed check (`SkillExposureResourceIT` seeds a booking ended 1h ago and stays green under the booking-end clock; check `CoachPlayerAuthorizationIT`, `RadarAssessmentResourceIT`, targets/correlation/report ITs). Fix fixtures with a recent booking end (in `transactionTemplate`, one `Instant` base for start/end); never weaken assertions.
  - [x] 2.6 IT for the completion gap: booking ended minutes ago, non-LIVE wrap-up submitted (no event yet) → reads 200.
- [x] Task 3: Roster (AC: #7, #8)
  - [x] 3.1 `accessExpired` + ordering in `CoachRosterService`/`RosterEntryResponse`; update `CoachRosterServiceTest`; the roster `accessExpired` HTTP cases live in `CoachAccessWindowIT` (`CoachRosterResourceIT` unchanged, still green).
  - [x] 3.2 `MyPlayersPage.vue` Lapsed section + `CoachPlayerDetailPage.vue` 403 state (+ store status) + i18n x3 + Vitest (`MyPlayersPageSpec.js`, detail page spec).
  - [x] 3.3 Query-count IT at the cap (AC8).
- [x] Task 4: Allowlist (AC: #9) — rewrite redaction, tests per table cell + unknown-key test.
- [x] Task 5: Docs + sprint bookkeeping (AC: #10, #11).

### Review Findings

_Code review 2026-10-23 (bmad-code-review: Blind Hunter + Edge Case Hunter + Acceptance Auditor), uncommitted changes on `feat/skillars-12-10-coach-player-access-consistency` vs `master` @ 7f463eec._

- [x] [Review][Patch] Measure the write window over `ACTIVE_RELATIONSHIP_STATUSES` (decision resolved 2026-10-23, option 1): replace `exists(ACTIVE)` + READ-status window in `requireCoachPlayerRelationship(UUID, Long)` with one `MAX(requestedEndTime)` aggregate over the write statuses (null → no relationship, closed → window ended), so `DISPUTED`/`NO_SHOW_COACH`/`REFUND_PENDING`/`PAYMENT_PENDING` cannot re-open writes. `generateReport` keeps its attended-session `AccessDeniedException` check; the write-overload pre-check is accepted (it can only fire where the attended check would also deny), so correct D12.10-A and the Completion Notes wording. [src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java:64-73]
- [x] [Review][Patch] Query-count IT pins only the cheapest path; "exactly 4 statements per roster player" is overstated. Seeded players have no SLU/timeline rows, so the resolver's new `findLastSessionEnd` pair and the conditional `findLastSessionBookingId` never run. A real attended player costs about 6-7 statements, and AC8's "cover one expired entry" is missing. Add an attended-player seed with its own pinned per-player budget, add an expired entry, and correct the "4 per player" wording in the dev-docs callout, the epics Resolved note and the Dev Agent Record. [src/test/java/com/softropic/skillars/platform/development/api/RosterQueryCountIT.java:110]
- [x] [Review][Patch] Missing real-DB coverage required by AC4/AC6. (1) `SluRepository.findLastSessionEnd` (native `LEAST/COALESCE` join through `session.sessions` → `booking.bookings`) never runs against Postgres; add an SLU-only case and a `session_id = NULL` fallback case. (2) AC4: add the SLU-side delayed write and the "written seconds after → inside" counterpart. (3) AC6: add just-inside/just-outside cases for timeline, roster `accessExpired`, and the windowed writes (`setTargets`, `submitAssessment`); radar entries/correlation can be reached by seeding a qualifying tier. [src/test/java/com/softropic/skillars/platform/development/api/CoachAccessWindowIT.java]
- [x] [Review][Patch] de-DE `playerDetailAccessEnded` uses informal "Du hast…" while every surrounding coach string uses "Sie/Ihnen" [src/frontend/src/i18n/de-DE/index.js:332]
- [x] [Review][Patch] `ConfigBounds` Javadoc still says "17 of the 18 via the 4-arg … overload"; after this change it is all 18 [src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:61]
- [x] [Review][Patch] `RosterEntryResponse` Javadoc still says `lastSessionDate` is null "if this coach has no SLU rows"; timeline-only attended sessions now count too [src/main/java/com/softropic/skillars/platform/development/contract/RosterEntryResponse.java:9]
- [x] [Review][Patch] Window-closed unit tests assert only the exception type (shared with "no relationship") and `stubReadClock` stubs `isOpen` leniently. Assert the message or verify `isOpen` was consulted, so a wrong-path throw cannot pass [src/test/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationServiceTest.java]
- [x] [Review][Patch] "Parents are never windowed" is asserted on `/exposure` only; loop `READ_PATHS` (plus `/radar/preferences` is coach-only, so skip it) for the 400-day-old parent case [src/test/java/com/softropic/skillars/platform/development/api/CoachAccessWindowIT.java]
- [x] [Review][Patch] Story bookkeeping. Task 1.1 still says "config clamp" (the test checks default fallback). Tasks 3.1/2.3 are ticked for `CoachRosterResourceIT`/`CoachPlayerAuthorizationIT`, which were not changed (`CoachAccessWindowIT` was added instead). Record that skill definitions are deliberately excluded from the `*Forbidden` flags (global endpoint). [_bmad-output/implementation-artifacts/skillars-12-10-coach-player-access-consistency-and-hardening.md:72]
- [x] [Review][Defer] Timeline `referenceId` is sent to every viewer (report id on `PERFORMANCE_REPORT`, another coach's booking id on `SESSION_COMPLETED`/`HOMEWORK_ASSIGNED`), outside the AC9 metadata allowlist [src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java:49-52] — deferred, pre-existing
- [x] [Review][Defer] Timeline authorship is determined from `metadata.coachId`, not the row's `coach_id` column; a row without the metadata key hides everything from its own author [src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java:80-81] — deferred, pre-existing
- [x] [Review][Defer] An out-of-range `development.timeline.coachAccessExpiryDays` silently becomes 90 (accepted in AC2); no `ConfigStartupAssertion` rejection exists to make an operator's "cut access" value fail loudly [src/main/java/com/softropic/skillars/platform/development/service/CoachAccessWindow.java:30-36] — deferred, pre-existing

## Dev Notes

### Current state of files being modified (read before editing)

- `CoachPlayerAuthorizationService.java` — two status lists: narrow `ACTIVE_RELATIONSHIP_STATUSES` (writes) and `READ_RELATIONSHIP_STATUSES` (12 values, shared with the roster by reference). All checks are `bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn` with **no time dimension** — that is G24. Preserve: the self-owner/parent arm, the `UUID` overload that avoids a profile lookup, role-first short-circuit (`ReportGenerationService:294-330` comment explains why ordering matters; `ReportGenerationServiceIT` calls with no `SecurityContext`).
- `RadarDisplayResource.java:43-61` — preferences endpoints gated by `HAS_COACH_ROLE` only. `getRadarDisplay` is `ROLE_COACH or playerOwnershipGuard` and delegates the relationship check to the service. Do **not** also add `@PreAuthorize` relationship logic in the resource; keep the service as the single enforcement point.
- `TimelineQueryService.java:33-69` — expired response shape `TimelineResponse(true, expiryDays, List.of(), false)` is consumed by the frontend; keep.
- `CoachRosterService.java` — cap query `BookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn` (730-day floor, `PageRequest.of(0, maxPlayers)` **unsorted** by design — `deferred-153`), per-player `buildEntry` fan-out (resolver 2-3 queries + neglected count + weekly snapshots ≈ 4-5/player). Preserve `hasUpcomingBooking`, dormant logic and the missing-profile filter.
- `TimelineEventListener.java:240-253` — `writeTimelineEvent` stamps `Instant.now()` at `:249`; unchanged by this story.
- `ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS` is registered in `HAS_CODE_DEFAULT` — the 4-arg helper is the correct call; the seeded DB value is in `V139`.

### Pitfalls (project memory)

- IDs: `Booking.playerId` is a `PlayerProfile` PK, not a user id. Parent/player identity goes through `PlayerProfile.userId/parentId`.
- IT fixtures: bare `jdbcTemplate` writes in a test method body never commit (Hikari auto-commit=false) — wrap in `transactionTemplate`. Never create booking `start`/`end` from two separate `Instant.now()` calls (`chk_bkg_end_after_start` CI-only flake).
- Roster/cap ITs mutate `development.roster.*` config rows — follow `CoachRosterResourceIT`'s reset pattern so the shared Spring context stays clean (`assert-context-count.sh` ceiling in `pr-build.yml`; avoid `@MockitoSpyBean`/new context forks).
- GDPR: do not add a new place that stores coach/player data; the V166 `coach_profiles` tombstone and coach-scrub ordering notes (memory `GDPR Coach Erasure Gap`) are unaffected, but the new booking-end queries must not resurrect scrubbed rows' identity.
- Run `mvn verify` locally: **no** (CI is the gate). Targeted `-Dtest=`/`-Dit.test=` runs and Vitest are fine. Prettier gate applies to the frontend.

### Testing standards

JUnit5 + AssertJ; ITs extend `AbstractIntegrationTest` with `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` (see `CoachRosterResourceIT`). Frontend: Vitest + `@vue/test-utils`, `localeKeyParitySpec`/`i18nTemplateKeyResolutionSpec` (literal `$t` keys only).

### Previous-story intelligence

- 12.9 (frontend-only): the coach detail page now isolates secondary-card failures; core trio (skill definitions / exposure / contributions) feeds the page-level gate — the 403 "access ended" state hooks into that gate. Radar preference sync rules (AC12) mean a 403 on preferences must show the inline preferences error, not break the chart.
- 12.8: timeline `drills` visible to all viewers (owner decision), ratings author-only — encoded in the AC9 table.
- 12.3: merged-clock + attendance-gated `coach_id` column; non-attended wrap-ups must never earn access (preserved: AC4 only counts attended signals).
- Recent commits `7f463eec` (12.9), `88363781` (12.8), `05f81d6d` (12.7) — all on `platform.development` / `MyPlayers`+`CoachPlayerDetail` pages; code-review cycles repeatedly caught async-ordering and null-safety issues, hence the guardrails above.

### Project Structure Notes

Backend in `src/main/java/com/softropic/skillars/platform/development/{api,service,contract,repo}`; tests mirror under `src/test/java/.../development/{api,service}`. Frontend `src/frontend/src/pages/coach/`, i18n under `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`. No variance.

### References

- [Source: _bmad-output/planning-artifacts/skillars-epics.md#Epic 12, Story 12.10, Gap → story map G19-G25]
- [Source: src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java:23-111]
- [Source: .../development/api/RadarDisplayResource.java:43-61]
- [Source: .../development/service/TimelineQueryService.java:33-98]
- [Source: .../development/service/CoachRosterService.java:62-175]
- [Source: .../development/service/CoachPlayerLastSessionResolver.java:27-66]
- [Source: .../development/service/TimelineEventListener.java:40-60,240-253]
- [Source: .../booking/repo/BookingRepository.java:238-298 (roster cap query, @Query at :283-292)]
- [Source: src/test/java/.../marketplace/api/CoachPublicProfileQueryCountIT.java (query-count precedent)]

## Open items (for the owner, non-blocking)

1. (Resolved 2026-10-23) D12.10-A/B/C/D confirmed by the owner as written.
2. Session-time source is the booking's `requestedEndTime` (no `completed_at` column exists on `session.sessions` or `booking`); alternatives are `bookings.live_started_at` (V167) or `session_completion_data.created_at`. Say so if a different instant is preferred.
3. Players beyond the 730-day floor or the 150 cap remain invisible by D6's scope — recorded as the closing note for G22.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5.5 (claude-sonnet-5-5)

### Debug Log References

- Targeted ITs (surefire, Docker): ParentDevelopmentPortalResourceIT 7, RadarAssessmentResourceIT 9, RadarDisplayResourceIT 13, PlayerTimelineResourceIT 21, CoachPlayerAuthorizationIT 6, CoachRosterResourceIT 14, SkillExposureResourceIT 13, SessionPrepResourceIT 18, ReportGenerationServiceIT 3 all green after the change (the only red was `PlayerTimelineResourceIT:331`, which pinned the old behaviour that another coach sees the authoring coach's `coachId`; flipped deliberately for G25). New `CoachAccessWindowIT` 12/12, `RosterQueryCountIT` 1/1.
- Unit: 343 tests across `platform.development.service`, `platform.config.service`, `platform.booking.repo` green. Frontend: Vitest 566/566, `prettier --check` and `eslint` clean. One pre-existing teardown error in `CoachCommandCenterPageSessionPrepSpec` (untouched). No `mvn verify` run.
- A first query-count run failed because the first measurement included one-off config-cache loads (7 statements for 2 players); fixed with a warm-up call. Settled value: 4 statements per roster player for the seeded booked-only shape. Code review 2026-10-23: that is the cheapest path; attended players cost 7 and lapsed players 2, each now pinned in its own `RosterQueryCountIT` test.

### Implementation Plan

- New `CoachAccessWindow` (pure `isOpen(instant, now, days)` + 4-arg bounded config read, `now` from `ClockProvider`). Two clocks per D12.10-D: reads/roster = latest booking END over `READ_RELATIONSHIP_STATUSES` (new `findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn`, plus `latestBookingEndTime` on the roster cap projection, no extra query); timeline/report generation = attended-session END (`MAX(LEAST(booking end, now))` aggregates on `SluRepository`/`PlayerTimelineRepository`, never the latest-processed row). `isRecapAttributable` still compares raw `sluDate`/`occurredAt`.
- `CoachPlayerAuthorizationService`: both read overloads and the write overload now run one windowed aggregate; no relationship and closed window are the same `OperationNotAllowedException`. `RadarDisplayService.get/savePreferences` call the UUID read overload before any read/upsert (G19).
- `TimelineQueryService`: shared window (attended clock, null = expired, response contract unchanged) and a per-event-type metadata allowlist. `ReportGenerationService` duplicated config block replaced by the shared helper, same attended-session semantics and `AccessDeniedException`; the shared write overload's booking-end pre-check now runs first (see D12.10-A).
- Roster: `accessExpired` computed from the booking-end clock; expired entries return zeroed counts and skip the neglected-count and snapshot queries; ordering = open by attention score, then lapsed by last session.
- Frontend: `MyPlayersPage.vue` Lapsed group (non-clickable) + all-lapsed hint; store `coreForbidden` (403 on exposure/contributions; skill definitions are deliberately not tracked -- a global endpoint with no player relationship, so it cannot 403 for access reasons) drives a neutral "no longer have access" message on `CoachPlayerDetailPage.vue`; i18n x3.

### Completion Notes List

- All 11 ACs implemented; owner decisions D5-D7 and D12.10-A/B/C/D applied as written. No Flyway migration, no config change.
- Behaviour change to note: the config read moved from the 3-arg clamping `getBoundedLong` to the 4-arg default-fallback form (an out-of-range value now yields 90, not the clamp bound). `ConfigBounds` note and Javadoc updated.
- `SessionPrepService` shows `resolver.lastSessionDate`, so the prep card's last-session date/days-since now reflect session END time; `SessionPrepResourceIT` (18) still green. There is no `SessionPrepServiceTest` class, so no unit test was changed.
- (Superseded by the 2026-10-23 code review, which added the missing ITs.) `CoachAccessWindowIT` now covers: the two windowed writes just inside/outside, plus a read-only-status-only case; `/radar/entries` and `/radar/correlation` just inside/outside (coach seeded at ACADEMY so the tier gate passes and only the window decides); timeline and roster `accessExpired` just inside/outside; the SLU-side `findLastSessionEnd` SQL against Postgres (delayed write for an old session, recent session, `session_id = NULL` fallback); and every parent-reachable read path.
- Not verified: no live-browser run of the Lapsed roster group or the access-ended state.

### File List

- _bmad-output/implementation-artifacts/sprint-status.yaml
- _bmad-output/planning-artifacts/skillars-epics.md
- docs/dev-docs/development/index.html
- src/frontend/src/i18n/de-DE/index.js
- src/frontend/src/i18n/en-US/index.js
- src/frontend/src/i18n/fr-FR/index.js
- src/frontend/src/pages/coach/CoachPlayerDetailPage.vue
- src/frontend/src/pages/coach/MyPlayersPage.vue
- src/frontend/src/pages/coach/__tests__/CoachPlayerDetailPageSpec.js
- src/frontend/src/pages/coach/__tests__/MyPlayersPageSpec.js
- src/frontend/src/stores/development.store.js
- src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java
- src/main/java/com/softropic/skillars/platform/booking/repo/RosterPlayerActivityProjection.java
- src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java
- src/main/java/com/softropic/skillars/platform/development/contract/RosterEntryResponse.java
- src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java
- src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java
- src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationService.java
- src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolver.java
- src/main/java/com/softropic/skillars/platform/development/service/CoachRosterService.java
- src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java
- src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java
- src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java
- src/test/java/com/softropic/skillars/platform/development/api/PlayerTimelineResourceIT.java
- src/test/java/com/softropic/skillars/platform/development/service/CoachPlayerAuthorizationServiceTest.java
- src/test/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolverTest.java
- src/test/java/com/softropic/skillars/platform/development/service/CoachRosterServiceTest.java
- src/test/java/com/softropic/skillars/platform/development/service/RadarDisplayServiceTest.java
- src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java
- src/test/java/com/softropic/skillars/platform/development/service/TimelineQueryServiceTest.java
- src/main/java/com/softropic/skillars/platform/development/service/CoachAccessWindow.java
- src/test/java/com/softropic/skillars/platform/development/api/CoachAccessWindowIT.java
- src/test/java/com/softropic/skillars/platform/development/api/RosterQueryCountIT.java
- src/test/java/com/softropic/skillars/platform/development/service/CoachAccessWindowTest.java

### Change Log

- 2026-10-23: Implemented story 12.10 (coach-player access consistency and hardening, G19-G25). Status in-progress -> review.
- 2026-10-23: Code review (bmad-code-review). 1 decision resolved (write window over write statuses), 9 patches applied, 3 deferred to deferred-work.md, 13 dismissed. Targeted runs green: CoachAccessWindowIT 21, RosterQueryCountIT 3, 7 existing development ITs (79), 9 unit classes. Status review -> done.
