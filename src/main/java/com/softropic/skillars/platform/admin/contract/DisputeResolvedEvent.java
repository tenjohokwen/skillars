package com.softropic.skillars.platform.admin.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class DisputeResolvedEvent extends ApplicationEvent {

    private final UUID disputeId;
    private final UUID bookingId;
    private final String resolution;
    private final Long raisedBy;

    public DisputeResolvedEvent(Object source, UUID disputeId, UUID bookingId, String resolution, Long raisedBy) {
        super(source);
        this.disputeId = disputeId;
        this.bookingId = bookingId;
        this.resolution = resolution;
        this.raisedBy = raisedBy;
    }

    public UUID getDisputeId() { return disputeId; }
    public UUID getBookingId() { return bookingId; }
    public String getResolution() { return resolution; }
    public Long getRaisedBy() { return raisedBy; }
}
