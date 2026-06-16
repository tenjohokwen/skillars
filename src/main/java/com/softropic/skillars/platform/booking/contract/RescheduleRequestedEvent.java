package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class RescheduleRequestedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String coachEmail;
    private final String parentName;
    private final Instant originalStartTime;
    private final Instant proposedStartTime;
    private final String canonicalTimezone;

    public RescheduleRequestedEvent(Object source, UUID bookingId, String coachEmail,
                                     String parentName, Instant originalStartTime,
                                     Instant proposedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.coachEmail = coachEmail;
        this.parentName = parentName;
        this.originalStartTime = originalStartTime;
        this.proposedStartTime = proposedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public String getCoachEmail() { return coachEmail; }
    public String getParentName() { return parentName; }
    public Instant getOriginalStartTime() { return originalStartTime; }
    public Instant getProposedStartTime() { return proposedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
