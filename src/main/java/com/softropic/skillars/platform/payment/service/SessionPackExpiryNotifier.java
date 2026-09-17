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
import org.springframework.dao.OptimisticLockingFailureException;
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
     * <p><strong>Delivery.</strong> Each pack's {@code SessionPackExpiryWarningEvent} is published
     * from inside {@code transactionTemplate.execute(...)} (the per-pack transaction that also stamps
     * {@code expiryWarnedAt}), and {@code SessionPackEmailListener.onExpiryWarning} is a
     * {@code @TransactionalEventListener(BEFORE_COMMIT)} that calls
     * {@code notificationOutboxSupport.enqueueEmail(...)}. So on the happy path the outbox row is
     * written in the same transaction as the {@code expiryWarnedAt} stamp — both persist or neither
     * does — and the actual send is the generic notification-outbox drainer's job (retried, not
     * fire-and-forget). <strong>Carve-out:</strong> {@code onExpiryWarning} wraps its body in
     * {@code catch (Exception)} and only logs, so if {@code enqueueEmail} itself throws (e.g. an
     * in-memory serialisation failure building the payload), the transaction still commits
     * {@code expiryWarnedAt} with no outbox row — that pack's warning is then lost and not retried.
     *
     * <p>Before {@code deferred-15}/{@code deferred-92} this method published with no bound
     * transaction while the listener was {@code AFTER_COMMIT} with {@code fallbackExecution = false},
     * so every event was silently dropped and the warning email was sent ZERO times — see those
     * stories. That history is kept here as a lesson, not as a description of current behaviour.
     *
     * <p><strong>Dedupe.</strong> A daily cron over a 14-day window selects the same pack every
     * morning. {@code expiryWarnedAt} is stamped inside the very transaction whose {@code
     * BEFORE_COMMIT} phase enqueues the email, so a delivered warning is always a recorded one.
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
                Boolean warned = transactionTemplate.execute(status -> {
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
                        return false;
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
                    return true;
                });
                // skillars-deferred-120 code review (2026-09-17, Patch #11): logged only after
                // transactionTemplate.execute returns — i.e. after the transaction has actually
                // committed. The prior placement logged success from inside the callback, before
                // commit; a commit-time failure (e.g. the optimistic-lock race below, which Hibernate
                // usually — but not always — detects earlier, at merge time) would have left a
                // "warning sent" INFO line immediately followed by a "skipped" line for the same
                // purchaseId, misrepresenting what actually happened.
                if (Boolean.TRUE.equals(warned)) {
                    log.info("Pack expiry warning sent: purchaseId={} expiresAt={}",
                        pack.getPurchaseId(), pack.getExpiresAt());
                }
            } catch (OptimisticLockingFailureException e) {
                // skillars-deferred-120 AC3 Finding 3: benign and self-correcting, mirroring
                // PaymentPendingSweeper's identical split — a legitimate concurrent write on this
                // pack (most likely ordinary session consumption, pausePack, or extendPack) won the
                // race between this scheduler's batch load and its own save(pack). The pack will
                // either be excluded from future selection or simply re-selected and warned on the
                // next run, still inside the 14-day window. Logging this at ERROR alongside the
                // genuinely unrecoverable cases below would dilute the signal an ERROR here exists
                // to produce — an operator should only see ERROR when something needs reconciling.
                //
                // skillars-deferred-120 code review (2026-09-17, Patch #12): this catch wraps the
                // whole per-pack transaction (coach/user lookups, save, publish), not narrowly
                // save(pack) alone — a version conflict from any entity flushed in this transaction
                // would land here. In practice `pack` (SessionPackPurchase, the only @Version entity
                // touched) is the only realistic source: the two findById calls are pure reads, and
                // the BEFORE_COMMIT-enqueued outbox row is a fresh INSERT with no version to
                // conflict on. Narrowing the try to wrap only save(pack) would need restructuring
                // this method's single-transaction delivery/dedupe guarantee (see this method's own
                // class Javadoc) for a benefit that is theoretical today — left as-is, with the
                // exception attached below so a future case that violates this assumption leaves
                // evidence instead of none.
                log.info("Session pack {} changed concurrently during the expiry-warning sweep — skipped",
                    pack.getPurchaseId(), e);
            } catch (Exception e) {
                log.error("Failed to send expiry warning for session pack purchase {}", pack.getPurchaseId(), e);
            }
        }
    }
}
