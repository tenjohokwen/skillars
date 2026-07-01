package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiredEvent;
import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
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
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionPackExpiryScheduler {

    private final SessionPackPurchasedRepository repository;
    private final CoachProfileRepository coachProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "SessionPackExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void runExpiryAndWarnings() {
        Instant now = Instant.now();
        expireActivePacks(now);
        sendWarnings(now);
    }

    void expireActivePacks(Instant now) {
        List<SessionPackPurchased> expired = transactionTemplate.execute(
            status -> repository.findExpiredActivePacks(now));
        if (expired == null) return;
        for (SessionPackPurchased pack : expired) {
            try {
                transactionTemplate.execute(status -> {
                    pack.setStatus("EXPIRED");
                    repository.save(pack);
                    CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
                    String parentEmail = resolveEmail(pack.getParentId());
                    eventPublisher.publishEvent(new SessionPackExpiredEvent(
                        this, pack.getId(), pack.getPlayerId(), pack.getCoachId(),
                        pack.getParentId(), parentEmail,
                        coach != null ? coach.getDisplayName() : "Coach",
                        pack.getCreditsRemaining()
                    ));
                    log.info("Expired pack {} ({} credits remaining)", pack.getId(), pack.getCreditsRemaining());
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to expire pack {}", pack.getId(), e);
            }
        }
    }

    void sendWarnings(Instant now) {
        Instant threshold30d = now.plus(Duration.ofDays(30));
        Instant threshold7d  = now.plus(Duration.ofDays(7));

        List<SessionPackPurchased> packs30d = transactionTemplate.execute(
            status -> repository.findPacksNeedingWarning30d(threshold7d, threshold30d));
        if (packs30d != null) {
            for (SessionPackPurchased pack : packs30d) {
                try {
                    transactionTemplate.execute(status -> {
                        CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
                        String coachEmail30d = coach != null ? resolveEmailByUserId(coach.getUserId()) : "";
                        eventPublisher.publishEvent(new SessionPackExpiryWarningEvent(
                            this, pack.getId(), pack.getParentId(), resolveEmail(pack.getParentId()),
                            pack.getCoachId(), coachEmail30d,
                            coach != null ? coach.getDisplayName() : "Coach",
                            pack.getCreditsRemaining(), pack.getExpiresAt(), "30d",
                            coach != null ? coach.getCanonicalTimezone() : "UTC"
                        ));
                        pack.setWarning30dSentAt(now);
                        repository.save(pack);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Failed to send 30d warning for pack {}", pack.getId(), e);
                }
            }
        }

        List<SessionPackPurchased> packs7d = transactionTemplate.execute(
            status -> repository.findPacksNeedingWarning7d(now, threshold7d));
        if (packs7d != null) {
            for (SessionPackPurchased pack : packs7d) {
                try {
                    transactionTemplate.execute(status -> {
                        CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
                        String coachEmail7d = coach != null ? resolveEmailByUserId(coach.getUserId()) : "";
                        eventPublisher.publishEvent(new SessionPackExpiryWarningEvent(
                            this, pack.getId(), pack.getParentId(), resolveEmail(pack.getParentId()),
                            pack.getCoachId(), coachEmail7d,
                            coach != null ? coach.getDisplayName() : "Coach",
                            pack.getCreditsRemaining(), pack.getExpiresAt(), "7d",
                            coach != null ? coach.getCanonicalTimezone() : "UTC"
                        ));
                        pack.setWarning7dSentAt(now);
                        repository.save(pack);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Failed to send 7d warning for pack {}", pack.getId(), e);
                }
            }
        }
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }

    private String resolveEmailByUserId(Long userId) {
        if (userId == null) return "";
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }
}
