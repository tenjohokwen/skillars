package com.softropic.skillars.infrastructure.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TimeZone;

//TODO move this to DateTimeService if possible
public final class TimeGuru {

    private static final Set<String> ZONE_IDS = Set.copyOf(Arrays.asList(TimeZone.getAvailableIDs()));

    public static boolean isZoneIdValid(final String zoneId) {
        return ZONE_IDS.contains(zoneId);
    }

    private TimeGuru() {}
    /**
     * Converts to UTC at the same instant.
     * @param zonedDateTime time to convert to UTC
     * @return The time at that instant without Zone information
     */
    public static LocalDateTime toLocalDateTimeSameInstantUtc(final ZonedDateTime zonedDateTime) {
        return zonedDateTime.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
    }

    public static LocalTime getCurrentTimeOf(final ZoneId zoneId) {
        return LocalTime.now(zoneId);
    }

    public static LocalDate getLocalDateOf(final ZoneId zoneId) {
        return LocalDate.now(zoneId);
    }

    public static LocalDateTime getCurrentDateTimeOf(final ZoneId zoneId) {
        return LocalDateTime.now(zoneId);
    }

    /**
     * Converts time from one zone to another.
     * It converts time to another zone at a particular instant.
     * It can be seen as conversion between zones at a particular point in the UTC time line
     * @param zoneId is the id of the zone to which the Date should be converted
     * @param zonedDateTime is the ZonedDateTime of the origin (from)
     * @return the time at new zone
     */
    public static ZonedDateTime timeAtSameInstantFor(final ZoneId zoneId, final ZonedDateTime zonedDateTime) {
        return zonedDateTime.withZoneSameInstant(zoneId);
    }

    public static LocalDateTime toLastSecondOfTheDay(final LocalDateTime localDateTime) {
        return localDateTime.with(LocalTime.of(23, 59, 59));
    }

    public static LocalDateTime lastSecondOfDay(final LocalDate localDate) {
        return localDate.atTime(LocalTime.of(23, 59, 59));
    }

    public static LocalDateTime toFirstMinuteOfDay(final LocalDate localDate) {
        return localDate.atTime(0, 0, 0);
    }

    /***************************************************************************************************************
     *
     *                                  Verifications
     ****************************************************************************************************************/

    /**
     * Checks if 2 {@code LocalDate} instances represent the same day.
     * @param date1 first date instance
     * @param date2 second date instance
     * @return true if same else false
     */
    public static boolean isSameDay(final LocalDate date1, final LocalDate date2) {
        return date1.getDayOfMonth() == date2.getDayOfMonth() &&
                date1.getMonthValue() == date2.getMonthValue() &&
                date1.getYear() == date2.getYear();
        //The calculation below is wrong because toInstant takes the time to UTC.
        // e.g 2.03.2016 00:01 (date1) And 1.03.2016 20:00 (date2) will be same day for gmt+1
        //i.e date1 - 1 hour (1.03.2016 23:01) and 1.03.2016 19:00 which are both the same day
        //isSameDay(date1.atStartOfDay(zoneId).toInstant(), date2.atStartOfDay(zoneId).toInstant());
    }

    /**
     * Checks if date is in the future
     * @param testLocalDate date to check
     * @param zoneId zone of date
     * @return treu if in the future else false
     */
    public static boolean isFutureLocalDate(final LocalDate testLocalDate, final ZoneId zoneId) {
        return testLocalDate.isAfter(getLocalDateOf(zoneId));
    }

    public static boolean isTargetInTimeFrame(final LocalDateTime targetDate,
                                              final LocalDateTime begin,
                                              final LocalDateTime end){
        return (targetDate.isEqual(begin) || targetDate.isAfter(begin)) &&
                (targetDate.isBefore(end) || targetDate.isEqual(end));
    }
}
