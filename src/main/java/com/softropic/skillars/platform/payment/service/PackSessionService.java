package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExhaustedEvent;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PackSessionService {

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void deductSession(UUID purchaseId) {
        SessionPackPurchase purchase = sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound"));

        if (purchase.getRemainingSessions() <= 0) {
            throw new PaymentGatewayException("payment.packExhausted");
        }

        purchase.setRemainingSessions(purchase.getRemainingSessions() - 1);
        sessionPackPurchaseRepository.save(purchase);

        if (purchase.getRemainingSessions() == 0) {
            log.info("Session pack exhausted: purchaseId={} parentId={}", purchaseId, purchase.getParentId());
            // Reuse existing event from booking.contract — do not create a duplicate
            eventPublisher.publishEvent(new SessionPackExhaustedEvent(
                this, purchaseId, purchase.getParentId(), purchase.getCoachId()));
        }
    }

    @Transactional
    public void restoreSession(UUID purchaseId) {
        SessionPackPurchase purchase = sessionPackPurchaseRepository.findByIdForUpdate(purchaseId)
            .orElseThrow(() -> new PaymentGatewayException("payment.packNotFound"));
        purchase.setRemainingSessions(purchase.getRemainingSessions() + 1);
        sessionPackPurchaseRepository.save(purchase);
        log.info("Session restored to pack: purchaseId={}", purchaseId);
    }
}
