package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.contract.ConfigValueType;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConfigService {

    private static final String MISCONFIGURED_COUNTER = "config.value.misconfigured";

    private final PlatformConfigRepository configRepository;
    private final ConfigProperties configProperties;
    private final ConfigMapper configMapper;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, PlatformConfig> cache = new ConcurrentHashMap<>();
    private volatile Instant lastRefreshed = Instant.MIN;

    public ConfigService(PlatformConfigRepository configRepository,
                         ConfigProperties configProperties,
                         ConfigMapper configMapper,
                         MeterRegistry meterRegistry) {
        this.configRepository = configRepository;
        this.configProperties = configProperties;
        this.configMapper = configMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}", timeUnit = TimeUnit.SECONDS)
    public void scheduledRefresh() {
        refreshCache();
    }

    public String getString(String key) {
        ensureFresh();
        PlatformConfig entry = cache.get(key);
        if (entry == null) {
            throw new IllegalStateException("Missing platform config key: " + key);
        }
        return entry.getValue();
    }

    public long getLong(String key) {
        String raw = getString(key);
        try {
            // trim() to match getLong(key, default) and ConfigStartupAssertion — a whitespace-padded
            // value must not pass boot validation and then throw here at every bounded call site
            // (skillars-deferred-107 code review).
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key '" + key + "' is not a valid long: " + raw);
        }
    }

    public long getLong(String key, long defaultValue) {
        return find(key)
            .filter(v -> !v.isBlank())
            .map(v -> {
                try { return Long.parseLong(v.trim()); }
                catch (NumberFormatException e) {
                    log.warn("Config key '{}' has non-numeric value '{}' — using default {}", key, v, defaultValue);
                    return defaultValue;
                }
            })
            .orElseGet(() -> {
                log.warn("Config key '{}' is absent or blank — using default {}", key, defaultValue);
                return defaultValue;
            });
    }

    public int getInt(String key, int defaultValue) {
        return (int) getLong(key, defaultValue);
    }

    /**
     * Like {@link #getLong(String, long)}, but also rejects values outside [min, max],
     * falling back to {@code defaultValue} with a WARN log. Use for config keys where a
     * syntactically valid but out-of-range number (negative, zero, or absurdly large) would
     * silently corrupt scheduler or business-rule behaviour.
     */
    public long getBoundedLong(String key, long defaultValue, long min, long max) {
        long value = getLong(key, defaultValue);
        if (value < min || value > max) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — using default {}",
                key, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * No-default bounded read for the 1-arg {@link #getLong(String)} call sites — a <em>missing</em>
     * key still throws {@link IllegalStateException} (the intended "operator must set this" contract),
     * but a present-yet-out-of-range value is <strong>clamped to the nearest bound</strong> with a
     * WARN rather than flowing through. Clamp, not throw: fail-fast-on-bad-value is
     * {@code ConfigStartupAssertion}'s job (one decision per key), not an accident of whether a
     * given call site happens to pass a default.
     */
    public long getBoundedLong(String key, long min, long max) {
        long value = getLong(key);
        if (value < min) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — clamping to {}",
                key, value, min, max, min);
            return min;
        }
        if (value > max) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — clamping to {}",
                key, value, min, max, max);
            return max;
        }
        return value;
    }

    /**
     * {@code int} counterpart of {@link #getBoundedLong(String, long, long, long)} — mirrors the
     * existing {@link #getInt(String, int)} narrowing. Out-of-range (or non-numeric / absent) values
     * fall back to {@code defaultValue} with a WARN.
     */
    public int getBoundedInt(String key, int defaultValue, int min, int max) {
        return (int) getBoundedLong(key, defaultValue, min, max);
    }

    public boolean getBoolean(String key) {
        return find(key)
            .map(v -> parseBoolean(key, v, ""))
            .orElseGet(() -> {
                log.warn("Feature gate config key '{}' not found in platform config; defaulting to false", key);
                Counter.builder(MISCONFIGURED_COUNTER)
                    .tag("key", key)
                    .tag("reason", "missing")
                    .register(meterRegistry)
                    .increment();
                return false;
            });
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key)
            .map(v -> parseBoolean(key, v, " (requested default " + defaultValue + " not used)"))
            .orElse(defaultValue);
    }

    /**
     * Shared by both {@code getBoolean} overloads: warns once when a present value is not
     * (case-insensitively) {@code "true"} or {@code "false"}, then returns the same {@code false}
     * result either overload already returned for such a value. {@code logSuffix} lets the
     * 2-arg overload additionally name the ignored default in its warning.
     */
    private boolean parseBoolean(String key, String value, String logSuffix) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            log.warn("Config key '{}' has non-boolean value '{}' — treating as false{}", key, value, logSuffix);
            Counter.builder(MISCONFIGURED_COUNTER)
                .tag("key", key)
                .tag("reason", "non_boolean")
                .register(meterRegistry)
                .increment();
        }
        return "true".equalsIgnoreCase(value);
    }

    public Optional<String> find(String key) {
        ensureFresh();
        return Optional.ofNullable(cache.get(key)).map(PlatformConfig::getValue);
    }

    public ConfigValueResponse findResponse(String key) {
        ensureFresh();
        PlatformConfig entry = cache.get(key);
        if (entry == null) {
            throw new ResourceNotFoundException("ConfigEntry", key);
        }
        return configMapper.toResponse(entry);
    }

    /**
     * skillars-deferred-127 AC2: a {@link ConfigBounds#HAS_CODE_DEFAULT} key with no existing
     * {@code platform_config} row is upserted (a new row is created) rather than 404ing — every
     * {@code HAS_CODE_DEFAULT} key's own Javadoc documents "an operator can widen this at runtime" as
     * its {@code max}-bound rationale, which this write path previously contradicted for the 4 of
     * those 17 keys that {@code V139__baseline_seed_data.sql} never seeded. A key that is absent AND
     * not in {@code HAS_CODE_DEFAULT} (a {@code failFast} key, or simply unrecognized) keeps the
     * existing 404 behavior exactly — this does not widen the write surface to arbitrary unknown keys.
     */
    public ConfigValueResponse updateConfig(String key, String newValue) {
        Optional<PlatformConfig> existing = configRepository.findByKey(key);
        if (existing.isEmpty() && !ConfigBounds.HAS_CODE_DEFAULT.contains(key)) {
            // Preserves the original 404-before-range-check ordering for a key that is neither
            // present nor upsertable — matching this method's pre-AC2 behavior exactly.
            throw new ResourceNotFoundException("ConfigEntry", key);
        }
        rejectOutOfRange(key, newValue);

        if (existing.isPresent()) {
            PlatformConfig entity = existing.get();
            entity.setValue(newValue);
            entity.setUpdatedAt(Instant.now());
            configRepository.save(entity);
            invalidate();
            return configMapper.toResponse(entity);
        }

        PlatformConfig entity = createOrReadBack(key, newValue);
        invalidate();
        return configMapper.toResponse(entity);
    }

    /**
     * skillars-deferred-127 code review (2026-09-21, owner decision taken live via
     * {@code AskUserQuestion}): this method is a read-then-insert TOCTOU with no
     * {@code @Transactional} — two concurrent first-ever writes for the same unseeded {@code
     * HAS_CODE_DEFAULT} key can both observe {@link #updateConfig}'s own {@code
     * configRepository.findByKey(key)} as empty and both attempt to create the row here; only one
     * {@code INSERT} can win against {@code uq_platform_config_key} (
     * {@code V138__baseline_schema.sql}). Rather than let the loser's constraint violation surface as
     * a raw {@code DataIntegrityViolationException} → HTTP 500, catch it and re-read the winner's
     * now-committed row, applying this call's own requested value to it instead — turning the race
     * into correct last-writer-wins semantics matching every other concurrent-write path in this
     * codebase, rather than a 500 with the request's value silently dropped.
     */
    private PlatformConfig createOrReadBack(String key, String newValue) {
        PlatformConfig created = PlatformConfig.builder()
            .key(key)
            .value(newValue)
            .valueType(ConfigValueType.LONG)
            .updatedAt(Instant.now())
            .build();
        try {
            configRepository.save(created);
            return created;
        } catch (DataIntegrityViolationException e) {
            PlatformConfig winner = configRepository.findByKey(key)
                .orElseThrow(() -> e);
            winner.setValue(newValue);
            winner.setUpdatedAt(Instant.now());
            configRepository.save(winner);
            return winner;
        }
    }

    /**
     * skillars-deferred-107 code review: the read-side clamp and {@code ConfigStartupAssertion}
     * only ever saw values that were bad <em>at the last restart</em> — the {@code PUT /api/config}
     * path operators actually use wrote the raw string with no check. For a key that
     * {@link ConfigBounds} bounds, reject a non-numeric or out-of-range write with 400 rather than
     * let it go live on this node before the response returns.
     */
    private void rejectOutOfRange(String key, String newValue) {
        ConfigBounds.ALL.stream()
            .filter(b -> b.key().equals(key))
            .findFirst()
            .ifPresent(b -> {
                long value;
                try {
                    value = Long.parseLong(newValue.trim());
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Config key '" + key + "' must be an integer in range [" + b.min() + ", " + b.max() + "]");
                }
                if (value < b.min() || value > b.max()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Config key '" + key + "' = " + value + " is outside the required range ["
                            + b.min() + ", " + b.max() + "] — " + b.note());
                }
            });
    }

    public void invalidate() {
        lastRefreshed = Instant.MIN;
    }

    private void ensureFresh() {
        if (Duration.between(lastRefreshed, Instant.now()).toSeconds() >= configProperties.getCacheTtlSeconds()) {
            refreshCache();
        }
    }

    private synchronized void refreshCache() {
        // double-check: skip if another thread already refreshed while waiting for this lock
        if (Duration.between(lastRefreshed, Instant.now()).toSeconds() < configProperties.getCacheTtlSeconds()) {
            return;
        }
        List<PlatformConfig> all = configRepository.findAll();
        Map<String, PlatformConfig> newData = new HashMap<>(all.size());
        all.forEach(pc -> newData.put(pc.getKey(), pc));
        // putAll then retainAll: cache is never empty, no reader sees a gap
        cache.putAll(newData);
        cache.keySet().retainAll(newData.keySet());
        lastRefreshed = Instant.now();
        log.debug("Platform config cache refreshed: {} entries", cache.size());
    }
}
