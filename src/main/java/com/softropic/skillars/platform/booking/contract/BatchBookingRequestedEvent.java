package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class BatchBookingRequestedEvent extends ApplicationEvent {

    private final UUID batchId;
    private final String coachEmail;
    private final String parentName;
    private final int requestedCount;
    private final List<Instant> sessionDates;
    private final String canonicalTimezone;

    public BatchBookingRequestedEvent(Object source, UUID batchId, String coachEmail,
                                      String parentName, int requestedCount,
                                      List<Instant> sessionDates, String canonicalTimezone) {
        super(source);
        this.batchId = batchId;
        this.coachEmail = coachEmail;
        this.parentName = parentName;
        this.requestedCount = requestedCount;
        this.sessionDates = sessionDates;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBatchId() { return batchId; }
    public String getCoachEmail() { return coachEmail; }
    public String getParentName() { return parentName; }
    public int getRequestedCount() { return requestedCount; }
    public List<Instant> getSessionDates() { return sessionDates; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
