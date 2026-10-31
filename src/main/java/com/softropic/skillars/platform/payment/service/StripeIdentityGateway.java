package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;
import com.softropic.skillars.platform.security.contract.AgeVerificationGateway;
import com.softropic.skillars.platform.security.contract.exception.AgeVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * S-27: Stripe Identity implementation of the security module's {@link AgeVerificationGateway}.
 *
 * <p>Data minimisation: a document check only (no selfie, no ID number), and the only identifier sent is the internal
 * numeric user id. No name, e-mail, phone or declared date of birth leaves the platform; Stripe reads the date of
 * birth off the document itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeIdentityGateway implements AgeVerificationGateway {

    private final StripeClient stripeClient;
    private final PaymentProperties paymentProperties;
    private final FrontendLinkBuilder frontendLinks;

    @Override
    public boolean isConfigured() {
        String apiKey = paymentProperties.getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Session createSession(long userId, String idempotencyKey, String returnPath) {
        VerificationSessionCreateParams params = VerificationSessionCreateParams.builder()
            .setType(VerificationSessionCreateParams.Type.DOCUMENT)
            .setOptions(VerificationSessionCreateParams.Options.builder()
                .setDocument(VerificationSessionCreateParams.Options.Document.builder()
                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.DRIVING_LICENSE)
                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.ID_CARD)
                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.PASSPORT)
                    .setRequireLiveCapture(true)
                    .build())
                .build())
            .setClientReferenceId(String.valueOf(userId))
            .putMetadata("purpose", "age_verification")
            .setReturnUrl(frontendLinks.route(returnPath))
            .build();
        try {
            VerificationSession session = stripeClient.createVerificationSession(params, idempotencyKey);
            return new Session(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.error("[AGE_VERIFICATION_STRIPE_CREATE_FAILED userId={} errorMessage={}]", userId, e.getMessage(), e);
            throw new AgeVerificationException(AgeVerificationErrorCode.UNAVAILABLE,
                "Age verification is temporarily unavailable");
        }
    }

    @Override
    public Optional<String> resumeUrl(String sessionId) {
        try {
            VerificationSession session = stripeClient.retrieveVerificationSession(sessionId);
            // A fresh, unsubmitted session is "requires_input" with no last_error; a failed one carries the error.
            if ("requires_input".equals(session.getStatus()) && session.getLastError() == null
                    && session.getUrl() != null) {
                return Optional.of(session.getUrl());
            }
        } catch (StripeException e) {
            log.warn("[AGE_VERIFICATION_STRIPE_RESUME_FAILED sessionId={} errorMessage={}]", sessionId, e.getMessage());
        }
        return Optional.empty();
    }
}
