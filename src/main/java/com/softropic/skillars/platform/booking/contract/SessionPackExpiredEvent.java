package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class SessionPackExpiredEvent extends ApplicationEvent {

    private final UUID packId;
    private final Long playerId;
    private final UUID coachId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final int creditsRemaining;

    public SessionPackExpiredEvent(Object source, UUID packId, Long playerId, UUID coachId,
                                   Long parentId, String parentEmail,
                                   String coachDisplayName, int creditsRemaining) {
        super(source);
        this.packId = packId;
        this.playerId = playerId;
        this.coachId = coachId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.creditsRemaining = creditsRemaining;
    }

    public UUID getPackId() { return packId; }
    public Long getPlayerId() { return playerId; }
    public UUID getCoachId() { return coachId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public int getCreditsRemaining() { return creditsRemaining; }
}
