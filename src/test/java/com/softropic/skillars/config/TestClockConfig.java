package com.softropic.skillars.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");

    // 2026-01-01T09:00:00Z = 2026-01-01T10:00:00 WAT (UTC+1)
    public static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T09:00:00Z");

    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(FIXED_INSTANT, WAT);
    }
}
