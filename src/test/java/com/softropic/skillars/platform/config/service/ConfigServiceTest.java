package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.AdminConfigEntryDto;
import com.softropic.skillars.platform.config.contract.ConfigValueType;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
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

    // ── skillars-deferred-145 D3: PUT-time cross-field guard for the review eligibility window ────
    // The guard resolves the OTHER key via readStoredBoundedInt, which reads the row directly
    // (round-2 R2 — see that method's javadoc for why a cached read was wrong). These three tests
    // leave the partner key's row unstubbed, so findByKey returns empty and the lookup falls back to
    // that key's coded default (7 for minSessionAgeDays, 30 for updateCooldownDays) exactly as a real
    // unseeded key would; each picks a value for the key it writes that violates (or satisfies) the
    // ordering against that default. The stored-partner path is pinned separately below.

    @Test
    void updateConfig_reviewMinSessionAgeDaysNotLessThanCooldownDefault_rejectedWith400() {
        when(configRepository.findByKey("reviews.minSessionAgeDays"))
                .thenReturn(Optional.of(entry("reviews.minSessionAgeDays", "7", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("reviews.minSessionAgeDays", "30"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("reviews.minSessionAgeDays")
                .hasMessageContaining("reviews.updateCooldownDays");
        verify(configRepository, never()).save(any());
    }

    @Test
    void updateConfig_reviewUpdateCooldownDaysNotGreaterThanMinAgeDefault_rejectedWith400() {
        when(configRepository.findByKey("reviews.updateCooldownDays"))
                .thenReturn(Optional.of(entry("reviews.updateCooldownDays", "30", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("reviews.updateCooldownDays", "7"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("reviews.minSessionAgeDays")
                .hasMessageContaining("reviews.updateCooldownDays");
        verify(configRepository, never()).save(any());
    }

    @Test
    void updateConfig_reviewMinSessionAgeDaysValidAgainstCooldownDefault_persists() {
        PlatformConfig existing = entry("reviews.minSessionAgeDays", "7", ConfigValueType.LONG);
        when(configRepository.findByKey("reviews.minSessionAgeDays")).thenReturn(Optional.of(existing));

        configService.updateConfig("reviews.minSessionAgeDays", "10");

        verify(configRepository).save(existing);
        assertThat(existing.getValue()).isEqualTo("10");
    }

    @Test
    void updateConfig_reviewWindowGapOneDay_nowRejectedWith400() {
        // skillars-deferred-149 AC5: 29/30 (gap=1) passed the OLD strict-ordering rule (29 < 30) but
        // must now fail under the new minimum-7-day-gap rule.
        when(configRepository.findByKey("reviews.minSessionAgeDays"))
                .thenReturn(Optional.of(entry("reviews.minSessionAgeDays", "29", ConfigValueType.LONG)));
        when(configRepository.findByKey("reviews.updateCooldownDays"))
                .thenReturn(Optional.of(entry("reviews.updateCooldownDays", "30", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("reviews.minSessionAgeDays", "29"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("reviews.minSessionAgeDays")
                .hasMessageContaining("reviews.updateCooldownDays");
        verify(configRepository, never()).save(any());
    }

    /**
     * Round-2 code review (R2): pins that the guard resolves the partner key from its STORED row, not
     * from the TTL cache. The cache is primed here with a partner value that would make the write
     * look legal (cooldown 300), while the stored row says otherwise (cooldown 50) — a 100/50 pair
     * must be rejected. Before R2 the guard read the cache and returned 200, committing a pair that
     * violates the invariant and that {@code ConfigStartupAssertion} then refuses to boot on; with a
     * cached read this test fails with "Expecting code to raise a throwable".
     */
    @Test
    void updateConfig_reviewWindowOrdering_readsPartnerFromStoredRowNotStaleCache() {
        when(configRepository.findAll()).thenReturn(List.of(
                entry("reviews.updateCooldownDays", "300", ConfigValueType.LONG)));
        configService.find("reviews.updateCooldownDays"); // warm the cache with the stale value

        when(configRepository.findByKey("reviews.updateCooldownDays"))
                .thenReturn(Optional.of(entry("reviews.updateCooldownDays", "50", ConfigValueType.LONG)));
        when(configRepository.findByKey("reviews.minSessionAgeDays"))
                .thenReturn(Optional.of(entry("reviews.minSessionAgeDays", "7", ConfigValueType.LONG)));

        assertThatThrownBy(() -> configService.updateConfig("reviews.minSessionAgeDays", "100"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("reviews.minSessionAgeDays")
                .hasMessageContaining("reviews.updateCooldownDays");
        verify(configRepository, never()).save(any());
    }

    // ── skillars-deferred-127 AC2: upsert for unseeded HAS_CODE_DEFAULT keys ──────────────────────

    @Test
    void updateConfig_unseededHasCodeDefaultKey_createsRow() {
        // platform.radar_composite_lock_timeout_seconds is one of the 5 HAS_CODE_DEFAULT keys
        // V139__baseline_seed_data.sql never seeded (story-review.md B2's corrected count/list; 4 at
        // the time, now 5 after skillars-deferred-129 AC1 added
        // platform.gdpr_erase_statement_lock_timeout_seconds to the same set).
        when(configRepository.findByKey("platform.radar_composite_lock_timeout_seconds"))
                .thenReturn(Optional.empty());

        configService.updateConfig("platform.radar_composite_lock_timeout_seconds", "10");

        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(configRepository).save(captor.capture());
        PlatformConfig saved = captor.getValue();
        assertThat(saved.getKey()).isEqualTo("platform.radar_composite_lock_timeout_seconds");
        assertThat(saved.getValue()).isEqualTo("10");
        assertThat(saved.getValueType()).isEqualTo(ConfigValueType.LONG);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateConfig_unseededNonHasCodeDefaultKey_stays404() {
        // A key that is neither present nor in HAS_CODE_DEFAULT must keep 404ing — this AC does not
        // widen the write surface to arbitrary unknown keys.
        when(configRepository.findByKey("some.never.seeded.key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> configService.updateConfig("some.never.seeded.key", "1"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(configRepository, never()).save(any());
    }

    /**
     * skillars-deferred-127 code review (2026-09-21): the concurrent first-write race
     * ({@code updateConfig} is a read-then-insert TOCTOU with no {@code @Transactional}) — two
     * callers can both observe {@code findByKey} as empty and both attempt to create the row; only
     * one {@code INSERT} can win against {@code uq_platform_config_key}. Simulates the losing
     * caller's {@code save()} throwing {@link DataIntegrityViolationException} and asserts it is
     * caught, the winner's now-committed row is re-read, and this call's own value is applied to it
     * instead of the exception propagating.
     */
    @Test
    void updateConfig_unseededHasCodeDefaultKey_concurrentFirstWriteLoses_readsBackAndUpdatesWinner() {
        String key = "platform.radar_composite_lock_timeout_seconds";
        PlatformConfig winnerRow = entry(key, "5", ConfigValueType.LONG);
        when(configRepository.findByKey(key))
                .thenReturn(Optional.empty())   // updateConfig's own initial lookup
                .thenReturn(Optional.of(winnerRow)); // re-read after the losing INSERT
        doThrow(new DataIntegrityViolationException("uq_platform_config_key"))
                .when(configRepository).save(argThat(c -> c != winnerRow));

        configService.updateConfig(key, "10");

        verify(configRepository).save(winnerRow);
        assertThat(winnerRow.getValue()).isEqualTo("10");
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

    /**
     * Platform cleanup (B8): {@code ReviewSubmissionService} used to read reviews.minSessionAgeDays
     * with a hand-copied max of 365 while ConfigBounds, the PUT guard and ConfigStartupAssertion
     * all cap it at 358. The registry-driven overload reads the bound from the BoundedKey itself, so
     * a stored 360 falls back to the code default instead of being honoured.
     */
    @Test
    void getBoundedInt_boundedKey_usesRegistryRange() {
        when(configRepository.findAll()).thenReturn(List.of(
            entry(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.key(), "360", ConfigValueType.LONG),
            entry(ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key(), "45", ConfigValueType.LONG)));
        configService.init();

        assertThat(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.max()).isEqualTo(358L);
        assertThat(configService.getBoundedInt(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS))
            .isEqualTo((int) ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.defaultValue());
        assertThat(configService.getBoundedInt(ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS)).isEqualTo(45);
    }

    @Test
    void getBoundedInt_boundedKeyWithoutCodeDefault_isRejected() {
        assertThat(ConfigBounds.HAS_CODE_DEFAULT).doesNotContain(ConfigBounds.BOOKING_BATCH_MAX_SIZE.key());

        assertThatThrownBy(() -> configService.getBoundedInt(ConfigBounds.BOOKING_BATCH_MAX_SIZE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(ConfigBounds.BOOKING_BATCH_MAX_SIZE.key());
    }

    @Test
    void getBoundedLong_boundedKey_usesRegistryRangeAndGuard() {
        when(configRepository.findAll()).thenReturn(List.of(
            entry(ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS.key(), "99999", ConfigValueType.LONG)));
        configService.init();

        assertThat(configService.getBoundedLong(ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS)).isEqualTo(24L);
        assertThatThrownBy(() -> configService.getBoundedLong(ConfigBounds.BOOKING_BATCH_MAX_SIZE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviewEligibilityBounds_derivedFromTheSingleGapConstant() {
        assertThat(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.max())
            .isEqualTo(ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.max()
                - ConfigBounds.MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS);
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

    // ---- skillars-deferred-161 AC6: refreshCache() no longer holds a monitor across findAll() ----

    @Test
    void ttlStaleReader_doesNotWaitForAnInFlightLoad_andServesTheCachedSnapshot() throws Exception {
        // ttl 0 => every read is TTL-stale. Cache is loaded once by init(); the second findAll() blocks.
        when(configProperties.getCacheTtlSeconds()).thenReturn(0L);
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        AtomicInteger findAllCalls = new AtomicInteger();
        when(configRepository.findAll()).thenAnswer(inv -> {
            if (findAllCalls.incrementAndGet() == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            loadEntered.countDown();
            assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> leader = pool.submit(() -> configService.find("k"));
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // The leader is parked inside findAll(). A second TTL-stale reader must return at once with the
            // cached value instead of queueing behind it (the old synchronized refreshCache() blocked here).
            Future<Optional<String>> follower = pool.submit(() -> configService.find("k"));
            assertThat(follower.get(2, TimeUnit.SECONDS)).contains("v1");

            releaseLoad.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).contains("v2");
            // single-flight: the follower never started a second load
            assertThat(findAllCalls.get()).isEqualTo(2);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void readersAfterInvalidate_allSeeFreshData_withASingleLoad() throws Exception {
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            int call = findAllCalls.incrementAndGet();
            if (call == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            loadEntered.countDown();
            assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();
        configService.invalidate();

        int readers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            List<Future<Optional<String>>> results = new ArrayList<>();
            for (int i = 0; i < readers; i++) {
                results.add(pool.submit(() -> configService.find("k")));
            }
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoad.countDown();
            for (Future<Optional<String>> r : results) {
                // read-after-invalidate: nobody may be handed the pre-invalidate snapshot
                assertThat(r.get(5, TimeUnit.SECONDS)).contains("v2");
            }
            assertThat(findAllCalls.get()).isEqualTo(2);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void invalidateDuringALoad_isNotLost() throws Exception {
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            int call = findAllCalls.incrementAndGet();
            if (call == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            if (call == 2) {
                // snapshot taken BEFORE the writer commits: stale by the time it is applied
                loadEntered.countDown();
                assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();
        configService.invalidate();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> loader = pool.submit(() -> configService.find("k"));
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            configService.invalidate(); // the write commits and invalidates while the old snapshot is loading
            releaseLoad.countDown();
            loader.get(5, TimeUnit.SECONDS);

            // the invalidate must still be honoured: the next read reloads instead of waiting out the TTL
            assertThat(configService.find("k")).contains("v2");
            assertThat(findAllCalls.get()).isEqualTo(3);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt() throws Exception {
        // skillars-deferred-163 AC1.1/1.6: the waiter now parks on a BOUNDED tryLock(timeout), so it
        // observes TIMED_WAITING, not the old uninterruptible lock()'s WAITING. Exercises the waiter
        // loop's second pass: B must not accept A's load, which began before B's invalidate.
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            int call = findAllCalls.incrementAndGet();
            if (call == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            if (call == 2) {
                loadEntered.countDown();
                assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();
        configService.invalidate();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        FutureTask<Optional<String>> waiter = new FutureTask<>(() -> configService.find("k"));
        Thread waiterThread = new Thread(waiter, "config-waiter");
        try {
            Future<Optional<String>> leader = pool.submit(() -> configService.find("k"));
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            configService.invalidate(); // lands after the leader's load observed its epoch
            waiterThread.start();
            // the must-be-fresh waiter parks on the bounded refreshLock.tryLock(timeout) behind the
            // in-flight load
            await().atMost(5, TimeUnit.SECONDS).until(() -> waiterThread.getState() == Thread.State.TIMED_WAITING);

            releaseLoad.countDown();
            leader.get(5, TimeUnit.SECONDS);
            assertThat(waiter.get(5, TimeUnit.SECONDS)).contains("v2");
            assertThat(findAllCalls.get()).isEqualTo(3);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
            waiterThread.interrupt();
        }
    }

    @Test
    void mustBeFreshWaiter_boundedTimeoutWithRecentRecordedFailure_rethrowsWithoutItsOwnFindAll() throws Exception {
        // skillars-deferred-163 AC1.1/1.6: L1 fails synchronously (records lastMustBeFreshFailure,
        // releases the lock). L2 then grabs the now-free lock and holds it mid-load past W's own
        // bounded tryLock timeout. W must rethrow L1's recorded failure instead of attempting a
        // third, redundant findAll() once L2 eventually frees the lock.
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch l2LoadEntered = new CountDownLatch(1);
        CountDownLatch releaseL2Load = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            int call = findAllCalls.incrementAndGet();
            if (call == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            if (call == 2) {
                throw new IllegalStateException("db down");
            }
            l2LoadEntered.countDown();
            assertThat(releaseL2Load.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();
        configService.invalidate();

        // L1: synchronous on the test thread, fails immediately and records the failure.
        assertThatThrownBy(() -> configService.find("k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> l2 = pool.submit(() -> configService.find("k"));
            assertThat(l2LoadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // W's bounded wait (4s) times out while L2 still holds the lock.
            Future<Optional<String>> waiter = pool.submit(() -> configService.find("k"));
            assertThatThrownBy(() -> waiter.get(8, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(ConfigRefreshException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db down");

            releaseL2Load.countDown();
            l2.get(5, TimeUnit.SECONDS);
            // L1's failure (call 2) + L2's successful load (call 3): W's rethrow made no findAll() of its own.
            assertThat(findAllCalls.get()).isEqualTo(3);
        } finally {
            releaseL2Load.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mustBeFreshWaiter_acquiringTheLockRightAfterTheLeaderFailed_rethrowsWithoutItsOwnFindAll() throws Exception {
        // skillars-deferred-163 code review (2026-10-27, AC1.1): W is parked on the bounded tryLock while
        // the leader's load fails; W then ACQUIRES the freed lock. It must rethrow the failure it waited
        // out, not run a second findAll() (in an outage: a second full Hikari connection timeout).
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            if (findAllCalls.incrementAndGet() == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            loadEntered.countDown();
            assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("db down");
        });
        configService.init();
        configService.invalidate();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> leader = pool.submit(() -> configService.find("k"));
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            Future<Optional<String>> waiter = pool.submit(() -> {
                waiterThread.set(Thread.currentThread());
                return configService.find("k");
            });
            await().atMost(5, TimeUnit.SECONDS).until(() -> waiterThread.get() != null
                && waiterThread.get().getState() == Thread.State.TIMED_WAITING);

            releaseLoad.countDown();
            assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db down");
            assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(ConfigRefreshException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db down");
            // init's load + the leader's failed load: the waiter made no findAll() of its own.
            assertThat(findAllCalls.get()).isEqualTo(2);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mustBeFreshWaiter_boundedTimeoutWithNoRecordedFailure_makesAnotherBoundedAttemptAndGetsTheFreshLoad()
            throws Exception {
        // skillars-deferred-163 AC1.6: a timed-out tryLock with no recent recorded failure does not fail the
        // reader — it loops for another bounded attempt. Here the leader's load outlasts W's first 4s wait,
        // then succeeds; W's second attempt finds the cache fresh and returns the new value with no
        // findAll() of its own.
        AtomicInteger findAllCalls = new AtomicInteger();
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(configRepository.findAll()).thenAnswer(inv -> {
            if (findAllCalls.incrementAndGet() == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            loadEntered.countDown();
            assertThat(releaseLoad.await(15, TimeUnit.SECONDS)).isTrue();
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();
        configService.invalidate();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> leader = pool.submit(() -> configService.find("k"));
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            long startNanos = System.nanoTime();
            Future<Optional<String>> waiter = pool.submit(() -> configService.find("k"));
            // hold the leader's load past W's first bounded wait (4s)
            await().atMost(10, TimeUnit.SECONDS)
                .until(() -> (System.nanoTime() - startNanos) / 1_000_000 > 4_500);
            assertThat(waiter.isDone()).isFalse();

            releaseLoad.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).contains("v2");
            assertThat(waiter.get(5, TimeUnit.SECONDS)).contains("v2");
            assertThat(findAllCalls.get()).isEqualTo(2);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void reloadUnderConcurrentReaders_neverExposesAGapOrAPartialMap() throws Exception {
        // skillars-deferred-163 AC1.4/AC1.6 (behavioral, no reflection): load() swaps one immutable map in a
        // single volatile write. Every read is served from exactly the old or the new snapshot: a key present
        // in both is never momentarily missing (a clear-then-put load would expose that gap), a removed key
        // is gone and an added key present once the reload returns.
        AtomicInteger findAllCalls = new AtomicInteger();
        when(configRepository.findAll()).thenAnswer(inv -> findAllCalls.incrementAndGet() % 2 == 1
            ? List.of(entry("stable", "v1", ConfigValueType.STRING), entry("removed", "x", ConfigValueType.STRING))
            : List.of(entry("stable", "v2", ConfigValueType.STRING), entry("added", "y", ConfigValueType.STRING)));
        configService.init();

        AtomicInteger gaps = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = pool.submit(() -> {
                while (!stop.get()) {
                    Optional<String> v = configService.find("stable");
                    if (v.isEmpty() || !(v.get().equals("v1") || v.get().equals("v2"))) gaps.incrementAndGet();
                }
            });
            for (int i = 0; i < 200; i++) {
                configService.invalidate();
                configService.find("stable"); // must-be-fresh: reloads on this thread
            }
            stop.set(true);
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        assertThat(gaps.get()).isZero();
        // 1 (init) + 200 reloads = 201 loads; odd count → the last load is the v1/"removed" shape.
        assertThat(findAllCalls.get()).isEqualTo(201);
        assertThat(configService.find("stable")).contains("v1");
        assertThat(configService.find("removed")).contains("x");
        assertThat(configService.find("added")).isEmpty();
    }

    @Test
    void scheduledRefresh_skipsTickWithoutBlocking_whenARefreshIsAlreadyInFlight() throws Exception {
        // skillars-deferred-163 AC1.2: the @Scheduled tick must never park the shared scheduler
        // thread behind a concurrent must-be-fresh load — it tries the lock once and returns.
        when(configProperties.getCacheTtlSeconds()).thenReturn(0L);
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        AtomicInteger findAllCalls = new AtomicInteger();
        when(configRepository.findAll()).thenAnswer(inv -> {
            if (findAllCalls.incrementAndGet() == 1) {
                return List.of(entry("k", "v1", ConfigValueType.STRING));
            }
            loadEntered.countDown();
            assertThat(releaseLoad.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(entry("k", "v2", ConfigValueType.STRING));
        });
        configService.init();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = pool.submit(configService::scheduledRefresh);
            assertThat(loadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            long startNanos = System.nanoTime();
            configService.scheduledRefresh();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            assertThat(elapsedMs).isLessThan(500);

            releaseLoad.countDown();
            inFlight.get(5, TimeUnit.SECONDS);
            // the skipped tick never called findAll() itself: only the in-flight load's own call.
            assertThat(findAllCalls.get()).isEqualTo(2);
        } finally {
            releaseLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void ttlOnlyRefreshFailure_servesTheCachedSnapshot_andRetriesNextRead() {
        when(configProperties.getCacheTtlSeconds()).thenReturn(0L);
        when(configRepository.findAll())
                .thenReturn(List.of(entry("k", "v1", ConfigValueType.STRING)))
                .thenThrow(new IllegalStateException("pool exhausted"))
                .thenReturn(List.of(entry("k", "v2", ConfigValueType.STRING)));
        configService.init();

        assertThat(configService.find("k")).contains("v1");
        assertThat(configService.find("k")).contains("v2");
        verify(configRepository, times(3)).findAll();
    }

    @Test
    void mustBeFreshRefreshFailure_stillPropagates() {
        when(configRepository.findAll())
                .thenReturn(List.of(entry("k", "v1", ConfigValueType.STRING)))
                .thenThrow(new IllegalStateException("db down"));
        configService.init();
        configService.invalidate();

        assertThatThrownBy(() -> configService.find("k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }

    @Test
    void firstLoadFailure_propagates() {
        when(configRepository.findAll()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> configService.init()).isInstanceOf(IllegalStateException.class);
    }

    // ---- admin config editor (FR-ADM-006) ----

    @Test
    void listForAdmin_annotatesStoredRowsWithBoundsAndAddsUnseededCodeDefaultKeys() {
        ConfigBounds.BoundedKey bounded = ConfigBounds.PACK_PAUSE_MAX_DAYS;
        when(configRepository.findAll()).thenReturn(List.of(
            entry("zzz.unbounded.flag", "true", ConfigValueType.STRING),
            entry(bounded.key(), "30", ConfigValueType.LONG)));

        List<AdminConfigEntryDto> list = configService.listForAdmin();

        // Sorted by key.
        assertThat(list).extracting(AdminConfigEntryDto::key).isSorted();

        AdminConfigEntryDto boundedRow = list.stream().filter(e -> e.key().equals(bounded.key())).findFirst().orElseThrow();
        assertThat(boundedRow.stored()).isTrue();
        assertThat(boundedRow.value()).isEqualTo("30");
        assertThat(boundedRow.valueType()).isEqualTo("LONG");
        assertThat(boundedRow.min()).isEqualTo(bounded.min());
        assertThat(boundedRow.max()).isEqualTo(bounded.max());
        assertThat(boundedRow.note()).isEqualTo(bounded.note());
        assertThat(boundedRow.failFast()).isEqualTo(bounded.failFast());
        assertThat(boundedRow.codeDefault()).isEqualTo(bounded.defaultValue());

        AdminConfigEntryDto unbounded = list.stream().filter(e -> e.key().equals("zzz.unbounded.flag")).findFirst().orElseThrow();
        assertThat(unbounded.valueType()).isEqualTo("STRING");
        assertThat(unbounded.min()).isNull();
        assertThat(unbounded.max()).isNull();
        assertThat(unbounded.codeDefault()).isNull();
        assertThat(unbounded.failFast()).isFalse();

        // Every HAS_CODE_DEFAULT key is reachable from the editor, stored or not — updateConfig upserts them.
        assertThat(list).extracting(AdminConfigEntryDto::key).containsAll(ConfigBounds.HAS_CODE_DEFAULT);
        AdminConfigEntryDto unseeded = list.stream()
            .filter(e -> e.key().equals(ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.key()))
            .findFirst().orElseThrow();
        assertThat(unseeded.stored()).isFalse();
        assertThat(unseeded.value()).isNull();
        assertThat(unseeded.valueType()).isEqualTo("LONG");
        assertThat(unseeded.codeDefault()).isEqualTo(ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.defaultValue());
        // Each key appears once.
        assertThat(list).extracting(AdminConfigEntryDto::key).doesNotHaveDuplicates();
    }
}
