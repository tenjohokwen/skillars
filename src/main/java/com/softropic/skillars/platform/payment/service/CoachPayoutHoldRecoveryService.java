package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.security.AdminTierGuard;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * fix/payment-payout-recovery: puts a coach's {@code HOLD} payouts back on the release path once the
 * coach's Stripe account can receive transfers again. A HOLD used to have no automatic or in-app way out
 * (only hand-written production SQL, runbook {@code COACH_PAYOUT_HELD}), so every payout whose hold ended
 * while Stripe reviewed the coach's KYC documents, or in the minutes after an OAuth reconnect, stayed parked.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #onAccountReady} — {@link CoachStripeOnboardingCompleteEvent}, published after commit by
 *       {@code CoachStripeAccountStatusApplier} for an {@code account.updated} webhook or the onboarding
 *       fallback sync that leaves the account COMPLETE (or its {@code transfers} capability active). Acts only
 *       when {@link StripePaymentGateway#transferReadiness} says READY;</li>
 *   <li>{@link #redriveHeld} with {@code requireReady=false} — the admin "release held payouts" action
 *       ({@code POST /api/admin/payment/coaches/{coachId}/payouts/release-held}); a still-unready account just
 *       HOLDs (or retries, if Stripe is still deciding) again at transfer time.</li>
 * </ul>
 *
 * <p>Each payout is re-driven in its own {@code REQUIRES_NEW} transaction (one bad row never blocks the rest;
 * no lock is held across rows), read in booking-id pages. Under the row lock it is re-checked HOLD, then put
 * through {@link CoachPayoutAdjustmentService#releaseIfUnfrozen} — the one path that re-checks every freeze
 * reason (an in-app or card dispute flagged while it sat in HOLD, a safety hold) and enqueues exactly one
 * transfer, not before its original {@code release_after}. Lock order is the transfer handler's (payout row,
 * then the safety-hold freeze row FOR SHARE). A {@code TRANSFER_*_MISMATCH} HOLD (a Stripe transfer already
 * exists for a different amount or account) is never re-driven: re-driving it would only HOLD again, and it
 * needs an operator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachPayoutHoldRecoveryService {

    static final int PAGE_SIZE = 100;
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final CoachPayoutRepository coachPayoutRepository;
    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final CoachPayoutAdjustmentService coachPayoutAdjustmentService;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNew;

    @PostConstruct
    void initTemplate() {
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a re-drive did. */
    public record Result(int released, int stillFrozen, int skipped) {
        public int total() {
            return released + stillFrozen + skipped;
        }
    }

    /**
     * Runs after the account-status write committed (the publisher's {@code afterCommit}), so it needs its own
     * transactions (REQUIRES_NEW, per row). Never throws: the publisher is a webhook / status sync that has
     * already committed, and a failure here must not turn into a Stripe redelivery or a 500.
     */
    @EventListener
    public void onAccountReady(CoachStripeOnboardingCompleteEvent event) {
        try {
            Result r = redriveHeld(event.coachId(), true, "ACCOUNT_READY");
            if (r.total() > 0) {
                log.warn("[COACH_PAYOUT_HOLD_RECOVERY] coach={} — account can receive transfers again: {} held "
                    + "payout(s) re-enqueued, {} still frozen, {} left for an operator", event.coachId(),
                    r.released(), r.stillFrozen(), r.skipped());
            }
        } catch (RuntimeException e) {
            log.error("[COACH_PAYOUT_HOLD_RECOVERY] coach={} — automatic HOLD re-drive failed; an admin can re-drive "
                + "with POST /api/admin/payment/coaches/{}/payouts/release-held", event.coachId(), event.coachId(), e);
        }
    }

    /**
     * Re-drives the coach's HOLD payouts.
     *
     * @param requireReady only act when the coach's account can receive transfers now (the automatic path)
     * @param trigger      log tag
     */
    public Result redriveHeld(UUID coachId, boolean requireReady, String trigger) {
        AdminTierGuard.requireFullAdmin(); // S-28: not for ROLE_LTD_ADMIN
        if (requireReady && StripePaymentGateway.transferReadiness(
                coachStripeAccountRepository.findById(coachId).orElse(null)) != StripePaymentGateway.TransferReadiness.READY) {
            return new Result(0, 0, 0);
        }
        int released = 0;
        int stillFrozen = 0;
        int skipped = 0;
        UUID after = MIN_UUID;
        while (true) {
            List<UUID> ids = coachPayoutRepository.findHoldIdsByCoachAfter(coachId, after, PAGE_SIZE);
            for (UUID bookingId : ids) {
                String outcome;
                try {
                    outcome = requiresNew.execute(s -> redriveOne(bookingId, trigger));
                } catch (RuntimeException e) {
                    log.error("[COACH_PAYOUT_HOLD_RECOVERY] booking={} coach={} — re-drive failed, left in HOLD",
                        bookingId, coachId, e);
                    outcome = "FAILED";
                }
                if (CoachPayoutStatus.PENDING_RELEASE.equals(outcome)) {
                    released++;
                } else if (CoachPayoutStatus.FROZEN.equals(outcome)) {
                    stillFrozen++;
                } else {
                    skipped++;
                }
            }
            if (ids.size() < PAGE_SIZE) {
                break;
            }
            after = ids.get(ids.size() - 1);
        }
        return new Result(released, stillFrozen, skipped);
    }

    /** @return the payout's status after the re-drive, or null when it was left alone */
    private String redriveOne(UUID bookingId, String trigger) {
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(bookingId).orElse(null);
        if (row == null || !CoachPayoutStatus.HOLD.equals(row.getStatus())) {
            return null; // released or changed by someone else meanwhile
        }
        String lastError = row.getLastError() == null ? "" : row.getLastError();
        if (lastError.startsWith(StripePaymentGateway.REASON_AMOUNT_MISMATCH)
                || lastError.startsWith(StripePaymentGateway.REASON_DESTINATION_MISMATCH)) {
            log.warn("[COACH_PAYOUT_HOLD_RECOVERY] booking={} coach={} — HOLD for {} is left for an operator "
                + "(runbook: COACH_PAYOUT_HELD)", bookingId, row.getCoachId(), lastError);
            return null;
        }
        // FROZEN + releaseIfUnfrozen: PENDING_RELEASE and one transfer message when nothing freezes it, else
        // FROZEN with the reason that still does (its own resolution then releases it).
        row.setStatus(CoachPayoutStatus.FROZEN);
        coachPayoutAdjustmentService.releaseIfUnfrozen(row);
        log.info("[COACH_PAYOUT_HOLD_RECOVERY] booking={} coach={} trigger={} — HOLD ({}) re-driven, now {}",
            bookingId, row.getCoachId(), trigger, lastError, row.getStatus());
        return row.getStatus();
    }
}
