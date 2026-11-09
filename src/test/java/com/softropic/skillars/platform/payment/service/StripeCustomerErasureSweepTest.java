package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-177 (RS03i): every erased payer whose Stripe erasure was never enqueued gets it, one failure at a time. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerErasureSweepTest {

    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock StripeCustomerErasureOutboxSupport erasureOutboxSupport;
    @Mock PlatformTransactionManager transactionManager;

    private StripeCustomerErasureSweep sweep() {
        return new StripeCustomerErasureSweep(stripeCustomerRepository, erasureOutboxSupport,
            new TransactionTemplate(transactionManager));
    }

    @Test
    void schedulesEachMissedErasure_aFailureDoesNotStopTheRest() {
        when(stripeCustomerRepository.findErasedPayersWithoutScheduledErasure(StripeCustomerErasureSweep.BATCH_SIZE))
            .thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("db down")).when(erasureOutboxSupport).scheduleErasure(2L);

        sweep().scheduleMissedErasures();

        verify(erasureOutboxSupport).scheduleErasure(1L);
        verify(erasureOutboxSupport).scheduleErasure(2L);
        verify(erasureOutboxSupport).scheduleErasure(3L);
    }

    @Test
    void nothingMissed_schedulesNothing() {
        when(stripeCustomerRepository.findErasedPayersWithoutScheduledErasure(StripeCustomerErasureSweep.BATCH_SIZE))
            .thenReturn(List.of());

        sweep().scheduleMissedErasures();

        verify(erasureOutboxSupport, never()).scheduleErasure(org.mockito.ArgumentMatchers.any());
    }
}
