package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachVisibilityReducedEvent extends ApplicationEvent {

    private final UUID coachId;
    private final long rollingStrikeCount;

    public CoachVisibilityReducedEvent(Object source, UUID coachId, long rollingStrikeCount) {
        super(source);
        this.coachId = coachId;
        this.rollingStrikeCount = rollingStrikeCount;
    }

    public UUID getCoachId() { return coachId; }
    public long getRollingStrikeCount() { return rollingStrikeCount; }
}
