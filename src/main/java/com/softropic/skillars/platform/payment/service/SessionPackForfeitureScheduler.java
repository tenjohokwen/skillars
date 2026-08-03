package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiredEvent;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionPackForfeitureScheduler {

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "SessionPackForfeitureScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void forfeitExpiredPacks() {
        Instant now = Instant.now();
        List<SessionPackPurchase> expired = transactionTemplate.execute(
            status -> sessionPackPurchaseRepository.findExpiredNotYetNotified(now));
        if (expired == null) return;

        for (SessionPackPurchase purchase : expired) {
            try {
                transactionTemplate.execute(status -> {
                    CoachProfile coach = coachProfileRepository.findById(purchase.getCoachId()).orElse(null);
                    String parentEmail = userRepository.findById(purchase.getParentId())
                        .map(u -> u.getEmail()).orElse("");
                    purchase.setExpiredNotifiedAt(now);
                    sessionPackPurchaseRepository.save(purchase);
                    eventPublisher.publishEvent(new SessionPackExpiredEvent(
                        this, purchase.getPurchaseId(), purchase.getPlayerId(), purchase.getCoachId(),
                        purchase.getParentId(), parentEmail,
                        coach != null ? coach.getDisplayName() : "Coach",
                        purchase.getRemainingSessions()
                    ));
                    log.info("Forfeited session pack purchase {} ({} sessions remaining)",
                        purchase.getPurchaseId(), purchase.getRemainingSessions());
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to forfeit session pack purchase {}", purchase.getPurchaseId(), e);
            }
        }
    }
}
