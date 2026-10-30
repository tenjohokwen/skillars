package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.SessionNeverStartedEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
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
 * fix/booking-completion-integrity: the sessions nothing else ever settles once their scheduled end has
 * passed (by {@code booking.unresolvedSession.graceMinutes}, default 120):
 * <ul>
 *   <li><b>IN_PROGRESS / PAUSED</b> — the coach pressed Start and never ended the session. Neither party
 *       could settle it (no parent cancel of a live session, no admin cancel), so it sat with the money
 *       captured forever. It is moved to COMPLETED_PENDING_CONFIRMATION ({@code auto_ended_at} stamped):
 *       the coach's later wrap-up is then a QUICK one ({@code BookingCompletionService.submitWrapUp}), so the
 *       parent's confirm / dispute window applies (the family is asked at once —
 *       fix/audit3-completion-dispute-integrity), and with no wrap-up at all
 *       {@link StuckCompletionSweeperService} takes over as for any abandoned wrap-up.</li>
 *   <li><b>UPCOMING</b> — the session ended without ever being started. Never auto-paid and never
 *       auto-refunded (the coach may genuinely have coached and forgotten the app): one
 *       {@code SESSION_NEVER_STARTED} admin alert per booking ({@code unresolved_alerted_at}), while the
 *       parent can still report a coach no-show and the coach can still quick-complete.</li>
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

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final BookingCompletionService bookingCompletionService;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "UnresolvedSessionSweeperService_sweep",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void sweep() {
        long graceMinutes = configService.getBoundedLong(ConfigBounds.BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES);
        Instant cutoff = Instant.now(ClockProvider.getClock()).minus(graceMinutes, ChronoUnit.MINUTES);
        endAbandonedLiveSessions(cutoff);
        alertNeverStartedSessions(cutoff);
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

    private void alertNeverStartedSessions(Instant cutoff) {
        List<UUID> ids = bookingRepository.findUnalertedNeverStartedIdsEndedBefore(
            cutoff, PageRequest.of(0, SWEEP_BATCH_SIZE));
        for (UUID bookingId : ids) {
            try {
                Boolean alerted = transactionTemplate.execute(status -> {
                    Booking booking = bookingService.lockBookingForUpdate(bookingId);
                    // Re-checked under the lock: the coach may have started / completed it, or the parent
                    // reported a no-show, between the id select and this transaction.
                    if (!BookingStatus.UPCOMING.name().equals(booking.getStatus())
                            || booking.getUnresolvedAlertedAt() != null) {
                        return false;
                    }
                    booking.setUnresolvedAlertedAt(Instant.now(ClockProvider.getClock()));
                    bookingRepository.save(booking);
                    eventPublisher.publishEvent(new SessionNeverStartedEvent(this, bookingId, booking.getCoachId(),
                        booking.getParentId(), booking.getRequestedEndTime()));
                    return true;
                });
                if (Boolean.TRUE.equals(alerted)) {
                    log.warn("[SESSION_NEVER_STARTED] booking {} ended without being started — admin alerted", bookingId);
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("Booking {} changed mid-sweep — retried on the next tick", bookingId);
            } catch (Exception e) {
                log.error("Failed to flag never-started session for booking {}", bookingId, e);
            }
        }
    }
}
