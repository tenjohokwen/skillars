package com.softropic.skillars.infrastructure.feature;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PropertiesFeatureToggleService implements FeatureToggleService {

    private static final Logger log = LoggerFactory.getLogger(PropertiesFeatureToggleService.class);

    /**
     * Toggle keys whose {@link AppFeature} value was removed because nothing ever read it
     * ({@code payments}, {@code invoicing}). An environment may still set them (e.g. a
     * {@code FEATURES_TOGGLES_PAYMENTS} env var outside this repo), so they are tolerated with a WARN
     * instead of failing startup like a genuinely unknown key. Drop an entry once no deployment sets it.
     */
    static final Set<String> RETIRED_KEYS = Set.of("payments", "invoicing");
    private final FeatureProperties properties;

    public PropertiesFeatureToggleService(FeatureProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        Set<String> validKeys = Arrays.stream(AppFeature.values())
                .map(AppFeature::key)
                .collect(Collectors.toSet());

        properties.getToggles().keySet().forEach(configured -> {
            if (RETIRED_KEYS.contains(configured)) {
                log.warn("Ignoring retired feature toggle '{}' — remove it from the configuration", configured);
                return;
            }
            if (!validKeys.contains(configured)) {
                throw new IllegalStateException("Unknown feature toggle: " + configured);
            }
        });

        log.info("Registered feature toggles: {}", properties.getToggles());
    }

    @Override
    public boolean isEnabled(AppFeature feature) {
        return isEnabled(feature, false);
    }

    @Override
    public boolean isEnabled(AppFeature feature, boolean defaultValue) {
        return properties.getToggles().getOrDefault(feature.key(), defaultValue);
    }
}
