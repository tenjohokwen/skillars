# Story Review — skillars-12-6-session-prep-completeness-and-feed-forward

- **Audit date:** 2026-10-22
- **Real HEAD:** `4cea321f feat(admin,frontend): parser-backed i18n comment blanking and GDPR booking-erasure label coverage (skillars-deferred-158) (#284)`
- The story says "Citations verified against master @ 4cea321f". That matches the real HEAD, but several line numbers are still wrong (see §1). Every citation below was checked against the real HEAD, not against the story's own description of itself. No source files differ from HEAD; the only local changes are `sprint-status.yaml` and the untracked story file.

## 1. Citation verification

| Citation (story) | Verdict | Evidence / corrected location |
|---|---|---|
| `BookingService.java:912-918` (recordNoShowPlayer ownership) | MATCH | `findByUserId(...).orElseThrow(... "Coach profile not found")`; `!Objects.equals(booking.getCoachId(), coach.getId())` → `MISSING_RIGHTS` |
| `BookingService.java:144-145` (`PLAYER_REQUEST_EDITABLE_STATUSES`) | MATCH | `Set.of(CONFIRMED.name(), UPCOMING.name())` |
| `BookingService.java:782-791` (editable guard) | MATCH | `rejectIfNotEditablePlayerRequest`; uses **bare `Instant.now()`** (see §5 F3) |
| `BookingService.java:1060-1066` (`sanitizeAndTruncate`) | DRIFTED (small) | 1060-1067 is the comment; the method is at **1068-1072** |
| `BookingService.java:756-760` (optimistic-lock catch) | MATCH | Location is correct; the behaviour is not what the story assumes (§3 M6) |
| `BookingService.java:744-762` (`setPlayerRequest`) | MATCH | |
| `BookingService.java:1150-1173` (`toResponse`) | MATCH | The method runs 1152-1173 |
| `GdprExportService:73`, `:117-140` | MATCH | `addEntry(zos,"bookings.json",mapper,buildBookings(...))`; `buildBookings` 117-137 returns `List<Booking>` |
| `BookingRepository.java:326-341` | MATCH | The comment is a `//` block, not Javadoc (AC5 calls it "Javadoc") |
| `GdprErasureService:470-505`, `:519-526`, `:519`, `:1516` | MATCH | Group tx at 472-501; switch at 519-525; caller at 1516 |
| `GdprErasureService:528` (log.warn text) | DRIFTED (small) | The `log.warn` starts at **526** |
| `AdminQueueService.java:175-190` | MATCH | The list runs 173-188 |
| `booking.api.js:92-93` | MATCH | |
| `en-US/index.js:254`, `:255-264` | MATCH | |
| `CoachRosterService.java:81` (ClockProvider) | **DRIFTED** | Actually at **:73** (`Instant now = Instant.now(ClockProvider.getClock())`); `:81` is `configService.getBoundedLong(` |
| `CoachRosterService.java:138-152` | MATCH | |
| `CoachRosterService.java:147` (`hasUpcomingBooking`) | DRIFTED (off by 1) | Actually at **:146** |
| `MyPlayersPage.vue:87-93` (caption) | **DRIFTED** | 87-93 is `onMounted`; `lastSessionLabel` is at **104-109** |
| `SessionPrepCard.vue:100-106` (`lastSessionLabel`) | **DRIFTED** | Actually at **107-112** |
| `SessionPrepCard.vue:109-111` (`skillDisplayName`) | **DRIFTED** | Actually at **118-120**; the raw-code fallback is confirmed |
| `NeglectedSkillFlagRepository.java:12` | DRIFTED (off by 1) | Actually at **:11** |
| `PlayerTimelineRepository.java:66-73` | MATCH on location; **substance wrong** | §3 M12 |
| `PlayerTimelineRepository.java:70-73` (`?` parser comment) | **DRIFTED** | Actually at **62-65**; 70-73 is the ORDER BY / signature |
| `sessionBuilder.store.js:117-150` (`fetchExistingPlan`) | **DRIFTED** | Actually at **111-144** |
| `sessionBuilder.store.js:57-92` (`initForBooking`) | **DRIFTED** | Actually at **73-109** |
| `sessionBuilder.store.js:155-165` (`savePlan` payload) | DRIFTED (small) | Actually at **152-161** |
| `CoachCommandCenterPage.vue:121-130`, `:754-758` | MATCH | |
| `CoachCommandCenterPage.vue:155` (cancel idiom) | DRIFTED (off by 2) | Actually at **:153** |
| `CoachCommandCenterPageSessionPrepSpec.js:74-81` | MATCH | |
| `SessionPlanService:240-250` | MATCH | 242-252 |
| `SessionPrepService:~92`, `:48-52` | MATCH | Constructor call at 82-89 |
| V163 shape; V161 index columns; V164 free | MATCH | The highest migration is V163 |
| SessionPrepResponse has 7 fields; RosterEntryResponse has 6 fields | MATCH | Each record has exactly one constructor call site, and no test builds either |

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| deferred-157 AC4 = AVAILABILITY_BLOCK group template | Story file, `deferred-work.md:3417`, `GdprErasureService:260-267,493-524` comments, commit `7121ace5` | CONFIRMED. Strictly it is AC3+AC4, and `GdprErasureIT` also references the reason codes |
| deferred-156: `sanitizeAndTruncate`, `PlayerRequestResponse`, notes GDPR | Ledger `:3334,:3396`, code comments, `git log -S` → `56803337` | CONFIRMED |
| deferred-158 "hardened GdprErasureService's booking statements" | `git show 4cea321f` | PARTIAL. The `GdprErasureService` change is **comment-only**; the hardening is in the tests. The dynamic-key exception list predates it (deferred-157 AC11a) |
| deferred-156 AC5: no `playerName` in the URL | Story, `MyPlayersPage.vue:111` comment, commit | CONFIRMED |
| deferred-152 AC6: roster name source and filtering | Story, `CoachRosterService:108` comment, `ec70ef4c` | CONFIRMED |
| 12-3 AC3 "recap only from the signal's source event" | 12-3 story and its code review, `CoachPlayerLastSessionResolver.isRecapAttributable` | CONFIRMED in intent. The paraphrase is the pre-review wording (now booking-identity correlation); harmless because AC12 says not to touch the pair |
| 12-5 is the latest Epic 12 story (`c7132064`) | git log, sprint-status | CONFIRMED |
| V163 `playerRequest` precedent | V163 header, `890f5c67` | CONFIRMED. The attribution belongs to deferred-155 |
| Epic gap map G1-G6 → 12.6; G13/G18/G23 owners; owner decisions | `skillars-epics.md:3471-3542` | CONFIRMED, with notes below |
| G2/G3 "caption and badge derive from the same signal" | Epic text and story AC9 | PARTIAL. The story makes the caption share the badge's **inputs**, not one signal. That removes the reported contradiction but is weaker than the epic's wording |
| G5 "applied to the session focus" | Epic text and AC13 | **CONTRADICTS the epic**, openly flagged as "author's call, owner veto". Needs explicit owner sign-off |
| Brainstorm Pattern #2/#5/#9, Priority 3 | Brainstorm file `:68,:80,:112,:238` | CONFIRMED |
| `NativeModifyingVersionAuditTest` covers JPQL bulk updates; `MigrationConventionLintTest` runs in the test phase | Test sources, `migration-conventions.md:12` | CONFIRMED |

