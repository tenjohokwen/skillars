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
 * configuration, so overriding the four static bases (API, Connect, file uploads, meter events) covers every
 * server-side Stripe call.
 * <p>
 * One side effect is in the browser, not the backend. {@code OAuth.authorizeUrl} builds the Connect onboarding
 * link from the Connect base, so under this profile the coach is sent to {@code <base-url>/oauth/authorize}. A
 * browser outside the Docker network can't resolve the stack's {@code wiremock} host. A spec that follows that
 * redirect must intercept it, as the plan's PAY-12 already asserts only that the redirect starts. Leaving the
 * Connect base alone wouldn't help: the link would point at connect.stripe.com, which the suite's network gate
 * blocks anyway, and the {@code OAuth.token} exchange would then reach the real Stripe.
 * <p>
 * Startup is refused if the base URL is missing or not an absolute http(s) URL, so the e2e profile can never
 * quietly fall through to the real Stripe API.
 * <p>
 * It is also refused alongside {@code prod} or {@code uat}. That check is a deny-list, so it can't stop a future
 * deployed profile with another name. Two things limit the damage there:
 * <ul>
 *   <li>{@link PaymentConfig} refuses a live key under any profile except {@code prod}, so the only key this
 *   redirect can ever carry is a test-mode one;</li>
 *   <li>the redirect needs both {@code e2e} and an explicit {@code app.payment.stripe.base-url}. No deployed
 *   configuration sets either: the URL lives only in the suite's mounted overlay.</li>
 * </ul>
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
