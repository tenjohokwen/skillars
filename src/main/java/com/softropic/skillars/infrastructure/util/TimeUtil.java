package com.softropic.skillars.infrastructure.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtil {
    private TimeUtil(){}

    public static Instant getFirstSecondOfDay(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
        LocalDateTime startOfDay = zdt.toLocalDate().atStartOfDay();
        return startOfDay.toInstant(zdt.getOffset());
    }

    public static Instant getLastSecondOfDay(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
        LocalDateTime endOfDay = zdt.toLocalDate().atTime(23, 59, 59);
        return endOfDay.toInstant(zdt.getOffset());
    }

    public static String toDateMonthYearTime(Instant instant) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm")
                                                       .withZone(ZoneId.of("Africa/Douala"));
        return formatter.format(instant);
    }
}
