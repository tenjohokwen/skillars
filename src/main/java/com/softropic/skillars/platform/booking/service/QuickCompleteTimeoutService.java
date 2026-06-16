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
    private final SessionPackService sessionPackService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void processExpiredQuickCompletes() {
        long timeoutHours = configService.getLong("booking.quick_complete_timeout_hours");
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        List<SessionCompletionData> expired = completionDataRepository.findPendingQuickCompletes(cutoff);

        for (SessionCompletionData scd : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    bookingService.transition(scd.getBookingId(), BookingEvent.COMPLETE,
                        new TransitionContext(ActorRole.SYSTEM, null));
                    Booking booking = bookingService.getBookingOrThrow(scd.getBookingId());
                    sessionPackService.deductCredit(scd.getPlayerId(), scd.getCoachId());
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
