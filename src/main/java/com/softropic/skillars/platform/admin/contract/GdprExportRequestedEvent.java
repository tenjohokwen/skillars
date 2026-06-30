package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class GdprExportRequestedEvent extends ApplicationEvent {

    private final UUID requestId;
    private final Long userId;

    public GdprExportRequestedEvent(Object source, UUID requestId, Long userId) {
        super(source);
        this.requestId = requestId;
        this.userId = userId;
    }

    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
}
