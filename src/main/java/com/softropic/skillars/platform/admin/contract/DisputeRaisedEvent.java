package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class DisputeRaisedEvent extends ApplicationEvent {

    private final UUID disputeId;
    private final UUID bookingId;
    private final Long raisedBy;
    private final String reason;

    public DisputeRaisedEvent(Object source, UUID disputeId, UUID bookingId, Long raisedBy, String reason) {
        super(source);
        this.disputeId = disputeId;
        this.bookingId = bookingId;
        this.raisedBy = raisedBy;
        this.reason = reason;
    }

    public UUID getDisputeId() { return disputeId; }
    public UUID getBookingId() { return bookingId; }
    public Long getRaisedBy() { return raisedBy; }
    public String getReason() { return reason; }
}
