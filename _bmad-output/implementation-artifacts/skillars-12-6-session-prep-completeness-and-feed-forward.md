# Story 12.6: Session Prep Completeness and Feed-Forward

Status: done

<!-- Source: _bmad-output/planning-artifacts/skillars-epics.md, Epic 12, Story 12.6 (gaps G1-G6). Citations verified against master @ 4cea321f on 2026-10-22. -->

## Story

As a **coach**,
I want **the Session Prep Card to tell me everything I need before a session — my own plan note, how long it has been, exactly which skills are neglected — and to carry last session's "what's next" into my planning**,
so that **history actively shapes the next session instead of being a passive log**.

## Acceptance Criteria

Gap IDs (G1-G6) are from the Epic 12 "Gap → story map". Do not re-litigate the epic's "Owner decisions already made" (siloed recaps, lapsed state computed, sweeper placeholder rows, roster fan-out cap).

### G1 — Coach plan note (backend)

1. **New column.** Flyway `V164__booking_bookings_coach_plan_note.sql`: `ALTER TABLE booking.bookings ADD COLUMN IF NOT EXISTS coach_plan_note varchar(500);` preceded by `SET LOCAL lock_timeout = '5s';` — copy `V163__booking_bookings_player_request.sql` exactly in shape (additive, nullable, no backfill). `Booking.coachPlanNote` (`@Column(name = "coach_plan_note", length = 500)`) **annotated `@JsonIgnore`** (see AC4 — non-negotiable).
2. **Write endpoint.** `PATCH /api/bookings/{id}/coach-plan-note`, body `UpdateCoachPlanNoteRequest(@Size(max = 500) String planNote)`, response `CoachPlanNoteResponse(UUID bookingId, String planNote)`. New `CoachPlanNoteResource` in `platform.booking.api` (own small resource, mirroring `PlayerRequestResource`), `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`, `@Observed(name = "booking.coachPlanNote")`. Service method `BookingService.setCoachPlanNote(UUID bookingId, Long coachUserId, String planNote)`:
   - Ownership: resolve the coach via `coachProfileRepository.findByUserId(coachUserId)` and compare `booking.getCoachId()` to `coach.getId()` — copy `recordNoShowPlayer` (`BookingService.java:912-918`) exactly (404 `Coach profile not found`; 403 `MISSING_RIGHTS` for a non-owner). **Not** `Objects.equals(booking.getParentId(), callerId)` — that is the player-request check and is wrong here.
   - Editability: status ∈ `{CONFIRMED, UPCOMING}` (`BookingError.BOOKING_NOT_EDITABLE`) **and** now before `requestedEndTime` (`BookingError.SESSION_ALREADY_ENDED`). `PLAYER_REQUEST_EDITABLE_STATUSES` and its guard are `private` in `booking.service.BookingService` and `development` cannot reach them (`SessionPrepService` imports only `booking.repo.*`), so **do not** try to share them by renaming. Instead add one public predicate on the entity, e.g. `Booking.isPrepNoteEditable(Instant now)` (status set + end-time bound, one definition), and have **both** `setCoachPlanNote` and `SessionPrepService` (for `coachPlanNoteEditable`) call it. Pass `Instant.now(ClockProvider.getClock())` from both so the write guard and the flag can never disagree under a fixed test clock. (The existing `setPlayerRequest` guard uses bare `Instant.now()` at `BookingService.java:787`; leave it alone, or if you route it through the new predicate first confirm no existing IT depends on the wall clock.) "Editable until the session starts" is realised by the status gate: pressing Start moves `UPCOMING → IN_PROGRESS`, which leaves the set. The end-time bound only stops post-hoc authoring of a session the coach never started.
   - Sanitize then truncate with the existing `sanitizeAndTruncate(contactDetailSanitizer, planNote, 500)` (`BookingService.java:1068-1072`); blank/null clears the note.
   - Concurrency: a parent editing `playerRequest` (and `BookingReminderScheduler` moving CONFIRMED→UPCOMING) write the **same row** as the coach, so a version conflict is reachable. Be precise about the mechanism: `getBookingOrThrow` is a plain `findById`, so `bookingRepository.save(booking)` inside the `try` is a merge with **no flush**; the version-checked UPDATE runs at commit, *outside* the `try`, and surfaces as `ObjectOptimisticLockingFailureException` → `ApiAdvice` (`:656-662`) → **409 `generic.conflict`**. The `catch (OptimisticLockingFailureException) → CONCURRENT_MODIFICATION` copied from `setPlayerRequest` (`:756-760`) therefore essentially never fires there (and if it did, it would be a 403 with errorKey `booking.concurrentModification`, see `BookingError` Javadoc). Pick one and state it in Completion Notes: (a) use `saveAndFlush` inside the `try` so the catch fires (403 + `booking.concurrentModification`), or (b) accept the commit-time 409. The frontend (AC6) must handle **both** a 409 and a 403 `booking.concurrentModification` with a "this booking changed, reload" message (precedent: `ParentBookingsPage.vue` branches on `errorKey`).
