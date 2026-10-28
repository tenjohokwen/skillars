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

    @GetMapping("/balance")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<CreditBalanceResponse> getBalance() {
        // skillars-deferred-141 code review: was getCurrentCoachUserId(), which is role-agnostic
        // despite its name (it just parses the principal's businessId) but reads as coach-specific —
        // a latent trap now that /balance serves PARENT and PLAYER. requireCurrentUserId() is the
        // behaviourally identical, correctly named accessor, and is what BookingResource already
        // uses for this same self-or-parent pattern (BookingResource.currentParentId()).
        Long parentId = securityUtil.requireCurrentUserId();
        return ResponseEntity.ok(new CreditBalanceResponse(
            creditWalletService.getBalance(parentId), "EUR"));
    }

    /**
     * feat/coach-workflow-gaps A22: pre-submit cost preview for a multi-session (batch) request — per-session
     * price, total and the € wallet credit / card split, computed by the same rule the batch settlement uses.
     * Scoped to the caller's own wallet; coachId only selects public pricing. sessionCount is bounded like
     * CreateBatchRequest.slots (1..10).
     */
    @GetMapping("/batch-quote")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
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
     * Stays PARENT-only: the UAT.5 story (skillars-uat-5-player-self-booking.md, "Explicitly out of scope")
     * keeps the credit wallet's cash-out a parent capability; a self-booking player can read their balance and
     * statement (feat/coach-workflow-gaps A23) but not cash out.
     */
    @PostMapping("/cashout")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> cashOut(@Valid @RequestBody CashOutRequest request) {
        // Same accessor rename as getBalance() above; this endpoint stays PARENT-only.
        Long parentId = securityUtil.requireCurrentUserId();
        cashOutService.processCashOut(parentId, request.amount());
        return ResponseEntity.noContent().build();
    }
}
