# Story skillars-3.11: Coach Slot Double-Booking Prevention

Status: done

## Story

As a coach,
I want the platform to stop two families from being able to hold the same time slot,
so that I never have to discover a scheduling collision only after accepting a request.

## Acceptance Criteria

1. **AC 1: Overlapping request rejected at creation** — Given a coach already has a booking in `REQUESTED`, `ACCEPTED`, `PAYMENT_PENDING`, `CONFIRMED`, or `UPCOMING` status whose `[requestedStartTime, requestedEndTime)` overlaps the incoming request's range, when a parent submits `createBookingRequest` for that same coach, then no `bookings` row is created and the call fails with `OperationNotAllowedException` / `ErrorDto` code `booking.slotUnavailable` — the existing availability-window check (Story 3.1, `isSlotWithinAvailabilityWindow`) still runs first and is unchanged.

2. **AC 2: Concurrent overlapping requests are serialized** — Given two parents submit overlapping booking requests for the same coach at nearly the same instant (two concurrent transactions), when both `createBookingRequest` calls execute, then the overlap check and the resulting insert are serialized per coach via a pessimistic lock acquired on that coach's row before the overlap query runs, so exactly one request succeeds and the other fails with `booking.slotUnavailable` — no interleaving can let both inserts land.

3. **AC 3: Accept-time re-validation closes the acceptance race** — Given a booking is `REQUESTED`, when the coach calls `acceptBooking` but another booking for the same coach with an overlapping time range has already reached `ACCEPTED`, `PAYMENT_PENDING`, `CONFIRMED`, or `UPCOMING` status (e.g. the coach already accepted a different overlapping request), then the accept is rejected with `booking.slotUnavailable`, the booking stays `REQUESTED` (the coach can still decline it), and no `ACCEPT` event reaches `BookingStateMachine`. This check uses the same per-coach pessimistic lock as AC 2.

4. **AC 4: Coach availability-edit conflict warning is wired to real data** — Given `AvailabilityService.updateWindow()` evaluates whether an edited recurring window overlaps a real booking (Story 3.1 AC: "a warning is shown if the change would overlap with an existing confirmed booking"), when `hasBookingConflict(coachId, window)` runs, then it queries actual `CONFIRMED`/`UPCOMING` bookings for that coach and returns `true` if any such booking's local start time (in the coach's `canonicalTimezone`) falls on the window's `dayOfWeek` within `[startTime, endTime)` — replacing the current hardcoded `return false` stub — and the `TODO(3.3): wire to BookingRepository once available` comment is removed.

5. **AC 5: Overlap lookups are indexed** — Given the overlap query in AC 1–3 runs on every booking creation and every accept call, when the `bookings` table grows to production scale, then a composite index on `(coach_id, status, requested_start_time, requested_end_time)` supports the lookup without a sequential scan.

## Tasks / Subtasks

### Backend — Database Migration

- [x] Task 1: Flyway migration `V86__booking_overlap_index.sql` (AC: 5)
  - [x] File: `src/main/resources/db/migration/V86__booking_overlap_index.sql`
  - [x] ```sql
    CREATE INDEX idx_bkg_coach_status_time
        ON booking.bookings (coach_id, status, requested_start_time, requested_end_time);
    ```
  - [x] Do not touch `chk_bkg_status` — no new status is introduced by this story.

### Backend — Error Code

