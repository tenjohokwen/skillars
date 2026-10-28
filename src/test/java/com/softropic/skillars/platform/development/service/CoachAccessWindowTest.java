package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** skillars-12-10 AC2: the pure window rule and the config helper. */
class CoachAccessWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void nullInstant_isClosed() {
        assertThat(CoachAccessWindow.isOpen(null, NOW, 90)).isFalse();
    }

    @Test
    void exactlyAtCutoff_isOpen_oneSecondBefore_isClosed() {
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        assertThat(CoachAccessWindow.isOpen(cutoff, NOW, 90)).isTrue();
        assertThat(CoachAccessWindow.isOpen(cutoff.minusSeconds(1), NOW, 90)).isFalse();
    }

    @Test
    void futureInstant_isOpen() {
        assertThat(CoachAccessWindow.isOpen(NOW.plusSeconds(86_400), NOW, 90)).isTrue();
    }

    @Test
    void windowDays_readsTheBoundedKeyWithItsCodeDefault() {
        ConfigService config = mock(ConfigService.class);
        when(config.getBoundedLong(ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS))
            .thenReturn(30L);
        assertThat(new CoachAccessWindow(config).windowDays()).isEqualTo(30L);
        assertThat(ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.defaultValue()).isEqualTo(90L);
    }

    @Test
    void instanceIsOpen_usesTheConfiguredWindow() {
        ConfigService config = mock(ConfigService.class);
        when(config.getBoundedLong(ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS))
            .thenReturn(7L);
        CoachAccessWindow window = new CoachAccessWindow(config);
        assertThat(window.isOpen(window.now().minus(Duration.ofDays(6)))).isTrue();
        assertThat(window.isOpen(window.now().minus(Duration.ofDays(8)))).isFalse();
    }
}
