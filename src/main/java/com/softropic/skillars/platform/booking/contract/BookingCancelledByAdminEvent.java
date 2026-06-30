package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.util.UUID;

public class BookingCancelledByAdminEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final UUID coachId;
    private final UUID sessionPackPurchaseId;
    private final BigDecimal sessionPrice;

    public BookingCancelledByAdminEvent(Object source, UUID bookingId, Long parentId,
            UUID coachId, UUID sessionPackPurchaseId, BigDecimal sessionPrice) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.coachId = coachId;
        this.sessionPackPurchaseId = sessionPackPurchaseId;
        this.sessionPrice = sessionPrice;
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public UUID getSessionPackPurchaseId() { return sessionPackPurchaseId; }
    public BigDecimal getSessionPrice() { return sessionPrice; }
}
