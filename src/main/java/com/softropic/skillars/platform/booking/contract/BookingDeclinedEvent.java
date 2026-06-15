package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class BookingDeclinedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingDeclinedEvent(Object source, UUID bookingId, Long parentId, String parentEmail,
                                 String coachDisplayName, Instant requestedStartTime,
                                 String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