3. **Read path.** `SessionPrepResponse` gains `String coachPlanNote` and `boolean coachPlanNoteEditable` (same two predicates as AC2). `SessionPrepService.getPrep` fills them from the already-loaded `booking`. Add Javadoc `@param` entries (the record is Javadoc-heavy; follow it).
4. **Coach-private — never leaves the coach.** The note must not be returned to player, parent, or any other coach, **including through three channels that are easy to miss**:
   - `BookingService.toResponse` (`:1150-1173`) builds `BookingResponse` positionally — do **not** add the field there. Add a test that a parent/player `GET` of the booking never contains the note text.
   - **GDPR data export serialises the `Booking` entity directly** (`GdprExportService.buildBookings` → `addEntry(zos, "bookings.json", mapper, ...)`, `:73`, `:117-137`, for PARENT, PLAYER and COACH). Without `@JsonIgnore` a parent's or player's export zip would contain the coach's private note. The `@JsonIgnore` from AC1 closes this; add a test asserting a parent export of a booking that has a plan note does not contain the note text. `GdprExportServiceTest` is Mockito-only and asserts the returned `List<Booking>`, which proves nothing about `@JsonIgnore` — the test must serialise through a real mapper (`objectMapper.copy()` as at `GdprExportService:67`) or generate the zip in an IT, and assert the text is absent. **Accepted limitation (record in Completion Notes, owner may veto):** `@JsonIgnore` also omits the note from the *coach's own* export. The note is the coach's authored data, so strictly it belongs there; including it only for the coach needs a DTO projection of `bookings.json` and is out of scope for this story — log it in `deferred-work.md`.
   - Any other path that returns a `Booking` entity or a field-by-field copy of it must still compile and not expose the field — grep `getPlayerRequest()` call sites as the checklist of "booking fields that fan out" and confirm none is a `BookingResponse`-style builder for non-coach audiences that should now carry the note (none should).
5. **GDPR erasure — both directions.**
   - **Coach erasure** (the note is coach-authored): new `BookingRepository.anonymizeCoachPlanNoteByCoachId(UUID coachId)` — `@Modifying @Query("UPDATE Booking b SET b.coachPlanNote = NULL, b.version = b.version + 1 WHERE b.coachId = :coachId AND b.coachPlanNote IS NOT NULL")`. The `b.version = b.version + 1` bump is mandatory (`NativeModifyingVersionAuditTest` enforces it; `Booking` is not `@DynamicUpdate`, so a concurrently loaded booking would otherwise silently resurrect the erased text — see the comment at `BookingRepository.java:326-341`). Wire it into the coach scrub transaction in `GdprErasureService` (`:470-505`) as a **fifth group** `BOOKING_PLAN_NOTE` added to the `CoachRecapGroup` enum, with its own `COACH_PLAN_NOTE_LOCK_TIMEOUT` / `COACH_PLAN_NOTE_CONTENDED` constants and `case` in the exhaustive `switch` (`:519-526`) — exactly the deferred-157 AC4 pattern for `AVAILABILITY_BLOCK`. Update the reason-code list comments in `AdminQueueService.java:175-190` and `GdprErasureService.raiseErasureAlert`'s Javadoc, and the `log.warn` text at `:526`, plus the "four groups" wording in the comments at `:218-236`, `:252-259` and `:464-467`. Note the groups share ONE transaction: a trip in the new group rolls back the other four groups' scrubs too (labels only, no per-group durability — the documented design). Extend `GdprErasureServiceTest` with the lock-timeout and contended cases for the new group (mirror the `AVAILABILITY_BLOCK` pair). No admin frontend label exists for these reason codes (verified: only `GdprErasureService`, `AdminQueueService`, `GdprErasureServiceTest` reference them).
   - **Player erasure** (the note describes the player): extend `anonymizeNotesAndPlayerRequestByPlayerId` (`BookingRepository.java:339-341`) to also null `b.coachPlanNote`. Renaming the method is optional; if renamed, update `GdprErasureService.java:1516`, `GdprErasureServiceTest` and `GdprErasureIT`. Update its Javadoc, which currently asserts "never written by the coach".
   - Extend `GdprErasureIT` (it already covers `notes`/`playerRequest`) with a booking carrying a plan note for both branches.

### G1 — Coach plan note (frontend)

6. **Plan note editor on the prep card.** A new `src/frontend/src/components/booking/CoachPlanNotePanel.vue` (`<script setup>`, props: `bookingId`, `note`, `editable`, optional `suggestion`; emits `saved(note)`), used by `SessionPrepCard.vue` and by `SessionBuilderPage.vue` (AC9). `q-input` `type="textarea"`, `maxlength="500"`, counter, Save button; read-only text when `editable` is false. The note is re-shown on reopen because it comes from the prep response. API: add `setCoachPlanNote(bookingId, planNote)` to `src/frontend/src/api/booking.api.js` next to `setPlayerRequest` (`:92-93`) — all API calls live in `src/api/*.api.js`.
7. **All user-visible strings via vue-i18n**, keys added to **all three** locale files (`en-US`, `de-DE`, `fr-FR`) — `localeKeyParitySpec.js` and `i18nTemplateKeyResolutionSpec.js` fail CI otherwise. Keys live under `coach.*` beside `sessionPrep*` (`en-US/index.js:255-264`).

### G2/G3 — Days since last session, one signal

8. **Server-provided value, shared computation.**
   - `SessionPrepResponse` gains `Long daysSinceLastSession` (null when `lastSessionDate` is null). `RosterEntryResponse` gains `Long daysSinceLastSession` (same definition) **and** `boolean hasUpcomingBooking` (see AC9). `daysSinceLastActivity` stays internal — it is not needed by the caption rules.
   - Compute once, in one pure static helper (new package-private final class in `platform.development.service`, mirroring `RosterAttentionCalculator`/`SessionFocusThreadCalculator`: no Spring, no DB) — `static long daysBetween(Instant from, Instant now)` using `Duration.between(from, now).toDays()` **clamped at ≥ 0** (a `lastSessionDate` can be marginally after `now` — `TimelineEventListener` stamps processing time and SLU `calculated_at` is async). Both `CoachRosterService` and `SessionPrepService` call it with `Instant.now(ClockProvider.getClock())` — **never bare `Instant.now()`** (the roster service and tests rely on `ClockProvider`; see `CoachRosterService.java:73`).
   - Accepted imprecision (record it): `toDays()` is 24h-based and `lastSessionDate` is processing time, so "today" can mean last evening and "1 day ago" can span up to 47h; consistent with dormancy, so no contradiction. Unit-test the helper (0 days, 1 day, 29h → 1, future → 0, negative clamp).
