package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class RescheduleAcceptedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String parentEmail;
    private final String coachEmail;
    private final String coachDisplayName;
    private final Instant newStartTime;
    private final String canonicalTimezone;

    public RescheduleAcceptedEvent(Object source, UUID bookingId, String parentEmail,
                                    String coachEmail, String coachDisplayName,
                                    Instant newStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentEmail = parentEmail;
        this.coachEmail = coachEmail;
        this.coachDisplayName = coachDisplayName;
        this.newStartTime = newStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachEmail() { return coachEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getNewStartTime() { return newStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
