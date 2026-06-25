package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.contract.CashOutRequest;
import com.softropic.skillars.platform.payment.contract.CreditBalanceResponse;
import com.softropic.skillars.platform.payment.service.CashOutService;
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment/credits")
@RequiredArgsConstructor
@Observed(name = "payment.credits")
public class CreditWalletResource {

    private final CreditWalletService creditWalletService;
    private final CashOutService cashOutService;
    private final SecurityUtil securityUtil;

    @GetMapping("/balance")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<CreditBalanceResponse> getBalance() {
        Long parentId = securityUtil.getCurrentCoachUserId();
        return ResponseEntity.ok(new CreditBalanceResponse(
            creditWalletService.getBalance(parentId), "EUR"));
    }

    @PostMapping("/cashout")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<Void> cashOut(@Valid @RequestBody CashOutRequest request) {
        Long parentId = securityUtil.getCurrentCoachUserId();
        cashOutService.processCashOut(parentId, request.amount());
        return ResponseEntity.noContent().build();
    }
}
