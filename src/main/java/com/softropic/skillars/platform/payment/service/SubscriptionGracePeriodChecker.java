package com.softropic.skillars.platform.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionGracePeriodChecker {

    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 0 3 * * *")
    public void checkGracePeriods() {
        log.info("[SUBSCRIPTION_GRACE_PERIOD_CHECKER] Running PAST_DUE grace period check");
        subscriptionService.checkPastDueGracePeriod();
    }
}
