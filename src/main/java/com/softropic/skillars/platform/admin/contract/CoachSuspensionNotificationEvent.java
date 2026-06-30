package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachSuspensionNotificationEvent extends ApplicationEvent {

    private final UUID coachId;
    private final String reason;

    public CoachSuspensionNotificationEvent(Object source, UUID coachId, String reason) {
        super(source);
        this.coachId = coachId;
        this.reason = reason;
    }

    public UUID getCoachId() { return coachId; }
    public String getReason() { return reason; }
}
