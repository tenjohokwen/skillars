package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.oauth.TokenResponse;
import com.stripe.net.OAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripeOnboardingService {

    private final CoachStripeAccountRepository coachStripeAccountRepository;
    private final PaymentProperties paymentProperties;

    /** Self-reference so @Transactional on upsertCoachStripeAccount is honoured via the Spring proxy. */
    @Autowired
    @Lazy
    private StripeOnboardingService self;

    /** Outside @Transactional — Stripe OAuth call must not run inside a DB transaction. */
    public String generateOnboardingUrl(UUID coachId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("scope", "read_write");
            // oauthCallbackUrl points to the backend callback endpoint, not the frontend page
            params.put("redirect_uri", paymentProperties.getOauthCallbackUrl());
            params.put("state", coachId.toString());
            return OAuth.authorizeUrl(params, null);
        } catch (StripeException e) {
            log.error("[STRIPE_CALL_FAILED coachId={} errorMessage={}]", coachId, e.getMessage(), e);
            throw new PaymentGatewayException("payment.providerUnavailable", e);
        }
    }

    /** OAuth exchange is outside @Transactional; DB upsert runs via self-proxy to honour @Transactional. */
    public void handleOAuthCallback(UUID coachId, String code) {
        TokenResponse token = exchangeOAuthCode(coachId, code);
        String stripeUserId = token.getStripeUserId();
        if (stripeUserId == null || stripeUserId.isBlank()) {
            log.error("[STRIPE_OAUTH_NULL_USER_ID coachId={}]", coachId);
            throw new PaymentGatewayException("payment.providerUnavailable");
        }
        self.upsertCoachStripeAccount(coachId, stripeUserId);
        log.info("[STRIPE_OAUTH_CALLBACK_SUCCESS coachId={} stripeAccountId={}]", coachId, stripeUserId);
    }

    private TokenResponse exchangeOAuthCode(UUID coachId, String code) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            return OAuth.token(params, null);
        } catch (StripeException e) {
            log.error("[STRIPE_CALL_FAILED coachId={} errorMessage={}]", coachId, e.getMessage(), e);
            throw new PaymentGatewayException("payment.providerUnavailable", e);
        }
    }

    /**
     * Must be public so the Spring CGLIB proxy intercepts it and @Transactional is applied.
     * Self-calls from handleOAuthCallback go through self.upsertCoachStripeAccount (not this).
     */
    @Transactional
    public void upsertCoachStripeAccount(UUID coachId, String stripeUserId) {
        coachStripeAccountRepository.findById(coachId).ifPresentOrElse(
            existing -> {
                existing.setStripeAccountId(stripeUserId);
                existing.setOnboardingStatus("PENDING");
                existing.setChargesEnabled(false);
                existing.setPayoutsEnabled(false);
                // dirty-checking persists changes at transaction commit; entity is managed within this TX
            },
            () -> {
                CoachStripeAccount acct = new CoachStripeAccount();
                acct.setCoachId(coachId);
                acct.setStripeAccountId(stripeUserId);
                acct.setOnboardingStatus("PENDING");
                coachStripeAccountRepository.save(acct);
            }
        );
    }

    @Transactional(readOnly = true)
    public CoachStripeStatusResponse getStripeStatus(UUID coachId) {
        return coachStripeAccountRepository.findById(coachId)
            .map(a -> new CoachStripeStatusResponse(
                coachId, a.getOnboardingStatus(), a.isChargesEnabled(), a.isPayoutsEnabled()))
            .orElse(new CoachStripeStatusResponse(coachId, "NOT_CONNECTED", false, false));
    }
}
