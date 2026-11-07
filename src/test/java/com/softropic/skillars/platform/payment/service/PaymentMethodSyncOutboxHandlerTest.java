package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-176 review round 3: the card sync is ordered against the erasure and skips an erased payer. */
@ExtendWith(MockitoExtension.class)
class PaymentMethodSyncOutboxHandlerTest {

    private static final long USER_ID = 42L;
    private static final String CUS = "cus_pm_176";
    private static final String PAYLOAD = "{\"userId\":42,\"paymentMethodId\":\"pm_new\"}";

    @Mock SubscriptionService subscriptionService;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock UserRepository userRepository;

    private PaymentMethodSyncOutboxHandler handler() {
        return new PaymentMethodSyncOutboxHandler(subscriptionService, stripeCustomerRepository, userRepository, new ObjectMapper());
    }

    private void customer() {
        StripeCustomer c = new StripeCustomer();
        ReflectionTestUtils.setField(c, "stripeCustomerId", CUS);
        when(stripeCustomerRepository.findById(USER_ID)).thenReturn(Optional.of(c));
    }

    private void user(String email) {
        User u = new User();
        u.setEmail(email);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(u));
    }

    @Test
    void aLivePayer_isSynced_withTheUserReadUnderTheCustomerOutboxLock() {
        customer();
        user("me@example.com");

        handler().handle(PAYLOAD);

        InOrder order = inOrder(stripeCustomerRepository, userRepository, subscriptionService);
        order.verify(stripeCustomerRepository).acquireCustomerOutboxLock(CUS);
        order.verify(userRepository).findOneById(USER_ID);
        order.verify(subscriptionService).syncPaymentMethodToSubscriptions(USER_ID, "pm_new");
    }

    @Test
    void anErasedPayer_isSkipped() {
        customer();
        user("deleted.42@erased.io");

        handler().handle(PAYLOAD);

        verify(subscriptionService, never()).syncPaymentMethodToSubscriptions(any(), any());
    }

    @Test
    void aGoneUser_isSkipped() {
        customer();
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.empty());

        handler().handle(PAYLOAD);

        verify(subscriptionService, never()).syncPaymentMethodToSubscriptions(any(), any());
    }

    @Test
    void noCustomerRow_takesNoLock_butStillSyncsALivePayer() {
        when(stripeCustomerRepository.findById(USER_ID)).thenReturn(Optional.empty());
        user("me@example.com");

        handler().handle(PAYLOAD);

        verify(stripeCustomerRepository, never()).acquireCustomerOutboxLock(any());
        verify(subscriptionService).syncPaymentMethodToSubscriptions(USER_ID, "pm_new");
    }
}
