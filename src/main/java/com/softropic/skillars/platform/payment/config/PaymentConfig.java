package com.softropic.skillars.platform.payment.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    private final PaymentProperties paymentProperties;

    @PostConstruct
    void configureStripe() {
        Stripe.apiKey = paymentProperties.getApiKey();
        log.info("Stripe SDK initialised (apiKey present={})", !paymentProperties.getApiKey().isBlank());
        // P26: Stripe OAuth requires an absolute redirect_uri — fail-fast rather than silently break in prod
        String callbackUrl = paymentProperties.getOauthCallbackUrl();
        if (!callbackUrl.startsWith("https://") && !callbackUrl.startsWith("http://")) {
            log.warn("app.payment.stripe.oauth-callback-url is a relative path ('{}') — " +
                "Stripe OAuth will reject it in production; set APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL to an absolute URL", callbackUrl);
        }
    }
}
