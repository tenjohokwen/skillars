package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-175 AC4 (RS03c): the Stripe customer's email is set to the user's current one, or the row is a no-op. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerEmailSyncOutboxHandlerTest {

    private static final long USER_ID = 42L;
    private static final String CUS = "cus_42";
    private static final String PAYLOAD = "{\"userId\":42}";

    @Mock StripeClient stripeClient;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock UserRepository userRepository;

    private StripeCustomerEmailSyncOutboxHandler handler() {
        return new StripeCustomerEmailSyncOutboxHandler(stripeClient, stripeCustomerRepository, userRepository, new ObjectMapper());
    }

    private void user(String email, boolean locked) {
        User u = new User();
        u.setEmail(email);
        u.setLocked(locked);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(u));
    }

    private void customer() {
        StripeCustomer c = new StripeCustomer();
        ReflectionTestUtils.setField(c, "stripeCustomerId", CUS);
        when(stripeCustomerRepository.findById(USER_ID)).thenReturn(Optional.of(c));
    }

    @Test
    void setsTheCustomersEmailToTheUsersCurrentOne() throws Exception {
        user("now@example.com", false);
        customer();

        handler().handle(PAYLOAD);

        verify(stripeClient).updateCustomerEmail(CUS, "now@example.com");
    }

    @Test
    void aGoneUser_isANoOp() throws Exception {
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.empty());

        handler().handle(PAYLOAD);

        verify(stripeClient, never()).updateCustomerEmail(anyString(), anyString());
    }

    @Test
    void aLockedUser_isANoOp() throws Exception {
        user("now@example.com", true);

        handler().handle(PAYLOAD);

        verify(stripeClient, never()).updateCustomerEmail(anyString(), anyString());
    }

    @Test
    void anErasedUser_isANoOp() throws Exception {
        user("deleted.42@erased.io", false);

        handler().handle(PAYLOAD);

        verify(stripeClient, never()).updateCustomerEmail(anyString(), anyString());
    }

    @Test
    void noCustomerRow_isANoOp() throws Exception {
        user("now@example.com", false);
        when(stripeCustomerRepository.findById(USER_ID)).thenReturn(Optional.empty());

        handler().handle(PAYLOAD);

        verify(stripeClient, never()).updateCustomerEmail(anyString(), anyString());
    }

    @Test
    void aCustomerStripeNoLongerHas_isDone() throws Exception {
        user("now@example.com", false);
        customer();
        doThrow(new InvalidRequestException("No such customer", "id", "req_1", "resource_missing", 404, null))
            .when(stripeClient).updateCustomerEmail(CUS, "now@example.com");

        assertThatCode(() -> handler().handle(PAYLOAD)).doesNotThrowAnyException();
    }

    @Test
    void aStripeOutage_throws_soTheRowIsRetried() throws Exception {
        user("now@example.com", false);
        customer();
        doThrow(new ApiConnectionException("down")).when(stripeClient).updateCustomerEmail(CUS, "now@example.com");

        assertThatThrownBy(() -> handler().handle(PAYLOAD)).isInstanceOf(IllegalStateException.class);
    }
}
