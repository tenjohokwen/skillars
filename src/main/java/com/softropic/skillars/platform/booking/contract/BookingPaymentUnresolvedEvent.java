package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking was abandoned while its payment outcome is <strong>unknown</strong>.
 *
 * <p>skillars-deferred-91 code review, decision D10. {@code PaymentPendingSweeper.abandonCapture}
 * used to publish {@link BookingDeclinedEvent}, the same event as the "no payment row at all" path —
 * which renders {@code EmailTemplate.BOOKING_DECLINED}, telling the parent their session credits
 * "have not been affected". V124 draws exactly the opposite distinction: {@code CHARGE_FAILED}
 * asserts "no money moved", while {@code CAPTURE_ABANDONED} asserts "we stopped waiting; the Stripe
 * side is unknown". A parent whose card was in fact charged was therefore told something the
 * platform cannot know to be true, with no mention of an outstanding charge.
 *
 * <p>This event exists so that case gets its own copy: the booking did not go ahead, and any charge
 * is being reconciled.
 */
public class BookingPaymentUnresolvedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    public BookingPaymentUnresolvedEvent(Object source, UUID bookingId, Long parentId, String parentEmail,
                                         String coachDisplayName, Instant requestedStartTime,
                                         String canonicalTimezone) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
}
