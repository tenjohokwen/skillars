# Story Deferred-68: Booking-Module Concurrency-Conflict Error Handling Parity

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want every interactive booking-module write path that can race a concurrent status change on the same
`Booking` row to convert `OptimisticLockingFailureException` into the same clean, retryable
`BookingError.CONCURRENT_MODIFICATION` response `BookingCompletionService` already uses,
so that a double-click or a genuine concurrent-action race never surfaces to a coach or parent as a raw,
unclassified 500 (`generic.unknown`), and the booking module's concurrency-conflict handling is consistent
across every service that mutates a booking's status.

### Why this story exists

`skillars-deferred-67`'s own code review (`_bmad-output/implementation-artifacts/deferred-work.md`, `##
Deferred from: code review of skillars-deferred-67 (2026-08-25)`) closed out `BookingCompletionService`'s
seven `OptimisticLockingFailureException` catch blocks and, in doing so, deferred one open question rather
than answer it: *"Recommend checking if the same pattern (reusing `SecurityError.MISSING_RIGHTS` for
`OptimisticLockingFailureException`) exists elsewhere in `BookingService`, `AvailabilityService`,
`RescheduleService`, or other booking-module services."* This story is that audit, done by direct source
read rather than by trusting the ledger, plus the fix for what it found.

**The literal question the ledger item asked has a clean answer: no.** Grepping
`OptimisticLockingFailureException` across `platform.booking.service` shows it is caught in exactly two
places today — `BookingCompletionService` (all 7 sites, fixed by `skillars-deferred-66`/`-67`) and
`QuickCompleteTimeoutService` (`:55`, a scheduler that already logs and skips, no error code involved at
all). `AvailabilityService` never touches `Booking`, only `CoachAvailabilityWindow`/`CoachAvailabilityBlock`
— neither entity carries `@Version`, so it cannot throw this exception at all; confirmed clean, no action
needed. So nothing today literally reuses `MISSING_RIGHTS` for this exception outside the already-fixed
class.

**But the audit surfaced the real, broader gap the ledger item was actually pointing at:** `Booking` is the
*only* entity with `@Version` in this module (`Booking.java:54-56`), and its core state-transition method,
`BookingService.transitionInternal` (`:151-162`, the private method both `transition()` and
`acceptAndInitiatePayment()` call), does an **unlocked** `getBookingOrThrow` read followed by
`bookingRepository.save(booking)` — no pessimistic lock, exactly like `BookingCompletionService`'s own
pre-fix shape. Six interactive, REST-reachable `BookingService` methods reach this unlocked write path and
catch nothing:

| Method | Write call | Reachable via |
|---|---|---|
| `acceptBooking` (`:316`) | `acceptAndInitiatePayment(bookingId, ctx)` (`:359`) | `POST /api/bookings/{id}/accept` (`BookingResource.java:64-65`) |
| `declineBooking` (`:410`) | `transition(bookingId, BookingEvent.DECLINE, ctx)` (`:420`) | `POST /api/bookings/{id}/decline` (`BookingResource.java:73-74`) |
| `cancelBookingAsCoach` (`:701`) | `transition(bookingId, BookingEvent.CANCEL_COACH, ...)` (`:722`) + trailing `booking.setCancelReason(...); bookingRepository.save(booking);` (`:724-725`) | coach cancel endpoint |
| `recordNoShowPlayer` (`:737`) | `transition(bookingId, BookingEvent.NO_SHOW_PLAYER, ...)` (`:746`) | coach no-show-player endpoint |
| `recordNoShowCoach` (`:755`) | `transition(bookingId, BookingEvent.NO_SHOW_COACH, ...)` (`:778`) | parent no-show-coach endpoint |
| `cancelDueToPause` (`:593`) | `transition(bookingId, BookingEvent.CANCEL_DUE_TO_PAUSE, ...)` (`:598`) | called in a loop from `PackSessionService`'s pause-pack flow (an interactive, parent-triggered endpoint), once per conflicting booking being cancelled |

None of these six catch `OptimisticLockingFailureException`. `BookingApiAdvice.java` has no handler for it
either (only `PaymentGatewayException`). It falls through to `ApiAdvice`'s catch-all `@ExceptionHandler
(Throwable.class)` (`ApiAdvice.java:130-134`), which maps it to a bare 500 `generic.unknown` — the exact
"raw, unhandled 500" failure mode `skillars-deferred-66`'s original finding described for
`BookingCompletionService`, just in six different, previously-unaudited methods.

**One more site outside `BookingService` has the identical shape:** `RescheduleService.acceptReschedule`
(`:155`) does not call `transition()`/`transitionInternal()` at all — it mutates the booking directly
(`booking.setRequestedStartTime(...)`, `booking.setRequestedEndTime(...)`, `bookingRepository.save(booking)`
at `:257-259`) on a `booking` reference that was loaded **unlocked** at the top of the method
(`bookingService.getBookingOrThrow(bookingId)`, `:156`) — only the `BookingRescheduleRequest` row and the
`CoachProfile` row get pessimistic locks in this method, never the `Booking` row itself. Same unguarded
`OptimisticLockingFailureException` exposure, same 500 fallback.

**Confirmed already safe, deliberately excluded from this story:**
- `BookingService.cancelBookingAsParent` (`:641-685`) already takes `findByIdForUpdate` +
  `entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE)` on the `Booking` row itself
  (`skillars-deferred-64` AC2) *before* its own `transition(..., BookingEvent.CANCEL_PARENT, ...)` call at
  `:685` — the row is exclusively locked by this transaction for the whole method, so no concurrent writer
  can race it. Do not add a catch here; it would be dead code.
- `BookingReminderScheduler` and `BookingExpiryScheduler` also call `transition()` unguarded, but both wrap
  every per-booking iteration in a bare `catch (Exception e) { log.error(...); }` (system-triggered
  `@Scheduled` jobs, not interactive requests) — a race there is already swallowed safely with no user-facing
  effect. Confirmed by direct read, not touched by this story.
- `QuickCompleteTimeoutService` already explicitly catches `BookingStateTransitionException |
  OptimisticLockingFailureException` and logs-and-skips (`:55`) — also a scheduler, already correct.
- `BookingDuplicationService.duplicateNextWeek` and `BookingBatchService.createBatch` only ever `save()` a
  **new** `Booking` (`new Booking()`, unsaved, version 0) — an insert has no existing row to race, so no
  `OptimisticLockingFailureException` exposure exists there at all.
- `BookingBatchService.acceptOneBooking`'s call into `bookingService.acceptAndInitiatePayment(...)`
  (`:407`) already runs inside `acceptAll`'s per-booking `try { ... } catch (Exception e) { ... }` loop
  (`:268-285`), so a race there does **not** crash the whole batch request today — but the loop's
  `resolveFailureCode` helper (`:355-363`) has no branch for `OptimisticLockingFailureException` and falls
  through to the same `"generic.unknown"` code the corrupted-status test
  (`acceptAll_oneBookingHasCorruptedStatus_returnsGenericUnknownNotRawMessage`) already proves that fallback
  produces — a real, if lower-severity, instance of the same classification gap. Picked up as AC3 below.

## Acceptance Criteria

1. **Wrap all six unguarded `BookingService` write paths in the same
   `OptimisticLockingFailureException` → `BookingError.CONCURRENT_MODIFICATION` shape `BookingCompletionService`
   already uses.**
   `[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java]` — add `import
   org.springframework.dao.OptimisticLockingFailureException;` (not currently imported in this file). For
   each of the six methods below, wrap only the write call(s) named — do not move any pre-existing
   authorization/validation checks into the `try` block — and throw exactly:
   ```java
   } catch (OptimisticLockingFailureException e) {
       throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
   }
   ```
   (the identical message and 3-arg-constructor cause-chaining shape all 7 `BookingCompletionService` sites
   now use, per `skillars-deferred-66`/`-67`).

   - `acceptBooking` (`:359`): wrap `acceptAndInitiatePayment(bookingId, ctx);` alone. This single call
     covers both internal `transitionInternal` legs (`ACCEPT` then `INITIATE_PAYMENT`) since
     `acceptAndInitiatePayment` is reached by plain self-invocation from `acceptBooking`, inside the same
     transaction (see the method's own doc comment, `:381-389`).
   - `declineBooking` (`:420`): wrap `transition(bookingId, BookingEvent.DECLINE, ctx);` alone.
   - `cancelDueToPause` (`:598`): wrap `transition(bookingId, BookingEvent.CANCEL_DUE_TO_PAUSE, ...);` alone.
   - `recordNoShowPlayer` (`:746`): wrap `transition(bookingId, BookingEvent.NO_SHOW_PLAYER, ...);` alone.
   - `recordNoShowCoach` (`:778`): wrap `transition(bookingId, BookingEvent.NO_SHOW_COACH, ...);` alone.
   - `cancelBookingAsCoach` (`:722-725`): wrap **both** statements together — the `transition(...,
     BookingEvent.CANCEL_COACH, ...)` call *and* the following `booking.setCancelReason(resolvedReason);
     bookingRepository.save(booking);` pair, since both write the same versioned row and either can throw.
     Compute `resolvedReason` **before** the `try` block (it is pure local logic, not a DB call). The
     `catch` throws, so it never falls through — but on the non-exceptional path, execution continues past
     the whole `try`/`catch` to the method's existing `eventPublisher.publishEvent(new
     BookingCancelledByCoachEvent(...))` call (unchanged, not shown here), which also reads
     `resolvedReason` — that's why it must be declared outside the `try`, not because of anything inside
     the `catch`:
     ```java
     String resolvedReason = cancelReason != null ? cancelReason : "OTHER_UNEXCUSED";
     try {
         transition(bookingId, BookingEvent.CANCEL_COACH, new TransitionContext(ActorRole.COACH, coachUserId));
         booking.setCancelReason(resolvedReason);
         bookingRepository.save(booking);
     } catch (OptimisticLockingFailureException e) {
         throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
     }
     // ... eventPublisher.publishEvent(new BookingCancelledByCoachEvent(..., resolvedReason, ...)) follows unchanged
     ```

2. **Apply the identical fix to `RescheduleService.acceptReschedule`'s direct booking write.**
   `[src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:257-259]` — add
   `import org.springframework.dao.OptimisticLockingFailureException;` (not currently imported in this
   file; `BookingError` and `OperationNotAllowedException` are already imported). **Only the `save` call
   goes inside the `try` block** — the two setters that precede it are plain in-memory field assignments
   and cannot throw `OptimisticLockingFailureException`:
   ```java
   booking.setRequestedStartTime(req.getProposedStartTime());
   booking.setRequestedEndTime(req.getProposedEndTime());
   try {
       bookingRepository.save(booking);
   } catch (OptimisticLockingFailureException e) {
       throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
   }
   req.setStatus("ACCEPTED");
   rescheduleRepo.save(req);
   ```
   Do not touch `declineReschedule` or `requestReschedule` — neither writes to the `Booking` entity itself
   (confirmed by direct read: `declineReschedule` only mutates the `BookingRescheduleRequest` row;
   `requestReschedule` only creates one), so neither has this exposure.

3. **Classify `OptimisticLockingFailureException` in `BookingBatchService`'s per-booking failure-code
   resolver instead of letting it fall through to `"generic.unknown"`.**
   `[src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:355-363]` — add
   `import org.springframework.dao.OptimisticLockingFailureException;` (not currently imported; `BookingError`
   already is). Add a branch to `resolveFailureCode` **before** the final `return "generic.unknown";`:
   ```java
   if (e instanceof OptimisticLockingFailureException) {
       return BookingError.CONCURRENT_MODIFICATION.getErrorCode();
   }
   ```
   This does not change `acceptAll`'s control flow (the surrounding `catch (Exception e)` at `:268-285`
   already catches and converts every per-booking failure into a `BatchAcceptResult`, unchanged) — it only
   makes the reported `code` for this specific failure cause match what the single-booking accept path now
   returns, instead of the generic fallback.

4. **Tests — one concurrency-conflict test per newly-guarded call site, mirroring each file's existing
   patterns.**

   **`BookingServiceTest.java`**
   (`[src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java]`). Add `import
   org.springframework.dao.OptimisticLockingFailureException;` if not already present. For each of the six
   methods, mock the relevant collaborators exactly as the method's existing happy-path test does (e.g.
   `acceptBooking_requestedBooking_transitionsToPaymentPending`, `:531-549`, is the template for
   `acceptBooking`'s test — same `findById`/`findByUserId`/`findByIdForUpdate`/`findOverlappingBookings`
   stubs), but stub `bookingRepository.save(any(Booking.class))` to `thenThrow(new
   OptimisticLockingFailureException("test"))` instead of returning a booking. Assert
   `assertThatThrownBy(...).isInstanceOf(OperationNotAllowedException.class).hasCauseInstanceOf
   (OptimisticLockingFailureException.class).satisfies(e -> assertThat(((OperationNotAllowedException)
   e).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION))` — the same three-part assertion
   shape `BookingCompletionServiceTest`'s seven concurrency tests use.
   - `acceptBooking_concurrentModification_throwsRetryableException`
   - `declineBooking_concurrentModification_throwsRetryableException`
   - `cancelBookingAsCoach_concurrentModification_throwsRetryableException` — **note:** no test for
     `cancelBookingAsCoach` exists in this file today at all (confirmed: zero matches for the method name);
     stub the same lookups `acceptBooking`'s happy path uses for coach ownership
     (`coachProfileRepository.findByUserId`), a valid `cancelReason`, and `sessionPackPurchaseRepository`
     only if `booking.getSessionPackPurchaseId()` is non-null on your fixture (it can be left null to skip
     that branch).
   - `recordNoShowPlayer_concurrentModification_throwsRetryableException` — also has no existing test in
     this file; stub `coachProfileRepository.findByUserId` and `userRepository`/`resolveCoachEmail`'s
     underlying lookup as needed.
   - `recordNoShowCoach_concurrentModification_throwsRetryableException` — mirror
     `recordNoShowCoach_afterScheduledStartTime_transitionsAndPublishesEvent` (`:1018`)'s fixture (booking
     with a past `requestedStartTime` so the `NO_SHOW_TOO_EARLY` guard at `:763-767` doesn't fire first).
   - `cancelDueToPause_concurrentModification_throwsRetryableException` — this method's only existing
     coverage is indirect, through `PackSessionServicePauseTest` (a different test class, testing
     `PackSessionService` with `bookingService` mocked, so it never exercises this method's own internals).
     Test it directly here the same way the other five are tested, with a minimal booking fixture matching
     `cancelDueToPause`'s own ownership check (`parentId`/`coachId` equality, `:595-596`).

   **`RescheduleServiceTest.java`**
   (`[src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java]`). Add the
   same import. Mirror `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus` (`:275`)'s fixture and
   stubs, but stub `bookingRepository.save(any(Booking.class))` to throw
   `OptimisticLockingFailureException` and assert the same three-part shape as above.
   - `acceptReschedule_concurrentModification_throwsRetryableException`

   **`BookingBatchServiceTest.java`**
   (`[src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java]`). Mirror
   `acceptAll_oneBookingHasCorruptedStatus_returnsGenericUnknownNotRawMessage` (`:588-650`)'s two-booking
   (`ok` + a second, failing one) fixture shape exactly — `bookingService` is a Mockito mock in this test
   class, so stub `doThrow(new OptimisticLockingFailureException("test")).when(bookingService)
   .acceptAndInitiatePayment(eq(racedBookingId), any());` in place of that test's `ResponseStatusException`
   stub, and assert the resulting `BatchAcceptResult` for the raced booking carries `code` equal to
   `BookingError.CONCURRENT_MODIFICATION.getErrorCode()` (`"booking.concurrentModification"`), not
   `"generic.unknown"`.
   - `acceptAll_oneBookingRacesConcurrentModification_returnsConcurrentModificationCode`

5. **Ledger hygiene.** Update `deferred-work.md`'s `## Deferred from: code review of skillars-deferred-67
   (2026-08-25)` section: change the one open item (the `MISSING_RIGHTS`-reuse audit question) from its
   current open bullet to `[CLOSED by skillars-deferred-68: audited by direct source read — no code
   literally reuses MISSING_RIGHTS for this exception outside BookingCompletionService (already fixed by
   deferred-66/67); AvailabilityService cannot throw it at all (no @Version entities); but 6 BookingService
   methods + RescheduleService.acceptReschedule + BookingBatchService's resolveFailureCode fallback all let
   it propagate as an unclassified 500/generic.unknown instead — fixed by AC1-AC3]`, with a one-line
   mechanism note matching this story's actual fix (per this ledger's own established convention for closed
   items, see `skillars-deferred-67`'s Task 3 for the exact style to copy).

## Tasks / Subtasks

- [x] Task 1: `BookingService` concurrency-conflict handling parity (AC1)
  - [x] 1.1: Add `OptimisticLockingFailureException` import
  - [x] 1.2: Wrap `acceptBooking`'s `acceptAndInitiatePayment` call
  - [x] 1.3: Wrap `declineBooking`'s `transition` call
  - [x] 1.4: Wrap `cancelDueToPause`'s `transition` call
  - [x] 1.5: Wrap `recordNoShowPlayer`'s `transition` call
  - [x] 1.6: Wrap `recordNoShowCoach`'s `transition` call
  - [x] 1.7: Wrap `cancelBookingAsCoach`'s `transition` + `setCancelReason`/`save` pair, `resolvedReason`
        computed before the `try`
- [x] Task 2: `RescheduleService.acceptReschedule` parity (AC2)
  - [x] 2.1: Add `OptimisticLockingFailureException` import
  - [x] 2.2: Wrap the `bookingRepository.save(booking)` call
- [x] Task 3: `BookingBatchService` failure-code classification (AC3)
  - [x] 3.1: Add `OptimisticLockingFailureException` import
  - [x] 3.2: Add the `CONCURRENT_MODIFICATION` branch to `resolveFailureCode`
- [x] Task 4: Tests (AC4)
  - [x] 4.1: 6 new `BookingServiceTest` cases (one per Task 1 method)
  - [x] 4.2: 1 new `RescheduleServiceTest` case
  - [x] 4.3: 1 new `BookingBatchServiceTest` case
- [x] Task 5: Ledger hygiene (AC5)

## Dev Notes

**This is a copy-the-target-shape sweep, not a redesign.** The catch shape, message string, exception type,
and error code are all already decided (`skillars-deferred-66`/`-67`) — do not invent a variant, do not add
retry logic, do not change any method's HTTP status (still 403 via `OperationNotAllowedException` →
`ApiAdvice.operationDeniedHandler`, matching every other `BookingError` code's routing).

**Do not add a pessimistic lock anywhere in this story.** The fix mirrors `BookingCompletionService`'s own
resolution exactly: catch-and-reclassify, not lock-and-prevent. Locking these six methods' `Booking` reads
would be a materially bigger, higher-risk change (new lock-ordering surface against the three
already-documented coach-lock acquirers — see the still-open `skillars-deferred-58` review item on this in
`deferred-work.md` — and against `cancelBookingAsParent`'s existing booking-row lock) that nobody has
decided to make. Out of scope.

**`cancelBookingAsCoach`'s two-statement wrap is the one place this differs from a bare single-call wrap** —
read AC1's code block carefully before implementing; do not wrap the whole method body (the earlier
ownership/validation checks and `resolveSessionPrice`/`resolveEmail`/pack-expiry lookups must stay outside
the `try`, unchanged).

**Three of the six `BookingService` methods (`cancelBookingAsCoach`, `recordNoShowPlayer`,
`cancelDueToPause`) have zero existing unit test coverage in `BookingServiceTest.java` today** — the new
concurrency test you add for each is that method's first test in this file. Do not feel obligated to add
happy-path coverage for them too; that's a pre-existing gap, out of this story's bounded scope (matches
`skillars-deferred-66`'s own precedent of leaving `BookingCompletionService`'s other-behavior test gaps
alone while fixing the concurrency path specifically).

**No frontend changes.** `booking.concurrentModification`'s i18n entries already exist in all four backend
`messages*.properties` files (added by `skillars-deferred-67` AC1) — nothing new to add there. Whether any
frontend page consuming these six endpoints reads `errorMsg.message` from the response was not re-verified
for this story (unlike `skillars-deferred-67`, which explicitly checked and found none did for
`BookingCompletionService`'s endpoints) — if the dev agent finds a frontend consumer of any of these six
endpoints that reads and displays `errorMsg.message` today, that's a helpful confirmation this fix has live
UI value, not something requiring extra work; do not go looking for one if it's not immediately obvious from
the files already being touched.

**Do not touch `cancelBookingAsParent`.** It is already correct (pessimistic lock, no catch needed) — see
"Why this story exists" above for the direct-read confirmation. Adding a catch there would be dead,
untestable code.

**Do not touch `AvailabilityService`.** It cannot throw `OptimisticLockingFailureException` — neither
`CoachAvailabilityWindow` nor `CoachAvailabilityBlock` carries `@Version`. Confirmed by direct grep, not an
assumption.

### Project Structure Notes

Backend only: `platform.booking.service.BookingService`, `platform.booking.service.RescheduleService`,
`platform.booking.service.BookingBatchService`, plus their three respective test classes, plus
`deferred-work.md` (ledger hygiene). No new migrations, no new classes, no i18n changes (the message key
already exists), no frontend changes.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md`, `## Deferred from: code review of
  skillars-deferred-67 (2026-08-25)` — the audit question this story answers and closes.
