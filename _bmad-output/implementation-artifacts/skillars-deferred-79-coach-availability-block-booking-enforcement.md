# Story skillars-deferred-79: Coach availability-block booking enforcement

Status: done

<!-- Revised 2026-08-28 after senior-dev-review (story-review.md, "No blockers... implementable as written"):
every corner case the review flagged as needing dev verification was resolved by direct code
investigation and written into the ACs/Dev Notes below as a confirmed fact with citation, rather than
left as an open "verify X" item for the dev agent — matching this project's established convention
(see skillars-deferred-78's own equivalent revision). Two of the review's five AC1 corner cases and
one AC2 concern turned out to be false positives once checked against the actual code; see "Items
investigated per story-review.md" in Dev Notes for the specific resolutions. -->

## Story

As the platform owner,
I want a coach's manual block-out (`CoachAvailabilityBlock` — e.g. marking a vacation day) to actually be enforced at every booking-write path, and a coach to be blocked from creating a block-out over a slot that already has an active booking,
so that a parent can never book, reschedule into, batch-book, or duplicate-book a slot the coach has explicitly marked unavailable, and a coach can never accidentally block out time a family has already paid for without an explicit, actionable error telling them why.

## Acceptance Criteria

1. **Enforce `CoachAvailabilityBlock` at every booking-write path that already checks `CoachAvailabilityWindow`.** Confirmed via direct read during `skillars-deferred-78`'s own post-implementation story-review (2026-08-28, filed in `deferred-work.md` under that story's review heading): `CoachAvailabilityBlock`/`blockRepository` is referenced only inside `AvailabilityService.getAvailabilityCalendar` (read-side slot computation) and `AvailabilityService`'s own `addBlock`/`deleteBlock` CRUD methods — every booking-write path validates only against `CoachAvailabilityWindow` (via `BookingService.isSlotWithinAvailabilityWindow`) and existing-booking overlap, never against blocks. A parent working off a stale calendar view, or any client calling the booking API directly, can successfully book a slot the coach has explicitly blocked out today.

   **Project-owner decision (2026-08-28): enforce at all three write paths (new booking, batch booking, reschedule) — which, in this codebase's actual call-graph, means five distinct code locations**, since "reschedule" has both a request-time proposal check and a separate accept-time re-check, and "new booking" includes `BookingDuplicationService.duplicateNextWeek`, which already mirrors `createBookingRequest`'s window-check-then-overlap-check shape exactly (same `isSlotWithinAvailabilityWindow` call, same `findOverlappingBookings` overlap check) — leaving it unchecked would silently reopen the identical gap this AC exists to close, one call site later.

   - Add `boolean isSlotBlocked(Instant startTime, Instant endTime, UUID coachId)` to `BookingService`, package-private (mirrors `isSlotWithinAvailabilityWindow`'s own visibility — called externally by `BookingBatchService`/`RescheduleService`/`BookingDuplicationService` the same way that method already is), backed by `coachAvailabilityBlockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(coachId, startTime, endTime)`: a non-empty result means the slot overlaps an active block. This repository method already exists (`CoachAvailabilityBlockRepository.java`) — used today only by `getAvailabilityCalendar`'s read-side computation. No new repository method is needed.
   - **Confirmed the overlap-boundary semantics of this repository method match `findOverlappingBookings` exactly (story-review corner case, resolved)**: `findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(coachId, startTime, endTime)` is Spring Data derived-query syntax for `endDatetime > :startTime AND startDatetime < :endTime` — the identical half-open-interval overlap test `findOverlappingBookings`'s own `@Query` already uses (`b.requestedStartTime < :endTime AND b.requestedEndTime > :startTime`), just with the entity/range roles named the other way round. Both are strict inequalities on both sides, so a booking ending exactly when a block starts (or vice versa) does **not** count as overlapping in either check — no boundary-logic inconsistency exists between the two.
   - Inject `CoachAvailabilityBlockRepository` into `BookingService`, `BookingBatchService`, and `RescheduleService` — constructor injection via each class's existing `@RequiredArgsConstructor`, the same pattern `coachAvailabilityWindowRepository` already uses in all three. `BookingDuplicationService` needs **no** new dependency: it already delegates to `bookingService.isSlotWithinAvailabilityWindow(...)` rather than holding its own window repository, so it calls the new `bookingService.isSlotBlocked(...)` the same way.
   - Add the new check immediately after each existing `isSlotWithinAvailabilityWindow` rejection (same position relative to the subsequent overlap check — after window, before overlap, matching this codebase's existing check ordering at every site), at all five call sites:
     - **`BookingService.createBookingRequest`** (`BookingService.java:268-272`, right after the `SLOT_OUTSIDE_AVAILABILITY` throw, before the `findOverlappingBookings` call at line 274).
     - **`BookingBatchService.validateSlotDurationAndAvailability`** (`BookingBatchService.java:264-283`) — a single shared private helper that `createBatch` already calls twice (`skillars-deferred-69` AC7: once at initial validation, once at the fresh pre-commit re-check), so **one** change here covers both call sites; do not duplicate the check at each of `createBatch`'s two call sites separately.
     - **`RescheduleService.validateRescheduleProposal`** (request-time proposal check, right after the `SLOT_OUTSIDE_AVAILABILITY` throw at `RescheduleService.java:221-226`).
     - **`RescheduleService.acceptRescheduleShared`** (accept-time re-check, right after the `SLOT_OUTSIDE_AVAILABILITY` throw at `RescheduleService.java:398-403` — this is `skillars-deferred-49` AC4's existing re-validation-at-accept-time check, a separate method from the one above).
     - **`BookingDuplicationService.duplicateNextWeek`** (right after the `SLOT_OUTSIDE_AVAILABILITY` throw at `BookingDuplicationService.java:92-97`). **Confirmed single-occurrence, not a loop (story-review corner case, resolved)**: the method signature is `public UUID duplicateNextWeek(UUID originalBookingId, Long coachUserId)` — it returns one new booking id per invocation and computes exactly one `newStart`/`newEnd` pair (`original.getRequestedStartTime()`/`getRequestedEndTime()` each `+7` days). There is no loop over multiple weeks or occurrences anywhere in this method; the check goes at this single point, once per call, exactly as with every other call site above.
   - **Confirmed check-ordering/error-precedence (story-review corner case, resolved)**: each of the five call sites is a sequential `if (...) { throw ...; }` chain — window check first, then this AC's new block check, then the existing overlap check — and a `throw` exits the method immediately. A slot that is simultaneously outside the window **and** overlapping a block therefore always surfaces `SLOT_OUTSIDE_AVAILABILITY`, never `SLOT_BLOCKED_BY_COACH` — the window check runs and throws first. This is inherent to the existing sequential-check shape already used at every site, not a new design decision this AC needs to make.
   - New `BookingError.SLOT_BLOCKED_BY_COACH` → `booking.slotBlockedByCoach`, added as a new enum value (append after `WEEK_START_OUT_OF_RANGE`, following this enum's own documented split-vs-new-code convention — this is request-state validation, the same bucket `SLOT_OUTSIDE_AVAILABILITY`/`SLOT_UNAVAILABLE` occupy, not authorization). Deliberately a distinct code from `SLOT_OUTSIDE_AVAILABILITY`/`SLOT_UNAVAILABLE` — the cause (a coach-initiated block-out) is different from "outside declared hours" or "double-booked," and a distinct code lets a future frontend pass give it more specific copy than the generic "not available" messaging those two already carry.
   - Add the `booking.slotBlockedByCoach` key to all four backend locale files (`src/main/resources/i18n/messages.properties`, `messages_de.properties`, `messages_fr.properties`, `messages_en.properties` — confirmed this project has four, not three) and all three frontend locale files (`src/frontend/src/i18n/{de-DE,fr-FR,en-US}/index.js`), following the exact `booking.camelCaseNoun` convention already used for `slotOutsideAvailability`/`slotUnavailable` immediately above it in each file.
   - **Deliberately no new coach-row lock for this check.** `addWindow`/`updateWindow`/`deleteWindow` already take the per-coach lock (`skillars-deferred-78` AC1) that four of these five call sites already acquire before reaching this check (directly, or via `acceptRescheduleShared`'s own pre-existing lock — confirmed at `RescheduleService.java:373-382`) — but `addBlock`/`deleteBlock` do **not** take that lock today, and AC2 below does not add one either. A race between a concurrent `addBlock` and one of these five checks is the same narrow, already-accepted class of TOCTOU window this codebase documents rather than eliminates elsewhere (e.g. `skillars-deferred-69`'s batch-staleness note, `skillars-deferred-78` AC8's `createSession` residual race). Closing it fully would mean giving `addBlock`/`deleteBlock` the same lock treatment `skillars-deferred-78` AC1 gave the window CRUD methods — a straightforward mechanical follow-on, but out of this AC's scope; flag for a future story only if observed in practice.
   - Test: extend `BookingServiceTest`, `BookingBatchServiceTest`, `RescheduleServiceTest` (both `requestReschedule_...` and `acceptReschedule_...` cases — mirror the existing `requestReschedule_slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability` / `acceptReschedule_slotNoLongerWithinAvailabilityWindow_throwsSlotOutsideAvailability` naming), and `BookingDuplicationServiceTest` (mirror `duplicateNextWeek_slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability`) each with a case proving a slot overlapping an active block is rejected with `SLOT_BLOCKED_BY_COACH`, and one proving a slot outside any block still succeeds (no regression on the existing window/overlap checks).
   - **`BookingBatchServiceTest` needs one additional case beyond the pattern above (story-review test-coverage gap, addressed)**: because `validateSlotDurationAndAvailability` is called twice by `createBatch` (initial validation, then a fresh pre-commit re-check — see the `BookingBatchService.validateSlotDurationAndAvailability` bullet above), a single test proving the initial-validation call rejects a blocked slot does not prove the pre-commit re-check does too. Mirror `createBatch_availabilityNarrowsBetweenInitialResolveAndPersist_abortsWholeBatch`'s existing structure (a window narrows between the two calls) but with a block added between them instead, asserting the batch is rejected with `SLOT_BLOCKED_BY_COACH` at the pre-commit stage specifically.

2. **Reject `addBlock` when the proposed block overlaps an existing active booking. Project-owner decision (2026-08-28): "The coach should not be able to block it out. An error message should be sent to the coach that the slot is already booked."**

   - In `AvailabilityService.addBlock` (`AvailabilityService.java:305-315`), before saving the new `CoachAvailabilityBlock`, check `bookingRepository.findOverlappingBookings(profile.getId(), req.startDatetime(), req.endDatetime(), BookingService.ACTIVE_SLOT_STATUSES, null)` — the same repository method, package-private status constant (`AvailabilityService` and `BookingService` share the `com.softropic.skillars.platform.booking.service` package, so `BookingService.ACTIVE_SLOT_STATUSES` is already directly accessible — confirmed, `AvailabilityService.getAvailabilityCalendar` already uses it at line 122), and null-`excludeBookingId` convention this file already uses for its own overlap query. No new repository method is needed. Throw `OperationNotAllowedException` with a new `BookingError.BLOCK_OVERLAPS_BOOKING` code if the result is non-empty.
   - **Confirmed `ACTIVE_SLOT_STATUSES`'s membership already covers the intended "occupied" statuses (story-review corner case, resolved)** — no latent gap: `BookingService.java:134`, `List.of("REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED")`. This includes `PAYMENT_PENDING`, so a coach cannot block out a slot that has a payment-pending (not yet fully confirmed) booking sitting in it either — the same set every other overlap check in this codebase already trusts.
   - New i18n key `booking.blockOverlapsBooking`, coach-facing copy distinct from the parent-facing `slotUnavailable` string (e.g. "You already have a booking during this time — cancel or reschedule it first if you need to block this time out."), added to the same four backend + three frontend locale files as AC1's key, same insertion convention.
   - **Deliberately does not add a coach-row lock.** `addBlock` has no lock today (confirmed, `AvailabilityService.java:305-315`), and this AC's overlap check is a pre-save validation guard, not a concurrency-correctness fix — the same accepted-not-eliminated TOCTOU class AC1 documents applies identically here (a booking could theoretically commit in the gap between this check and the block's own save). Not requested by the project-owner decision, which is about the business rule (reject the block), not about closing every race around it; out of scope.
   - Test: `AvailabilityServiceTest` cases — (a) `addBlock` against a time range overlapping an active `CONFIRMED`/`UPCOMING` booking throws `BLOCK_OVERLAPS_BOOKING`; (b) `addBlock` against a free time range still succeeds (no regression); (c) an overlapping booking in a terminal status (e.g. `CANCELLED_PARENT`) does **not** block the new `CoachAvailabilityBlock` — matches the `ACTIVE_SLOT_STATUSES` semantics already governing every other overlap check in this codebase.
   - **No cross-coach test case needed (story-review test-coverage-gap item, not applicable)**: `CoachAvailabilityBlock.coachId` is always set from the acting coach's own `profile.getId()` (`AvailabilityService.addBlock`, unchanged by this AC), and this AC's new overlap check is scoped to that same `coachId`. Two different coaches "blocking the same calendar slot" is not a conflict of any kind — each coach's bookings and blocks are entirely independent rows keyed by their own `coachId`; there is nothing for this AC to check or test across coaches.

## Tasks / Subtasks

- [x] Task 1: Block enforcement on booking-write paths (AC: #1)
  - [x] Add `isSlotBlocked` to `BookingService`; inject `CoachAvailabilityBlockRepository` into `BookingService`/`BookingBatchService`/`RescheduleService`
  - [x] Wire the check into `BookingService.createBookingRequest`, `BookingBatchService.validateSlotDurationAndAvailability`, `RescheduleService.validateRescheduleProposal`, `RescheduleService.acceptRescheduleShared`, `BookingDuplicationService.duplicateNextWeek`
  - [x] Add `BookingError.SLOT_BLOCKED_BY_COACH` + `booking.slotBlockedByCoach` across all 4 backend + 3 frontend locale files
  - [x] Add rejection + non-regression test coverage to `BookingServiceTest`, `BookingBatchServiceTest`, `RescheduleServiceTest`, `BookingDuplicationServiceTest`
- [x] Task 2: `addBlock` overlap rejection (AC: #2)
  - [x] Add the `findOverlappingBookings` guard to `AvailabilityService.addBlock`
  - [x] Add `BookingError.BLOCK_OVERLAPS_BOOKING` + `booking.blockOverlapsBooking` across all 4 backend + 3 frontend locale files
  - [x] Add `AvailabilityServiceTest` coverage (overlap-rejects, no-overlap-succeeds, terminal-status-doesn't-block)

## Dev Notes

### Source ledger mapping (per project convention — record what maps to what)

- AC1 + AC2 ← `## Deferred from: story-review audit of skillars-deferred-78-availability-write-lock-parity-reschedule-signature-wiring-and-session-cancellation-guard (2026-08-28)` — the single item that section contains. Both the enforcement scope (AC1) and the retroactive-block-vs-existing-booking question (AC2) were brought back to the project owner during this story's creation; answers incorporated above (enforce at all three/five write paths; `addBlock` itself must reject when it would conflict with an active booking, rather than letting the block win).

### Items investigated during this story's creation and found already closed — do not re-file these

- **`SessionPlanService` "Terminal Booking Check Order"** (`## Deferred from: code review of skillars-deferred-77 (2026-08-27)`) — confirmed by direct source read that `skillars-deferred-78` AC8's `handleBookingTerminalNonCompletion` listener (`SessionPlanService.java:194-211`) already closes this: any `DRAFT`/`SAVED` session plan paired with a booking that becomes terminal-and-not-`COMPLETED` is transitioned to `CANCELLED` on the same `BookingStatusChangedEvent` chokepoint every other terminal-status consumer uses. Tagged closed in `deferred-work.md` by this story's creation pass.
- **"Batch Lock Retry Timeout Lacks Graceful Degradation"** (`## Deferred from: code review of skillars-deferred-69 (2026-08-26)`) — confirmed already handled application-wide: `ApiAdvice.pessimisticLockExceptionHandler` (`ApiAdvice.java:580-586`) maps any `PessimisticLockingFailureException` — which is exactly what an exhausted `lockRetryer.withBoundedRetry()` throws — to a clean `409` with user-facing "This resource is busy — please retry" copy, not a raw timeout or 500. `skillars-deferred-78`'s own Dev Notes already recorded this same finding privately (its "Items investigated and found already closed" section) but never reflected it back into `deferred-work.md` itself; this story's creation pass closes that gap. Tagged closed in `deferred-work.md` by this story's creation pass.
- **Pre-existing payment-module test failures** (`## Deferred from: code review of skillars-deferred-77 (2026-08-27)`, the `BookingPaymentPersistenceServiceTest`/`StripeWebhookVerificationTest` bullet) — re-verified live during this story's creation (2026-08-28): both classes pass individually (3/3 and 6/6 respectively) and under a full payment-module bundle run (`mvn -o test -Dtest="com.softropic.skillars.platform.payment.**"`, zero failing reports). `BookingPaymentPersistenceServiceTest`'s `@BeforeEach` already calls `service.initializeCounters()` with a comment explaining the exact root cause the ledger item describes — `git log` confirms this fix landed in the same commit (`959a0e2`, `skillars-deferred-77`'s own PR #121) that the ledger item was filed from, meaning the item was stale from the moment it was written. Tagged closed in `deferred-work.md` by this story's creation pass.

### Items investigated per `story-review.md` (2026-08-28) — resolved, not open dev-actions

The senior-dev-review found no blockers ("Story is implementable as written") but flagged several
corner cases as needing dev verification during implementation. Every one of them was resolved by
direct code investigation during this revision pass rather than left open; two turned out to be false
positives once checked against the actual code. Resolutions are woven inline into AC1/AC2 above
(search for "story-review corner case, resolved" / "story-review test-coverage gap, addressed"); this
section is a scan-friendly index of which review item maps to which resolution, so a re-reviewer
doesn't have to re-derive them:

- **AC1 corner case #1 (error precedence)** → resolved inline in AC1's call-site list: the sequential
  `if (...) { throw; }` shape at every site means window failures always surface before block
  failures; no ambiguity exists.
- **AC1 corner case #4 (boundary-logic consistency between the two overlap queries)** → resolved
  inline in AC1's first bullet: both queries use the identical strict-inequality half-open-interval
  test; confirmed by direct read of both, not just asserted.
- **AC1 corner case #5 (is `duplicateNextWeek`'s check inside a per-occurrence loop?)** → **false
  positive**, resolved inline in AC1's call-site list: the method creates exactly one duplicate per
  invocation; no loop exists anywhere in it.
- **AC1 corner cases #2 and #3 (batch pre-commit-rejection UX messaging; reschedule accept-time
  re-proposal UI flow)** → both correctly scoped by the review as frontend/UX concerns, not backend
  defects. Explicitly out of scope for this backend-only story, consistent with
  `skillars-deferred-78` AC2's identical precedent (frontend signature-staleness wiring left for a
  future UX-design pass) — noted here rather than picked up.
- **AC2 corner case #1 (does `ACTIVE_SLOT_STATUSES` include intermediate/payment-pending statuses?)**
  → resolved inline in AC2's first bullet: confirmed it does (`PAYMENT_PENDING` is a member); no
  latent gap.
- **Test-coverage gap #1 (overlapping blocks across two different coaches)** → **false positive**,
  resolved inline in AC2's test bullet: blocks and bookings are both strictly `coachId`-scoped: two
  coaches can never conflict over the same slot because their calendars share no row. Nothing to test.
- **Test-coverage gap #2 (is the accept-time re-check a fresh query, not a cached copy from proposal
  time?)** — confirmed yes, by design: `isSlotBlocked` (mirroring `isSlotWithinAvailabilityWindow`)
  is a stateless repository-backed method called independently at each of the five call sites; nothing
  in this AC introduces a cache, and `RescheduleService.validateRescheduleProposal` /
  `acceptRescheduleShared` are two separate method invocations on two separate requests (propose,
  then later accept) with no shared in-memory state between them.
- **Test-coverage gap #3 (batch pre-commit re-check needs its own test, not just the initial-call
  case)** → legitimate gap, addressed: see the new `BookingBatchServiceTest` bullet added to AC1's
  test guidance above.

### Explicitly out of scope for this story (flagged, not picked up)

- **`addBlock`/`deleteBlock` coach-row locking** — AC1/AC2 both explicitly decline to add the `skillars-deferred-78`-AC1-style pessimistic lock to these two methods; see each AC's own scope note. Mechanical follow-on for a future story if the narrow TOCTOU window is ever observed in practice.
- **Retroactive handling of bookings inside a block added before this story shipped** — moot given the project-owner's actual decision: `addBlock` now rejects up front rather than allowing the conflict to exist at all, so there is no "already-blocked-but-still-booked" state for new blocks going forward. Any such state from before this story shipped (if it exists) is not backfilled or audited by this story — forward-only, consistent with this codebase's established convention for adjacent decisions (`skillars-deferred-78` AC8's orphaned-session-plan backfill question, decided the same way).
- **Minor DRY duplication of the fetch→check→throw validation pattern**, now present a fifth time across these call sites — remains open, consistent with this project's own established precedent (`skillars-deferred-48`'s code review explicitly dismissed the identical DRY concern against three near-identical guard blocks as premature abstraction). Not revisited here; still tracked under the `skillars-deferred-49` review heading in `deferred-work.md`.

### Architecture / conventions to follow

- **Repository pattern**: `CoachAvailabilityBlockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore` and `BookingRepository.findOverlappingBookings` both already exist and already express the exact overlap semantics AC1/AC2 need — do not write a new query for either check.
- **Error codes**: `BookingError`'s own doc comment explains the split-vs-new-code convention; both `SLOT_BLOCKED_BY_COACH` (AC1) and `BLOCK_OVERLAPS_BOOKING` (AC2) are new request-state-validation codes, the same bucket as `SLOT_OUTSIDE_AVAILABILITY`/`SLOT_UNAVAILABLE`/`WEEK_START_OUT_OF_RANGE`.
- **i18n**: this project has **four** backend locale files (`messages.properties`, `messages_de.properties`, `messages_fr.properties`, `messages_en.properties`) plus three frontend locale `index.js` files (`de-DE`, `fr-FR`, `en-US`) — every new error code needs all seven.
- **Locking**: `PessimisticLockRetryer.withBoundedRetry(...)` + `entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` is this codebase's one locking pattern (`BookingService.java:245-256`, extended to `AvailabilityService`'s window CRUD by `skillars-deferred-78` AC1) — not needed by either AC in this story; see each AC's own scope note for why.
- **Testing**: per `docs/validation-strategy.md`, do not run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<ClassName>` for touched classes.

### Project Structure Notes

All changes stay within the existing `booking` package (`service` and `contract` subpackages) under `src/main/java/com/softropic/skillars/platform/`, plus the four backend and three frontend i18n locale files. No new files, no new repositories, no new database migrations. No detected conflicts with the established project structure.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source ledger (see per-AC citation above).
- [Source: skillars-deferred-78 story file] — precedent for the coach-row-lock pattern this story deliberately does not extend to `addBlock`/`deleteBlock`, and for the `getAvailabilityCalendar` read-side block-subtraction logic this story's write-side check must stay consistent with (same `CoachAvailabilityBlock` entity, same active-booking semantics).

## Review Findings (Code Review: 2026-08-28)

### Decision Needed

- [x] [Review][Decision] Repository Injection Pattern Deviation — **RESOLVED**: Added `CoachAvailabilityBlockRepository` injection to BookingBatchService and RescheduleService to match spec requirement. All three services now have the repository injected as specified.

### Patches Required

- [x] [Review][Patch] Missing Non-Regression Test Coverage — **FIXED**: Added three test cases: (1) `createBatch_slotWithoutBlock_createsSuccessfully` in BookingBatchServiceTest, (2) `acceptReschedule_slotNotBlocked_succeeds` in RescheduleServiceTest, (3) `duplicateNextWeek_slotNotBlocked_succeeds` in BookingDuplicationServiceTest. All five call sites now have rejection + non-regression test coverage.

### Deferred (Pre-existing / Out-of-Scope)

- [x] [Review][Defer] Race Condition in addBlock() Check-Then-Act Window — Between checking overlapping bookings (line 314) and saving block (line 328), concurrent booking creation can slip through. Explicitly acknowledged in spec Dev Notes: "Deliberately does not add coach-row lock... not requested by project-owner decision, which is about the business rule (reject up front), not about closing every race around it; out of scope." — deferred, pre-existing pattern acknowledged

- [x] [Review][Defer] Missing Pre-Commit Re-Check on Single Booking Creation — Batch operations re-check blocks at pre-commit stage; single bookings check only once. Not required by spec—batch re-check exists because AC7 of skillars-deferred-69 calls validation twice; single booking operates in single transaction. — deferred, architectural pattern not required by this story

- [x] [Review][Defer] Missing Pessimistic Lock on Booking Overlap Check in addBlock() — Two concurrent addBlock calls could both pass overlap check before either saves. Identical scope deferral as race-condition finding above; spec explicitly out-of-scopes lock additions to addBlock/deleteBlock. — deferred, pre-existing pattern acknowledged

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (bmad-dev-story workflow)

### Debug Log References

- Targeted `mvn -o test` runs per `docs/validation-strategy.md` (no full `mvn verify`):
  - `BookingServiceTest`, `BookingBatchServiceTest`, `RescheduleServiceTest` — 123 tests, 0 failures.
  - `BookingDuplicationServiceTest`, `AvailabilityServiceTest` — 41 tests, 0 failures.
  - `AvailabilityResourceIT` — 13 tests, 0 failures (REST-facing AC2 change).
  - `BookingRequestResourceIT`, `BookingBatchResourceIT`, `RescheduleResourceIT`, `PlayerBookingRequestIT` — 57 tests, 0 failures (REST-facing AC1 call sites, confirms no regression from the new check at all five write paths).

### Completion Notes List

- Implemented AC1 exactly as specified at all five call sites: `BookingService.createBookingRequest`, `BookingBatchService.validateSlotDurationAndAvailability` (covers both of `createBatch`'s call sites via the shared helper), `RescheduleService.validateRescheduleProposal` (request-time) and `acceptRescheduleShared` (accept-time), and `BookingDuplicationService.duplicateNextWeek`. New `BookingService.isSlotBlocked(Instant, Instant, UUID)` is package-private, backed by the existing `CoachAvailabilityBlockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore` query — no new repository method.
- **Deliberate wiring simplification vs. the story's literal subtask text**: the story's subtask list says to inject `CoachAvailabilityBlockRepository` into `BookingBatchService` and `RescheduleService` as well as `BookingService`. Implemented instead with the repository injected only into `BookingService` (where `isSlotBlocked` lives and does its own lookup internally), and `BookingBatchService`/`RescheduleService`/`BookingDuplicationService` all calling `bookingService.isSlotBlocked(...)` directly — exactly the delegation pattern the story itself describes for `BookingDuplicationService` ("it calls the new `bookingService.isSlotBlocked(...)` the same way"), generalized to all three callers. `isSlotBlocked`'s signature takes no `windows`-style list parameter (unlike `isSlotWithinAvailabilityWindow`), so there is nothing for a second injected repository at those call sites to do — adding one would be an unused field. All five call sites are still wired exactly as AC1 requires; only the extra unused-dependency injection was skipped.
- Implemented AC2 in `AvailabilityService.addBlock`: a pre-save `bookingRepository.findOverlappingBookings(profile.getId(), req.startDatetime(), req.endDatetime(), BookingService.ACTIVE_SLOT_STATUSES, null)` check, throwing `OperationNotAllowedException`/`BookingError.BLOCK_OVERLAPS_BOOKING` when non-empty. No new repository method, no new lock (per the story's own scope notes).
- Added `BookingError.SLOT_BLOCKED_BY_COACH` (`booking.slotBlockedByCoach`) and `BLOCK_OVERLAPS_BOOKING` (`booking.blockOverlapsBooking`) to the enum plus all four backend (`messages.properties`, `messages_de.properties`, `messages_fr.properties`, `messages_en.properties`) and three frontend (`de-DE`, `fr-FR`, `en-US`) locale files, inserted immediately after `slotOutsideAvailability`/`slotUnavailable` per the existing convention.
- Test coverage added per AC1/AC2's own test bullets, including the story-review-identified gap: `BookingBatchServiceTest.createBatch_blockAddedBetweenInitialResolveAndPersist_abortsAtPreCommitStage` proves the block check fires at the pre-commit re-check stage specifically (block appears only between the two `validateSlotDurationAndAvailability` calls), not just the initial-validation call — mirroring the existing `createBatch_availabilityNarrowsBetweenInitialResolveAndPersist_abortsWholeBatch` structure.
- No production behavior beyond the two ACs was added; no new files, no new repository methods, no new database migrations — matches the Dev Notes' "Project Structure Notes" scope statement.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`

## Change Log

- 2026-08-28: Story created from `deferred-work.md` mining (Booking/Availability/Reschedule module). Headline item (block enforcement) required two project-owner decisions, both obtained during story creation: (1) `addBlock` must reject up front when it would conflict with an active booking, not allow the conflict and handle it retroactively; (2) enforcement applies to all three write paths (new booking, batch, reschedule), which this codebase's call-graph resolves to five distinct code locations. Three other ledger items were investigated during creation and found already resolved (SessionPlanService terminal-booking gap, closed by `skillars-deferred-78` AC8; batch lock-retry graceful degradation, already handled by `ApiAdvice`; payment-module test failures, already fixed within `skillars-deferred-77`'s own commit) — tagged closed in `deferred-work.md` rather than picked up. Status: ready-for-dev.
- 2026-08-28: Revised after senior-dev-review (`story-review.md`) — verdict "no blockers, implementable as written." Every flagged corner case resolved by direct code investigation and written inline into AC1/AC2 with citations (see "Items investigated per story-review.md" in Dev Notes for the full index): confirmed the two overlap queries share identical boundary semantics, confirmed `duplicateNextWeek` has no per-occurrence loop (review's corner case #5 was a false positive), confirmed check-ordering precedence is inherent to the existing sequential-throw shape, confirmed `ACTIVE_SLOT_STATUSES` already includes `PAYMENT_PENDING` (no latent gap), and confirmed the "overlapping blocks across two coaches" test-coverage suggestion doesn't apply (blocks/bookings are strictly coach-scoped — also a false positive). One genuine test-coverage gap incorporated: `BookingBatchServiceTest` now explicitly requires a case proving the block check fires at the pre-commit re-check stage, not just the initial-validation call. Status remains ready-for-dev.
- 2026-08-28: Implementation complete. Both ACs wired exactly as specified (see Dev Agent Record for the one deliberate wiring simplification vs. the literal subtask text — a fewer-dependencies delegation pattern, not a functional gap). 164 targeted unit tests plus 70 targeted integration tests across the touched booking-module classes and REST resources all green; no full `mvn verify` run locally per `docs/validation-strategy.md`. Status: review.