- [x] Task 2: Add `SLOT_UNAVAILABLE` to `BookingError.java` (AC: 1, 2, 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
  - [x] Current file only has `COACH_UNAVAILABLE`. Add a second enum constant and switch branch:
    ```java
    public enum BookingError implements ErrorCode {
        COACH_UNAVAILABLE,
        SLOT_UNAVAILABLE;

        @Override
        public String getErrorCode() {
            return switch (this) {
                case COACH_UNAVAILABLE -> "booking.coachUnavailable";
                case SLOT_UNAVAILABLE  -> "booking.slotUnavailable";
            };
        }
    }
    ```

### Backend — Repository Query Additions

- [x] Task 3: Add overlap query to `BookingRepository.java` (AC: 1, 2, 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
  - [x] Half-open interval overlap test — mirrors the existing `findByCoachIdAndStatusInAndTimeBetween` query style already in this file:
    ```java
    @Query("""
        SELECT b FROM Booking b
        WHERE b.coachId = :coachId
          AND b.status IN :statuses
          AND b.requestedStartTime < :endTime
          AND b.requestedEndTime > :startTime
        """)
    List<Booking> findOverlappingBookings(
        @Param("coachId") UUID coachId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime,
        @Param("statuses") List<String> statuses);
    ```
  - [x] Reused by both `createBookingRequest` (Task 5) and `acceptBooking` (Task 6) with different `statuses` lists — do not create two separate methods.

- [x] Task 4: Add locking lookup to `CoachProfileRepository.java` (AC: 2, 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
  - [x] Same pattern already used in `SessionPackPurchasedRepository.findByIdForUpdate` / `SessionPackPurchaseRepository.findByIdForUpdate` — the codebase's documented convention for "SELECT FOR UPDATE exclusively for optimistic-to-pessimistic boundary cases":
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CoachProfile c WHERE c.id = :id")
    Optional<CoachProfile> findByIdForUpdate(@Param("id") UUID id);
    ```
  - [x] New imports needed in that file: `org.springframework.data.jpa.repository.Lock`, `jakarta.persistence.LockModeType`.
  - [x] **CRITICAL**: this locks the `CoachProfile` row, not a `bookings` row — there is no single row representing "the coach's calendar," so the coach's own profile row is the natural serialization point. `BookingService` already depends on `CoachProfileRepository` (existing cross-module dependency, same DB — see project-context.md monolith conventions), so no new module dependency is introduced.

### Backend — BookingService Wiring

- [x] Task 5: Wire overlap check + lock into `createBookingRequest` (AC: 1, 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
  - [x] Root cause: the method currently only validates the requested time against the coach's recurring `coach_availability_windows` template via `isSlotWithinAvailabilityWindow` (around line 180) — it never checks for existing overlapping `Booking` rows.
  - [x] Add a class constant for the statuses that count as "holding" a slot:
    ```java
    private static final List<String> ACTIVE_SLOT_STATUSES =
        List.of("REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING");
    ```
  - [x] Immediately **after** the existing `isSlotWithinAvailabilityWindow` check and **before** the session-pack-purchase validation block, insert:
    ```java
    coachAvailabilityWindowRepository... // unchanged — leave as-is

    // AC 1/2: acquire a per-coach lock before the authoritative overlap check so two
    // concurrent requests for the same coach are serialized, not interleaved.
    coachProfileRepository.findByIdForUpdate(req.coachId());
    List<Booking> overlapping = bookingRepository.findOverlappingBookings(
        req.coachId(), req.requestedStartTime(), req.requestedEndTime(), ACTIVE_SLOT_STATUSES);
    if (!overlapping.isEmpty()) {
        throw new OperationNotAllowedException(
            "Requested slot overlaps an existing booking for this coach", BookingError.SLOT_UNAVAILABLE);
    }
    ```
  - [x] **CRITICAL — do not move the lock acquisition earlier.** The existing `coach` lookup near the top of the method (`coachProfileRepository.findById(req.coachId())`) is used for the coach-status check and stays a plain read. Do not replace it with the locking variant — `paymentGateway.isCoachPaymentReady(coach.getId())` is an external network call that runs between that lookup and this new block; holding a DB row lock across an external HTTP call would serialize unrelated requests on network latency. Acquire the lock only right before the overlap check, as shown above.
  - [x] The pre-existing session-pack / legacy-pack-lock code below this block is unchanged.

- [x] Task 6: Wire overlap re-check + lock into `acceptBooking` (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
  - [x] Immediately **after** the existing ownership check (`if (!Objects.equals(booking.getCoachId(), coach.getId()))`) and **before** the `transitionInternal(bookingId, BookingEvent.ACCEPT, ...)` call, insert:
    ```java
    // AC 3: re-check for a slot conflict that may have appeared since this booking was
    // REQUESTED (e.g. the coach already accepted a different overlapping request).
    coachProfileRepository.findByIdForUpdate(coach.getId());
    List<Booking> overlapping = bookingRepository.findOverlappingBookings(
        booking.getCoachId(), booking.getRequestedStartTime(), booking.getRequestedEndTime(),
        List.of("ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING"));
    if (!overlapping.isEmpty()) {
        throw new OperationNotAllowedException(
            "This slot is no longer available — another booking was accepted for the same time", BookingError.SLOT_UNAVAILABLE);
    }
    ```
  - [x] **Do not** include `REQUESTED` in this status list — the booking being accepted is itself still `REQUESTED` at this point and must not match its own query; other merely-`REQUESTED` competing bookings are not yet a real conflict (the coach simply hasn't acted on them, and will get `booking.slotUnavailable` if they later try to accept one after this one is confirmed).
  - [x] `declineBooking` is unaffected — declining never creates a conflict, so no change needed there.

### Backend — AvailabilityService Wiring

- [x] Task 7: Replace the `hasBookingConflict` stub (AC: 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
  - [x] Add `BookingRepository` as a new constructor-injected dependency (Lombok `@RequiredArgsConstructor` already generates the constructor — just add the field).
  - [x] Replace:
    ```java
    // TODO(3.3): wire to BookingRepository once available
    private boolean hasBookingConflict(UUID coachId, CoachAvailabilityWindow window) {
        return false;
    }
    ```
    with a real implementation that reuses the existing bounded-lookahead query (`findByCoachIdAndStatusInAndTimeBetween`, already used by `getAvailabilityCalendar` in this same class) rather than adding a new unbounded query:
    ```java
    private boolean hasBookingConflict(UUID coachId, CoachAvailabilityWindow window) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(window.getCanonicalTimezone());
        } catch (DateTimeException e) {
            return false;
        }
        Instant now = Instant.now();
        Instant horizon = now.plus(90, ChronoUnit.DAYS);
        List<Booking> futureBookings = bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            coachId, List.of("CONFIRMED", "UPCOMING"), now, horizon);

        return futureBookings.stream().anyMatch(b -> {
            ZonedDateTime startZdt = b.getRequestedStartTime().atZone(zoneId);
            LocalTime localTime = startZdt.toLocalTime();
            return window.getDayOfWeek() == (short) startZdt.getDayOfWeek().getValue()
                && !localTime.isBefore(window.getStartTime())
                && localTime.isBefore(window.getEndTime());
        });
    }
    ```
  - [x] This is a 90-day lookahead by design — Story 3.1 states editing/deleting a window is "prospective only," so there is no need to scan indefinitely far into the future. Follow the same "local day-of-week + local time-of-day" comparison approach already used in `BookingService.isSlotWithinAvailabilityWindow` — do not compare raw `Instant`s against the window, since the window is a recurring template, not a concrete datetime.
  - [x] New imports needed: `com.softropic.skillars.platform.booking.repo.Booking`, `com.softropic.skillars.platform.booking.repo.BookingRepository`, `java.time.LocalTime`, `java.time.ZonedDateTime`, `java.time.temporal.ChronoUnit`.

### Backend — Tests

- [x] Task 8: Extend `BookingServiceTest.java` (AC: 1, 2, 3)
  - [x] File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
  - [x] Add `@Mock` usage of the existing `coachProfileRepository` and `bookingRepository` mocks — no new `@Mock` fields needed, both are already injected into `bookingService` in `setUp()`.
  - [x] `createBookingRequest_overlappingActiveBooking_throwsOperationNotAllowedException` — stub `bookingRepository.findOverlappingBookings(...)` to return a non-empty `List.of(makeBooking(...))`, assert `assertThatThrownBy(...).isInstanceOf(OperationNotAllowedException.class)`, and `verify(bookingRepository, never()).save(any(Booking.class))`.
  - [x] `createBookingRequest_noOverlap_createsRequestedBooking` — stub `findOverlappingBookings` to return `List.of()`, assert the existing happy-path assertions from `createBookingRequest_hasCredits_createsRequestedBooking` still hold (this is a regression check that the new call doesn't break the existing flow — every existing `createBookingRequest_*` test needs `coachProfileRepository.findByIdForUpdate(...)` and `bookingRepository.findOverlappingBookings(...)` stubbed with `lenient()` or non-empty-safe defaults, or the existing tests will start throwing `UnnecessaryStubbingException`/NPE once Task 5 lands — audit every existing `createBookingRequest_*` test in this file).
  - [x] `acceptBooking_overlappingConfirmedBooking_throwsOperationNotAllowedException` — stub `bookingRepository.findOverlappingBookings(...)` (called with `ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING`) to return a non-empty list, assert the exception, and `verify(bookingRepository, never()).save(any(Booking.class))` — reuse `acceptBooking_alreadyDeclined_throwsBookingStateTransitionException` as the structural template.
  - [x] `acceptBooking_noOverlap_transitionsToPaymentPending` — audit the existing `acceptBooking_requestedBooking_transitionsToPaymentPending` test; it will need `coachProfileRepository.findByIdForUpdate(...)` and `bookingRepository.findOverlappingBookings(...)` (empty list) stubbed once Task 6 lands.

- [x] Task 9: New `BookingServiceConcurrencyIT.java` (AC: 2, 3)
  - [x] File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`
  - [x] `@SpringBootTest` + `@Testcontainers`, following this project's existing concurrency-test convention referenced in Story 4.2's `QuotaServiceConcurrencyTest` (two threads competing for the same resource via Testcontainers).
  - [x] `concurrentCreateBookingRequest_overlappingSlot_onlyOneSucceeds` (AC 2): Seed one `CoachProfile` with an `ACTIVE` status, a matching `CoachAvailabilityWindow`, and enough session-pack credit for two different players (so the credit check is not the thing that blocks the second request — only the slot-overlap check should be under test). Fire two `createBookingRequest` calls concurrently (e.g. `ExecutorService` with 2 threads + `CountDownLatch` to align start, or `Awaitility`) for the same coach and the same overlapping time range from two different parent/player pairs. Assert exactly one `Booking` row exists in the DB for that time range afterward, and exactly one of the two calls threw `OperationNotAllowedException` with `BookingError.SLOT_UNAVAILABLE`.
  - [x] `concurrentAcceptBooking_overlappingRequestedBookings_onlyOneAccepts` (AC 3): **Seed two `Booking` rows directly via `bookingRepository.save(...)` in `REQUESTED` status with overlapping time ranges for the same coach** — do not create them through `createBookingRequest`, since AC 1/2 already make it impossible for two overlapping `REQUESTED` bookings to coexist once this story ships; the direct-seed approach is what lets this test exercise the accept-time re-validation in isolation, and also stands in for the legitimate real-world case of two overlapping `REQUESTED` bookings that predate this story's deployment. Fire two `acceptBooking` calls concurrently, one per booking, for the same coach. Assert exactly one booking reaches `PAYMENT_PENDING` and the other stays `REQUESTED`, and that the losing call threw `OperationNotAllowedException` with `BookingError.SLOT_UNAVAILABLE`.

