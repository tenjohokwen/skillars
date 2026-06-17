package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class PackPausedEvent extends ApplicationEvent {

    private final UUID packId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant newExpiresAt;
    private final List<Instant> cancelledBookingTimes;
    private final String canonicalTimezone;

    public PackPausedEvent(Object source, UUID packId, Long parentId, String parentEmail,
                           String coachDisplayName, Instant newExpiresAt,
                           List<Instant> cancelledBookingTimes, String canonicalTimezone) {
        super(source);
        this.packId = packId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.newExpiresAt = newExpiresAt;
        this.cancelledBookingTimes = cancelledBookingTimes;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getPackId() { return packId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getNewExpiresAt() { return newExpiresAt; }
    public List<Instant> getCancelledBookingTimes() { return cancelledBookingTimes; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
