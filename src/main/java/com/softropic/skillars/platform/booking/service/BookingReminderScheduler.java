package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingReminderScheduler {

    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.config.service.ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void processReminderWindows() {
        long primaryHours = configService.getLong("platform.reminder_interval_primary_hours");
        long secondaryHours = configService.getLong("platform.reminder_interval_secondary_hours");
        Instant now = Instant.now();

        Instant primaryWindowEnd = now.plus(Duration.ofHours(primaryHours));
        List<Booking> toTransition = bookingRepository.findConfirmedForUpcomingTransition(primaryWindowEnd);
        Set<UUID> primaryProcessed = toTransition.stream().map(Booking::getId).collect(Collectors.toSet());
        for (Booking b : toTransition) {
            try {
                b.setStatus("UPCOMING");
                b.setPrimaryReminderSentAt(now);
                bookingRepository.save(b);
                eventPublisher.publishEvent(buildReminderEvent(b, "PRIMARY"));
                log.info("Transitioned booking {} to UPCOMING and sent primary reminder", b.getId());
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
                log.info("Sent secondary reminder for booking {}", b.getId());
            } catch (Exception e) {
                log.error("Failed to process secondary reminder for booking {}", b.getId(), e);
            }
        }
    }

    private BookingReminderEvent buildReminderEvent(Booking b, String reminderType) {
        CoachProfile coach = coachProfileRepository.findById(b.getCoachId()).orElse(null);
        String coachName = coach != null ? coach.getDisplayName() : "Coach";
        String coachEmail = coach != null ? resolveEmail(coach.getUserId()) : "";
        String parentEmail = resolveEmail(b.getParentId());

        return new BookingReminderEvent(
            this, b.getId(), parentEmail, coachEmail, coachName,
            b.getRequestedStartTime(), b.getCanonicalTimezone(), reminderType
        );
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }
}