## 3. Mechanistic claims

- **M1 — GDPR export serialises the `Booking` entity, and `@JsonIgnore` closes the leak.** TRUE. `GdprExportService:67` uses `objectMapper.copy()` of the Spring mapper (`CommonConfig:33-38`): no Hibernate module, no mixins, so annotations are honoured. `Booking` has no `@JsonIgnore` today. See §5 F7 for the test caveat.
- **M2 — `toResponse` is positional, and no other non-coach path exposes entity fields.** TRUE. `getPlayerRequest()` has 3 call sites (`SessionPrepService:89`, `BookingService:761,1171`). No controller returns `Booking`. `BookingDuplicationService:126-134` copies fields one by one and does not copy notes or requests.
- **M3 — `recordNoShowPlayer` ownership shape; `setPlayerRequest` uses the parentId check.** TRUE.
- **M4 — Editable statuses, Start moves UPCOMING → IN_PROGRESS, both errors return 403.** TRUE. `BookingStateMachine:38-52` allows START only from UPCOMING. `OperationNotAllowedException` → `ApiAdvice:329-339` is a 403 unconditionally. The guard uses bare `Instant.now()` (`:787`).
- **M6 — "Same `OptimisticLockingFailureException → CONCURRENT_MODIFICATION` mapping … a 409 here is a real, reachable outcome."** **FALSE as stated.** Re-verified:
  - `getBookingOrThrow` is `findById` (`:731-733`), so the entity is managed. `bookingRepository.save(booking)` inside the try is a merge with no flush.
  - The version-checked UPDATE therefore runs at commit, outside the try. It surfaces as `ObjectOptimisticLockingFailureException` → `ApiAdvice:656-662` → **409 `generic.conflict`**.
  - If the catch ever did fire, it would return **403** with errorKey `booking.concurrentModification`. `BookingError.java:24-36` says so explicitly.
  - The race itself is real: `BookingReminderScheduler` moves CONFIRMED→UPCOMING, and the parent's `setPlayerRequest` writes the same row.
