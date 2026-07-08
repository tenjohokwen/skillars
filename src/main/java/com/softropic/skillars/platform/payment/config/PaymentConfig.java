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
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    // Profiles that must never be able to move real money, even by accident (e.g. a
    // copy-pasted .env). Deliberately opt-in on these rather than opt-out on "not prod":
    // production today boots with no SPRING_PROFILES_ACTIVE set at all (a pre-existing,
    // separately-tracked gap), so an opt-out check would fail-close on real production traffic.
    private static final Set<String> NON_PROD_PROFILES = Set.of("dev", "uat", "test");
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
        boolean nonProdProfileActive = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(NON_PROD_PROFILES::contains);
        if (nonProdProfileActive && LIVE_KEY_PATTERN.matcher(apiKey).matches()) {
            throw new AppSetupException(
                "app.payment.stripe.api-key is a LIVE Stripe key (starts with 'sk_live_' or " +
                "'rk_live_') but active profile(s) " + Arrays.toString(environment.getActiveProfiles()) +
                " indicate a non-production environment. Refusing to start — use a Stripe test-mode " +
                "key (sk_test_...) here so this environment can never charge real money.");
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
