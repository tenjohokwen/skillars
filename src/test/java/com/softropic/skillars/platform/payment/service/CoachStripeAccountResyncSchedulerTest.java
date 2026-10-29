package com.softropic.skillars.platform.payment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/** fix/prod-readiness-gaps: the daily job re-reads COMPLETE accounts within its page budget. */
@ExtendWith(MockitoExtension.class)
class CoachStripeAccountResyncSchedulerTest {

    @Mock StripeOnboardingService stripeOnboardingService;

    @Test
    void resyncCompleteAccounts_delegatesWithThePageBudget() {
        new CoachStripeAccountResyncScheduler(stripeOnboardingService).resyncCompleteAccounts();

        verify(stripeOnboardingService).resyncCompleteAccounts(
            CoachStripeAccountResyncScheduler.PAGE_SIZE, CoachStripeAccountResyncScheduler.MAX_PAGES);
    }
}
