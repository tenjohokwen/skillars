package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.CoachStartReminderEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * S-07 (owner decision 2026-10-31): fewer forgotten Starts. A session still UPCOMING at its scheduled start gets the
 * coach a "press Start, or this session can be refunded" reminder (email + in-app inbox row, the project's only
 * notification channels — there is no push), and a second one at start + 10 minutes if it is still unstarted.
 *
 * <p>Per-booking sent-at columns ({@code coach_start_reminder_sent_at}, {@code coach_late_start_reminder_sent_at},
 * V219) are the dedupe, stamped in the same transaction that publishes the event, under the booking row lock with the
 * status re-checked — so a coach who pressed Start (or whose session was claimed or cancelled) between the id select and
 * here gets nothing. A coach found only after start + 10 minutes (scheduler downtime, a late confirmation) gets ONE
 * "LATE" reminder that stamps both columns, never two at once; nothing is sent for a session more than an hour past its
 * start (a long outage must not replay stale reminders). Shape mirrors {@link BookingReminderScheduler} /
 * {@link UnresolvedSessionSweeperService}: a bounded id page per tick, one transaction per booking, the catches outside
 * the template; ShedLock'd so only one instance sends.
 *
 * <p>The email is category BOOKING (see {@code NotificationCatalog}): transactional and safety-relevant, so it ignores the
 * REMINDER opt-out the ordinary session reminders honour.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachStartReminderScheduler {

    static final int BATCH_SIZE = 200;
    /** The second reminder is due this long after the scheduled start. */
    static final Duration LATE_REMINDER_AFTER = Duration.ofMinutes(10);
    /** No reminder is sent for a session further past its start than this. */
    static final Duration STALE_AFTER = Duration.ofHours(1);

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "CoachStartReminderScheduler_remind", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void remind() {
        Instant now = Instant.now(ClockProvider.getClock());
        List<UUID> ids = bookingRepository.findUnstartedIdsDueForCoachReminder(
            now, now.minus(STALE_AFTER), PageRequest.of(0, BATCH_SIZE));
        for (UUID bookingId : ids) {
            try {
                String sent = transactionTemplate.execute(status -> remindOne(bookingId, now));
                if (sent != null) {
                    log.info("Queued {} start reminder for booking {}", sent, bookingId);
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("Booking {} changed mid-sweep — retried on the next tick", bookingId);
            } catch (Exception e) {
                log.error("Failed to queue the coach start reminder for booking {}", bookingId, e);
            }
        }
    }

    /** @return the reminder type queued, or null when nothing was due / the booking moved on under the lock */
    private String remindOne(UUID bookingId, Instant now) {
        Booking booking = bookingService.lockBookingForUpdate(bookingId);
        if (!BookingStatus.UPCOMING.name().equals(booking.getStatus())) {
            return null;
        }
        boolean lateDue = !now.isBefore(booking.getRequestedStartTime().plus(LATE_REMINDER_AFTER));
        String type;
        if (booking.getCoachStartReminderSentAt() == null) {
            // Never reminded: the late one covers the first when both are already due.
            type = lateDue ? CoachStartReminderEvent.LATE : CoachStartReminderEvent.START;
            booking.setCoachStartReminderSentAt(now);
            if (lateDue) {
                booking.setCoachLateStartReminderSentAt(now);
            }
        } else if (lateDue && booking.getCoachLateStartReminderSentAt() == null) {
            type = CoachStartReminderEvent.LATE;
            booking.setCoachLateStartReminderSentAt(now);
        } else {
            return null;
        }
        bookingRepository.save(booking);
        eventPublisher.publishEvent(new CoachStartReminderEvent(this, bookingId, resolveCoachEmail(booking),
            resolvePlayerName(booking), booking.getRequestedStartTime(), booking.getCanonicalTimezone(), type));
        return type;
    }

    private String resolveCoachEmail(Booking booking) {
        return coachProfileRepository.findById(booking.getCoachId())
            .map(CoachProfile::getUserId)
            .flatMap(userRepository::findById)
            .map(User::getEmail)
            .orElse("");
    }

    private String resolvePlayerName(Booking booking) {
        return playerProfileRepository.findById(booking.getPlayerId()).map(PlayerProfile::getName).orElse("your player");
    }
}
