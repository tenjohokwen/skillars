package com.softropic.skillars.infrastructure.feature;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "features")
public class FeatureProperties {

    private Map<String, Boolean> toggles = new HashMap<>();

    public Map<String, Boolean> getToggles() {
        return toggles;
    }

    public void setToggles(Map<String, Boolean> toggles) {
        this.toggles = toggles;
    }
}
