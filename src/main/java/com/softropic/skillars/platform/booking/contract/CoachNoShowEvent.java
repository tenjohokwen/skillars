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
    private final boolean coachSplit;

    public CoachNoShowEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                             UUID sessionPackPurchaseId, BigDecimal sessionPrice,
                             boolean packExpiredAtCancellation, String parentEmail,
                             Instant requestedStartTime, String canonicalTimezone) {
        this(source, bookingId, parentId, coachId, sessionPackPurchaseId, sessionPrice, packExpiredAtCancellation,
            parentEmail, requestedStartTime, canonicalTimezone, false);
    }

    /**
     * @param coachSplit S-07 conflict zone: the coach quick-completed WITHOUT a live Start and the booker reports a
     *                   no-show. Neither side is established, so the session fee is shared 50/50 (see
     *                   {@link #splitRefundShare}) and the coach is NOT struck.
     */
    public CoachNoShowEvent(Object source, UUID bookingId, Long parentId, UUID coachId,
                             UUID sessionPackPurchaseId, BigDecimal sessionPrice,
                             boolean packExpiredAtCancellation, String parentEmail,
                             Instant requestedStartTime, String canonicalTimezone, boolean coachSplit) {
        super(source);
        this.coachSplit = coachSplit;
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

    public boolean isCoachSplit() { return coachSplit; }

    // NO_SHOW_COACH only leaves UPCOMING or a never-started COMPLETED_PENDING_CONFIRMATION, so payment was always
    // captured.

    /**
     * True when the payer gets a credit-wallet refund: a card/credit booking, or one on a pack that has since expired.
     * A split always refunds money (the paying half) — a pack session is consumed, never restored.
     */
    public boolean isWalletRefund() {
        return coachSplit || sessionPackPurchaseId == null || packExpiredAtCancellation;
    }

    /** True when the payer's (unexpired) pack gets its session back. Never on a split: the session is consumed. */
    public boolean isPackRestore() {
        return !coachSplit && sessionPackPurchaseId != null && !packExpiredAtCancellation;
    }

    /**
     * S-07 split: the payer's half of {@code paid}, rounded DOWN to the cent — the coach takes the odd cent
     * ({@code paid - splitRefundShare(paid)}). Half-cent cases are rare and the coach did attend to the booking by
     * quick-completing it, so the rounding favours the coach; documented in the booking dev-docs.
     */
    public static BigDecimal splitRefundShare(BigDecimal paid) {
        return paid == null ? BigDecimal.ZERO : paid.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.DOWN);
    }
}
