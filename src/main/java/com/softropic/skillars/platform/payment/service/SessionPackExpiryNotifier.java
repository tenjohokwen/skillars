package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPackExpiryNotifier {

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 0 8 * * *")
    public void notifyExpiringPacks() {
        Instant now = Instant.now();
        Instant window = now.plus(14, ChronoUnit.DAYS);

        List<SessionPackPurchase> expiring =
            sessionPackPurchaseRepository.findExpiringWithinWindowAndSessionsRemaining(now, window);

        for (SessionPackPurchase pack : expiring) {
            CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
            if (coach == null) continue;

            String parentEmail = userRepository.findById(pack.getParentId())
                .map(u -> u.getEmail()).orElse("");
            String coachEmail = userRepository.findById(coach.getUserId())
                .map(u -> u.getEmail()).orElse("");

            log.info("Pack expiry warning scheduled: purchaseId={} expiresAt={}",
                pack.getPurchaseId(), pack.getExpiresAt());

            eventPublisher.publishEvent(new SessionPackExpiryWarningEvent(
                this,
                pack.getPurchaseId(),
                pack.getParentId(),
                parentEmail,
                pack.getCoachId(),
                coachEmail,
                coach.getDisplayName(),
                pack.getRemainingSessions(),
                pack.getExpiresAt(),
                "14_DAYS",
                coach.getCanonicalTimezone()
            ));
        }
    }
}
