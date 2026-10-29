package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * fix/booking-completion-integrity: an UPCOMING booking's session ended (scheduled end +
 * {@code booking.unresolvedSession.graceMinutes}) without the coach ever starting or completing it. The
 * payment is captured and nothing is settled: no payout, no refund. Published once per booking by
 * {@code UnresolvedSessionSweeperService} inside the transaction that stamps
 * {@code bookings.unresolved_alerted_at}; the admin module raises a {@code SESSION_NEVER_STARTED} alert
 * after commit. Never auto-paid: the parent can still report a coach no-show (refund + strike), and the
 * coach can still quick-complete (parent confirm / dispute window).
 */
public class SessionNeverStartedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final UUID coachId;
    private final Long parentId;
    private final Instant requestedEndTime;

    public SessionNeverStartedEvent(Object source, UUID bookingId, UUID coachId, Long parentId,
                                    Instant requestedEndTime) {
        super(source);
        this.bookingId = bookingId;
        this.coachId = coachId;
        this.parentId = parentId;
        this.requestedEndTime = requestedEndTime;
    }

    public UUID getBookingId() { return bookingId; }
    public UUID getCoachId() { return coachId; }
    public Long getParentId() { return parentId; }
    public Instant getRequestedEndTime() { return requestedEndTime; }
}
