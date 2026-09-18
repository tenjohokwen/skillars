package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigStartupAssertionTest {

    @Mock
    private ConfigService configService;

    @Mock
    private Environment env;

    // skillars-deferred-123 code review 2026-09-18 (Decision 4): the scheduler-lock ordering check
    // reads @SchedulerLock annotations off the live context. A mock with no bean definitions makes it
    // a no-op for every pre-existing test here, which keeps those tests testing only what they were
    // written to test; the dedicated tests below supply real beans.
    @Mock
    private ApplicationContext applicationContext;

    private SimpleMeterRegistry meterRegistry;
    private ConfigStartupAssertion assertion;

    private static final ApplicationReadyEvent EVENT = mock(ApplicationReadyEvent.class);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        assertion = new ConfigStartupAssertion(configService, meterRegistry, env, applicationContext);
        lenient().when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[] {});
        lenient().when(env.resolvePlaceholders(anyString()))
            .thenAnswer(inv -> inv.getArgument(0, String.class));
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
    void videoQuotaProKey_negative_noThrow_metricIncremented() {
        // skillars-deferred-109 AC10: video.quota.pro.storageBytes / .bandwidthBytesMonthly (and the
        // semiPro pair) are now generated into ConfigBounds.ALL. They are failFast=false, so an
        // out-of-range value is ERROR + metric only, NOT a boot refusal.
        String key = "video.quota.pro.storageBytes";
        assertThat(ConfigBounds.ALL.stream().anyMatch(k -> k.key().equals(key)))
                .as("video.quota.pro.storageBytes is a bounded key")
                .isTrue();
        when(configService.find(key)).thenReturn(Optional.of("-1"));

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        var counter = meterRegistry.get("config.value.misconfigured")
                .tag("key", key).tag("reason", "out_of_range").counter();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).hasSize(2);
    }

    // ── skillars-deferred-122 AC4: visibilityThreshold > suspensionThreshold cross-field check ──

    @Test
    void thresholdOrderingViolation_nonDev_throwsAppSetupExceptionNamingBothKeys() {
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(5L);
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(10L);

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY)
            .hasMessageContaining(ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY);
    }

    @Test
    void thresholdOrderingViolation_devProfile_noThrowButErrorMetricStillFires() {
        when(env.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(5L);
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(10L);

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.get("config.value.misconfigured")
            .tag("key", "reliability.strike.threshold_ordering")
            .tag("reason", "cross_field_ordering")
            .counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void thresholdOrderingEqualValues_doesNotFlag() {
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(5L);
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(5L);

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.find("config.value.misconfigured")
            .tag("key", "reliability.strike.threshold_ordering")
            .counter())
            .isNull();
    }

    @Test
    void thresholdOrderingVisibilityBelowSuspension_doesNotFlag() {
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(5L);
        when(configService.getBoundedLong(eq(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY), anyLong(), anyLong(), anyLong()))
            .thenReturn(3L);

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.find("config.value.misconfigured")
            .tag("key", "reliability.strike.threshold_ordering")
            .counter())
            .isNull();
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

    // ---------------------------------------------------------------------------------------------
    // skillars-deferred-123 code review 2026-09-18 (Decision 4): @SchedulerLock pair ordering.
    //
    // Deliberately overrides AC4's "no boot-time check" decision — see assertSchedulerLockOrdering's
    // Javadoc for why the failure mode (ShedLock throws per invocation, Spring swallows it, the job
    // silently never runs again) clears this class's existing failFast bar.
    //
    // These fixtures exercise the mechanism. The real shipped defaults are covered for free by every
    // @SpringBootTest in the suite: ApplicationReadyEvent fires there against the real context, so an
    // inverted default on any actual scheduler would fail every IT at boot rather than in production.
    // ---------------------------------------------------------------------------------------------

    static class InvertedLockFixture {
        @SchedulerLock(name = "InvertedLockFixture_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT15M")
        public void run() { }
    }

    static class ValidLockFixture {
        @SchedulerLock(name = "ValidLockFixture_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
        public void run() { }
    }

    static class ShorthandLockFixture {
        // ShedLock's own converter accepts Spring shorthand as well as ISO-8601; so must this check.
        @SchedulerLock(name = "ShorthandLockFixture_run", lockAtMostFor = "10m", lockAtLeastFor = "20m")
        public void run() { }
    }

    static class UnparseableLockFixture {
        @SchedulerLock(name = "UnparseableLockFixture_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30")
        public void run() { }
    }

    static class PropertyLockFixture {
        @SchedulerLock(name = "PropertyLockFixture_run",
                       lockAtMostFor = "PT10M",
                       lockAtLeastFor = "${some.scheduler.lock-at-least:PT30S}")
        public void run() { }
    }

    private void withSchedulerBean(Class<?> fixture) {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[] {"fixture"});
        when(applicationContext.getType("fixture")).thenAnswer(inv -> fixture);
    }

    @Test
    void schedulerLock_lockAtLeastForAboveLockAtMostFor_blocksBootAndIncrementsMetric() {
        withSchedulerBean(InvertedLockFixture.class);

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("InvertedLockFixture_run")
            .hasMessageContaining("PT15M")
            .hasMessageContaining("PT10M");

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", "scheduler.lock.InvertedLockFixture_run")
                .tag("reason", "lock_at_least_exceeds_at_most").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void schedulerLock_validPair_doesNotBlockBoot() {
        withSchedulerBean(ValidLockFixture.class);

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();

        assertThat(meterRegistry.find("config.value.misconfigured")
            .tag("key", "scheduler.lock.ValidLockFixture_run").counter()).isNull();
    }

    @Test
    void schedulerLock_springShorthandDurations_areParsedAndCompared() {
        withSchedulerBean(ShorthandLockFixture.class);

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("ShorthandLockFixture_run");
    }

    @Test
    void schedulerLock_unparseableDuration_blocksBoot() {
        withSchedulerBean(UnparseableLockFixture.class);

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("UnparseableLockFixture_run");

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", "scheduler.lock.UnparseableLockFixture_run")
                .tag("reason", "unparseable_duration").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void schedulerLock_propertyOverrideInvertingThePair_blocksBoot() {
        // The whole point of AC4 is that this value is now operator-settable, so the check must see
        // the RESOLVED value, not the raw ${...} expression.
        withSchedulerBean(PropertyLockFixture.class);
        when(env.resolvePlaceholders("${some.scheduler.lock-at-least:PT30S}")).thenReturn("PT20M");

        assertThatThrownBy(() -> assertion.onApplicationEvent(EVENT))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("PropertyLockFixture_run")
            .hasMessageContaining("PT20M");
    }

    @Test
    void schedulerLock_propertyOverrideLeftAtItsDefault_doesNotBlockBoot() {
        withSchedulerBean(PropertyLockFixture.class);
        when(env.resolvePlaceholders("${some.scheduler.lock-at-least:PT30S}")).thenReturn("PT30S");

        assertThatCode(() -> assertion.onApplicationEvent(EVENT)).doesNotThrowAnyException();
    }
}
