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
