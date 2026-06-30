package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachSuspendedEvent extends ApplicationEvent {

    private final UUID coachId;
    private final String reason;
    private final Long adminId;

    public CoachSuspendedEvent(Object source, UUID coachId, String reason, Long adminId) {
        super(source);
        this.coachId = coachId;
        this.reason = reason;
        this.adminId = adminId;
    }

    public UUID getCoachId() { return coachId; }
    public String getReason() { return reason; }
    public Long getAdminId() { return adminId; }
}
