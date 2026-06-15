package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class BookingRequestedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final Long playerId;
    private final UUID coachId;
    private final String coachDisplayName;
    private final String coachEmail;
    private final String notes;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingRequestedEvent(Object source, UUID bookingId, Long parentId, Long playerId,
                                  UUID coachId, String coachDisplayName, String coachEmail,
                                  String notes, Instant requestedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.playerId = playerId;
        this.coachId = coachId;
        this.coachDisplayName = coachDisplayName;
        this.coachEmail = coachEmail;
        this.notes = notes;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public Long getPlayerId() { return playerId; }
    public UUID getCoachId() { return coachId; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public String getCoachEmail() { return coachEmail; }
    public String getNotes() { return notes; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
