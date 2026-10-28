package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutFreezeRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * feat/coach-payouts-pack-and-credit (DECISIONS.md item 2): while a coach is under a CSAM safety hold,
 * none of their payouts may be released; an admin reinstatement makes them releasable again. Normal
 * (admin or strike) suspensions do NOT freeze payouts — only the safety-hold trigger below does.
 *
 * <p><strong>Three gates, one source of truth</strong> ({@code payment.coach_payout_freezes}):
 * <ol>
 *   <li>{@link #onAccountSuspensionRequested} — the CSAM match: activates the freeze and flips the
 *       coach's {@code PENDING_RELEASE} payouts to {@code FROZEN};</li>
 *   <li>{@code CoachPayoutOutboxSupport.enqueuePayout} — a session completing while the hold stands is
 *       recorded {@code FROZEN} directly;</li>
 *   <li>{@code CoachPayoutTransferHandler} — the release-time check under the payout row lock, which
 *       also catches a row the freeze UPDATE could not see yet (inserted by a completion that had not
 *       committed). This is the gate the money actually passes through.</li>
 * </ol>
 * A transfer that already fired before the hold landed is not clawed back here; that is the admin's
 * call (dispute/reversal tooling), and racing it is inherent — it would have been released a moment
 * earlier anyway.
 *
 * <p>Listens to the security module's {@link AccountSuspensionRequestedEvent} directly rather than to
 * the admin-side {@code CoachSafetySuspensionListener}: the freeze must hold even when the coach's
 * profile was already SUSPENDED by an admin (that listener then no-ops), and it keeps payouts out of
 * the admin module's suspension code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachPayoutFreezeService {

    /** {@code coach_payouts.last_error} marker for a frozen payout, and the freeze reason prefix. */
    public static final String REASON_SAFETY_HOLD = "SAFETY_HOLD";

    private final CoachPayoutFreezeRepository freezeRepository;
    private final CoachPayoutRepository coachPayoutRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNew;

    @PostConstruct
    void initTemplate() {
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The CSAM match. {@code AFTER_COMMIT} + {@code fallbackExecution}: the publisher
     * ({@code ModerationOrchestrationService}) holds no transaction today, so this runs inline — and the
     * multicaster stops at the first listener that throws, so this never throws and runs right after the
     * (also never-throwing, {@code HIGHEST_PRECEDENCE}) admin profile hold, ahead of the account
     * suspension listener. Own {@code REQUIRES_NEW} transaction; idempotent (a re-delivered event finds
     * the freeze active and no {@code PENDING_RELEASE} rows left).
     *
     * <p>Only an owner id that is a UUID naming an existing coach profile is acted on — a player
     * upload's owner is a numeric {@code PlayerProfile} id, and players have no payouts.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountSuspensionRequested(AccountSuspensionRequestedEvent event) {
        UUID coachId = parseUuid(event.ownerId());
        if (coachId == null) {
            return;
        }
        try {
            Integer frozen = requiresNew.execute(s -> freeze(coachId, event.videoId()));
            if (frozen != null && frozen >= 0) {
                log.error("[COACH_PAYOUT_FREEZE] coach={} videoId={} — payouts frozen for a safety hold, "
                    + "{} pending payout(s) moved to FROZEN", coachId, event.videoId(), frozen);
            }
        } catch (RuntimeException e) {
            // The release-time check in CoachPayoutTransferHandler reads the freeze row; if even that write
            // failed, an operator must freeze by hand before the hold window elapses.
            log.error("CRITICAL: [COACH_PAYOUT_FREEZE_FAILED] coach={} videoId={} — payouts NOT frozen after a "
                + "CSAM match; freeze them by hand (payment.coach_payout_freezes) before any release",
                coachId, event.videoId(), e);
        }
    }

    /** @return payouts moved to FROZEN, or -1 when the owner is not a coach. */
    private int freeze(UUID coachId, UUID videoId) {
        CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);
        if (coach == null) {
            return -1;
        }
        if (coach.getStatus() != CoachProfileStatus.SUSPENDED) {
            // The admin-side profile hold (CoachSafetySuspensionListener, which runs first) did not take.
            // The freeze is applied regardless — money must not reach the account behind a CSAM match —
            // but its only lift path is CoachReinstatedEvent, which reinstateCoach does not publish for an
            // ACTIVE coach. Say how to recover without SQL.
            log.error("CRITICAL: [COACH_PAYOUT_FREEZE] coach={} is under a safety-hold payout freeze but its profile "
                + "is {} — suspend the coach from the admin console; reinstating them afterwards lifts the freeze",
                coachId, coach.getStatus());
        }
        // Payout rows first, freeze row second: the same order as CoachPayoutTransferHandler (payout row
        // lock, then FOR SHARE on the freeze row), so a hold landing during a drain cannot deadlock with it.
        // A row this UPDATE misses (inserted by a completion not yet committed) is caught by the handler's
        // release-time check once the freeze row below commits.
        int frozen = coachPayoutRepository.freezePendingForCoach(coachId);
        freezeRepository.activate(coachId, REASON_SAFETY_HOLD + " video=" + videoId, Instant.now());
        return frozen;
    }

    /**
     * Admin reinstatement lifts the freeze, in the reinstatement's own transaction
     * ({@code BEFORE_COMMIT}): the lift, the {@code FROZEN -> PENDING_RELEASE} flips and their outbox
     * messages commit atomically with the coach going back to ACTIVE, or not at all. Each unfrozen payout
     * is re-enqueued not before its original {@code release_after}, so a payout frozen inside its hold
     * window still waits the hold out. (No {@code @Transactional} here: Spring rejects it on a
     * {@code @TransactionalEventListener} unless REQUIRES_NEW, and BEFORE_COMMIT already runs inside the
     * publisher's transaction.) A reinstatement of a coach who was never frozen (a normal
     * suspension) finds nothing to do.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void liftOnReinstatement(CoachReinstatedEvent event) {
        UUID coachId = event.getCoachId();
        Instant now = Instant.now();
        // Freeze row FIRST: this UPDATE waits for every writer holding it FOR SHARE (a completion recording
        // a FROZEN payout, a drain freezing one) to commit, so the FROZEN select below — a new statement,
        // a new READ COMMITTED snapshot — sees their rows.
        int lifted = freezeRepository.lift(coachId, event.getAdminId(), now);
        List<CoachPayout> frozen = coachPayoutRepository.findFrozenByCoachIdForUpdate(coachId);
        for (CoachPayout row : frozen) {
            row.setStatus(CoachPayoutStatus.PENDING_RELEASE);
            row.setLastError(null);
            coachPayoutRepository.save(row);
            Instant notBefore = row.getReleaseAfter() != null && row.getReleaseAfter().isAfter(now)
                ? row.getReleaseAfter() : now;
            coachPayoutOutboxSupport.enqueueTransfer(row.getBookingId(), notBefore);
        }
        if (lifted > 0 || !frozen.isEmpty()) {
            log.warn("[COACH_PAYOUT_UNFREEZE] coach={} adminId={} — safety-hold payout freeze lifted, {} payout(s) "
                + "re-enqueued for release", coachId, event.getAdminId(), frozen.size());
        }
    }

    private static UUID parseUuid(String ownerId) {
        if (ownerId == null) {
            return null;
        }
        try {
            return UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
