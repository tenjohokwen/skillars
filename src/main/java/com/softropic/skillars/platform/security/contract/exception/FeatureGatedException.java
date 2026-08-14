package com.softropic.skillars.platform.security.contract.exception;

public class FeatureGatedException extends RuntimeException {

    private final String requiredTier;
    private final String featureKey;

    public FeatureGatedException(String featureKey, String requiredTier) {
        super(buildMessage(featureKey, requiredTier));
        this.requiredTier = requiredTier;
        this.featureKey = featureKey;
    }

    // requiredTier == null means no tier at all currently grants this feature (a misconfiguration),
    // as opposed to "the caller's tier is below what's required" — the two must read differently.
    private static String buildMessage(String featureKey, String requiredTier) {
        if (requiredTier == null) {
            return "Feature '" + featureKey + "' is not currently available at any subscription tier";
        }
        return "Feature '" + featureKey + "' requires tier: " + requiredTier;
    }

    public String getRequiredTier() { return requiredTier; }
    public String getFeatureKey() { return featureKey; }
}
