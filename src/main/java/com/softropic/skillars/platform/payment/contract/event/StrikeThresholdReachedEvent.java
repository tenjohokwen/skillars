package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class StrikeThresholdReachedEvent extends ApplicationEvent {

    private final UUID coachId;
    private final UUID triggeringBookingId;
    private final long rollingStrikeCount;

    public StrikeThresholdReachedEvent(Object source, UUID coachId, UUID triggeringBookingId,
                                        long rollingStrikeCount) {
        super(source);
        this.coachId = coachId;
        this.triggeringBookingId = triggeringBookingId;
        this.rollingStrikeCount = rollingStrikeCount;
    }

    public UUID getCoachId() { return coachId; }
    public UUID getTriggeringBookingId() { return triggeringBookingId; }
    public long getRollingStrikeCount() { return rollingStrikeCount; }
}
