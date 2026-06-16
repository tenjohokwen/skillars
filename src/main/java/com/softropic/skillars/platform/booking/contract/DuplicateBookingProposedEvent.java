package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class DuplicateBookingProposedEvent extends ApplicationEvent {

    private final UUID newBookingId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant proposedStartTime;
    private final String canonicalTimezone;

    public DuplicateBookingProposedEvent(Object source, UUID newBookingId, String parentEmail,
                                          String coachDisplayName, Instant proposedStartTime,
                                          String canonicalTimezone) {
        super(source);
        this.newBookingId = newBookingId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.proposedStartTime = proposedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getNewBookingId() { return newBookingId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getProposedStartTime() { return proposedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
