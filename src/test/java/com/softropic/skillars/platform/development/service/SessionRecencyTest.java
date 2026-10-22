package com.softropic.skillars.platform.development.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** skillars-12-6 AC8: the shared, pure days-since helper. */
class SessionRecencyTest {

    private static final Instant NOW = Instant.parse("2027-01-06T10:00:00Z");

    @Test
    void sameInstant_isZeroDays() {
        assertThat(SessionRecency.daysBetween(NOW, NOW)).isZero();
    }

    @Test
    void exactlyOneDay_isOne() {
        assertThat(SessionRecency.daysBetween(NOW.minus(Duration.ofDays(1)), NOW)).isEqualTo(1);
    }

    @Test
    void twentyNineHours_isOneDay() {
        assertThat(SessionRecency.daysBetween(NOW.minus(Duration.ofHours(29)), NOW)).isEqualTo(1);
    }

    @Test
    void twentyThreeHours_isZeroDays() {
        assertThat(SessionRecency.daysBetween(NOW.minus(Duration.ofHours(23)), NOW)).isZero();
    }

    @Test
    void futureInstant_clampsToZero() {
        assertThat(SessionRecency.daysBetween(NOW.plus(Duration.ofDays(3)), NOW)).isZero();
    }

    @Test
    void daysSinceOrNull_nullInput_isNull() {
        assertThat(SessionRecency.daysSinceOrNull(null, NOW)).isNull();
        assertThat(SessionRecency.daysSinceOrNull(NOW.minus(Duration.ofDays(4)), NOW)).isEqualTo(4L);
    }
}
