package com.softropic.skillars.infrastructure.util;

import java.time.Clock;
import java.time.ZoneId;

import static java.time.Clock.systemUTC;

public class TestClockProvider {
    public static void setClock(final Clock clock) {
        ClockProvider.setClock(clock);
    }

    public static void setClockWithZoneId(final ZoneId zoneId) {
        ClockProvider.setClockWithZoneId(zoneId);
    }

    public static Clock getClock() {
        return ClockProvider.getClock();
    }

    public static Clock getClock(final ZoneId zoneId) {
        return ClockProvider.getClock(zoneId);
    }

    public static void unsetClock() {
        ClockProvider.unsetClock();
    }

    public static void setSystemClock() {
        ClockProvider.setClock(systemUTC());
    }
}
