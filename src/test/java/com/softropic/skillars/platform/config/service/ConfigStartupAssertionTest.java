package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigStartupAssertionTest {

    @Mock
    private ConfigService configService;

    @Mock
    private Environment env;

    private SimpleMeterRegistry meterRegistry;
    private ConfigStartupAssertion assertion;

    private static final ApplicationReadyEvent EVENT = mock(ApplicationReadyEvent.class);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        assertion = new ConfigStartupAssertion(configService, meterRegistry, env);
        // Default: every bounded key holds an in-range value (100 is inside every ConfigBounds range).
        lenient().when(configService.find(anyString())).thenReturn(Optional.of("100"));
        lenient().when(env.getActiveProfiles()).thenReturn(new String[] {});
    }

    private BoundedKey aFailFastKey() {
        return ConfigBounds.ALL.stream().filter(BoundedKey::failFast).findFirst().orElseThrow();
    }

    private BoundedKey aFailFastKeyWithoutCodeDefault() {
        return ConfigBounds.ALL.stream()
                .filter(BoundedKey::failFast)
                .filter(k -> !ConfigBounds.HAS_CODE_DEFAULT.contains(k.key()))
                .findFirst().orElseThrow();
    }

    private BoundedKey aNonFailFastKey() {
        return ConfigBounds.ALL.stream()
                .filter(k -> !k.failFast())
                .filter(k -> !ConfigBounds.HAS_CODE_DEFAULT.contains(k.key()))
                .findFirst().orElseThrow();
    }

    private BoundedKey aKeyWithCodeDefault() {
        return ConfigBounds.ALL.stream()
                .filter(k -> ConfigBounds.HAS_CODE_DEFAULT.contains(k.key()))
                .findFirst().orElseThrow();
    }

    @Test
    void allKeysInRange_noThrow_noErrorMetric() {
        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();
        assertThat(meterRegistry.find("config.value.misconfigured").counter()).isNull();
    }

    @Test
    void failFastKeyOutOfRange_nonDev_throwsAppSetupExceptionNamingKeyAndRange() {
        BoundedKey k = aFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.of("0"));

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
                .isInstanceOf(AppSetupException.class)
                .hasMessageContaining(k.key())
                .hasMessageContaining("[" + k.min() + ", " + k.max() + "]");
    }

    @Test
    void twoFailFastKeysOutOfRange_messageNamesBoth() {
        var failFast = ConfigBounds.ALL.stream().filter(BoundedKey::failFast).limit(2).toList();
        BoundedKey a = failFast.get(0);
        BoundedKey b = failFast.get(1);
        when(configService.find(a.key())).thenReturn(Optional.of("0"));
        when(configService.find(b.key())).thenReturn(Optional.of("0"));

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
                .isInstanceOf(AppSetupException.class)
                .hasMessageContaining(a.key())
                .hasMessageContaining(b.key());
    }

    @Test
    void nonFailFastKeyOutOfRange_noThrow_metricIncrementedWithKeyAndReasonTagsOnly() {
        BoundedKey k = aNonFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.of("0"));

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        // Same {key, reason} tag scheme ConfigService uses — no third "expected" tag, which would
        // make PrometheusMeterRegistry reject the second registration of this counter name.
        var counter = meterRegistry.get("config.value.misconfigured")
                .tag("key", k.key()).tag("reason", "out_of_range").counter();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).hasSize(2);
    }

    @Test
    void failFastKeyNonNumeric_nonDev_throwsAppSetupException() {
        BoundedKey k = aFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.of("abc"));

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
                .isInstanceOf(AppSetupException.class)
                .hasMessageContaining(k.key());
        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", k.key()).tag("reason", "non_numeric").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void failFastKeyWithoutCodeDefault_absent_nonDev_throwsAppSetupException() {
        BoundedKey k = aFailFastKeyWithoutCodeDefault();
        when(configService.find(k.key())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
                .isInstanceOf(AppSetupException.class)
                .hasMessageContaining(k.key());
    }

    @Test
    void absentKeyWithCodeDefault_noThrow_noMetric() {
        // A 2-arg call site — the code default covers an absent value, e.g. an unseeded key like
        // platform.development.radar_composite_dlq.max_attempts — must not spam ERROR/metric on boot.
        BoundedKey k = aKeyWithCodeDefault();
        when(configService.find(k.key())).thenReturn(Optional.empty());

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.find("config.value.misconfigured")
                .tag("key", k.key()).counter()).isNull();
    }

    @Test
    void failFastKeyOutOfRange_devProfile_noThrowButErrorMetricStillFires() {
        when(env.getActiveProfiles()).thenReturn(new String[] {"dev"});
        BoundedKey k = aFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.of("0"));

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", k.key()).tag("reason", "out_of_range").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void absentKey_noThrow_metricIncrementedWithMissingReason() {
        BoundedKey k = aNonFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.empty());

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", k.key()).tag("reason", "missing").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void nonNumericValue_noThrow_metricIncrementedWithNonNumericReason() {
        BoundedKey k = aNonFailFastKey();
        when(configService.find(k.key())).thenReturn(Optional.of("not-a-number"));

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", k.key()).tag("reason", "non_numeric").counter().count())
                .isEqualTo(1.0);
    }
}
