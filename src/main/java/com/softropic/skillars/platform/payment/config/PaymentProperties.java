package com.softropic.skillars.platform.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.payment.stripe")
public class PaymentProperties {
    private String apiKey = "";
    private String webhookSecret = "";
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
}
