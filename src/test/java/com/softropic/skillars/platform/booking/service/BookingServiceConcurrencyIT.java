package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BookingServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private CoachProfileService coachProfileService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_ID_1 = 9511000001L;
    private static final long PARENT_ID_2 = 9511000002L;
    private static final long PLAYER_ID_1 = 9511000011L;
    private static final long PLAYER_ID_2 = 9511000012L;
    private static final long COACH_USER_ID = 9511000021L;

    private static final String WINDOW_TZ = "Europe/Berlin";

    /**
     * How long the lock-holding thread in the three tests below keeps the coach_profiles row
     * locked before releasing it. skillars-deferred-62 made findByIdForUpdate NO_WAIT, so a
     * NOWAIT collision leaves no pg_locks "waiting" trace to poll for any more — this fixed hold
     * replaces that polling. Must comfortably outlast the contending thread's wake-from-latch and
     * first-attempt latency under CI load (so its first attempt is guaranteed to collide), while
     * staying well inside PessimisticLockRetryer's ~3.2s default retry budget (so the contending
     * thread's retry succeeds afterward rather than exhausting its budget and surfacing a 409).
     */
    private static final long COACH_LOCK_HOLD_MILLIS = 2000;

    private UUID coachProfileId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            insertUser(PARENT_ID_1, "parent1.concurrency@skillars-test.com", "PARENT");
            insertUser(PARENT_ID_2, "parent2.concurrency@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.concurrency@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Concurrency Player 1', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID_1, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID_1, Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Concurrency Player 2', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID_2, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID_2, Timestamp.from(Instant.now())
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Concurrency Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, WINDOW_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, WINDOW_TZ
            );
            return null;
        });
    }


    @Test
    void concurrentCreateBookingRequest_overlappingSlot_onlyOneSucceeds() throws Exception {
        // AC 2: two different parent/player pairs submit overlapping requests for the same
        // coach and same time range at nearly the same instant.
        CreateBookingRequest req1 = new CreateBookingRequest(
            coachProfileId, PLAYER_ID_1, slotStart, slotEnd, null, null);
        CreateBookingRequest req2 = new CreateBookingRequest(
            coachProfileId, PLAYER_ID_2, slotStart, slotEnd, null, null);

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger slotUnavailableCount = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedException = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runCreate(PARENT_ID_1, req1, startLatch, successCount, slotUnavailableCount, unexpectedException)));
        futures.add(executor.submit(() -> runCreate(PARENT_ID_2, req2, startLatch, successCount, slotUnavailableCount, unexpectedException)));

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        if (unexpectedException.get() != null) {
            throw new AssertionError("Unexpected exception in concurrent thread", unexpectedException.get());
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(slotUnavailableCount.get()).isEqualTo(1);

        List<Booking> bookings = bookingRepository.findAllByCoachId(coachProfileId);
        assertThat(bookings).hasSize(1);
    }

    private void runCreate(long parentId, CreateBookingRequest req, CountDownLatch startLatch,
                            AtomicInteger successCount, AtomicInteger slotUnavailableCount,
                            AtomicReference<Throwable> unexpectedException) {
        try {
            startLatch.await();
            bookingService.createBookingRequest(parentId, req);
            successCount.incrementAndGet();
        } catch (OperationNotAllowedException e) {
            if (e.getErrorCode() == BookingError.SLOT_UNAVAILABLE) {
                slotUnavailableCount.incrementAndGet();
            } else {
                unexpectedException.set(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            unexpectedException.set(t);
        }
    }

    @Test
    void concurrentAcceptBooking_overlappingRequestedBookings_onlyOneAccepts() throws Exception {
        // AC 3: two overlapping REQUESTED bookings pre-dating this story's overlap prevention
        // (seeded directly, since AC 1/2 make it impossible to create them via createBookingRequest).
        Booking booking1 = seedRequestedBooking(PARENT_ID_1, PLAYER_ID_1, slotStart, slotEnd);
        Booking booking2 = seedRequestedBooking(PARENT_ID_2, PLAYER_ID_2, slotStart.plusSeconds(1800), slotEnd.plusSeconds(1800));

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger slotUnavailableCount = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedException = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> runAccept(booking1.getId(), startLatch, successCount, slotUnavailableCount, unexpectedException)));
        futures.add(executor.submit(() -> runAccept(booking2.getId(), startLatch, successCount, slotUnavailableCount, unexpectedException)));

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        if (unexpectedException.get() != null) {
            throw new AssertionError("Unexpected exception in concurrent thread", unexpectedException.get());
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(slotUnavailableCount.get()).isEqualTo(1);

        // The winning booking leaves acceptBooking() as PAYMENT_PENDING, but the
        // AFTER_COMMIT PaymentLifecycleService listener (StubPaymentGateway, synchronous in
        // this test context) may already have advanced it to CONFIRMED by the time we read it back.
        List<String> statuses = List.of(
            bookingRepository.findById(booking1.getId()).orElseThrow().getStatus(),
            bookingRepository.findById(booking2.getId()).orElseThrow().getStatus());
        assertThat(statuses).contains("REQUESTED");
        assertThat(statuses).anyMatch(s -> s.equals("PAYMENT_PENDING") || s.equals("CONFIRMED"));
    }

    private void runAccept(UUID bookingId, CountDownLatch startLatch,
                            AtomicInteger successCount, AtomicInteger slotUnavailableCount,
                            AtomicReference<Throwable> unexpectedException) {
        try {
            startLatch.await();
            bookingService.acceptBooking(bookingId, COACH_USER_ID);
            successCount.incrementAndGet();
        } catch (OperationNotAllowedException e) {
            if (e.getErrorCode() == BookingError.SLOT_UNAVAILABLE) {
                slotUnavailableCount.incrementAndGet();
            } else {
                unexpectedException.set(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            unexpectedException.set(t);
        }
    }

    /**
     * Deferred-12 AC3: an admin suspends the coach in the window between
     * createBookingRequest()'s first, unlocked status read and its later
     * coachProfileRepository.findByIdForUpdate() acquiring the per-coach lock.
     *
     * <p>The race is driven deterministically rather than by luck: a second connection takes
     * {@code SELECT … FOR UPDATE} on the coach row and flips the status to SUSPENDED without
     * committing. The booking thread's unlocked read therefore still observes ACTIVE (READ
     * COMMITTED), then blocks on the same row lock. Once the suspending transaction commits, the
     * booking thread acquires the lock over a row that is now SUSPENDED.
     *
     * <p>This must be a real IT: with a mocked repository the "re-check" would read whatever the
     * mock was told to return and would pass even against a fix that does nothing. It also fails
     * against a fix that merely calls getStatus() on findByIdForUpdate's return value, because that
     * is the same managed instance loaded earlier in the method and still carries stale state.
     */
    @Test
    void createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable() throws Exception {
        CreateBookingRequest req = new CreateBookingRequest(
            coachProfileId, PLAYER_ID_1, slotStart, slotEnd, null, null);

        CountDownLatch suspensionStagedAndLockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> suspenderFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> suspender = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                        String.class, coachProfileId);
                    jdbcTemplate.update(
                        "UPDATE marketplace.coach_profiles SET status = 'SUSPENDED' WHERE id = ?",
                        coachProfileId);
                    suspensionStagedAndLockHeld.countDown();
                    // See COACH_LOCK_HOLD_MILLIS's javadoc: this fixed hold takes the place of the
                    // pg_locks polling this test used before NO_WAIT, guaranteeing the booking
                    // thread's first attempt collides while this transaction is still open and
                    // uncommitted — exactly the window a missing/broken lock would let slip through
                    // with a stale (pre-SUSPENDED) read.
                    try {
                        Thread.sleep(COACH_LOCK_HOLD_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the coach_profiles lock — "
                            + "results below are not trustworthy.", e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                suspenderFailure.set(t);
            }
        });

        AtomicReference<Throwable> bookingOutcome = new AtomicReference<>();
        AtomicReference<Instant> bookingCallStartedAt = new AtomicReference<>();
        AtomicReference<Instant> bookingCallEndedAt = new AtomicReference<>();
        Future<?> booker = executor.submit(() -> {
            try {
                suspensionStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                bookingCallStartedAt.set(Instant.now());
                bookingService.createBookingRequest(PARENT_ID_1, req);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                bookingOutcome.set(t);
            } finally {
                bookingCallEndedAt.set(Instant.now());
            }
        });

        suspender.get(30, TimeUnit.SECONDS);
        booker.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (suspenderFailure.get() != null) {
            throw new AssertionError("Suspending thread failed", suspenderFailure.get());
        }

        assertThat(Duration.between(bookingCallStartedAt.get(), bookingCallEndedAt.get()))
            .as("must have taken close to the full lock-hold duration via NO_WAIT retry, not just "
                + "some incidental delay — a near-instant or barely-delayed outcome would mean the "
                + "booking thread never actually contended for the coach-profile lock, making this "
                + "test's proof worthless")
            .isGreaterThanOrEqualTo(Duration.ofMillis(COACH_LOCK_HOLD_MILLIS - 300));

        assertThat(bookingOutcome.get())
            .as("Booking must be rejected once the locked re-read sees the SUSPENDED coach")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(((OperationNotAllowedException) bookingOutcome.get()).getErrorCode())
            .isEqualTo(BookingError.COACH_UNAVAILABLE);

        assertThat(bookingRepository.findAllByCoachId(coachProfileId))
            .as("No booking row may be persisted for a coach suspended before the lock was taken")
            .isEmpty();
    }

    /**
     * Deferred-15 AC4: an admin suspends the coach while a coach-accept is parked on the coach-row
     * lock. Same deterministic staging as the createBookingRequest test above — the accepting thread
     * passes its unlocked read, blocks on findByIdForUpdate, and is granted the lock over a row that
     * is by then SUSPENDED.
     *
     * <p>This is more than an abstract race: suspendCoach cancels only the coach's REQUESTED
     * bookings, so a booking accepted inside its window moves to PAYMENT_PENDING and survives the
     * suspension entirely, invisible to that sweep.
     *
     * <p>Mutation-verified in both directions. Removing the entityManager.refresh alone is enough to
     * break it: findByIdForUpdate is JPQL and the coach row is already managed from the findByUserId
     * earlier in acceptBooking, so the locked read hands back the stale ACTIVE instance and the
     * check never fires. Asserting on the booking's final stored status, not on whether it waited.
     */
    @Test
    void acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable() throws Exception {
        Booking booking = seedRequestedBooking(PARENT_ID_1, PLAYER_ID_1, slotStart, slotEnd);

        CountDownLatch suspensionStagedAndLockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> suspenderFailure = new AtomicReference<>();
        AtomicReference<Throwable> acceptOutcome = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> suspender = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                        String.class, coachProfileId);
                    jdbcTemplate.update(
                        "UPDATE marketplace.coach_profiles SET status = 'SUSPENDED' WHERE id = ?", coachProfileId);
                    suspensionStagedAndLockHeld.countDown();
                    // See COACH_LOCK_HOLD_MILLIS's javadoc, and the identical comment in
                    // createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable
                    // above.
                    try {
                        Thread.sleep(COACH_LOCK_HOLD_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the coach_profiles lock — "
                            + "results below are not trustworthy.", e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                suspenderFailure.set(t);
            }
        });

        AtomicReference<Instant> acceptCallStartedAt = new AtomicReference<>();
        AtomicReference<Instant> acceptCallEndedAt = new AtomicReference<>();
        Future<?> accepter = executor.submit(() -> {
            try {
                suspensionStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                acceptCallStartedAt.set(Instant.now());
                bookingService.acceptBooking(booking.getId(), COACH_USER_ID);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                acceptOutcome.set(t);
            } finally {
                acceptCallEndedAt.set(Instant.now());
            }
        });

        suspender.get(30, TimeUnit.SECONDS);
        accepter.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (suspenderFailure.get() != null) {
            throw new AssertionError("Suspending thread failed", suspenderFailure.get());
        }

        assertThat(Duration.between(acceptCallStartedAt.get(), acceptCallEndedAt.get()))
            .as("must have taken close to the full lock-hold duration via NO_WAIT retry, not just "
                + "some incidental delay — a near-instant or barely-delayed outcome would mean the "
                + "accepting thread never actually contended for the coach-profile lock, making this "
                + "test's proof worthless")
            .isGreaterThanOrEqualTo(Duration.ofMillis(COACH_LOCK_HOLD_MILLIS - 300));

        assertThat(acceptOutcome.get())
            .as("the accept must fail once the locked re-read sees the SUSPENDED coach")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(((OperationNotAllowedException) acceptOutcome.get()).getErrorCode())
            .isEqualTo(BookingError.COACH_UNAVAILABLE);
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
            .as("a booking accepted here would rest in PAYMENT_PENDING, which suspendCoach's "
                + "REQUESTED-only cancellation sweep never sees")
            .isEqualTo("REQUESTED");
    }

    /**
     * Deferred-58 AC2: {@code CoachProfileService.saveStep4} must now serialize against the same
     * coach-profile row lock {@code RescheduleService.acceptReschedule} and
     * {@code BookingDuplicationService.duplicateNextWeek} already take when reading these windows.
     * Same staging as the two suspend tests above: a raw-SQL thread takes {@code SELECT ... FOR
     * UPDATE} on the coach row and holds it for a fixed duration, then commits. Postgres row locks
     * are symmetric regardless of which query path acquires them, so proving {@code saveStep4}
     * contends for this raw lock is sufficient evidence that it now shares it with the two existing
     * readers proven above.
     *
     * <p>skillars-deferred-62: {@code findByIdForUpdate} is now {@code NO_WAIT} plus a bounded retry
     * ({@link com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer}), so there is
     * no genuine DB-level block to poll pg_locks for any more — a NOWAIT failure leaves no trace
     * there. The fixed hold below comfortably outlasts the time it takes {@code saveStep4} to wake
     * from its latch and issue its first (necessarily contended) attempt, and is comfortably inside
     * the retry budget so the eventual retry succeeds rather than exhausting it into a 409.
     */
    @Test
    void saveStep4_coachRowLockedByAnotherSession_blocksUntilReleasedThenWritesCorrectly() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        AtomicReference<Instant> lockReleasedAt = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> locker = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                        String.class, coachProfileId);
                    lockHeld.countDown();
                    // See COACH_LOCK_HOLD_MILLIS's javadoc.
                    try {
                        Thread.sleep(COACH_LOCK_HOLD_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while holding the coach_profiles lock — "
                            + "results below are not trustworthy.", e);
                    }
                    return null;
                });
                lockReleasedAt.set(Instant.now());
            } catch (Throwable t) {
                lockerFailure.set(t);
            }
        });

        AtomicReference<Instant> saveStep4CompletedAt = new AtomicReference<>();
        AtomicReference<Throwable> saverFailure = new AtomicReference<>();
        Future<?> saver = executor.submit(() -> {
            try {
                lockHeld.await(10, TimeUnit.SECONDS);
                ProfileBuilderStep4Request req = new ProfileBuilderStep4Request(List.of(
                    new ProfileBuilderStep4Request.AvailabilityWindowRequest(
                        (short) 1, LocalTime.of(9, 0), LocalTime.of(17, 0), WINDOW_TZ)));
                coachProfileService.saveStep4(COACH_USER_ID, req);
                saveStep4CompletedAt.set(Instant.now());
            } catch (Throwable t) {
                saverFailure.set(t);
            }
        });

        locker.get(30, TimeUnit.SECONDS);
        saver.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (lockerFailure.get() != null) {
            throw new AssertionError("Locking thread failed", lockerFailure.get());
        }
        if (saverFailure.get() != null) {
            throw new AssertionError("saveStep4 failed", saverFailure.get());
        }

        assertThat(saveStep4CompletedAt.get())
            .as("saveStep4 must not complete until the raw SELECT ... FOR UPDATE lock is released — "
                + "proving it now contends for the same coach-profile row lock the existing readers do")
            .isAfterOrEqualTo(lockReleasedAt.get());

        List<Short> writtenDays = jdbcTemplate.queryForList(
            "SELECT day_of_week FROM marketplace.coach_availability_windows WHERE coach_id = ?",
            Short.class, coachProfileId);
        assertThat(writtenDays)
            .as("the rewrite must still land correctly once the lock is acquired")
            .containsExactly((short) 1);
    }

    private Booking seedRequestedBooking(long parentId, long playerId, Instant start, Instant end) {
        return transactionTemplate.execute(status -> {
            Booking booking = new Booking();
            booking.setParentId(parentId);
            booking.setPlayerId(playerId);
            booking.setCoachId(coachProfileId);
            booking.setRequestedStartTime(start);
            booking.setRequestedEndTime(end);
            booking.setCanonicalTimezone(WINDOW_TZ);
            booking.setStatus("REQUESTED");
            return bookingRepository.save(booking);
        });
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "69" + (id % 100000000),
            email,
            role
        );
    }
}
