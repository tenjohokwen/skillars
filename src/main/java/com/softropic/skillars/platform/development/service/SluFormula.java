package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public final class SluFormula {

    private SluFormula() {}

    /**
     * Calculates SLU contribution per skill for a single drill.
     *
     * Formula per skill:
     *   slu = repDensity × weight × (intensity × intensityScale)
     *              × (pressureLevel × pressureScale)
     *              × (matchRealism × matchRealismScale)
     *              × durationMinutes
     *
     * @param metadata          drill metadata snapshot at session time
     * @param durationMinutes   allocated time for this drill (blockDuration / drillsInBlock)
     * @param intensityScale    from ConfigService key "slu.intensity.scale"
     * @param pressureScale     from ConfigService key "slu.pressure.scale"
     * @param matchRealismScale from ConfigService key "slu.matchRealism.scale"
     * @return map of skillCode → SLU value; only skills with positive contribution included
     */
    public static Map<String, BigDecimal> calculate(
            DrillMetadata metadata,
            int durationMinutes,
            BigDecimal intensityScale,
            BigDecimal pressureScale,
            BigDecimal matchRealismScale) {

        Map<String, BigDecimal> result = new HashMap<>();
        if (metadata == null
                || metadata.skillWeighting() == null
                || metadata.skillWeighting().isEmpty()
                || durationMinutes <= 0) {
            return result;
        }

        BigDecimal intensityM = BigDecimal.valueOf(metadata.intensity()).multiply(intensityScale);
        BigDecimal pressureM  = BigDecimal.valueOf(metadata.pressureLevel()).multiply(pressureScale);
        BigDecimal matchM     = BigDecimal.valueOf(metadata.matchRealism()).multiply(matchRealismScale);
        BigDecimal duration   = BigDecimal.valueOf(durationMinutes);
        BigDecimal repD       = BigDecimal.valueOf(metadata.repDensity());

        for (Map.Entry<String, Integer> entry : metadata.skillWeighting().entrySet()) {
            int weight = entry.getValue();
            if (weight <= 0) continue;

            BigDecimal slu = repD
                .multiply(BigDecimal.valueOf(weight))
                .multiply(intensityM)
                .multiply(pressureM)
                .multiply(matchM)
                .multiply(duration)
                .setScale(4, RoundingMode.HALF_UP);

            if (slu.compareTo(BigDecimal.ZERO) > 0) {
                result.put(entry.getKey(), slu);
            }
        }
        return result;
    }
}
