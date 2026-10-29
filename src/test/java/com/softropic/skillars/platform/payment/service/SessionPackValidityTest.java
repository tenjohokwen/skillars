package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.ConfigValueType;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigMapper;
import com.softropic.skillars.platform.config.service.ConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * fix/pack-expiry-size-tiers: pack validity comes from the session-count tier, read through a REAL
 * {@link ConfigService} (mocked repository) so the bounded-read fallback is exercised, not stubbed.
 */
@ExtendWith(MockitoExtension.class)
class SessionPackValidityTest {

    @Mock PlatformConfigRepository configRepository;
    @Mock ConfigProperties configProperties;
    @Mock ConfigMapper configMapper;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        lenient().when(configProperties.getCacheTtlSeconds()).thenReturn(300L);
        configService = new ConfigService(configRepository, configProperties, configMapper, new SimpleMeterRegistry());
    }

    private static PlatformConfig row(String key, String value) {
        return Instancio.of(PlatformConfig.class)
            .set(field(PlatformConfig::getKey), key)
            .set(field(PlatformConfig::getValue), value)
            .set(field(PlatformConfig::getValueType), ConfigValueType.LONG)
            .create();
    }

    /** Distinct non-default values per tier, so a wrong tier mapping cannot pass by coincidence. */
    private void seedDistinctTiers() {
        when(configRepository.findAll()).thenReturn(List.of(
            row("pack.expiry.days.tier1", "91"),
            row("pack.expiry.days.tier2", "181"),
            row("pack.expiry.days.tier3", "366"),
            row("pack.expiry.days.tier4", "549")));
        configService.init();
    }

    @ParameterizedTest(name = "{0} session(s) → {1} days")
    @CsvSource({
        "1, 91",
        "2, 181",
        "5, 181",
        "6, 366",
        "10, 366",
        "11, 549",
        "50, 549"
    })
    void eachTierBoundaryReadsItsOwnKey(int sessionCount, long expectedDays) {
        seedDistinctTiers();

        assertThat(SessionPackValidity.days(configService, sessionCount)).isEqualTo(expectedDays);
    }

    @ParameterizedTest(name = "{0} session(s) → {1} days when unseeded")
    @CsvSource({
        "1, 90",
        "2, 180",
        "5, 180",
        "6, 365",
        "10, 365",
        "11, 548"
    })
    void absentConfigFallsBackToTheV139SeedValues(int sessionCount, long expectedDays) {
        when(configRepository.findAll()).thenReturn(List.of());
        configService.init();

        assertThat(SessionPackValidity.days(configService, sessionCount)).isEqualTo(expectedDays);
    }

    @ParameterizedTest(name = "stored ''{0}'' → default")
    @CsvSource({"abc", "0", "-5", "13", "3651"})
    void badStoredValueFallsBackToTheDefaultInsteadOfFailingThePurchase(String stored) {
        when(configRepository.findAll()).thenReturn(List.of(row("pack.expiry.days.tier2", stored)));
        configService.init();

        assertThat(SessionPackValidity.days(configService, 3)).isEqualTo(180L);
    }

    @Test
    void registryDefaultsEqualTheV139Seeds() {
        assertThat(ConfigBounds.PACK_EXPIRY_DAYS_TIER1.defaultValue()).isEqualTo(90L);
        assertThat(ConfigBounds.PACK_EXPIRY_DAYS_TIER2.defaultValue()).isEqualTo(180L);
        assertThat(ConfigBounds.PACK_EXPIRY_DAYS_TIER3.defaultValue()).isEqualTo(365L);
        assertThat(ConfigBounds.PACK_EXPIRY_DAYS_TIER4.defaultValue()).isEqualTo(548L);
        assertThat(ConfigBounds.HAS_CODE_DEFAULT).contains(
            ConfigBounds.PACK_EXPIRY_DAYS_TIER1.key(), ConfigBounds.PACK_EXPIRY_DAYS_TIER2.key(),
            ConfigBounds.PACK_EXPIRY_DAYS_TIER3.key(), ConfigBounds.PACK_EXPIRY_DAYS_TIER4.key());
    }

    /** The floor keeps a fresh pack outside the 14-day expiry-warning / Extend window. */
    @Test
    void floorIsNotBelowTheExtensionWindow() {
        for (ConfigBounds.BoundedKey k : List.of(ConfigBounds.PACK_EXPIRY_DAYS_TIER1, ConfigBounds.PACK_EXPIRY_DAYS_TIER2,
                ConfigBounds.PACK_EXPIRY_DAYS_TIER3, ConfigBounds.PACK_EXPIRY_DAYS_TIER4)) {
            assertThat(k.min()).isGreaterThanOrEqualTo(SessionPackPaymentService.EXTENSION_WINDOW.toDays());
        }
    }
}
