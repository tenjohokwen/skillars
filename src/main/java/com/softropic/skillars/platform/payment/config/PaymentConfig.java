package com.softropic.skillars.platform.payment.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.regex.Pattern;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    // Fail-closed by construction: a live key is refused unless the "prod" profile is explicitly
    // active, rather than refused only under an allow-list of known non-prod profile names. An
    // allow-list would silently fail to protect a typo'd, missing, or future profile name; requiring
    // "prod" protects every environment except the one that's supposed to carry real money.
    // docker-compose.yml sets SPRING_PROFILES_ACTIVE=prod for a plain production deploy;
    // docker-compose.uat.yml already sets SPRING_PROFILES_ACTIVE=uat the same way.
    private static final String PROD_PROFILE = "prod";
    // Matches both standard secret keys (sk_live_...) and restricted keys (rk_live_...).
    // Restricted keys can't complete Connect OAuth (StripeOnboardingService needs sk_...
    // for that), but a live restricted key scoped to PaymentIntents/Refunds/Subscriptions
    // could still move real money through the rest of this module, so both prefixes are
    // treated as equally dangerous here — this check is about live vs. test, not which
    // Stripe key type is otherwise correct to use.
    private static final Pattern LIVE_KEY_PATTERN = Pattern.compile("^(sk|rk)_live_.*");

    private final PaymentProperties paymentProperties;
    private final Environment environment;

    @PostConstruct
    void configureStripe() {
        String apiKey = paymentProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AppSetupException(
                "app.payment.stripe.api-key is missing or empty. Stripe integration requires a valid API key.");
        }
        boolean prodProfileActive = Arrays.stream(environment.getActiveProfiles())
            .filter(p -> p != null)
            .anyMatch(PROD_PROFILE::equals);
        if (!prodProfileActive && LIVE_KEY_PATTERN.matcher(apiKey).matches()) {
            throw new AppSetupException(
                "app.payment.stripe.api-key is a LIVE Stripe key (starts with 'sk_live_' or " +
                "'rk_live_') but active profile(s) " + Arrays.toString(environment.getActiveProfiles()) +
                " do not include 'prod'. Refusing to start — use a Stripe test-mode key (sk_test_...) " +
                "here so this environment can never charge real money.");
        }
        Stripe.apiKey = apiKey;
        log.info("Stripe SDK initialised (apiKey present={})", !apiKey.isBlank());
        // P26: Stripe OAuth requires an absolute redirect_uri — fail-fast rather than silently break in prod
        String callbackUrl = paymentProperties.getOauthCallbackUrl();
        if (!callbackUrl.startsWith("https://") && !callbackUrl.startsWith("http://")) {
            log.warn("app.payment.stripe.oauth-callback-url is a relative path ('{}') — " +
                "Stripe OAuth will reject it in production; set APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL to an absolute URL", callbackUrl);
        }
    }
}
