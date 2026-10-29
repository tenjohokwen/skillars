package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An admin resolved a dispute. {@code recipient} is the person who raised it (a minor player's parent
 * instead of the child). The resolution note is the same text the raiser can already read through
 * {@code GET /api/disputes/{id}}.
 */
public class DisputeResolvedEvent extends ApplicationEvent {

    private final UUID disputeId;
    private final UUID bookingId;
    private final String resolution;
    private final Long raisedBy;
    /** The amount actually credited to the booking's parent wallet (zero when none was). */
    private final BigDecimal creditAmount;
    private final String resolutionNote;
    private final String coachDisplayName;
    private final Instant sessionStart;
    /** Null when the raiser has no reachable address — the listener then skips the email. */
    private final AdminNotificationRecipient recipient;
    /** feat/payment-forfeit-payouts-and-clawback: UPHOLD_COACH only (else null) — what it did, and who reads it. */
    private final UpholdCoachOutcome upholdOutcome;
    private final UpholdCoachOutcome.Audience audience;

    public DisputeResolvedEvent(Object source, UUID disputeId, UUID bookingId, String resolution, Long raisedBy) {
        this(source, disputeId, bookingId, resolution, raisedBy, null, null, null, null, null);
    }

    @SuppressWarnings("java:S107") // an immutable event: one field per fact the email states
    public DisputeResolvedEvent(Object source, UUID disputeId, UUID bookingId, String resolution, Long raisedBy,
                                BigDecimal creditAmount, String resolutionNote, String coachDisplayName,
                                Instant sessionStart, AdminNotificationRecipient recipient) {
        this(source, disputeId, bookingId, resolution, raisedBy, creditAmount, resolutionNote, coachDisplayName,
            sessionStart, recipient, null, null);
    }

    @SuppressWarnings("java:S107") // an immutable event: one field per fact the email states
    public DisputeResolvedEvent(Object source, UUID disputeId, UUID bookingId, String resolution, Long raisedBy,
                                BigDecimal creditAmount, String resolutionNote, String coachDisplayName,
                                Instant sessionStart, AdminNotificationRecipient recipient,
                                UpholdCoachOutcome upholdOutcome, UpholdCoachOutcome.Audience audience) {
        super(source);
        this.disputeId = disputeId;
        this.bookingId = bookingId;
        this.resolution = resolution;
        this.raisedBy = raisedBy;
        this.creditAmount = creditAmount;
        this.resolutionNote = resolutionNote;
        this.coachDisplayName = coachDisplayName;
        this.sessionStart = sessionStart;
        this.recipient = recipient;
        this.upholdOutcome = upholdOutcome;
        this.audience = audience;
    }

    public UUID getDisputeId() { return disputeId; }
    public UUID getBookingId() { return bookingId; }
    public String getResolution() { return resolution; }
    public Long getRaisedBy() { return raisedBy; }
    public BigDecimal getCreditAmount() { return creditAmount; }
    public String getResolutionNote() { return resolutionNote; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getSessionStart() { return sessionStart; }
    public AdminNotificationRecipient getRecipient() { return recipient; }
    public UpholdCoachOutcome getUpholdOutcome() { return upholdOutcome; }
    public UpholdCoachOutcome.Audience getAudience() { return audience; }
}
