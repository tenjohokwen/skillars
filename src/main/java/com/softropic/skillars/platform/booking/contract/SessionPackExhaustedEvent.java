package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class SessionPackExhaustedEvent extends ApplicationEvent {

    private final UUID packId;
    private final Long playerId;
    private final UUID coachId;

    public SessionPackExhaustedEvent(Object source, UUID packId, Long playerId, UUID coachId) {
        super(source);
        this.packId = packId;
        this.playerId = playerId;
        this.coachId = coachId;
    }

    public UUID getPackId() { return packId; }
    public Long getPlayerId() { return playerId; }
    public UUID getCoachId() { return coachId; }
}
