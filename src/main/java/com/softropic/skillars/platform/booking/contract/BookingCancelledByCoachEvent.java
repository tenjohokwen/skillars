package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BookingCancelledByCoachEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final String cancelReason;
    private final UUID sessionPackPurchaseId;
    private final BigDecimal sessionPrice;
    private final boolean packExpiredAtCancellation;
    private final String parentEmail;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingCancelledByCoachEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                                         String cancelReason, UUID sessionPackPurchaseId,
                                         BigDecimal sessionPrice, boolean packExpiredAtCancellation,
                                         String parentEmail, Instant requestedStartTime,
                                         String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.cancelReason = cancelReason;
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
    public String getCancelReason() { return cancelReason; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
    public boolean isPackExpiredAtCancellation() { return packExpiredAtCancellation; }
    public String getParentEmail() { return parentEmail; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