- `skillars-deferred-66`/`skillars-deferred-67` — established the `OptimisticLockingFailureException` →
  `BookingError.CONCURRENT_MODIFICATION` catch shape and the `OperationNotAllowedException(String,
  Throwable, ErrorCode)` 3-arg constructor this story reuses unchanged, in `BookingCompletionService.java`.
- `platform.booking.contract.BookingError` class doc comment — already documents `CONCURRENT_MODIFICATION`'s
  rationale; no changes needed there, just reuse of the existing constant.
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:130-134` — the catch-all
  `Throwable` handler that produces the `generic.unknown` 500 every one of this story's fixed call sites
  currently falls through to.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

### Completion Notes List

- AC1: All six `BookingService` write paths (`acceptBooking`'s `acceptAndInitiatePayment` call,
  `declineBooking`, `cancelDueToPause`, `recordNoShowPlayer`, `recordNoShowCoach`, and
  `cancelBookingAsCoach`'s `transition`+`setCancelReason`/`save` pair) now catch
  `OptimisticLockingFailureException` and rethrow `OperationNotAllowedException` carrying
  `BookingError.CONCURRENT_MODIFICATION`, cause-chained, identical message/shape to
  `BookingCompletionService`'s established pattern. `cancelBookingAsCoach`'s `resolvedReason` is computed
  before the `try` block, unchanged from the spec's required diff — the trailing
  `eventPublisher.publishEvent(...)` call after the `try`/`catch` still reads it.
- AC2: `RescheduleService.acceptReschedule`'s `bookingRepository.save(booking)` call now wraps in the
  identical catch shape; the two preceding setters stay outside the `try` since they cannot throw this
  exception.
- AC3: `BookingBatchService.resolveFailureCode` gained an `OptimisticLockingFailureException` branch
  returning `BookingError.CONCURRENT_MODIFICATION.getErrorCode()`, placed before the final
  `"generic.unknown"` fallback. `acceptAll`'s surrounding per-booking `try`/`catch` loop is unchanged.
- AC4: 8 new concurrency-conflict tests added — 6 in `BookingServiceTest` (one per Task 1 method;
  `cancelBookingAsCoach`, `recordNoShowPlayer`, and `cancelDueToPause` had zero prior test coverage in
  this file, so each new test is that method's first), 1 in `RescheduleServiceTest`
  (`acceptReschedule_concurrentModification_throwsRetryableException`), 1 in `BookingBatchServiceTest`
  (`acceptAll_oneBookingRacesConcurrentModification_returnsConcurrentModificationCode`, mirroring the
  existing corrupted-status two-booking fixture shape). All three test classes green:
  `mvn -o test -Dtest=BookingServiceTest,RescheduleServiceTest,BookingBatchServiceTest` — 45/19/27,
  91/91 total, 0 failures. `mvn verify` not run per `docs/validation-strategy.md`.
- AC5: `deferred-work.md`'s `## Deferred from: code review of skillars-deferred-67 (2026-08-25)` section's
  sole open item flipped from `[PICKED UP by skillars-deferred-68: ...]` to
  `[CLOSED by skillars-deferred-68: ...]` with a mechanism note matching this story's actual fix.
