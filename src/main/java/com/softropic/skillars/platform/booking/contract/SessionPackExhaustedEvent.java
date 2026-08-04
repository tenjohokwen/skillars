package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class SessionPackExhaustedEvent extends ApplicationEvent {

    private final UUID packId;
    // Deferred-12 AC5: the publisher has always passed SessionPackPurchase.parentId into this slot
    // (PackSessionService.deductSession) — the field was simply misnamed playerId.
    private final Long parentId;
    private final UUID coachId;

    public SessionPackExhaustedEvent(Object source, UUID packId, Long parentId, UUID coachId) {
        super(source);
        this.packId = packId;
        this.parentId = parentId;
        this.coachId = coachId;
    }

    public UUID getPackId() { return packId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
}
