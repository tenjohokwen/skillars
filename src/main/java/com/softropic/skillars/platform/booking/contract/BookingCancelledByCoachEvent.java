package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A coach cancelled one of their bookings ({@code CANCEL_COACH}, from ACCEPTED / CONFIRMED / UPCOMING /
 * PAUSED). {@code paymentCaptured} is decided under the booking row lock: ACCEPTED has taken no money (a
 * card booking is charged, and a pack booking's session deducted, only at settle), so nothing is refunded
 * or restored for it (C6). {@code sessionPrice} is what the payer actually paid when captured (zero
 * otherwise).
 */
public class BookingCancelledByCoachEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final String cancelReason;
    private final UUID sessionPackPurchaseId;
    private final BigDecimal sessionPrice;
    private final boolean packExpiredAtCancellation;
    private final boolean paymentCaptured;
    private final String parentEmail;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;
    /**
     * fix/audit3-completion-dispute-integrity: the coach cancelled at or after the booking's start — a no-show in
     * disguise. Always a strike, whatever the reason, and not counted toward the excused-cancellation cap.
     */
    private final boolean cancelledAfterStart;

    public BookingCancelledByCoachEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                                         String cancelReason, UUID sessionPackPurchaseId,
                                         BigDecimal sessionPrice, boolean packExpiredAtCancellation,
                                         boolean paymentCaptured, String parentEmail, Instant requestedStartTime,
                                         String canonicalTimezone) {
        this(source, bookingId, parentId, coachId, cancelReason, sessionPackPurchaseId, sessionPrice,
            packExpiredAtCancellation, paymentCaptured, parentEmail, requestedStartTime, canonicalTimezone, false);
    }

    @SuppressWarnings("java:S107") // an immutable event: one field per fact its listeners need
    public BookingCancelledByCoachEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                                         String cancelReason, UUID sessionPackPurchaseId,
                                         BigDecimal sessionPrice, boolean packExpiredAtCancellation,
                                         boolean paymentCaptured, String parentEmail, Instant requestedStartTime,
                                         String canonicalTimezone, boolean cancelledAfterStart) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.cancelReason = cancelReason;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.sessionPrice = sessionPrice;
        this.packExpiredAtCancellation = packExpiredAtCancellation;
        this.paymentCaptured = paymentCaptured;
        this.parentEmail = parentEmail;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
        this.cancelledAfterStart = cancelledAfterStart;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public String getCancelReason() { return cancelReason; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
    public boolean isPackExpiredAtCancellation() { return packExpiredAtCancellation; }
    /** True only when the payer had been charged (CONFIRMED / UPCOMING / PAUSED) — nothing is owed otherwise. */
    public boolean isPaymentCaptured() { return paymentCaptured; }
    public String getParentEmail() { return parentEmail; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
    public boolean isCancelledAfterStart() { return cancelledAfterStart; }

    /** True when the payer gets a credit-wallet refund: a captured card/credit booking, or a captured booking on a pack that has since expired. */
    public boolean isWalletRefund() {
        return paymentCaptured && (sessionPackPurchaseId == null || packExpiredAtCancellation);
    }

    /** True when the payer's (unexpired) pack gets back the session it was charged at settle. */
    public boolean isPackRestore() {
        return paymentCaptured && sessionPackPurchaseId != null && !packExpiredAtCancellation;
    }
}
