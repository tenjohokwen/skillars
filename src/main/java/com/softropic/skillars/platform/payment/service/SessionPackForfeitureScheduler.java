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
                    // skillars-deferred-103 AC7: explicit error handling for missing/blank records
                    CoachProfile coach = coachProfileRepository.findById(purchase.getCoachId()).orElse(null);
                    if (coach == null) {
                        // Deliberately left unstamped, mirroring SessionPackExpiryNotifier: the pack
                        // keeps being selected so the ERROR repeats every run until the row is
                        // repaired. session_pack_purchases.coach_id carries an FK (fk_spp_coach), so
                        // reaching here is a data-integrity failure, not an ordinary missing coach —
                        // stamping it would silence the only signal that it happened.
                        log.error("Session pack expiry notification skipped — coach profile missing: "
                            + "purchaseId={} coachId={} parentId={}",
                            purchase.getPurchaseId(), purchase.getCoachId(), purchase.getParentId());
                        return null;
                    }
                    String parentEmail = userRepository.findById(purchase.getParentId())
                        .map(u -> u.getEmail())
                        .filter(email -> org.springframework.util.StringUtils.hasText(email))
                        .orElse(null);
                    if (parentEmail == null) {
                        // A parent legitimately without an email is not a repairable data bug and a
                        // retry cannot change the outcome — stamp it so the forfeiture is finalised
                        // once and the purchase stops being re-selected every cycle (the query
                        // filters expiredNotifiedAt IS NULL). The skipped notification is the only
                        // loss, and it is logged.
                        log.error("Session pack expiry notification skipped — parent email missing/blank: "
                            + "parentId={} purchaseId={} coachId={}",
                            purchase.getParentId(), purchase.getPurchaseId(), purchase.getCoachId());
                        purchase.setExpiredNotifiedAt(now);
                        sessionPackPurchaseRepository.save(purchase);
                        return null;
                    }
                    purchase.setExpiredNotifiedAt(now);
                    sessionPackPurchaseRepository.save(purchase);
                    eventPublisher.publishEvent(new SessionPackExpiredEvent(
                        this, purchase.getPurchaseId(), purchase.getPlayerId(), purchase.getCoachId(),
                        purchase.getParentId(), parentEmail,
                        coach.getDisplayName(),
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
