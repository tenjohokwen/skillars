package com.softropic.skillars.platform.security.contract.exception;

public class FeatureGatedException extends RuntimeException {

    private final String requiredTier;
    private final String featureKey;

    public FeatureGatedException(String featureKey, String requiredTier) {
        super("Feature '" + featureKey + "' requires tier: " + requiredTier);
        this.requiredTier = requiredTier;
        this.featureKey = featureKey;
    }

    public String getRequiredTier() { return requiredTier; }
    public String getFeatureKey() { return featureKey; }
}
