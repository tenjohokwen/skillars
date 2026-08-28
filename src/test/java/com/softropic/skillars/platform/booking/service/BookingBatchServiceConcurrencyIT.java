package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.booking.contract.BatchSlot;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deferred-78 AC1: {@code BookingBatchService.createBatch} previously re-checked duration/
 * availability against a fresh read taken outside any lock (skillars-deferred-69 AC7's own comment
 * documented this as an accepted, unclosed gap). Mirrors {@code BookingServiceConcurrencyIT}'s
 * window-deleted-while-waiting-for-the-lock shape: a background thread holds the coach-profile row
 * lock and, while holding it, deletes the coach's only availability window. The batch create is
 * parked on its (now lock-guarded) fresh re-check for the whole hold, and must see the POST-delete,
 * empty window list once granted the lock, not a pre-lock snapshot.
 */
class BookingBatchServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private BookingBatchService bookingBatchService;
    @Autowired private BookingRepository bookingRepository;

    private static final long PARENT_ID = 9512000001L;
    private static final long PLAYER_ID = 9512000011L;
    private static final long COACH_USER_ID = 9512000021L;

    private static final String WINDOW_TZ = "Europe/Berlin";
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
            insertUser(PARENT_ID, "parent.batchconcurrency@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.batchconcurrency@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Batch Concurrency Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, java.sql.Date.valueOf(java.time.LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Batch Concurrency Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
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
    @Timeout(30)
    void createBatch_windowDeletedWhileWaitingForCoachLock_seesPostLockEmptyWindowsAndRejects() throws Exception {
        CreateBatchRequest req = new CreateBatchRequest(coachProfileId, PLAYER_ID,
            List.of(new BatchSlot(slotStart, slotEnd)), BigDecimal.ZERO, null);

        CountDownLatch windowDeletedAndLockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> deleterFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> deleter = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE",
                        String.class, coachProfileId);
                    jdbcTemplate.update(
                        "DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?",
                        coachProfileId);
                    windowDeletedAndLockHeld.countDown();
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
                deleterFailure.set(t);
            }
        });

        AtomicReference<Throwable> batchOutcome = new AtomicReference<>();
        AtomicReference<Instant> callStartedAt = new AtomicReference<>();
        AtomicReference<Instant> callEndedAt = new AtomicReference<>();
        Future<?> batcher = executor.submit(() -> {
            try {
                windowDeletedAndLockHeld.await(10, TimeUnit.SECONDS);
                callStartedAt.set(Instant.now());
                bookingBatchService.createBatch(PARENT_ID, req);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                batchOutcome.set(t);
            } finally {
                callEndedAt.set(Instant.now());
            }
        });

        deleter.get(30, TimeUnit.SECONDS);
        batcher.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (deleterFailure.get() != null) {
            throw new AssertionError("Window-deleting thread failed", deleterFailure.get());
        }

        assertThat(Duration.between(callStartedAt.get(), callEndedAt.get()))
            .as("must have taken close to the full lock-hold duration via NO_WAIT retry — a near-"
                + "instant outcome would mean the batch thread never actually contended for the "
                + "coach-profile lock, making this test's proof worthless")
            .isGreaterThanOrEqualTo(Duration.ofMillis(COACH_LOCK_HOLD_MILLIS - 300));

        assertThat(batchOutcome.get())
            .as("must reject once the locked fresh re-check sees the now-empty window list, proving "
                + "the re-check happens under the lock rather than before it")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(((OperationNotAllowedException) batchOutcome.get()).getErrorCode())
            .isEqualTo(BookingError.SLOT_OUTSIDE_AVAILABILITY);

        assertThat(bookingRepository.findAllByCoachId(coachProfileId))
            .as("no booking row may be persisted once the window backing the batch slot was deleted before the lock was granted")
            .isEmpty();
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
            "71" + (id % 100000000),
            email,
            role
        );
    }
}
