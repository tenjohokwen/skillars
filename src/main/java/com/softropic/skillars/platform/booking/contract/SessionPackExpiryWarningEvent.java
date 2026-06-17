package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class SessionPackExpiryWarningEvent extends ApplicationEvent {

    private final UUID packId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final int creditsRemaining;
    private final Instant expiresAt;
    private final String warningThreshold;
    private final String canonicalTimezone;

    public SessionPackExpiryWarningEvent(Object source, UUID packId, Long parentId, String parentEmail,
                                         String coachDisplayName, int creditsRemaining,
                                         Instant expiresAt, String warningThreshold,
                                         String canonicalTimezone) {
        super(source);
        this.packId = packId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.creditsRemaining = creditsRemaining;
        this.expiresAt = expiresAt;
        this.warningThreshold = warningThreshold;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getPackId() { return packId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public int getCreditsRemaining() { return creditsRemaining; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getWarningThreshold() { return warningThreshold; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
