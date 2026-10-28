package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A booking was cancelled by an admin/system action on the coach's side — a coach suspension or a
 * child-safety hold ({@code BookingService.cancelBookingAsAdmin}, {@code CANCEL_ADMIN}). Published in
 * the cancelling transaction, so its {@code BEFORE_COMMIT} listeners (refund enqueue, the parent's
 * neutral notice) commit atomically with the {@code CANCELLED} write.
 *
 * <p>Refund rule (C2, DECISIONS.md item 2): the payer is made whole, the coach gets no strike.
 * {@code refundEligible} is true only when the booking's payment had been captured (it was
 * {@code CONFIRMED}/{@code UPCOMING}) — REQUESTED / ACCEPTED / PAYMENT_PENDING bookings have taken no
 * money and no pack session, so there is nothing to give back. When eligible: a pack-funded booking
 * gets its session restored to the pack, or — if the pack has already expired, where a restored
 * session would be unusable — a credit-wallet refund of {@code refundAmount}; a card/credit-funded
 * booking gets a credit-wallet {@code BOOKING_REFUND} of {@code refundAmount}, exactly the funding rule
 * a coach cancellation ({@code CANCELLED_COACH}) follows.
 *
 * <p>The notice data ({@code parentEmail}, {@code coachDisplayName}, start time, timezone) never
 * carries the safety/suspension reason: for a suspension sweep the parent is told only that the coach
 * is unavailable; for a single admin cancellation (C6, {@code cancelReason} {@code ADMIN_SUPPORT}) only
 * that the Skillars team cancelled it.
 */
public class BookingCancelledByAdminEvent extends ApplicationEvent {

    /** {@code bookings.cancel_reason} of a coach suspension / child-safety-hold sweep cancellation. */
    public static final String REASON_COACH_UNAVAILABLE = "COACH_UNAVAILABLE";
    /** C6: {@code bookings.cancel_reason} of a single booking an admin cancelled from the admin bookings page. */
    public static final String REASON_ADMIN_SUPPORT = "ADMIN_SUPPORT";

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final UUID sessionPackPurchaseId;
    private final BigDecimal refundAmount;
    private final boolean refundEligible;
    private final boolean packExpiredAtCancellation;
    private final String parentEmail;
    private final String coachDisplayName;
    private final String cancelReason;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingCancelledByAdminEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
            UUID sessionPackPurchaseId, BigDecimal refundAmount, boolean refundEligible,
            boolean packExpiredAtCancellation, String parentEmail, String coachDisplayName,
            String cancelReason, Instant requestedStartTime, String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.refundAmount = refundAmount;
        this.refundEligible = refundEligible;
        this.packExpiredAtCancellation = packExpiredAtCancellation;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.cancelReason = cancelReason;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    /** The amount a credit-wallet refund pays out (what the payer was charged); unused for a pack restore. */
    public BigDecimal getRefundAmount() { return refundAmount; }
    /** True only when payment had been captured — nothing is refunded or restored otherwise. */
    public boolean isRefundEligible() { return refundEligible; }
    public boolean isPackExpiredAtCancellation() { return packExpiredAtCancellation; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    /**
     * {@code COACH_UNAVAILABLE} (a coach suspension / safety-hold sweep) or {@code ADMIN_SUPPORT} (C6: an
     * admin cancelled this one booking from the admin bookings page). Decides only the wording of the
     * payer's neutral notice — never the refund.
     */
    public String getCancelReason() { return cancelReason; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }

    /** True when the payer gets a credit-wallet refund (not a pack-session restore). */
    public boolean isWalletRefund() {
        return refundEligible && (sessionPackPurchaseId == null || packExpiredAtCancellation);
    }

    /** True when the payer's pack gets its session back. */
    public boolean isPackRestore() {
        return refundEligible && sessionPackPurchaseId != null && !packExpiredAtCancellation;
    }
}
