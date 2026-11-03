package com.softropic.skillars.platform.payment.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.stripe.Stripe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeE2eApiBaseConfigTest {

    // The bases are JVM-wide statics: put Stripe's own hosts back so no other test in this fork is redirected.
    @AfterEach
    void restoreStripeHosts() {
        Stripe.overrideApiBase(Stripe.LIVE_API_BASE);
        Stripe.overrideConnectBase(Stripe.CONNECT_API_BASE);
        Stripe.overrideUploadBase(Stripe.UPLOAD_API_BASE);
        Stripe.overrideMeterEventsBase(Stripe.METER_EVENTS_API_BASE);
    }

    private static StripeE2eApiBaseConfig config(String baseUrl, String... profiles) {
        PaymentProperties props = new PaymentProperties();
        props.setBaseUrl(baseUrl);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new StripeE2eApiBaseConfig(props, env);
    }

    @Test
    void pointsEveryStripeBaseAtTheConfiguredUrl() {
        config("http://wiremock:8080/", "dev", "e2e").overrideStripeBases();

        assertThat(Stripe.getApiBase()).isEqualTo("http://wiremock:8080");
        assertThat(Stripe.getConnectBase()).isEqualTo("http://wiremock:8080");
        assertThat(Stripe.getUploadBase()).isEqualTo("http://wiremock:8080");
        assertThat(Stripe.getMeterEventsBase()).isEqualTo("http://wiremock:8080");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "wiremock:8080", "/v1", "ftp://wiremock:8080", "http://"})
    void refusesToStartWithoutAnAbsoluteHttpUrl(String baseUrl) {
        assertThatThrownBy(() -> config(baseUrl, "dev", "e2e").overrideStripeBases())
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.payment.stripe.base-url");
        assertThat(Stripe.getApiBase()).isEqualTo(Stripe.LIVE_API_BASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "uat"})
    void refusesToStartAlongsideADeployedProfile(String deployed) {
        assertThatThrownBy(() -> config("http://wiremock:8080", deployed, "e2e").overrideStripeBases())
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining(deployed);
        assertThat(Stripe.getApiBase()).isEqualTo(Stripe.LIVE_API_BASE);
    }
}
