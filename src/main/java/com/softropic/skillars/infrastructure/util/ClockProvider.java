package com.softropic.skillars.infrastructure.util;

import java.time.Clock;
import java.time.ZoneId;

public final class ClockProvider {
    private static final ThreadLocal<Clock> contextHolder = new ThreadLocal<>(); // NOPMD

    private ClockProvider() {}

   static void setClock(final Clock clock) {
        contextHolder.set(clock);
    }

    public static void setClockWithZoneId(final ZoneId zoneId) {
        contextHolder.set(Clock.system(zoneId));
    }

    public static Clock getClock() {
        final Clock clock = contextHolder.get();
        return clock != null ? clock : Clock.systemDefaultZone(); //do not use UTC as default. Hibernate config handles conversion in the db to UTC and back
    }

    public static Clock getClock(final ZoneId zoneId) {
        return Clock.system(zoneId);
    }

    public static void unsetClock() {
        contextHolder.remove();
    }

}
