package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachReinstatedEvent extends ApplicationEvent {

    private final UUID coachId;
    private final Long adminId;

    public CoachReinstatedEvent(Object source, UUID coachId, Long adminId) {
        super(source);
        this.coachId = coachId;
        this.adminId = adminId;
    }

    public UUID getCoachId() { return coachId; }
    public Long getAdminId() { return adminId; }
}
