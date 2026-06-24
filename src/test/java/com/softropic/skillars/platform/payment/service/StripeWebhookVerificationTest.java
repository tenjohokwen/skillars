package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.exception.WebhookSignatureException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookVerificationTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests_only";
    private static final UUID COACH_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
    private static final String STRIPE_ACCOUNT_ID = "acct_test123";

    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock StripeWebhookEventRepository webhookEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentProperties paymentProperties;
    StripeWebhookService webhookService;

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        paymentProperties.setWebhookSecret(WEBHOOK_SECRET);
        webhookService = new StripeWebhookService(
            coachStripeAccountRepository, webhookEventRepository, paymentProperties, eventPublisher);
        // Wire the self-reference so @Transactional dispatch works in unit tests without a Spring context
        ReflectionTestUtils.setField(webhookService, "self", webhookService);
    }

    @Test
    void processWebhook_validSignature_insertsEventAndProcesses() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        CoachStripeAccount account = buildAccount(COACH_ID, STRIPE_ACCOUNT_ID, "PENDING", false, false);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(coachStripeAccountRepository.findByStripeAccountId(STRIPE_ACCOUNT_ID))
            .thenReturn(Optional.of(account));

        webhookService.processWebhook(payload, sigHeader);

        verify(webhookEventRepository).insertIfAbsent(any(), any());
        assertThat(account.getOnboardingStatus()).isEqualTo("COMPLETE");
        assertThat(account.isChargesEnabled()).isTrue();
        assertThat(account.isPayoutsEnabled()).isTrue();

        ArgumentCaptor<CoachStripeOnboardingCompleteEvent> captor =
            ArgumentCaptor.forClass(CoachStripeOnboardingCompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().coachId()).isEqualTo(COACH_ID);
    }

    @Test
    void processWebhook_invalidSignature_throwsWebhookSignatureException() {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String badHeader = "t=1234567890,v1=invalidsignaturehex";

        assertThatThrownBy(() -> webhookService.processWebhook(payload, badHeader))
            .isInstanceOf(WebhookSignatureException.class)
            .extracting("errorCode")
            .isEqualTo("payment.webhookSignatureInvalid");

        verify(webhookEventRepository, never()).insertIfAbsent(any(), any());
        verify(coachStripeAccountRepository, never()).findByStripeAccountId(any());
    }

    @Test
    void processWebhook_duplicateEventId_returnsWithoutProcessing() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        webhookService.processWebhook(payload, sigHeader);

        verify(coachStripeAccountRepository, never()).findByStripeAccountId(any());
    }

    @Test
    void processWebhook_accountUpdated_chargesDisabled_transitionsToRestricted() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, false, false);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        CoachStripeAccount account = buildAccount(COACH_ID, STRIPE_ACCOUNT_ID, "COMPLETE", true, true);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(coachStripeAccountRepository.findByStripeAccountId(STRIPE_ACCOUNT_ID))
            .thenReturn(Optional.of(account));

        webhookService.processWebhook(payload, sigHeader);

        assertThat(account.isChargesEnabled()).isFalse();
        assertThat(account.isPayoutsEnabled()).isFalse();
        assertThat(account.getOnboardingStatus()).isEqualTo("RESTRICTED");
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String buildStripeSignature(String secret, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + sig;
    }

    private static String buildAccountUpdatedPayload(String accountId, boolean chargesEnabled, boolean payoutsEnabled) {
        return """
            {
              "id": "evt_test_001",
              "object": "event",
              "type": "account.updated",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "account",
                  "charges_enabled": %b,
                  "payouts_enabled": %b
                }
              }
            }
            """.formatted(accountId, chargesEnabled, payoutsEnabled);
    }

    private static CoachStripeAccount buildAccount(UUID coachId, String stripeAccountId,
                                                    String status, boolean charges, boolean payouts) {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setCoachId(coachId);
        account.setStripeAccountId(stripeAccountId);
        account.setOnboardingStatus(status);
        account.setChargesEnabled(charges);
        account.setPayoutsEnabled(payouts);
        return account;
    }
}
