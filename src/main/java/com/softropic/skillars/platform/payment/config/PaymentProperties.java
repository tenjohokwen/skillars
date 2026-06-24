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
    /** URL the backend sends the coach to after processing the OAuth code (frontend settings page). */
    private String callbackSuccessUrl = "/coach/payment-settings";
}
