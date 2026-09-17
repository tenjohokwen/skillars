package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuickCompleteTimeoutService {

    private final SessionCompletionDataRepository completionDataRepository;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    /**
     * skillars-deferred-118 AC3 sizing basis: {@code findPendingQuickCompletes} carries no
     * config-bound batch-size ceiling, the same shape as {@code BookingExpiryScheduler}/
     * {@code BookingReminderScheduler} (both {@code fixedDelay = 5min}, both {@code PT15M}/
     * {@code PT2M}). Per-row work here is {@code transition()} (pessimistic lock + save) + one
     * booking reload + an event publish — three DB round trips, no external HTTP call, the same
     * cost class as those two siblings. At a pessimistic 500ms/row and an assumed worst-case
     * pending-quick-complete volume of 1500 (already an order of magnitude above any plausible
     * near-term scale — this table is a subset of {@code COMPLETED_PENDING_CONFIRMATION} bookings,
     * inherently bounded below total booking volume, and there is no ceiling to size off instead):
     * {@code 1500 × 500ms = 12.5 minutes}. {@code PT15M} sits above that with real margin, matching
     * the two siblings' identical-cadence convention rather than inventing a new one.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "QuickCompleteTimeoutService_processExpiredQuickCompletes",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void processExpiredQuickCompletes() {
        // skillars-deferred-107 AC2: 0 → Quick Complete auto-confirms instantly; neg → nonsense cutoff.
        long timeoutHours = configService.getBoundedLong("booking.quick_complete_timeout_hours", 1L, 168L);
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        List<SessionCompletionData> expired = completionDataRepository.findPendingQuickCompletes(cutoff);

        for (SessionCompletionData scd : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    bookingService.transition(scd.getBookingId(), BookingEvent.COMPLETE,
                        new TransitionContext(ActorRole.SYSTEM, null));
                    Booking booking = bookingService.getBookingOrThrow(scd.getBookingId());
                    eventPublisher.publishEvent(new BookingCompletedEvent(
                        this, scd.getBookingId(), scd.getCoachId(), scd.getPlayerId(),
                        booking.getParentId(), scd.isPlayerAttended(), scd.getEffortRating(),
                        scd.getFocusRating(), scd.getTechniqueRating(), List.of()
                    ));
                });
                log.info("Auto-confirmed Quick Complete for booking {}", scd.getBookingId());
            } catch (BookingStateTransitionException | OptimisticLockingFailureException e) {
                log.warn("Booking {} already completed by another instance — skipping", scd.getBookingId());
            } catch (Exception e) {
                log.error("Failed to auto-confirm Quick Complete for booking {}", scd.getBookingId(), e);
            }
        }
    }
}
