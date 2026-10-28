package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * skillars-deferred-153 AC1: a coach who Quick-Completes a session and then abandons
 * {@code WrapUpSequence.vue} before its mandatory step-5 submit leaves the booking in
 * {@code COMPLETED_PENDING_CONFIRMATION} with no {@code SessionCompletionData} row at all —
 * {@link QuickCompleteTimeoutService}'s own sweeper only matches rows that already exist, so it
 * can never pick this case up. Mirrors that sibling's {@code @Scheduled}/{@code @SchedulerLock}/
 * {@code TransactionTemplate} shape exactly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StuckCompletionSweeperService {

    /**
     * skillars-deferred-153 code review (2026-10-17): bounded batch. See
     * {@code BookingRepository.findStuckCompletionsWithNoSessionData}'s own comment for why — the
     * backlog drains across several ticks rather than one oversized pass that could outlive the
     * ShedLock lease.
     */
    private static final int SWEEP_BATCH_SIZE = 200;

    private final BookingRepository bookingRepository;
    private final BookingCompletionService bookingCompletionService;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "StuckCompletionSweeperService_processStuckCompletions",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void processStuckCompletions() {
        long timeoutHours = configService.getBoundedLong(ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS);
        // skillars-deferred-153 code review (2026-10-17): ClockProvider, not a bare Instant.now(), so
        // the cutoff is pinnable by a fake clock in tests — the cutoff is the only business logic this
        // class computes, and it was previously unassertable.
        Instant cutoff = Instant.now(ClockProvider.getClock()).minus(timeoutHours, ChronoUnit.HOURS);
        List<Booking> stuck = bookingRepository.findStuckCompletionsWithNoSessionData(
            cutoff, PageRequest.of(0, SWEEP_BATCH_SIZE));

        for (Booking booking : stuck) {
            try {
                // The catches below sit OUTSIDE transactionTemplate.execute deliberately, mirroring
                // QuickCompleteTimeoutService. forceCompleteWithPlaceholderData runs @Transactional
                // REQUIRED and therefore joins this template's transaction, so a constraint violation
                // or a lost transition race only surfaces when the template commits — i.e. here, not
                // inside the service method. See that method's Javadoc for the full reasoning.
                ForceCompleteOutcome outcome = transactionTemplate.execute(status ->
                    bookingCompletionService.forceCompleteWithPlaceholderData(
                        booking.getId(), ActorRole.SYSTEM, null));
                if (outcome == ForceCompleteOutcome.FORCE_COMPLETED) {
                    log.info("Force-completed abandoned wrap-up for booking {}", booking.getId());
                } else {
                    // Benign and expected: the batch read is unlocked, so a real wrap-up or another
                    // instance can resolve a booking between the query and this iteration.
                    log.info("Skipped stuck booking {} — {}", booking.getId(), outcome);
                }
            } catch (DataIntegrityViolationException | BookingStateTransitionException
                     | OptimisticLockingFailureException e) {
                log.warn("Booking {} was resolved by another actor mid-sweep — skipping", booking.getId());
            } catch (Exception e) {
                log.error("Failed to force-complete stuck booking {}", booking.getId(), e);
            }
        }
    }
}
