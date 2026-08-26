package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class RescheduleRequestedByCoachEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant originalStartTime;
    private final Instant proposedStartTime;
    private final String canonicalTimezone;

    public RescheduleRequestedByCoachEvent(Object source, UUID bookingId, String parentEmail,
                                            String coachDisplayName, Instant originalStartTime,
                                            Instant proposedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.originalStartTime = originalStartTime;
        this.proposedStartTime = proposedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getOriginalStartTime() { return originalStartTime; }
    public Instant getProposedStartTime() { return proposedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
