package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The sessions nothing else ever settles:
 * <ul>
 *   <li><b>IN_PROGRESS / PAUSED</b> (fix/booking-completion-integrity) — the coach pressed Start and never ended
 *       the session, once its scheduled end has passed by {@code booking.unresolvedSession.graceMinutes} (default
 *       120). Neither party could settle it (no parent cancel of a live session, no admin cancel), so it sat with
 *       the money captured forever. It is moved to COMPLETED_PENDING_CONFIRMATION ({@code auto_ended_at} stamped):
 *       the coach's later wrap-up is then a QUICK one ({@code BookingCompletionService.submitWrapUp}), so the
 *       parent's confirm / dispute window applies (the family is asked at once —
 *       fix/audit3-completion-dispute-integrity), and with no wrap-up at all {@link StuckCompletionSweeperService}
 *       takes over as for any abandoned wrap-up.</li>
 *   <li><b>UPCOMING, never started</b> (S-07, owner decision 2026-10-31) — nobody recorded a Start, a claim or a
 *       Quick Complete by {@code booking.unresolvedSession.autoRefundHours} (default 48) after the scheduled START:
 *       "no live Start, no session". The payer is refunded in full and the coach is NOT struck, through the same
 *       admin-cancel path a suspension uses ({@code BookingService.cancelBookingAsAdmin}, system actor): a pack
 *       session goes back to the pack (or the money to the wallet if the pack expired), a card / credit payment
 *       to the wallet, and both sides are told. This replaces the old admin-only SESSION_NEVER_STARTED alert —
 *       there is nothing left for an admin to decide. The coach can still Quick Complete until then (the family's
 *       confirm / dispute window applies); the family's own claim window is shorter and closes first (the
 *       auto-refund is never earlier than the report window + 1 hour).</li>
 * </ul>
 * Shape mirrors {@link StuckCompletionSweeperService}: a bounded id page per tick, one transaction per
 * booking, the catches outside the template.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnresolvedSessionSweeperService {

    /** Bounded batch per tick, like StuckCompletionSweeperService — a backlog drains across ticks. */
    static final int SWEEP_BATCH_SIZE = 200;

    /** The system actor of an automatic cancel (admin_action_log's SYSTEM_ACTOR_ID; booking does not import admin). */
    static final long SYSTEM_ACTOR_ID = 0L;

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final BookingCompletionService bookingCompletionService;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "UnresolvedSessionSweeperService_sweep",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void sweep() {
        long graceMinutes = configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES);
        Instant cutoff = Instant.now(ClockProvider.getClock()).minus(graceMinutes, ChronoUnit.MINUTES);
        endAbandonedLiveSessions(cutoff);
        autoRefundNeverStartedSessions(Instant.now(ClockProvider.getClock()));
    }

    private void endAbandonedLiveSessions(Instant cutoff) {
        List<UUID> ids = bookingRepository.findLiveSessionIdsEndedBefore(cutoff, PageRequest.of(0, SWEEP_BATCH_SIZE));
        for (UUID bookingId : ids) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    // transition() takes the row lock and re-validates the status, so a coach ending the
                    // session concurrently makes this a BookingStateTransitionException, not a double move.
                    bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING,
                        new TransitionContext(ActorRole.SYSTEM, null));
                    // The same managed instance transition() refreshed under its lock: flushed at commit.
                    Booking booking = bookingService.getBookingOrThrow(bookingId);
                    booking.setAutoEndedAt(Instant.now(ClockProvider.getClock()));
                    // fix/audit3-completion-dispute-integrity: the family is asked to confirm (or dispute) now —
                    // with no wrap-up, StuckCompletionSweeperService would otherwise pay the coach unasked.
                    bookingCompletionService.requestFamilyConfirmation(booking);
                });
                log.info("Auto-ended live session left open past its end: booking {}", bookingId);
            } catch (BookingStateTransitionException | OptimisticLockingFailureException e) {
                log.info("Booking {} left the live state mid-sweep — skipping", bookingId);
            } catch (Exception e) {
                log.error("Failed to auto-end live session for booking {}", bookingId, e);
            }
        }
    }

    private void autoRefundNeverStartedSessions(Instant now) {
        // Never earlier than the family's claim window + 1h, whatever the config says.
        long reportWindowHours = configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPORT_WINDOW_HOURS);
        long autoRefundHours = Math.max(
            configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_AUTO_REFUND_HOURS),
            reportWindowHours + 1);
        Instant cutoff = now.minus(autoRefundHours, ChronoUnit.HOURS);
        List<UUID> ids = bookingRepository.findNeverStartedIdsStartedBefore(cutoff, PageRequest.of(0, SWEEP_BATCH_SIZE));
        for (UUID bookingId : ids) {
            try {
                // cancelBookingAsAdmin takes the row lock and re-validates the status under it, so a coach starting
                // or quick-completing, or the family claiming, between the id select and here is NOT_CANCELLABLE /
                // LIVE_SESSION (left alone), never a double settlement.
                AdminBookingCancellationOutcome outcome = transactionTemplate.execute(status ->
                    bookingService.cancelBookingAsAdmin(bookingId, SYSTEM_ACTOR_ID,
                        BookingCancelledByAdminEvent.REASON_NEVER_STARTED));
                if (outcome == AdminBookingCancellationOutcome.CANCELLED) {
                    log.info("[NEVER_STARTED_AUTO_REFUND] booking {} was never started or recorded {}h after its start — "
                        + "refunded in full, no coach strike", bookingId, autoRefundHours);
                } else {
                    log.info("Booking {} not auto-refunded (outcome {}) — left as is", bookingId, outcome);
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("Booking {} changed mid-sweep — retried on the next tick", bookingId);
            } catch (Exception e) {
                log.error("Failed to auto-refund never-started session for booking {}", bookingId, e);
            }
        }
    }
}
