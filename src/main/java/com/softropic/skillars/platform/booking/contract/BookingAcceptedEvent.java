package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BookingAcceptedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final BigDecimal sessionPrice;
    private final UUID sessionPackPurchaseId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingAcceptedEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                                BigDecimal sessionPrice, UUID sessionPackPurchaseId,
                                String parentEmail, String coachDisplayName,
                                Instant requestedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.sessionPrice = sessionPrice;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
