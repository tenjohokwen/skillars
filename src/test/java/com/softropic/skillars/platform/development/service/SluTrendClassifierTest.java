package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.SkillTrendDirection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SluTrendClassifier}. skillars-deferred-98 AC1b. No Spring, no mocks —
 * the classifier is a function of a weekly-total list.
 */
class SluTrendClassifierTest {

    private static List<BigDecimal> series(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void monotonicallyRisingSeries_isImproving() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("2", "4", "6", "8", "10"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.IMPROVING);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("2");
        assertThat(r.weeksObserved()).isEqualTo(5);
    }

    @Test
    void monotonicallyFallingSeries_isDeclining() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("10", "8", "6", "4", "2"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.DECLINING);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("-2");
        assertThat(r.weeksObserved()).isEqualTo(5);
    }

    @Test
    void constantSeries_isFlat() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("5", "5", "5", "5"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.FLAT);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("0");
    }

    @Test
    void movementWithinFivePercentNoiseBand_isFlat() {
        // mean 10.1, OLS slope 0.1 -> relative slope ~0.0099, well under the 0.05 threshold.
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("10.0", "10.1", "9.9", "10.4"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.FLAT);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("0.1");
    }

    @Test
    void relativeSlopeExactlyAtThreshold_isImproving() {
        // x = 0..4, y = 90,95,100,105,110 -> OLS slope 5, mean 100 -> relative slope exactly 0.05.
        // Threshold comparison is inclusive (>=), so this is IMPROVING, not FLAT.
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("90", "95", "100", "105", "110"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.IMPROVING);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("5");
    }

    @Test
    void fewerThanThreeWeeks_isInsufficientData() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("3", "9"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.INSUFFICIENT_DATA);
        assertThat(r.slopePerWeek()).isEqualByComparingTo("0");
        assertThat(r.weeksObserved()).isEqualTo(2);
    }

    @Test
    void emptySeries_isInsufficientData() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(List.of());

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.INSUFFICIENT_DATA);
        assertThat(r.weeksObserved()).isZero();
    }

    @Test
    void allZeroSeries_isInsufficientData_becauseThereIsNoBaselineToMeasureAgainst() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("0", "0", "0", "0"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.INSUFFICIENT_DATA);
    }

    @Test
    void noisyButClearlyRisingSeries_isImproving() {
        SluTrendClassifier.Result r = SluTrendClassifier.classify(series("1", "3", "2", "5", "4", "7"));

        assertThat(r.direction()).isEqualTo(SkillTrendDirection.IMPROVING);
        assertThat(r.slopePerWeek()).isGreaterThan(BigDecimal.ZERO);
    }
}
