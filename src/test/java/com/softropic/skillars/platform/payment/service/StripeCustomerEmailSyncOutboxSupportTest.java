package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.contract.event.AccountEmailChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-175 AC4 (RS03c): an email change enqueues one sync, only for a user with a Stripe customer. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerEmailSyncOutboxSupportTest {

    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock OutboxService outboxService;

    private StripeCustomerEmailSyncOutboxSupport support() {
        return new StripeCustomerEmailSyncOutboxSupport(stripeCustomerRepository, outboxService, new ObjectMapper());
    }

    @Test
    void aUserWithACustomer_enqueuesOneSyncCarryingOnlyTheUserId() {
        when(stripeCustomerRepository.existsById(7L)).thenReturn(true);

        support().onAccountEmailChanged(new AccountEmailChangedEvent(7L));

        verify(outboxService).enqueue(StripeCustomerEmailSyncOutboxSupport.AGGREGATE_TYPE, "{\"userId\":7}");
        verify(outboxService).requestDrainAfterCommit();
    }

    @Test
    void aUserWithoutACustomer_enqueuesNothing() {
        when(stripeCustomerRepository.existsById(7L)).thenReturn(false);

        support().onAccountEmailChanged(new AccountEmailChangedEvent(7L));

        verify(outboxService, never()).enqueue(anyString(), anyString());
    }
}
