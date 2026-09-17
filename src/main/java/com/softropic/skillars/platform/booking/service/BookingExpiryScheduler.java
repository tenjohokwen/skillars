package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * skillars-deferred-118 AC1 — one transaction per booking, never one per batch.
 *
 * <p>{@code expireStaleRequests} used to carry a method-level {@code @Transactional} around its
 * whole loop over the stale-booking batch. {@link BookingService#transition} joins that same
 * physical transaction (default {@code REQUIRED} propagation) rather than opening its own, so a
 * concurrent accept or cancel racing the scheduler between the batch {@code SELECT} and a specific
 * booking's turn in the loop — a real, everyday occurrence — throws an unchecked
 * {@code BookingStateTransitionException} inside that shared transaction. Spring's default rollback
 * rule marks the whole physical transaction {@code rollbackOnly=true} immediately, even though the
 * per-iteration {@code try/catch} below swallows the exception and the loop continues. When the
 * method returns normally, the transaction interceptor's commit attempt finds {@code rollbackOnly}
 * and throws {@code UnexpectedRollbackException}, uncaught, out of the whole {@code @Scheduled}
 * method — silently discarding every other booking's already-logged-successful auto-expiry from the
 * same run, not just the one that raced.
 *
 * <p>Each booking now gets its own {@link TransactionTemplate} scope, mirroring
 * {@link BookingReminderScheduler#processReminderWindows} exactly — the established pattern this
 * codebase already uses for "batch-select then per-item transaction with a swallowing catch". The
 * absence of {@code @Transactional} on this method is pinned by
 * {@code SchedulerLockTransactionOrderingIT}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingExpiryScheduler {

    private static final long MAX_WINDOW_HOURS = 24L * 365; // 1 year — guards against Duration.ofHours overflow

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.config.service.ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "BookingExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void expireStaleRequests() {
        long expiryHours = configService.getBoundedLong("booking.request_expiry_hours", 48L, 1L, MAX_WINDOW_HOURS);
        Instant threshold = Instant.now().minus(Duration.ofHours(expiryHours));
        List<Booking> stale = transactionTemplate.execute(status ->
            bookingRepository.findRequestedBookingsOlderThan(threshold));
        if (stale == null) {
            stale = List.of();
        }
        for (Booking booking : stale) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    bookingService.transition(booking.getId(), BookingEvent.DECLINE,
                        new TransitionContext(ActorRole.SYSTEM, null));
                    CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
                    String coachName = coach != null ? coach.getDisplayName() : "Coach";
                    eventPublisher.publishEvent(BookingExpiredEvent.builder()
                        .source(this)
                        .bookingId(booking.getId())
                        .parentId(booking.getParentId())
                        .parentEmail(resolveEmail(booking.getParentId(), booking.getId()))
                        .coachDisplayName(coachName)
                        .requestedStartTime(booking.getRequestedStartTime())
                        .canonicalTimezone(booking.getCanonicalTimezone())
                        .build());
                });
                // Logged after the commit, matching BookingReminderScheduler's convention: the
                // per-booking transaction can still fail inside the try, and a line claiming the
                // booking was expired must not precede the commit that actually expired it.
                log.info("Auto-expired booking {} (created at {})", booking.getId(), booking.getCreatedAt());
            } catch (Exception e) {
                log.error("Failed to auto-expire booking {}", booking.getId(), e);
            }
        }
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — expiry notification will be skipped", userId, bookingId);
            return "";
        });
    }
}
