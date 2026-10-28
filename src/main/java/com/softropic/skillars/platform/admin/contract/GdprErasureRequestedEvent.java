package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class GdprErasureRequestedEvent extends ApplicationEvent {

    private final UUID requestId;
    private final Long userId;
    // V172: non-null when the request targets a parent-owned child profile (userId is then the parent).
    private final Long playerId;

    public GdprErasureRequestedEvent(Object source, UUID requestId, Long userId) {
        this(source, requestId, userId, null);
    }

    public GdprErasureRequestedEvent(Object source, UUID requestId, Long userId, Long playerId) {
        super(source);
        this.requestId = requestId;
        this.userId = userId;
        this.playerId = playerId;
    }

    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getPlayerId() { return playerId; }
}
