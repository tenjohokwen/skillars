package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    /**
     * Deferred-15 AC6 fixed two independent defects here, which had to ship together.
     *
     * <p><strong>Delivery.</strong> This method published its events with no transaction bound,
     * while {@code SessionPackEmailListener.onExpiryWarning} is a
     * {@code @TransactionalEventListener(AFTER_COMMIT)} with the default
     * {@code fallbackExecution = false} — so every event was discarded and the warning email was
     * sent ZERO times, not the fourteen the ledger item assumed (reproduced before the fix).
     * {@code SessionPackForfeitureScheduler} publishes inside {@code transactionTemplate.execute}
     * and therefore works; that difference was the whole bug.
     *
     * <p><strong>Dedupe.</strong> Once delivery works, a daily cron over a 14-day window selects the
     * same pack every morning. {@code expiryWarnedAt} is stamped inside the very transaction whose
     * commit fires the listener, so a delivered warning is always a recorded one.
     *
     * <p>A pack whose expiry is extended is already excluded by the query's {@code extendedAt IS
     * NULL} predicate; re-warning after an extension would be new product behaviour, not a bug fix.
     */
    @Scheduled(cron = "0 0 8 * * *")
    @SchedulerLock(name = "SessionPackExpiryNotifier_warn", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    public void notifyExpiringPacks() {
        Instant now = Instant.now();
        Instant window = now.plus(14, ChronoUnit.DAYS);

        List<SessionPackPurchase> expiring = transactionTemplate.execute(
            status -> sessionPackPurchaseRepository.findExpiringWithinWindowAndSessionsRemaining(now, window));
        if (expiring == null) return;

        for (SessionPackPurchase pack : expiring) {
            // Per pack, as in SessionPackForfeitureScheduler: one bad pack must not abort the run.
            try {
                transactionTemplate.execute(status -> {
                    CoachProfile coach = coachProfileRepository.findById(pack.getCoachId()).orElse(null);
                    if (coach == null) {
                        // Deliberately left unstamped: this pack keeps being selected, so the ERROR
                        // repeats daily until someone repairs the row. Stamping it would silence the
                        // only signal that a purchase points at a coach that does not exist —
                        // session_pack_purchases.coach_id carries an FK (fk_spp_coach), so reaching
                        // here at all means a data-integrity failure, not an ordinary missing coach.
                        log.error("Session pack expiry warning skipped — coach profile missing: "
                            + "purchaseId={} coachId={} parentId={} expiresAt={}",
                            pack.getPurchaseId(), pack.getCoachId(), pack.getParentId(), pack.getExpiresAt());
                        return null;
                    }

                    String parentEmail = userRepository.findById(pack.getParentId())
                        .map(u -> u.getEmail()).orElse("");
                    String coachEmail = userRepository.findById(coach.getUserId())
                        .map(u -> u.getEmail()).orElse("");

                    pack.setExpiryWarnedAt(now);
                    sessionPackPurchaseRepository.save(pack);

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
                    log.info("Pack expiry warning sent: purchaseId={} expiresAt={}",
                        pack.getPurchaseId(), pack.getExpiresAt());
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to send expiry warning for session pack purchase {}", pack.getPurchaseId(), e);
            }
        }
    }
}