- **M7 — `@Version`, no `@DynamicUpdate`, the version-bump comment, "never written by the coach".** TRUE.
- **M8 — Coach scrub is one REQUIRES_NEW tx with one transaction-scoped lock_timeout.** TRUE. Re-verified at `:218-236` and `:464-470`. The per-group constants are reason-code **labels only**, and any group failure rolls back all of them. Adding a 5th group (`BOOKING_PLAN_NOTE`, which locks booking rows) adds failure surface that rolls back the SCD/TIMELINE/REPORT/AVAILABILITY_BLOCK scrubs too. That is consistent with the documented design and acceptable, but the AC should say it.
- **M9 — `SessionPrepService` is readOnly, checks ownership, has no status gate; `findByPlayerIdAndResolvedAtIsNull` exists; ID spaces match.** TRUE. The partial unique index `(player_id, skill_code) WHERE resolved_at IS NULL` makes de-duplication redundant (harmless).
- **M10 — Roster uses ClockProvider; dormancy is booking-time based; projection carries `MAX(requestedStartTime)` only.** TRUE (line drift noted in §1).
- **M11 — `lastSessionDate` can be after `now`, which justifies the clamp.** TRUE. `TimelineEventListener:111` uses bare `Instant.now()`, `SluCalculationService` runs async, and fixed-clock tests make this real.
- **M12 — "same shape and rationale as `findSessionNotesForReport` … filter on the `coach_id` column".** **FALSE / backwards.** Re-verified at `PlayerTimelineRepository:47-53,67`. The precedent filters `metadata->>'coachId'` *precisely because* the column is attendance-gated. Choosing the column for this new query is a valid deliberate choice (it excludes unattended sessions and uses the V161 index), but the precedent claim is inverted. A developer copying the precedent would get the opposite filter.
- **M13 — `developmentFocus` is a list of `@ValidFocusCode` skill codes.** TRUE (`UpdateSessionPlanRequest:14`).
- **M14 — `isBookingPlannable` accepts CONFIRMED only; UPCOMING without a plan → `SESSION_BOOKING_NOT_OWNED`.** TRUE.
- **M15 — "the prep fetch is independent of `checkSessionBuilderGate`".** PARTIAL. `checkSessionBuilderGate` is a **backend** method; there is no such call in `src/frontend`. The frontend signal is `isGated`, which is only known after `fetchExistingPlan` resolves (`SessionBuilderPage.vue:299-304`).
- **M16 — The `$t` stub echoes keys; i18n spec files exist; pluralisation works.** TRUE.
- **M17 — ID spaces.** TRUE.
- **M18 — "Spring Boot 3.5.11".** **FALSE.** `pom.xml:8` is **3.5.16**. Java 17 and commons-lang3 3.21.0 are correct.

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

**F1 (MED) — AC2 / AC12 / AC15: the 409 / CONCURRENT_MODIFICATION contract is wrong.** See M6. Evidence: `BookingService.java:731-733,756-760`, `ApiAdvice.java:329-339,656-662`, `BookingError.java:24-36`.
- *Fix:*
  - Either call `saveAndFlush` inside the try, so the catch actually fires (→ 403 + `booking.concurrentModification`), or accept the commit-time 409 `generic.conflict`.
  - State which one, and have the frontend handle both (precedent: `ParentBookingsPage.vue` branches on `errorKey`).
  - The `CoachPlanNoteResourceIT` "409 concurrent modification" case is not reproducible with sequential HTTP calls (the request carries no version). Move it to a `BookingServiceTest` unit case, or an IT with truly interleaved transactions. `PlayerRequestResourceIT` has no 409 test either.

