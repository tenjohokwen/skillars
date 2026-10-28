package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class CoachWarningIssuedEvent extends ApplicationEvent {

    // UUID because Booking.coachId is UUID (coach profile PK, not main.user BIGINT)
    private final UUID coachId;
    private final UUID disputeId;
    private final String reason;
    /** Null when the coach has no reachable address — the listener then skips the email. */
    private final AdminNotificationRecipient recipient;

    public CoachWarningIssuedEvent(Object source, UUID coachId, UUID disputeId, String reason) {
        this(source, coachId, disputeId, reason, null);
    }

    public CoachWarningIssuedEvent(Object source, UUID coachId, UUID disputeId, String reason,
                                   AdminNotificationRecipient recipient) {
        super(source);
        this.coachId = coachId;
        this.disputeId = disputeId;
        this.reason = reason;
        this.recipient = recipient;
    }

    public UUID getCoachId() { return coachId; }
    public UUID getDisputeId() { return disputeId; }
    public String getReason() { return reason; }
    public AdminNotificationRecipient getRecipient() { return recipient; }
}
