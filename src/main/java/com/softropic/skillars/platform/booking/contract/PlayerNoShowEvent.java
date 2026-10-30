package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class PlayerNoShowEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final String coachEmail;
    /**
     * fix/forfeit-family-emails: the paying family's address — the user {@code Booking.parentId} names (a
     * parent, or the adult player who booked for themselves), never resolved through {@code playerId}, which
     * is a PlayerProfile key. Blank when it could not be resolved; the family notice is then skipped.
     */
    private final String parentEmail;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public PlayerNoShowEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                              String coachEmail, String parentEmail, Instant requestedStartTime,
                              String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.coachEmail = coachEmail;
        this.parentEmail = parentEmail;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public String getCoachEmail() { return coachEmail; }
    public String getParentEmail() { return parentEmail; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
