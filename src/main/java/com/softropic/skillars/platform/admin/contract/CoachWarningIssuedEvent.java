package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachWarningIssuedEvent extends ApplicationEvent {

    // UUID because Booking.coachId is UUID (coach profile PK, not main.user BIGINT)
    private final UUID coachId;
    private final UUID disputeId;
    private final String reason;

    public CoachWarningIssuedEvent(Object source, UUID coachId, UUID disputeId, String reason) {
        super(source);
        this.coachId = coachId;
        this.disputeId = disputeId;
        this.reason = reason;
    }

    public UUID getCoachId() { return coachId; }
    public UUID getDisputeId() { return disputeId; }
    public String getReason() { return reason; }
}
