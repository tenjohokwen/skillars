package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripePaymentGatewayTest {

    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock ConfigService configService;
    @Mock StripeClient stripeClient;

    @InjectMocks StripePaymentGateway stripePaymentGateway;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID PACK_TIER_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    private void stubCoachAndCommission() {
        // skillars-deferred-106: chargeAndCapture no longer reads platform.commission.rate — the rate
        // is stamped onto booking_payments.commission_rate at capture by BookingPaymentPersistenceService.
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_test");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        when(configService.getString("platform.payment.currency")).thenReturn("eur");
    }

    private void stubStripeCustomer(Long parentId, String stripeCustomerId) {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(parentId);
        customer.setStripeCustomerId(stripeCustomerId);
        customer.setStripePaymentMethodId("pm_test");
        when(stripeCustomerRepository.findById(parentId)).thenReturn(Optional.of(customer));
    }

    @Test
    void chargeAndCapture_passesIdempotencyKeyDerivedFromReferenceIdAndParentId() throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient)
            .createPaymentIntent(any(PaymentIntentCreateParams.class), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("pi-" + PACK_TIER_ID + "-1001-");
    }

    @Test
    void chargeAndCapture_passesConfiguredCurrencyToStripe() throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        // Deliberately NOT "eur" — that was the old hardcoded literal. Stubbing a distinctive value
        // proves the currency is actually read from config rather than still hardcoded: a reversion to
        // .setCurrency("eur") would fail this assertion instead of coincidentally passing it.
        when(configService.getString("platform.payment.currency")).thenReturn("usd");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
            ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(stripeClient)
            .createPaymentIntent(paramsCaptor.capture(), any(String.class));
        assertThat(paramsCaptor.getValue().getCurrency()).isEqualTo("usd");
    }

    @Test
    void chargeAndCapture_repeatedWithinWindow_producesSameIdempotencyKey() throws StripeException {
        // The idempotency key must dedupe a true retry (e.g. a duplicate event/network resend
        // of the same purchase attempt) — proven here by freezing the clock across two calls
        // that would otherwise be indistinguishable from two separate purchases.
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2026-08-14T10:00:10Z"), ZoneOffset.UTC));
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);
        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.times(2))
            .createPaymentIntent(any(PaymentIntentCreateParams.class), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0)).isEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void chargeAndCapture_samePackTierAndParent_separatePurchaseWindow_producesDifferentIdempotencyKey()
            throws StripeException {
        // The pack-purchase collision the review found: referenceId (packTierId) and parentId
        // alone repeat across two genuinely separate purchases of the same tier by the same
        // parent. A key built from only those two would replay the first PaymentIntent instead
        // of charging the second purchase. The time bucket must break that collision once the
        // two attempts are far enough apart to not be the same retry.
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC));
        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_2"));
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2026-08-14T10:00:00Z").plus(Duration.ofDays(1)), ZoneOffset.UTC));
        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.times(2))
            .createPaymentIntent(any(PaymentIntentCreateParams.class), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void chargeAndCapture_sameReferenceId_differentParents_producesDifferentIdempotencyKeys()
            throws StripeException {
        // SessionPackPaymentService.purchasePack passes the same packTierId (referenceId) for
        // every parent buying that tier — the key must not collide across parents.
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));
        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        stubStripeCustomer(2002L, "cus_2002");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_2"));
        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 2002L, COACH_ID, AMOUNT);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.times(2))
            .createPaymentIntent(any(PaymentIntentCreateParams.class), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void chargeAndCapture_missingCurrencyConfig_throwsPaymentGatewayException() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_test");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        IllegalStateException configError =
            new IllegalStateException("Missing platform config key: platform.payment.currency");
        when(configService.getString("platform.payment.currency")).thenThrow(configError);

        assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                .isEqualTo("payment.configurationUnavailable"))
            .satisfies(e -> assertThat(e.getCause()).isSameAs(configError));

        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class));
    }

    // --- skillars-deferred-90 AC6: ISO-4217 validation of platform.payment.currency ---------------

    private void stubCoachAndCommissionOnly() {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_test");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
    }

    @Test
    void chargeAndCapture_currencyConfigWithWhitespaceAndCase_isNormalisedForStripe() throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(configService.getString("platform.payment.currency")).thenReturn("  EUR ");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
            ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(stripeClient)
            .createPaymentIntent(paramsCaptor.capture(), any(String.class));
        assertThat(paramsCaptor.getValue().getCurrency()).isEqualTo("eur");
    }

    @Test
    void chargeAndCapture_unknownCurrencyCode_throwsPaymentGatewayException_notRaw500() throws StripeException {
        stubCoachAndCommissionOnly();
        when(configService.getString("platform.payment.currency")).thenReturn("US Dollars");

        assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                .isEqualTo("payment.configurationUnavailable"))
            .satisfies(e -> assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class));

        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class));
    }

    @Test
    void chargeAndCapture_blankCurrencyConfig_throwsPaymentGatewayException() throws StripeException {
        stubCoachAndCommissionOnly();
        when(configService.getString("platform.payment.currency")).thenReturn("   ");

        assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                .isEqualTo("payment.configurationUnavailable"));

        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class));
    }

    @Test
    void chargeAndCapture_nullCurrencyConfig_throwsPaymentGatewayException() throws StripeException {
        stubCoachAndCommissionOnly();
        when(configService.getString("platform.payment.currency")).thenReturn(null);

        assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                .isEqualTo("payment.configurationUnavailable"));

        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class));
    }

    // --- skillars-deferred-106: B-1 separate charges & transfers ----------------------------------

    @Test
    void chargeAndCapture_chargesPlatformAccount_noTransferDataNoAppFee_carriesTransferGroup()
            throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
            ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        org.mockito.Mockito.verify(stripeClient).createPaymentIntent(captor.capture(), any(String.class));
        PaymentIntentCreateParams params = captor.getValue();
        assertThat(params.getTransferData()).as("no destination charge under B-1").isNull();
        assertThat(params.getApplicationFeeAmount()).as("commission retained implicitly, not as app fee").isNull();
        assertThat(params.getTransferGroup()).isEqualTo(PACK_TIER_ID.toString());
    }

    @Test
    void chargeAndCapture_coachStripeAccountNotComplete_failsFastBeforeStripe() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_test");
        account.setOnboardingStatus("PENDING");
        account.setChargesEnabled(false);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
            .isInstanceOf(PaymentGatewayException.class)
            .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                .isEqualTo("payment.coachStripeNotConfigured"));

        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class));
    }

    @Test
    void transferToCoach_buildsExpectedParamsAndDeterministicKey() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        com.stripe.model.Transfer tr = new com.stripe.model.Transfer();
        tr.setId("tr_1");
        when(stripeClient.createTransfer(any(com.stripe.param.TransferCreateParams.class), any(String.class)))
            .thenReturn(tr);
        UUID bookingId = UUID.randomUUID();

        String id = stripePaymentGateway.transferToCoach(bookingId, COACH_ID, new BigDecimal("46.00"), "eur");

        assertThat(id).isEqualTo("tr_1");
        ArgumentCaptor<com.stripe.param.TransferCreateParams> pc =
            ArgumentCaptor.forClass(com.stripe.param.TransferCreateParams.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient).createTransfer(pc.capture(), key.capture());
        assertThat(key.getValue()).isEqualTo("transfer-" + bookingId);
        assertThat(pc.getValue().getAmount()).isEqualTo(4600L);
        assertThat(pc.getValue().getCurrency()).isEqualTo("eur");
        assertThat(pc.getValue().getDestination()).isEqualTo("acct_coach");
        assertThat(pc.getValue().getTransferGroup()).isEqualTo(bookingId.toString());
    }

    @Test
    void transferToCoach_stripeException_mapsToClassifiedCoachPayoutTransferException() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        when(stripeClient.createTransfer(any(com.stripe.param.TransferCreateParams.class), any(String.class)))
            .thenThrow(new com.stripe.exception.ApiConnectionException("boom"));
        UUID bookingId = UUID.randomUUID();

        assertThatThrownBy(() -> stripePaymentGateway.transferToCoach(bookingId, COACH_ID, new BigDecimal("1.00"), "eur"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException.class)
            .satisfies(e -> assertThat(((com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException) e)
                .isRetryable()).isTrue());
    }

    @Test
    void reverseTransfer_usesDeterministicReversalKey() throws StripeException {
        when(stripeClient.createTransferReversal(any(String.class),
                any(com.stripe.param.TransferReversalCollectionCreateParams.class), any(String.class)))
            .thenReturn(new com.stripe.model.TransferReversal());

        stripePaymentGateway.reverseTransfer("tr_9", new BigDecimal("10.00"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient).createTransferReversal(
            org.mockito.ArgumentMatchers.eq("tr_9"),
            any(com.stripe.param.TransferReversalCollectionCreateParams.class), key.capture());
        assertThat(key.getValue()).isEqualTo("reversal-tr_9");
    }

    @Test
    void createStripeCustomer_tagsMetadataWithUserId() throws StripeException {
        Customer customer = new Customer();
        customer.setId("cus_new");
        when(stripeClient.createCustomer(any(CustomerCreateParams.class))).thenReturn(customer);

        stripePaymentGateway.createStripeCustomer(1001L);

        ArgumentCaptor<CustomerCreateParams> paramsCaptor = ArgumentCaptor.forClass(CustomerCreateParams.class);
        org.mockito.Mockito.verify(stripeClient).createCustomer(paramsCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) paramsCaptor.getValue().getMetadata();
        assertThat(metadata).containsExactly(org.assertj.core.api.Assertions.entry("userId", "1001"));
    }

    private PaymentIntent mockIntent(String id) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        return intent;
    }
}
