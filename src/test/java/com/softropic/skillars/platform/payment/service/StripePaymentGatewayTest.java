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

    /** fix/payment-session-pack-integrity: the pack purchase's per-attempt key is used verbatim — no time bucket. */
    @Test
    void chargeAndCapture_explicitKey_isPassedVerbatim() throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_1"));

        stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT, "pack-1001-attempt-a");

        org.mockito.Mockito.verify(stripeClient)
            .createPaymentIntent(any(PaymentIntentCreateParams.class), org.mockito.ArgumentMatchers.eq("pack-1001-attempt-a"));
    }

    /**
     * fix/payment-session-pack-integrity: the money is captured before last_payment_intent_id is saved. A failing
     * save used to escape before the intent id reached the caller — captured, no pack, no compensating refund.
     */
    @Test
    void chargeAndCapture_lastIntentSaveFails_stillReturnsTheCapturedIntent() throws StripeException {
        stubCoachAndCommission();
        stubStripeCustomer(1001L, "cus_1001");
        when(stripeClient.createPaymentIntent(any(PaymentIntentCreateParams.class), any(String.class)))
            .thenReturn(mockIntent("pi_captured"));
        when(stripeCustomerRepository.save(any())).thenThrow(new org.springframework.dao.QueryTimeoutException("lock timeout"));

        assertThat(stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT, "pack-1001-attempt-b"))
            .isEqualTo("pi_captured");
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
        account.setPayoutsEnabled(true);
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
        account.setPayoutsEnabled(true);
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

        stripePaymentGateway.reverseTransfer("tr_9", new BigDecimal("10.00"), "reversal-tr_9-r1");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient).createTransferReversal(
            org.mockito.ArgumentMatchers.eq("tr_9"),
            any(com.stripe.param.TransferReversalCollectionCreateParams.class), key.capture());
        assertThat(key.getValue()).isEqualTo("reversal-tr_9-r1");
    }

    // --- fix/payment-payouts-disputes-webhooks item 4: an unusable account is a classified HOLD ---------

    @Test
    void transferToCoach_noAccount_isNonRetryableHold_beforeStripe() throws StripeException {
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stripePaymentGateway.transferToCoach(UUID.randomUUID(), COACH_ID, BigDecimal.ONE, "eur"))
            .isInstanceOfSatisfying(com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException.class,
                e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getReason()).isEqualTo("NO_ACCOUNT");
                });
        org.mockito.Mockito.verifyNoInteractions(stripeClient);
    }

    @Test
    void transferToCoach_transfersCapabilityInactive_isNonRetryableHold_evenIfChargesEnabled() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setOnboardingStatus("COMPLETE");
        account.setChargesEnabled(true);
        account.setPayoutsEnabled(true);
        account.setTransfersCapability("inactive");
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> stripePaymentGateway.transferToCoach(UUID.randomUUID(), COACH_ID, BigDecimal.ONE, "eur"))
            .isInstanceOfSatisfying(com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException.class,
                e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getReason()).isEqualTo("ACCOUNT_NOT_READY");
                });
        org.mockito.Mockito.verifyNoInteractions(stripeClient);
    }

    @Test
    void transferToCoach_restrictedLegacyAccount_isNonRetryableHold() {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setOnboardingStatus("RESTRICTED");
        account.setChargesEnabled(true);
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> stripePaymentGateway.transferToCoach(UUID.randomUUID(), COACH_ID, BigDecimal.ONE, "eur"))
            .isInstanceOfSatisfying(com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException.class,
                e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void transferToCoach_activeCapability_isEnoughEvenWithoutChargesEnabled() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setOnboardingStatus("RESTRICTED");
        account.setTransfersCapability("active");
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        com.stripe.model.Transfer tr = new com.stripe.model.Transfer();
        tr.setId("tr_new");
        when(stripeClient.createTransfer(any(com.stripe.param.TransferCreateParams.class), any(String.class)))
            .thenReturn(tr);

        assertThat(stripePaymentGateway.transferToCoach(UUID.randomUUID(), COACH_ID, BigDecimal.ONE, "eur"))
            .isEqualTo("tr_new");
    }

    // --- fix/payment-payouts-disputes-webhooks item 7: adopt a transfer made beyond the 24h key window --

    @Test
    void transferToCoach_existingTransferForGroup_isAdopted_noSecondTransfer() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setTransfersCapability("active");
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        UUID bookingId = UUID.randomUUID();
        com.stripe.model.Transfer other = new com.stripe.model.Transfer();
        other.setId("tr_other_dest");
        other.setDestination("acct_someone_else");
        com.stripe.model.Transfer reversed = new com.stripe.model.Transfer();
        reversed.setId("tr_reversed");
        reversed.setDestination("acct_coach");
        reversed.setReversed(true);
        com.stripe.model.Transfer existing = new com.stripe.model.Transfer();
        existing.setId("tr_existing");
        existing.setDestination("acct_coach");
        existing.setReversed(false);
        when(stripeClient.listTransfersByGroup(bookingId.toString()))
            .thenReturn(java.util.List.of(other, reversed, existing));

        assertThat(stripePaymentGateway.transferToCoach(bookingId, COACH_ID, BigDecimal.ONE, "eur"))
            .isEqualTo("tr_existing");
        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createTransfer(any(com.stripe.param.TransferCreateParams.class), any(String.class));
    }

    @Test
    void transferToCoach_listFails_isRetryable_noBlindCreate() throws StripeException {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setStripeAccountId("acct_coach");
        account.setTransfersCapability("active");
        when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
        when(stripeClient.listTransfersByGroup(any(String.class)))
            .thenThrow(new com.stripe.exception.ApiConnectionException("boom"));

        assertThatThrownBy(() -> stripePaymentGateway.transferToCoach(UUID.randomUUID(), COACH_ID, BigDecimal.ONE, "eur"))
            .isInstanceOfSatisfying(com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException.class,
                e -> assertThat(e.isRetryable()).isTrue());
        org.mockito.Mockito.verify(stripeClient, org.mockito.Mockito.never())
            .createTransfer(any(com.stripe.param.TransferCreateParams.class), any(String.class));
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

    @Test
    void refund_usesTheCallerSuppliedIdempotencyKey() throws StripeException {
        stripePaymentGateway.refund("pi_7", new BigDecimal("12.34"), "cashout-abc");

        ArgumentCaptor<com.stripe.param.RefundCreateParams> params =
            ArgumentCaptor.forClass(com.stripe.param.RefundCreateParams.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(stripeClient).createRefund(params.capture(), key.capture());
        assertThat(key.getValue()).isEqualTo("cashout-abc");
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_7");
        assertThat(params.getValue().getAmount()).isEqualTo(1234L);
    }

    @Test
    void getRefundableAmount_isLatestChargeCapturedMinusRefunded() throws StripeException {
        PaymentIntent intent = mockIntent("pi_8");
        com.stripe.model.Charge charge = new com.stripe.model.Charge();
        charge.setAmountCaptured(5000L);
        charge.setAmountRefunded(1250L);
        intent.setLatestChargeObject(charge);
        when(stripeClient.retrievePaymentIntentWithLatestCharge("pi_8")).thenReturn(intent);

        assertThat(stripePaymentGateway.getRefundableAmount("pi_8")).isEqualByComparingTo("37.50");
    }

    @Test
    void getRefundableAmount_noChargeYet_isZero() throws StripeException {
        when(stripeClient.retrievePaymentIntentWithLatestCharge("pi_9")).thenReturn(mockIntent("pi_9"));

        assertThat(stripePaymentGateway.getRefundableAmount("pi_9")).isEqualByComparingTo("0");
    }

    @Test
    void getRefundableAmount_lookupFails_isNotReportedAsARefundFailure() throws StripeException {
        when(stripeClient.retrievePaymentIntentWithLatestCharge("pi_x"))
            .thenThrow(new com.stripe.exception.ApiConnectionException("down"));

        assertThatThrownBy(() -> stripePaymentGateway.getRefundableAmount("pi_x"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundLookupFailed");
    }

    @Test
    void refund_idempotencyConflict_isOutcomeUnknown_soCreditIsNeverRestoredWhileAnotherAttemptRuns()
            throws StripeException {
        when(stripeClient.createRefund(any(com.stripe.param.RefundCreateParams.class), any(String.class)))
            .thenThrow(new com.stripe.exception.IdempotencyException("key in use", "req_1", "idempotency_key_in_use", 409));

        assertThatThrownBy(() -> stripePaymentGateway.refund("pi_1", new BigDecimal("5.00"), "cashout-k"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundOutcomeUnknown");
    }

    @Test
    void refund_networkFailure_isOutcomeUnknown_notAFailure() throws StripeException {
        when(stripeClient.createRefund(any(com.stripe.param.RefundCreateParams.class), any(String.class)))
            .thenThrow(new com.stripe.exception.ApiConnectionException("timeout"));

        assertThatThrownBy(() -> stripePaymentGateway.refund("pi_1", new BigDecimal("5.00"), "cashout-k"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundOutcomeUnknown");
    }

    @Test
    void refund_rejectedByStripe_isADefiniteFailure() throws StripeException {
        when(stripeClient.createRefund(any(com.stripe.param.RefundCreateParams.class), any(String.class)))
            .thenThrow(new com.stripe.exception.InvalidRequestException(
                "Refund exceeds charge", "amount", "req_1", "amount_too_large", 400, null));

        assertThatThrownBy(() -> stripePaymentGateway.refund("pi_1", new BigDecimal("5.00"), "cashout-k"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.refundFailed");
    }

    private PaymentIntent mockIntent(String id) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        return intent;
    }
}