**F2 (MED) — AC2 / AC3: the "same two predicates" cannot be shared as specified.** `PLAYER_REQUEST_EDITABLE_STATUSES` and the guard are `private` in `booking.service.BookingService`. `SessionPrepService` (`development`) imports only `booking.repo.*`; nothing in `development` imports `booking.service`.
- "Rename it, or share one private helper" does not make it reachable for `coachPlanNoteEditable`, which pushes the developer into the duplicate status list the story forbids.
- *Fix:* a public predicate on the entity (e.g. `Booking.isPrepNoteEditable(Instant now)`), used by both services.

**F3 (MED) — AC2 vs AC8: two clocks.** The write guard uses bare `Instant.now()` (`BookingService:787`). AC8 mandates `ClockProvider` in `SessionPrepService`. With a fixed `ClockProvider` in tests, `coachPlanNoteEditable` can be `true` while the write is rejected (or the reverse).
- *Fix:* pass `Instant.now(ClockProvider.getClock())` into the shared predicate from F2.
- Changing the existing `setPlayerRequest` guard to ClockProvider needs a check that existing ITs don't rely on the wall clock.

**F4 (MED) — AC13: prep fetch ordering vs `initForBooking` reset.** `fetchExistingPlan` calls `initForBooking(bId)` at entry (`store:112`) **and again on 404** (`:131-132`), and that is the common "no plan yet" path. If `prep` is reset in `initForBooking` (as AC13 asks) and `fetchPrep` runs concurrently or first, the prep is wiped. `isGated` is also only known after `fetchExistingPlan`.
- *Fix:* specify `await fetchExistingPlan(bookingId); if (!isGated) await fetchPrep(bookingId)` in `SessionBuilderPage.onMounted`, and add a spec for the 404-then-prep path.

**F5 (MED) — AC13: the suggestion card ignores editability and unsaved panel edits.**
- The Apply buttons are not gated on `prep.coachPlanNoteEditable`. The builder is reachable for IN_PROGRESS/COMPLETED/past-end bookings, and `getPrep` has no status gate, so Apply would get a 403.
- Apply builds from the *saved* `prep.coachPlanNote`. If the coach has unsaved text in the `CoachPlanNotePanel` on the same page, Apply silently discards it, which breaks the AC's own "never silently overwrite" rule.
- *Fix:* hide or disable Apply when the note is not editable. Apply into the panel draft, or disable Apply while the panel is dirty.

**F6 (LOW-MED) — AC13: sanitisation makes "already contains" and the 500-char check unreliable.**
- The recap `whatsNext` is stored raw (`BookingCompletionService:163`, `WrapUpRequest:22` only `@NotBlank @Size(max=500)`).
- The plan-note write runs `ContactDetailSanitizer`, which replaces emails and phone-like runs with `"[contact details removed]"`. If a suggestion contains contact details, the stored note never contains the raw suggestion, so "Add to plan note" reappears forever and re-appends.
- Redaction can also lengthen the text past the client's 500 check, and then the server silently truncates.
- This is rare (coach-authored text), but real.
- *Fix:* sanitise `suggestedWhatsNext` server-side before returning it. Always render the note as the server returned it.

**F7 (LOW) — AC12: precedent inverted.** See M12. *Fix:* reword to "unlike `findSessionNotesForReport` (which filters `metadata->>'coachId'`), filter on the `coach_id` column, deliberately, because it is attendance-gated."
- Also consider `reference_id <> :bookingId`. Opening the builder or prep on a booking that already has its own SESSION_COMPLETED event would otherwise "suggest" that same session's `whatsNext`. This is low impact, because completed bookings are mostly outside the prep entry points.

**F8 (LOW) — AC4: the export leak test must actually serialise.** `GdprExportServiceTest` is Mockito with `@InjectMocks` and only asserts the `List<Booking>` returned by `buildBookings()` (`:31-56`). A test at that level proves nothing about `@JsonIgnore`.
- *Fix:* serialise through the real mapper (`objectMapper.copy()` as at `:67`), or generate the zip in an IT, and assert the text is absent.

**F9 (LOW) — Dev Notes table contradicts AC8.** The `RosterEntryResponse` row says "Expose it [daysSinceLastActivity] + `daysSinceLastSession` + `hasUpcomingBooking`". AC8 says "`daysSinceLastActivity` stays internal". Fix the table.

**F10 (LOW) — AC5 misses comment updates.** "four groups" wording at `GdprErasureService:218-236`, `:464-467` and the enum comment near `:252-259` also need updating for the 5th group, as well as the `log.warn` at `:526`.

