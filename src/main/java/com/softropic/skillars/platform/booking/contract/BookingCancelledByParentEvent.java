package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BookingCancelledByParentEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final UUID sessionPackPurchaseId;
    private final long hoursBeforeSession;
    private final boolean refundEligible;
    private final BigDecimal sessionPrice;
    private final String parentEmail;
    private final String coachEmail;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingCancelledByParentEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                                          UUID sessionPackPurchaseId, long hoursBeforeSession,
                                          boolean refundEligible,
                                          BigDecimal sessionPrice, String parentEmail, String coachEmail,
                                          Instant requestedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.hoursBeforeSession = hoursBeforeSession;
        this.refundEligible = refundEligible;
        this.sessionPrice = sessionPrice;
        this.parentEmail = parentEmail;
        this.coachEmail = coachEmail;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public long getHoursBeforeSession() { return hoursBeforeSession; }
    public boolean isRefundEligible() { return refundEligible; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachEmail() { return coachEmail; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
