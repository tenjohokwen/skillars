package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.contract.event.AccountEmailChangedEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-175 AC4 (RS03c): an email change enqueues one sync, only for a user with a Stripe customer. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerEmailSyncOutboxSupportTest {

    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock OutboxService outboxService;
    @Mock UserRepository userRepository;
    @Mock PlatformTransactionManager transactionManager;

    private StripeCustomerEmailSyncOutboxSupport support() {
        return new StripeCustomerEmailSyncOutboxSupport(stripeCustomerRepository, outboxService, new ObjectMapper(),
            userRepository, new TransactionTemplate(transactionManager));
    }

    private void email(String email) {
        User u = new User();
        u.setEmail(email);
        when(userRepository.findOneById(7L)).thenReturn(Optional.of(u));
    }

    // skillars-deferred-176 AC2 (RS03e)
    @Test
    void afterCustomerCreation_anEmailChangedMeanwhile_enqueuesOneSync() {
        email("after@example.com");

        support().syncIfEmailChangedSince(7L, "before@example.com");

        verify(outboxService).enqueue(StripeCustomerEmailSyncOutboxSupport.AGGREGATE_TYPE, "{\"userId\":7}");
        verify(outboxService).requestDrainAfterCommit();
    }

    @Test
    void afterCustomerCreation_anUnchangedEmail_enqueuesNothing() {
        email("before@example.com");

        support().syncIfEmailChangedSince(7L, "before@example.com");

        verify(outboxService, never()).enqueue(anyString(), anyString());
    }

    @Test
    void afterCustomerCreation_anEnqueueFailure_isLoggedNotThrown() {
        email("after@example.com");
        doThrow(new IllegalStateException("db down")).when(outboxService).enqueue(anyString(), anyString());

        assertThatCode(() -> support().syncIfEmailChangedSince(7L, "before@example.com")).doesNotThrowAnyException();
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
