package com.softropic.skillars.infrastructure.feature;

public interface FeatureToggleService {

    boolean isEnabled(AppFeature feature);

    boolean isEnabled(AppFeature feature, boolean defaultValue);
}
