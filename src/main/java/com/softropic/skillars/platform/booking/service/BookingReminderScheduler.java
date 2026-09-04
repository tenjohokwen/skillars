package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Drives the 24-hour (primary) and 2-hour (secondary) booking reminders.
 *
 * <h2>What {@code primaryReminderSentAt} / {@code secondaryReminderSentAt} mean (skillars-deferred-92 AC29.4)</h2>
 *
 * They are stamped <em>before</em> the event is published, and that ordering is deliberate rather
 * than incidental. {@code BookingEmailListener.onBookingReminder} is
 * {@code @TransactionalEventListener(BEFORE_COMMIT)}, so its outbox write happens inside this
 * method's own {@code @Transactional} boundary: the stamp and the outbox row commit together or
 * neither does. The columns therefore mean <strong>"a reminder was durably queued for this
 * booking"</strong>, not "an SMTP server accepted it" — delivery itself is the outbox drain's job
 * and is tracked by the outbox row's {@code attempts} / {@code last_error} and the
 * {@code [OUTBOX_STUCK]} alert.
 *
 * <p>Stamping <em>after</em> a confirmed send was considered and rejected: it would need the stamp
 * to be written from the drain, i.e. from a different transaction than the one that selected the
 * booking, reintroducing the double-send window the {@code IS NULL} filter on
 * {@link com.softropic.skillars.platform.booking.repo.BookingRepository#findUpcomingWithin2hWindow}
 * exists to close.
 *
 * <p><strong>History.</strong> Before skillars-deferred-92 AC29 the listener carried no annotation
 * at all, so these columns recorded reminders that were never sent. {@code V129} resets
 * {@code secondary_reminder_sent_at} for still-future bookings so they are re-picked; see that
 * migration for why the primary column is deliberately left alone.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingReminderScheduler {

    private static final long MAX_WINDOW_HOURS = 24L * 365; // 1 year — guards against Duration.ofHours overflow

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.config.service.ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "BookingReminderScheduler_remind",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    @Transactional
    public void processReminderWindows() {
        long primaryHours = configService.getBoundedLong("platform.reminder_interval_primary_hours", 24L, 1L, MAX_WINDOW_HOURS);
        long secondaryHours = configService.getBoundedLong("platform.reminder_interval_secondary_hours", 2L, 1L, MAX_WINDOW_HOURS);
        Instant now = Instant.now();

        Instant primaryWindowEnd = now.plus(Duration.ofHours(primaryHours));
        List<Booking> toTransition = bookingRepository.findConfirmedForUpcomingTransition(primaryWindowEnd);
        Set<UUID> primaryProcessed = toTransition.stream().map(Booking::getId).collect(Collectors.toSet());
        for (Booking b : toTransition) {
            try {
                bookingService.transition(b.getId(), BookingEvent.SCHEDULE_UPCOMING,
                    new TransitionContext(ActorRole.SYSTEM, null));
                b.setPrimaryReminderSentAt(now);
                eventPublisher.publishEvent(buildReminderEvent(b, "PRIMARY"));
                // skillars-deferred-92 AC29.4: says "queued", not "sent". The old wording asserted a
                // delivery this method has never performed — it publishes an event that
                // BookingEmailListener turns into an outbox row; the actual SMTP send happens later,
                // in the drain, and can still fail there. A log line that claims more than the code
                // did is how AC29's bug survived a full story and a 3-layer review.
                log.info("Transitioned booking {} to UPCOMING and queued primary reminder", b.getId());
            } catch (Exception e) {
                log.error("Failed to process primary reminder for booking {}", b.getId(), e);
            }
        }

        Instant secondaryWindowEnd = now.plus(Duration.ofHours(secondaryHours));
        List<Booking> toRemind = bookingRepository.findUpcomingWithin2hWindow(now, secondaryWindowEnd)
            .stream().filter(b -> !primaryProcessed.contains(b.getId())).toList();
        for (Booking b : toRemind) {
            try {
                b.setSecondaryReminderSentAt(now);
                bookingRepository.save(b);
                eventPublisher.publishEvent(buildReminderEvent(b, "SECONDARY"));
                log.info("Queued secondary reminder for booking {}", b.getId());
            } catch (Exception e) {
                log.error("Failed to process secondary reminder for booking {}", b.getId(), e);
            }
        }
    }

    private BookingReminderEvent buildReminderEvent(Booking b, String reminderType) {
        CoachProfile coach = coachProfileRepository.findById(b.getCoachId()).orElse(null);
        String coachName = coach != null ? coach.getDisplayName() : "Coach";
        String coachEmail = coach != null ? resolveEmail(coach.getUserId(), b.getId()) : "";
        String parentEmail = resolveEmail(b.getParentId(), b.getId());

        return BookingReminderEvent.builder()
            .source(this)
            .bookingId(b.getId())
            .parentEmail(parentEmail)
            .coachEmail(coachEmail)
            .coachDisplayName(coachName)
            .requestedStartTime(b.getRequestedStartTime())
            .canonicalTimezone(b.getCanonicalTimezone())
            .reminderType(reminderType)
            .build();
    }

    private String resolveEmail(Long userId, UUID bookingId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElseGet(() -> {
            log.warn("Could not resolve email for userId={} bookingId={} — reminder notification will be skipped", userId, bookingId);
            return "";
        });
    }
}
