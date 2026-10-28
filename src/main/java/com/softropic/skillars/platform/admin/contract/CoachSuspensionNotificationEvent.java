package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@code AdminCoachEnforcementService.suspendCoach} only when the admin ticked "notify
 * coach". Its listener ({@code AdminOutcomeEmailListener}) sends the COACH_SUSPENDED email and inbox row.
 * Unlike {@link CoachSuspendedEvent} (always published, the domain fact), this one carries the
 * admin's choice to tell the coach — so a safety suspension, which passes {@code notifyCoach=false},
 * never alerts the account it is acting against.
 */
public class CoachSuspensionNotificationEvent extends ApplicationEvent {

    private final UUID coachId;
    private final String reason;
    private final String coachDisplayName;
    /** Null when the coach has no reachable address — the listener then skips the email. */
    private final AdminNotificationRecipient recipient;

    public CoachSuspensionNotificationEvent(Object source, UUID coachId, String reason) {
        this(source, coachId, reason, null, null);
    }

    public CoachSuspensionNotificationEvent(Object source, UUID coachId, String reason, String coachDisplayName,
                                            AdminNotificationRecipient recipient) {
        super(source);
        this.coachId = coachId;
        this.reason = reason;
        this.coachDisplayName = coachDisplayName;
        this.recipient = recipient;
    }

    public UUID getCoachId() { return coachId; }
    public String getReason() { return reason; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public AdminNotificationRecipient getRecipient() { return recipient; }
}
