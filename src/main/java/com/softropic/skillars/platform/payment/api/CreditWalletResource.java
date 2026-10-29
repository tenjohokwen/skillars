package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.contract.BatchPaymentQuoteDto;
import com.softropic.skillars.platform.payment.contract.CashOutRequest;
import com.softropic.skillars.platform.payment.contract.CreditBalanceResponse;
import com.softropic.skillars.platform.payment.service.BatchPaymentQuoteService;
import com.softropic.skillars.platform.payment.service.CashOutService;
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payment/credits")
@RequiredArgsConstructor
@Observed(name = "payment.credits")
public class CreditWalletResource {

    private final CreditWalletService creditWalletService;
    private final CashOutService cashOutService;
    private final SecurityUtil securityUtil;
    private final BatchPaymentQuoteService batchPaymentQuoteService;

    /**
     * fix/player-wallet-cashout: the credit wallet's gate — a PARENT, or a self-registered adult PLAYER (own
     * profile, no parent). The wallet is keyed by the caller's own user id, and a self-booking player's bookings
     * and packs carry that id as the payer, so their refunds land here; a PLAYER login that is not such a payer
     * (no self-owned profile — e.g. one belonging to a parent) is refused by {@code PlayerOwnershipGuard}.
     * Shared with {@code RevenueResource}'s credit statement.
     */
    static final String WALLET_OWNER = "(" + SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE + ")"
        + " and @playerOwnershipGuard.isWalletOwner(authentication)";

    @GetMapping("/balance")
    @PreAuthorize(WALLET_OWNER)
    public ResponseEntity<CreditBalanceResponse> getBalance() {
        // skillars-deferred-141 code review: was getCurrentCoachUserId(), which is role-agnostic
        // despite its name (it just parses the principal's businessId) but reads as coach-specific —
        // a latent trap now that /balance serves PARENT and PLAYER. requireCurrentUserId() is the
        // behaviourally identical, correctly named accessor, and is what BookingResource already
        // uses for this same self-or-parent pattern (BookingResource.currentParentId()).
        Long parentId = securityUtil.requireCurrentUserId();
        // fix/payment-wallet-dispute-integrity: with the cashable / spend-only split.
        CreditWalletService.WalletBalance wallet = creditWalletService.getWalletBalance(parentId);
        return ResponseEntity.ok(new CreditBalanceResponse(
            wallet.total(), "EUR", wallet.cashable(), wallet.spendOnly()));
    }

    /**
     * feat/coach-workflow-gaps A22: pre-submit cost preview for a multi-session (batch) request — per-session
     * price, total and the € wallet credit / card split, computed by the same rule the batch settlement uses.
     * Scoped to the caller's own wallet; coachId only selects public pricing. sessionCount is bounded like
     * CreateBatchRequest.slots (1..10).
     */
    @GetMapping("/batch-quote")
    @PreAuthorize(WALLET_OWNER)
    public ResponseEntity<BatchPaymentQuoteDto> getBatchQuote(
            @RequestParam UUID coachId,
            @RequestParam int sessionCount) {
        if (sessionCount < 1 || sessionCount > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionCount must be between 1 and 10");
        }
        Long payerId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(batchPaymentQuoteService.quote(payerId, coachId, sessionCount));
    }

    /**
     * fix/player-wallet-cashout: widened from PARENT-only to {@link #WALLET_OWNER}. A self-registered adult
     * player pays for their own sessions and packs, so the cashable refund credit in their wallet is theirs to
     * cash out under exactly the parent's rules — {@link CashOutService} is payer-agnostic: the fee, the refund
     * onto a funding / latest card charge, the open-dispute block, the spend-only exclusion and the
     * reconciliation record all key on the caller's user id. (Previously UAT.5 kept cash-out parent-only,
     * leaving a self-booking player's refunds stranded in the wallet.)
     */
    @PostMapping("/cashout")
    @PreAuthorize(WALLET_OWNER)
    public ResponseEntity<Void> cashOut(@Valid @RequestBody CashOutRequest request) {
        // The payer's own user id: a parent, or a self-registered adult player (same accessor as getBalance()).
        Long parentId = securityUtil.requireCurrentUserId();
        cashOutService.processCashOut(parentId, request.amount());
        return ResponseEntity.noContent().build();
    }
}
