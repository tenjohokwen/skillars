package com.softropic.skillars.platform.payment.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;

/**
 * Pitchside UI suite only: points every Stripe SDK call at {@code app.payment.stripe.base-url} (the e2e stack's
 * WireMock) instead of Stripe's hosts. That makes the deterministic Stripe tier possible: purchases, refunds and
 * wallet flows run against canned Stripe responses, with no network access and no real test-mode account.
 * <p>
 * {@code StripeClient} and the OAuth calls in {@code StripeOnboardingService} all use the SDK's static
 * configuration, so overriding the four static bases covers them all: the API, Connect OAuth, file uploads and
 * meter events.
 * <p>
 * Fails closed. Startup is refused if the base URL is missing or not an absolute http(s) URL, because the e2e
 * profile must never quietly fall through to the real Stripe API. It is also refused if a deployed profile
 * ({@code prod}, {@code uat}) is active as well, so a stray {@code e2e} in SPRING_PROFILES_ACTIVE can't redirect
 * real payments.
 *
 * @see PaymentConfig for the API key, timeouts and live-key guard, which still apply here
 */
@Slf4j
@Configuration
@Profile(StripeE2eApiBaseConfig.E2E_PROFILE)
@RequiredArgsConstructor
public class StripeE2eApiBaseConfig {

    static final String E2E_PROFILE = "e2e";
    private static final Set<String> DEPLOYED_PROFILES = Set.of("prod", "uat");

    private final PaymentProperties paymentProperties;
    private final Environment environment;

    @PostConstruct
    void overrideStripeBases() {
        String[] active = environment.getActiveProfiles();
        if (Arrays.stream(active).anyMatch(DEPLOYED_PROFILES::contains)) {
            throw new AppSetupException("The 'e2e' profile redirects every Stripe call to a stub, and must never run "
                + "alongside a deployed profile; active profiles: " + Arrays.toString(active));
        }
        String baseUrl = normalise(paymentProperties.getBaseUrl());
        Stripe.overrideApiBase(baseUrl);
        Stripe.overrideConnectBase(baseUrl);
        Stripe.overrideUploadBase(baseUrl);
        Stripe.overrideMeterEventsBase(baseUrl);
        log.warn("e2e profile: every Stripe SDK call goes to {} instead of Stripe", baseUrl);
    }

    private static String normalise(String raw) {
        String value = raw == null ? "" : raw.strip();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null
            || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new AppSetupException("The 'e2e' profile needs app.payment.stripe.base-url set to an absolute "
                + "http(s) URL (the stack's WireMock), so that no Stripe call can reach the real API; got '"
                + value + "'");
        }
        // The SDK appends paths such as /v1/customers itself.
        return value.replaceAll("/+$", "");
    }
}
