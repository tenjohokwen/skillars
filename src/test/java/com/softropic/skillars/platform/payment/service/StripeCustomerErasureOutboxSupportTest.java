package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-19: the erasure-transaction half — local card id nulled, linkage kept, Stripe cleanup enqueued. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerErasureOutboxSupportTest {

    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock OutboxService outboxService;

    private StripeCustomerErasureOutboxSupport support() {
        return new StripeCustomerErasureOutboxSupport(stripeCustomerRepository, outboxService, new ObjectMapper());
    }

    @Test
    void nullsTheSavedCardKeepsTheLinkageAndEnqueuesTheStripeCleanup() {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(7L);
        customer.setStripeCustomerId("cus_123");
        customer.setStripePaymentMethodId("pm_abc");
        customer.setLastPaymentIntentId("pi_1");
        when(stripeCustomerRepository.findById(7L)).thenReturn(Optional.of(customer));

        support().scheduleErasure(7L);

        assertThat(customer.getStripePaymentMethodId()).isNull();
        assertThat(customer.getStripeCustomerId()).isEqualTo("cus_123");
        assertThat(customer.getLastPaymentIntentId()).isEqualTo("pi_1");
        verify(stripeCustomerRepository).save(customer);
        verify(outboxService).enqueue(eq(StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE),
            eq("{\"stripeCustomerId\":\"cus_123\"}"));
        verify(outboxService).requestDrainAfterCommit();
    }

    @Test
    void userWithoutStripeCustomer_enqueuesNothing() {
        when(stripeCustomerRepository.findById(8L)).thenReturn(Optional.empty());

        support().scheduleErasure(8L);

        verify(outboxService, never()).enqueue(anyString(), anyString());
        verify(stripeCustomerRepository, never()).save(any());
    }
}
