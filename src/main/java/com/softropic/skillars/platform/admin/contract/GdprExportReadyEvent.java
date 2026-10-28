package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class GdprExportReadyEvent extends ApplicationEvent {

    private final UUID requestId;
    /** The requester: the user themselves, or the parent for a child export. */
    private final Long userId;
    private final Instant expiresAt;
    /** Null when the requester has no reachable address — the listener then skips the email. */
    private final AdminNotificationRecipient recipient;

    public GdprExportReadyEvent(Object source, UUID requestId, Long userId) {
        this(source, requestId, userId, null, null);
    }

    public GdprExportReadyEvent(Object source, UUID requestId, Long userId, Instant expiresAt,
                                AdminNotificationRecipient recipient) {
        super(source);
        this.requestId = requestId;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.recipient = recipient;
    }

    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public AdminNotificationRecipient getRecipient() { return recipient; }
}
