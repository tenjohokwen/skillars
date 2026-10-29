package com.softropic.skillars.platform.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.payment.stripe")
public class PaymentProperties {
    private String apiKey = "";
    /** Signing secret of the platform-account webhook endpoint ({@code /api/payment/webhooks/stripe}). */
    private String webhookSecret = "";
    /**
     * Signing secret of the separate Connect webhook endpoint ({@code /api/payment/webhooks/stripe/connect},
     * "events on connected accounts"). Stripe delivers a connected account's {@code account.updated}
     * only there, and gives that endpoint its own {@code whsec_} secret.
     */
    private String connectWebhookSecret = "";
    private String oauthClientId = "";
    /** URL Stripe redirects to after OAuth authorisation — must be the backend callback endpoint. */
    private String oauthCallbackUrl = "/api/payment/coaches/me/stripe/callback";
    /**
     * Where the backend sends the coach after processing the OAuth code: a FRONTEND ROUTE path
     * (resolved against {@code app.frontend-url} with the hash router's {@code #/} by
     * FrontendLinkBuilder), or an absolute http(s) URL used verbatim.
     */
    private String callbackSuccessUrl = "/coach/payment-settings";
    /** Stripe.js publishable key, safe to expose to authenticated frontend clients. */
    private String publishableKey = "";
    /**
     * fix/payment-charge-idempotency (L2): Stripe SDK timeouts and network retries, set explicitly instead of
     * the SDK defaults (30s connect, 80s read). A booking settlement holds the payer's wallet lock and a DB
     * connection across the charge call, so its worst case — (connect + read) x (1 + retries) — must stay
     * short. Retries are safe: every money-moving call carries an idempotency key.
     */
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 30_000;
    private int maxNetworkRetries = 2;
}
