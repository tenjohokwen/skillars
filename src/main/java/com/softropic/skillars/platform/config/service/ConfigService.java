package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final PlatformConfigRepository configRepository;
    private final ConfigProperties configProperties;
    private final ConfigMapper configMapper;

    private final ConcurrentHashMap<String, PlatformConfig> cache = new ConcurrentHashMap<>();
    private volatile Instant lastRefreshed = Instant.MIN;

    public ConfigService(PlatformConfigRepository configRepository,
                         ConfigProperties configProperties,
                         ConfigMapper configMapper) {
        this.configRepository = configRepository;
        this.configProperties = configProperties;
        this.configMapper = configMapper;
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
            return Long.parseLong(raw);
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

    public boolean getBoolean(String key) {
        return find(key)
            .map(v -> "true".equalsIgnoreCase(v))
            .orElseGet(() -> {
                log.warn("Feature gate config key '{}' not found in platform config; defaulting to false", key);
                return false;
            });
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key)
            .map(v -> "true".equalsIgnoreCase(v))
            .orElse(defaultValue);
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

    public ConfigValueResponse updateConfig(String key, String newValue) {
        PlatformConfig entity = configRepository.findByKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("ConfigEntry", key));
        entity.setValue(newValue);
        entity.setUpdatedAt(Instant.now());
        configRepository.save(entity);
        invalidate();
        return configMapper.toResponse(entity);
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
