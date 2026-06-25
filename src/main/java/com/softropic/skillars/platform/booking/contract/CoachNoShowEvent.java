package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class CoachNoShowEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final UUID sessionPackPurchaseId;
    private final BigDecimal sessionPrice;
    private final boolean packExpiredAtCancellation;
    private final String parentEmail;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public CoachNoShowEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                             UUID sessionPackPurchaseId, BigDecimal sessionPrice,
                             boolean packExpiredAtCancellation, String parentEmail,
                             Instant requestedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.sessionPrice = sessionPrice;
        this.packExpiredAtCancellation = packExpiredAtCancellation;
        this.parentEmail = parentEmail;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
    public boolean isPackExpiredAtCancellation() { return packExpiredAtCancellation; }
    public String getParentEmail() { return parentEmail; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
