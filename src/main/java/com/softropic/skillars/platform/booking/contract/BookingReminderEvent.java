package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class BookingReminderEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String parentEmail;
    private final String coachEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;
    private final String reminderType;

    public BookingReminderEvent(Object source, UUID bookingId, String parentEmail, String coachEmail,
                                 String coachDisplayName, Instant requestedStartTime,
                                 String canonicalTimezone, String reminderType) {
        super(source);
        this.bookingId = bookingId;
        this.parentEmail = parentEmail;
        this.coachEmail = coachEmail;
        this.coachDisplayName = coachDisplayName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
        this.reminderType = reminderType;
    }

    public UUID getBookingId() { return bookingId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachEmail() { return coachEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
    public String getReminderType() { return reminderType; }
}
