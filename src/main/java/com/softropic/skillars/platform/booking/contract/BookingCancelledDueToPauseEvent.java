package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BookingCancelledDueToPauseEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final String coachEmail;
    private final String coachDisplayName;
    private final String parentName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;
    // fix/payment-session-pack-integrity: what the pause must give back. Only a captured booking
    // (CONFIRMED/UPCOMING) was ever charged / had its pack session deducted.
    private final UUID sessionPackPurchaseId;
    private final boolean paymentCaptured;
    private final boolean packExpiredAtCancellation;
    private final BigDecimal refundAmount;

    public BookingCancelledDueToPauseEvent(Object source, UUID bookingId, Long parentId,
                                            UUID coachId, String coachEmail, String coachDisplayName,
                                            String parentName, Instant requestedStartTime,
                                            String canonicalTimezone, UUID sessionPackPurchaseId,
                                            boolean paymentCaptured, boolean packExpiredAtCancellation,
                                            BigDecimal refundAmount) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.coachEmail = coachEmail;
        this.coachDisplayName = coachDisplayName;
        this.parentName = parentName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.paymentCaptured = paymentCaptured;
        this.packExpiredAtCancellation = packExpiredAtCancellation;
        this.refundAmount = refundAmount;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public String getCoachEmail() { return coachEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public String getParentName() { return parentName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public boolean isPaymentCaptured() { return paymentCaptured; }
    public boolean isPackExpiredAtCancellation() { return packExpiredAtCancellation; }
    public BigDecimal getRefundAmount() { return refundAmount; }

    /** True when the payer gets {@link #getRefundAmount()} back as wallet credit (card/credit, or an expired pack). */
    public boolean isWalletRefund() {
        return paymentCaptured && (sessionPackPurchaseId == null || packExpiredAtCancellation);
    }

    /** True when the payer's (unexpired) pack gets back the session it was charged at settle. */
    public boolean isPackRestore() {
        return paymentCaptured && sessionPackPurchaseId != null && !packExpiredAtCancellation;
    }
}
