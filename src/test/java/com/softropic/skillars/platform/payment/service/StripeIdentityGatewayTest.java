package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;
import com.softropic.skillars.platform.security.contract.AgeVerificationGateway;
import com.softropic.skillars.platform.security.contract.exception.AgeVerificationException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.ApiResource;
import com.stripe.param.identity.VerificationSessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-27: what is (and is not) sent to Stripe Identity, and how a session is resumed. */
@ExtendWith(MockitoExtension.class)
class StripeIdentityGatewayTest {

    @Mock StripeClient stripeClient;

    private PaymentProperties properties;
    private StripeIdentityGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        gateway = new StripeIdentityGateway(stripeClient, properties, new FrontendLinkBuilder("https://app.example.com"));
    }

    private static VerificationSession session(String json) {
        return ApiResource.GSON.fromJson(json, VerificationSession.class);
    }

    @Test
    void isConfigured_followsTheApiKey() {
        assertThat(gateway.isConfigured()).isFalse();
        properties.setApiKey("sk_test_123");
        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void createSession_sendsADocumentCheckWithOnlyTheInternalUserId() throws Exception {
        when(stripeClient.createVerificationSession(any(), eq("key-1"))).thenReturn(session(
            "{\"id\": \"vs_1\", \"url\": \"https://verify.stripe.com/start/x\"}"));

        AgeVerificationGateway.Session s = gateway.createSession(42L, "key-1", "/player/age-verification");

        assertThat(s.sessionId()).isEqualTo("vs_1");
        assertThat(s.url()).isEqualTo("https://verify.stripe.com/start/x");
        ArgumentCaptor<VerificationSessionCreateParams> captor =
            ArgumentCaptor.forClass(VerificationSessionCreateParams.class);
        verify(stripeClient).createVerificationSession(captor.capture(), eq("key-1"));
        VerificationSessionCreateParams p = captor.getValue();
        assertThat(p.getType()).isEqualTo(VerificationSessionCreateParams.Type.DOCUMENT);
        assertThat(p.getClientReferenceId()).isEqualTo("42");
        assertThat(p.getReturnUrl()).isEqualTo("https://app.example.com/#/player/age-verification");
        assertThat(p.getMetadata()).containsOnlyKeys("purpose");
        // Document only: no selfie, no ID number, no provided details (name/e-mail/phone/dob).
        Map<String, Object> wire = p.toMap();
        assertThat(wire).doesNotContainKey("provided_details");
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) ((Map<String, Object>) wire.get("options")).get("document");
        assertThat(document).containsEntry("require_live_capture", true)
            .doesNotContainKeys("require_matching_selfie", "require_id_number");
        assertThat(document.get("allowed_types")).isEqualTo(List.of("driving_license", "id_card", "passport"));
    }

    @Test
    void createSession_whenStripeFails_isReportedAsUnavailable() throws Exception {
        when(stripeClient.createVerificationSession(any(), any())).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> gateway.createSession(42L, "k", "/x"))
            .isInstanceOf(AgeVerificationException.class)
            .satisfies(e -> assertThat(((AgeVerificationException) e).getErrorCode())
                .isEqualTo(AgeVerificationErrorCode.UNAVAILABLE));
    }

    @Test
    void resumeUrl_isOfferedOnlyForAFreshUnsubmittedSession() throws Exception {
        when(stripeClient.retrieveVerificationSession("vs_fresh")).thenReturn(session(
            "{\"id\": \"vs_fresh\", \"status\": \"requires_input\", \"url\": \"https://verify.stripe.com/s\"}"));
        when(stripeClient.retrieveVerificationSession("vs_failed")).thenReturn(session(
            "{\"id\": \"vs_failed\", \"status\": \"requires_input\", \"url\": \"https://verify.stripe.com/s\","
                + " \"last_error\": {\"code\": \"document_unverified_other\"}}"));
        when(stripeClient.retrieveVerificationSession("vs_done")).thenReturn(session(
            "{\"id\": \"vs_done\", \"status\": \"verified\"}"));

        assertThat(gateway.resumeUrl("vs_fresh")).contains("https://verify.stripe.com/s");
        assertThat(gateway.resumeUrl("vs_failed")).isEqualTo(Optional.empty());
        assertThat(gateway.resumeUrl("vs_done")).isEqualTo(Optional.empty());
    }

    @Test
    void resumeUrl_whenStripeFails_isEmptyNotAnError() throws Exception {
        when(stripeClient.retrieveVerificationSession("vs_1")).thenThrow(new ApiConnectionException("down"));

        assertThat(gateway.resumeUrl("vs_1")).isEmpty();
    }
}
