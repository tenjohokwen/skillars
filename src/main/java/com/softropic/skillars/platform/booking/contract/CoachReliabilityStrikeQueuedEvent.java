package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachReliabilityStrikeQueuedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final UUID coachId;
    private final String reason;

    public CoachReliabilityStrikeQueuedEvent(Object source, UUID bookingId, UUID coachId, String reason) {
        super(source);
        this.bookingId = bookingId;
        this.coachId = coachId;
        this.reason = reason;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public UUID coachId() {
        return coachId;
    }

    public String reason() {
        return reason;
    }
}
