package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.contract.event.AccountEmailChangedEvent;
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
    @Mock StripeCustomerErasureOutboxSupport erasureOutboxSupport;

    private StripeCustomerEmailSyncOutboxSupport support() {
        return new StripeCustomerEmailSyncOutboxSupport(stripeCustomerRepository, outboxService, new ObjectMapper(),
            userRepository, new TransactionTemplate(transactionManager), erasureOutboxSupport);
    }

    /** Review round 6: the post-creation check reads the email under FOR SHARE. */
    private void email(String email) {
        when(userRepository.findEmailByIdForShare(7L)).thenReturn(Optional.of(email));
    }

    // skillars-deferred-176 AC2 (RS03e)
    @Test
    void afterCustomerCreation_anEmailChangedMeanwhile_enqueuesOneSync() {
        email("after@example.com");

        org.assertj.core.api.Assertions.assertThat(support().syncIfEmailChangedSince(7L, "before@example.com")).isFalse();

        verify(outboxService).enqueue(StripeCustomerEmailSyncOutboxSupport.AGGREGATE_TYPE, "{\"userId\":7}");
        verify(outboxService).requestDrainAfterCommit();
    }

    // review round 4: an erasure that committed while the customer was created found no row to schedule the Stripe erasure on
    @Test
    void afterCustomerCreation_anErasureMeanwhile_schedulesTheStripeErasure_notASync() {
        email("deleted.7@erased.io");

        // skillars-deferred-177 (RS03h): true, so the caller refuses the purchase or SetupIntent
        org.assertj.core.api.Assertions.assertThat(support().syncIfEmailChangedSince(7L, "before@example.com")).isTrue();

        verify(erasureOutboxSupport).scheduleErasure(7L);
        verify(outboxService, never()).enqueue(anyString(), anyString());
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

    /** skillars-deferred-177 (RS03i): a failed late erasure is not thrown; the sweep schedules it. */
    @Test
    void afterCustomerCreation_aFailedLateErasure_isLoggedNotThrown_andReportsNotErased() {
        email("deleted.7@erased.io");
        doThrow(new IllegalStateException("db down")).when(erasureOutboxSupport).scheduleErasure(7L);

        org.assertj.core.api.Assertions.assertThat(support().syncIfEmailChangedSince(7L, "before@example.com")).isFalse();
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
