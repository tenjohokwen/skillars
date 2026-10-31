package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-19: the Stripe half of GDPR erasure — detach cards, delete the customer, scrub when refused, retry on outage. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerErasureOutboxHandlerTest {

    private static final String CUS = "cus_123";
    private static final String PAYLOAD = "{\"stripeCustomerId\":\"cus_123\"}";

    @Mock StripeClient stripeClient;

    private StripeCustomerErasureOutboxHandler handler() {
        return new StripeCustomerErasureOutboxHandler(stripeClient, new ObjectMapper());
    }

    private static PaymentMethod pm(String id) {
        PaymentMethod pm = new PaymentMethod();
        pm.setId(id);
        return pm;
    }

    private static InvalidRequestException invalid(String code, String message) {
        return new InvalidRequestException(message, "customer", "req_1", code, 400, null);
    }

    private void liveCustomerWithTwoCards() throws Exception {
        when(stripeClient.retrieveCustomer(CUS)).thenReturn(new Customer());
        when(stripeClient.listCustomerPaymentMethods(CUS)).thenReturn(List.of(pm("pm_a"), pm("pm_b")));
    }

    @Test
    void detachesEveryCardThenDeletesTheCustomer() throws Exception {
        liveCustomerWithTwoCards();

        handler().handle(PAYLOAD);

        verify(stripeClient).detachPaymentMethod("pm_a");
        verify(stripeClient).detachPaymentMethod("pm_b");
        verify(stripeClient).deleteCustomer(CUS);
        verify(stripeClient, never()).scrubCustomer(anyString());
    }

    @Test
    void stripeRefusesDelete_scrubsTheCustomerInstead() throws Exception {
        liveCustomerWithTwoCards();
        doThrow(invalid("customer_has_open_balance", "cannot delete")).when(stripeClient).deleteCustomer(CUS);

        assertThatCode(() -> handler().handle(PAYLOAD)).doesNotThrowAnyException();

        verify(stripeClient).detachPaymentMethod("pm_a");
        verify(stripeClient).scrubCustomer(CUS);
    }

    @Test
    void alreadyDeletedCustomer_isANoOp() throws Exception {
        Customer deleted = new Customer();
        deleted.setDeleted(true);
        when(stripeClient.retrieveCustomer(CUS)).thenReturn(deleted);

        handler().handle(PAYLOAD);

        verify(stripeClient, never()).listCustomerPaymentMethods(anyString());
        verify(stripeClient, never()).deleteCustomer(anyString());
    }

    @Test
    void customerMissingAtStripe_isANoOp() throws Exception {
        when(stripeClient.retrieveCustomer(CUS)).thenThrow(invalid("resource_missing", "No such customer"));

        assertThatCode(() -> handler().handle(PAYLOAD)).doesNotThrowAnyException();
        verify(stripeClient, never()).deleteCustomer(anyString());
    }

    @Test
    void cardAlreadyDetachedOnARerun_isTolerated() throws Exception {
        liveCustomerWithTwoCards();
        doThrow(invalid("resource_missing", "gone")).when(stripeClient).detachPaymentMethod("pm_a");

        handler().handle(PAYLOAD);

        verify(stripeClient).detachPaymentMethod("pm_b");
        verify(stripeClient).deleteCustomer(CUS);
    }

    @Test
    void stripeOutage_throwsSoTheOutboxRowIsRetried() throws Exception {
        when(stripeClient.retrieveCustomer(CUS)).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> handler().handle(PAYLOAD)).isInstanceOf(IllegalStateException.class);
        verify(stripeClient, never()).deleteCustomer(anyString());
    }

    @Test
    void outageMidwayThenRetry_completesTheErasure() throws Exception {
        liveCustomerWithTwoCards();
        org.mockito.Mockito.lenient().doThrow(new ApiConnectionException("down")).doNothing()
            .when(stripeClient).detachPaymentMethod("pm_b");

        assertThatThrownBy(() -> handler().handle(PAYLOAD)).isInstanceOf(IllegalStateException.class);
        verify(stripeClient, never()).deleteCustomer(anyString());

        handler().handle(PAYLOAD);

        verify(stripeClient).deleteCustomer(CUS);
    }
}