9. **Roster caption and dormant badge can no longer contradict each other.** Today `MyPlayersPage.vue:104-109` shows "You haven't trained together yet" whenever `lastSessionDate` is null, even when the same row carries the "Gone quiet" badge (dormancy is booking-time based — `CoachRosterService.java:138-152`). Caption rules, in order, using the new server fields:
   1. `daysSinceLastSession != null` → `t('coach.myPlayersLastTrainedDaysAgo', { count })` (plural-aware: "today" for 0, "1 day ago", "{count} days ago" — use vue-i18n pluralisation the way `myPlayersNeglectedSkillCount` does at `en-US/index.js:254`).
   2. else if `!hasUpcomingBooking` → new string `coach.myPlayersBookedNoSessionYet` ("Booked, but no attended session yet"). By construction the player's latest roster-qualifying booking is then in the **past**, so a booking happened but no attended session was recorded (this also covers a latest booking that is COMPLETED-but-unattended, a no-show or in progress, hence "attended" not "completed") — exactly the case where the dormant badge can fire next to a "never trained" caption today.
   3. else (`lastSessionDate == null` and an upcoming booking exists) → existing `myPlayersNeverTrainedTogether`. Accepted imprecision: the roster projection carries only `MAX(requestedStartTime)`, so a player with an old past booking *and* a future one is captioned "haven't trained together yet"; dormancy is suppressed for such players anyway (`hasUpcomingBooking`), so there is no badge/caption contradiction. Do not change the repository query to fix this (G23/12.10 territory).
   Expose the existing local `hasUpcomingBooking` (`CoachRosterService.java:146`) as `boolean hasUpcomingBooking` on `RosterEntryResponse`. Cover all three captions with a `CoachRosterServiceTest` case each and a `MyPlayersPageSpec.js` case each. The dormant badge keeps its current logic unchanged — the point is that the caption is now derived from the same inputs the badge is.
10. **Prep card caption** uses the same strings and server value: `lastSessionLabel` (`SessionPrepCard.vue:107-112`) switches from a locally formatted date to `daysSinceLastSession`, keeping the absolute date as secondary text. `prep.lastSessionDate == null` stays "You haven't trained together yet" there (a prep card is always for an existing booking, so rule 2 does not apply — do not add `hasUpcomingBooking` to `SessionPrepResponse`).

### G4 — Neglected skills by name

11. `SessionPrepResponse` gains `List<String> openNeglectedSkillCodes`. `SessionPrepService` replaces `neglectedSkillFlagRepository.countByPlayerIdAndResolvedAtIsNull` with `findByPlayerIdAndResolvedAtIsNull` (already exists, `NeglectedSkillFlagRepository.java:11`), maps to `skillCode`, de-duplicates, sorts deterministically (alphabetical). `openNeglectedSkillCount` **stays** and is `codes.size()` — roster and existing tests depend on the count field name; do not remove it. The skill list is a bounded dictionary so no cap is needed. `SessionPrepCard.vue` renders the badge count as today **plus** a `q-badge` per code through the existing `skillDisplayName(code)` (`:118-120`, already falls back to the raw code). Roster is unchanged (count only).

### G5 — Feed-forward: "what's next" into the Session Builder

