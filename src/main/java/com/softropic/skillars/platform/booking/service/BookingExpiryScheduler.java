package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingExpiredEvent;
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
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.softropic.skillars.platform.config.service.ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void expireStaleRequests() {
        long expiryHours = configService.getLong("booking.request_expiry_hours");
        Instant threshold = Instant.now().minus(Duration.ofHours(expiryHours));
        List<Booking> stale = bookingRepository.findRequestedBookingsOlderThan(threshold);
        for (Booking booking : stale) {
            try {
                booking.setStatus("DECLINED");
                bookingRepository.save(booking);
                CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
                String coachName = coach != null ? coach.getDisplayName() : "Coach";
                eventPublisher.publishEvent(new BookingExpiredEvent(
                    this, booking.getId(), booking.getParentId(),
                    resolveEmail(booking.getParentId()), coachName,
                    booking.getRequestedStartTime(), booking.getCanonicalTimezone()
                ));
                log.info("Auto-expired booking {} (created at {})", booking.getId(), booking.getCreatedAt());
            } catch (Exception e) {
                log.error("Failed to auto-expire booking {}", booking.getId(), e);
            }
        }
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }
}
