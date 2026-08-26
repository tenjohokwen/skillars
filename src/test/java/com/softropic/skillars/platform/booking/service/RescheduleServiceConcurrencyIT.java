package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-69 AC9: {@code RescheduleService.acceptReschedule} is the one of the three
 * {@code PessimisticLockRetryer}-using methods {@code skillars-deferred-62}'s own review flagged as
 * worth a direct concurrency IT — its two-sequential-locks-in-one-transaction shape (reschedule
 * request row, then coach profile row; see the lock-ordering comment in
 * {@code RescheduleService#acceptRescheduleShared}) is more complex than the single-lock case
 * {@link com.softropic.skillars.platform.payment.service.SessionPackPurchaseLockContentionIT}
 * proves. Mirrors that class's structure and both of its lock-contention test shapes, contending on
 * the FIRST lock this method takes (the reschedule-request row) via a background thread's raw
 * {@code SELECT ... FOR UPDATE}, real Postgres row-lock contention via Testcontainers, not mocked.
 *
 * <p>Story text originally described the prolonged-contention case as failing with an
 * {@code OperationNotAllowedException}/{@code BookingError.CONCURRENT_MODIFICATION}-shaped
 * rejection — that shape belongs to {@code bookingRepository.save}'s {@code
 * OptimisticLockingFailureException} catch (a version-conflict at save time), a different
 * mechanism. {@code PessimisticLockRetryer.withBoundedRetry} re-throws the raw {@code
 * PessimisticLockingFailureException} unwrapped on retry-budget exhaustion (see its own {@code
 * throw e;} at the end of the retry loop), and neither {@code acceptReschedule} nor {@code
 * acceptRescheduleShared} catches it — confirmed by direct read of both methods. This class
 * therefore asserts the actual exception type, matching {@code SessionPackPurchaseLockContentionIT
 * #deductSession_prolongedContention_failsWithBounded409AfterRetryBudgetExhausted}'s own shape.
 */
class RescheduleServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    RescheduleService rescheduleService;

    private static final long COACH_USER_ID = 96401L;
    private static final long PARENT_ID = 96402L;
    private static final long PLAYER_ID = 96403L;

    private UUID bookingId;
    private UUID rescheduleId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            insertCoachUser(COACH_USER_ID, "reschedule.concurrency.coach@skillars-test.com");

            UUID coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Concurrency Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );
            // Wide-open, every-day-of-week availability so the accept-time re-check
            // (RescheduleService#acceptRescheduleShared) always clears for the proposed slot below.
            for (short dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_availability_windows " +
                    "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                    "VALUES (?, ?, ?, '00:00:00', '23:59:59', 'Europe/Berlin')",
                    UUID.randomUUID(), coachProfileId, dayOfWeek
                );
            }

            bookingId = UUID.randomUUID();
            Instant bookingStart = safeFutureStart(2);
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                "status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Europe/Berlin', 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(bookingStart), Timestamp.from(bookingStart.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );

            rescheduleId = UUID.randomUUID();
            Instant proposedStart = safeFutureStart(5);
            jdbcTemplate.update(
                "INSERT INTO booking.booking_reschedule_requests " +
                "(id, booking_id, proposed_by, proposed_start_time, proposed_end_time, status, created_at) " +
                "VALUES (?, ?, 'PARENT', ?, ?, 'PENDING', ?)",
                rescheduleId, bookingId, Timestamp.from(proposedStart),
                Timestamp.from(proposedStart.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    /** Anchors to a fixed, safe local hour rather than preserving wall-clock time-of-day — same
     *  rationale as RescheduleResourceIT's own safeProposedStart (skillars-deferred-69 AC2). */
    private Instant safeFutureStart(int daysAhead) {
        return ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
            .plusDays(daysAhead)
            .truncatedTo(ChronoUnit.DAYS)
            .withHour(10)
            .toInstant();
    }

    private void insertCoachUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Concurrency', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "70" + (id % 100000000),
            email
        );
    }

    /**
     * A competing lock held well within {@link com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer}'s
     * retry budget (~3.2s across its default 8 attempts) must not surface a failure — the retry loop
     * should absorb it and the accept should still land, exactly as it would with no contention.
     */
    @Test
    @Timeout(30)
    void acceptReschedule_briefContentionOnRescheduleRequestRow_succeedsAfterBoundedRetry() throws Exception {
        long holdMillis = 1200;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM booking.booking_reschedule_requests WHERE id = ? FOR UPDATE",
                    rs -> { }, rescheduleId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() -> {
                rescheduleService.acceptReschedule(bookingId, rescheduleId, COACH_USER_ID);
                return null;
            });
            contender.get(20, TimeUnit.SECONDS);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("must have actually waited out the brief contention via retry, not skipped it")
                .isGreaterThanOrEqualTo(holdMillis - 200);

            String rescheduleStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?", String.class, rescheduleId);
            assertThat(rescheduleStatus)
                .as("brief contention must not turn into a lost accept or a surfaced failure")
                .isEqualTo("ACCEPTED");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A competing lock held past the retry budget must still surface the contention as {@link
     * PessimisticLockingFailureException}, and must do so bounded (well before the full hold time),
     * not after an unbounded wait — and must not have partially applied the accept.
     */
    @Test
    @Timeout(30)
    void acceptReschedule_prolongedContentionOnRescheduleRequestRow_failsWithBoundedPessimisticLockingFailure() throws Exception {
        long holdMillis = 8000;
        CountDownLatch lockHeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.query(
                    "SELECT id FROM booking.booking_reschedule_requests WHERE id = ? FOR UPDATE",
                    rs -> { }, rescheduleId);
                lockHeld.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("holder must acquire the lock first").isTrue();

            Instant start = Instant.now();
            Future<?> contender = pool.submit(() -> {
                rescheduleService.acceptReschedule(bookingId, rescheduleId, COACH_USER_ID);
                return null;
            });

            assertThatContenderFailsWithPessimisticLockingFailure(contender);
            long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(elapsedMillis)
                .as("retry budget exhaustion must be bounded, well under the %dms hold time", holdMillis)
                .isLessThan(4500);
            assertThat(elapsedMillis)
                .as("must have genuinely retried, not failed on the very first attempt")
                .isGreaterThan(1000);

            String rescheduleStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?", String.class, rescheduleId);
            assertThat(rescheduleStatus)
                .as("the failed attempt must not have partially applied the accept")
                .isEqualTo("PENDING");
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertThatContenderFailsWithPessimisticLockingFailure(Future<?> contender)
            throws InterruptedException, TimeoutException {
        try {
            contender.get(20, TimeUnit.SECONDS);
            throw new AssertionError("expected acceptReschedule to fail with PessimisticLockingFailureException");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
        }
    }
}