- [x] Task 10: Extend `AvailabilityServiceTest.java` (AC: 4)
  - [x] File: `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
  - [x] The existing file only tests `computeAvailableSlots` (a public method) via `@InjectMocks` — `hasBookingConflict` is currently `private`. Since `updateWindow()` is the only caller and is already `@Transactional`/public, drive these new tests through `updateWindow()` (mock `windowRepository.findByIdAndCoachId(...)` to return a window, mock `coachProfileRepository.requireProfile`-equivalent lookup) and assert on the returned `AvailabilityWindowResponse.hasConflict` field, mirroring how the class is already tested end-to-end via its public API rather than reflection.
  - [x] `updateWindow_overlappingConfirmedBooking_returnsHasConflictTrue` — mock `bookingRepository.findByCoachIdAndStatusInAndTimeBetween(...)` to return a `Booking` whose `requestedStartTime`, converted to the window's timezone, falls on the window's `dayOfWeek` within `[startTime, endTime)`.
  - [x] `updateWindow_noOverlap_returnsHasConflictFalse` — same setup but with a booking outside the window's day/time, or an empty list.
  - [x] Add `@Mock private BookingRepository bookingRepository;` to the test class and pass it into the `AvailabilityService` constructor (via `@InjectMocks`, Mockito will pick it up automatically once the field is added to `AvailabilityService`).

## Dev Notes

- **Root cause (confirmed by reading the running code, not assumed):** `CoachAvailabilityWindow` is a recurring weekly *template* `(coachId, dayOfWeek, startTime, endTime, timezone)` — it has no capacity or status and is never written to when a booking is made. `BookingService.createBookingRequest()`'s `isSlotWithinAvailabilityWindow` only checks that the requested time falls inside the coach's declared weekly hours; it never checks for existing overlapping `Booking` rows. `acceptBooking()` has no conflict check either. `AvailabilityService.hasBookingConflict()` was stubbed to always `return false` with `// TODO(3.3): wire to BookingRepository once available` — the wiring was planned but never done. This story closes all three gaps.
- **Locking strategy:** per architecture.md's documented convention ("`SELECT FOR UPDATE` is used exclusively for optimistic-to-pessimistic boundary cases... all other entities use `@Version` for optimistic locking"), this story locks the `CoachProfile` row (via a new `findByIdForUpdate`) as the serialization point for the "is this coach's slot free" check — there is no dedicated calendar-slot row to lock. `Booking` keeps its existing `@Version` optimistic locking for its own normal updates; the pessimistic lock here is scoped narrowly to the overlap-check-then-insert / overlap-check-then-accept critical sections.
- **Known trade-off — coach-level lock, not slot-level:** because the lock is on the `CoachProfile` row rather than a per-slot row, *all* concurrent `createBookingRequest`/`acceptBooking` calls for the same coach serialize through this single lock, even when their requested time ranges don't overlap at all (e.g. one request for Tuesday, one for Friday). This is an accepted MVP trade-off, not a bug: booking volume per coach is low enough that lock hold time (a few milliseconds per overlap query) won't produce meaningful queuing in practice. Revisit only if a coach-level hot-row contention problem is actually observed in production (e.g. via slow-query or lock-wait monitoring) — do not pre-optimize this in this story.
- **Explicitly out of scope:** auto-declining other `REQUESTED` bookings that lose the race when a coach accepts a competing overlapping request. If a coach tries to accept a second overlapping `REQUESTED` booking after confirming the first, they get `booking.slotUnavailable` and must decline it manually — acceptable v1 UX, no cascade-decline logic needed. Do not build this — it's a distinct feature (would need its own notification/event) and would gold-plate this story.
- **Explicitly out of scope — slot capacity / group bookings:** this story enforces strict 1:1 slot exclusivity (any overlap at all is rejected, per AC 1–3). A coach accepting multiple bookings against the same slot up to a capacity (e.g. group sessions) is a materially different model — see PRD FR-BKG-016 (`_bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/prd.md`), explicitly deferred to post-MVP and tracked as its own future epic, not a variant of this story.
- **No frontend changes required.** `src/frontend/src/utils/errorHandler.js` (`parseApiError`) already surfaces `error.response.data.errorMsg.message` generically via the existing toast/error-display mechanism used for every other booking error (e.g. `booking.coachUnavailable`) — there is no per-error-code i18n mapping in the frontend for booking errors today (confirmed: no `booking.coachUnavailable` or `booking.creditsExhausted` string exists anywhere in `src/frontend/`). The `OperationNotAllowedException` message string set in Tasks 5/6 is what the parent/coach will see.
- **Testing standards (project-context.md):** unit tests use Mockito + AssertJ (`assertThat`, `assertThatThrownBy`) — see `BookingServiceTest.java` for the exact mocking conventions already in use (constructor args must stay positionally correct if the `BookingService` constructor signature changes — it currently takes 14 args in a fixed order; this story does **not** add a new constructor dependency to `BookingService`, only new calls on already-injected `bookingRepository`/`coachProfileRepository`). Integration tests use `@SpringBootTest` + `@Testcontainers`, no DB mocking — see `QuotaServiceConcurrencyTest` (Epic 6) for the established two-thread concurrency-test pattern to copy for Task 9.

