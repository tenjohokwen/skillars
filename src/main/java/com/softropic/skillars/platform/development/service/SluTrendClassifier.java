package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.SkillTrendDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure, stateless classifier that turns a per-skill weekly SLU series into an improving / flat /
 * declining signal. Deliberately free of persistence and security so it is exhaustively unit-testable
 * (see {@code SluTrendClassifierTest}). skillars-deferred-98 AC1b.
 *
 * <p>Method: ordinary-least-squares slope of {@code totalSlu} against the week index (0..n-1),
 * expressed relative to the series mean so the threshold is scale-free — a player accumulating
 * ~10 SLU/week on a skill and one accumulating ~1 SLU/week are both judged on proportional change,
 * not absolute. Fewer than {@link #MIN_WEEKS} weekly data points, or a zero mean, is
 * {@link SkillTrendDirection#INSUFFICIENT_DATA} rather than a misleading "flat".
 */
final class SluTrendClassifier {

    /** Minimum weekly data points before a direction is asserted. */
    static final int MIN_WEEKS = 3;

    /**
     * Relative slope (slope / mean) at or beyond which the trend is called. {@code 0.05} == the
     * skill's weekly SLU is moving by ~5% of its own average per week; below that the week-to-week
     * noise in an additive accumulator is not worth surfacing as a direction.
     */
    static final BigDecimal RELATIVE_SLOPE_THRESHOLD = new BigDecimal("0.05");

    private static final int SCALE = 6;

    private SluTrendClassifier() {}

    record Result(SkillTrendDirection direction, BigDecimal slopePerWeek, int weeksObserved) {}

    /**
     * @param weeklyTotalsChronological one entry per week that has a snapshot row for the skill,
     *                                  oldest first. Never {@code null}; may be empty.
     */
    static Result classify(List<BigDecimal> weeklyTotalsChronological) {
        int n = weeklyTotalsChronological.size();
        if (n < MIN_WEEKS) {
            return new Result(SkillTrendDirection.INSUFFICIENT_DATA, BigDecimal.ZERO, n);
        }

        double meanX = (n - 1) / 2.0;
        double sumY = 0.0;
        for (BigDecimal y : weeklyTotalsChronological) {
            sumY += y.doubleValue();
        }
        double meanY = sumY / n;
        if (meanY == 0.0) {
            return new Result(SkillTrendDirection.INSUFFICIENT_DATA, BigDecimal.ZERO, n);
        }

        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            num += dx * (weeklyTotalsChronological.get(i).doubleValue() - meanY);
            den += dx * dx;
        }
        double slope = den == 0.0 ? 0.0 : num / den;
        BigDecimal relative =
            BigDecimal.valueOf(slope / meanY).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal slopePerWeek = BigDecimal.valueOf(slope).setScale(SCALE, RoundingMode.HALF_UP);

        SkillTrendDirection direction;
        if (relative.compareTo(RELATIVE_SLOPE_THRESHOLD) >= 0) {
            direction = SkillTrendDirection.IMPROVING;
        } else if (relative.compareTo(RELATIVE_SLOPE_THRESHOLD.negate()) <= 0) {
            direction = SkillTrendDirection.DECLINING;
        } else {
            direction = SkillTrendDirection.FLAT;
        }
        return new Result(direction, slopePerWeek, n);
    }
}
