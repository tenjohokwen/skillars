package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_ID_1 = 9511000001L;
    private static final long PARENT_ID_2 = 9511000002L;
    private static final long PLAYER_ID_1 = 9511000011L;
    private static final long PLAYER_ID_2 = 9511000012L;
    private static final long COACH_USER_ID = 9511000021L;

    private static final String WINDOW_TZ = "Europe/Berlin";

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
                    // Hold the lock until the booking thread is genuinely blocked on
                    // findByIdForUpdate, not for a fixed guessed duration.
                    try {
                        awaitAnotherSessionBlockedOnCoachProfileLock(Duration.ofSeconds(10));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // Do not silently fall through to commit: an interrupt here means the block was
                        // never confirmed, so releasing the lock now would reintroduce the exact
                        // non-determinism this helper exists to eliminate.
                        throw new AssertionError("Interrupted before observing the booking thread "
                            + "genuinely blocked on the coach_profiles lock — results below are not "
                            + "trustworthy.", e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                suspenderFailure.set(t);
            }
        });

        AtomicReference<Throwable> bookingOutcome = new AtomicReference<>();
        Future<?> booker = executor.submit(() -> {
            try {
                suspensionStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                bookingService.createBookingRequest(PARENT_ID_1, req);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                bookingOutcome.set(t);
            }
        });

        suspender.get(30, TimeUnit.SECONDS);
        booker.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (suspenderFailure.get() != null) {
            throw new AssertionError("Suspending thread failed", suspenderFailure.get());
        }

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
                    try {
                        awaitAnotherSessionBlockedOnCoachProfileLock(Duration.ofSeconds(10));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // Do not silently fall through to commit: an interrupt here means the block was
                        // never confirmed, so releasing the lock now would reintroduce the exact
                        // non-determinism this helper exists to eliminate.
                        throw new AssertionError("Interrupted before observing the accepting thread "
                            + "genuinely blocked on the coach_profiles lock — results below are not "
                            + "trustworthy.", e);
                    }
                    return null;
                });
            } catch (Throwable t) {
                suspenderFailure.set(t);
            }
        });

        Future<?> accepter = executor.submit(() -> {
            try {
                suspensionStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                bookingService.acceptBooking(booking.getId(), COACH_USER_ID);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                acceptOutcome.set(t);
            }
        });

        suspender.get(30, TimeUnit.SECONDS);
        accepter.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (suspenderFailure.get() != null) {
            throw new AssertionError("Suspending thread failed", suspenderFailure.get());
        }

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
     * Polls pg_locks until another backend is observed waiting (granted = false) on this session's own
     * current transaction id, or fails the test if that never happens within the timeout. Deterministic
     * replacement for a fixed-duration sleep guess — a {@code SELECT ... FOR UPDATE} blocked on a row
     * this transaction holds does not take a distinct row-level entry in pg_locks; Postgres implements
     * it as the blocked backend waiting on THIS transaction's xid to complete (locktype = 'transactionid'),
     * which is exactly what this query targets. pg_locks is a system view reflecting all backends
     * instance-wide, so polling it from inside this thread's own open transaction (via the same
     * jdbcTemplate bean already used for the staging UPDATE two lines above) is safe: it is not subject
     * to this transaction's MVCC row-data snapshot. (An earlier version of this helper matched on
     * pg_stat_activity.query ILIKE '%coach_profiles%' instead — that field does not reliably reflect the
     * blocked statement's text while the backend is parked waiting, so it never matched; pg_locks'
     * blocked-on-our-xid check is the correct, textbook mechanism and has no such ambiguity.)
     */
    private void awaitAnotherSessionBlockedOnCoachProfileLock(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Integer blockedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks waiting " +
                "WHERE waiting.locktype = 'transactionid' AND waiting.granted = false " +
                "AND waiting.pid != pg_backend_pid() " +
                "AND waiting.transactionid = pg_current_xact_id()::text::xid " +
                "AND EXISTS (" +
                "  SELECT 1 FROM pg_locks rel" +
                "  WHERE rel.pid = waiting.pid AND rel.relation = 'marketplace.coach_profiles'::regclass" +
                ")",
                Integer.class);
            if (blockedCount != null && blockedCount > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No other session was observed blocked on the coach_profiles row lock "
            + "within " + timeout + " — this test's staging assumption failed, results below are not "
            + "trustworthy.");
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