- No frontend changes — the `booking.concurrentModification` i18n key already exists in all 4 locale
  files (added by `skillars-deferred-67` AC1); this story added no new call sites needing translation.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

- 2026-08-25: Story created via story-creation process. Re-mined `deferred-work.md`'s newest, most specific
  Booking-module item — the audit question `skillars-deferred-67`'s own code review deferred (does any
  other booking-module service reuse `MISSING_RIGHTS` for `OptimisticLockingFailureException`?) — by direct
  source read rather than trusting the ledger. Literal answer: no. Real answer: six `BookingService` methods
  plus `RescheduleService.acceptReschedule` reach `Booking`'s only unlocked, `@Version`-checked write path
  with no catch at all (falls through to `ApiAdvice`'s generic 500), and `BookingBatchService`'s
  `resolveFailureCode` has no branch for it either. AC1-AC3 extend the already-established, already-decided
  `skillars-deferred-66`/`-67` fix shape to all of them; AC4 adds one concurrency test per newly-guarded
  site; AC5 closes the ledger item precisely. Confirmed already-safe and deliberately excluded:
  `cancelBookingAsParent` (already pessimistic-locked), `AvailabilityService` (no `@Version` entities to
  race), both booking schedulers and `QuickCompleteTimeoutService` (already safely catch-and-log/skip, being
  system-triggered rather than interactive). No decision from the project owner was needed for this story —
  the fix shape, error code, and message were already decided by `skillars-deferred-66`/`-67`; this is a
  mechanical extension of that decision to the sites the prior story's own review flagged for a follow-up
  audit.
- 2026-08-25: Story-review complete (`story-review.md`), status remains ready-for-dev. READY FOR DEV
  verdict, no blockers — "well-researched, appropriately scoped, and technically sound," all six
  `BookingService` gaps and the `RescheduleService`/`BookingBatchService` extensions confirmed real, no
  false positives, no missed flows or edge cases. Two of the review's three clarifications pointed at
  genuine wording ambiguity in the AC text (not incorrect fixes, just easy to misread) and were tightened:
  AC2 now says explicitly that only the `save` call goes inside the `try` (the two setters cannot throw
  this exception); AC1's `cancelBookingAsCoach` block now shows the unchanged `eventPublisher.publishEvent`
  call that follows the `try`/`catch` on the non-exceptional path, so `resolvedReason`'s pre-`try`
  declaration reads as serving that later call, not anything inside the `catch` (which throws). The third
  clarification (verify `acceptAndInitiatePayment`'s transaction propagation) needed no edit — the story
  already cites the source comment (`:381-389`) the claim is based on, and the review itself confirms the
  fix (wrapping the single call) is correct regardless. Full detail in `story-review.md`.
- 2026-08-25: Dev-story implementation complete, status review. AC1-AC3 shipped verbatim against the
  spec's required diffs: all six `BookingService` write paths, `RescheduleService.acceptReschedule`'s
  `save` call, and `BookingBatchService.resolveFailureCode` now convert
  `OptimisticLockingFailureException` into `OperationNotAllowedException`/`BookingError.CONCURRENT_MODIFICATION`,
  identical shape to `BookingCompletionService`'s established pattern. AC4 added 8 new concurrency-conflict
  tests (6 `BookingServiceTest`, 1 `RescheduleServiceTest`, 1 `BookingBatchServiceTest`) — 3 of the 6
  `BookingService` methods had zero prior coverage in that file, each now has its first test. Targeted
  suite green: 91/91 across the three touched test classes, 0 failures. AC5 closed the deferred-67 ledger
  item with a mechanism note. `mvn verify` not run per `docs/validation-strategy.md`.
