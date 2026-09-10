package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.ConfigValueType;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private PlatformConfigRepository configRepository;

    @Mock
    private ConfigProperties configProperties;

    @Mock
    private ConfigMapper configMapper;

    private SimpleMeterRegistry meterRegistry;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        // lenient: the updateConfig write-path tests never read the cache TTL.
        lenient().when(configProperties.getCacheTtlSeconds()).thenReturn(300L);
        meterRegistry = new SimpleMeterRegistry();
        configService = new ConfigService(configRepository, configProperties, configMapper, meterRegistry);
    }

    private PlatformConfig entry(String key, String value, ConfigValueType type) {
        return Instancio.of(PlatformConfig.class)
                .set(field(PlatformConfig::getKey), key)
                .set(field(PlatformConfig::getValue), value)
                .set(field(PlatformConfig::getValueType), type)
                .create();
    }

    @Test
    void getString_returnsValueFromWarmCache() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("some.key", "hello", ConfigValueType.STRING)));
        configService.init();

        String result = configService.getString("some.key");

        assertThat(result).isEqualTo("hello");
        verify(configRepository, times(1)).findAll();
    }

    @Test
    void getString_throwsIllegalStateExceptionForMissingKey() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        assertThatThrownBy(() -> configService.getString("unknown.key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown.key");
    }

    @Test
    void getString_refreshesFromDbWhenCacheStale() {
        when(configProperties.getCacheTtlSeconds()).thenReturn(0L);
        when(configRepository.findAll())
                .thenReturn(List.of(entry("k", "v1", ConfigValueType.STRING)))
                .thenReturn(List.of(entry("k", "v2", ConfigValueType.STRING)));

        configService.init();
        configService.invalidate();

        String result = configService.getString("k");

        assertThat(result).isEqualTo("v2");
        verify(configRepository, times(2)).findAll();
    }

    @Test
    void getLong_parsesNumericString() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("quota.gb", "50", ConfigValueType.LONG)));
        configService.init();

        long result = configService.getLong("quota.gb");

        assertThat(result).isEqualTo(50L);
    }

    @Test
    void getLong_throwsIllegalStateExceptionForMissingKey() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        assertThatThrownBy(() -> configService.getLong("missing.key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.key");
    }

    @Test
    void getLong_throwsIllegalStateExceptionForNonNumericValue() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("bad.value", "not-a-number", ConfigValueType.STRING)));
        configService.init();

        assertThatThrownBy(() -> configService.getLong("bad.value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.value");
    }

    @Test
    void find_returnsEmptyForMissingKey() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        Optional<String> result = configService.find("no.such.key");

        assertThat(result).isEmpty();
    }

    @Test
    void find_returnsPresentForExistingKey() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("present.key", "value", ConfigValueType.STRING)));
        configService.init();

        Optional<String> result = configService.find("present.key");

        assertThat(result).hasValue("value");
    }

    @Test
    void getBoolean_singleArg_returnsFalseForInvalidPresentValue() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("feature.gate", "yes", ConfigValueType.STRING)));
        configService.init();

        boolean result = configService.getBoolean("feature.gate");

        assertThat(result).isFalse();
    }

    @Test
    void getBoolean_withDefault_returnsFalseNotDefaultForInvalidPresentValue() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("feature.gate", "yes", ConfigValueType.STRING)));
        configService.init();

        boolean result = configService.getBoolean("feature.gate", true);

        assertThat(result).isFalse();
    }

    @Test
    void getBoolean_singleArg_missingKey_incrementsMisconfiguredCounterWithMissingReason() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        configService.getBoolean("absent.gate");

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", "absent.gate").tag("reason", "missing").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void getBoolean_singleArg_nonBooleanValue_incrementsMisconfiguredCounterWithNonBooleanReason() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("feature.gate", "yes", ConfigValueType.STRING)));
        configService.init();

        configService.getBoolean("feature.gate");

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", "feature.gate").tag("reason", "non_boolean").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void getBoolean_withDefault_nonBooleanValue_incrementsMisconfiguredCounterWithNonBooleanReason() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("feature.gate", "maybe", ConfigValueType.STRING)));
        configService.init();

        configService.getBoolean("feature.gate", true);

        assertThat(meterRegistry.get("config.value.misconfigured")
                .tag("key", "feature.gate").tag("reason", "non_boolean").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void getBoolean_validValue_doesNotIncrementMisconfiguredCounter() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("feature.gate", "true", ConfigValueType.STRING)));
        configService.init();

        boolean result = configService.getBoolean("feature.gate");

        assertThat(result).isTrue();
        assertThat(meterRegistry.find("config.value.misconfigured").counter()).isNull();
    }

    // ── skillars-deferred-107 AC2: bounded accessors ────────────────────────────────────────────

    @Test
    void getBoundedLong_withDefault_inRange_passesThrough() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "45", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 90L, 1L, 3650L)).isEqualTo(45L);
    }

    @Test
    void getBoundedLong_withDefault_belowMin_fallsBackToDefault() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "0", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 90L, 1L, 3650L)).isEqualTo(90L);
    }

    @Test
    void getBoundedLong_withDefault_aboveMax_fallsBackToDefault() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "999999", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 90L, 1L, 3650L)).isEqualTo(90L);
    }

    @Test
    void getBoundedLong_noDefault_missingKey_throwsIllegalStateException() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        assertThatThrownBy(() -> configService.getBoundedLong("missing.key", 1L, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.key");
    }

    @Test
    void getBoundedLong_noDefault_inRange_passesThrough() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "50", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 1L, 100L)).isEqualTo(50L);
    }

    @Test
    void getBoundedLong_noDefault_belowMin_clampsToMin() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "-3", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 1L, 100L)).isEqualTo(1L);
    }

    @Test
    void getBoundedLong_noDefault_aboveMax_clampsToMax() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "5000", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong("k", 1L, 100L)).isEqualTo(100L);
    }

    @Test
    void getLong_trimsWhitespacePaddedValue() {
        // skillars-deferred-107 code review: getLong(key) must trim like getLong(key, default) and
        // the startup assertion — otherwise a "  60  " value passes boot validation then throws here.
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "  60  ", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getLong("k")).isEqualTo(60L);
        assertThat(configService.getBoundedLong("k", 1L, 100L)).isEqualTo(60L);
    }

    // ── skillars-deferred-107 code review: PUT /api/config write-path range validation ──────────

    @Test
    void updateConfig_boundedKeyOutOfRange_rejectedWith400() {
        when(configRepository.findByKey("pack.pause.maxDays"))
                .thenReturn(Optional.of(entry("pack.pause.maxDays", "90", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("pack.pause.maxDays", "0"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("pack.pause.maxDays");
        verify(configRepository, never()).save(any());
    }

    @Test
    void updateConfig_boundedKeyNonNumeric_rejectedWith400() {
        when(configRepository.findByKey("booking.batch.maxSize"))
                .thenReturn(Optional.of(entry("booking.batch.maxSize", "5", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("booking.batch.maxSize", "lots"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(configRepository, never()).save(any());
    }

    @Test
    void updateConfig_boundedKeyInRange_persists() {
        PlatformConfig existing = entry("pack.pause.maxDays", "90", ConfigValueType.LONG);
        when(configRepository.findByKey("pack.pause.maxDays")).thenReturn(Optional.of(existing));

        configService.updateConfig("pack.pause.maxDays", "45");

        verify(configRepository).save(existing);
        assertThat(existing.getValue()).isEqualTo("45");
    }

    @Test
    void updateConfig_unboundedKey_notRangeChecked() {
        PlatformConfig existing = entry("some.free.text.key", "old", ConfigValueType.STRING);
        when(configRepository.findByKey("some.free.text.key")).thenReturn(Optional.of(existing));

        configService.updateConfig("some.free.text.key", "anything-goes");

        verify(configRepository).save(existing);
        assertThat(existing.getValue()).isEqualTo("anything-goes");
    }

    @Test
    void getBoundedInt_inRange_passesThrough() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "14", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedInt("k", 24, 1, 600)).isEqualTo(14);
    }

    @Test
    void getBoundedInt_belowMin_fallsBackToDefault() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "0", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedInt("k", 24, 1, 600)).isEqualTo(24);
    }

    @Test
    void getBoundedInt_aboveMax_fallsBackToDefault() {
        when(configRepository.findAll()).thenReturn(List.of(entry("k", "5000", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedInt("k", 24, 1, 600)).isEqualTo(24);
    }

    @Test
    void getBoundedInt_absentKey_fallsBackToDefault() {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        assertThat(configService.getBoundedInt("no.such.key", 24, 1, 600)).isEqualTo(24);
    }

    @Test
    void invalidate_forcesRefreshOnNextGet() {
        when(configRepository.findAll())
                .thenReturn(List.of(entry("k", "old", ConfigValueType.STRING)))
                .thenReturn(List.of(entry("k", "new", ConfigValueType.STRING)));
        when(configProperties.getCacheTtlSeconds()).thenReturn(300L);

        configService.init();
        configService.invalidate();

        String result = configService.getString("k");

        assertThat(result).isEqualTo("new");
        verify(configRepository, times(2)).findAll();
    }
}
