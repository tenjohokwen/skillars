package com.softropic.skillars.platform.payment.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentConfigTest {

    @Mock Environment environment;

    @Test
    void refusesToStartWithLiveKeyUnderUatProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"uat"});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("sk_live_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatThrownBy(config::configureStripe)
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("sk_live_");
    }

    @Test
    void refusesToStartWithLiveKeyUnderDevProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("sk_live_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatThrownBy(config::configureStripe)
            .isInstanceOf(AppSetupException.class);
    }

    @Test
    void refusesToStartWithRestrictedLiveKeyUnderUatProfile() {
        // rk_live_ can't complete Connect OAuth, but scoped to PaymentIntents/Refunds it
        // could still move real money — must be blocked the same as sk_live_.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"uat"});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("rk_live_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatThrownBy(config::configureStripe)
            .isInstanceOf(AppSetupException.class);
    }

    @Test
    void allowsTestKeyUnderUatProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"uat"});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("sk_test_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatCode(config::configureStripe).doesNotThrowAnyException();
    }

    @Test
    void allowsRestrictedTestKeyUnderUatProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"uat"});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("rk_test_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatCode(config::configureStripe).doesNotThrowAnyException();
    }

    @Test
    void allowsLiveKeyWhenNoNonProdProfileActive() {
        // Mirrors current production behaviour, which boots with no SPRING_PROFILES_ACTIVE set.
        when(environment.getActiveProfiles()).thenReturn(new String[]{});
        PaymentProperties props = new PaymentProperties();
        props.setApiKey("sk_live_abc123");
        PaymentConfig config = new PaymentConfig(props, environment);

        assertThatCode(config::configureStripe).doesNotThrowAnyException();
    }
}
