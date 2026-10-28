package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachReinstatedEvent extends ApplicationEvent {

    private final UUID coachId;
    private final Long adminId;
    private final String coachDisplayName;
    /** Null when the coach has no reachable address — the listener then skips the email. */
    private final AdminNotificationRecipient recipient;

    public CoachReinstatedEvent(Object source, UUID coachId, Long adminId) {
        this(source, coachId, adminId, null, null);
    }

    public CoachReinstatedEvent(Object source, UUID coachId, Long adminId, String coachDisplayName,
                                AdminNotificationRecipient recipient) {
        super(source);
        this.coachId = coachId;
        this.adminId = adminId;
        this.coachDisplayName = coachDisplayName;
        this.recipient = recipient;
    }

    public UUID getCoachId() { return coachId; }
    public Long getAdminId() { return adminId; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public AdminNotificationRecipient getRecipient() { return recipient; }
}