**F11 (LOW) — AC9 rule 2 copy.** `!hasUpcomingBooking` also covers a latest booking that is COMPLETED-but-unattended, NO_SHOW_* or IN_PROGRESS. For a COMPLETED-unattended booking, "no completed session yet" is literally false. Suggested copy: "Booked, but no attended session yet."

**F12 (LOW) — AC8: `Duration.toDays` is 24h-based, and `lastSessionDate` is processing time.** "today" can mean yesterday evening, and "1 day ago" can cover up to 47h. Quick-complete-timeout and sweeper events stamp later than the session. This is consistent with dormancy (`daysSinceLastActivity ≤ daysSinceLastSession` by construction, so no contradiction), but record it as an accepted imprecision or soften the "today" copy.

**F13 (LOW) — stale facts.** Spring Boot 3.5.16 (not 3.5.11). The deferred-158 description is "comment-only in GdprErasureService; hardening in tests". The cited line numbers in §1 marked DRIFTED should be corrected before dev.

## 5. What did not survive re-verification

- **Multiple coach profiles per user / admin callers.** Refuted: `coach_profiles_user_id_key UNIQUE (user_id)`, and admins without ROLE_COACH get 403.
- **"403 for not-editable / session-ended is wrong".** Refuted: `ApiAdvice:332` maps to 403, and `PlayerRequestResourceIT:179` asserts it.
- **Other Booking serialisation leaks** (outbox, caches, toString, Spring Data REST, duplication). Refuted: none found, and `BookingDuplicationService` does not copy free text.
- **Coach-erasure ordering** (coachId nulled before the new query). Refuted: the coach branch never nulls `coachId` or deletes bookings, and there is no prior booking write in the outer tx, so no REQUIRES_NEW self-deadlock.
- **Roster rule 2 for players with zero bookings.** Refuted: roster membership comes from the bookings query.
- **Command center doesn't list CONFIRMED bookings.** Refuted: the cancel button at `:153` already renders for CONFIRMED.
- **Missing `SessionPrepResponse` constructor call sites.** Refuted: there is one call site.
- **Appending `"\n"` can join digits across the boundary into one phone redaction** (raised by Layer 4). **Not re-verified.** `PHONE_PATTERN`'s exact regex was not read in Step 4b; the redaction also requires a 5+ digit run (`PHONE_DIGIT_RUN`). Dropped from the findings; it is subsumed by F6's "sanitise the suggestion server-side" fix.
- **`GdprExportService:122` passes a User id to `findAllByPlayerId`** (ID-space mismatch). Pre-existing and out of this story's scope. **Not re-verified in Step 4b.** Worth a separate ledger check, not a 12-6 change.
- **12-3 AC3 paraphrase out of date.** Downgraded to informational, because AC12 explicitly says not to touch the existing pair.

## 6. Recommendation

The story is well-researched and its central design calls hold up: the `@JsonIgnore` leak analysis, the coach-ownership shape, the 5th erasure group, server-side days-since, neglected-skill codes, and the G6 frontend-only change. Fix it before dev on these points, in priority order:

1. **F1 (409 contract)** — rewrite the concurrency wording and test plan. *Confidence: high.* Re-read `getBookingOrThrow`, the catch, both `ApiAdvice` handlers and `BookingError`'s own Javadoc.
2. **F2 + F3 (shared predicate across modules, one clock)** — specify a public entity-level predicate taking `now`. *High* for the visibility problem (grep re-run). *Medium* on how much existing-test fallout a ClockProvider switch causes (not checked).
3. **F4 + F5 (Session Builder ordering, editability, unsaved draft)** — specify the `onMounted` sequence and the Apply gating. *High* for the ordering (store and page re-read). *Medium* for the unsaved-draft issue (depends on how the panel is built).
4. **F6 (sanitised suggestion)** — *Medium.* Raw storage and sanitiser behaviour were verified; real-world frequency is low.
5. **F7-F13** — wording and citation corrections. *High* for the line-number drift (each re-checked by the citation layer). The G5 deviation (apply → plan note instead of session focus) was **approved by the owner on 2026-10-22**, and the story file now records it as settled.

What was **not** independently re-checked in Step 4b: the exact `PHONE_PATTERN` regex, the GDPR export PLAYER-branch ID-space bug, and the small DRIFTED line numbers beyond the ones listed (accepted from Layer 1 with Layer 3/4 corroboration).
