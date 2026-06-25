package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionChangeApplicator {

    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 0 2 * * *")
    public void applyPendingChanges() {
        log.info("[SUBSCRIPTION_CHANGE_APPLICATOR] Running scheduled downgrade application");
        subscriptionService.applyPendingChanges();
    }
}