12. **Backend: `suggestedWhatsNext`.** `SessionPrepResponse` gains `String suggestedWhatsNext` and `Instant suggestedWhatsNextSessionDate`. Source: the most recent attended `SESSION_COMPLETED` timeline event **for this coach and player** whose `metadata.whatsNext` is non-blank, **falling back to older sessions** when the latest has none. New `PlayerTimelineRepository` native query, same native-query shape as `findSessionNotesForReport` (`PlayerTimelineRepository.java:66-73`) **but with the opposite filter, deliberately**: that precedent filters `metadata->>'coachId'` precisely *because* the column is attendance-gated and it wants unattended recaps; this query filters the `coach_id` **column** (attendance-gated — consistent with the prep card's existing rule that unattended sessions are excluded; the unattended-recap inconsistency is **G13, story 12.7 — do not touch it here**), `event_type = 'SESSION_COMPLETED'`, `jsonb_exists(metadata, 'whatsNext') AND btrim(metadata->>'whatsNext') <> ''`, `ORDER BY occurred_at DESC, id DESC LIMIT 1`. Also exclude the event of the booking being prepped (`reference_id <> :bookingId`) so a booking that already has its own SESSION_COMPLETED event cannot suggest its own `whatsNext`. Use `jsonb_exists(...)`, **not** the bare `?` operator (parser ambiguity — comment at `:62-65`). The `idx_player_timeline_events_coach_lookup` index (V161) covers `(player_id, coach_id, event_type, occurred_at DESC)`.
    - `lastWhatWeDid`/`lastWhatsNext` keep their existing 12-3 AC3 consistency rule (populated only when the event producing `lastSessionDate` is the recap source). `suggestedWhatsNext` is a **separate** field with its own — deliberately looser — semantics; do not merge them and do not change the existing pair.
13. **Frontend: show and apply inside the Session Builder.** `SessionBuilderPage.vue` fetches `getSessionPrep(bookingId)` on mount (add `prep` state + `fetchPrep` to `sessionBuilder.store.js` next to `fetchExistingPlan`, `:111-144`; reset it in `initForBooking`, `:73-109`; a failed/403 prep fetch must **not** break the builder — swallow into `prep = null`, the builder works exactly as today). When `prep.suggestedWhatsNext` exists render a suggestion card (above the blocks column, under the template banner) with the text, its session date, and one action.
    - **Design decision (owner-approved 2026-10-22 — apply lands in the coach plan note; do not re-litigate):** the epic text says "applied to the session focus", but a session's `developmentFocus` is a list of validated **skill codes** (`ValidFocusCode`; `savePlan` payload `sessionBuilder.store.js:152-161`), while `whatsNext` is free text. It cannot be written into `developmentFocus`. "Apply" therefore **saves the suggestion into the booking's coach plan note** (G1) — the one free-text slot that exists for exactly this purpose and is shown back on the prep card. Do not invent a new free-text field on `Session`.
    - **Sanitise the suggestion server-side.** The recap `whatsNext` is stored raw (`BookingCompletionService:163`) while the plan-note write runs `ContactDetailSanitizer`, which can replace and lengthen text. Apply `contactDetailSanitizer` (+ truncate to 500) to `suggestedWhatsNext` before returning it from `SessionPrepService`, so the "already contains" and 500-char checks compare like with like and an unsanitised suggestion cannot make "Add to plan note" reappear forever. Always render the note exactly as the server returned it.
   - **Never silently overwrite** (the AC's hard rule): if the plan note is empty → button "Use as plan note" sets it. If the note is non-empty and does not already contain the suggestion → button "Add to plan note" **appends** `"\n" + suggestion`; if the combined text exceeds 500 chars the button is disabled with an explanatory caption. If the note already contains the suggestion → no button, show "Already in your plan note". Saving goes through the same `CoachPlanNotePanel`/`setCoachPlanNote` path; show the resulting note in the card so the coach sees what was stored.
    - **Gate Apply on editability and unsaved edits:** hide/disable the Apply button unless `prep.coachPlanNoteEditable` (the builder is reachable for IN_PROGRESS/COMPLETED bookings and `getPrep` has no status gate, so Apply would 403). Apply must not discard unsaved text in the `CoachPlanNotePanel` on the same page — apply into the panel's draft, or disable Apply while the panel is dirty.
   - **Ordering:** `fetchExistingPlan` calls `initForBooking` at entry and again on the 404 "no plan yet" path, and `isGated` is only known after it resolves. In `SessionBuilderPage.onMounted` do `await builderStore.fetchExistingPlan(bookingId); if (!builderStore.isGated) await builderStore.fetchPrep(bookingId)` — never concurrently, or a reset wipes `prep`. Spec the 404-then-prep path.
   - The Session Builder also renders `CoachPlanNotePanel` (editable per `prep.coachPlanNoteEditable`) so the coach can edit the note without leaving the page.
    - The builder must keep working when `isGated` (the scout gate is a *backend* check; the frontend signal is the `isGated` ref) — do not fetch prep when `isGated`.

### G6 — Prep for CONFIRMED bookings

14. `CoachCommandCenterPage.vue:121-130`: change the Prep button's `v-if="booking.status === 'UPCOMING'"` to `['CONFIRMED', 'UPCOMING'].includes(booking.status)` (the exact idiom the cancel button already uses at `:153`). `SessionPrepService.getPrep` has **no status gate** (verified) and `SessionPrepResource` authorizes on ownership only, so no backend change is needed for G6 — add an IT proving a `CONFIRMED` booking returns a prep response. Update `CoachCommandCenterPageSessionPrepSpec.js` (`:74-81`, currently asserts "only UPCOMING" and counts exactly one button for `[UPCOMING, COMPLETED]`): add CONFIRMED to the positive set, keep COMPLETED (and e.g. REQUESTED) negative.
    - **Do not "fix" but do know:** `SessionPlanService.isBookingPlannable` (`:240-250`) accepts only `CONFIRMED`; `createSession` for an `UPCOMING` booking with no existing plan is rejected with `SESSION_BOOKING_NOT_OWNED` (a misleading code). This makes G6 materially more useful (a CONFIRMED booking is the only state in which a *new* plan can be created) but also means "Plan this session" on an `UPCOMING` booking with no prior plan fails at save. Pre-existing, outside the gap list: append a bullet to `deferred-work.md`; do not change `SessionPlanService`.

### Tests, i18n, hygiene

15. Backend: `SessionPrepResourceIT` (`src/test/.../development/api/SessionPrepResourceIT.java`) extended for every new response field, the CONFIRMED case, non-owner 403, and the note round-trip; new `CoachPlanNoteResourceIT` in `src/test/.../booking/api/` modelled on `PlayerRequestResourceIT` (200 set / clear / sanitised-and-truncated, 403 non-owner coach, 403 wrong role (parent token), 403 not-editable status, 403 session-already-ended, 404 unknown booking); `CoachRosterServiceTest` additions; `BookingServiceTest` unit cases beside the `setPlayerRequest` ones, **including the optimistic-lock case** (a sequential-HTTP IT cannot reproduce it — the request carries no version; use a unit test that makes `save`/`saveAndFlush` throw, or a truly interleaved-transaction IT; `PlayerRequestResourceIT` has no 409 test either). Integration tests use Testcontainers (no mocked DB), AssertJ, Instancio. **If an IT does a bare `jdbcTemplate` write, wrap it in `transactionTemplate`** (Hikari auto-commit=false — an unwrapped write never commits). Booking fixtures must derive `requestedStartTime`/`requestedEndTime` from a **single** `Instant.now()` (`chk_bkg_end_after_start` — two separate calls is a CI-only flake).
16. Frontend (Vitest, specs beside code in `__tests__/`, explicit `describe/it/expect/vi` imports): `SessionPrepCardSpec.js` (note editor, days-since caption, neglected-skill names, 409 handling), new `CoachPlanNotePanelSpec.js`, `MyPlayersPageSpec.js` (three captions), `CoachCommandCenterPageSessionPrepSpec.js`, a `SessionBuilderPage`/store spec for the suggestion card (empty note / non-empty note / already-contained / over-cap / prep fetch failure leaves the builder working). Prettier is mandatory on `.js .vue .scss .json`; the CI Prettier gate lives in `.github/actions/` and blocks `build-and-push`.
17. **Do not run `mvn verify` locally** — GitHub CI is the sole full-verification gate. Run only targeted unit/spec runs (`mvn -Dtest=… test`, `cd src/frontend && npm run test:unit`).

## Tasks / Subtasks

- [x] Task 1: Migration + entity + repository (AC: 1, 5)
  - [x] 1.1 `V164__booking_bookings_coach_plan_note.sql` (copy V163 shape; confirm V164 is still free on `master` before committing — `MigrationConventionLintTest` runs in the `test` phase)
  - [x] 1.2 `Booking.coachPlanNote` with `@JsonIgnore`
  - [x] 1.3 `BookingRepository.anonymizeCoachPlanNoteByCoachId`; extend the player-keyed anonymize query
- [x] Task 2: Booking write endpoint (AC: 2, 4)
  - [x] 2.1 `UpdateCoachPlanNoteRequest`, `CoachPlanNoteResponse`, `CoachPlanNoteResource`
  - [x] 2.2 `BookingService.setCoachPlanNote` (shared editable-status helper, `sanitizeAndTruncate`, optimistic-lock mapping)
  - [x] 2.3 Tests: `CoachPlanNoteResourceIT`, `BookingServiceTest`, parent-GET privacy test, `GdprExportService` leak test
- [x] Task 3: GDPR erasure (AC: 5)
  - [x] 3.1 New `BOOKING_PLAN_NOTE` group, constants, switch case, log text, `AdminQueueService`/Javadoc comment updates
  - [x] 3.2 Player-erasure query extension + comments
  - [x] 3.3 `GdprErasureServiceTest` timeout/contended pair; `GdprErasureIT` both branches
- [x] Task 4: Prep response + recency helper (AC: 3, 8, 11, 12)
  - [x] 4.1 New pure recency helper + unit test
  - [x] 4.2 `SessionPrepResponse` new fields (+ Javadoc), `SessionPrepService` fills them
  - [x] 4.3 `PlayerTimelineRepository` suggestion query
  - [x] 4.4 `RosterEntryResponse` fields + `CoachRosterService.buildEntry`
  - [x] 4.5 `SessionPrepResourceIT`, `CoachRosterServiceTest`, `CoachRosterResourceIT`
- [x] Task 5: Frontend prep card + roster (AC: 6, 7, 9, 10, 11, 14)
  - [x] 5.1 `booking.api.js` `setCoachPlanNote`; `CoachPlanNotePanel.vue`
  - [x] 5.2 `SessionPrepCard.vue`: panel, days-since caption, neglected-skill badges
  - [x] 5.3 `MyPlayersPage.vue` caption rules
  - [x] 5.4 `CoachCommandCenterPage.vue` Prep button for CONFIRMED
  - [x] 5.5 i18n keys ×3 locales; run parity + key-resolution specs
- [x] Task 6: Session Builder feed-forward (AC: 12, 13)
  - [x] 6.1 `sessionBuilder.store.js` `prep` state/`fetchPrep`/reset
  - [x] 6.2 Suggestion card + apply logic + plan-note panel in `SessionBuilderPage.vue`
  - [x] 6.3 Specs for every apply branch
- [x] Task 7: Docs/ledger (AC: 4, 14)
  - [x] 7.1 `deferred-work.md` bullets: coach-export omission; `isBookingPlannable` UPCOMING mismatch
  - [x] 7.2 `docs/dev-docs` development + booking module pages if they describe the prep endpoint / booking columns (check `docs/dev-docs/` for `prep`)

### Review Findings

_Code review 2026-10-22 (/bmad-code-review: Blind Hunter / Edge Case Hunter / Acceptance Auditor). 1 decision-needed (resolved → defer), 8 patch (all applied), 4 defer, 17 dismissed. Verified after patching: Vitest 55 files / 455 tests; BookingJsonPrivacyTest 2/2, BookingServiceTest 85/85; CoachPlanNoteResourceIT 11/11 on real Postgres (Testcontainers); Prettier + ESLint clean on touched frontend files. P1 and P2 mutation-verified (reverting each fix fails its new spec)._

- [x] [Review][Defer] Plan note is last-write-wins across surfaces; "Add to plan note" appends onto a stale client copy (PATCH carries no version; `saveAndFlush` only catches in-window server races) [src/main/java/com/softropic/skillars/platform/booking/contract/UpdateCoachPlanNoteRequest.java] — deferred (owner decision 2026-10-22): accepted LWW, same model as `setPlayerRequest`; single-author note, loss needs the same coach editing in two places at once
- [x] [Review][Patch] Stale `fetchPrep` response can land on a different booking (no `bookingId === bId` guard; the Pinia store outlives the page) [src/frontend/src/stores/sessionBuilder.store.js:118]
- [x] [Review][Patch] Typing in the plan-note panel during an in-flight Apply is overwritten by the `props.note` watcher — panel is not disabled while `applying` [src/frontend/src/components/session/PrepFeedForwardCard.vue:47]
- [x] [Review][Patch] `BookingJsonPrivacyTest` serialises through a hand-built `new ObjectMapper()`, not the Spring-configured mapper the export copies (AC4 asks for `objectMapper.copy()` as at `GdprExportService:67`) [src/test/java/com/softropic/skillars/platform/booking/repo/BookingJsonPrivacyTest.java:22]
- [x] [Review][Patch] `parentGetBooking_neverContainsCoachPlanNote` never asserts the booking is in the response, so it passes on an empty list [src/test/java/com/softropic/skillars/platform/booking/api/CoachPlanNoteResourceIT.java:238]
- [x] [Review][Patch] 403 ITs assert only the status, not the errorKey — cannot distinguish `booking.notEditable` / `booking.sessionAlreadyEnded` / `MISSING_RIGHTS` (sibling ITs assert `"errorKey":"..."`) [src/test/java/com/softropic/skillars/platform/booking/api/CoachPlanNoteResourceIT.java:251]
- [x] [Review][Patch] Stale comment: "there is no coach-authored half to also scrub" is now false (`anonymizeCoachPlanNoteByCoachId` exists) [src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:328]
- [x] [Review][Patch] Dead i18n key `coach.sessionPrepNeglectedSkillsLabel` added ×3 locales but referenced nowhere [src/frontend/src/i18n/en-US/index.js:268]
- [x] [Review][Patch] Store spec "the page then never fetches prep" asserts something the store can never do — vacuous; retitle/tighten [src/frontend/src/stores/__tests__/sessionBuilderStoreSpec.js:74]
- [x] [Review][Defer] Suggestion query has no `occurred_at < booking start` bound — a later session's whatsNext can show when prepping an older booking (read-only there; a naive bound would drop back-to-back completions stamped after the next start) [src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java:84] — deferred
- [x] [Review][Defer] Panel/Apply stay active after the server rejects the write as locked (page left open past end time / moved to IN_PROGRESS) — only a toast [src/frontend/src/utils/planNoteError.js:23] — deferred
- [x] [Review][Defer] `CoachPlanNoteResourceIT.patchNote` uses `Map.of`, so the null-clears contract is never exercised end-to-end (blank-clears is) [src/test/java/com/softropic/skillars/platform/booking/api/CoachPlanNoteResourceIT.java:282] — deferred

## Dev Notes

### Current state of every file this story modifies (READ BEFORE EDITING)

| File | Today | Story changes | Must preserve |
|---|---|---|---|
| `development/contract/SessionPrepResponse.java` | 7-field record: `playerId, lastSessionDate, lastWhatWeDid, lastWhatsNext, focusThreadSkillCodes, openNeglectedSkillCount, playerRequest` | Append new components at the **end** | Existing component order/names (frontend + `SessionPrepResourceIT` read them by name). It is a record: every constructor call site (`SessionPrepService:~92`, any test building one) must be updated. |
| `development/service/SessionPrepService.java` | Loads booking, ownership check (`:48-52`), resolver signal, recap fields, focus thread, neglected **count** | Add note fields, days-since, neglected codes, suggestion lookup | Ownership check by `booking.getCoachId()`; class is `@Transactional(readOnly = true)`; 12-3 AC3 "recap only from the signal's source event" rule |
| `development/contract/RosterEntryResponse.java` + `CoachRosterService.buildEntry` | 6 fields; `daysSinceLastActivity` is a local that only feeds `RosterAttentionCalculator` | Add `daysSinceLastSession` + `hasUpcomingBooking` (`daysSinceLastActivity` stays internal) | `attentionScore` and `dormant` computation untouched; the 150-player cap, 730-day floor, N+1 shape (**G23 is story 12.10 — do not restructure the fan-out**); `ROSTER_MEMBERSHIP_STATUSES` reference to `CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES` |
| `booking/repo/Booking.java` | has `notes`, `playerRequest` | + `coachPlanNote` (`@JsonIgnore`) | Optimistic `@Version`; no `@DynamicUpdate` |
| `booking/service/BookingService.java` | `setPlayerRequest` `:744-762`, editable guard `:782-791`, `sanitizeAndTruncate` `:1060-1066`, positional `toResponse` `:1150-1173` | + `setCoachPlanNote`; share the editable-status constant/guard | `BookingResponse` stays free of the note |
| `admin/service/GdprErasureService.java` | Coach scrub = 4-group enum in ONE `requiresNewTemplate` tx, one transaction-scoped `lock_timeout` | 5th group | Label **before** the statement; the null-coalesce on the group label in the catch (`:519`); `raiseErasureAlert` stays outside the tx boundary; `skipped.set(true)` after the alert |
| `frontend SessionPrepCard.vue` | loads `getSessionPrep` + skill definitions in `onMounted`; `<dl>` reset via `:where()` in scoped style | note panel, captions, neglected badges | The `:where(dl, dt, dd)` zero-specificity reset and its comment; per-field `?.trim()` recap guards; `hasRecap` OR-logic |
| `frontend MyPlayersPage.vue` | caption from `lastSessionDate` only | three-way caption | `goToPlayerDetail` pushes **no** `playerName` in the URL (deferred-156 AC5 — a minor's name must not reach browser history) |
| `frontend CoachCommandCenterPage.vue` | Prep button UPCOMING-only (`:121-130`) | CONFIRMED too | Dialog wiring `openSessionPrep` (`:754-758`), `SessionPrepCard` props |
| `frontend sessionBuilder.store.js` | `fetchExistingPlan` → `initForBooking` resets all state; 404 → fresh plan; 403 `security.featureGated` → `isGated` | `prep` state | Every field `initForBooking` resets today; `savePlan` payload shape |

### Why these design choices (so they are not re-argued mid-implementation)

- **Plan note on `booking.bookings`, not a new table or `Session`.** The epic AC says "persisted on that booking". A `Session` row only exists once the coach starts a plan; the note must be writable on a bare `CONFIRMED` booking. `playerRequest` (V163) is the precedent: nullable `varchar(500)` on the same row, sanitised, editable only in `CONFIRMED/UPCOMING`.
- **`@JsonIgnore` is a security control, not a nicety.** `GdprExportService` hands the `Booking` entity straight to Jackson. This is the exact class of "free-text field quietly leaks through an entity serialisation" defect the project's rule "never return JPA entities directly" exists to prevent. Test it.
- **`suggestedWhatsNext` is a new field, not a loosening of `lastWhatsNext`.** 12-3 AC3's consistency rule exists so the recap on the card and the "last session" date can never describe different sessions. The feed-forward suggestion is allowed to reach further back (the epic says so), so it carries its own date.
- **Days are computed server-side from `ClockProvider`.** A client-side `Date.now()` diff would disagree with the server's dormancy calculation across timezones and in tests, which is the very contradiction G3 reports.
- **Apply = plan note** (see AC13) — **owner-approved 2026-10-22.** For the record, the only alternative that fits the data model is mapping `whatsNext` text to skill codes, which would be fuzzy NLP and is rejected.

### Cross-story and ledger boundaries (do NOT absorb)

- 12.7: recap edit/resume, nudge, "what went well", voice note, PDF label/visibility, **G13 unattended-recap inconsistency**. 12.8: timeline content/producers. 12.9: coach-detail-page parity, `/neglected-skills` unused endpoint (**G18** — this story reads neglected flags via the repository inside `SessionPrepService`; do not start calling the REST endpoint). 12.10: access windows, roster N+1, radar-preferences auth, redaction allowlist.
- Roster `playerName` source and `findAllById` filtering (deferred-152 AC6) stay as is.

### Project rules that apply (from `_bmad-output/project-context.md` and memory)

- DTOs are Java `record`s; every endpoint has `@PreAuthorize` with `SecurityConstants`; `@Observed` on resources; Jakarta validation on request records; no DDL in Java; Flyway only, expand/contract (`docs/deployment/migration-conventions.md`); `ClockProvider` for time.
- Frontend: `<script setup>`, `async/await` (no `.then()`), Pinia for shared state, `src/api/*.api.js` for HTTP, all strings in vue-i18n for **three** locales, Prettier.
- **ID spaces:** `Booking.playerId` is a `PlayerProfile` PK, **not** a `User` id (`SessionPrepResponse.playerId` is the same space). The coach caller's `userId` ≠ `coach_profiles.id`; `Booking.coachId` is the latter.
- **REQUIRES_NEW row-lock trap:** if any new code writes a booking row and then calls something that opens a `REQUIRES_NEW` revocation on the same row, it self-deadlocks (55P03 → 409). `setCoachPlanNote` does neither; keep it that way.
- Never run `mvn verify` locally.

### Testing standards

`@SpringBootTest` + Testcontainers, AssertJ, Instancio, Awaitility for async. Frontend: Vitest + `@vue/test-utils` (`happy-dom`), the global `$t` stub echoes keys (so key typos only fail in the two i18n spec files — run them). Models: `PlayerRequestResourceIT` (booking-side PATCH), `SessionPrepResourceIT` (prep GET), `CoachRosterServiceTest`/`CoachRosterResourceIT` (roster), `SessionPrepCardSpec.js`, `MyPlayersPageSpec.js`, `CoachCommandCenterPageSessionPrepSpec.js`.

### Project Structure Notes

- Backend new files: `booking/api/CoachPlanNoteResource.java`, `booking/contract/{UpdateCoachPlanNoteRequest,CoachPlanNoteResponse}.java`, `development/service/<RecencyHelper>.java`, `db/migration/V164__…sql`. Frontend new: `components/booking/CoachPlanNotePanel.vue` (+ `__tests__/CoachPlanNotePanelSpec.js`).
- The note endpoint lives in **booking** (owns the table); the read lives in **development** (the prep endpoint). That cross-module read already exists for `playerRequest` via the shared `Booking` entity — follow it, do not add a new cross-module contract.
- No conflicts with the unified structure detected.

### Git intelligence (recent commits)

`4cea321f` (deferred-158) hardened `GdprErasureService`'s booking statements and the i18n key spec (parser-backed comment blanking — a new `t()` key inside a comment is now correctly ignored, but a **dynamic** key built by concatenation is a registered exception list: use literal keys). `7121ace5` (deferred-157) added the `AVAILABILITY_BLOCK` coach-scrub group and reason codes — the template for AC5. `56803337` (deferred-156) added `sanitizeAndTruncate`, `PlayerRequestResponse`, booking-notes GDPR coverage. `c7132064` (12-5) is the latest Epic 12 story. Code-review habits to anticipate: javadoc-heavy rationale comments citing the story/AC, exhaustive switches, explicit `version + 1` on bulk updates.

### Latest tech information

No new libraries. Stack unchanged: Java 17 (no `case null` in switch), Spring Boot 3.5.16, Quasar 2.16 / Vue 3.5, `commons-lang3` 3.21.0 (`StringUtils.truncate` is surrogate-pair-aware — already relied on by `sanitizeAndTruncate`).

### References

- [Source: `_bmad-output/planning-artifacts/skillars-epics.md` — Epic 12 intro, "Gap → story map", Story 12.6]
- [Source: `_bmad-output/implementation-artifacts/skillars-12-3-session-prep-card-and-timeline-access-clock-fix.md`, `skillars-12-5-coach-recap-on-player-timeline.md`, `skillars-deferred-155/-156/-157/-158-*.md`]
- [Source: `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` — Pattern #2, #5, #9, Priority 3]
- [Source: `_bmad-output/project-context.md`; `docs/deployment/migration-conventions.md`; `docs/testing/frontend-unit-tests.md`]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5.5 (`claude-sonnet-5-5`), /bmad-dev-story, 2026-10-22.

### Debug Log References

- `mvn -o test` (targeted): `SessionRecencyTest`, `CoachRosterServiceTest`, `BookingServiceTest`, `GdprErasureServiceTest`, `BookingJsonPrivacyTest`, `NativeModifyingVersionAuditTest`, `MigrationConventionLintTest` — 170 tests, 0 failures.
- `failsafe` (targeted, Testcontainers): `CoachPlanNoteResourceIT` (11), `SessionPrepResourceIT` (16), `CoachRosterResourceIT` (13), `GdprErasureIT` (44) — all green. First `SessionPrepResourceIT` run had three fixture errors (Long fields serialise as JSON strings; two same-coach bookings tripped `excl_bkg_coach_slot_overlap`; invalid skill codes `PASSING`/`DRIBBLING` vs the seeded `PAS`/`SHO`) — test-only, fixed.
- `cd src/frontend && npm run test:unit`: 55 files / 452 tests pass (incl. `localeKeyParitySpec`, `i18nTemplateKeyResolutionSpec`). Prettier `--check` clean; ESLint clean on touched files. `mvn verify` was NOT run (GitHub CI is the gate).
- `i18nTemplateKeyResolutionSpec` rejected a dynamic `t(planNoteErrorMessageKey(err))`; replaced with `planNoteErrorMessage(err, t)` using literal keys.

### Completion Notes List

- **Concurrency decision (AC2): option (a)** — `setCoachPlanNote` uses `saveAndFlush` inside the `try`, so a clash with a parent `playerRequest` edit or the reminder scheduler maps to 403 `booking.concurrentModification` (the siblings' contract) rather than a commit-time 409. The frontend (`planNoteErrorMessage`) handles BOTH a 409 and either spelling of the concurrent-modification errorKey. Covered by a `BookingServiceTest` unit case (a sequential-HTTP IT cannot produce it).
- **Shared editability (AC2/AC3):** `Booking.isPrepNoteEditable(Instant)` (status CONFIRMED/UPCOMING and now strictly before `requestedEndTime`) is the single definition. `setCoachPlanNote` checks status first (BOOKING_NOT_EDITABLE) then the predicate (SESSION_ALREADY_ENDED); `SessionPrepService` uses it for `coachPlanNoteEditable`. Both use `Instant.now(ClockProvider.getClock())`.
- **Privacy (AC4):** `@JsonIgnore` on `Booking.coachPlanNote`; `BookingJsonPrivacyTest` serialises through a real `ObjectMapper` (the existing `GdprExportServiceTest` is Mockito-only and proves nothing); `CoachPlanNoteResourceIT` asserts the parent `GET /api/bookings/requests` never contains the text. **Accepted limitation (owner may veto):** `@JsonIgnore` also omits the note from the coach's OWN export — logged in `deferred-work.md`.
- **GDPR (AC5):** fifth coach-scrub group `BOOKING_PLAN_NOTE` (`COACH_PLAN_NOTE_LOCK_TIMEOUT`/`_CONTENDED`) with `version + 1`; player-erasure query extended; unit pair + two `GdprErasureIT` cases (player and coach direction, each with a control row).
- **Recency (AC8):** pure `SessionRecency` helper (clamped at 0, 24h-based — imprecision accepted and documented in its Javadoc). Roster + prep both pass `Instant.now(ClockProvider.getClock())`.
- **Roster caption (AC9):** three rules driven by `daysSinceLastSession` + `hasUpcomingBooking`; dormant badge logic untouched. Long values arrive as JSON strings, so the frontend coerces with `Number()` and treats `0` as "attended today".
- **Neglected skills (AC11):** names via `findByPlayerIdAndResolvedAtIsNull`, de-duplicated and sorted; `openNeglectedSkillCount` is now `codes.size()`.
- **Feed-forward (AC12/AC13):** new native query `findLatestWhatsNextSuggestion` (coach_id COLUMN, non-blank `whatsNext`, excludes the prepped booking, `LIMIT 1`); suggestion sanitised + truncated to 500 server-side. Session Builder: `prep` state + `fetchPrep` in the store (reset in `initForBooking`), new `PrepFeedForwardCard.vue` (apply = plan note; never overwrites; disabled when not editable, over 500 chars, or the panel has unsaved edits). The page awaits `fetchExistingPlan` first, then fetches prep only when not gated.
- **G6:** Prep button for CONFIRMED and UPCOMING; `SessionPrepResourceIT` proves a CONFIRMED booking returns a full prep response.
- **Deviations / not done:** no page-level `SessionBuilderPage` mount spec (heavy dependency graph) — ordering and gating are covered at store level and logged in `deferred-work.md`; no `docs/dev-docs` change (no page mentions the prep endpoint or booking columns).
- **Branch:** work is on local branch `feat/skillars-12-6-session-prep-completeness`; nothing committed or pushed.

### File List

Backend (new): `src/main/resources/db/migration/V164__booking_bookings_coach_plan_note.sql`, `…/booking/api/CoachPlanNoteResource.java`, `…/booking/contract/CoachPlanNoteResponse.java`, `…/booking/contract/UpdateCoachPlanNoteRequest.java`, `…/development/service/SessionRecency.java`
Backend (modified): `…/booking/repo/Booking.java`, `…/booking/repo/BookingRepository.java`, `…/booking/service/BookingService.java`, `…/admin/service/GdprErasureService.java`, `…/admin/service/AdminQueueService.java`, `…/development/contract/SessionPrepResponse.java`, `…/development/contract/RosterEntryResponse.java`, `…/development/repo/PlayerTimelineRepository.java`, `…/development/service/SessionPrepService.java`, `…/development/service/CoachRosterService.java`
Backend tests (new): `…/booking/api/CoachPlanNoteResourceIT.java`, `…/booking/repo/BookingJsonPrivacyTest.java`, `…/development/service/SessionRecencyTest.java`
Backend tests (modified): `…/booking/service/BookingServiceTest.java`, `…/admin/service/GdprErasureServiceTest.java`, `…/admin/api/GdprErasureIT.java`, `…/development/api/SessionPrepResourceIT.java`, `…/development/api/CoachRosterResourceIT.java`, `…/development/service/CoachRosterServiceTest.java`
Frontend (new): `src/frontend/src/components/booking/CoachPlanNotePanel.vue`, `src/frontend/src/components/session/PrepFeedForwardCard.vue`, `src/frontend/src/utils/planNoteError.js`
Frontend (modified): `src/api/booking.api.js`, `src/components/booking/SessionPrepCard.vue`, `src/pages/coach/MyPlayersPage.vue`, `src/pages/coach/CoachCommandCenterPage.vue`, `src/pages/coach/SessionBuilderPage.vue`, `src/stores/sessionBuilder.store.js`, `src/i18n/{en-US,de-DE,fr-FR}/index.js`
Frontend tests (new): `src/components/booking/__tests__/CoachPlanNotePanelSpec.js`, `src/components/session/__tests__/PrepFeedForwardCardSpec.js`, `src/stores/__tests__/sessionBuilderStoreSpec.js`
Frontend tests (modified): `src/components/booking/__tests__/SessionPrepCardSpec.js`, `src/pages/coach/__tests__/MyPlayersPageSpec.js`, `src/pages/coach/__tests__/CoachCommandCenterPageSessionPrepSpec.js`
Docs/ledger: `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`, this story file

### Change Log

- 2026-10-22: Implemented skillars-12-6 (G1–G6): coach plan note (V164, endpoint, GDPR both directions, export leak guard), shared days-since recency and roster caption rules, neglected skills by name, "what's next" suggestion + Session Builder feed-forward card, Prep button for CONFIRMED. Status → review.
