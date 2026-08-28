package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.RescheduleService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.CreateSessionPlanRequest;
import com.softropic.skillars.platform.session.contract.SessionBlockRequest;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.UpdateSessionPlanRequest;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deferred-78: cross-AC regression spanning AC1 (availability write-lock parity), AC2 (reschedule
 * availabilitySignature parity), and AC8 (session-plan cancellation lock) together — a booking
 * lifecycle none of those ACs' own individual tests exercises end-to-end. Drives the flow through
 * the real service beans against a real Postgres DB (Testcontainers), not mocks: create a booking
 * (seeded directly, CONFIRMED), request a reschedule (exercises AC1's reordered lock and AC2's
 * signature guard together in {@code RescheduleService.validateRescheduleProposal}), build a
 * session plan for it, then cancel the booking as the parent and assert the session plan the AC8
 * listener locks is the same one AC1/AC2 validated the reschedule proposal against.
 */
class SessionPlanCancellationLifecycleIT extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private RescheduleService rescheduleService;
    @Autowired private SessionPlanService sessionPlanService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final long PARENT_ID = 9620000001L;
    private static final long PLAYER_ID = 9620000002L;
    private static final long COACH_USER_ID = 9620000010L;
    private static final String WINDOW_TZ = "Europe/Berlin";

    private UUID coachProfileId;
    private UUID bookingId;
    private Instant bookingStart;
    private Instant bookingEnd;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode("TestPass@123!");

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9620, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9621, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertUser(PARENT_ID, "parent.planlifecycle@skillars-test.com", passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Lifecycle Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, "coach.planlifecycle@skillars-test.com", passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Lifecycle Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, WINDOW_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
                "VALUES (?, 'INSTRUCTOR', NOW()) ON CONFLICT DO NOTHING",
                coachProfileId
            );
            // Wide-open, every-day-of-week availability so the reschedule proposal's slot always
            // clears AC1/AC2's locked isSlotWithinAvailabilityWindow check below.
            for (short dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_availability_windows " +
                    "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                    "VALUES (?, ?, ?, '00:00:00', '23:59:59', ?)",
                    UUID.randomUUID(), coachProfileId, dayOfWeek, WINDOW_TZ
                );
            }

            bookingId = UUID.randomUUID();
            ZonedDateTime slot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
            bookingStart = slot.toInstant();
            bookingEnd = slot.plusHours(1).toInstant();
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                "status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(bookingStart), Timestamp.from(bookingEnd), WINDOW_TZ,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "72" + (id % 100000000),
            email, passwordHash, role
        );
    }

    @Test
    @Timeout(30)
    void bookingReschedulePropsedThenCancelled_lockSessionPlanAfterCommit() {
        // Step 1 (AC1 + AC2): request a reschedule — validateRescheduleProposal now locks the
        // coach row before reading windows (AC1) and would reject a stale availabilitySignature
        // (AC2); passing null here exercises the "no check" branch, proving the reordering itself
        // didn't break the ordinary no-signature path.
        Instant proposedStart = bookingStart.plus(1, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        rescheduleService.requestReschedule(bookingId, PARENT_ID,
            new CreateRescheduleRequest(proposedStart, proposedEnd, null));

        String rescheduleStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE booking_id = ?",
            String.class, bookingId);
        assertThat(rescheduleStatus).isEqualTo("PENDING");

        // Step 2: build a session plan for the still-CONFIRMED booking.
        SessionBlockRequest block = new SessionBlockRequest("WARMUP", "Warmup", 10, List.of());
        SessionPlanResponse created = sessionPlanService.createSession(
            new CreateSessionPlanRequest(bookingId, List.of(block), List.of("PASSING")), COACH_USER_ID);
        assertThat(created.status()).isEqualTo("DRAFT");

        // Step 3 (AC8): the parent cancels the booking. cancelBookingAsParent's CANCEL_PARENT
        // transition publishes BookingStatusChangedEvent AFTER_COMMIT, which
        // SessionPlanService.handleBookingTerminalNonCompletion (no @Async) consumes synchronously
        // before this call returns.
        bookingService.cancelBookingAsParent(bookingId, PARENT_ID);

        Session lockedSession = sessionRepository.findById(created.id()).orElseThrow();
        assertThat(lockedSession.getStatus())
            .as("the session plan built against this booking must be locked once the booking is cancelled")
            .isEqualTo("CANCELLED");

        // Step 4: the lock is enforced going forward too, not just recorded as a status string.
        UpdateSessionPlanRequest updateReq = new UpdateSessionPlanRequest(
            List.of(block), List.of("PASSING"), "SAVED");
        assertThatThrownBy(() -> sessionPlanService.updateSession(created.id(), updateReq, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(SessionErrorCode.SESSION_PLAN_LOCKED));
    }
}