### Project Structure Notes

- All changes stay within existing files in `platform.booking` (`BookingService`, `BookingRepository`, `AvailabilityService`, `BookingError`) plus one method added to `platform.marketplace`'s `CoachProfileRepository` — no new module, no new package. This mirrors the existing precedent of `BookingService` already depending on `marketplace.CoachProfileRepository` and `marketplace.CoachAvailabilityWindowRepository` directly (same DB, monolith stage — per project-context.md's DDD module rules, cross-module reads via direct repository injection are the established pattern here, not a violation).
- One new Flyway migration file only: `V86__booking_overlap_index.sql` (current head is `V85__phone_otp_required_toggle.sql`).

### References

- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java] — `createBookingRequest` (line ~144), `acceptBooking` (line ~232), `isSlotWithinAvailabilityWindow` (line ~647, pattern to mirror in Task 7).
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java] — `hasBookingConflict` stub (line ~204), `getAvailabilityCalendar` (existing use of `findByCoachIdAndStatusInAndTimeBetween` to reuse in Task 7).
- [Source: src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java] — existing query conventions to mirror for Task 3.
- [Source: src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java#L17-L31] — `@Lock(LockModeType.PESSIMISTIC_WRITE)` pattern to mirror for Task 4.
- [Source: _bmad-output/planning-artifacts/architecture.md#Architecture Validation Results] — `SELECT FOR UPDATE` locking convention.
- [Source: _bmad-output/planning-artifacts/skillars-epics.md#Story 3.11: Coach Slot Double-Booking Prevention] — this story's AC/dev-notes source of truth in the epic doc.
- [Source: _bmad-output/planning-artifacts/skillars-epics.md#Story 3.1: Coach Availability Management] — "a warning is shown if the change would overlap with an existing confirmed booking" (AC being wired in Task 7); "editing availability is prospective only" (bounds the 90-day lookahead).
- [Source: _bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/prd.md#5.2 Booking & Scheduling, FR-BKG-016] — slot capacity / group bookings, explicitly Post-MVP; not in scope for this story.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -q -o compile` — clean after Tasks 1–7 (main source changes).
- `mvn -q -o test -Dtest=BookingServiceTest` — 14/14 pass after Task 8.
- `mvn -q -o test -Dtest=AvailabilityServiceTest` — 6/6 pass after Task 10.
- `mvn -q -o test -Dtest=BookingServiceConcurrencyIT` — 2/2 pass after Task 9 (Testcontainers Postgres). First run of `concurrentAcceptBooking_overlappingRequestedBookings_onlyOneAccepts` failed because the winning booking had already been advanced from `PAYMENT_PENDING` to `CONFIRMED` by the synchronous `AFTER_COMMIT` `PaymentLifecycleService` listener (`StubPaymentGateway`) by the time the test read it back — fixed by asserting the winner is `PAYMENT_PENDING` **or** `CONFIRMED` instead of only `PAYMENT_PENDING`.
- `mvn -q -o test -Dtest="com.softropic.skillars.platform.booking.**,com.softropic.skillars.platform.marketplace.**"` — 1379 tests, 0 failures, 0 errors, 5 skipped (regression check for both touched modules).
- `mvn -q -o test` (project-wide, all modules) — exit 0, 0 failures/0 errors across all `*Test.java` unit tests. Note: this project wires `*IT.java` integration tests to the `maven-failsafe-plugin` (runs under `mvn verify`), not surefire/`mvn test` — the booking/marketplace ITs (including the new `BookingServiceConcurrencyIT`) were already verified separately in the targeted run above, which explicitly includes IT classes.

### Completion Notes List

- Implemented all 5 ACs: overlap rejection at creation (AC1), per-coach pessimistic lock via `CoachProfileRepository.findByIdForUpdate` serializing concurrent creates (AC2), accept-time re-validation against the same lock (AC3), `AvailabilityService.hasBookingConflict` wired to real `CONFIRMED`/`UPCOMING` bookings with a 90-day lookahead (AC4), and a composite index `idx_bkg_coach_status_time` on `booking.bookings(coach_id, status, requested_start_time, requested_end_time)` (AC5).
- `BookingRepository.findOverlappingBookings` is a single half-open-interval query, reused by both `createBookingRequest` (statuses `REQUESTED/ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING`) and `acceptBooking` (statuses `ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING`, deliberately excluding `REQUESTED` per Dev Notes).
- Lock acquisition in `createBookingRequest` is placed immediately before the overlap check (after the availability-window check), not earlier — `paymentGateway.isCoachPaymentReady` (an external HTTP call) runs before this point and must not be covered by the row lock.
- `declineBooking` was left untouched — declining never creates a conflict, as specified in Dev Notes.
- Test coverage: extended `BookingServiceTest` (new overlap tests for create/accept, existing happy-path tests updated with `findByIdForUpdate`/`findOverlappingBookings` stubs), extended `AvailabilityServiceTest` (new `updateWindow` conflict-true/false tests exercising `hasBookingConflict` through the public API), and added `BookingServiceConcurrencyIT` (Testcontainers, two-thread `ExecutorService` + `CountDownLatch` pattern copied from `QuotaServiceConcurrencyTest`) covering AC2 and AC3 end-to-end against a real Postgres instance.
- No frontend changes — confirmed no per-error-code i18n mapping exists for booking errors; the existing generic error-toast path already surfaces the `OperationNotAllowedException` message.

### File List

- `src/main/resources/db/migration/V86__booking_overlap_index.sql` (new)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java` (modified)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` (new)
- `src/main/resources/db/migration/V87__booking_overlap_exclusion_constraint.sql` (new — review D1)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (modified — review D1, P8)
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java` (modified — fixed regression surfaced by review P3)
- `src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java` (new — review P10)
- `src/test/java/com/softropic/skillars/platform/payment/service/BatchPaymentIT.java` (modified — fixed a second regression surfaced by review D1's exclusion constraint: `insertBooking` reused the same relative `now()`-based time literal for multiple bookings against the same coach, which the app never would have hit but the raw-SQL test fixture did; switched to Java-computed, deterministically non-overlapping timestamps)

## Change Log

- 2026-07-31: Implemented Story 3.11 (Tasks 1–10) — coach slot double-booking prevention via overlap checks, per-coach pessimistic locking, real `AvailabilityService.hasBookingConflict` wiring, and a supporting composite index. Status set to `review`.
- 2026-08-01: Code review pass — resolved all 4 decision-needed findings (D1: DB-level exclusion constraint backstop `excl_bkg_coach_slot_overlap`; D2: consistent exception metadata; D3: `IN_PROGRESS`/`PAUSED` added to active-slot statuses; D4: deferred `CREATE INDEX CONCURRENTLY`) and all 10 patch findings (self-match guard, timezone-swallow logging, lock-timeout handling, test coverage additions including DST-boundary and repository-boundary tests, etc.). Fixed two regressions surfaced by the new D1 exclusion constraint / P3 `.orElseThrow()` patch: `ExpiredPackBookingValidationTest` (missing `findByIdForUpdate` stub) and `BatchPaymentIT` (raw-SQL fixture inserted overlapping bookings for the same coach). Full project test suite verified clean across two independent runs: 1389 tests, 0 failures, 0 errors, 5 skipped each time; also manually audited all other raw-SQL `booking.bookings` test fixtures in the repo (31 files) for the same overlap pattern — none others were affected.

### Review Findings

- [x] [Review][Decision] Batch and reschedule flows bypass the new double-booking protection entirely — resolved: added a DB-level exclusion constraint as a backstop (option b). `V87__booking_overlap_exclusion_constraint.sql` adds `excl_bkg_coach_slot_overlap` (Postgres `EXCLUDE USING gist` on `(coach_id, tstzrange(requested_start_time, requested_end_time, '[)'))`, scoped to `ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING/IN_PROGRESS/PAUSED` — `REQUESTED` deliberately excluded, see migration comment) covering all write paths including `BookingBatchService`/`RescheduleService`. Wired into the existing global `DataIntegrityViolationException` handler in `ApiAdvice.java` (`CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS`) so a violation returns `booking.slotUnavailable` / 409 instead of a raw 500. Note: `BookingBatchService`/`RescheduleService` still don't get the app-layer lock+pre-check (option a was not applied) — a violation there fails at the DB with a clean error code but without the pre-emptive UX of the app-layer check; residual gap tracked in `deferred-work.md`.
- [x] [Review][Decision] Undisclosed scope-creep: unrelated exception-metadata added to pre-existing exception throws in `createBookingRequest` — resolved: applied consistently (option 2). All `OperationNotAllowedException` throws in `createBookingRequest` and `acceptBooking` now carry relevant metadata (requested times, coach/parent/pack/booking ids).
- [x] [Review][Decision] `IN_PROGRESS`/`PAUSED` booking statuses are not covered by overlap protection — resolved: expanded (option 1). `ACTIVE_SLOT_STATUSES` now includes `IN_PROGRESS`, `PAUSED`; `acceptBooking`'s re-check list is now derived from it (`ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`) instead of hand-duplicated (also resolves the related patch finding below).
- [x] [Review][Decision] Migration doesn't use `CREATE INDEX CONCURRENTLY` — resolved: deferred (option 3). Logged in `deferred-work.md` — deferred, reason: needs Flyway non-transactional migration config to safely add `CONCURRENTLY`, bigger than a single-migration patch; revisit if table size or deploy window makes the blocking-lock risk material.
- [x] [Review][Patch] Missing self-exclude filter lets a retried `acceptBooking` call self-match and mask the real error — fixed: `findOverlappingBookings` gained an `excludeBookingId` parameter (`AND (:excludeBookingId IS NULL OR b.id <> :excludeBookingId)`); `acceptBooking` passes its own `bookingId`, `createBookingRequest` passes `null`. New regression test `acceptBooking_retriedOnAlreadyAcceptedBooking_doesNotSelfMatchAsOverlap`. [src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java, BookingService.java:200,278]
- [x] [Review][Patch] `hasBookingConflict` silently swallows invalid coach timezones as "no conflict" with no logging — fixed: added `log.warn(...)` in the `catch (DateTimeException e)` branch. [src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java]
- [x] [Review][Patch] `findByIdForUpdate`'s return value is discarded at both call sites, undocumented as lock-only — fixed: both call sites now `.orElseThrow(() -> new ResourceNotFoundException(...))` with an explanatory comment. [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java]
- [x] [Review][Patch] New overlap-check unit tests never assert the `SLOT_UNAVAILABLE` error code or the exact status list passed to `findOverlappingBookings` — fixed: both tests now assert `getErrorCode() == BookingError.SLOT_UNAVAILABLE` and `verify(...)` the exact status list + `excludeBookingId` argument. [src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java]
- [x] [Review][Patch] `acceptBooking`'s status list is hand-duplicated instead of derived from `ACTIVE_SLOT_STATUSES` — fixed alongside decision D3: now derived via `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`. [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:259]
- [x] [Review][Patch] Stray unrelated TODO comment added to unchanged code — fixed: removed while touching the adjacent lines for decision D2. [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:183]
- [x] [Review][Patch] Concurrency IT's `f.get()` calls have no timeout — fixed: both now use `f.get(30, TimeUnit.SECONDS)`. [src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java]
- [x] [Review][Patch] No handling for `PessimisticLockException`/lock timeout around the new lock acquisitions — fixed: `CoachProfileRepository.findByIdForUpdate` now sets a 5s `jakarta.persistence.lock.timeout` query hint; new global `ApiAdvice` handler maps `PessimisticLockingFailureException` to 409 `generic.conflict` (mirrors the existing `ObjectOptimisticLockingFailureException` handler). [src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java, src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java]
- [x] [Review][Patch] No DST-boundary test coverage despite DST-sensitive zone conversion logic — fixed: new test `updateWindow_bookingImmediatelyAfterDstTransition_computesConflictUsingCorrectOffset`, computed against the real next Europe/Berlin DST transition (not a hardcoded date). [src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java]
- [x] [Review][Patch] No direct repository-level boundary test for `findOverlappingBookings` — fixed: new `BookingRepositoryIT.java` covering adjacent-non-overlapping (both directions), fully-nested, partial-overlap, exact-boundary-match, self-exclude, different-coach, and status-filter cases. [src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java]
- [x] [Review][Defer] Coach-suspension race window between the unlocked initial status check and the later lock acquisition in `createBookingRequest` — `coach.getStatus()` is validated via a plain `findById` at `BookingService.java:154-164`, then the row is locked later (after the external `paymentGateway.isCoachPaymentReady` call, per this story's own explicit ordering requirement) without re-checking status. If an admin suspends the coach in that window, a booking can still be created. Pre-existing pattern (the unlocked initial read predates this story); the new lock creates a cheap opportunity to close it in a future change. — deferred, pre-existing, not caused by this diff
- [x] [Review][Defer] No DB-level exclusion constraint backing the overlap prevention — all double-booking protection lives in app-layer checks at each write path. Pre-existing architectural trade-off, explicitly justified in this story's own Dev Notes ("no dedicated calendar-slot row to lock"). See the related decision item above (batch/reschedule bypass) for why this may be worth revisiting. — deferred, pre-existing design trade-off documented in Dev Notes

**Dismissed as noise (9):**
- `hasBookingConflict` "only checks booking start time, not full interval overlap" — matches AC4's literal spec text exactly ("returns true if any such booking's local start time... falls on the window's dayOfWeek within [startTime, endTime)").
- Unconfigurable 90-day lookahead horizon in `hasBookingConflict` — explicitly justified in Dev Notes as an intentional bounded lookahead ("editing/deleting a window is prospective only").
- Lock held across a payment-gateway call in `acceptBooking` — verified false; `INITIATE_PAYMENT`'s `transitionInternal` doesn't call the payment gateway synchronously, the actual gateway interaction happens in an `AFTER_COMMIT` listener outside the transaction/lock scope (confirmed via code + inline comment).
- AC3 re-check's test-scenario "unproven necessity in production" — the story's own Dev Notes explicitly explain and justify the direct-seed test approach as standing in for pre-existing overlapping `REQUESTED` bookings.
- Migration missing `IF NOT EXISTS` — Flyway versioned migrations are tracked by checksum and run exactly once; non-issue under normal operation.
- Task 9's loosened `PAYMENT_PENDING`-or-`CONFIRMED` assertion vs. the story's literal "reaches PAYMENT_PENDING" text — transparently disclosed in the Debug Log References and justified by a real synchronous `AFTER_COMMIT` listener race, not a defect.
- IT test container setup (`@Import(TestConfig.class)` vs. `QuotaServiceConcurrencyTest`'s `BaseVideoIT`) — functionally equivalent, uses an established convention already used elsewhere in the suite.
- `hasBookingConflict`'s `CONFIRMED`/`UPCOMING`-only status list for the availability-edit conflict warning — matches AC4's literal text exactly, not a deviation.
- Composite index shape not validated with `EXPLAIN` against the query planner — spec Task 1 prescribes the exact index verbatim; AC5 itself frames performance validation as a "grows to production scale" concern, not required now.
