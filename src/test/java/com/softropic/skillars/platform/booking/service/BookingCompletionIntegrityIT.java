package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/booking-completion-integrity, against a real database (V209 columns and the widened admin_alerts CHECK):
 * <ul>
 *   <li>the expiry query also returns a REQUESTED booking whose start time has passed;</li>
 *   <li>UnresolvedSessionSweeperService ends a live session left open past its end (CPC + auto_ended_at) and
 *       raises one SESSION_NEVER_STARTED alert for an ended, never-started UPCOMING session;</li>
 *   <li>a parent cannot cancel a PAUSED session.</li>
 * </ul>
 * No new mock beans.
 */
class BookingCompletionIntegrityIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 98411L;
    private static final long PARENT_ID = 98412L;
    private static final long PLAYER_ID = 98413L;

    @Autowired BookingRepository bookingRepository;
    @Autowired BookingService bookingService;
    @Autowired UnresolvedSessionSweeperService unresolvedSessionSweeperService;

    private UUID coachId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "completion_integrity_coach@test.com", "Integrity Coach");
        insertTestParent(PARENT_ID, "completion_integrity_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        releaseSchedulerLock("UnresolvedSessionSweeperService_sweep");
    }

    /** One Instant for start and end (chk_bkg_end_after_start). */
    private UUID seedBooking(String status, Instant start, Instant createdAt) {
        UUID bookingId = UUID.randomUUID();
        Instant end = start.plus(1, ChronoUnit.HOURS);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, "
                + "status, canonical_timezone, version, created_at, updated_at, status_changed_at, agreed_price) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 0, ?, now(), now(), 50.00)",
            bookingId, PARENT_ID, PLAYER_ID, coachId, Timestamp.from(start), Timestamp.from(end), status,
            Timestamp.from(createdAt)));
        return bookingId;
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    @Test
    void expiryQuery_returnsAPastStartRequest_andAStaleOne_butNotAFreshFutureOne() {
        Instant now = Instant.now();
        UUID pastStart = seedBooking("REQUESTED", now.minus(3, ChronoUnit.HOURS), now.minus(5, ChronoUnit.HOURS));
        UUID stale = seedBooking("REQUESTED", now.plus(5, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS));
        UUID fresh = seedBooking("REQUESTED", now.plus(5, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS));

        List<UUID> ids = transactionTemplate.execute(s -> bookingRepository
            .findExpiredRequestedBookings(now.minus(48, ChronoUnit.HOURS), now, PageRequest.of(0, 200))
            .stream().map(b -> b.getId()).toList());

        assertThat(ids).contains(pastStart, stale).doesNotContain(fresh);
    }

    @Test
    void sweep_endsAnAbandonedPausedSession_andAlertsANeverStartedOneOnce() {
        // Distinct, non-overlapping slots (excl_bkg_coach_slot_overlap).
        Instant longAgo = Instant.now().minus(8, ChronoUnit.HOURS);
        UUID paused = seedBooking("PAUSED", longAgo, longAgo.minus(2, ChronoUnit.DAYS));
        UUID neverStarted = seedBooking("UPCOMING", longAgo.plus(2, ChronoUnit.HOURS), longAgo.minus(2, ChronoUnit.DAYS));
        UUID stillRunning = seedBooking("IN_PROGRESS", Instant.now().minus(30, ChronoUnit.MINUTES),
            longAgo.minus(2, ChronoUnit.DAYS));

        unresolvedSessionSweeperService.sweep();

        assertThat(statusOf(paused)).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT auto_ended_at IS NOT NULL FROM booking.bookings WHERE id = ?", Boolean.class, paused)).isTrue();
        assertThat(statusOf(stillRunning)).as("inside its end + grace").isEqualTo("IN_PROGRESS");

        assertThat(statusOf(neverStarted)).as("never auto-settled").isEqualTo("UPCOMING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT unresolved_alerted_at IS NOT NULL FROM booking.bookings WHERE id = ?", Boolean.class, neverStarted))
            .isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM admin.admin_alerts WHERE type = 'SESSION_NEVER_STARTED' AND reference_id = ?",
            Integer.class, neverStarted.toString())).isEqualTo(1);

        // An admin resolves the alert; the next sweep must not raise it again.
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE admin.admin_alerts SET status = 'RESOLVED', resolved_at = now() WHERE reference_id = ?",
            neverStarted.toString()));
        releaseSchedulerLock("UnresolvedSessionSweeperService_sweep");
        unresolvedSessionSweeperService.sweep();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM admin.admin_alerts WHERE type = 'SESSION_NEVER_STARTED' AND reference_id = ?",
            Integer.class, neverStarted.toString())).isEqualTo(1);
    }

    @Test
    void parentCancelOfAPausedSession_isRefused_andTheBookingStaysPaused() {
        Instant start = Instant.now().minus(20, ChronoUnit.MINUTES);
        UUID paused = seedBooking("PAUSED", start, start.minus(2, ChronoUnit.DAYS));

        assertThatThrownBy(() -> bookingService.cancelBookingAsParent(paused, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(BookingError.SESSION_IN_PROGRESS));
        assertThat(statusOf(paused)).isEqualTo("PAUSED");
    }
}
